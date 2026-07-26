package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.core.TelemetryScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.util.List;
import java.util.Optional;

/**
 * Demonstracao de integracao da Fase 10 - Parte 2 (Debloat Adicional), via
 * console: os 10 servicos classicos da secao 2.1 (ja lidos pelo {@link
 * ServiceScanner} existente - aqui so confirmamos a leitura real + a
 * classificacao vinda da base de conhecimento), o Armazenamento Reservado
 * (secao 2.2, novo em {@link PerformanceScanner}), os 2 itens de privacidade
 * adicionais (secao 2.3, novos em {@link TelemetryScanner}) e o item de
 * interface do Menu Iniciar (secao 2.4, novo em {@link ConsumerFeatureScanner}).
 *
 * REGRAS DE SEGURANCA SEGUIDAS NESTE TESTE (mesmo padrao das fases anteriores):
 *  - A listagem dos 13 servicos e leitura pura (Get-Service via ServiceScanner), sem risco.
 *  - O round-trip real (ler original -> aplicar -> confirmar -> reverter -> confirmar com leitura
 *    DIRETA pos-restore) roda contra as chaves REAIS desta maquina para Telemetria/Consumidor/
 *    Armazenamento Reservado - mesma decisao ja tomada nas Fases 8 Parte 2/9 Parte 1 (nao ha motivo
 *    para fabricar uma chave de teste quando o objetivo e validar o mecanismo real).
 *  - Chaves em HKLM (Historico de Atividades, Localizacao) normalmente exigem Administrador para
 *    ESCREVER - se a escrita falhar por falta de permissao, isso e tratado como falha ESPERADA/
 *    documentada (nao e um bug), confirmando que nada foi alterado no registro.
 *  - Nenhum servico real foi parado/desativado neste teste - a Fase 10 Parte 2 so cataloga os
 *    servicos na base de conhecimento (o ActionExecutor.disableService/stopService ja foi validado
 *    nas Fases 1/2/3, reaproveitado sem mudanca aqui).
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase10Part2ConsoleDemo
 */
public final class Phase10Part2ConsoleDemo {

    private static final List<String> NEW_SERVICE_NAMES = List.of(
            "DiagTrack", "dmwappushservice", "PcaSvc", "RetailDemo", "MapsBroker",
            "WerSvc", "Fax", "TabletInputService", "WbioSrvc",
            "XblAuthManager", "XblGameSave", "XboxNetApiSvc", "XboxGipSvc"
    );

    private static final List<String> NEW_TELEMETRY_IDS = List.of(
            "activity_history_publish", "activity_history_upload", "location_tracking"
    );

    private Phase10Part2ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 10 Parte 2 (Debloat Adicional)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        ActionExecutor actionExecutor = new ActionExecutor(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);

        ServiceScanner serviceScanner = new ServiceScanner();
        PerformanceScanner performanceScanner = new PerformanceScanner();
        TelemetryScanner telemetryScanner = new TelemetryScanner();
        ConsumerFeatureScanner consumerFeatureScanner = new ConsumerFeatureScanner();

        section("10.2.1) Servicos classicos de telemetria/bloat - leitura real via ServiceScanner (ja existente)");
        listNewServices(serviceScanner);

        section("10.2.2) Armazenamento Reservado - leitura do estado real via Get-WindowsReservedStorageState");
        testReservedStorage(actionExecutor, performanceScanner);

        section("10.2.3) Privacidade adicional (Historico de Atividades / Localizacao) - round-trip real");
        for (String id : NEW_TELEMETRY_IDS) {
            testTelemetryRoundTrip(actionExecutor, telemetryScanner, findTelemetryById(id));
        }

        section("10.2.4) Interface - Secao 'Recomendado' no Menu Iniciar - round-trip real (chave HKCU)");
        testConsumerRoundTrip(actionExecutor, consumerFeatureScanner, findConsumerById("start_menu_recommended"));

        section("Historico completo de acoes (mais recente primeiro)");
        try {
            historyRepository.printRecentHistory(30);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 10 Parte 2");
    }

    // ------------------------------------------------------------------
    // 10.2.1) Servicos - leitura real, um por um (ja lidos pelo ServiceScanner desde a Fase 1)
    // ------------------------------------------------------------------

    private static void listNewServices(ServiceScanner scanner) {
        for (String serviceName : NEW_SERVICE_NAMES) {
            Optional<ServiceScanner.ServiceInfo> info = scanner.findByName(serviceName);
            if (info.isPresent()) {
                System.out.printf("    - %-20s estado=%-10s inicio=%-10s (nome exibido: %s)%n",
                        info.get().name(), info.get().state(), info.get().startMode(), info.get().displayName());
            } else {
                System.out.printf("    - %-20s nao encontrado nesta maquina (servico ausente/removido/nome de exibicao diferente)%n", serviceName);
            }
        }
    }

    // ------------------------------------------------------------------
    // 10.2.2) Armazenamento Reservado - leitura + round-trip se o cmdlet for suportado
    // ------------------------------------------------------------------

    private static void testReservedStorage(ActionExecutor actionExecutor, PerformanceScanner scanner) {
        PerformanceScanner.ReservedStorageStatus original = scanner.checkReservedStorageState();

        if (!original.supported()) {
            System.out.println("  [NAO APLICAVEL] Cmdlet 'Get-WindowsReservedStorageState' nao reconhecido/suportado "
                    + "nesta versao do Windows desta maquina - tratado como nao aplicavel, nao como erro.");
            return;
        }
        if (original.checkFailed()) {
            // O cmdlet EXISTE (supported=true), mas nem a LEITURA foi possivel sem elevacao nesta
            // maquina/sessao - diferente das demais categorias (telemetria/performance/jogos), onde
            // ler sempre funciona e so a ESCRITA exige Administrador. Ainda assim seguimos para
            // exercitar o caminho de escrita via ActionExecutor (mesmo padrao das demais chaves
            // HKLM desta fase) - a falha esperada por falta de elevacao sera confirmada abaixo.
            System.out.println("  [OBSERVACAO] Nao foi possivel LER o estado (cmdlet exige elevacao ate para leitura "
                    + "nesta maquina/sessao) - tentando mesmo assim a escrita via ActionExecutor, mesma logica "
                    + "das demais chaves HKLM desta fase.");
        } else {
            System.out.println("  Estado ORIGINAL: " + original.state());
        }

        boolean testTarget = "Disabled".equalsIgnoreCase(original.state()); // alterna para o estado oposto (ou tenta "Enabled" se desconhecido)
        ActionExecutor.ActionResult setResult = actionExecutor.setReservedStorageEnabled(testTarget);
        System.out.println("  setReservedStorageEnabled(" + testTarget + ") -> sucesso=" + setResult.success() + " | " + setResult.message());

        if (!setResult.success()) {
            PerformanceScanner.ReservedStorageStatus stillOriginal = scanner.checkReservedStorageState();
            boolean untouched = java.util.Objects.equals(original.state(), stillOriginal.state());
            System.out.println("  [ESPERADO SE SEM ADMIN] Alteracao falhou - " + (untouched
                    ? "confirmado que o Armazenamento Reservado NAO foi alterado."
                    : "[ATENCAO] o estado real mudou mesmo com a chamada reportando falha!"));
            return;
        }

        PerformanceScanner.ReservedStorageStatus afterSet = scanner.checkReservedStorageState();
        System.out.println("  Estado apos alterar: " + afterSet.state());

        ActionExecutor.ActionResult restoreResult = actionExecutor.restoreReservedStorageState(setResult.backupId());
        System.out.println("  restoreReservedStorageState() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

        PerformanceScanner.ReservedStorageStatus afterRestore = scanner.checkReservedStorageState();
        boolean restoredOk = java.util.Objects.equals(original.state(), afterRestore.state());
        System.out.println("  Estado apos reverter (leitura direta pos-restore): " + afterRestore.state());
        System.out.println("  [" + (restoredOk ? "OK" : "ATENCAO") + "] Maquina " + (restoredOk ? "confirmada restaurada ao estado original." : "NAO foi confirmada restaurada ao original!"));
    }

    // ------------------------------------------------------------------
    // 10.2.3) Telemetria/Privacidade - round trip real por chave, com confirmacao pos-restore
    // ------------------------------------------------------------------

    private static void testTelemetryRoundTrip(ActionExecutor actionExecutor, TelemetryScanner scanner,
                                                 TelemetryScanner.TelemetryKeyDefinition definition) {
        System.out.println();
        System.out.println("  --- " + definition.friendlyName() + " (" + definition.registryPath() + "\\" + definition.valueName() + ") ---");
        TelemetryScanner.TelemetryKeyInfo original = scanner.readValue(definition);
        System.out.println("    Valor ORIGINAL: " + describe(original.exists(), original.currentValue()));

        String testValue = "location_tracking".equals(definition.id()) ? "1" : "0";
        ActionExecutor.ActionResult setResult = actionExecutor.setTelemetryValue(definition, testValue);
        System.out.println("    setTelemetryValue(" + testValue + ") -> sucesso=" + setResult.success() + " | " + setResult.message());

        if (!setResult.success()) {
            // Esperado para as chaves HKLM desta secao (Historico de Atividades, Localizacao) sem elevacao.
            TelemetryScanner.TelemetryKeyInfo stillOriginal = scanner.readValue(definition);
            boolean untouched = sameValue(original, stillOriginal);
            System.out.println("    [ESPERADO SE HKLM SEM ADMIN] Escrita falhou - " + (untouched
                    ? "confirmado que NADA foi alterado no registro (valor real ainda = original)."
                    : "[ATENCAO] o valor real mudou mesmo com a chamada reportando falha!"));
            return;
        }

        TelemetryScanner.TelemetryKeyInfo afterSet = scanner.readValue(definition);
        System.out.println("    Valor apos alterar: " + describe(afterSet.exists(), afterSet.currentValue()));

        ActionExecutor.ActionResult restoreResult = actionExecutor.restoreTelemetryValue(setResult.backupId());
        System.out.println("    restoreTelemetryValue() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

        TelemetryScanner.TelemetryKeyInfo afterRestore = scanner.readValue(definition);
        boolean restoredOk = sameValue(original, afterRestore);
        System.out.println("    Valor apos reverter (leitura direta pos-restore): " + describe(afterRestore.exists(), afterRestore.currentValue()));
        System.out.println("    [" + (restoredOk ? "OK" : "ATENCAO") + "] Maquina " + (restoredOk ? "confirmada restaurada ao estado original." : "NAO foi confirmada restaurada ao original!"));
    }

    // ------------------------------------------------------------------
    // 10.2.4) Consumidor (Menu Iniciar) - round trip real, com confirmacao pos-restore
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
    // Utilitarios
    // ------------------------------------------------------------------

    private static TelemetryScanner.TelemetryKeyDefinition findTelemetryById(String id) {
        return TelemetryScanner.KNOWN_KEYS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao de telemetria '" + id + "' nao encontrada."));
    }

    private static ConsumerFeatureScanner.ConsumerFeatureKeyDefinition findConsumerById(String id) {
        return ConsumerFeatureScanner.KNOWN_KEYS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao de consumidor '" + id + "' nao encontrada."));
    }

    private static boolean sameValue(TelemetryScanner.TelemetryKeyInfo a, TelemetryScanner.TelemetryKeyInfo b) {
        if (a.exists() != b.exists()) {
            return false;
        }
        return !a.exists() || java.util.Objects.equals(a.currentValue(), b.currentValue());
    }

    private static boolean sameValue(ConsumerFeatureScanner.ConsumerFeatureKeyInfo a, ConsumerFeatureScanner.ConsumerFeatureKeyInfo b) {
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
