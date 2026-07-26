package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.util.List;
import java.util.Optional;

/**
 * Demonstracao de integracao da Fase 12 - Parte B (Suite de Testes de Validacao), secao B.2
 * (checklist de teste manual) - cobre os itens "Backup/Reversao", "Bloqueio" e "Historico" do
 * checklist, contra um servico REAL desta maquina (nao um item de teste fabricado, ja que
 * servicos do Windows nao podem ser criados ad-hoc como uma entrada de registro de teste).
 *
 * SERVICO ESCOLHIDO: o documento da Fase 12 sugere usar o servico "Fax" como alvo - esta maquina
 * (Windows 11 24H2/build 26200) NAO TEM o servico Fax instalado (confirmado via
 * {@code Get-Service Fax} -> "Cannot find any service with service name 'Fax'"), entao foi usado
 * "MapsBroker" (Gerenciador de Mapas Baixados) como substituto: classificado como "seguro" na base
 * de conhecimento, StartType=Automatic (delay-start) mas Status=Stopped nesta maquina (raramente
 * usado em desktop/notebook sem o app Mapas ativo) - impacto minimo mesmo que a mudanca nao seja
 * revertida por algum motivo.
 *
 * LIMITACAO DE AMBIENTE (mesma classe ja documentada em BLOCKERS.md itens 6/7/9/10): esta sessao de
 * console NAO esta elevada (Administrador) - confirmado via
 * {@code ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(...Administrator)}
 * -> False. {@code Set-Service -StartupType} exige elevacao para ESCREVER a configuracao do
 * servico, entao o round-trip de sucesso completo (desativar -> confirmar parado -> reverter ->
 * confirmar original) NAO pode ser validado de ponta a ponta nesta sessao - o caminho de FALHA
 * gracioso (backup criado, comando tentado, falha tratada sem excecao, nada alterado no sistema,
 * historico registrado) e o que este teste confirma, e e o mesmo comportamento ja validado
 * exaustivamente para as demais escritas em HKLM/servicos desde a Fase 9.
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase12PartBConsoleDemo
 */
public final class Phase12PartBConsoleDemo {

    private static final String SERVICE_NAME = "MapsBroker";
    private static final String SERVICE_TYPE = "service";

    private Phase12PartBConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 12 Parte B (Checklist Manual B.2)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        ActionExecutor actionExecutor = new ActionExecutor(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);
        LockManager lockManager = new LockManager(databaseManager);
        ServiceScanner serviceScanner = new ServiceScanner();

        section("Bloqueio: lockItem('" + SERVICE_NAME + "') -> tentar desativar -> confirmar recusa -> desbloquear");
        Optional<ServiceScanner.ServiceInfo> originalInfo = serviceScanner.findByName(SERVICE_NAME);
        System.out.println("Estado ORIGINAL (leitura direta): " + describe(originalInfo));

        LockManager.LockResult lockResult = lockManager.lockItem(SERVICE_NAME, SERVICE_TYPE,
                "Bloqueado pelo Phase12PartBConsoleDemo para reconfirmar a recusa por bloqueio (B.2).");
        System.out.println("lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

        ActionExecutor.ActionResult blockedAttempt = actionExecutor.disableService(SERVICE_NAME);
        System.out.println("disableService() [item bloqueado] -> sucesso=" + blockedAttempt.success() + " | " + blockedAttempt.message());
        boolean refusedCorrectly = !blockedAttempt.success()
                && blockedAttempt.message().toLowerCase(java.util.Locale.ROOT).contains("bloqueado");
        System.out.println("[" + (refusedCorrectly ? "OK" : "ATENCAO") + "] " + (refusedCorrectly
                ? "Acao recusada corretamente por bloqueio - nenhum comando foi executado, servico intacto."
                : "Resultado inesperado - revisar a logica de bloqueio."));

        Optional<ServiceScanner.ServiceInfo> afterBlockedAttempt = serviceScanner.findByName(SERVICE_NAME);
        System.out.println("Estado apos tentativa recusada (leitura direta): " + describe(afterBlockedAttempt));

        LockManager.LockResult unlockResult = lockManager.unlockItem(SERVICE_NAME, SERVICE_TYPE);
        System.out.println("unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());

        section("Backup/Reversao: disableService('" + SERVICE_NAME + "') desbloqueado -> confirmar -> restoreService()");
        ActionExecutor.ActionResult disableResult = actionExecutor.disableService(SERVICE_NAME);
        System.out.println("disableService() [desbloqueado] -> sucesso=" + disableResult.success() + " | " + disableResult.message());

        if (!disableResult.success()) {
            Optional<ServiceScanner.ServiceInfo> stillOriginal = serviceScanner.findByName(SERVICE_NAME);
            boolean untouched = describe(stillOriginal).equals(describe(originalInfo));
            System.out.println("[ESPERADO SEM ADMIN NESTA SESSAO] Escrita falhou (Set-Service exige elevacao) - "
                    + (untouched ? "confirmado via leitura direta que NADA foi alterado no servico."
                                 : "[ATENCAO] o estado real mudou mesmo com a chamada reportando falha!"));
        } else {
            Optional<ServiceScanner.ServiceInfo> afterDisable = serviceScanner.findByName(SERVICE_NAME);
            System.out.println("Estado apos desativar (leitura direta): " + describe(afterDisable));
        }

        if (disableResult.backupId() != null) {
            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreService(disableResult.backupId());
            System.out.println("restoreService(#" + disableResult.backupId() + ") -> sucesso=" + restoreResult.success()
                    + " | " + restoreResult.message());
        } else {
            System.out.println("Nenhum backup foi criado (falha ocorreu antes da criacao do backup) - nada a reverter.");
        }

        Optional<ServiceScanner.ServiceInfo> finalInfo = serviceScanner.findByName(SERVICE_NAME);
        boolean restoredToOriginal = describe(finalInfo).equals(describe(originalInfo));
        System.out.println("Estado FINAL (leitura direta): " + describe(finalInfo));
        System.out.println("[" + (restoredToOriginal ? "OK" : "ATENCAO") + "] Servico "
                + (restoredToOriginal ? "confirmado no mesmo estado de antes do teste." : "NAO esta no mesmo estado original!"));

        section("Historico: confirmar que as acoes acima aparecem em actions_history com timestamp");
        try {
            List<ActionHistoryRepository.HistoryEntry> recent = historyRepository.findRecent(10);
            for (ActionHistoryRepository.HistoryEntry entry : recent) {
                if (SERVICE_NAME.equalsIgnoreCase(entry.itemName())) {
                    System.out.printf("  #%-4d %-10s acao=%-10s sucesso=%-5s quando=%-20s previo=%s%n",
                            entry.id(), entry.itemType(), entry.actionType(), entry.success(),
                            entry.performedAt(), entry.previousState());
                }
            }
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 12 Parte B (checklist manual B.2)");
    }

    private static String describe(Optional<ServiceScanner.ServiceInfo> info) {
        return info.map(s -> s.state() + "/" + s.startMode()).orElse("(servico nao encontrado)");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
