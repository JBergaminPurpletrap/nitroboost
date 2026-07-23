package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.audit.AuditFinding;
import com.nitroboost.audit.AuditReport;
import com.nitroboost.audit.SystemAuditEngine;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.knowledge.KnowledgeBase;
import com.nitroboost.ui.ItemActionDispatcher;
import com.nitroboost.ui.ScannedItem;
import com.nitroboost.ui.SystemScanTask;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Demonstracao de integracao da Fase 9 - Parte 2 (Diagnostico do Sistema), via console:
 * {@link SystemAuditEngine} rodado contra os dados REAIS desta maquina (leitura pura de registro,
 * sem risco), imprimindo o relatorio completo agrupado por categoria + o placar geral.
 *
 * As 36 chaves de registro individuais (telemetria/performance/jogos/IA/consumidor) ja tiveram
 * round-trip completo (aplicar -> confirmar -> reverter -> confirmar com leitura direta)
 * testado por scanner em {@code Phase8Part2ConsoleDemo}/{@code Phase9Part1ConsoleDemo} - nao repete
 * isso aqui. O alvo deste teste e a camada NOVA desta fase: a comparacao com {@code
 * valor_recomendado} e o caminho {@code AuditFinding -> ScannedItem -> ItemActionDispatcher ->
 * ActionExecutor}, validado de ponta a ponta contra UM item real e seguro (chave HKCU, ja
 * confirmada gravavel sem elevacao nas fases anteriores).
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase9Part2ConsoleDemo
 */
public final class Phase9Part2ConsoleDemo {

    private Phase9Part2ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 9 Parte 2 (Diagnostico do Sistema)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        ActionExecutor actionExecutor = new ActionExecutor(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);
        SystemAuditEngine engine = new SystemAuditEngine(knowledgeBase);

        System.out.println("Rodando diagnostico completo contra a maquina real (le chaves de registro reais)...");
        AuditReport report = engine.run();

        System.out.println();
        System.out.println(report.totalOptimized() + " de " + report.totalApplicable() + " itens ja otimizados "
                + "(" + report.totalFindings() + " avaliados no total).");

        for (Map.Entry<String, List<AuditFinding>> entry : report.findingsByCategory().entrySet()) {
            System.out.println();
            System.out.println("-- " + entry.getKey() + " --");
            for (AuditFinding f : entry.getValue()) {
                System.out.printf("  %-13s %-55s atual=%-14s recomendado=%-14s%n",
                        f.status(), f.itemName(),
                        f.currentValue() == null ? "(nao definido)" : f.currentValue(),
                        f.recommendedValue() == null ? "-" : f.recommendedValue());
            }
        }

        // Alvo do teste de acao: chave HKCU ja confirmada gravavel sem elevacao desde a Fase 8
        // Parte 2 - evita mexer em chaves HKLM, que exigiriam Administrador aqui.
        Optional<AuditFinding> targetOpt = report.findings().stream()
                .filter(f -> f.status() == AuditFinding.Status.SUGESTAO
                        && "Botao do Copilot na Barra de Tarefas".equals(f.itemName()))
                .findFirst();

        section("Teste de ponta a ponta: AuditFinding -> ScannedItem -> ItemActionDispatcher -> ActionExecutor");
        if (targetOpt.isEmpty()) {
            System.out.println("Item-alvo do teste ja esta otimizado (ou nao apareceu como sugestao) nesta maquina - "
                    + "pulando o teste de acao; o relatorio acima ja confirma a comparacao funcionando corretamente.");
            return;
        }
        AuditFinding target = targetOpt.get();

        System.out.println("Alvo escolhido: " + target.itemName() + " | atual=" + target.currentValue()
                + " | recomendado=" + target.recommendedValue());

        ScannedItem scannedItem = SystemScanTask.buildItem(knowledgeBase, target.category(), target.itemType(),
                target.itemName(), "Valor atual: " + target.currentValue(), target.source());

        ActionExecutor.ActionResult applyResult = ItemActionDispatcher.performPrimaryAction(actionExecutor, scannedItem);
        System.out.println("Aplicar -> sucesso=" + applyResult.success() + " | " + applyResult.message());

        AuditReport afterApply = engine.run();
        afterApply.findings().stream()
                .filter(f -> f.itemName().equals(target.itemName()))
                .findFirst()
                .ifPresent(f -> System.out.println("Releitura apos aplicar -> status=" + f.status() + " | atual=" + f.currentValue()));

        try {
            List<ActionHistoryRepository.HistoryEntry> recent = historyRepository.findRecent(5);
            Optional<ActionHistoryRepository.HistoryEntry> historyEntry = recent.stream()
                    .filter(h -> h.itemName().equals(target.itemName()) && "set".equals(h.actionType()))
                    .findFirst();
            if (historyEntry.isEmpty()) {
                System.out.println("Nao foi possivel localizar a entrada de historico da acao aplicada - pulando reversao.");
                return;
            }

            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreFromHistory(historyEntry.get().id());
            System.out.println("Reverter (restoreFromHistory) -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao consultar historico para reversao: " + e.getMessage());
            return;
        }

        AuditReport afterRestore = engine.run();
        afterRestore.findings().stream()
                .filter(f -> f.itemName().equals(target.itemName()))
                .findFirst()
                .ifPresent(f -> System.out.println("Releitura apos reverter -> status=" + f.status()
                        + " | atual=" + f.currentValue() + " (esperado: igual ao valor original, antes do teste)"));

        System.out.println();
        System.out.println("Fim da demonstracao da Fase 9 Parte 2.");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
