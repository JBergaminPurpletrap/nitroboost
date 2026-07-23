package com.nitroboost.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Estrutura da tela de tutoriais internos. O CONTEUDO real (passo a passo de
 * XMP/BIOS por fabricante, deteccao automatica, etc.) e escopo da Fase 5
 * ("Tutoriais e Educacao") - aqui so criamos a estrutura da view funcional
 * com um exemplo de placeholder, conforme pedido explicitamente para esta
 * fase (nao antecipar conteudo elaborado demais).
 */
public class TutorialView extends BorderPane {

    public TutorialView() {
        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));

        Label title = new Label("TUTORIAIS");
        title.getStyleClass().add("title-hud");

        Label subtitle = new Label("Guias internos para ajustes que exigem acao manual (ex: BIOS)");
        subtitle.getStyleClass().add("text-secondary");

        ListView<String> tutorialList = new ListView<>(FXCollections.observableArrayList(
                "Como habilitar XMP na BIOS (conteudo completo chega na Fase 5)"
        ));
        tutorialList.setPrefHeight(120);

        Label placeholderTitle = new Label("EM BREVE");
        placeholderTitle.getStyleClass().add("subtitle-hud");

        Label placeholderBody = new Label(
                "Esta tela ja esta conectada a navegacao principal do NITRO BOOST. O conteudo detalhado dos "
                        + "tutoriais - como detectar XMP desativado comparando a velocidade da RAM, o passo a passo "
                        + "generico de como habilitar XMP na BIOS, e os links por fabricante (ASUS, Gigabyte, MSI, "
                        + "ASRock) - sera implementado na Fase 5 do projeto, conforme o checklist da documentacao.");
        placeholderBody.getStyleClass().add("text-body");
        placeholderBody.setWrapText(true);
        placeholderBody.setMaxWidth(560);

        VBox card = new VBox(10, placeholderTitle, placeholderBody);
        card.getStyleClass().add("card");
        card.setMaxWidth(600);

        VBox root = new VBox(16, title, subtitle, tutorialList, card);
        VBox.setVgrow(card, Priority.NEVER);
        setCenter(root);
    }
}
