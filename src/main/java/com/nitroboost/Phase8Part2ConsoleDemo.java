package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.util.List;

/**
 * Demonstracao de integracao da Fase 8 - Parte 2 (Debloat Real Completo,
 * incluindo IAs do Windows 11), via console: {@link AiFeatureScanner} (7
 * chaves de registro/politica) e {@link ConsumerFeatureScanner} (12 chaves de
 * registro).
 *
 * REGRAS DE SEGURANCA SEGUIDAS NESTE TESTE (mesmo padrao da Fase 9 Parte 1):
 *  - Todas as listagens sao leitura pura do registro real desta maquina, sem risco.
 *  - O teste de "alterar com backup" roda contra as chaves REAIS de IA/Consumidor, seguindo sempre:
 *    (1) ler o valor ORIGINAL antes de qualquer mudanca, (2) aplicar a mudanca via ActionExecutor (o
 *    mesmo caminho de codigo usado em producao), (3) confirmar a mudanca, (4) reverter via
 *    ActionExecutor.restore*, (5) confirmar - com uma LEITURA DIRETA pos-restore (nao so confiar no
 *    "success=true" da chamada) - que a maquina voltou exatamente ao estado original.
 *  - Chaves em HKLM (Windows Copilot para todos os usuarios, Recall/Click to Do/Cocreator, Copilot no
 *    Edge, Pesquisa do Bing via politica, Apps em Segundo Plano) normalmente exigem privilegio de
 *    Administrador para ESCREVER (a leitura funciona sem elevacao). Se a escrita falhar por falta de
 *    permissao neste ambiente de desenvolvimento, isso e tratado como falha ESPERADA/documentada (nao
 *    e um bug) - o teste confirma que a logica do comando foi montada corretamente e que NADA foi
 *    alterado na maquina quando a escrita falha (leitura direta pos-tentativa continua identica ao
 *    valor original).
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase8Part2ConsoleDemo
 */
public final class Phase8Part2ConsoleDemo {

    private Phase8Part2ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 8 Parte 2 (IA e Recursos de Consumidor)");

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

        AiFeatureScanner aiScanner = new AiFeatureScanner();
        ConsumerFeatureScanner consumerScanner = new ConsumerFeatureScanner();

        listAi(aiScanner);
        listConsumer(consumerScanner);

        section("8.2.1) AiFeatureScanner - bloqueio recusa a acao, desbloqueio permite (item real: Botao do Copilot na Barra de Tarefas)");
        testAiLockRefusal(actionExecutor, lockManager, aiScanner);

        section("8.2.2) AiFeatureScanner - alterar/reverter as 7 chaves reais, com confirmacao pos-restore");
        for (AiFeatureScanner.AiFeatureKeyDefinition definition : AiFeatureScanner.KNOWN_KEYS) {
            testAiRoundTrip(actionExecutor, aiScanner, definition);
        }

        section("8.2.3) ConsumerFeatureScanner - bloqueio recusa a acao, desbloqueio permite (item real: Sugestoes e Anuncios no Menu Iniciar)");
        testConsumerLockRefusal(actionExecutor, lockManager, consumerScanner);

        section("8.2.4) ConsumerFeatureScanner - alterar/reverter as 12 chaves reais, com confirmacao pos-restore");
        for (ConsumerFeatureScanner.ConsumerFeatureKeyDefinition definition : ConsumerFeatureScanner.KNOWN_KEYS) {
            testConsumerRoundTrip(actionExecutor, consumerScanner, definition);
        }

        section("Historico completo de acoes (mais recente primeiro)");
        try {
            historyRepository.printRecentHistory(50);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 8 Parte 2");
    }

    private static void listAi(AiFeatureScanner scanner) {
        section("AiFeatureScanner.scan() - leitura real das 7 chaves conhecidas (Copilot/Recall/Click to Do/Cocreator/Edge)");
        List<AiFeatureScanner.AiFeatureKeyInfo> keys = scanner.scan();
        for (AiFeatureScanner.AiFeatureKeyInfo info : keys) {
            System.out.printf("    - %-58s valor=%-14s existe=%s%n",
                    info.definition().friendlyName(), info.exists() ? info.currentValue() : "(nao definido)", info.exists());
        }
    }

    private static void listConsumer(ConsumerFeatureScanner scanner) {
        section("ConsumerFeatureScanner.scan() - leitura real das 12 chaves conhecidas (anuncios/sugestoes + segundo plano)");
        List<ConsumerFeatureScanner.ConsumerFeatureKeyInfo> keys = scanner.scan();
        for (ConsumerFeatureScanner.ConsumerFeatureKeyInfo info : keys) {
            System.out.printf("    - %-58s valor=%-14s existe=%s%n",
                    info.definition().friendlyName(), info.exists() ? info.currentValue() : "(nao definido)", info.exists());
        }
    }

    // ------------------------------------------------------------------
    // Bloqueio/desbloqueio contra um item REAL (nunca toca no registro, so a tabela locks)
    // ------------------------------------------------------------------

    private static void testAiLockRefusal(ActionExecutor actionExecutor, LockManager lockManager, AiFeatureScanner scanner) {
        AiFeatureScanner.AiFeatureKeyDefinition definition = findAiById("copilot_taskbar_button");
        String itemName = definition.friendlyName();

        LockManager.LockResult lockResult = lockManager.lockItem(itemName, "ai", "teste automatizado Fase 8 Parte 2");
        System.out.println("  lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

        AiFeatureScanner.AiFeatureKeyInfo before = scanner.readValue(definition);
        ActionExecutor.ActionResult refused = actionExecutor.setAiFeatureValue(definition, definition.recommendedValue());
        System.out.println("  setAiFeatureValue() [bloqueado] -> sucesso=" + refused.success() + " | " + refused.message());
        if (refused.success() || refused.backupId() != null) {
            System.out.println("  [ATENCAO] A acao deveria ter sido recusada sem criar backup.");
        } else {
            System.out.println("  [OK] Acao recusada corretamente: nenhum backup criado, nada alterado no registro.");
        }
        AiFeatureScanner.AiFeatureKeyInfo afterRefusal = scanner.readValue(definition);
        boolean untouched = sameValue(before, afterRefusal);
        System.out.println("  [" + (untouched ? "OK" : "ATENCAO") + "] Valor real no registro " + (untouched ? "permanece identico" : "MUDOU") + " apos a tentativa recusada.");

        LockManager.LockResult unlockResult = lockManager.unlockItem(itemName, "ai");
        System.out.println("  unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());
    }

    private static void testConsumerLockRefusal(ActionExecutor actionExecutor, LockManager lockManager, ConsumerFeatureScanner scanner) {
        ConsumerFeatureScanner.ConsumerFeatureKeyDefinition definition = findConsumerById("start_menu_suggestions");
        String itemName = definition.friendlyName();

        LockManager.LockResult lockResult = lockManager.lockItem(itemName, "consumer", "teste automatizado Fase 8 Parte 2");
        System.out.println("  lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

        ConsumerFeatureScanner.ConsumerFeatureKeyInfo before = scanner.readValue(definition);
        ActionExecutor.ActionResult refused = actionExecutor.setConsumerFeatureValue(definition, definition.recommendedValue());
        System.out.println("  setConsumerFeatureValue() [bloqueado] -> sucesso=" + refused.success() + " | " + refused.message());
        if (refused.success() || refused.backupId() != null) {
            System.out.println("  [ATENCAO] A acao deveria ter sido recusada sem criar backup.");
        } else {
            System.out.println("  [OK] Acao recusada corretamente: nenhum backup criado, nada alterado no registro.");
        }
        ConsumerFeatureScanner.ConsumerFeatureKeyInfo afterRefusal = scanner.readValue(definition);
        boolean untouched = sameValue(before, afterRefusal);
        System.out.println("  [" + (untouched ? "OK" : "ATENCAO") + "] Valor real no registro " + (untouched ? "permanece identico" : "MUDOU") + " apos a tentativa recusada.");

        LockManager.LockResult unlockResult = lockManager.unlockItem(itemName, "consumer");
        System.out.println("  unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());
    }

    // ------------------------------------------------------------------
    // Round trip real por chave, com confirmacao pos-restore
    // ------------------------------------------------------------------

    private static void testAiRoundTrip(ActionExecutor actionExecutor, AiFeatureScanner scanner,
                                         AiFeatureScanner.AiFeatureKeyDefinition definition) {
        System.out.println();
        System.out.println("  --- " + definition.friendlyName() + " (" + definition.registryPath() + "\\" + definition.valueName() + ") ---");
        AiFeatureScanner.AiFeatureKeyInfo original = scanner.readValue(definition);
        System.out.println("    Valor ORIGINAL: " + describe(original.exists(), original.currentValue()));

        ActionExecutor.ActionResult setResult = actionExecutor.setAiFeatureValue(definition, definition.recommendedValue());
        System.out.println("    setAiFeatureValue(" + definition.recommendedValue() + ") -> sucesso=" + setResult.success() + " | " + setResult.message());

        if (!setResult.success()) {
            // Esperado para chaves HKLM sem elevacao (Copilot todos-usuarios, Recall, Click to Do,
            // Cocreator, Copilot no Edge).
            AiFeatureScanner.AiFeatureKeyInfo stillOriginal = scanner.readValue(definition);
            boolean untouched = sameValue(original, stillOriginal);
            System.out.println("    [ESPERADO SE HKLM SEM ADMIN] Escrita falhou - " + (untouched
                    ? "confirmado que NADA foi alterado no registro (valor real ainda = original)."
                    : "[ATENCAO] o valor real mudou mesmo com a chamada reportando falha!"));
            return;
        }

        AiFeatureScanner.AiFeatureKeyInfo afterSet = scanner.readValue(definition);
        System.out.println("    Valor apos alterar: " + describe(afterSet.exists(), afterSet.currentValue()));

        ActionExecutor.ActionResult restoreResult = actionExecutor.restoreAiFeatureValue(setResult.backupId());
        System.out.println("    restoreAiFeatureValue() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

        AiFeatureScanner.AiFeatureKeyInfo afterRestore = scanner.readValue(definition);
        boolean restoredOk = sameValue(original, afterRestore);
        System.out.println("    Valor apos reverter (leitura direta pos-restore): " + describe(afterRestore.exists(), afterRestore.currentValue()));
        System.out.println("    [" + (restoredOk ? "OK" : "ATENCAO") + "] Maquina " + (restoredOk ? "confirmada restaurada ao estado original." : "NAO foi confirmada restaurada ao original!"));
    }

    private static void testConsumerRoundTrip(ActionExecutor actionExecutor, ConsumerFeatureScanner scanner,
                                               ConsumerFeatureScanner.ConsumerFeatureKeyDefinition definition) {
        System.out.println();
        System.out.println("  --- " + definition.friendlyName() + " (" + definition.registryPath() + "\\" + definition.valueName() + ") ---");
        ConsumerFeatureScanner.ConsumerFeatureKeyInfo original = scanner.readValue(definition);
        System.out.println("    Valor ORIGINAL: " + describe(original.exists(), original.currentValue()));

        ActionExecutor.ActionResult setResult = actionExecutor.setConsumerFeatureValue(definition, definition.recommendedValue());
        System.out.println("    setConsumerFeatureValue(" + definition.recommendedValue() + ") -> sucesso=" + setResult.success() + " | " + setResult.message());

        if (!setResult.success()) {
            // Esperado para as chaves HKLM sem elevacao (Pesquisa do Bing via politica, Apps em
            // Segundo Plano).
            ConsumerFeatureScanner.ConsumerFeatureKeyInfo stillOriginal = scanner.readValue(definition);
            boolean untouched = sameValue(original, stillOriginal);
            System.out.println("    [ESPERADO SE HKLM SEM ADMIN] Escrita falhou - " + (untouched
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

    private static AiFeatureScanner.AiFeatureKeyDefinition findAiById(String id) {
        return AiFeatureScanner.KNOWN_KEYS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao '" + id + "' nao encontrada."));
    }

    private static ConsumerFeatureScanner.ConsumerFeatureKeyDefinition findConsumerById(String id) {
        return ConsumerFeatureScanner.KNOWN_KEYS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao '" + id + "' nao encontrada."));
    }

    private static boolean sameValue(AiFeatureScanner.AiFeatureKeyInfo a, AiFeatureScanner.AiFeatureKeyInfo b) {
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
