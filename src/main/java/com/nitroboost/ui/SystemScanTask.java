package com.nitroboost.ui;

import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.BloatwareScanner;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.DisplayScanner;
import com.nitroboost.core.GamingScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.core.PowerPlanScanner;
import com.nitroboost.core.ProcessScanner;
import com.nitroboost.core.ScanProgressListener;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.core.StartupScanner;
import com.nitroboost.core.TaskSchedulerScanner;
import com.nitroboost.core.TelemetryScanner;
import com.nitroboost.knowledge.ItemClassification;
import com.nitroboost.knowledge.KnowledgeBase;
import javafx.concurrent.Task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Roda os 12 scanners do backend em uma thread de fundo (nunca na UI thread do
 * JavaFX - regra do guia de skills tecnicas) e devolve uma lista unica de
 * {@link ScannedItem}, cada um ja classificado pela {@link KnowledgeBase}.
 *
 * Cada scanner roda isolado dentro de seu proprio try/catch: uma falha em um
 * scanner (ex: comando indisponivel) nunca impede os demais de rodar, mesma
 * filosofia defensiva ja usada no backend desde a Fase 1.
 *
 * Implementa a ponte de progresso da Fase 12 Parte C: para cada categoria, traduz o {@link
 * ScanProgressListener} do scanner em {@link #updateProgress(double, double)}/{@link
 * #updateMessage(String)} nativos do {@link Task} (progresso geral entre as 11 categorias +
 * mensagem codificando tambem o sub-progresso da categoria atual).
 */
public class SystemScanTask extends Task<List<ScannedItem>> {

    /** Nomes de categoria exibidos ao usuario - usados tambem pelo filtro de {@link ScanResultsView}. */
    public static final String CATEGORY_PROCESS = "Processos";
    public static final String CATEGORY_SERVICE = "Servicos";
    public static final String CATEGORY_STARTUP = "Inicializacao";
    public static final String CATEGORY_TASK = "Tarefas Agendadas";
    public static final String CATEGORY_POWERPLAN = "Planos de Energia";
    public static final String CATEGORY_TELEMETRY = "Telemetria";
    public static final String CATEGORY_BLOATWARE = "Bloatware";
    public static final String CATEGORY_PERFORMANCE = "Performance e Energia";
    public static final String CATEGORY_GAMING = "Otimizacoes para Jogos";
    public static final String CATEGORY_AI = "IA / Inteligencia Artificial";
    public static final String CATEGORY_CONSUMER = "Recursos de Consumidor e Segundo Plano";
    public static final String CATEGORY_DISPLAY = "Taxa de Atualizacao da Tela";

    /** Nome fixo do item de hibernacao - mesmo valor usado por {@link com.nitroboost.actions.ActionExecutor}. */
    private static final String HIBERNATION_ITEM_NAME = "Arquivo de Hibernacao";

    /** Nome fixo do item de Armazenamento Reservado - mesmo valor usado por {@link com.nitroboost.actions.ActionExecutor}. */
    private static final String RESERVED_STORAGE_ITEM_NAME = "Armazenamento Reservado (Reserved Storage)";

    /** Nome fixo do item de desinstalacao completa do OneDrive (Fase 12 Parte A) - mesmo valor usado por {@link com.nitroboost.actions.ActionExecutor}. */
    private static final String ONEDRIVE_UNINSTALL_ITEM_NAME = "OneDrive - Desinstalacao Completa";

    // ponytail: limite pratico para nao empilhar centenas de processos irrelevantes
    // na tabela - os processos mais pesados (RAM) sao os que mais importam para o
    // objetivo do app ("otimizar performance"). Suba este numero se fizer falta.
    private static final int MAX_PROCESSES_SHOWN = 60;

    private final KnowledgeBase knowledgeBase;

    public SystemScanTask(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    protected List<ScannedItem> call() {
        List<ScannedItem> items = new ArrayList<>();
        List<CategoryDef> categories = List.of(
                new CategoryDef("processos", CATEGORY_PROCESS, this::scanProcesses),
                new CategoryDef("servicos", CATEGORY_SERVICE, this::scanServices),
                new CategoryDef("startup", CATEGORY_STARTUP, this::scanStartup),
                new CategoryDef("tarefas agendadas", CATEGORY_TASK, this::scanTasks),
                new CategoryDef("planos de energia", CATEGORY_POWERPLAN, this::scanPowerPlans),
                new CategoryDef("telemetria", CATEGORY_TELEMETRY, this::scanTelemetry),
                new CategoryDef("bloatware", CATEGORY_BLOATWARE, this::scanBloatware),
                new CategoryDef("performance", CATEGORY_PERFORMANCE, this::scanPerformance),
                new CategoryDef("jogos", CATEGORY_GAMING, this::scanGaming),
                new CategoryDef("IA", CATEGORY_AI, this::scanAiFeatures),
                new CategoryDef("recursos de consumidor", CATEGORY_CONSUMER, this::scanConsumerFeatures),
                new CategoryDef("taxa de atualizacao da tela", CATEGORY_DISPLAY, this::scanDisplay)
        );
        int totalCategories = categories.size();
        for (int i = 0; i < totalCategories; i++) {
            CategoryDef def = categories.get(i);
            int categoryIndex = i;
            updateProgress(categoryIndex, totalCategories, 0.0);
            updateMessage(formatProgressMessage(categoryIndex, totalCategories, def.category(), 0, 0, "Iniciando..."));
            ScanProgressListener uiListener = (category, current, total, message) -> {
                updateProgress(categoryIndex, totalCategories, total > 0 ? (double) current / total : 1.0);
                updateMessage(formatProgressMessage(categoryIndex, totalCategories, def.category(), current, total, message));
            };
            scanSafely(items, def.label(), () -> def.step().run(uiListener));
        }
        updateProgress(totalCategories, totalCategories);
        updateMessage("Varredura concluida.");
        return items;
    }

    /** Um passo de varredura (categoria) que aceita um {@link ScanProgressListener} opcional. */
    private interface CategoryScanStep {
        List<ScannedItem> run(ScanProgressListener listener);
    }

    /** Amarra o rotulo de log, o nome de categoria exibido ao usuario e o passo de varredura em si. */
    private record CategoryDef(String label, String category, CategoryScanStep step) {
    }

    /**
     * Traduz o progresso de UMA categoria (current/total locais) em progresso GERAL da Task (0.0 a
     * 1.0, entre todas as {@code totalCategories}) via {@link javafx.concurrent.Task#updateProgress}
     * - assim a barra de progresso na UI avanca suavemente item a item dentro da categoria atual, em
     * vez de pular de categoria em categoria.
     */
    private void updateProgress(int categoryIndex, int totalCategories, double localFraction) {
        double overall = (categoryIndex + Math.max(0, Math.min(1, localFraction))) / totalCategories;
        updateProgress(overall, 1.0);
    }

    /**
     * Monta uma unica mensagem de texto codificando os dois niveis de progresso pedidos pela Fase 12
     * Parte C: geral ("Categoria 3 de 11: Servicos") e da categoria atual ("67 de 130 processados").
     */
    private String formatProgressMessage(int categoryIndex, int totalCategories, String categoryLabel,
                                          int current, int total, String message) {
        String detail = total > 0 ? String.format(" (%d/%d)", current, total) : "";
        return String.format("Categoria %d de %d: %s — %s%s",
                categoryIndex + 1, totalCategories, categoryLabel, message, detail);
    }

    private void scanSafely(List<ScannedItem> items, String label, java.util.function.Supplier<List<ScannedItem>> step) {
        try {
            items.addAll(step.get());
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao escanear " + label + " para a interface: " + e.getMessage());
        }
    }

    private List<ScannedItem> scanProcesses(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        List<ProcessScanner.ProcessInfo> topProcesses = new ProcessScanner().scan(listener).stream()
                .sorted(Comparator.comparingLong(ProcessScanner.ProcessInfo::ramBytes).reversed())
                .limit(MAX_PROCESSES_SHOWN)
                .toList();
        for (ProcessScanner.ProcessInfo p : topProcesses) {
            String state = String.format("PID %d | %.0f MB RAM | %.1f%% CPU",
                    p.pid(), p.ramBytes() / 1024.0 / 1024.0, p.cpuPercent());
            result.add(build(CATEGORY_PROCESS, "process", p.name(), state, p));
        }
        return result;
    }

    private List<ScannedItem> scanServices(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (ServiceScanner.ServiceInfo s : new ServiceScanner().scan(listener)) {
            String state = s.state() + " / " + s.startMode();
            result.add(build(CATEGORY_SERVICE, "service", s.name(), state, s));
        }
        return result;
    }

    private List<ScannedItem> scanStartup(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (StartupScanner.StartupItemInfo item : new StartupScanner().scan(listener)) {
            result.add(build(CATEGORY_STARTUP, "startup", item.name(), item.source().name(), item));
        }
        return result;
    }

    private List<ScannedItem> scanTasks(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (TaskSchedulerScanner.TaskInfo t : new TaskSchedulerScanner().scan(listener)) {
            result.add(build(CATEGORY_TASK, "task", t.name(), t.status(), t));
        }
        return result;
    }

    private List<ScannedItem> scanPowerPlans(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (PowerPlanScanner.PowerPlanInfo p : new PowerPlanScanner().scan(listener)) {
            result.add(build(CATEGORY_POWERPLAN, "powerplan", p.name(), p.active() ? "Ativo" : "Inativo", p));
        }
        return result;
    }

    private List<ScannedItem> scanTelemetry(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (TelemetryScanner.TelemetryKeyInfo info : new TelemetryScanner().scan(listener)) {
            String state = info.exists() ? "Valor atual: " + info.currentValue() : "Nao definido (padrao do Windows)";
            // "source" guarda a definicao (nao o info) - e o que ActionExecutor.setTelemetryValue espera.
            result.add(build(CATEGORY_TELEMETRY, "telemetry", info.definition().friendlyName(), state, info.definition()));
        }
        return result;
    }

    private List<ScannedItem> scanBloatware(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        // scan() traz TODOS os apps UWP instalados (nao so os ~15 reconhecidos por
        // categoria) - itens sem categoria conhecida ainda aparecem, classificados
        // via KnowledgeBase (ou como "nao catalogado", que ja e o comportamento
        // padrao de build() abaixo). Ver PROGRESS.md "Fase 8 - Correcao".
        for (BloatwareScanner.AppxInfo app : new BloatwareScanner().scan(listener)) {
            result.add(build(CATEGORY_BLOATWARE, "bloatware", app.name(), "Instalado (" + app.category() + ")", app));
        }
        return result;
    }

    private List<ScannedItem> scanPerformance(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (PerformanceScanner.PerformanceKeyInfo info : new PerformanceScanner().scan(listener)) {
            String state = info.exists() ? "Valor atual: " + info.currentValue() : "Nao definido (padrao do Windows)";
            // "source" guarda a definicao (nao o info) - e o que ActionExecutor.setPerformanceValue espera.
            result.add(build(CATEGORY_PERFORMANCE, "performance", info.definition().friendlyName(), state, info.definition()));
        }
        PerformanceScanner.HibernationStatus hibernation = new PerformanceScanner().checkHibernationFile();
        String hibernationState = hibernation.checkFailed()
                ? "Nao foi possivel verificar"
                : (hibernation.fileExists() ? "Ativado (arquivo presente)" : "Desativado (arquivo ausente)");
        result.add(build(CATEGORY_PERFORMANCE, "hibernation", HIBERNATION_ITEM_NAME, hibernationState, hibernation));

        PerformanceScanner.ReservedStorageStatus reservedStorage = new PerformanceScanner().checkReservedStorageState();
        String reservedStorageState = !reservedStorage.supported()
                ? "Nao suportado nesta versao do Windows"
                : (reservedStorage.checkFailed() ? "Nao foi possivel verificar" : "Estado atual: " + reservedStorage.state());
        result.add(build(CATEGORY_PERFORMANCE, "reservedstorage", RESERVED_STORAGE_ITEM_NAME, reservedStorageState, reservedStorage));
        return result;
    }

    private List<ScannedItem> scanGaming(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (GamingScanner.GamingKeyInfo info : new GamingScanner().scan(listener)) {
            String state = info.exists() ? "Valor atual: " + info.currentValue() : "Nao definido (padrao do Windows)";
            // "source" guarda a definicao (nao o info) - e o que ActionExecutor.setGamingValue espera.
            result.add(build(CATEGORY_GAMING, "gaming", info.definition().friendlyName(), state, info.definition()));
        }
        return result;
    }

    private List<ScannedItem> scanAiFeatures(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (AiFeatureScanner.AiFeatureKeyInfo info : new AiFeatureScanner().scan(listener)) {
            String state = info.exists() ? "Valor atual: " + info.currentValue() : "Nao definido (padrao do Windows)";
            // "source" guarda a definicao (nao o info) - e o que ActionExecutor.setAiFeatureValue espera.
            result.add(build(CATEGORY_AI, "ai", info.definition().friendlyName(), state, info.definition()));
        }
        return result;
    }

    private List<ScannedItem> scanConsumerFeatures(ScanProgressListener listener) {
        List<ScannedItem> result = new ArrayList<>();
        for (ConsumerFeatureScanner.ConsumerFeatureKeyInfo info : new ConsumerFeatureScanner().scan(listener)) {
            String state = info.exists() ? "Valor atual: " + info.currentValue() : "Nao definido (padrao do Windows)";
            // "source" guarda a definicao (nao o info) - e o que ActionExecutor.setConsumerFeatureValue espera.
            result.add(build(CATEGORY_CONSUMER, "consumer", info.definition().friendlyName(), state, info.definition()));
        }

        // Item fixo de catalogo (Fase 12 Parte A, secao A.4) - nao vem de um scanner de registro como
        // os demais itens deste metodo, mas reaproveita a mesma categoria (OneDrive ja tem outros
        // itens de consumidor aqui) em vez de criar uma categoria nova so para este item (YAGNI). O
        // "source" fica null: a acao (ActionExecutor.uninstallOneDriveCompletely) nao precisa de cast
        // nenhum, igual aos itens fixos de hibernacao/armazenamento reservado em scanPerformance().
        String onedriveSetupPath = com.nitroboost.actions.ActionExecutor.resolveOneDriveSetupPath();
        String onedriveState = onedriveSetupPath != null
                ? "OneDriveSetup.exe encontrado (" + onedriveSetupPath + ")"
                : "OneDriveSetup.exe nao encontrado (OneDrive pode ja estar desinstalado)";
        result.add(build(CATEGORY_CONSUMER, "onedrive_uninstall", ONEDRIVE_UNINSTALL_ITEM_NAME, onedriveState, null));
        return result;
    }

    /**
     * Item fixo de catalogo (Fase 14 Parte 2) - assim como hibernacao/armazenamento reservado, nao
     * vem de uma lista de chaves conhecidas ({@code KNOWN_KEYS}) como os demais itens de registro,
     * mas de uma leitura direta via {@link DisplayScanner} (JNA, sem chave de registro envolvida).
     * "source" fica null: a acao principal (abrir Configuracoes de Tela) nao precisa de cast nenhum,
     * mesmo raciocinio do item de desinstalacao do OneDrive acima.
     */
    private List<ScannedItem> scanDisplay(ScanProgressListener listener) {
        if (listener != null) {
            listener.onProgress(CATEGORY_DISPLAY, 0, 1, "Verificando taxa de atualizacao da tela...");
        }
        DisplayScanner scanner = new DisplayScanner();
        Optional<Integer> current = scanner.getCurrentRefreshRate();
        List<Integer> available = scanner.getAvailableRefreshRates();
        Optional<Integer> max = available.isEmpty() ? Optional.empty() : Optional.of(available.get(available.size() - 1));

        String state = current.map(v -> v + " Hz atual").orElse("Taxa atual nao detectada")
                + " / maxima suportada: " + max.map(v -> v + " Hz").orElse("nao detectada")
                + (available.isEmpty() ? "" : " (taxas suportadas: " + available + " Hz)");

        List<ScannedItem> result = new ArrayList<>();
        result.add(build(CATEGORY_DISPLAY, "display", "Taxa de Atualizacao da Tela", state, null));
        if (listener != null) {
            listener.onProgress(CATEGORY_DISPLAY, 1, 1, "Verificacao concluida.");
        }
        return result;
    }

    private ScannedItem build(String category, String type, String name, String state, Object source) {
        return buildItem(knowledgeBase, category, type, name, state, source);
    }

    /**
     * Constroi um {@link ScannedItem} classificado pela {@link KnowledgeBase} - extraido como
     * metodo estatico (em vez de ficar preso a instancia desta classe) para que
     * {@link com.nitroboost.audit.SystemAuditEngine} reaproveite exatamente a mesma logica de
     * classificacao/fallback ao montar seus achados de diagnostico, sem duplicar nada aqui.
     */
    public static ScannedItem buildItem(KnowledgeBase knowledgeBase, String category, String type, String name, String state, Object source) {
        var entry = knowledgeBase.find(name, type);
        ItemClassification classification = entry.map(KnowledgeBase.KnowledgeEntry::classification).orElse(ItemClassification.DEPENDE);
        String description = entry.map(KnowledgeBase.KnowledgeEntry::description)
                .filter(s -> !s.isBlank())
                .orElse("Item ainda nao catalogado na base de conhecimento interna do NITRO BOOST.");
        String disableImpact = entry.map(KnowledgeBase.KnowledgeEntry::disableImpact)
                .filter(s -> !s.isBlank())
                .orElse("Impacto nao mapeado - avalie com cuidado antes de desativar este item.");
        String keepImpact = entry.map(KnowledgeBase.KnowledgeEntry::keepImpact)
                .filter(s -> !s.isBlank())
                .orElse("Continua funcionando normalmente se voce mantiver como esta.");
        return new ScannedItem(category, type, name, state, classification, description, disableImpact, keepImpact, source, entry.isPresent());
    }
}
