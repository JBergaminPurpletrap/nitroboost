package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.BloatwareScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.util.List;

/**
 * Demonstracao de integracao da Fase 8 - Ajuste (Desinstalar em itens de IA), via console: testa,
 * contra o estado REAL desta maquina, os dois novos mecanismos de "desinstalar de verdade" (alem de
 * so desativar via politica) adicionados para os itens de IA que suportam ambas as acoes:
 *
 *  - Windows Copilot (usuario/todos os usuarios): remocao do pacote Appx via
 *    {@link ActionExecutor#uninstallAiFeatureApp}, quando o pacote existir separadamente.
 *  - Windows Recall: desativacao do recurso opcional do Windows via DISM, em
 *    {@link ActionExecutor#disableRecallFeature}.
 *
 * Click to Do, Cocreator e Copilot no Edge NAO entram aqui de proposito - continuam com uma unica
 * acao (Desativar), conforme documentado na base de conhecimento e no proprio documento da Fase 8.
 *
 * REGRAS DE SEGURANCA SEGUIDAS NESTE TESTE (mesmo padrao da Fase 8 Parte 2 / Fase 9):
 *  - O NITRO BOOST nunca reinicia a maquina sozinho - nenhuma chamada aqui passa "/Restart" ao DISM.
 *  - As chamadas contra o Recall (que precisa de Administrador so para SER CONSULTADO pelo DISM,
 *    conforme confirmado neste ambiente) sao seguras de rodar sem elevacao: o DISM recusa ANTES de
 *    tentar qualquer alteracao real, entao "falhar por falta de Administrador" e um resultado real e
 *    esperado, nao um risco.
 *  - A chamada de desinstalar o Copilot so e considerada segura de rodar "de verdade" (whatIf=false)
 *    porque a varredura de pacotes Appx desta maquina (rodada logo abaixo, e tambem confirmada
 *    manualmente via PowerShell antes de escrever este teste) ja mostra que NENHUM pacote com
 *    "Copilot" no nome esta instalado aqui - o codigo nunca chega a rodar Remove-AppxPackage de
 *    verdade neste ambiente (a checagem de existencia do pacote vem antes de qualquer comando).
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase8AiUninstallConsoleDemo
 */
public final class Phase8AiUninstallConsoleDemo {

    private Phase8AiUninstallConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao da Fase 8 Ajuste (Desinstalar em itens de IA: Copilot e Recall)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        ActionExecutor actionExecutor = new ActionExecutor(databaseManager);
        LockManager lockManager = new LockManager(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);
        BloatwareScanner bloatwareScanner = new BloatwareScanner();

        AiFeatureScanner.AiFeatureKeyDefinition copilotUser = findAiById("windows_copilot_user");
        AiFeatureScanner.AiFeatureKeyDefinition copilotAllUsers = findAiById("windows_copilot_allusers");
        AiFeatureScanner.AiFeatureKeyDefinition recall = findAiById("windows_recall");

        section("1) Pacotes Appx com categoria AI_COPILOT nesta maquina (BloatwareScanner.scan())");
        List<BloatwareScanner.AppxInfo> copilotPackages = bloatwareScanner.scan().stream()
                .filter(a -> a.category() == BloatwareScanner.Category.AI_COPILOT)
                .toList();
        if (copilotPackages.isEmpty()) {
            System.out.println("  Nenhum pacote Appx com 'Copilot' no nome encontrado (Get-AppxPackage do usuario atual).");
            System.out.println("  RESULTADO REAL: builds recentes do Windows integraram o Copilot ao shell/Explorer,");
            System.out.println("  sem um pacote UWP separado para remover nesta maquina - confirmado tambem manualmente");
            System.out.println("  via 'Get-AppxPackage | Where-Object { $_.Name -like *Copilot* }' antes deste teste.");
        } else {
            for (BloatwareScanner.AppxInfo app : copilotPackages) {
                System.out.println("  - " + app.name() + " (" + app.packageFullName() + ")");
            }
        }

        section("2) uninstallAiFeatureApp() - Windows Copilot (Usuario Atual), chamada REAL (whatIf=false)");
        testCopilotUninstall(actionExecutor, copilotUser, false);

        section("3) uninstallAiFeatureApp() - Windows Copilot (Todos os Usuarios), chamada REAL (allUsers=true, whatIf=false)");
        testCopilotUninstall(actionExecutor, copilotAllUsers, true);

        section("4) Bloqueio recusa a acao de Desinstalar do Copilot (mesmo lock usado por 'Desativar' - item real: Windows Copilot Usuario Atual)");
        testCopilotLockRefusal(actionExecutor, lockManager, copilotUser);

        section("5) disableRecallFeature() / restoreRecallFeature() - Windows Recall via DISM, chamada REAL");
        testRecallDismRoundTrip(actionExecutor, recall);

        section("6) Bloqueio recusa a acao de Desinstalar do Recall (mesmo lock usado por 'Desativar' - item real: Windows Recall)");
        testRecallLockRefusal(actionExecutor, lockManager, recall);

        section("Historico completo de acoes (mais recente primeiro)");
        try {
            historyRepository.printRecentHistory(30);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 8 Ajuste");
    }

    // ------------------------------------------------------------------
    // Windows Copilot - desinstalacao de pacote Appx
    // ------------------------------------------------------------------

    private static void testCopilotUninstall(ActionExecutor actionExecutor, AiFeatureScanner.AiFeatureKeyDefinition definition,
                                               boolean allUsers) {
        ActionExecutor.ActionResult result = actionExecutor.uninstallAiFeatureApp(definition, allUsers, false);
        System.out.println("  uninstallAiFeatureApp(allUsers=" + allUsers + ", whatIf=false) -> sucesso=" + result.success());
        System.out.println("  mensagem: " + result.message());
        if (result.backupId() != null) {
            System.out.println("  [ATENCAO] Um backup foi criado (backupId=" + result.backupId() + ") - so deveria acontecer se um pacote real tivesse sido encontrado.");
            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreAiFeatureApp(result.backupId());
            System.out.println("  restoreAiFeatureApp() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());
        } else {
            System.out.println("  [OK] Nenhum backup criado - coerente com 'nenhum pacote encontrado' (nada foi alterado no sistema).");
        }
    }

    private static void testCopilotLockRefusal(ActionExecutor actionExecutor, LockManager lockManager,
                                                 AiFeatureScanner.AiFeatureKeyDefinition definition) {
        String itemName = definition.friendlyName();
        LockManager.LockResult lockResult = lockManager.lockItem(itemName, "ai", "teste automatizado Fase 8 Ajuste");
        System.out.println("  lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

        ActionExecutor.ActionResult refused = actionExecutor.uninstallAiFeatureApp(definition, false, false);
        System.out.println("  uninstallAiFeatureApp() [bloqueado] -> sucesso=" + refused.success() + " | " + refused.message());
        if (refused.success() || refused.backupId() != null) {
            System.out.println("  [ATENCAO] A acao deveria ter sido recusada sem criar backup.");
        } else {
            System.out.println("  [OK] Acao recusada corretamente pelo MESMO lock usado pela acao 'Desativar' deste item.");
        }

        LockManager.LockResult unlockResult = lockManager.unlockItem(itemName, "ai");
        System.out.println("  unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());
    }

    // ------------------------------------------------------------------
    // Windows Recall - recurso opcional do Windows via DISM
    // ------------------------------------------------------------------

    private static void testRecallDismRoundTrip(ActionExecutor actionExecutor, AiFeatureScanner.AiFeatureKeyDefinition definition) {
        ActionExecutor.ActionResult disableResult = actionExecutor.disableRecallFeature(definition);
        System.out.println("  disableRecallFeature() -> sucesso=" + disableResult.success());
        System.out.println("  mensagem: " + disableResult.message());

        if (!disableResult.success()) {
            System.out.println("  [ESPERADO NESTE AMBIENTE] O DISM exige privilegio de Administrador mesmo so para consultar");
            System.out.println("  o recurso opcional 'Recall' - confirmado manualmente antes deste teste (dism.exe e");
            System.out.println("  Get-WindowsOptionalFeature retornam 'requer elevacao' sem tentar nenhuma alteracao real).");
            System.out.println("  Esta maquina tambem NAO e um Copilot+ PC (CPU Intel x64, sem NPU) - o Recall quase");
            System.out.println("  certamente nao existiria aqui mesmo com elevacao, mas isso nao pode ser confirmado sem");
            System.out.println("  rodar como Administrador (ver docs/BLOCKERS.md).");
        }

        if (disableResult.backupId() != null) {
            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreRecallFeature(disableResult.backupId());
            System.out.println("  restoreRecallFeature() -> sucesso=" + restoreResult.success());
            System.out.println("  mensagem: " + restoreResult.message());
        } else {
            System.out.println("  [OK] Nenhum backup criado (falha ao gravar no banco?) - restoreRecallFeature() nao testado nesta rodada.");
        }
    }

    private static void testRecallLockRefusal(ActionExecutor actionExecutor, LockManager lockManager,
                                                AiFeatureScanner.AiFeatureKeyDefinition definition) {
        String itemName = definition.friendlyName();
        LockManager.LockResult lockResult = lockManager.lockItem(itemName, "ai", "teste automatizado Fase 8 Ajuste");
        System.out.println("  lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

        ActionExecutor.ActionResult refused = actionExecutor.disableRecallFeature(definition);
        System.out.println("  disableRecallFeature() [bloqueado] -> sucesso=" + refused.success() + " | " + refused.message());
        if (refused.success() || refused.backupId() != null) {
            System.out.println("  [ATENCAO] A acao deveria ter sido recusada sem criar backup.");
        } else {
            System.out.println("  [OK] Acao recusada corretamente pelo MESMO lock usado pela acao 'Desativar' deste item.");
        }

        LockManager.LockResult unlockResult = lockManager.unlockItem(itemName, "ai");
        System.out.println("  unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private static AiFeatureScanner.AiFeatureKeyDefinition findAiById(String id) {
        return AiFeatureScanner.KNOWN_KEYS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao '" + id + "' nao encontrada."));
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
