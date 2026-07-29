package com.nitroboost.ui;

import com.nitroboost.repair.RepairLock;
import com.nitroboost.repair.SystemFileRepairTool;
import com.nitroboost.repair.SystemFileRepairTool.RepairResult;
import com.nitroboost.repair.SystemFileRepairTool.SfcOutcome;
import com.nitroboost.repair.SystemFileRepairTool.SfcRunResult;
import com.nitroboost.repair.SystemFileRepairTool.Stage;
import com.nitroboost.repair.SystemFileRepairTool.StageResult;
import com.nitroboost.ui.components.NitroProgressBar;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Tela "REPARO DO SISTEMA" (Fase 15): duas frentes - Parte A (esta parte, implementada agora)
 * verifica/repara arquivos corrompidos do Windows via {@link SystemFileRepairTool} (DISM + SFC), e
 * Parte B (placeholder por enquanto, sera implementada em uma proxima tarefa apos validacao do
 * usuario) fara limpeza/reset de componentes de rede.
 *
 * <p>Mesma natureza de {@code MemoryCleaner}/secao de limpeza de RAM do {@code DashboardView}
 * (Fase 10): acao de diagnostico/reparo pontual, sem backup/reversao associada, so um aviso de
 * confirmacao antes de rodar e o resultado exibido depois - so que aqui com log em tempo real
 * (estilo terminal) e barra de progresso alimentada pelo percentual extraido da propria saida do
 * DISM/SFC, ja que o processo pode levar varios minutos.
 */
public class SystemRepairView extends BorderPane {

    private static final String FULL_WARNING = """
            Esse processo pode levar varios minutos (o SFC sozinho pode levar 10-20 minutos). \
            Nao feche o aplicativo enquanto estiver rodando.

            O DISM precisa de conexao com a internet para funcionar corretamente (usa os \
            servidores da Microsoft como fonte de arquivos).

            Enquanto o reparo estiver rodando, a navegacao para as outras telas do app fica \
            temporariamente bloqueada, para evitar rodar duas acoes administrativas ao mesmo tempo.""";

    private static final String DISM_ONLY_WARNING = """
            Esse processo pode levar varios minutos.

            O DISM precisa de conexao com a internet para funcionar corretamente (usa os \
            servidores da Microsoft como fonte de arquivos).

            Enquanto estiver rodando, a navegacao para as outras telas do app fica temporariamente \
            bloqueada, para evitar rodar duas acoes administrativas ao mesmo tempo.""";

    private static final String SFC_ONLY_WARNING = """
            Esse processo pode levar de 10 a 20 minutos. Nao feche o aplicativo enquanto estiver \
            rodando.

            Enquanto estiver rodando, a navegacao para as outras telas do app fica temporariamente \
            bloqueada, para evitar rodar duas acoes administrativas ao mesmo tempo.""";

    private final SystemFileRepairTool repairTool;

    private final Button mainButton = new Button("🛠️ Verificar e Reparar Sistema");
    private final Button dismOnlyButton = new Button("Rodar so DISM");
    private final Button sfcOnlyButton = new Button("Rodar so SFC");

    private final NitroProgressBar progressBar = new NitroProgressBar();
    private final TextArea logArea = new TextArea();
    private final Label resultLabel = new Label("");

    public SystemRepairView(AppContext context) {
        this.repairTool = context.systemFileRepairTool();

        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));

        Label title = new Label("REPARO DO SISTEMA");
        title.getStyleClass().add("title-hud");
        Label subtitle = new Label("Verificacao/reparo de arquivos do Windows e diagnostico de rede");
        subtitle.getStyleClass().add("text-secondary");
        VBox header = new VBox(4, title, subtitle);

        VBox sectionA = buildSectionA();
        VBox sectionB = buildSectionBPlaceholder();

        VBox center = new VBox(18, header, sectionA, sectionB);
        setCenter(center);
    }

    // ------------------------------------------------------------------
    // Secao A: SFC + DISM
    // ------------------------------------------------------------------

    private VBox buildSectionA() {
        Label sectionTitle = new Label("VERIFICACAO E REPARO DE ARQUIVOS DO SISTEMA (SFC + DISM)");
        sectionTitle.getStyleClass().add("subtitle-hud");

        Label description = new Label("Verifica e repara arquivos corrompidos do proprio Windows, rodando primeiro o "
                + "DISM (corrige a fonte que o SFC usa como referencia) e depois o SFC (verifica/repara os arquivos "
                + "protegidos do sistema usando essa fonte ja corrigida). Sem backup ou reversao associada - assim "
                + "como a limpeza de RAM, e uma acao pontual, nao uma configuracao a desfazer.");
        description.getStyleClass().add("text-secondary");
        description.setWrapText(true);

        mainButton.getStyleClass().add("btn-turbo");
        mainButton.setOnAction(e -> onMainButtonClicked());

        Label advancedLabel = new Label("Avancado (rodar individualmente):");
        advancedLabel.getStyleClass().add("text-secondary");

        dismOnlyButton.getStyleClass().add("btn-secondary");
        dismOnlyButton.setOnAction(e -> onDismOnlyClicked());
        sfcOnlyButton.getStyleClass().add("btn-secondary");
        sfcOnlyButton.setOnAction(e -> onSfcOnlyClicked());

        HBox advancedRow = new HBox(10, advancedLabel, dismOnlyButton, sfcOnlyButton);
        advancedRow.setAlignment(Pos.CENTER_LEFT);

        VBox actionsBox = new VBox(10, mainButton, advancedRow);

        logArea.getStyleClass().add("terminal-log");
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(14);
        logArea.setText("Aguardando inicio do reparo...");

        resultLabel.setWrapText(true);

        VBox box = new VBox(10, sectionTitle, description, actionsBox, progressBar, logArea, resultLabel);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(16));
        VBox.setVgrow(logArea, Priority.ALWAYS);
        return box;
    }

    private void onMainButtonClicked() {
        if (!confirm("Verificar e reparar arquivos do sistema?", FULL_WARNING)) {
            return;
        }
        beginRun();
        Thread thread = new Thread(() -> {
            RepairResult result = repairTool.runFullRepair(buildListener());
            Platform.runLater(() -> {
                endRun();
                showFullResult(result);
            });
        }, "nitroboost-system-repair-full");
        thread.setDaemon(true);
        thread.start();
    }

    private void onDismOnlyClicked() {
        if (!confirm("Rodar somente o DISM?", DISM_ONLY_WARNING)) {
            return;
        }
        beginRun();
        Thread thread = new Thread(() -> {
            StageResult result = repairTool.runDismOnly(buildListener());
            Platform.runLater(() -> {
                endRun();
                showDismOnlyResult(result);
            });
        }, "nitroboost-system-repair-dism");
        thread.setDaemon(true);
        thread.start();
    }

    private void onSfcOnlyClicked() {
        if (!confirm("Rodar somente o SFC?", SFC_ONLY_WARNING)) {
            return;
        }
        beginRun();
        Thread thread = new Thread(() -> {
            SfcRunResult result = repairTool.runSfcOnly(buildListener());
            Platform.runLater(() -> {
                endRun();
                showSfcOnlyResult(result);
            });
        }, "nitroboost-system-repair-sfc");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean confirm(String headerText, String warningText) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Reparo do Sistema");
        confirm.setHeaderText(headerText);
        confirm.setContentText(warningText);
        confirm.getDialogPane().setMinWidth(480);
        return confirm.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /** Trava a UI (botoes desta tela + navegacao global) e limpa o log/resultado anterior. */
    private void beginRun() {
        RepairLock.start();
        mainButton.setDisable(true);
        dismOnlyButton.setDisable(true);
        sfcOnlyButton.setDisable(true);
        logArea.setText("");
        resultLabel.setText("");
        resultLabel.getStyleClass().removeAll("text-success", "text-danger", "text-warning");
        progressBar.show();
        progressBar.setMessage("Iniciando...");
        progressBar.setProgress(0);
    }

    /** Libera a UI - chamado sempre ao final, independente do resultado ter sido sucesso ou falha. */
    private void endRun() {
        RepairLock.finish();
        mainButton.setDisable(false);
        dismOnlyButton.setDisable(false);
        sfcOnlyButton.setDisable(false);
    }

    /**
     * Constroi o listener de progresso repassado ao {@link SystemFileRepairTool} - todos os
     * callbacks chegam na thread de fundo que roda o processo, entao cada um encaminha a
     * atualizacao visual via {@link Platform#runLater}, mesmo padrao ja usado em
     * DashboardView/HardwareUpdateView para nunca tocar em nos de cena fora da JavaFX Application
     * Thread.
     */
    private SystemFileRepairTool.ProgressListener buildListener() {
        return new SystemFileRepairTool.ProgressListener() {
            @Override
            public void onStageStarted(Stage stage) {
                Platform.runLater(() -> {
                    appendLog("===== Iniciando " + stageLabel(stage) + " =====");
                    progressBar.setMessage("Rodando " + stageLabel(stage) + "...");
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS); // indeterminado ate a 1a linha com percentual chegar
                });
            }

            @Override
            public void onLogLine(Stage stage, String line) {
                Platform.runLater(() -> appendLog(line));
            }

            @Override
            public void onProgress(Stage stage, double progress) {
                Platform.runLater(() -> progressBar.setProgress(progress));
            }
        };
    }

    private void appendLog(String line) {
        logArea.appendText((logArea.getText().isEmpty() ? "" : System.lineSeparator()) + line);
    }

    private String stageLabel(Stage stage) {
        return stage == Stage.DISM ? "DISM /RestoreHealth" : "SFC /scannow";
    }

    private void showFullResult(RepairResult result) {
        progressBar.setProgress(1.0);
        progressBar.setMessage("Reparo concluido.");

        String dismLine = result.dismResult().success()
                ? "DISM: concluido com sucesso."
                : "DISM: falhou (codigo " + result.dismResult().exitCode() + ") - verifique a conexao com a internet.";

        SfcOutcome outcome = result.sfcRunResult().outcome();
        applyResultLabel(dismLine + "\n" + outcomeIcon(outcome) + " SFC: " + SystemFileRepairTool.describeOutcome(outcome), outcome);
    }

    private void showDismOnlyResult(StageResult result) {
        progressBar.setProgress(1.0);
        progressBar.setMessage("DISM concluido.");
        String text = result.success()
                ? "✅ DISM concluido com sucesso."
                : "🔴 DISM falhou (codigo " + result.exitCode() + ") - verifique a conexao com a internet.";
        resultLabel.setText(text);
        resultLabel.getStyleClass().add(result.success() ? "text-success" : "text-danger");
    }

    private void showSfcOnlyResult(SfcRunResult result) {
        progressBar.setProgress(1.0);
        progressBar.setMessage("SFC concluido.");
        SfcOutcome outcome = result.outcome();
        applyResultLabel(outcomeIcon(outcome) + " " + SystemFileRepairTool.describeOutcome(outcome), outcome);
    }

    private void applyResultLabel(String text, SfcOutcome outcome) {
        resultLabel.setText(text);
        resultLabel.getStyleClass().add(switch (outcome) {
            case NO_PROBLEMS, FIXED -> "text-success";
            case UNABLE_TO_FIX -> "text-danger";
            case UNKNOWN -> "text-warning";
        });
    }

    private String outcomeIcon(SfcOutcome outcome) {
        return switch (outcome) {
            case NO_PROBLEMS -> "✅";
            case FIXED -> "🟢";
            case UNABLE_TO_FIX -> "🔴";
            case UNKNOWN -> "⚠️";
        };
    }

    // ------------------------------------------------------------------
    // Secao B: rede (placeholder - Parte 3 desta fase, aguardando validacao do usuario)
    // ------------------------------------------------------------------

    private VBox buildSectionBPlaceholder() {
        Label sectionTitle = new Label("LIMPEZA E RESET DE COMPONENTES DE REDE");
        sectionTitle.getStyleClass().add("subtitle-hud");

        Label placeholder = new Label("Em breve: limpeza de cache de DNS e diagnostico de rede avancado "
                + "(reset do Winsock, reset da pilha TCP/IP, renovar IP, limpar cache ARP). Esta secao ainda "
                + "nao foi implementada.");
        placeholder.getStyleClass().add("text-secondary");
        placeholder.setWrapText(true);

        VBox box = new VBox(8, sectionTitle, placeholder);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(16));
        return box;
    }
}
