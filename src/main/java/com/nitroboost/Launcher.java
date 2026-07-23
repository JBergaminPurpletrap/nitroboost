package com.nitroboost;

/**
 * Ponto de entrada real do jar/exe empacotado (jpackage / {@code java -jar}).
 *
 * Motivo de existir: quando a classe indicada como "Main-Class" no manifesto
 * do jar (ou como --main-class no jpackage) estende diretamente {@code
 * javafx.application.Application}, o launcher padrao do Java (desde o JDK 11)
 * faz uma checagem especial e recusa iniciar com o erro "os componentes de
 * runtime do JavaFX nao foram encontrados" - mesmo com os jars do JavaFX
 * presentes no classpath - porque essa checagem so reconhece o modulo
 * {@code javafx.graphics} quando ele esta no MODULE PATH, nao no classpath
 * comum (que e como o jpackage empacota esta aplicacao, ja que o projeto nao
 * usa {@code module-info.java}). Isso so afeta a execucao via {@code java -jar}
 * / jpackage; {@code mvnw javafx:run} continua funcionando sem essa classe,
 * pois o plugin do JavaFX monta o module-path corretamente sozinho.
 *
 * Solucao padrao (documentada pela propria comunidade OpenJFX): usar uma
 * classe separada, que NAO estende {@code Application}, como ponto de
 * entrada do jar - ela apenas repassa para {@link Main#main(String[])}. Como
 * essa classe (esta aqui) nao estende {@code Application}, a checagem especial
 * do launcher nunca e acionada, e o JavaFX inicializa normalmente a partir do
 * classpath.
 */
public final class Launcher {

    private Launcher() {
        // Classe utilitaria, apenas ponto de entrada - nao deve ser instanciada.
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
