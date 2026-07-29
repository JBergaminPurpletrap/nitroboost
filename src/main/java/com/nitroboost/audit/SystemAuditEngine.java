package com.nitroboost.audit;

import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.DisplayScanner;
import com.nitroboost.core.GamingScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.core.ScanProgressListener;
import com.nitroboost.core.TelemetryScanner;
import com.nitroboost.knowledge.KnowledgeBase;
import com.nitroboost.ui.SystemScanTask;

import com.nitroboost.core.RegistryValueUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** Nome fixo do item de taxa de atualizacao da tela (Fase 14 Parte 2) - mesmo valor usado por {@code ui.SystemScanTask}. */
    private static final String DISPLAY_ITEM_NAME = "Taxa de Atualizacao da Tela";

    /**
     * Categoria de EXIBICAO usada apenas no relatorio de Diagnostico (Fase 14 Parte 3) para os 4
     * itens de limpeza de icones da barra de tarefas - distinta e independente da categoria geral
     * de varredura ({@code SystemScanTask.CATEGORY_CONSUMER}), onde esses mesmos itens continuam
     * aparecendo normalmente como "Recursos de Consumidor e Segundo Plano" (mesma origem: {@link
     * ConsumerFeatureScanner}, so muda o agrupamento aqui no Diagnostico).
     */
    private static final String CATEGORY_TASKBAR_CLEANUP = "Limpeza da Barra de Tarefas";

    /** Nomes amigaveis (mesmos usados como "nome" na base de conhecimento) dos itens que entram em {@link #CATEGORY_TASKBAR_CLEANUP}. */
    private static final Set<String> TASKBAR_CLEANUP_ITEM_NAMES = Set.of(
            "Icone de Chat (Teams Pessoal) na Barra de Tarefas",
            "Caixa de Pesquisa na Barra de Tarefas",
            "Botao Visao de Tarefas na Barra de Tarefas",
            "Icone de Widgets na Barra de Tarefas"
    );

    private final KnowledgeBase knowledgeBase;
    private final TelemetryScanner telemetryScanner = new TelemetryScanner();
    private final PerformanceScanner performanceScanner = new PerformanceScanner();
    private final GamingScanner gamingScanner = new GamingScanner();
    private final AiFeatureScanner aiFeatureScanner = new AiFeatureScanner();
    private final ConsumerFeatureScanner consumerFeatureScanner = new ConsumerFeatureScanner();
    private final DisplayScanner displayScanner = new DisplayScanner();

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
        auditSafely(findings, "tela", this::auditDisplay);
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

    /**
     * Sobrecarga de {@link #run()} que reporta progresso via {@link ScanProgressListener} (Fase 12
     * Parte C) - uma categoria auditada por vez (Telemetria/Performance/Jogos/IA/Consumidor),
     * repassando o progresso interno de cada scanner (via {@code scan(listener)}) traduzido para o
     * indice desta categoria dentro do total de 5. {@code run()} continua inalterado.
     */
    public AuditReport run(ScanProgressListener listener) {
        List<AuditFinding> findings = new ArrayList<>();
        List<AuditCategoryDef> categories = List.of(
                new AuditCategoryDef("telemetria", SystemScanTask.CATEGORY_TELEMETRY, this::auditTelemetry),
                new AuditCategoryDef("performance", SystemScanTask.CATEGORY_PERFORMANCE, this::auditPerformance),
                new AuditCategoryDef("jogos", SystemScanTask.CATEGORY_GAMING, this::auditGaming),
                new AuditCategoryDef("IA", SystemScanTask.CATEGORY_AI, this::auditAi),
                new AuditCategoryDef("recursos de consumidor", SystemScanTask.CATEGORY_CONSUMER, this::auditConsumer),
                new AuditCategoryDef("tela", SystemScanTask.CATEGORY_DISPLAY, this::auditDisplay)
        );
        int totalCategories = categories.size();
        for (int i = 0; i < totalCategories; i++) {
            AuditCategoryDef def = categories.get(i);
            int categoryIndex = i;
            ScanProgressListener bridged = listener == null ? null : (category, current, total, message) ->
                    listener.onProgress(def.category(), categoryIndex, totalCategories,
                            def.category() + ": " + message);
            try {
                def.step().run(findings, bridged);
            } catch (Exception e) {
                System.err.println("[NITRO BOOST] Erro ao rodar diagnostico de " + def.label() + ": " + e.getMessage());
            }
        }
        if (listener != null) {
            listener.onProgress("Diagnostico", totalCategories, totalCategories, "Diagnostico concluido.");
        }
        return new AuditReport(findings);
    }

    private interface AuditCategoryStep {
        void run(List<AuditFinding> out, ScanProgressListener listener);
    }

    /** Amarra o rotulo de log, o nome de categoria exibido ao usuario e o passo de auditoria em si. */
    private record AuditCategoryDef(String label, String category, AuditCategoryStep step) {
    }

    private void auditTelemetry(List<AuditFinding> out) {
        auditTelemetry(out, null);
    }

    private void auditTelemetry(List<AuditFinding> out, ScanProgressListener listener) {
        for (TelemetryScanner.TelemetryKeyInfo info : telemetryScanner.scan(listener)) {
            out.add(evaluate(SystemScanTask.CATEGORY_TELEMETRY, "telemetry", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditPerformance(List<AuditFinding> out) {
        auditPerformance(out, null);
    }

    private void auditPerformance(List<AuditFinding> out, ScanProgressListener listener) {
        // O arquivo de hibernacao (tipo "hibernation", tambem escaneado por PerformanceScanner) fica
        // FORA do diagnostico de proposito: nao tem "valor_recomendado" na base de conhecimento (o
        // proprio documento da Fase 9 trata como "depende se o usuario usa essa funcao ou nao").
        for (PerformanceScanner.PerformanceKeyInfo info : performanceScanner.scan(listener)) {
            out.add(evaluate(SystemScanTask.CATEGORY_PERFORMANCE, "performance", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditGaming(List<AuditFinding> out) {
        auditGaming(out, null);
    }

    private void auditGaming(List<AuditFinding> out, ScanProgressListener listener) {
        for (GamingScanner.GamingKeyInfo info : gamingScanner.scan(listener)) {
            out.add(evaluate(SystemScanTask.CATEGORY_GAMING, "gaming", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditAi(List<AuditFinding> out) {
        auditAi(out, null);
    }

    private void auditAi(List<AuditFinding> out, ScanProgressListener listener) {
        for (AiFeatureScanner.AiFeatureKeyInfo info : aiFeatureScanner.scan(listener)) {
            out.add(evaluate(SystemScanTask.CATEGORY_AI, "ai", info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditConsumer(List<AuditFinding> out) {
        auditConsumer(out, null);
    }

    private void auditConsumer(List<AuditFinding> out, ScanProgressListener listener) {
        for (ConsumerFeatureScanner.ConsumerFeatureKeyInfo info : consumerFeatureScanner.scan(listener)) {
            String itemName = info.definition().friendlyName();
            String category = TASKBAR_CLEANUP_ITEM_NAMES.contains(itemName)
                    ? CATEGORY_TASKBAR_CLEANUP
                    : SystemScanTask.CATEGORY_CONSUMER;
            out.add(evaluate(category, "consumer", itemName,
                    info.exists() ? info.currentValue() : null, info.definition()));
        }
    }

    private void auditDisplay(List<AuditFinding> out) {
        auditDisplay(out, null);
    }

    /**
     * Excecao deliberada ao padrao de {@link #evaluate}: este item NAO tem um {@code
     * valor_recomendado} FIXO na base de conhecimento (ver nota tecnica no proprio
     * {@code knowledge-base.json}) - a "recomendacao" e relativa (taxa atual configurada vs a taxa
     * MAXIMA suportada pelo monitor conectado no momento da varredura), entao a comparacao e feita
     * aqui diretamente contra o resultado do {@link DisplayScanner}, em vez de contra uma string do
     * catalogo (Fase 14 Parte 2).
     */
    private void auditDisplay(List<AuditFinding> out, ScanProgressListener listener) {
        if (listener != null) {
            listener.onProgress(SystemScanTask.CATEGORY_DISPLAY, 0, 1, "Verificando taxa de atualizacao da tela...");
        }
        Optional<Integer> current = displayScanner.getCurrentRefreshRate();
        List<Integer> available = displayScanner.getAvailableRefreshRates();
        Optional<Integer> max = available.isEmpty() ? Optional.empty() : Optional.of(available.get(available.size() - 1));

        AuditFinding.Status status = resolveDisplayStatus(current, max);
        out.add(new AuditFinding(DISPLAY_ITEM_NAME, "display", SystemScanTask.CATEGORY_DISPLAY, status,
                current.map(v -> v + " Hz").orElse(null), max.map(v -> v + " Hz (maxima suportada)").orElse(null), null));

        if (listener != null) {
            listener.onProgress(SystemScanTask.CATEGORY_DISPLAY, 1, 1, "Verificacao concluida.");
        }
    }

    /**
     * Compara a taxa atual com a taxa maxima detectada - {@code NAO_APLICAVEL} se qualquer uma das
     * duas nao pode ser lida (sem fonte confiavel para comparar), {@code SUGESTAO} se a atual for
     * menor que a maxima, {@code JA_OTIMIZADO} caso contrario. Extraido como metodo estatico de
     * pacote para ser testavel isoladamente sem depender de hardware real (ver
     * {@code SystemAuditEngineTest}).
     */
    static AuditFinding.Status resolveDisplayStatus(Optional<Integer> current, Optional<Integer> max) {
        if (current.isEmpty() || max.isEmpty()) {
            return AuditFinding.Status.NAO_APLICAVEL;
        }
        return current.get() < max.get() ? AuditFinding.Status.SUGESTAO : AuditFinding.Status.JA_OTIMIZADO;
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
     * contar como "ja otimizado". Delega para {@link RegistryValueUtils#dwordValuesEqual} (Melhoria
     * de Confiabilidade: a mesma logica agora e reaproveitada pelo {@code ActionExecutor} para
     * confirmar por releitura que uma acao realmente mudou o valor) - mantido aqui com visibilidade
     * de pacote so para nao quebrar {@code SystemAuditEngineTest}, que ja testa contra este nome.
     */
    static boolean dwordValuesEqual(String current, String recommended) {
        return RegistryValueUtils.dwordValuesEqual(current, recommended);
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
