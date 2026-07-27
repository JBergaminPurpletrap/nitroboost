package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.audit.AuditFinding;
import com.nitroboost.audit.AuditReport;
import com.nitroboost.audit.SystemAuditEngine;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.knowledge.KnowledgeBase;

import java.util.List;
import java.util.Objects;

/**
 * Demonstracao de integracao da Fase 14 - Parte 3 (Icones e Itens da Barra de Tarefas), via
 * console: as 3 chaves novas do {@link ConsumerFeatureScanner} (Caixa de Pesquisa/Visao de
 * Tarefas/Widgets), a confirmacao de que o item "Chat" (ja existente desde a Fase 8) continua com
 * o valor recomendado certo, e a categorizacao de EXIBICAO "Limpeza da Barra de Tarefas" no
 * relatorio de {@link SystemAuditEngine} (distinta da categoria geral "Recursos de Consumidor e
 * Segundo Plano" usada na varredura completa - ver {@code SystemAuditEngine.CATEGORY_TASKBAR_CLEANUP}).
 *
 * REGRAS DE SEGURANCA SEGUIDAS NESTE TESTE (mesmo padrao das fases anteriores):
 *  - Todas as 4 chaves desta fase sao HKCU (nao exigem elevacao para escrever, ao contrario de
 *    varias chaves HKLM usadas em fases anteriores) - o round-trip completo (ler original -> aplicar
 *    -> confirmar -> reverter -> confirmar leitura DIRETA pos-restore) roda contra a chave REAL desta
 *    maquina para "Caixa de Pesquisa na Barra de Tarefas", esperando sucesso mesmo sem Administrador.
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase14Part3ConsoleDemo
 */
public final class Phase14Part3ConsoleDemo {

    private static final List<String> NEW_CONSUMER_IDS = List.of(
            "taskbar_search_box", "taskbar_task_view_button", "taskbar_widgets_icon"
    );

    private Phase14Part3ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 14 Parte 3 (Icones da Barra de Tarefas)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        ActionExecutor actionExecutor = new ActionExecutor(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);
        ConsumerFeatureScanner consumerFeatureScanner = new ConsumerFeatureScanner();
        KnowledgeBase knowledgeBase = new KnowledgeBase();

        section("14.3.1) Leitura real das 3 chaves novas nesta maquina");
        for (String id : NEW_CONSUMER_IDS) {
            ConsumerFeatureScanner.ConsumerFeatureKeyDefinition definition = findConsumerById(id);
            ConsumerFeatureScanner.ConsumerFeatureKeyInfo info = consumerFeatureScanner.readValue(definition);
            System.out.printf("  - %-45s valor atual=%-14s (existe=%s) recomendado=%s%n",
                    definition.friendlyName(), describe(info.exists(), info.currentValue()),
                    info.exists(), definition.recommendedValue());
        }

        section("14.3.2) Confirmar item 'Chat' (Fase 8) com valor_recomendado correto na base de conhecimento");
        knowledgeBase.find("Icone de Chat (Teams Pessoal) na Barra de Tarefas", "consumer").ifPresentOrElse(
                entry -> System.out.println("  Encontrado: valor_recomendado='" + entry.recommendedValue()
                        + "' (esperado '0') -> " + ("0".equals(entry.recommendedValue()) ? "OK" : "[FALHA] valor incorreto!")),
                () -> System.out.println("  [FALHA] Item 'Chat' nao encontrado na base de conhecimento!")
        );

        section("14.3.3) Round-trip real (ler original -> aplicar -> confirmar -> reverter -> confirmar pos-restore)");
        testConsumerRoundTrip(actionExecutor, consumerFeatureScanner, findConsumerById("taskbar_search_box"));

        section("14.3.4) Diagnostico do Sistema - categoria 'Limpeza da Barra de Tarefas'");
        SystemAuditEngine engine = new SystemAuditEngine(knowledgeBase);
        AuditReport report = engine.run();
        List<AuditFinding> taskbarFindings = report.findingsByCategory().getOrDefault("Limpeza da Barra de Tarefas", List.of());
        if (taskbarFindings.size() != 4) {
            System.out.println("  [FALHA] Esperava 4 itens na categoria 'Limpeza da Barra de Tarefas', encontrou "
                    + taskbarFindings.size() + "!");
        } else {
            System.out.println("  [OK] 4 itens encontrados na categoria 'Limpeza da Barra de Tarefas':");
        }
        for (AuditFinding f : taskbarFindings) {
            System.out.printf("    %-13s %-45s atual=%-14s recomendado=%-14s%n",
                    f.status(), f.itemName(),
                    f.currentValue() == null ? "(nao definido)" : f.currentValue(),
                    f.recommendedValue() == null ? "-" : f.recommendedValue());
        }
        long consumerCategoryCount = report.findingsByCategory()
                .getOrDefault("Recursos de Consumidor e Segundo Plano", List.of()).size();
        System.out.println("  (categoria geral 'Recursos de Consumidor e Segundo Plano' no Diagnostico tem "
                + consumerCategoryCount + " itens - os 4 de barra de tarefas NAO aparecem duplicados aqui, "
                + "so na categoria nova.)");

        section("Historico completo de acoes (mais recente primeiro)");
        try {
            historyRepository.printRecentHistory(15);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 14 Parte 3 - conclui a Fase 14 inteira (Partes 1+2+3)");
    }

    // ------------------------------------------------------------------
    // Round trip real, com confirmacao pos-restore (mesmo padrao da Fase 10 Parte 2)
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
            System.out.println("    [ATENCAO - INESPERADO PARA CHAVE HKCU] Escrita falhou - " + (untouched
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
        System.out.println("    [" + (restoredOk ? "OK" : "ATENCAO") + "] Maquina " + (restoredOk
                ? "confirmada restaurada ao estado original (round-trip HKCU funcionou sem elevacao, conforme esperado)."
                : "NAO foi confirmada restaurada ao original!"));
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private static ConsumerFeatureScanner.ConsumerFeatureKeyDefinition findConsumerById(String id) {
        return ConsumerFeatureScanner.KNOWN_KEYS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Definicao de consumidor '" + id + "' nao encontrada."));
    }

    private static boolean sameValue(ConsumerFeatureScanner.ConsumerFeatureKeyInfo a, ConsumerFeatureScanner.ConsumerFeatureKeyInfo b) {
        if (a.exists() != b.exists()) {
            return false;
        }
        return !a.exists() || Objects.equals(a.currentValue(), b.currentValue());
    }

    private static String describe(boolean exists, String value) {
        return exists ? value : "(nao definido)";
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
