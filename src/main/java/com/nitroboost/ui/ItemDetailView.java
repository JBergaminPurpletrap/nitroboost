package com.nitroboost.ui;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.knowledge.ItemClassification;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Modal de detalhes de um item ({@link ScannedItem}): nome, descricao,
 * impacto de desativar/manter e recomendacao, vindos da {@code
 * KnowledgeBase}, com botoes Desativar / Bloquear / Ver Tutorial.
 *
 * Implementado como um {@link Stage} modal simples (sem FXML, conforme
 * recomendado no guia de skills tecnicas para telas dinamicas) em vez de um
 * {@code Dialog<>} do JavaFX, para ter controle total do layout/tema.
 */
public final class ItemDetailView {

    private ItemDetailView() {
        // Classe utilitaria, nao deve ser instanciada.
    }

    /**
     * Abre o modal de detalhes do item e bloqueia ate ser fechado.
     *
     * @param owner               janela dona do modal (pode ser {@code null})
     * @param context             contexto com acesso ao backend
     * @param item                item a detalhar
     * @param onActionPerformed   chamado quando uma acao (desativar/bloquear) foi concluida com sucesso,
     *                            para a tela que abriu o modal poder atualizar seu proprio estado
     * @param openTutorialCallback chamado quando o usuario clica em "Ver Tutorial" (fecha o modal e navega ate a TutorialView)
     */
    public static void show(Window owner, AppContext context, ScannedItem item,
                             Runnable onActionPerformed, Runnable openTutorialCallback) {
        Stage stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setTitle(item.name());

        Label nameLabel = new Label(item.name());
        nameLabel.getStyleClass().add("title-hud");

        Label badge = new Label(item.classification().label().toUpperCase());
        badge.getStyleClass().addAll("status-badge", switch (item.classification()) {
            case SEGURO -> "status-badge-safe";
            case ESSENCIAL -> "status-badge-essential";
            case DEPENDE -> "status-badge-depends";
        });

        Label categoryLabel = new Label(item.category() + " • " + item.currentState());
        categoryLabel.getStyleClass().add("text-secondary");

        HBox headerRow = new HBox(10, badge, categoryLabel);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Label descriptionTitle = sectionTitle("O QUE É");
        Label descriptionLabel = bodyText(item.description());

        Label disableTitle = sectionTitle("IMPACTO DE DESATIVAR");
        Label disableLabel = bodyText(item.disableImpact());

        Label keepTitle = sectionTitle("IMPACTO DE MANTER");
        Label keepLabel = bodyText(item.keepImpact());

        Label statusMessage = new Label();
        statusMessage.getStyleClass().add("text-secondary");
        statusMessage.setWrapText(true);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);

        boolean[] locked = {context.lockManager().isLocked(item.name(), item.type())};

        Button primaryButton = new Button(ItemActionDispatcher.primaryActionLabel(item.type()));
        primaryButton.getStyleClass().add(item.classification() == ItemClassification.ESSENCIAL ? "btn-danger" : "btn-turbo");

        Button lockButton = new Button(locked[0] ? "Desbloquear" : "Bloquear");
        lockButton.getStyleClass().add("btn-secondary");

        Button tutorialButton = new Button("Ver Tutorial");
        tutorialButton.getStyleClass().add("btn-secondary");
        tutorialButton.setOnAction(e -> {
            stage.close();
            if (openTutorialCallback != null) {
                openTutorialCallback.run();
            }
        });

        primaryButton.setOnAction(e -> {
            primaryButton.setDisable(true);
            spinner.setVisible(true);
            runInBackground(
                    () -> ItemActionDispatcher.performPrimaryAction(context.actionExecutor(), item),
                    result -> {
                        spinner.setVisible(false);
                        primaryButton.setDisable(false);
                        statusMessage.getStyleClass().removeAll("text-danger", "text-success");
                        statusMessage.getStyleClass().add(result.success() ? "text-success" : "text-danger");
                        statusMessage.setText(result.message());
                        if (result.success()) {
                            primaryButton.setDisable(true);
                            if (onActionPerformed != null) {
                                onActionPerformed.run();
                            }
                        }
                    });
        });

        lockButton.setOnAction(e -> {
            lockButton.setDisable(true);
            spinner.setVisible(true);
            runInBackground(
                    () -> {
                        LockManager.LockResult result = locked[0]
                                ? context.lockManager().unlockItem(item.name(), item.type())
                                : context.lockManager().lockItem(item.name(), item.type(), "Bloqueado pelo usuario via interface grafica");
                        return new ActionExecutor.ActionResult(result.success(), result.message(), null);
                    },
                    result -> {
                        spinner.setVisible(false);
                        lockButton.setDisable(false);
                        statusMessage.getStyleClass().removeAll("text-danger", "text-success");
                        statusMessage.getStyleClass().add(result.success() ? "text-success" : "text-danger");
                        statusMessage.setText(result.message());
                        if (result.success()) {
                            locked[0] = !locked[0];
                            lockButton.setText(locked[0] ? "Desbloquear" : "Bloquear");
                            if (onActionPerformed != null) {
                                onActionPerformed.run();
                            }
                        }
                    });
        });

        HBox buttonRow = new HBox(10, primaryButton, lockButton, tutorialButton, spinner);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, nameLabel, headerRow, descriptionTitle, descriptionLabel,
                disableTitle, disableLabel, keepTitle, keepLabel, buttonRow, statusMessage);
        root.getStyleClass().add("detail-root");
        root.setPadding(new Insets(20));
        VBox.setVgrow(descriptionLabel, Priority.NEVER);

        // ScrollPane em vez de VBox direto na Scene: garante que o modal continue
        // usavel (sem cortar texto) em resolucoes/escalas de tela menores ou quando
        // a descricao de um item for mais longa que o esperado - a janela deixa de
        // ter um tamanho fixo rigido e passa a ter apenas um tamanho inicial + minimo.
        ScrollPane scrollPane = new ScrollPane(root);
        scrollPane.setFitToWidth(true);

        Scene scene = new Scene(scrollPane, 480, 460);
        scene.getStylesheets().add(ItemDetailView.class.getResource(Theme.STYLESHEET).toExternalForm());
        stage.setScene(scene);
        stage.setResizable(true);
        stage.setMinWidth(420);
        stage.setMinHeight(360);
        stage.showAndWait();
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subtitle-hud");
        return label;
    }

    private static Label bodyText(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("text-body");
        label.setWrapText(true);
        return label;
    }

    private static void runInBackground(java.util.function.Supplier<ActionExecutor.ActionResult> action,
                                         java.util.function.Consumer<ActionExecutor.ActionResult> onDone) {
        Thread thread = new Thread(() -> {
            ActionExecutor.ActionResult result;
            try {
                result = action.get();
            } catch (Exception e) {
                result = new ActionExecutor.ActionResult(false, "Erro inesperado: " + e.getMessage(), null);
            }
            ActionExecutor.ActionResult finalResult = result;
            Platform.runLater(() -> onDone.accept(finalResult));
        }, "nitroboost-detail-action");
        thread.setDaemon(true);
        thread.start();
    }
}
