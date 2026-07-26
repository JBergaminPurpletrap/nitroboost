package com.nitroboost.ui;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.knowledge.ItemClassification;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tela de resultados da varredura: tabela unificada com todos os itens
 * encontrados pelos 7 scanners, filtro por categoria e botoes de acao rapida
 * (Detalhes / Desativar-Finalizar-Desinstalar-Ativar / Bloquear-Desbloquear)
 * em cada linha, todos chamando o backend real (ActionExecutor/LockManager).
 */
public class ScanResultsView extends BorderPane {

    private final AppContext context;
    private final Runnable openTutorialCallback;

    private final ObservableList<ScannedItem> masterItems = FXCollections.observableArrayList();
    private final FilteredList<ScannedItem> filteredItems = new FilteredList<>(masterItems, i -> true);
    private final TableView<ScannedItem> table = new TableView<>(filteredItems);

    private static final String CLASSIFICATION_FILTER_ALL = "Todos os status";
    private static final String CLASSIFICATION_FILTER_SEGURO = "🟢 Seguro";
    private static final String CLASSIFICATION_FILTER_DEPENDE = "🟡 Depende";
    private static final String CLASSIFICATION_FILTER_ESSENCIAL = "🔴 Essencial";

    private final Label statusLabel = new Label("Nenhuma varredura executada ainda.");
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final ComboBox<String> classificationFilter = new ComboBox<>();
    private final CheckBox knownOnlyFilter = new CheckBox("Mostrar apenas itens conhecidos");
    private final Button closeAllGreenButton = new Button("FECHAR TODOS OS ITENS VERDES");

    public ScanResultsView(AppContext context, Runnable openTutorialCallback) {
        this.context = context;
        this.openTutorialCallback = openTutorialCallback;

        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));

        Label title = new Label("RESULTADOS DA VARREDURA");
        title.getStyleClass().add("title-hud");

        progressIndicator.setPrefSize(20, 20);
        progressIndicator.setVisible(false);

        categoryFilter.getItems().addAll("Todos", SystemScanTask.CATEGORY_PROCESS, SystemScanTask.CATEGORY_SERVICE,
                SystemScanTask.CATEGORY_STARTUP, SystemScanTask.CATEGORY_TASK, SystemScanTask.CATEGORY_POWERPLAN,
                SystemScanTask.CATEGORY_TELEMETRY, SystemScanTask.CATEGORY_BLOATWARE,
                SystemScanTask.CATEGORY_PERFORMANCE, SystemScanTask.CATEGORY_GAMING,
                SystemScanTask.CATEGORY_AI, SystemScanTask.CATEGORY_CONSUMER);
        categoryFilter.setValue("Todos");
        categoryFilter.setOnAction(e -> applyFilter());

        classificationFilter.getItems().addAll(CLASSIFICATION_FILTER_ALL, CLASSIFICATION_FILTER_SEGURO,
                CLASSIFICATION_FILTER_DEPENDE, CLASSIFICATION_FILTER_ESSENCIAL);
        classificationFilter.setValue(CLASSIFICATION_FILTER_ALL);
        classificationFilter.setOnAction(e -> applyFilter());

        Button rescanButton = new Button("↻ ESCANEAR NOVAMENTE");
        rescanButton.getStyleClass().add("btn-secondary");
        rescanButton.setOnAction(e -> startScan());

        closeAllGreenButton.getStyleClass().add("btn-turbo");
        closeAllGreenButton.setOnAction(e -> closeAllGreen());

        // Ligado por padrao: reduz ruido visual (ex: bloatware agora lista TODOS os
        // apps UWP, nao so os ~15 reconhecidos - ver Fase 8/PROGRESS.md). O usuario
        // pode desligar para ver a lista completa, incluindo itens nao catalogados.
        knownOnlyFilter.setSelected(true);
        knownOnlyFilter.getStyleClass().add("text-secondary");
        knownOnlyFilter.setOnAction(e -> applyFilter());

        HBox toolbar = new HBox(14, new Label("Filtrar por categoria:"), categoryFilter,
                new Label("Status:"), classificationFilter, knownOnlyFilter, rescanButton, closeAllGreenButton, progressIndicator);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        for (var node : toolbar.getChildren()) {
            if (node instanceof Label l) {
                l.getStyleClass().add("text-secondary");
            }
        }

        buildColumns();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(table, Priority.ALWAYS);

        statusLabel.getStyleClass().add("text-secondary");

        VBox root = new VBox(14, title, toolbar, table, statusLabel);
        VBox.setVgrow(table, Priority.ALWAYS);
        setCenter(root);
    }

    private void buildColumns() {
        TableColumn<ScannedItem, ScannedItem> statusCol = new TableColumn<>("");
        statusCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ScannedItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    Circle dot = new Circle(6, classificationColor(item.classification()));
                    setGraphic(dot);
                }
            }
        });
        statusCol.setMaxWidth(36);
        statusCol.setMinWidth(36);

        TableColumn<ScannedItem, String> categoryCol = new TableColumn<>("Categoria");
        categoryCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().category()));

        TableColumn<ScannedItem, String> nameCol = new TableColumn<>("Nome");
        nameCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().name()));

        TableColumn<ScannedItem, String> stateCol = new TableColumn<>("Estado Atual");
        stateCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().currentState()));

        TableColumn<ScannedItem, String> classificationCol = new TableColumn<>("Classificacao");
        classificationCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().classification().label()));

        TableColumn<ScannedItem, ScannedItem> actionsCol = new TableColumn<>("Acoes");
        actionsCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        actionsCol.setCellFactory(col -> new ActionsCell());
        actionsCol.setMinWidth(300);
        actionsCol.setPrefWidth(320);

        table.getColumns().addAll(statusCol, categoryCol, nameCol, stateCol, classificationCol, actionsCol);
    }

    private Color classificationColor(ItemClassification classification) {
        return switch (classification) {
            case SEGURO -> Color.web(Theme.COLOR_NEON_GREEN);
            case ESSENCIAL -> Color.web(Theme.COLOR_RED);
            case DEPENDE -> Color.web(Theme.COLOR_AMBER);
        };
    }

    private void applyFilter() {
        String selectedCategory = categoryFilter.getValue();
        ItemClassification selectedClassification = classificationFromFilterLabel(classificationFilter.getValue());
        boolean knownOnly = knownOnlyFilter.isSelected();
        filteredItems.setPredicate(item -> {
            boolean categoryOk = selectedCategory == null || selectedCategory.equals("Todos")
                    || item.category().equals(selectedCategory);
            boolean classificationOk = selectedClassification == null || item.classification() == selectedClassification;
            boolean knownOk = !knownOnly || item.catalogued();
            return categoryOk && classificationOk && knownOk;
        });
    }

    private ItemClassification classificationFromFilterLabel(String label) {
        if (label == null) {
            return null;
        }
        return switch (label) {
            case CLASSIFICATION_FILTER_SEGURO -> ItemClassification.SEGURO;
            case CLASSIFICATION_FILTER_DEPENDE -> ItemClassification.DEPENDE;
            case CLASSIFICATION_FILTER_ESSENCIAL -> ItemClassification.ESSENCIAL;
            default -> null;
        };
    }

    /**
     * Executa a acao principal (finalizar/desativar/desinstalar, conforme o tipo)
     * em todos os itens classificados como "seguro" (verde) atualmente visiveis
     * na tabela (respeitando os filtros ativos), um por vez. Cada item passa
     * pelo fluxo normal do ActionExecutor (lock + backup + historico) - a acao
     * em massa nao pula nenhuma dessas garantias, so dispara varias sequenciais.
     */
    private void closeAllGreen() {
        List<ScannedItem> targets = filteredItems.stream()
                .filter(item -> item.classification() == ItemClassification.SEGURO)
                .toList();
        if (targets.isEmpty()) {
            statusLabel.getStyleClass().removeAll("text-danger", "text-success");
            statusLabel.setText("Nenhum item verde (seguro) na lista atual para fechar.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirmar acao em massa");
        confirm.setHeaderText("Fechar " + targets.size() + " item(ns) classificado(s) como seguro?");
        confirm.setContentText("Cada item passa pelo backup e historico normal antes de ser desativado. "
                + "Itens bloqueados serao recusados automaticamente, sem interromper os demais.");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return;
        }

        closeAllGreenButton.setDisable(true);
        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
        statusLabel.setText("Fechando " + targets.size() + " item(ns) verde(s)...");

        Thread thread = new Thread(() -> {
            List<ScannedItem> succeeded = new ArrayList<>();
            int failed = 0;
            for (ScannedItem item : targets) {
                try {
                    ActionExecutor.ActionResult result = ItemActionDispatcher.performPrimaryAction(context.actionExecutor(), item);
                    if (result.success()) {
                        succeeded.add(item);
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                }
            }
            int finalFailed = failed;
            Platform.runLater(() -> {
                masterItems.removeAll(succeeded);
                closeAllGreenButton.setDisable(false);
                statusLabel.getStyleClass().removeAll("text-danger", "text-success");
                statusLabel.getStyleClass().add(finalFailed == 0 ? "text-success" : "text-danger");
                statusLabel.setText(succeeded.size() + " item(ns) fechado(s) com sucesso"
                        + (finalFailed > 0 ? ", " + finalFailed + " falharam (ver Historico para detalhes)." : "."));
            });
        }, "nitroboost-bulk-close-green");
        thread.setDaemon(true);
        thread.start();
    }

    /** Dispara uma nova varredura completa em background e atualiza a tabela ao concluir. */
    public void startScan() {
        statusLabel.setText("Escaneando o sistema (processos, servicos, startup, tarefas, energia, telemetria, "
                + "bloatware, performance, jogos)...");
        progressIndicator.setVisible(true);

        SystemScanTask task = new SystemScanTask(context.knowledgeBase());
        task.setOnSucceeded(e -> {
            masterItems.setAll(task.getValue());
            applyFilter();
            progressIndicator.setVisible(false);
            statusLabel.setText(masterItems.size() + " itens encontrados.");
        });
        task.setOnFailed(e -> {
            progressIndicator.setVisible(false);
            Throwable ex = task.getException();
            statusLabel.getStyleClass().add("text-danger");
            statusLabel.setText("Falha ao escanear o sistema: " + (ex != null ? ex.getMessage() : "erro desconhecido"));
        });

        Thread thread = new Thread(task, "nitroboost-scan");
        thread.setDaemon(true);
        thread.start();
    }

    /** Executa uma acao do backend em thread separada para nao travar a UI; devolve o resultado na FX thread. */
    void runBackendAction(Supplier<ActionExecutor.ActionResult> action, Consumer<ActionExecutor.ActionResult> onDone) {
        Thread thread = new Thread(() -> {
            ActionExecutor.ActionResult result;
            try {
                result = action.get();
            } catch (Exception e) {
                result = new ActionExecutor.ActionResult(false, "Erro inesperado: " + e.getMessage(), null);
            }
            ActionExecutor.ActionResult finalResult = result;
            Platform.runLater(() -> onDone.accept(finalResult));
        }, "nitroboost-action");
        thread.setDaemon(true);
        thread.start();
    }

    private void showResultStatus(ActionExecutor.ActionResult result) {
        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
        statusLabel.getStyleClass().add(result.success() ? "text-success" : "text-danger");
        statusLabel.setText(result.message());
    }

    void openDetail(ScannedItem item) {
        ItemDetailView.show(getScene() != null ? getScene().getWindow() : null, context, item,
                this::afterActionPerformed, openTutorialCallback);
    }

    /** Chamado quando uma acao (desativar/bloquear) foi realizada com sucesso a partir do modal de detalhes. */
    private void afterActionPerformed() {
        statusLabel.setText("Acao aplicada - clique em \"Escanear novamente\" para atualizar a lista.");
    }

    /** Celula com os 3 botoes de acao rapida de cada linha: Detalhes / acao primaria / Bloquear-Desbloquear. */
    private class ActionsCell extends TableCell<ScannedItem, ScannedItem> {
        private final Button detailsButton = new Button("Detalhes");
        private final Button primaryButton = new Button();
        private final Button lockButton = new Button();
        private final HBox box = new HBox(6, detailsButton, primaryButton, lockButton);

        ActionsCell() {
            detailsButton.getStyleClass().add("btn-secondary");
            primaryButton.getStyleClass().add("btn-secondary");
            lockButton.getStyleClass().add("btn-secondary");
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(ScannedItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            detailsButton.setOnAction(e -> openDetail(item));

            primaryButton.setText(ItemActionDispatcher.primaryActionLabel(item.type()));
            primaryButton.getStyleClass().removeAll("btn-secondary", "btn-danger");
            primaryButton.getStyleClass().add(item.classification() == ItemClassification.ESSENCIAL ? "btn-danger" : "btn-secondary");
            primaryButton.setOnAction(e -> {
                // Acoes drasticas demais para o confirm padrao (hoje, so a desinstalacao completa do
                // OneDrive - Fase 12 Parte A) exigem um aviso extra-explicito ANTES de chegar ao
                // ActionExecutor - sem isso, nao e possivel clicar neste botao sem ler o risco.
                if (ItemActionDispatcher.requiresExtraConfirmation(item)) {
                    Window window = getScene() != null ? getScene().getWindow() : null;
                    if (!DestructiveActionConfirmation.confirmOneDriveUninstall(window)) {
                        return;
                    }
                }
                primaryButton.setDisable(true);
                runBackendAction(
                        () -> ItemActionDispatcher.performPrimaryAction(context.actionExecutor(), item),
                        result -> {
                            showResultStatus(result);
                            primaryButton.setDisable(false);
                            if (result.success()) {
                                masterItems.remove(item);
                            }
                        });
            });

            boolean locked = context.lockManager().isLocked(item.name(), item.type());
            lockButton.setText(locked ? "Desbloquear" : "Bloquear");
            lockButton.setOnAction(e -> {
                lockButton.setDisable(true);
                runBackendAction(
                        () -> {
                            LockManager.LockResult lockResult = locked
                                    ? context.lockManager().unlockItem(item.name(), item.type())
                                    : context.lockManager().lockItem(item.name(), item.type(), "Bloqueado pelo usuario via interface grafica");
                            return new ActionExecutor.ActionResult(lockResult.success(), lockResult.message(), null);
                        },
                        result -> {
                            showResultStatus(result);
                            lockButton.setDisable(false);
                            lockButton.setText(context.lockManager().isLocked(item.name(), item.type()) ? "Desbloquear" : "Bloquear");
                        });
            });

            setGraphic(box);
        }
    }
}
