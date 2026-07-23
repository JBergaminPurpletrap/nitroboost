package com.nitroboost.audit;

import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.GamingScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.core.TelemetryScanner;
import com.nitroboost.knowledge.KnowledgeBase;
import com.nitroboost.ui.SystemScanTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orquestra os scanners de chaves de registro com um "valor recomendado" objetivo e claro
 * (Telemetria, Performance/Energia, Otimizacoes para Jogos, IA, Recursos de Consumidor) e compara
 * o valor atual lido da maquina com o {@code valor_recomendado} catalogado na {@link
 * KnowledgeBase} - ver secao 3 de {@code NITRO-BOOST-fase9-diagnostico-e-performance.md}.
 *
 * Reaproveita 100% dos scanners ja existentes (nenhuma logica de leitura de registro e duplicada
 * aqui) - so adiciona a camada de COMPARACAO com o valor recomendado por cima do que ja existe.
 *
 * Demais categorias (processos, servicos, startup, tarefas agendadas, planos de energia,
 * bloatware, arquivo de hibernacao) ficam DE FORA deste diagnostico por decisao deliberada:
 * nenhuma delas tem um "valor certo" unico e objetivo para comparar (ex: nao existe um "PID
 * recomendado", nem uma "lista fixa de servicos que devem estar parados" sem depender do uso de
 * cada usuario) - encher o relatorio com centenas de entradas "nao aplicavel" so para preencher
 * seria ruido, nao sinal (qualidade > cobertura total, conforme a secao 3.1 do documento da Fase 9).
 */
public class SystemAuditEngine {

    private final KnowledgeBase knowledgeBase;
    private final TelemetryScanner telemetryScanner = new TelemetryScanner();
    private final PerformanceScanner performanceScanner = new PerformanceScanner();
    private final GamingScanner gamingScanner = new GamingScanner();
    private final AiFeatureScanner aiFeatureScanner = new AiFeatureScanner();
    private final ConsumerFeatureScanner consumerFeatureScanner = new ConsumerFeatureScanner();

    public SystemAuditEngine(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    /** Roda a leitura de todas as categorias auditaveis e devolve o relatorio completo. */
    public AuditReport run() {
        List<AuditFinding> findings = new ArrayList<>();
        auditSafely(findings, "telemetria", this::auditTelemetry);
        auditSafely(findings, "performance", this::auditPerformance);
        auditSafely(findings, "jogos", this::auditGaming);
        auditSafely(findings, "IA", this::auditAi);
        auditSafely(findings, "recursos de consumidor", this::auditConsumer);
        return new AuditReport(findings);
    }

    private interface AuditStep {
        void run(List<AuditFinding> out);
    }

    /** Uma categoria falhando (ex: comando indisponivel) nunca impede as demais - mesma filosofia defensiva do backend inteiro. */
    private void auditSafely(List<AuditFinding> findings, String label, AuditStep step) {
        try {
            step.run(findings);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao rodar diagnostico de " + label + ": " + e.getMessage());
        }
    }

    private void auditTelemetry(List<AuditFinding> out) {
        for (TelemetryScanner.TelemetryKeyInfo info : telemetryScanner.scan()) {
            out.add(evaluate(SystemScanTask.CATEGORY_TELEMETRY, "telemetry", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditPerformance(List<AuditFinding> out) {
        // O arquivo de hibernacao (tipo "hibernation", tambem escaneado por PerformanceScanner) fica
        // FORA do diagnostico de proposito: nao tem "valor_recomendado" na base de conhecimento (o
        // proprio documento da Fase 9 trata como "depende se o usuario usa essa funcao ou nao").
        for (PerformanceScanner.PerformanceKeyInfo info : performanceScanner.scan()) {
            out.add(evaluate(SystemScanTask.CATEGORY_PERFORMANCE, "performance", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditGaming(List<AuditFinding> out) {
        for (GamingScanner.GamingKeyInfo info : gamingScanner.scan()) {
            out.add(evaluate(SystemScanTask.CATEGORY_GAMING, "gaming", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditAi(List<AuditFinding> out) {
        for (AiFeatureScanner.AiFeatureKeyInfo info : aiFeatureScanner.scan()) {
            out.add(evaluate(SystemScanTask.CATEGORY_AI, "ai", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditConsumer(List<AuditFinding> out) {
        for (ConsumerFeatureScanner.ConsumerFeatureKeyInfo info : consumerFeatureScanner.scan()) {
            out.add(evaluate(SystemScanTask.CATEGORY_CONSUMER, "consumer", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    /**
     * Compara o valor atual bruto (ja lido pelo scanner - {@code null} = chave nao definida) com o
     * {@code valor_recomendado} catalogado na base de conhecimento para o item, gerando o {@link
     * AuditFinding} correspondente. Sem entrada na base ou sem o campo preenchido = {@code
     * NAO_APLICAVEL} (nunca inventa uma comparacao sem fonte na base de conhecimento).
     */
    private AuditFinding evaluate(String category, String itemType, String itemName, String rawCurrentValue, Object source) {
        Optional<KnowledgeBase.KnowledgeEntry> entry = knowledgeBase.find(itemName, itemType);
        String recommended = entry.map(KnowledgeBase.KnowledgeEntry::recommendedValue)
                .filter(s -> !s.isBlank())
                .orElse(null);

        AuditFinding.Status status;
        if (recommended == null) {
            status = AuditFinding.Status.NAO_APLICAVEL;
        } else if (rawCurrentValue == null) {
            status = AuditFinding.Status.SUGESTAO;
        } else if (dwordValuesEqual(rawCurrentValue, recommended)) {
            status = AuditFinding.Status.JA_OTIMIZADO;
        } else {
            status = AuditFinding.Status.SUGESTAO;
        }
        return new AuditFinding(itemName, itemType, category, status, rawCurrentValue, recommended, source);
    }

    /**
     * Compara dois valores DWORD de registro numericamente (nao como texto puro): {@code reg
     * query} sempre devolve hexadecimal (ex: "0x26"), mas o "valor_recomendado" da base de
     * conhecimento as vezes esta documentado em decimal (ex: "38" para Win32PrioritySeparation, o
     * mesmo valor citado na tabela da Fase 9) - "38" e "0x26" sao numericamente iguais e devem
     * contar como "ja otimizado". Cai para comparacao de texto se algum dos dois nao for numerico
     * (nao deveria acontecer para chaves DWORD reais, mas evita excecao no caso raro).
     */
    static boolean dwordValuesEqual(String current, String recommended) {
        try {
            return parseDword(current) == parseDword(recommended);
        } catch (NumberFormatException e) {
            return current.trim().equalsIgnoreCase(recommended.trim());
        }
    }

    private static long parseDword(String value) {
        String trimmed = value.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("0x")) {
            return Long.parseLong(trimmed.substring(2), 16);
        }
        return Long.parseLong(trimmed, 10);
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.audit.SystemAuditEngine
     */
    public static void main(String[] args) {
        SystemAuditEngine engine = new SystemAuditEngine(new KnowledgeBase());
        AuditReport report = engine.run();

        System.out.println("===== NITRO BOOST - Diagnostico do Sistema =====");
        System.out.println(report.totalOptimized() + " de " + report.totalApplicable() + " itens ja otimizados "
                + "(" + report.totalFindings() + " avaliados no total).");

        for (var categoryEntry : report.findingsByCategory().entrySet()) {
            System.out.println();
            System.out.println("-- " + categoryEntry.getKey() + " --");
            for (AuditFinding f : categoryEntry.getValue()) {
                System.out.printf("  %-13s %-55s atual=%-14s recomendado=%-14s%n",
                        f.status(), f.itemName(),
                        f.currentValue() == null ? "(nao definido)" : f.currentValue(),
                        f.recommendedValue() == null ? "-" : f.recommendedValue());
            }
        }
    }
}
