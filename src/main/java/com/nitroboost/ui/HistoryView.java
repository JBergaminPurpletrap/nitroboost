package com.nitroboost.ui;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.db.ActionHistoryRepository;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Lista cronologica das acoes registradas em {@code actions_history} (mais
 * recente primeiro), com botao "Reverter" em cada linha - chama {@code
 * ActionExecutor.restoreFromHistory}, o mesmo caminho ja validado via
 * console nas Fases 1 a 3.
 */
public class HistoryView extends BorderPane {

    private static final int HISTORY_LIMIT = 100;

    private final AppContext context;
    private final ObservableList<ActionHistoryRepository.HistoryEntry> entries = FXCollections.observableArrayList();
    private final TableView<ActionHistoryRepository.HistoryEntry> table = new TableView<>(entries);
    private final Label statusLabel = new Label();

    public HistoryView(AppContext context) {
        this.context = context;

        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));

        Label title = new Label("HISTORICO DE ACOES");
        title.getStyleClass().add("title-hud");

        Button refreshButton = new Button("↻ ATUALIZAR");
        refreshButton.getStyleClass().add("btn-secondary");
        refreshButton.setOnAction(e -> refresh());

        HBox toolbar = new HBox(14, title, spacer(), refreshButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        buildColumns();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        statusLabel.getStyleClass().add("text-secondary");

        VBox root = new VBox(14, toolbar, table, statusLabel);
        VBox.setVgrow(table, Priority.ALWAYS);
        setCenter(root);
    }

    private HBox spacer() {
        HBox box = new HBox();
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private void buildColumns() {
        TableColumn<ActionHistoryRepository.HistoryEntry, String> whenCol = new TableColumn<>("Data/Hora");
        whenCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().performedAt()));

        TableColumn<ActionHistoryRepository.HistoryEntry, String> actionCol = new TableColumn<>("Acao");
        actionCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().actionType()));

        TableColumn<ActionHistoryRepository.HistoryEntry, String> itemCol = new TableColumn<>("Item");
        itemCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().itemName() + " (" + d.getValue().itemType() + ")"));

        TableColumn<ActionHistoryRepository.HistoryEntry, String> transitionCol = new TableColumn<>("De -> Para");
        transitionCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                nullToDash(d.getValue().previousState()) + " -> " + nullToDash(d.getValue().newState())));

        TableColumn<ActionHistoryRepository.HistoryEntry, String> resultCol = new TableColumn<>("Resultado");
        resultCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                d.getValue().success() ? "Sucesso" : "Falhou: " + nullToDash(d.getValue().errorMessage())));

        TableColumn<ActionHistoryRepository.HistoryEntry, ActionHistoryRepository.HistoryEntry> revertCol = new TableColumn<>("Reverter");
        revertCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        revertCol.setCellFactory(col -> new RevertCell());
        revertCol.setMinWidth(140);

        table.getColumns().addAll(whenCol, actionCol, itemCol, transitionCol, resultCol, revertCol);
    }

    private String nullToDash(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }

    /** Recarrega o historico do banco (leitura local rapida - roda direto na FX thread, protegida por try/catch). */
    public void refresh() {
        try {
            entries.setAll(context.historyRepository().findRecent(HISTORY_LIMIT));
            statusLabel.getStyleClass().removeAll("text-danger");
            statusLabel.setText(entries.size() + " acoes no historico (mais recente primeiro).");
        } catch (Exception e) {
            statusLabel.getStyleClass().add("text-danger");
            statusLabel.setText("Erro ao carregar historico: " + e.getMessage());
        }
    }

    private class RevertCell extends TableCell<ActionHistoryRepository.HistoryEntry, ActionHistoryRepository.HistoryEntry> {
        private final Button revertButton = new Button("Reverter");

        RevertCell() {
            revertButton.getStyleClass().add("btn-secondary");
        }

        @Override
        protected void updateItem(ActionHistoryRepository.HistoryEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            revertButton.setDisable(!entry.success() || "restore".equals(entry.actionType()));
            revertButton.setOnAction(e -> {
                revertButton.setDisable(true);
                Thread thread = new Thread(() -> {
                    ActionExecutor.ActionResult result;
                    try {
                        result = context.actionExecutor().restoreFromHistory(entry.id());
                    } catch (Exception ex) {
                        result = new ActionExecutor.ActionResult(false, "Erro inesperado ao reverter: " + ex.getMessage(), null);
                    }
                    ActionExecutor.ActionResult finalResult = result;
                    Platform.runLater(() -> {
                        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
                        statusLabel.getStyleClass().add(finalResult.success() ? "text-success" : "text-danger");
                        statusLabel.setText(finalResult.message());
                        refresh();
                    });
                }, "nitroboost-revert");
                thread.setDaemon(true);
                thread.start();
            });
            setGraphic(revertButton);
        }
    }
}
