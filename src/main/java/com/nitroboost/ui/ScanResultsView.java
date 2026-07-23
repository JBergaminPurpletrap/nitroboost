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
import javafx.scene.control.Button;
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

    private final Label statusLabel = new Label("Nenhuma varredura executada ainda.");
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final ComboBox<String> categoryFilter = new ComboBox<>();

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
                SystemScanTask.CATEGORY_TELEMETRY, SystemScanTask.CATEGORY_BLOATWARE);
        categoryFilter.setValue("Todos");
        categoryFilter.setOnAction(e -> applyFilter());

        Button rescanButton = new Button("↻ ESCANEAR NOVAMENTE");
        rescanButton.getStyleClass().add("btn-secondary");
        rescanButton.setOnAction(e -> startScan());

        HBox toolbar = new HBox(14, new Label("Filtrar por categoria:"), categoryFilter, rescanButton, progressIndicator);
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
        String selected = categoryFilter.getValue();
        if (selected == null || selected.equals("Todos")) {
            filteredItems.setPredicate(item -> true);
        } else {
            filteredItems.setPredicate(item -> item.category().equals(selected));
        }
    }

    /** Dispara uma nova varredura completa em background e atualiza a tabela ao concluir. */
    public void startScan() {
        statusLabel.setText("Escaneando o sistema (processos, servicos, startup, tarefas, energia, telemetria, bloatware)...");
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
