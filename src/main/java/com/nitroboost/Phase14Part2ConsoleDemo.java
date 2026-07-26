package com.nitroboost;

import com.nitroboost.audit.AuditFinding;
import com.nitroboost.audit.AuditReport;
import com.nitroboost.audit.SystemAuditEngine;
import com.nitroboost.core.DisplayScanner;
import com.nitroboost.knowledge.KnowledgeBase;

import java.util.List;
import java.util.Optional;

/**
 * Demonstracao de integracao da Fase 14 - Parte 2 (Taxa de Atualizacao da Tela), via console:
 * {@link DisplayScanner} (leitura real da taxa atual/suportadas desta maquina), a entrada nova na
 * {@link KnowledgeBase} (tipo {@code display}) e a comparacao dinamica (atual vs maxima) feita por
 * {@link SystemAuditEngine} para este item especifico.
 *
 * REGRA DE OURO SEGUIDA NESTE TESTE (Fase 14 Parte 2, secao D.3 do documento): o botao "Abrir
 * Configuracoes de Tela" (Nivel 1) NUNCA e executado de verdade aqui - este demo so imprime o comando
 * que {@code ActionExecutor.openDisplaySettings()} montaria ({@code ActionExecutor.displaySettingsCommand()}),
 * para conferencia manual, sem abrir a tela de configuracoes de verdade (o mesmo cuidado ja usado na
 * Fase 11 Parte 1 com {@code Desktop.browse()}). O Nivel 2 (troca automatica de taxa via
 * {@code ChangeDisplaySettingsEx}) NAO foi implementado nesta fase - documentado como melhoria
 * futura opcional em {@code docs/PROGRESS.md}.
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase14Part2ConsoleDemo
 */
public final class Phase14Part2ConsoleDemo {

    private Phase14Part2ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 14 Parte 2 (Taxa de Atualizacao da Tela)");

        DisplayScanner scanner = new DisplayScanner();
        Optional<Integer> current = scanner.getCurrentRefreshRate();
        List<Integer> available = scanner.getAvailableRefreshRates();
        Optional<Integer> max = scanner.getMaxRefreshRate();

        System.out.println("Taxa atual detectada: " + current.map(v -> v + " Hz").orElse("(nao foi possivel detectar)"));
        System.out.println("Taxas suportadas (resolucao atual): " + available + " Hz");
        System.out.println("Taxa maxima suportada: " + max.map(v -> v + " Hz").orElse("(nao foi possivel detectar)"));
        System.out.println();
        System.out.println("CONFIRA na propria maquina: Configuracoes > Sistema > Tela > Exibicao avancada");
        System.out.println("- o valor 'Taxa atual detectada' acima deve bater com a taxa de atualizacao mostrada la.");

        section("Entrada na base de conhecimento (tipo 'display')");
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        Optional<KnowledgeBase.KnowledgeEntry> entry = knowledgeBase.find("Taxa de Atualizacao da Tela", "display");
        if (entry.isPresent()) {
            System.out.println("Encontrada: " + entry.get().name() + " | classificacao=" + entry.get().classification());
            System.out.println("valor_recomendado no JSON: '" + entry.get().recommendedValue()
                    + "' (vazio de proposito - a comparacao e dinamica, feita pelo SystemAuditEngine, nao um valor fixo)");
        } else {
            System.out.println("[FALHA] Item 'Taxa de Atualizacao da Tela' (tipo 'display') nao encontrado na base de conhecimento!");
        }

        section("Diagnostico do Sistema - comparacao dinamica (SystemAuditEngine)");
        SystemAuditEngine engine = new SystemAuditEngine(knowledgeBase);
        AuditReport report = engine.run();
        List<AuditFinding> displayFindings = report.findingsByCategory().getOrDefault("Taxa de Atualizacao da Tela", List.of());
        if (displayFindings.isEmpty()) {
            System.out.println("[FALHA] Nenhum achado de diagnostico gerado para a categoria de tela!");
        } else {
            for (AuditFinding f : displayFindings) {
                System.out.printf("  %-13s %-30s atual=%-20s maxima=%-20s%n",
                        f.status(), f.itemName(),
                        f.currentValue() == null ? "(nao definido)" : f.currentValue(),
                        f.recommendedValue() == null ? "(nao definido)" : f.recommendedValue());
            }
        }

        section("Botao 'Abrir Configuracoes de Tela' (Nivel 1) - comando montado, NAO executado");
        String[] command = com.nitroboost.actions.ActionExecutor.displaySettingsCommand();
        System.out.println("Comando: " + String.join(" ", command));
        System.out.println("NOTA: o comando acima NAO foi executado neste teste (ActionExecutor.openDisplaySettings() ");
        System.out.println("nunca foi chamado) - abrir de verdade a tela de Configuracoes fica a criterio do usuario,");
        System.out.println("clicando no botao real da interface grafica.");

        section("Nivel 2 (troca automatica de taxa via ChangeDisplaySettingsEx)");
        System.out.println("NAO implementado nesta fase, conforme decisao explicita do documento da Fase 14 (risco de");
        System.out.println("tela preta se a taxa aplicada nao for suportada pelo monitor) - ver docs/PROGRESS.md.");

        System.out.println();
        System.out.println("Fim da demonstracao da Fase 14 Parte 2.");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
