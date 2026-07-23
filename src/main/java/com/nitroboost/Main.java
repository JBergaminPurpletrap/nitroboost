package com.nitroboost;

import com.nitroboost.core.HardwareInfoPrinter;
import com.nitroboost.db.DatabaseManager;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Ponto de entrada da aplicacao NITRO BOOST.
 *
 * Fase 0: apenas abre uma janela JavaFX vazia com o titulo "NITRO BOOST",
 * imprime as especificacoes de hardware (CPU/RAM) no console via OSHI e
 * garante que o banco de dados SQLite local (com o schema inicial) exista.
 */
public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        // Garante que o banco SQLite local existe e possui o schema inicial
        // antes de qualquer outra coisa. Erros aqui nao devem impedir a UI
        // de abrir - sao apenas logados no console (regra de ouro: sempre
        // tratar erros de interacao com o sistema/arquivos).
        try {
            DatabaseManager databaseManager = new DatabaseManager();
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar o banco de dados: " + e.getMessage());
        }

        // Testa a leitura de hardware via OSHI e imprime no console.
        try {
            HardwareInfoPrinter.printCpuAndRamUsage();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler informacoes de hardware via OSHI: " + e.getMessage());
        }

        StackPane root = new StackPane(new Label("NITRO BOOST"));
        Scene scene = new Scene(root, 800, 600);

        primaryStage.setTitle("NITRO BOOST");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
