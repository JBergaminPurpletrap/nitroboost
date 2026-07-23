package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.GamingScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.util.List;

/**
 * Demonstracao de integracao da Fase 9 - Parte 1 (Novos scanners de
 * Performance e Jogos), via console: {@link PerformanceScanner} (6 chaves de
 * registro + arquivo de hibernacao) e {@link GamingScanner} (4 chaves de
 * registro).
 *
 * REGRAS DE SEGURANCA SEGUIDAS NESTE TESTE (ver instrucoes da Fase 9):
 *  - Todas as listagens sao leitura pura do registro/disco real desta maquina, sem risco.
 *  - Diferente da Fase 3 (que usou uma chave de registro FABRICADA para testar telemetria), aqui o
 *    teste de "alterar com backup" roda contra as chaves REAIS de Performance/Jogos, porque o
 *    proposito explicito desta fase e validar o comportamento em cima dos mecanismos reais (efeitos
 *    visuais, transparencia, GPU scheduling, hibernacao etc.) - cada alteracao real segue sempre o
 *    mesmo padrao: (1) ler o valor ORIGINAL antes de qualquer mudanca, (2) aplicar a mudanca de
 *    teste via ActionExecutor (o mesmo caminho de codigo usado em producao), (3) confirmar a
 *    mudanca, (4) reverter via ActionExecutor.restore*, (5) confirmar - com uma LEITURA DIRETA pos-
 *    restore (nao so confiar no "success=true" da chamada) - que a maquina voltou exatamente ao
 *    estado original.
 *  - Chaves em HKLM (GPU scheduling, Fast Startup, Network Throttling, Delivery Optimization,
 *    Prioridade de CPU para jogos) normalmente exigem privilegio de Administrador para ESCREVER
 *    (a leitura funciona sem elevacao). Se a escrita falhar por falta de permissao neste ambiente de
 *    desenvolvimento, isso e tratado como falha ESPERADA/documentada (nao e um bug) - o teste
 *    confirma que a logica do comando foi montada corretamente e que NADA foi alterado na maquina
 *    quando a escrita falha (leitura direta pos-tentativa continua identica ao valor original).
 *  - O arquivo de hibernacao (powercfg /hibernate on/off) tambem normalmente exige elevacao -
 *    mesma logica acima.
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase9Part1ConsoleDemo
 */
public final class Phase9Part1ConsoleDemo {

    private Phase9Part1ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 9 Parte 1 (Performance e Jogos)");

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

        PerformanceScanner performanceScanner = new PerformanceScanner();
        GamingScanner gamingScanner = new GamingScanner();

        listPerformance(performanceScanner);
        listGaming(gamingScanner);

        section("9.1) PerformanceScanner - bloqueio recusa a acao, desbloqueio permite (item real: Transparencia)");
        testLockRefusal(actionExecutor, lockManager, performanceScanner);

        section("9.2) PerformanceScanner - alterar/reverter as 6 chaves reais, com confirmacao pos-restore");
        for (PerformanceScanner.PerformanceKeyDefinition definition : PerformanceScanner.KNOWN_KEYS) {
            testPerformanceRoundTrip(actionExecutor, performanceScanner, definition);
        }

        section("9.3) PerformanceScanner - arquivo de hibernacao (powercfg /hibernate) com confirmacao pos-restore");
        testHibernationRoundTrip(actionExecutor, performanceScanner);

        section("9.4) GamingScanner - alterar/reverter as 4 chaves reais, com confirmacao pos-restore");
        for (GamingScanner.GamingKeyDefinition definition : GamingScanner.KNOWN_KEYS) {
            testGamingRoundTrip(actionExecutor, gamingScanner, definition);
        }

        section("Historico completo de acoes (mais recente primeiro)");
        try {
            historyRepository.printRecentHistory(40);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 9 Parte 1");
    }

    private static void listPerformance(PerformanceScanner scanner) {
        section("PerformanceScanner.scan() - leitura real das 6 chaves conhecidas + arquivo de hibernacao");
        List<PerformanceScanner.PerformanceKeyInfo> keys = scanner.scan();
        for (PerformanceScanner.PerformanceKeyInfo info : keys) {
            System.out.printf("    - %-58s valor=%-14s existe=%s%n",
                    info.definition().friendlyName(), info.exists() ? info.currentValue() : "(nao definido)", info.exists());
        }
        PerformanceScanner.HibernationStatus hibernation = scanner.checkHibernationFile();
        System.out.println("    - Arquivo de hibernacao (" + hibernation.filePath() + "): "
                + (hibernation.checkFailed() ? "nao foi possivel verificar" : (hibernation.fileExists() ? "existe (habilitada)" : "nao existe (desabilitada)")));
    }

    private static void listGaming(GamingScanner scanner) {
        section("GamingScanner.scan() - leitura real das 4 chaves conhecidas");
        List<GamingScanner.GamingKeyInfo> keys = scanner.scan();
        for (GamingScanner.GamingKeyInfo info : keys) {
            System.out.printf("    - %-58s valor=%-14s existe=%s%n",
                    info.definition().friendlyName(), info.exists() ? info.currentValue() : "(nao definido)", info.exists());
        }
    }

    // ------------------------------------------------------------------
    // 9.1) Bloqueio/desbloqueio contra um item REAL (nunca toca no registro, so a tabela locks)
    // ------------------------------------------------------------------

    private static void testLockRefusal(ActionExecutor actionExecutor, LockManager lockManager, PerformanceScanner scanner) {
        PerformanceScanner.PerformanceKeyDefinition definition = findById(PerformanceScanner.KNOWN_KEYS, "enable_transparency");
        String itemName = definition.friendlyName();

        LockManager.LockResult lockResult = lockManager.lockItem(itemName, "performance", "teste automatizado Fase 9 Parte 1");
        System.out.println("  lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

        PerformanceScanner.PerformanceKeyInfo before = scanner.readValue(definition);
        ActionExecutor.ActionResult refused = actionExecutor.setPerformanceValue(definition, definition.recommendedValue());
        System.out.println("  setPerformanceValue() [bloqueado] -> sucesso=" + refused.success() + " | " + refused.message());
        if (refused.success() || refused.backupId() != null) {
            System.out.println("  [ATENCAO] A acao deveria ter sido recusada sem criar backup.");
        } else {
            System.out.println("  [OK] Acao recusada corretamente: nenhum backup criado, nada alterado no registro.");
        }
        PerformanceScanner.PerformanceKeyInfo afterRefusal = scanner.readValue(definition);
        boolean untouched = sameValue(before, afterRefusal);
        System.out.println("  [" + (untouched ? "OK" : "ATENCAO") + "] Valor real no registro " + (untouched ? "permanece identico" : "MUDOU") + " apos a tentativa recusada.");

        LockManager.LockResult unlockResult = lockManager.unlockItem(itemName, "performance");
        System.out.println("  unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());
    }

    // ------------------------------------------------------------------
    // 9.2) Performance - round trip real por chave, com confirmacao pos-restore
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
            // Esperado para chaves HKLM sem elevacao (Fast Startup, GPU scheduling, Network
            // Throttling, Delivery Optimization) - documentado como bloqueio conhecido, nao um bug.
            PerformanceScanner.PerformanceKeyInfo stillOriginal = scanner.readValue(definition);
            boolean untouched = sameValue(original, stillOriginal);
            System.out.println("    [ESPERADO SE HKLM SEM ADMIN] Escrita falhou - " + (untouched
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
    // 9.3) Arquivo de hibernacao - round trip real, com confirmacao pos-restore
    // ------------------------------------------------------------------

    private static void testHibernationRoundTrip(ActionExecutor actionExecutor, PerformanceScanner scanner) {
        PerformanceScanner.HibernationStatus original = scanner.checkHibernationFile();
        System.out.println("  Estado ORIGINAL: " + (original.fileExists() ? "ativado (arquivo presente)" : "desativado (arquivo ausente)"));

        boolean testTarget = !original.fileExists(); // testa alternar para o estado oposto
        ActionExecutor.ActionResult setResult = actionExecutor.setHibernationEnabled(testTarget);
        System.out.println("  setHibernationEnabled(" + testTarget + ") -> sucesso=" + setResult.success() + " | " + setResult.message());

        if (!setResult.success()) {
            PerformanceScanner.HibernationStatus stillOriginal = scanner.checkHibernationFile();
            boolean untouched = stillOriginal.fileExists() == original.fileExists();
            System.out.println("  [ESPERADO SE SEM ADMIN] Alteracao falhou - " + (untouched
                    ? "confirmado que o arquivo de hibernacao NAO foi alterado."
                    : "[ATENCAO] o estado real mudou mesmo com a chamada reportando falha!"));
            return;
        }

        PerformanceScanner.HibernationStatus afterSet = scanner.checkHibernationFile();
        System.out.println("  Estado apos alterar: " + (afterSet.fileExists() ? "ativado" : "desativado"));

        ActionExecutor.ActionResult restoreResult = actionExecutor.restoreHibernationState(setResult.backupId());
        System.out.println("  restoreHibernationState() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

        PerformanceScanner.HibernationStatus afterRestore = scanner.checkHibernationFile();
        boolean restoredOk = afterRestore.fileExists() == original.fileExists();
        System.out.println("  Estado apos reverter (leitura direta pos-restore): " + (afterRestore.fileExists() ? "ativado" : "desativado"));
        System.out.println("  [" + (restoredOk ? "OK" : "ATENCAO") + "] Maquina " + (restoredOk ? "confirmada restaurada ao estado original." : "NAO foi confirmada restaurada ao original!"));
    }

    // ------------------------------------------------------------------
    // 9.4) Jogos - round trip real por chave, com confirmacao pos-restore
    // ------------------------------------------------------------------

    private static void testGamingRoundTrip(ActionExecutor actionExecutor, GamingScanner scanner,
                                             GamingScanner.GamingKeyDefinition definition) {
        System.out.println();
        System.out.println("  --- " + definition.friendlyName() + " (" + definition.registryPath() + "\\" + definition.valueName() + ") ---");
        GamingScanner.GamingKeyInfo original = scanner.readValue(definition);
        System.out.println("    Valor ORIGINAL: " + describe(original.exists(), original.currentValue()));

        ActionExecutor.ActionResult setResult = actionExecutor.setGamingValue(definition, definition.recommendedValue());
        System.out.println("    setGamingValue(" + definition.recommendedValue() + ") -> sucesso=" + setResult.success() + " | " + setResult.message());

        if (!setResult.success()) {
            // Esperado para a chave HKLM (Prioridade de Processador) sem elevacao.
            GamingScanner.GamingKeyInfo stillOriginal = scanner.readValue(definition);
            boolean untouched = sameValue(original, stillOriginal);
            System.out.println("    [ESPERADO SE HKLM SEM ADMIN] Escrita falhou - " + (untouched
                    ? "confirmado que NADA foi alterado no registro (valor real ainda = original)."
                    : "[ATENCAO] o valor real mudou mesmo com a chamada reportando falha!"));
            return;
        }

        GamingScanner.GamingKeyInfo afterSet = scanner.readValue(definition);
        System.out.println("    Valor apos alterar: " + describe(afterSet.exists(), afterSet.currentValue()));

        ActionExecutor.ActionResult restoreResult = actionExecutor.restoreGamingValue(setResult.backupId());
        System.out.println("    restoreGamingValue() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

        GamingScanner.GamingKeyInfo afterRestore = scanner.readValue(definition);
        boolean restoredOk = sameValue(original, afterRestore);
        System.out.println("    Valor apos reverter (leitura direta pos-restore): " + describe(afterRestore.exists(), afterRestore.currentValue()));
        System.out.println("    [" + (restoredOk ? "OK" : "ATENCAO") + "] Maquina " + (restoredOk ? "confirmada restaurada ao estado original." : "NAO foi confirmada restaurada ao original!"));
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private static PerformanceScanner.PerformanceKeyDefinition findById(List<PerformanceScanner.PerformanceKeyDefinition> list, String id) {
        return list.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao '" + id + "' nao encontrada."));
    }

    private static boolean sameValue(PerformanceScanner.PerformanceKeyInfo a, PerformanceScanner.PerformanceKeyInfo b) {
        if (a.exists() != b.exists()) {
            return false;
        }
        return !a.exists() || java.util.Objects.equals(a.currentValue(), b.currentValue());
    }

    private static boolean sameValue(GamingScanner.GamingKeyInfo a, GamingScanner.GamingKeyInfo b) {
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
