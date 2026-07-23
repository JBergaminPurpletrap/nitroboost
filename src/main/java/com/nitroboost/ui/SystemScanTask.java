package com.nitroboost.ui;

import com.nitroboost.core.BloatwareScanner;
import com.nitroboost.core.PowerPlanScanner;
import com.nitroboost.core.ProcessScanner;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.core.StartupScanner;
import com.nitroboost.core.TaskSchedulerScanner;
import com.nitroboost.core.TelemetryScanner;
import com.nitroboost.knowledge.ItemClassification;
import com.nitroboost.knowledge.KnowledgeBase;
import javafx.concurrent.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * Roda os 7 scanners do backend em uma thread de fundo (nunca na UI thread do
 * JavaFX - regra do guia de skills tecnicas) e devolve uma lista unica de
 * {@link ScannedItem}, cada um ja classificado pela {@link KnowledgeBase}.
 *
 * Cada scanner roda isolado dentro de seu proprio try/catch: uma falha em um
 * scanner (ex: comando indisponivel) nunca impede os demais de rodar, mesma
 * filosofia defensiva ja usada no backend desde a Fase 1.
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
        scanSafely(items, "processos", this::scanProcesses);
        scanSafely(items, "servicos", this::scanServices);
        scanSafely(items, "startup", this::scanStartup);
        scanSafely(items, "tarefas agendadas", this::scanTasks);
        scanSafely(items, "planos de energia", this::scanPowerPlans);
        scanSafely(items, "telemetria", this::scanTelemetry);
        scanSafely(items, "bloatware", this::scanBloatware);
        return items;
    }

    private interface ScanStep {
        List<ScannedItem> run();
    }

    private void scanSafely(List<ScannedItem> items, String label, ScanStep step) {
        try {
            items.addAll(step.run());
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao escanear " + label + " para a interface: " + e.getMessage());
        }
    }

    private List<ScannedItem> scanProcesses() {
        List<ScannedItem> result = new ArrayList<>();
        for (ProcessScanner.ProcessInfo p : new ProcessScanner().topByRam(MAX_PROCESSES_SHOWN)) {
            String state = String.format("PID %d | %.0f MB RAM | %.1f%% CPU",
                    p.pid(), p.ramBytes() / 1024.0 / 1024.0, p.cpuPercent());
            result.add(build(CATEGORY_PROCESS, "process", p.name(), state, p));
        }
        return result;
    }

    private List<ScannedItem> scanServices() {
        List<ScannedItem> result = new ArrayList<>();
        for (ServiceScanner.ServiceInfo s : new ServiceScanner().scan()) {
            String state = s.state() + " / " + s.startMode();
            result.add(build(CATEGORY_SERVICE, "service", s.name(), state, s));
        }
        return result;
    }

    private List<ScannedItem> scanStartup() {
        List<ScannedItem> result = new ArrayList<>();
        for (StartupScanner.StartupItemInfo item : new StartupScanner().scan()) {
            result.add(build(CATEGORY_STARTUP, "startup", item.name(), item.source().name(), item));
        }
        return result;
    }

    private List<ScannedItem> scanTasks() {
        List<ScannedItem> result = new ArrayList<>();
        for (TaskSchedulerScanner.TaskInfo t : new TaskSchedulerScanner().scan()) {
            result.add(build(CATEGORY_TASK, "task", t.name(), t.status(), t));
        }
        return result;
    }

    private List<ScannedItem> scanPowerPlans() {
        List<ScannedItem> result = new ArrayList<>();
        for (PowerPlanScanner.PowerPlanInfo p : new PowerPlanScanner().scan()) {
            result.add(build(CATEGORY_POWERPLAN, "powerplan", p.name(), p.active() ? "Ativo" : "Inativo", p));
        }
        return result;
    }

    private List<ScannedItem> scanTelemetry() {
        List<ScannedItem> result = new ArrayList<>();
        for (TelemetryScanner.TelemetryKeyInfo info : new TelemetryScanner().scan()) {
            String state = info.exists() ? "Valor atual: " + info.currentValue() : "Nao definido (padrao do Windows)";
            // "source" guarda a definicao (nao o info) - e o que ActionExecutor.setTelemetryValue espera.
            result.add(build(CATEGORY_TELEMETRY, "telemetry", info.definition().friendlyName(), state, info.definition()));
        }
        return result;
    }

    private List<ScannedItem> scanBloatware() {
        List<ScannedItem> result = new ArrayList<>();
        // scan() traz TODOS os apps UWP instalados (nao so os ~15 reconhecidos por
        // categoria) - itens sem categoria conhecida ainda aparecem, classificados
        // via KnowledgeBase (ou como "nao catalogado", que ja e o comportamento
        // padrao de build() abaixo). Ver PROGRESS.md "Fase 8 - Correcao".
        for (BloatwareScanner.AppxInfo app : new BloatwareScanner().scan()) {
            result.add(build(CATEGORY_BLOATWARE, "bloatware", app.name(), "Instalado (" + app.category() + ")", app));
        }
        return result;
    }

    private ScannedItem build(String category, String type, String name, String state, Object source) {
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
