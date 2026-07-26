package com.nitroboost.ui;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.audit.AuditFinding;
import com.nitroboost.audit.AuditReport;
import com.nitroboost.audit.SystemAuditEngine;
import com.nitroboost.core.ScanProgressListener;
import com.nitroboost.ui.components.NitroProgressBar;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tela de Diagnostico do Sistema (Fase 9 Parte 2): roda {@link SystemAuditEngine}, mostra o
 * placar geral e a lista agrupada por categoria (Telemetria/Performance/Jogos/IA/Consumidor), com
 * acao individual por item e acao em lote por categoria - reaproveitando 100% o mesmo backend
 * (ActionExecutor via {@link ItemActionDispatcher}) e o mesmo padrao de confirmacao em massa ja
 * validado em {@link ScanResultsView#closeAllGreen()}, so que aqui o modal LISTA CADA ITEM
 * (nome + valor atual -> recomendado), nao so a contagem - regra de seguranca explicita da secao
 * 3.3 do documento da Fase 9.
 */
public class AuditView extends BorderPane {

    private final AppContext context;
    private final SystemAuditEngine engine;

    private final Label scoreLabel = new Label("Nenhum diagnostico executado ainda.");
    private final Label statusLabel = new Label();
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final NitroProgressBar auditProgressBar = new NitroProgressBar();
    private final VBox resultsBox = new VBox(18);

    public AuditView(AppContext context) {
        this.context = context;
        this.engine = new SystemAuditEngine(context.knowledgeBase());

        getStyleClass().add("carbon-bg-subtle");
        setPadding(new Insets(24));

        Label title = new Label("DIAGNOSTICO DO SISTEMA");
        title.getStyleClass().add("title-hud");

        progressIndicator.setPrefSize(20, 20);
        progressIndicator.setVisible(false);

        Button runButton = new Button("🩺 RODAR DIAGNOSTICO");
        runButton.getStyleClass().add("btn-turbo");
        runButton.setOnAction(e -> runAudit());

        HBox toolbar = new HBox(14, runButton, progressIndicator);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        scoreLabel.getStyleClass().add("subtitle-hud");
        statusLabel.getStyleClass().add("text-secondary");

        resultsBox.setPadding(new Insets(12, 0, 0, 0));
        ScrollPane scrollPane = new ScrollPane(resultsBox);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("scroll-pane");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox root = new VBox(14, title, toolbar, auditProgressBar, scoreLabel, scrollPane, statusLabel);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        setCenter(root);
    }

    /**
     * Dispara o diagnostico completo em background (le chaves de registro reais) e renderiza ao
     * concluir. A barra de progresso (Fase 12 Parte C) reporta 1 das 5 categorias auditadas por vez
     * (Telemetria/Performance/Jogos/IA/Consumidor) via {@link SystemAuditEngine#run(ScanProgressListener)}
     * - como essa varredura roda numa {@link Thread} simples (nao um {@code javafx.concurrent.Task}),
     * cada callback do listener e repassado para a UI via {@link Platform#runLater}.
     */
    public void runAudit() {
        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
        statusLabel.setText("Executando diagnostico...");
        progressIndicator.setVisible(true);
        auditProgressBar.show();
        auditProgressBar.setProgress(0);
        auditProgressBar.setMessage("Iniciando diagnostico...");

        Thread thread = new Thread(() -> {
            try {
                ScanProgressListener listener = (category, current, total, message) -> Platform.runLater(() -> {
                    double fraction = total > 0 ? (double) current / total : 0.0;
                    auditProgressBar.setProgress(fraction);
                    auditProgressBar.setMessage(message);
                });
                AuditReport report = engine.run(listener);
                Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    auditProgressBar.hide();
                    statusLabel.setText("Diagnostico concluido.");
                    render(report);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    auditProgressBar.hide();
                    statusLabel.getStyleClass().add("text-danger");
                    statusLabel.setText("Falha ao executar diagnostico: " + e.getMessage());
                });
            }
        }, "nitroboost-audit-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void render(AuditReport report) {
        scoreLabel.setText(report.totalOptimized() + " de " + report.totalApplicable() + " itens ja otimizados");
        resultsBox.getChildren().clear();
        for (Map.Entry<String, List<AuditFinding>> entry : report.findingsByCategory().entrySet()) {
            resultsBox.getChildren().add(buildCategorySection(entry.getKey(), entry.getValue()));
        }
    }

    private VBox buildCategorySection(String category, List<AuditFinding> findings) {
        long optimizedCount = findings.stream().filter(f -> f.status() == AuditFinding.Status.JA_OTIMIZADO).count();
        List<AuditFinding> suggestions = findings.stream()
                .filter(f -> f.status() == AuditFinding.Status.SUGESTAO)
                .toList();

        Label header = new Label(category + " — " + optimizedCount + "/" + findings.size() + " otimizados");
        header.getStyleClass().add("title-hud");
        HBox.setHgrow(header, Priority.ALWAYS);

        Button applyAllButton = new Button("Aplicar todas as sugestoes seguras (" + suggestions.size() + ")");
        applyAllButton.getStyleClass().add("btn-secondary");
        applyAllButton.setDisable(suggestions.isEmpty());
        applyAllButton.setOnAction(e -> confirmAndApplyAll(category, suggestions, applyAllButton));

        HBox headerRow = new HBox(14, header, applyAllButton);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        VBox itemsBox = new VBox(4);
        for (AuditFinding finding : findings) {
            itemsBox.getChildren().add(buildFindingRow(finding));
        }

        VBox section = new VBox(10, headerRow, itemsBox);
        section.getStyleClass().add("card");
        return section;
    }

    private HBox buildFindingRow(AuditFinding finding) {
        String icon = switch (finding.status()) {
            case JA_OTIMIZADO -> "✅";
            case SUGESTAO -> "🟡";
            case NAO_APLICAVEL -> "⚪";
        };
        Label label = new Label(icon + "  " + finding.itemName() + "   (atual: " + displayValue(finding.currentValue())
                + "  →  recomendado: " + displayValue(finding.recommendedValue()) + ")");
        label.getStyleClass().add(finding.status() == AuditFinding.Status.JA_OTIMIZADO ? "text-success" : "text-body");
        HBox.setHgrow(label, Priority.ALWAYS);

        HBox row = new HBox(10, label);
        row.setAlignment(Pos.CENTER_LEFT);

        if (finding.status() == AuditFinding.Status.SUGESTAO) {
            // Fase 14 Parte 2: o item de taxa de atualizacao da tela nao "aplica" nada sozinho - so
            // abre a tela nativa de Configuracoes do Windows (Nivel 1) - o texto generico "Aplicar"
            // ficaria enganoso aqui. Demais itens continuam com o texto generico de sempre.
            String buttonLabel = "display".equals(finding.itemType()) ? "Abrir Configuracoes de Tela" : "Aplicar";
            Button applyButton = new Button(buttonLabel);
            applyButton.getStyleClass().add("btn-secondary");
            applyButton.setOnAction(e -> {
                applyButton.setDisable(true);
                applySingle(finding, applyButton);
            });
            row.getChildren().add(applyButton);
        }
        return row;
    }

    private String displayValue(String rawValue) {
        return rawValue == null ? "nao definido" : rawValue;
    }

    /** Reconstroi um {@link ScannedItem} a partir do achado do diagnostico, para reusar o mesmo despacho de acao das outras telas. */
    private ScannedItem toScannedItem(AuditFinding finding) {
        String state = "Valor atual: " + displayValue(finding.currentValue());
        return SystemScanTask.buildItem(context.knowledgeBase(), finding.category(), finding.itemType(),
                finding.itemName(), state, finding.source());
    }

    private void applySingle(AuditFinding finding, Button button) {
        Thread thread = new Thread(() -> {
            ActionExecutor.ActionResult result;
            try {
                result = ItemActionDispatcher.performPrimaryAction(context.actionExecutor(), toScannedItem(finding));
            } catch (Exception e) {
                result = new ActionExecutor.ActionResult(false, "Erro inesperado: " + e.getMessage(), null);
            }
            ActionExecutor.ActionResult finalResult = result;
            Platform.runLater(() -> {
                showResultStatus(finalResult);
                button.setDisable(false);
                if (finalResult.success()) {
                    runAudit();
                }
            });
        }, "nitroboost-audit-action");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Modal de confirmacao de aplicacao em lote: LISTA CADA ITEM (nome + valor atual -> recomendado)
     * antes de aplicar, nunca so a contagem - regra de seguranca explicita da secao 3.3 do
     * documento da Fase 9. Cada item, um por vez, ainda passa pelo fluxo normal do ActionExecutor
     * (lock -> backup individual -> acao -> historico), o mesmo padrao ja validado em
     * {@link ScanResultsView#closeAllGreen()} - a acao em lote so dispara varias sequenciais, nunca
     * pula nenhuma dessas garantias nem cria um "backup unico para o lote inteiro".
     */
    private void confirmAndApplyAll(String category, List<AuditFinding> suggestions, Button triggerButton) {
        if (suggestions.isEmpty()) {
            return;
        }

        StringBuilder itemList = new StringBuilder();
        for (AuditFinding f : suggestions) {
            itemList.append("- ").append(f.itemName()).append(":  ").append(displayValue(f.currentValue()))
                    .append("  →  ").append(displayValue(f.recommendedValue())).append('\n');
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirmar aplicacao em lote");
        confirm.setHeaderText("Aplicar " + suggestions.size() + " sugestao(oes) de '" + category + "'?");
        TextArea detail = new TextArea(itemList.toString());
        detail.setEditable(false);
        detail.setWrapText(false);
        detail.setPrefRowCount(Math.min(12, suggestions.size()));
        VBox content = new VBox(8,
                new Label("Cada item abaixo tera sua acao de melhoria aplicada individualmente, com backup quando "
                        + "aplicavel (itens bloqueados sao recusados automaticamente, sem interromper os demais):"),
                detail);
        confirm.getDialogPane().setContent(content);
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return;
        }

        triggerButton.setDisable(true);
        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
        statusLabel.setText("Aplicando " + suggestions.size() + " sugestao(oes) de '" + category + "'...");

        int total = suggestions.size();
        auditProgressBar.show();
        auditProgressBar.setProgress(0);
        auditProgressBar.setMessage("0 de " + total + " item(ns) aplicado(s)...");

        Thread thread = new Thread(() -> {
            List<AuditFinding> succeeded = new ArrayList<>();
            int failed = 0;
            int done = 0;
            for (AuditFinding f : suggestions) {
                try {
                    ActionExecutor.ActionResult result = ItemActionDispatcher.performPrimaryAction(context.actionExecutor(), toScannedItem(f));
                    if (result.success()) {
                        succeeded.add(f);
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                }
                done++;
                int finalDone = done;
                Platform.runLater(() -> {
                    auditProgressBar.setProgress((double) finalDone / total);
                    auditProgressBar.setMessage(finalDone + " de " + total + " item(ns) aplicado(s)...");
                });
            }
            int finalFailed = failed;
            int finalSucceeded = succeeded.size();
            Platform.runLater(() -> {
                auditProgressBar.hide();
                statusLabel.getStyleClass().removeAll("text-danger", "text-success");
                statusLabel.getStyleClass().add(finalFailed == 0 ? "text-success" : "text-danger");
                statusLabel.setText(finalSucceeded + " item(ns) aplicado(s) com sucesso"
                        + (finalFailed > 0 ? ", " + finalFailed + " falharam (ver Historico para detalhes)." : "."));
                runAudit();
            });
        }, "nitroboost-audit-bulk");
        thread.setDaemon(true);
        thread.start();
    }

    private void showResultStatus(ActionExecutor.ActionResult result) {
        statusLabel.getStyleClass().removeAll("text-danger", "text-success");
        statusLabel.getStyleClass().add(result.success() ? "text-success" : "text-danger");
        statusLabel.setText(result.message());
    }
}
