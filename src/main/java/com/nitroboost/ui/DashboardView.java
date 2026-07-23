package com.nitroboost.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.Group;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tela inicial ("PAINEL DE CONTROLE"): grafico de linha de CPU/RAM em tempo
 * real, velocimetro de carga geral do sistema e o botao principal "ESCANEAR
 * SISTEMA".
 *
 * Leitura de hardware via OSHI acontece em uma {@link ScheduledExecutorService}
 * de fundo (nunca na JavaFX Application Thread), e cada atualizacao visual e
 * publicada de volta com {@link Platform#runLater} - exatamente o padrao
 * recomendado no guia de skills tecnicas do projeto para nao travar a UI.
 */
public class DashboardView extends BorderPane {

    private static final int POLL_INTERVAL_SECONDS = 2;
    private static final int MAX_HISTORY_POINTS = 30;
    private static final double CRITICAL_THRESHOLD_PERCENT = 90.0;

    private final SystemInfo systemInfo = new SystemInfo();
    private final HardwareAbstractionLayer hardware = systemInfo.getHardware();
    private final CentralProcessor processor = hardware.getProcessor();
    private final GlobalMemory memory = hardware.getMemory();

    private long[] previousCpuTicks = processor.getSystemCpuLoadTicks();
    private double secondsElapsed = 0;

    private final XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> ramSeries = new XYChart.Series<>();

    private final ProgressBar cpuBar = new ProgressBar(0);
    private final ProgressBar ramBar = new ProgressBar(0);
    private final Label cpuValueLabel = new Label("0%");
    private final Label ramValueLabel = new Label("0%");

    private final Arc gaugeArc;
    private final Label gaugeValueLabel;

    private ScheduledExecutorService poller;

    public DashboardView(Runnable onScanRequested) {
        cpuSeries.setName("CPU (%)");
        ramSeries.setName("RAM (%)");

        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));

        Label title = new Label("PAINEL DE CONTROLE");
        title.getStyleClass().add("title-hud");
        Label subtitle = new Label("Monitoramento em tempo real do sistema");
        subtitle.getStyleClass().add("text-secondary");
        VBox header = new VBox(4, title, subtitle);

        Button scanButton = new Button("⚡ ESCANEAR SISTEMA");
        scanButton.getStyleClass().add("btn-turbo");
        scanButton.setOnAction(e -> {
            if (onScanRequested != null) {
                onScanRequested.run();
            }
        });

        HBox headerBar = new HBox(header, spacer(), scanButton);
        headerBar.setAlignment(Pos.CENTER_LEFT);

        var gauge = buildGauge();
        this.gaugeArc = gauge.arc();
        this.gaugeValueLabel = gauge.valueLabel();

        VBox metricsBox = buildMetricsBox();

        HBox topContent = new HBox(28, gauge.pane(), metricsBox);
        topContent.setAlignment(Pos.CENTER_LEFT);
        topContent.setPadding(new Insets(24, 0, 24, 0));

        LineChart<Number, Number> chart = buildChart();

        VBox center = new VBox(12, headerBar, topContent, chart);
        VBox.setVgrow(chart, Priority.ALWAYS);
        setCenter(center);

        startPolling();
    }

    private HBox spacer() {
        HBox box = new HBox();
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private record Gauge(StackPane pane, Arc arc, Label valueLabel) {
    }

    private Gauge buildGauge() {
        double radius = 80;
        Circle track = new Circle(radius);
        track.setFill(Color.TRANSPARENT);
        track.getStyleClass().add("gauge-track");

        Arc arc = new Arc(0, 0, radius, radius, 90, 0);
        arc.setType(ArcType.OPEN);
        arc.setFill(Color.TRANSPARENT);
        arc.setStroke(Color.web(Theme.COLOR_TECH_GREEN));
        arc.getStyleClass().add("gauge-arc");

        Label valueLabel = new Label("0%");
        valueLabel.getStyleClass().add("gauge-value");
        Label captionLabel = new Label("CARGA DO SISTEMA");
        captionLabel.getStyleClass().add("gauge-caption");
        VBox textBox = new VBox(2, valueLabel, captionLabel);
        textBox.setAlignment(Pos.CENTER);

        // Agrupa track+arc num Group (nao um StackPane) para que o arco parcial
        // nao seja recentralizado pelo proprio bounding box a cada atualizacao
        // de angulo - o Group herda os bounds fixos e simetricos do Circle
        // completo, entao o centro nunca se desloca conforme a carga muda.
        StackPane pane = new StackPane(new Group(track, arc), textBox);
        pane.getStyleClass().add("gauge-pane");
        pane.setPrefSize(radius * 2 + 24, radius * 2 + 24);
        return new Gauge(pane, arc, valueLabel);
    }

    private VBox buildMetricsBox() {
        cpuBar.getStyleClass().add("progress-rpm");
        ramBar.getStyleClass().add("progress-rpm");
        cpuBar.setPrefWidth(320);
        ramBar.setPrefWidth(320);

        Label cpuCaption = new Label("USO DE CPU");
        cpuCaption.getStyleClass().add("subtitle-hud");
        Label ramCaption = new Label("USO DE RAM");
        ramCaption.getStyleClass().add("subtitle-hud");

        HBox cpuRow = new HBox(10, cpuBar, cpuValueLabel);
        cpuRow.setAlignment(Pos.CENTER_LEFT);
        HBox ramRow = new HBox(10, ramBar, ramValueLabel);
        ramRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, cpuCaption, cpuRow, ramCaption, ramRow);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private LineChart<Number, Number> buildChart() {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Tempo (s)");
        xAxis.setForceZeroInRange(false);
        NumberAxis yAxis = new NumberAxis(0, 100, 10);
        yAxis.setLabel("Uso (%)");

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("CPU e RAM em tempo real");
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.getData().addAll(cpuSeries, ramSeries);
        return chart;
    }

    /**
     * Inicia a leitura periodica de hardware em uma thread de fundo dedicada
     * (daemon, para nao impedir o encerramento da aplicacao). Cada execucao
     * e protegida por try/catch: uma falha pontual de leitura via OSHI nunca
     * deve interromper as proximas execucoes agendadas (regra de ouro do
     * projeto + comportamento conhecido de ScheduledExecutorService, que
     * para de reagendar silenciosamente se uma excecao escapar).
     */
    private void startPolling() {
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nitroboost-dashboard-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(this::pollHardwareSafely, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void pollHardwareSafely() {
        try {
            double cpuLoad = processor.getSystemCpuLoadBetweenTicks(previousCpuTicks) * 100.0;
            previousCpuTicks = processor.getSystemCpuLoadTicks();

            long total = memory.getTotal();
            long available = memory.getAvailable();
            double ramLoad = total > 0 ? ((total - available) * 100.0) / total : 0.0;

            Platform.runLater(() -> updateUi(cpuLoad, ramLoad));
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao ler hardware via OSHI para o dashboard: " + e.getMessage());
        }
    }

    private void updateUi(double cpuPercent, double ramPercent) {
        cpuPercent = clamp(cpuPercent);
        ramPercent = clamp(ramPercent);

        cpuBar.setProgress(cpuPercent / 100.0);
        ramBar.setProgress(ramPercent / 100.0);
        cpuValueLabel.setText(String.format("%.0f%%", cpuPercent));
        ramValueLabel.setText(String.format("%.0f%%", ramPercent));
        setCritical(cpuBar, cpuPercent);
        setCritical(ramBar, ramPercent);

        double overall = (cpuPercent + ramPercent) / 2.0;
        gaugeArc.setLength(-3.6 * overall);
        gaugeValueLabel.setText(String.format("%.0f%%", overall));
        gaugeArc.setStroke(Color.web(overall >= CRITICAL_THRESHOLD_PERCENT ? Theme.COLOR_RED : Theme.COLOR_NEON_GREEN));

        cpuSeries.getData().add(new XYChart.Data<>(secondsElapsed, cpuPercent));
        ramSeries.getData().add(new XYChart.Data<>(secondsElapsed, ramPercent));
        if (cpuSeries.getData().size() > MAX_HISTORY_POINTS) {
            cpuSeries.getData().remove(0);
        }
        if (ramSeries.getData().size() > MAX_HISTORY_POINTS) {
            ramSeries.getData().remove(0);
        }
        secondsElapsed += POLL_INTERVAL_SECONDS;
    }

    private void setCritical(ProgressBar bar, double percent) {
        if (percent >= CRITICAL_THRESHOLD_PERCENT) {
            if (!bar.getStyleClass().contains("progress-critical")) {
                bar.getStyleClass().add("progress-critical");
            }
        } else {
            bar.getStyleClass().remove("progress-critical");
        }
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(100, value));
    }

    /** Para a leitura periodica de hardware - deve ser chamado ao fechar a aplicacao. */
    public void shutdown() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }
}
