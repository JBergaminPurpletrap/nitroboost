package com.nitroboost.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Barra de progresso padronizada do NITRO BOOST (Fase 12 Parte C): encapsula um {@link
 * ProgressBar} comum do JavaFX (reaproveitando o estilo {@code .progress-rpm} ja definido em
 * {@code nitroboost-carbon.css} desde a Fase 4 - trilho carbono escuro, gradiente verde tecnico
 * -> verde neon com glow, e a variante vermelha {@code .progress-critical}) mais um {@link Label}
 * de mensagem e o percentual numerico, para nao duplicar esse layout em cada tela que precisa de
 * barra de progresso.
 *
 * Fica invisivel/sem ocupar espaco por padrao ({@link #hide()}) ate a primeira chamada de {@link
 * #show()} - assim as telas que a usam nao precisam de um contêiner condicional a parte.
 */
public class NitroProgressBar extends VBox {

    private final ProgressBar bar = new ProgressBar(0);
    private final Label percentLabel = new Label("0%");
    private final Label messageLabel = new Label("");

    public NitroProgressBar() {
        bar.getStyleClass().add("progress-rpm");
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);

        percentLabel.getStyleClass().add("subtitle-hud");
        messageLabel.getStyleClass().add("text-secondary");
        messageLabel.setWrapText(true);

        HBox barRow = new HBox(10, bar, percentLabel);
        barRow.setAlignment(Pos.CENTER_LEFT);

        setSpacing(4);
        getChildren().addAll(barRow, messageLabel);
        hide();
    }

    /**
     * @param progress 0.0 a 1.0 para progresso proporcional, ou {@link
     *                 ProgressBar#INDETERMINATE_PROGRESS} para o modo indeterminado (girando, sem
     *                 percentual - usado quando nao ha "quantidade" para medir, ex: 1 requisicao HTTP).
     */
    public void setProgress(double progress) {
        bar.setProgress(progress);
        percentLabel.setText(progress < 0 ? "" : String.format("%.0f%%", progress * 100));
    }

    public void setMessage(String message) {
        messageLabel.setText(message == null ? "" : message);
    }

    /** Mostra a barra e passa a ocupar espaco no layout. */
    public void show() {
        setVisible(true);
        setManaged(true);
    }

    /** Esconde a barra e libera o espaco que ela ocupava no layout. */
    public void hide() {
        setVisible(false);
        setManaged(false);
    }
}
