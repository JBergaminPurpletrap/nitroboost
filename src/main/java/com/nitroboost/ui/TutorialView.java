package com.nitroboost.ui;

import com.nitroboost.knowledge.TutorialProvider;
import com.nitroboost.knowledge.TutorialProvider.Tutorial;
import com.nitroboost.knowledge.XmpAdvisor;
import com.nitroboost.knowledge.XmpAdvisor.XmpEvaluation;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Tela de tutoriais internos: mostra o alerta de possivel XMP/EXPO
 * desativado (calculado via {@link XmpAdvisor}) e o conteudo dos tutoriais
 * carregados por {@link TutorialProvider} (Markdown simples, renderizado de
 * forma leve - sem depender de nenhuma biblioteca externa de Markdown, o que
 * seria excesso para o volume de conteudo atual do projeto).
 */
public class TutorialView extends VBox {

    private final TutorialProvider tutorialProvider = new TutorialProvider();
    private final ListView<Tutorial> tutorialList = new ListView<>();
    private final VBox contentBox = new VBox(6);
    private final VBox alertBox = new VBox();

    public TutorialView() {
        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));
        setSpacing(16);

        Label title = new Label("TUTORIAIS");
        title.getStyleClass().add("title-hud");

        Label subtitle = new Label("Guias internos para ajustes que exigem acao manual (ex: BIOS)");
        subtitle.getStyleClass().add("text-secondary");

        alertBox.setVisible(false);
        alertBox.setManaged(false);

        tutorialList.setItems(FXCollections.observableArrayList(tutorialProvider.all()));
        tutorialList.setPrefWidth(240);
        tutorialList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Tutorial item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
            }
        });
        tutorialList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> showTutorial(newVal));

        ScrollPane contentScroll = new ScrollPane(contentBox);
        contentScroll.setFitToWidth(true);

        HBox splitRow = new HBox(16, tutorialList, contentScroll);
        HBox.setHgrow(contentScroll, Priority.ALWAYS);
        VBox.setVgrow(splitRow, Priority.ALWAYS);

        getChildren().addAll(title, subtitle, alertBox, splitRow);

        List<Tutorial> tutorials = tutorialProvider.all();
        if (!tutorials.isEmpty()) {
            tutorialList.getSelectionModel().selectFirst();
        } else {
            showEmptyState();
        }

        checkXmpInBackground();
    }

    /** Roda a leitura de velocidade da RAM (comando externo, pode levar alguns segundos) fora da thread da UI. */
    private void checkXmpInBackground() {
        Thread thread = new Thread(() -> {
            XmpEvaluation evaluation;
            try {
                evaluation = new XmpAdvisor().evaluate();
            } catch (Exception e) {
                evaluation = null;
            }
            XmpEvaluation finalEvaluation = evaluation;
            Platform.runLater(() -> {
                if (finalEvaluation != null && finalEvaluation.dataAvailable() && finalEvaluation.likelyXmpDisabled()) {
                    showXmpAlert(finalEvaluation);
                }
            });
        }, "nitroboost-xmp-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void showXmpAlert(XmpEvaluation evaluation) {
        alertBox.getChildren().clear();

        Label alertTitle = new Label("⚠ POSSIVEL XMP/EXPO DESATIVADO");
        alertTitle.getStyleClass().add("text-warning");
        alertTitle.setStyle("-fx-font-weight: bold; -fx-font-family: 'Consolas', monospace;");

        Label alertBody = new Label(evaluation.message());
        alertBody.getStyleClass().add("text-body");
        alertBody.setWrapText(true);

        Button openTutorialButton = new Button("Ver Tutorial de XMP");
        openTutorialButton.getStyleClass().add("btn-secondary");
        openTutorialButton.setOnAction(e -> tutorialProvider.find("xmp-bios")
                .ifPresent(t -> tutorialList.getSelectionModel().select(t)));

        VBox card = new VBox(8, alertTitle, alertBody, openTutorialButton);
        card.getStyleClass().add("alert-card");
        card.setMaxWidth(720);

        alertBox.getChildren().add(card);
        alertBox.setVisible(true);
        alertBox.setManaged(true);
    }

    private void showTutorial(Tutorial tutorial) {
        contentBox.getChildren().clear();
        if (tutorial == null) {
            showEmptyState();
            return;
        }
        for (String line : tutorial.content().split("\\R")) {
            contentBox.getChildren().add(renderLine(line));
        }
    }

    private void showEmptyState() {
        contentBox.getChildren().clear();
        Label empty = new Label("Nenhum tutorial disponivel no momento.");
        empty.getStyleClass().add("text-secondary");
        contentBox.getChildren().add(empty);
    }

    /**
     * Renderizacao leve de uma linha de Markdown - sem biblioteca externa,
     * ja que o unico uso e um punhado de tutoriais internos com formatacao
     * simples (titulos, subtitulos, listas e texto corrido).
     */
    private Region renderLine(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty()) {
            Region spacer = new Region();
            spacer.setPrefHeight(8);
            return spacer;
        }
        if (line.startsWith("# ")) {
            Label label = new Label(line.substring(2).trim());
            label.getStyleClass().add("title-hud");
            label.setWrapText(true);
            return label;
        }
        if (line.startsWith("## ")) {
            Label label = new Label(line.substring(3).trim());
            label.getStyleClass().add("subtitle-hud");
            label.setWrapText(true);
            return label;
        }
        if (line.startsWith("- ") || line.startsWith("* ")) {
            Label label = new Label("•  " + stripMarkdownEmphasis(line.substring(2).trim()));
            label.getStyleClass().add("text-body");
            label.setWrapText(true);
            HBox row = new HBox(label);
            HBox.setMargin(label, new Insets(0, 0, 0, 12));
            return row;
        }
        Label label = new Label(stripMarkdownEmphasis(line));
        label.getStyleClass().add("text-body");
        label.setWrapText(true);
        label.setMaxWidth(760);
        return label;
    }

    /** Remove marcacao de negrito (**texto**) para leitura limpa, sem precisar de um parser de Markdown de verdade. */
    private String stripMarkdownEmphasis(String text) {
        return text.replace("**", "");
    }
}
