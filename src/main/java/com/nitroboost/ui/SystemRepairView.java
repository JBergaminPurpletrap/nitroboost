package com.nitroboost.ui;

import com.nitroboost.repair.NetworkRepairTool;
import com.nitroboost.repair.RepairLock;
import com.nitroboost.repair.SystemFileRepairTool;
import com.nitroboost.repair.SystemFileRepairTool.RepairResult;
import com.nitroboost.repair.SystemFileRepairTool.SfcOutcome;
import com.nitroboost.repair.SystemFileRepairTool.SfcRunResult;
import com.nitroboost.repair.SystemFileRepairTool.Stage;
import com.nitroboost.repair.SystemFileRepairTool.StageResult;
import com.nitroboost.ui.components.NitroProgressBar;
import com.nitroboost.validation.Fase16ValidationRunner;
import com.nitroboost.validation.ValidationReport;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/**
 * Tela "REPARO DO SISTEMA" (Fase 15): duas frentes - Parte A verifica/repara arquivos corrompidos
 * do Windows via {@link SystemFileRepairTool} (DISM + SFC), e Parte B limpa/reseta componentes de
 * rede via {@link NetworkRepairTool} (DNS, Winsock, TCP/IP, IP, ARP).
 *
 * <p>Mesma natureza de {@code MemoryCleaner}/secao de limpeza de RAM do {@code DashboardView}
 * (Fase 10): acao de diagnostico/reparo pontual, sem backup/reversao associada, so um aviso de
 * confirmacao antes de rodar (quando a acao tem algum impacto notavel) e o resultado exibido
 * depois. A Parte A usa log em tempo real (estilo terminal) e barra de progresso alimentada pelo
 * percentual extraido da propria saida do DISM/SFC, ja que o processo pode levar varios minutos; a
 * Parte B usa indicador indeterminado (mesmo padrao de {@code DashboardView#cleanRamProgress}), ja
 * que suas acoes sao rapidas e sem percentual disponivel de forma confiavel.
 *
 * <p>As duas partes compartilham a mesma trava ({@link RepairLock}) e desabilitam os botoes uma da
 * outra enquanto qualquer acao esta rodando - o layout so mostra uma tela por vez, entao rodar SFC/
 * DISM e um reset de rede ao mesmo tempo seria um conflito de acoes administrativas simultaneas
 * (mesmo motivo documentado na secao A.4 da Fase 15).
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

    private static final String WINSOCK_WARNING = """
            Isso reseta o catalogo do Winsock (componente de baixo nivel de rede do Windows) para o \
            padrao de fabrica - ajuda em problemas de conexao mais teimosos.

            ATENCAO: so tem efeito depois que voce REINICIAR o computador. Ate la, nada muda na pratica.""";

    private static final String TCPIP_WARNING = """
            Isso restaura as configuracoes do protocolo TCP/IP para o padrao de fabrica.

            ATENCAO: so tem efeito depois que voce REINICIAR o computador. Ate la, nada muda na pratica.""";

    private static final String RENEW_IP_WARNING = """
            Isso libera o endereco IP atual e pede um novo ao roteador/DHCP (ipconfig /release \
            seguido de ipconfig /renew).

            ATENCAO: a conexao de rede fica indisponivel por alguns segundos durante o processo. Nao \
            exige reiniciar o computador.""";

    private static final String SFC_DISM_OPT_IN_WARNING = """
            O SFC sozinho pode levar de 10 a 20 minutos, e o DISM mais alguns minutos - com esta opcao \
            marcada, o autoteste inteiro pode levar ate 30 minutos. Nao feche o aplicativo enquanto \
            estiver rodando.

            Se preferir um autoteste rapido, cancele e desmarque esta opcao antes de continuar.""";

    private final SystemFileRepairTool repairTool;
    private final NetworkRepairTool networkRepairTool;
    private final AppContext context;
    private final Fase16ValidationRunner fase16ValidationRunner;

    private final Button mainButton = new Button("🛠️ Verificar e Reparar Sistema");
    private final Button dismOnlyButton = new Button("Rodar so DISM");
    private final Button sfcOnlyButton = new Button("Rodar so SFC");

    private final NitroProgressBar progressBar = new NitroProgressBar();
    private final TextArea logArea = new TextArea();
    private final Label resultLabel = new Label("");

    private final Button dnsButton = new Button("🌐 Limpar Cache de DNS");
    private final ProgressIndicator dnsProgress = new ProgressIndicator();
    private final Label dnsResultLabel = new Label("");

    private final Button winsockButton = new Button("Reset do Winsock");
    private final Button tcpIpButton = new Button("Reset da Pilha TCP/IP");
    private final Button renewIpButton = new Button("Renovar IP");
    private final Button arpButton = new Button("Limpar Cache ARP");
    private final ProgressIndicator advancedProgress = new ProgressIndicator();
    private final Label advancedResultLabel = new Label("");

    private final Button autotestButton = new Button("🧪 Rodar Autoteste (Fase 16)");
    private final CheckBox includeSfcDismCheckBox = new CheckBox("Incluir SFC/DISM completo (adiciona 10-30 minutos)");
    private final NitroProgressBar autotestProgressBar = new NitroProgressBar();
    private final Label autotestResultLabel = new Label("");
    private final TextField autotestReportPathField = new TextField();
    private final Button downloadReportButton = new Button("⬇️ Baixar Relatorio");
    private Path lastAutotestReportPath;

    public SystemRepairView(AppContext context) {
        this.context = context;
        this.repairTool = context.systemFileRepairTool();
        this.networkRepairTool = context.networkRepairTool();
        this.fase16ValidationRunner = new Fase16ValidationRunner(context);

        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));

        Label title = new Label("REPARO DO SISTEMA");
        title.getStyleClass().add("title-hud");
        Label subtitle = new Label("Verificacao/reparo de arquivos do Windows e diagnostico de rede");
        subtitle.getStyleClass().add("text-secondary");
        VBox header = new VBox(4, title, subtitle);

        VBox sectionA = buildSectionA();
        VBox sectionB = buildSectionB();
        VBox sectionC = buildSectionC();

        VBox center = new VBox(18, header, sectionA, sectionB, sectionC);
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
        setSectionAButtonsDisabled(true);
        setSectionCButtonsDisabled(true);
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
        setSectionAButtonsDisabled(false);
        setSectionCButtonsDisabled(false);
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
    // Secao B: limpeza e reset de componentes de rede
    // ------------------------------------------------------------------

    private VBox buildSectionB() {
        Label sectionTitle = new Label("LIMPEZA E RESET DE COMPONENTES DE REDE");
        sectionTitle.getStyleClass().add("subtitle-hud");

        Label description = new Label("Ferramentas de diagnostico de rede que resolvem problemas comuns de "
                + "lentidao, sites 'nao abrindo' ou DNS desatualizado em cache. Sem backup ou reversao associada "
                + "- assim como o reparo de arquivos do sistema acima, sao acoes pontuais, nao configuracoes a "
                + "desfazer.");
        description.getStyleClass().add("text-secondary");
        description.setWrapText(true);

        dnsButton.getStyleClass().add("btn-turbo");
        dnsButton.setOnAction(e -> onDnsClicked());

        dnsProgress.setPrefSize(18, 18);
        dnsProgress.setVisible(false);
        dnsProgress.setManaged(false);
        dnsResultLabel.getStyleClass().add("text-secondary");
        dnsResultLabel.setWrapText(true);

        HBox dnsRow = new HBox(16, dnsButton, dnsProgress, dnsResultLabel);
        dnsRow.setAlignment(Pos.CENTER_LEFT);

        Label advancedTitle = new Label("Diagnostico de Rede Avancado");
        advancedTitle.getStyleClass().add("subtitle-hud");

        Label advancedHint = new Label("Reset do Winsock e Reset da Pilha TCP/IP so fazem efeito depois que "
                + "voce REINICIAR o computador. Renovar IP nao exige reiniciar, mas derruba a conexao de rede "
                + "por alguns segundos. Limpar Cache ARP e seguro e instantaneo, sem nenhum efeito colateral.");
        advancedHint.getStyleClass().add("text-secondary");
        advancedHint.setWrapText(true);

        winsockButton.getStyleClass().add("btn-secondary");
        winsockButton.setOnAction(e -> onAdvancedClicked("Reset do Winsock", WINSOCK_WARNING, networkRepairTool::resetWinsock));
        tcpIpButton.getStyleClass().add("btn-secondary");
        tcpIpButton.setOnAction(e -> onAdvancedClicked("Reset da Pilha TCP/IP", TCPIP_WARNING, networkRepairTool::resetTcpIp));
        renewIpButton.getStyleClass().add("btn-secondary");
        renewIpButton.setOnAction(e -> onAdvancedClicked("Renovar IP", RENEW_IP_WARNING, networkRepairTool::renewIp));
        arpButton.getStyleClass().add("btn-secondary");
        arpButton.setOnAction(e -> onAdvancedClicked("Limpar Cache ARP", null, networkRepairTool::clearArpCache));

        HBox advancedButtons = new HBox(10, winsockButton, tcpIpButton, renewIpButton, arpButton);
        advancedButtons.setAlignment(Pos.CENTER_LEFT);

        advancedProgress.setPrefSize(18, 18);
        advancedProgress.setVisible(false);
        advancedProgress.setManaged(false);
        advancedResultLabel.getStyleClass().add("text-secondary");
        advancedResultLabel.setWrapText(true);

        HBox advancedResultRow = new HBox(16, advancedProgress, advancedResultLabel);
        advancedResultRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, sectionTitle, description, dnsRow, advancedTitle, advancedHint,
                advancedButtons, advancedResultRow);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(16));
        return box;
    }

    private void onDnsClicked() {
        beginNetworkRun();
        dnsProgress.setVisible(true);
        dnsProgress.setManaged(true);
        dnsResultLabel.getStyleClass().removeAll("text-success", "text-danger");
        dnsResultLabel.setText("Limpando cache de DNS...");

        Thread thread = new Thread(() -> {
            NetworkRepairTool.NetworkRepairResult result = networkRepairTool.flushDns();
            Platform.runLater(() -> {
                dnsProgress.setVisible(false);
                dnsProgress.setManaged(false);
                endNetworkRun();
                dnsResultLabel.getStyleClass().add(result.success() ? "text-success" : "text-danger");
                dnsResultLabel.setText((result.success() ? "✅ " : "🔴 ") + result.message());
            });
        }, "nitroboost-flush-dns");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Executa uma das 4 acoes de "Diagnostico de Rede Avancado". Quando {@code warningText} nao e
     * nulo, exige confirmacao explicita antes de rodar (mesmo padrao ja usado para acoes impactantes
     * do projeto, ex: desinstalacao do OneDrive na Fase 12) - usado para Winsock/TCP-IP (exigem
     * reinicio) e Renovar IP (derruba a conexao momentaneamente). Limpar Cache ARP passa
     * {@code null} porque e seguro e instantaneo, sem necessidade de aviso extra.
     */
    private void onAdvancedClicked(String actionLabel, String warningText,
                                    Supplier<NetworkRepairTool.NetworkRepairResult> action) {
        if (warningText != null && !confirm(actionLabel + "?", warningText)) {
            return;
        }
        beginNetworkRun();
        advancedProgress.setVisible(true);
        advancedProgress.setManaged(true);
        advancedResultLabel.getStyleClass().removeAll("text-success", "text-danger");
        advancedResultLabel.setText("Executando: " + actionLabel + "...");

        Thread thread = new Thread(() -> {
            NetworkRepairTool.NetworkRepairResult result = action.get();
            Platform.runLater(() -> {
                advancedProgress.setVisible(false);
                advancedProgress.setManaged(false);
                endNetworkRun();
                advancedResultLabel.getStyleClass().add(result.success() ? "text-success" : "text-danger");
                advancedResultLabel.setText((result.success() ? "✅ " : "🔴 ") + result.message());
            });
        }, "nitroboost-network-advanced");
        thread.setDaemon(true);
        thread.start();
    }

    /** Trava a UI inteira da tela (secao A + secao B + secao C) e a navegacao global, igual ao reparo de arquivos. */
    private void beginNetworkRun() {
        RepairLock.start();
        setSectionAButtonsDisabled(true);
        setSectionBButtonsDisabled(true);
        setSectionCButtonsDisabled(true);
    }

    private void endNetworkRun() {
        RepairLock.finish();
        setSectionAButtonsDisabled(false);
        setSectionBButtonsDisabled(false);
        setSectionCButtonsDisabled(false);
    }

    private void setSectionAButtonsDisabled(boolean disabled) {
        mainButton.setDisable(disabled);
        dismOnlyButton.setDisable(disabled);
        sfcOnlyButton.setDisable(disabled);
    }

    private void setSectionBButtonsDisabled(boolean disabled) {
        dnsButton.setDisable(disabled);
        winsockButton.setDisable(disabled);
        tcpIpButton.setDisable(disabled);
        renewIpButton.setDisable(disabled);
        arpButton.setDisable(disabled);
    }

    private void setSectionCButtonsDisabled(boolean disabled) {
        autotestButton.setDisable(disabled);
        includeSfcDismCheckBox.setDisable(disabled);
    }

    // ------------------------------------------------------------------
    // Secao C: Autoteste (Fase 16)
    // ------------------------------------------------------------------

    private VBox buildSectionC() {
        Label sectionTitle = new Label("🧪 AUTOTESTE (FASE 16)");
        sectionTitle.getStyleClass().add("subtitle-hud");

        Label description = new Label("Roda automaticamente o maximo possivel do roteiro de validacao com "
                + "Administrador (docs/NITRO-BOOST-fase16-validacao-administrador.md) e gera um relatorio em "
                + "Markdown para voce trazer de volta e finalizar a validacao. Itens que so podem ser confirmados "
                + "visualmente (tema, animacoes, comparar com uma tela nativa do Windows) ficam marcados no "
                + "relatorio - nunca sao fingidos como verificados.");
        description.getStyleClass().add("text-secondary");
        description.setWrapText(true);

        includeSfcDismCheckBox.getStyleClass().add("text-secondary");

        autotestButton.getStyleClass().add("btn-turbo");
        autotestButton.setOnAction(e -> onAutotestClicked());

        HBox actionsRow = new HBox(14, autotestButton, includeSfcDismCheckBox);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        // Fica logo abaixo da opcao de rodar o autoteste - so habilita depois que um relatorio
        // existe de verdade (nesta execucao ou de uma pasta ja salva), nunca antes.
        downloadReportButton.getStyleClass().add("btn-secondary");
        downloadReportButton.setDisable(true);
        downloadReportButton.setOnAction(e -> onDownloadReportClicked());
        HBox downloadRow = new HBox(downloadReportButton);
        downloadRow.setAlignment(Pos.CENTER_LEFT);

        autotestResultLabel.setWrapText(true);

        Label reportLabel = new Label("Arquivo de relatorio gerado:");
        reportLabel.getStyleClass().add("text-secondary");
        autotestReportPathField.setEditable(false);
        autotestReportPathField.setPrefWidth(520);
        HBox reportRow = new HBox(10, reportLabel, autotestReportPathField);
        reportRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, sectionTitle, description, actionsRow, downloadRow, autotestProgressBar,
                autotestResultLabel, reportRow);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(16));
        return box;
    }

    /**
     * Abre um dialogo "Salvar como" para o usuario escolher onde guardar uma copia do relatorio do
     * autoteste (ex: area de trabalho, pendrive) - o relatorio ja fica salvo automaticamente em
     * {@code %USERPROFILE%\.nitroboost\}, este botao e so uma forma mais direta de levar o arquivo
     * para onde o usuario quiser sem precisar navegar ate essa pasta manualmente.
     */
    private void onDownloadReportClicked() {
        if (lastAutotestReportPath == null || !Files.exists(lastAutotestReportPath)) {
            autotestResultLabel.getStyleClass().removeAll("text-success", "text-warning");
            autotestResultLabel.getStyleClass().add("text-danger");
            autotestResultLabel.setText("🔴 Nenhum relatorio disponivel para baixar ainda - rode o autoteste primeiro.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Salvar relatorio do Autoteste (Fase 16)");
        chooser.setInitialFileName(lastAutotestReportPath.getFileName().toString());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown (*.md)", "*.md"));

        Window ownerWindow = getScene() != null ? getScene().getWindow() : null;
        java.io.File destination = chooser.showSaveDialog(ownerWindow);
        if (destination == null) {
            return;
        }

        try {
            Files.copy(lastAutotestReportPath, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            autotestResultLabel.getStyleClass().removeAll("text-danger", "text-warning");
            autotestResultLabel.getStyleClass().add("text-success");
            autotestResultLabel.setText("✅ Relatorio salvo em: " + destination.getAbsolutePath());
        } catch (IOException e) {
            autotestResultLabel.getStyleClass().removeAll("text-success", "text-warning");
            autotestResultLabel.getStyleClass().add("text-danger");
            autotestResultLabel.setText("🔴 Falha ao salvar copia do relatorio: " + e.getMessage());
        }
    }

    private void onAutotestClicked() {
        boolean includeFull = includeSfcDismCheckBox.isSelected();
        if (includeFull && !confirm("Incluir SFC/DISM completo no autoteste?", SFC_DISM_OPT_IN_WARNING)) {
            return;
        }

        beginAutotestRun();
        Thread thread = new Thread(() -> {
            ValidationReport report = fase16ValidationRunner.run(includeFull, buildAutotestListener());
            Platform.runLater(() -> {
                endAutotestRun();
                showAutotestResult(report);
            });
        }, "nitroboost-fase16-autoteste");
        thread.setDaemon(true);
        thread.start();
    }

    private Fase16ValidationRunner.Listener buildAutotestListener() {
        return new Fase16ValidationRunner.Listener() {
            @Override
            public void onCheckStarted(int section, String item) {
                Platform.runLater(() -> autotestProgressBar.setMessage("Secao " + section + ": " + item + "..."));
            }
        };
    }

    private void beginAutotestRun() {
        beginNetworkRun(); // reaproveita a trava global (RepairLock) e desabilita as secoes A e B tambem
        autotestResultLabel.setText("");
        autotestResultLabel.getStyleClass().removeAll("text-success", "text-danger", "text-warning");
        autotestReportPathField.setText("");
        lastAutotestReportPath = null;
        downloadReportButton.setDisable(true);
        autotestProgressBar.show();
        autotestProgressBar.setMessage("Iniciando autoteste...");
        autotestProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
    }

    private void endAutotestRun() {
        endNetworkRun();
        autotestProgressBar.hide();
    }

    private void showAutotestResult(ValidationReport report) {
        ValidationReport.Summary summary = report.summary();
        String summaryText = summary.passed() + " passaram, " + summary.failed() + " falharam, "
                + summary.skipped() + " puladas deliberadamente, " + summary.visual()
                + " requerem confirmacao visual (" + summary.total() + " itens no total).";

        try {
            Path reportDir = context.databaseManager().getDatabasePath().getParent();
            Path savedPath = report.save(reportDir);
            autotestReportPathField.setText(savedPath.toAbsolutePath().toString());
            lastAutotestReportPath = savedPath;
            downloadReportButton.setDisable(false);

            String prefix = report.elevated() ? "✅ " : "⚠️ ";
            String suffix = report.elevated() ? "" : " ATENCAO: esta execucao nao estava elevada (Administrador) - "
                    + "a maioria das falhas acima e esperada por esse motivo. Rode como Administrador para uma "
                    + "validacao completa.";
            autotestResultLabel.setText(prefix + summaryText + suffix);
            autotestResultLabel.getStyleClass().add(summary.failed() == 0 && report.elevated() ? "text-success" : "text-warning");
        } catch (IOException e) {
            autotestReportPathField.setText("(falha ao salvar: " + e.getMessage() + ")");
            autotestResultLabel.setText("🔴 Autoteste concluido (" + summaryText + "), mas houve falha ao salvar "
                    + "o arquivo de relatorio: " + e.getMessage());
            autotestResultLabel.getStyleClass().add("text-danger");
        }
    }
}
