package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.util.List;
import java.util.Optional;

/**
 * Demonstracao de integracao da Fase 12 - Parte A (Ultimos Itens de Debloat),
 * via console: os 4 servicos de telemetria de GPU da secao A.1 (ja lidos pelo
 * {@link ServiceScanner} existente - so classificacao nova na base de
 * conhecimento), o Edge Startup Boost/Background Mode da secao A.3 (novo em
 * {@link ConsumerFeatureScanner}), o bloqueio de driver update via Windows
 * Update da secao A.5 (novo em {@link PerformanceScanner}), e a validacao da
 * logica de desinstalacao do OneDrive da secao A.4 (SEM executar a
 * desinstalacao de verdade).
 *
 * REGRAS DE SEGURANCA SEGUIDAS NESTE TESTE (mesmo padrao das fases anteriores):
 *  - A listagem dos 4 servicos de GPU e leitura pura (Get-CimInstance via ServiceScanner ja
 *    existente), sem risco. Esta maquina de desenvolvimento (notebook sem GPU dedicada) nao tem
 *    NENHUM dos 4 servicos instalado - tratado como resultado esperado/valido (servicos de
 *    terceiros, so classificados se existirem de verdade), nao como falha.
 *  - O round-trip real (ler original -> aplicar -> confirmar -> reverter -> confirmar com leitura
 *    DIRETA pos-restore) roda contra as chaves REAIS desta maquina para Edge/Driver Update - mesma
 *    decisao ja tomada nas fases anteriores (nao ha motivo para fabricar uma chave de teste quando
 *    o objetivo e validar o mecanismo real). Sao todas chaves HKLM - se a escrita falhar por falta
 *    de elevacao (Administrador), isso e tratado como falha ESPERADA/documentada (nao e um bug),
 *    confirmando que nada foi alterado no registro.
 *  - A desinstalacao do OneDrive (secao A.4) e a acao MAIS DRASTICA/IRREVERSIVEL implementada ate
 *    aqui no projeto - o metodo {@code uninstallOneDriveCompletely()} NUNCA e chamado neste teste
 *    em estado desbloqueado (o que poderia executar o comando de verdade, caso o instalador exista
 *    nesta maquina). A validacao aqui cobre: (1) resolucao dinamica do caminho do instalador
 *    (leitura de disco, sem risco), (2) a montagem do comando que SERIA executado (so texto, nunca
 *    rodado), e (3) o fluxo de bloqueio (chamando o metodo real, mas com o item travado de proposito
 *    - garantidamente seguro, ja que o {@code LockManager} e checado ANTES de qualquer resolucao de
 *    caminho ou execucao de comando dentro do metodo).
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase12PartAConsoleDemo
 */
public final class Phase12PartAConsoleDemo {

    private static final List<String> GPU_SERVICE_NAMES = List.of(
            "NvTelemetryContainer", "NVDisplay.ContainerLocalSystem",
            "AMD External Events Utility", "AMD Crash Defender Service"
    );

    private Phase12PartAConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 12 Parte A (Debloat Final)");

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
        ConsumerFeatureScanner consumerFeatureScanner = new ConsumerFeatureScanner();
        PerformanceScanner performanceScanner = new PerformanceScanner();

        section("A.1) Servicos de telemetria de fabricantes de GPU - leitura real via ServiceScanner (ja existente)");
        listGpuServices(serviceScanner);

        section("A.3) Edge - Startup Boost/Background Mode - round-trip real (chaves HKLM)");
        testConsumerRoundTrip(actionExecutor, consumerFeatureScanner, findConsumerById("edge_startup_boost"));
        testConsumerRoundTrip(actionExecutor, consumerFeatureScanner, findConsumerById("edge_background_mode"));

        section("A.5) Bloquear atualizacao de driver de video pelo Windows Update - round-trip real (chaves HKLM)");
        testPerformanceRoundTrip(actionExecutor, performanceScanner, findPerformanceById("block_driver_update_search"));
        testPerformanceRoundTrip(actionExecutor, performanceScanner, findPerformanceById("block_driver_update_prompt"));

        section("A.4) OneDrive - Desinstalacao Completa - validacao SEM executar a desinstalacao de verdade");
        testOneDriveUninstallLogic(actionExecutor, lockManager);

        section("Historico completo de acoes (mais recente primeiro)");
        try {
            historyRepository.printRecentHistory(30);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 12 Parte A");
    }

    // ------------------------------------------------------------------
    // A.1) Servicos de GPU - leitura real, um por um (ja lidos pelo ServiceScanner desde a Fase 1)
    // ------------------------------------------------------------------

    private static void listGpuServices(ServiceScanner scanner) {
        int found = 0;
        for (String serviceName : GPU_SERVICE_NAMES) {
            Optional<ServiceScanner.ServiceInfo> info = scanner.findByName(serviceName);
            if (info.isPresent()) {
                found++;
                System.out.printf("    - %-32s estado=%-10s inicio=%-10s (nome exibido: %s)%n",
                        info.get().name(), info.get().state(), info.get().startMode(), info.get().displayName());
            } else {
                System.out.printf("    - %-32s nao encontrado nesta maquina (esperado - servico de terceiro, "
                        + "so aparece se o driver NVIDIA/AMD correspondente estiver instalado)%n", serviceName);
            }
        }
        System.out.println("  Total encontrado nesta maquina: " + found + " de " + GPU_SERVICE_NAMES.size()
                + " (esta maquina de desenvolvimento nao tem GPU dedicada NVIDIA/AMD - resultado esperado).");
    }

    // ------------------------------------------------------------------
    // A.3) Consumidor (Edge) - round trip real, com confirmacao pos-restore
    // ------------------------------------------------------------------

    private static void testConsumerRoundTrip(ActionExecutor actionExecutor, ConsumerFeatureScanner scanner,
                                                ConsumerFeatureScanner.ConsumerFeatureKeyDefinition definition) {
        System.out.println();
        System.out.println("  --- " + definition.friendlyName() + " (" + definition.registryPath() + "\\" + definition.valueName() + ") ---");
        ConsumerFeatureScanner.ConsumerFeatureKeyInfo original = scanner.readValue(definition);
        System.out.println("    Valor ORIGINAL: " + describe(original.exists(), original.currentValue()));

        ActionExecutor.ActionResult setResult = actionExecutor.setConsumerFeatureValue(definition, definition.recommendedValue());
        System.out.println("    setConsumerFeatureValue(" + definition.recommendedValue() + ") -> sucesso=" + setResult.success() + " | " + setResult.message());

        if (!setResult.success()) {
            ConsumerFeatureScanner.ConsumerFeatureKeyInfo stillOriginal = scanner.readValue(definition);
            boolean untouched = sameValue(original, stillOriginal);
            System.out.println("    [ESPERADO SE SEM ADMIN] Escrita falhou - " + (untouched
                    ? "confirmado que NADA foi alterado no registro (valor real ainda = original)."
                    : "[ATENCAO] o valor real mudou mesmo com a chamada reportando falha!"));
            return;
        }

        ConsumerFeatureScanner.ConsumerFeatureKeyInfo afterSet = scanner.readValue(definition);
        System.out.println("    Valor apos alterar: " + describe(afterSet.exists(), afterSet.currentValue()));

        ActionExecutor.ActionResult restoreResult = actionExecutor.restoreConsumerFeatureValue(setResult.backupId());
        System.out.println("    restoreConsumerFeatureValue() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

        ConsumerFeatureScanner.ConsumerFeatureKeyInfo afterRestore = scanner.readValue(definition);
        boolean restoredOk = sameValue(original, afterRestore);
        System.out.println("    Valor apos reverter (leitura direta pos-restore): " + describe(afterRestore.exists(), afterRestore.currentValue()));
        System.out.println("    [" + (restoredOk ? "OK" : "ATENCAO") + "] Maquina " + (restoredOk ? "confirmada restaurada ao estado original." : "NAO foi confirmada restaurada ao original!"));
    }

    // ------------------------------------------------------------------
    // A.5) Performance (bloqueio de driver update) - round trip real, com confirmacao pos-restore
    // ------------------------------------------------------------------

    private static void testPerformanceRoundTrip(ActionExecutor actionExecutor, PerformanceScanner scanner,
                                                   PerformanceScanner.PerformanceKeyDefinition definition) {
        System.out.println();
        System.out.println("  --- " + definition.friendlyName() + " (" + definition.registryPath() + "\\" + definition.valueName() + ") ---");
        PerformanceScanner.PerformanceKeyInfo original = scanner.readValue(definition);
        System.out.println("    Valor ORIGINAL: " + describe(original.exists(), original.currentValue()));

        ActionExecutor.ActionResult setResult = actionExecutor.setPerformanceValue(definition, definition.recommendedValue());
        System.out.println("    setPerformanceValue(" + definition.recommendedValue() + ") -> sucesso=" + setResult.success() + " | " + setResult.message());

        if (!setResult.success()) {
            PerformanceScanner.PerformanceKeyInfo stillOriginal = scanner.readValue(definition);
            boolean untouched = sameValue(original, stillOriginal);
            System.out.println("    [ESPERADO SE SEM ADMIN] Escrita falhou - " + (untouched
                    ? "confirmado que NADA foi alterado no registro (valor real ainda = original)."
                    : "[ATENCAO] o valor real mudou mesmo com a chamada reportando falha!"));
            return;
        }

        PerformanceScanner.PerformanceKeyInfo afterSet = scanner.readValue(definition);
        System.out.println("    Valor apos alterar: " + describe(afterSet.exists(), afterSet.currentValue()));

        ActionExecutor.ActionResult restoreResult = actionExecutor.restorePerformanceValue(setResult.backupId());
        System.out.println("    restorePerformanceValue() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

        PerformanceScanner.PerformanceKeyInfo afterRestore = scanner.readValue(definition);
        boolean restoredOk = sameValue(original, afterRestore);
        System.out.println("    Valor apos reverter (leitura direta pos-restore): " + describe(afterRestore.exists(), afterRestore.currentValue()));
        System.out.println("    [" + (restoredOk ? "OK" : "ATENCAO") + "] Maquina " + (restoredOk ? "confirmada restaurada ao estado original." : "NAO foi confirmada restaurada ao original!"));
    }

    // ------------------------------------------------------------------
    // A.4) OneDrive - Desinstalacao Completa - validacao SEM executar a desinstalacao de verdade
    // ------------------------------------------------------------------

    private static final String ONEDRIVE_UNINSTALL_ITEM_NAME = "OneDrive - Desinstalacao Completa";
    private static final String ONEDRIVE_UNINSTALL_ITEM_TYPE = "onedrive_uninstall";

    private static void testOneDriveUninstallLogic(ActionExecutor actionExecutor, LockManager lockManager) {
        System.out.println();
        System.out.println("  [1/3] Resolucao dinamica do caminho do OneDriveSetup.exe (so leitura de disco, sem risco):");
        String setupPath = ActionExecutor.resolveOneDriveSetupPath();
        if (setupPath != null) {
            System.out.println("    Encontrado: " + setupPath);
            System.out.println("    Comando que SERIA executado (NAO executado neste teste): \"" + setupPath + "\" /uninstall");
        } else {
            System.out.println("    OneDriveSetup.exe NAO encontrado nem em SysWOW64 nem em System32 nesta maquina "
                    + "(resultado esperado - OneDrive per-usuario moderno normalmente instala em outro local, fora "
                    + "do escopo desta secao, ou o OneDrive ja nao esta presente nesta instalacao). Tratado como "
                    + "'nao aplicavel', nao como erro.");
        }

        System.out.println();
        System.out.println("  [2/3] Fluxo de bloqueio (Lock) - chamando o metodo REAL com o item travado de proposito, "
                + "seguro por construcao (o LockManager e checado ANTES de qualquer resolucao de caminho/execucao):");
        LockManager.LockResult lockResult = lockManager.lockItem(ONEDRIVE_UNINSTALL_ITEM_NAME, ONEDRIVE_UNINSTALL_ITEM_TYPE,
                "Bloqueado pelo Phase12PartAConsoleDemo para validar a recusa por bloqueio sem risco de execucao real.");
        System.out.println("    lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

        ActionExecutor.ActionResult attempt = actionExecutor.uninstallOneDriveCompletely();
        System.out.println("    uninstallOneDriveCompletely() [item bloqueado] -> sucesso=" + attempt.success() + " | " + attempt.message());
        boolean refusedCorrectly = !attempt.success() && attempt.message().toLowerCase(java.util.Locale.ROOT).contains("bloqueado");
        System.out.println("    [" + (refusedCorrectly ? "OK" : "ATENCAO") + "] " + (refusedCorrectly
                ? "Acao recusada corretamente por bloqueio - nenhum comando de desinstalacao foi executado."
                : "Resultado inesperado - revisar a logica de bloqueio."));

        LockManager.LockResult unlockResult = lockManager.unlockItem(ONEDRIVE_UNINSTALL_ITEM_NAME, ONEDRIVE_UNINSTALL_ITEM_TYPE);
        System.out.println("    unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());

        System.out.println();
        System.out.println("  [3/3] Desinstalacao real (desbloqueada) - NAO EXECUTADA de proposito neste teste automatizado:");
        System.out.println("    uninstallOneDriveCompletely() nao e chamado aqui com o item desbloqueado - e uma acao "
                + "irreversivel demais (remove o OneDrive por completo) para rodar sem supervisao humana direta. "
                + "A montagem do comando ja foi validada no passo [1/3] e o fluxo de bloqueio no passo [2/3]; a "
                + "execucao real e o modal de confirmacao extra-explicito (DestructiveActionConfirmation, so na UI) "
                + "ficam para o usuario acionar manualmente quando quiser, na tela real do NITRO BOOST.");
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private static ConsumerFeatureScanner.ConsumerFeatureKeyDefinition findConsumerById(String id) {
        return ConsumerFeatureScanner.KNOWN_KEYS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao de consumidor '" + id + "' nao encontrada."));
    }

    private static PerformanceScanner.PerformanceKeyDefinition findPerformanceById(String id) {
        return PerformanceScanner.KNOWN_KEYS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao de performance '" + id + "' nao encontrada."));
    }

    private static boolean sameValue(ConsumerFeatureScanner.ConsumerFeatureKeyInfo a, ConsumerFeatureScanner.ConsumerFeatureKeyInfo b) {
        if (a.exists() != b.exists()) {
            return false;
        }
        return !a.exists() || java.util.Objects.equals(a.currentValue(), b.currentValue());
    }

    private static boolean sameValue(PerformanceScanner.PerformanceKeyInfo a, PerformanceScanner.PerformanceKeyInfo b) {
        if (a.exists() != b.exists()) {
            return false;
        }
        return !a.exists() || java.util.Objects.equals(a.currentValue(), b.currentValue());
    }

    private static String describe(boolean exists, String value) {
        return exists ? value : "(nao definido)";
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
