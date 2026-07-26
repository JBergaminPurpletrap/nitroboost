package com.nitroboost;

import com.nitroboost.ui.AppContext;
import com.nitroboost.ui.AuditView;
import com.nitroboost.ui.DashboardView;
import com.nitroboost.ui.HardwareUpdateView;
import com.nitroboost.ui.HistoryView;
import com.nitroboost.ui.ScanResultsView;
import com.nitroboost.ui.Theme;
import com.nitroboost.ui.TutorialView;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ponto de entrada da aplicacao NITRO BOOST (Fase 4).
 *
 * Estrutura de navegacao: {@code BorderPane} principal com um menu lateral
 * (sidebar) trocando o conteudo central entre as 4 telas - Dashboard,
 * Resultados do Scan, Historico e Tutoriais - a opcao mais simples e direta
 * de implementar corretamente, conforme pedido na Fase 4. Todas as views sao
 * criadas uma unica vez (mantem estado - ex: itens ja escaneados) e apenas
 * trocadas de lugar no centro do layout.
 */
public class Main extends Application {

    private DashboardView dashboardView;

    @Override
    public void start(Stage primaryStage) {
        // Garante que o banco SQLite local existe e possui o schema inicial antes de
        // qualquer outra coisa. Erro aqui nunca deve impedir a UI de abrir - regra de
        // ouro do projeto (toda interacao com o sistema/arquivos precisa de try/catch).
        AppContext context;
        try {
            context = AppContext.create();
            context.databaseManager().initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar o banco de dados: " + e.getMessage());
            context = AppContext.create();
        }

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");

        VBox header = buildHeader();
        root.setTop(header);

        Map<String, Node> views = new LinkedHashMap<>();
        HistoryView historyView = new HistoryView(context);
        TutorialView tutorialView = new TutorialView();

        Runnable[] navigateToTutorial = new Runnable[1];
        ScanResultsView scanResultsView = new ScanResultsView(context, () -> navigateToTutorial[0].run());
        AuditView auditView = new AuditView(context);
        HardwareUpdateView hardwareUpdateView = new HardwareUpdateView();
        dashboardView = new DashboardView(context, () -> {
            switchTo(root, views, "Resultados do Scan");
            scanResultsView.startScan();
        });

        views.put("Dashboard", dashboardView);
        views.put("Resultados do Scan", scanResultsView);
        views.put("Diagnostico", auditView);
        views.put("BIOS / Drivers", hardwareUpdateView);
        views.put("Historico", historyView);
        views.put("Tutoriais", tutorialView);
        navigateToTutorial[0] = () -> switchTo(root, views, "Tutoriais");

        VBox sidebar = buildSidebar(root, views, historyView, auditView);
        root.setLeft(sidebar);
        switchTo(root, views, "Dashboard");

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(getClass().getResource(Theme.STYLESHEET).toExternalForm());

        primaryStage.setTitle("NITRO BOOST");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1024);
        primaryStage.setMinHeight(700);
        // Para a leitura periodica de hardware do dashboard ao fechar a janela -
        // sem isso, a thread de fundo do ScheduledExecutorService (mesmo daemon)
        // ficaria rodando desnecessariamente enquanto o processo nao encerra.
        primaryStage.setOnCloseRequest(e -> dashboardView.shutdown());
        primaryStage.show();
    }

    private VBox buildHeader() {
        Label title = new Label("🚀 NITRO BOOST");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("SEU PC NO MODO TURBO — CONTROLE TOTAL, ZERO MISTERIO");
        subtitle.getStyleClass().add("app-subtitle");
        VBox header = new VBox(2, title, subtitle);
        header.getStyleClass().addAll("app-header", "carbon-bg-strong");
        return header;
    }

    private VBox buildSidebar(BorderPane root, Map<String, Node> views, HistoryView historyView, AuditView auditView) {
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");

        String[] labels = {"Dashboard", "Resultados do Scan", "Diagnostico", "BIOS / Drivers", "Historico", "Tutoriais"};
        String[] icons = {"🏠", "📋", "🩺", "🔧", "🕒", "📖"};
        for (int i = 0; i < labels.length; i++) {
            String viewName = labels[i];
            Button navButton = new Button(icons[i] + "  " + viewName.toUpperCase());
            navButton.getStyleClass().add("nav-button");
            navButton.setMaxWidth(Double.MAX_VALUE);
            navButton.setOnAction(e -> {
                if ("Historico".equals(viewName)) {
                    historyView.refresh();
                } else if ("Diagnostico".equals(viewName)) {
                    auditView.runAudit();
                }
                switchTo(root, views, viewName);
                highlightActive(sidebar, navButton);
            });
            if (i == 0) {
                navButton.getStyleClass().add("nav-button-active");
            }
            sidebar.getChildren().add(navButton);
        }

        Region grower = new Region();
        VBox.setVgrow(grower, Priority.ALWAYS);
        sidebar.getChildren().add(grower);
        return sidebar;
    }

    private void highlightActive(VBox sidebar, Button active) {
        for (Node node : sidebar.getChildren()) {
            if (node instanceof Button b) {
                b.getStyleClass().remove("nav-button-active");
            }
        }
        active.getStyleClass().add("nav-button-active");
    }

    private void switchTo(BorderPane root, Map<String, Node> views, String name) {
        Node view = views.get(name);
        if (view != null) {
            root.setCenter(view);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
