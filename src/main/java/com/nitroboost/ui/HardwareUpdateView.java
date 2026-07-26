package com.nitroboost.ui;

import com.nitroboost.core.HardwareIdentityScanner;
import com.nitroboost.core.HardwareIdentityScanner.BoardIdentity;
import com.nitroboost.core.HardwareIdentityScanner.DriverInfo;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.updates.OnlineUpdateChecker;
import com.nitroboost.updates.VendorLinkStrategy;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;

/**
 * Tela "BIOS / DRIVERS" (Fase 11): mostra fabricante/modelo/versao e data da BIOS detectados via
 * {@link HardwareIdentityScanner}, a lista de drivers relevantes instalados, botoes para abrir a
 * pagina de suporte do fabricante (Nivel 1 - deteccao automatica via
 * {@link VendorLinkStrategy#resolve(String)}, nas 3 camadas de fallback do documento da fase, 100%
 * confiavel) e um botao "Verificar Atualizacao Online" (Nivel 2 - melhor esforco, via
 * {@link OnlineUpdateChecker}, so dispara quando o usuario clica, nunca automaticamente durante a
 * deteccao inicial).
 *
 * Nenhuma acao desta tela modifica BIOS/drivers - e so deteccao + link/consulta, igual ao padrao ja
 * usado no tutorial de XMP (Fase 5). Por isso, diferente das demais telas do NITRO BOOST, esta view
 * nao usa ActionExecutor/LockManager/BackupManager (nao ha nada para bloquear ou reverter aqui).
 */
public class HardwareUpdateView extends BorderPane {

    private final Label manufacturerLabel = new Label("-");
    private final Label modelLabel = new Label("-");
    private final Label biosVersionLabel = new Label("-");
    private final Label biosDateLabel = new Label("-");
    private final Label vendorLabel = new Label("-");
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final Label statusLabel = new Label("Detectando hardware...");

    private final Button openSupportButton = new Button("🔗 Abrir Pagina de Suporte");
    private final Button openVendorSearchButton = new Button("Buscar no Site do Fabricante");
    private final Button openExternalSearchButton = new Button("Buscar no Google");
    private final Button checkOnlineButton = new Button("🌐 Verificar Atualizacao Online");
    private final ProgressIndicator onlineCheckProgress = new ProgressIndicator();
    private final Label onlineCheckStatusLabel = new Label("");

    private final TableView<DriverInfo> driversTable = new TableView<>();

    private final OnlineUpdateChecker onlineUpdateChecker;

    private VendorLinkStrategy resolvedStrategy;
    private String detectedModel = "";

    public HardwareUpdateView(DatabaseManager databaseManager) {
        this.onlineUpdateChecker = new OnlineUpdateChecker(databaseManager);
        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));

        Label title = new Label("BIOS / DRIVERS");
        title.getStyleClass().add("title-hud");
        Label subtitle = new Label("Versao atual da BIOS e drivers da placa-mae, com link direto para o site do fabricante");
        subtitle.getStyleClass().add("text-secondary");
        VBox header = new VBox(4, title, subtitle);

        progressIndicator.setPrefSize(18, 18);
        statusLabel.getStyleClass().add("text-secondary");
        HBox statusRow = new HBox(8, progressIndicator, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox identityCard = buildIdentityCard();
        VBox actionsCard = buildActionsCard();

        driversTable.getStyleClass().add("table-view");
        buildDriversColumns();
        Label driversTitle = new Label("DRIVERS RELEVANTES DETECTADOS (rede / audio-video / sistema)");
        driversTitle.getStyleClass().add("subtitle-hud");
        VBox driversBox = new VBox(8, driversTitle, driversTable);
        VBox.setVgrow(driversTable, Priority.ALWAYS);

        HBox cardsRow = new HBox(16, identityCard, actionsCard);

        VBox center = new VBox(16, header, statusRow, cardsRow, driversBox);
        VBox.setVgrow(driversBox, Priority.ALWAYS);
        setCenter(center);

        setActionsEnabled(false);
        loadInBackground();
    }

    private VBox buildIdentityCard() {
        Label cardTitle = new Label("PLACA-MAE / BIOS");
        cardTitle.getStyleClass().add("subtitle-hud");

        VBox box = new VBox(10, cardTitle,
                infoRow("Fabricante:", manufacturerLabel),
                infoRow("Modelo:", modelLabel),
                infoRow("Versao da BIOS:", biosVersionLabel),
                infoRow("Data de release:", biosDateLabel));
        box.getStyleClass().add("card");
        box.setPadding(new Insets(16));
        box.setPrefWidth(420);
        return box;
    }

    private HBox infoRow(String caption, Label valueLabel) {
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("text-secondary");
        captionLabel.setPrefWidth(140);
        valueLabel.getStyleClass().add("text-body");
        valueLabel.setWrapText(true);
        return new HBox(8, captionLabel, valueLabel);
    }

    private VBox buildActionsCard() {
        Label cardTitle = new Label("SUPORTE DO FABRICANTE");
        cardTitle.getStyleClass().add("subtitle-hud");

        Label vendorCaption = new Label("Fabricante identificado:");
        vendorCaption.getStyleClass().add("text-secondary");
        vendorLabel.getStyleClass().add("text-body");

        Label hint = new Label("Nenhuma acao aqui altera a BIOS ou os drivers automaticamente - o NITRO BOOST "
                + "so detecta o que esta instalado e te leva ate a pagina oficial. A atualizacao em si "
                + "continua sendo feita por voce, manualmente, no site do fabricante.");
        hint.getStyleClass().add("text-secondary");
        hint.setWrapText(true);

        openSupportButton.getStyleClass().add("btn-turbo");
        openSupportButton.setOnAction(e -> openUrl(resolvedStrategy == null ? null : bestDeepUrl()));

        openVendorSearchButton.getStyleClass().add("btn-secondary");
        openVendorSearchButton.setOnAction(e -> openUrl(resolvedStrategy == null ? null : resolvedStrategy.vendorSearchUrl(detectedModel)));

        openExternalSearchButton.getStyleClass().add("btn-secondary");
        openExternalSearchButton.setOnAction(e -> openUrl(resolvedStrategy == null ? null : resolvedStrategy.externalSearchUrl(detectedModel)));

        Label onlineHint = new Label("Verificacao automatica (melhor esforco): tenta ler a versao mais recente de BIOS "
                + "direto do site do fabricante. Pode nao funcionar sempre - sites de fabricante mudam de estrutura "
                + "com frequencia. Resultado fica em cache por 24h para nao sobrecarregar o site do fabricante.");
        onlineHint.getStyleClass().add("text-secondary");
        onlineHint.setWrapText(true);

        checkOnlineButton.getStyleClass().add("btn-secondary");
        checkOnlineButton.setOnAction(e -> runOnlineCheck());

        // Indicador indeterminado (Fase 12 Parte C): 1 unica requisicao HTTP, sem "quantidade" para
        // medir progresso proporcional - so mostra que algo esta acontecendo enquanto espera a rede.
        onlineCheckProgress.setPrefSize(16, 16);
        onlineCheckProgress.setVisible(false);
        onlineCheckProgress.setManaged(false);

        onlineCheckStatusLabel.getStyleClass().add("text-secondary");
        onlineCheckStatusLabel.setWrapText(true);

        HBox onlineCheckRow = new HBox(8, checkOnlineButton, onlineCheckProgress);
        onlineCheckRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, cardTitle, new HBox(8, vendorCaption, vendorLabel), hint,
                openSupportButton, openVendorSearchButton, openExternalSearchButton,
                onlineHint, onlineCheckRow, onlineCheckStatusLabel);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(16));
        VBox.setVgrow(box, Priority.ALWAYS);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * Dispara a verificacao online (Nivel 2) em thread de fundo - so quando o usuario clica neste
     * botao, nunca automaticamente. Requisicao HTTP real (ou cache local) pode levar alguns
     * segundos; qualquer falha (timeout, site fora do ar, robots.txt bloqueando, parser nao
     * implementado para este fabricante, estrutura da pagina mudou) e tratada de forma graciosa por
     * {@link OnlineUpdateChecker} - esta view so exibe a mensagem pronta que ele devolve, nunca
     * lanca excecao para a UI.
     */
    private void runOnlineCheck() {
        checkOnlineButton.setDisable(true);
        onlineCheckProgress.setVisible(true);
        onlineCheckProgress.setManaged(true);
        onlineCheckStatusLabel.getStyleClass().removeAll("text-danger", "text-success");
        onlineCheckStatusLabel.getStyleClass().add("text-secondary");
        onlineCheckStatusLabel.setText("Verificando no site do fabricante...");

        String vendor = resolvedStrategy == null ? null : resolvedStrategy.vendorName();
        String model = detectedModel;
        String pageUrl = resolvedStrategy == null ? null : bestDeepUrl();

        Thread thread = new Thread(() -> {
            OnlineUpdateChecker.CheckResult result;
            try {
                result = onlineUpdateChecker.checkForUpdate(vendor, model, pageUrl);
            } catch (Exception e) {
                result = new OnlineUpdateChecker.CheckResult(false,
                        "Nao foi possivel verificar automaticamente agora - use o link abaixo para checar manualmente.",
                        null, false);
            }
            OnlineUpdateChecker.CheckResult finalResult = result;
            Platform.runLater(() -> {
                checkOnlineButton.setDisable(false);
                onlineCheckProgress.setVisible(false);
                onlineCheckProgress.setManaged(false);
                onlineCheckStatusLabel.getStyleClass().removeAll("text-danger", "text-success", "text-secondary");
                onlineCheckStatusLabel.getStyleClass().add(finalResult.success() ? "text-success" : "text-secondary");
                onlineCheckStatusLabel.setText(finalResult.message());
            });
        }, "nitroboost-online-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Camada 1 (link profundo) se existir; cai para a camada 2 (busca do fabricante) senao. */
    private String bestDeepUrl() {
        String deep = resolvedStrategy.deepLinkUrl(detectedModel);
        return (deep != null && !deep.isBlank()) ? deep : resolvedStrategy.vendorSearchUrl(detectedModel);
    }

    private void buildDriversColumns() {
        TableColumn<DriverInfo, String> classCol = new TableColumn<>("Classe");
        classCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().deviceClass()));
        classCol.setPrefWidth(80);

        TableColumn<DriverInfo, String> nameCol = new TableColumn<>("Dispositivo");
        nameCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().deviceName()));
        nameCol.setPrefWidth(320);

        TableColumn<DriverInfo, String> versionCol = new TableColumn<>("Versao");
        versionCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().version()));
        versionCol.setPrefWidth(140);

        TableColumn<DriverInfo, String> manufacturerCol = new TableColumn<>("Fabricante");
        manufacturerCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().manufacturer()));
        manufacturerCol.setPrefWidth(200);

        driversTable.getColumns().addAll(List.of(classCol, nameCol, versionCol, manufacturerCol));
        driversTable.setPlaceholder(new Label("Nenhum driver relevante encontrado."));
    }

    /**
     * Roda a deteccao (OSHI + PowerShell, pode levar alguns segundos) em thread de fundo - mesmo
     * padrao ja usado em TutorialView/ScanResultsView, nunca na JavaFX Application Thread.
     */
    private void loadInBackground() {
        Thread thread = new Thread(() -> {
            HardwareIdentityScanner scanner = new HardwareIdentityScanner();
            BoardIdentity identity = scanner.scanBoardIdentity();
            List<DriverInfo> drivers = scanner.scanRelevantDrivers();
            Platform.runLater(() -> applyResult(identity, drivers));
        }, "nitroboost-hardware-identity-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyResult(BoardIdentity identity, List<DriverInfo> drivers) {
        progressIndicator.setVisible(false);
        statusLabel.setText("Deteccao concluida.");

        manufacturerLabel.setText(identity.manufacturerDetected() ? identity.manufacturer() : "(nao detectado)");
        modelLabel.setText(identity.modelDetected() ? identity.model() : "(nao detectado)");
        biosVersionLabel.setText(identity.biosVersion().isBlank() ? "(nao detectada)" : identity.biosVersion());
        biosDateLabel.setText(identity.biosReleaseDate().isBlank() ? "(nao detectada)" : identity.biosReleaseDate());

        detectedModel = identity.modelDetected() ? identity.model() : identity.manufacturer();
        resolvedStrategy = VendorLinkStrategy.resolve(identity.manufacturer());
        vendorLabel.setText(resolvedStrategy.vendorName());

        driversTable.setItems(FXCollections.observableArrayList(drivers));

        setActionsEnabled(!detectedModel.isBlank());
    }

    private void setActionsEnabled(boolean enabled) {
        openSupportButton.setDisable(!enabled);
        openVendorSearchButton.setDisable(!enabled || resolvedStrategy == null || resolvedStrategy.vendorSearchUrl(detectedModel) == null);
        openExternalSearchButton.setDisable(!enabled);
        checkOnlineButton.setDisable(!enabled);
    }

    /**
     * Abre a URL no navegador padrao do Windows via {@link Desktop#browse}. Nunca lanca excecao
     * para fora - pode falhar se nao houver navegador registrado ou em ambientes sem suporte a
     * Desktop (ex: sessao sem interface grafica); nesse caso so avisa o usuario, nao trava a tela.
     */
    private void openUrl(String url) {
        if (url == null || url.isBlank()) {
            showError("Nao foi possivel montar um link para este fabricante.");
            return;
        }
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                showError("Este ambiente nao suporta abrir o navegador automaticamente. Copie o link manualmente:\n" + url);
                return;
            }
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            showError("Nao foi possivel abrir o navegador (" + e.getMessage() + "). Copie o link manualmente:\n" + url);
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("NITRO BOOST");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
