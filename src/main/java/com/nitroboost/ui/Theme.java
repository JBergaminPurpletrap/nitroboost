package com.nitroboost.ui;

/**
 * Constantes do tema visual "Carbono & Verde Turbo" compartilhadas entre as
 * views. Mantido em um unico lugar para que toda janela/modal da aplicacao
 * (inclusive as que abrem em um {@code Stage} separado, como {@link ItemDetailView})
 * aplique exatamente a mesma folha de estilos.
 */
public final class Theme {

    public static final String STYLESHEET = "/theme/nitroboost-carbon.css";

    /** Cores da paleta oficial do projeto (ver secao 4.1 da documentacao). */
    public static final String COLOR_BACKGROUND = "#0A0A0A";
    public static final String COLOR_PANEL = "#161616";
    public static final String COLOR_NEON_GREEN = "#39FF14";
    public static final String COLOR_TECH_GREEN = "#00C853";
    public static final String COLOR_RED = "#FF3B30";
    public static final String COLOR_AMBER = "#FFB300";
    public static final String COLOR_TEXT = "#E8E8E8";
    public static final String COLOR_TEXT_SECONDARY = "#7A7A7A";
    public static final String COLOR_BORDER = "#2A2A2A";

    private Theme() {
        // Classe utilitaria, nao deve ser instanciada.
    }
}
