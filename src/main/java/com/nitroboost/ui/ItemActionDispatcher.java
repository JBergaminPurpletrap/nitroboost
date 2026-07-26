package com.nitroboost.ui;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.BloatwareScanner;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.GamingScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.core.PowerPlanScanner;
import com.nitroboost.core.ProcessScanner;
import com.nitroboost.core.StartupScanner;
import com.nitroboost.core.TaskSchedulerScanner;
import com.nitroboost.core.TelemetryScanner;

/**
 * Traduz um {@link ScannedItem} generico (categoria unificada usada pela UI)
 * para a chamada especifica e correta do {@link ActionExecutor}, conforme o
 * tipo real do item por baixo. Usado tanto por {@link ScanResultsView}
 * (botao de acao rapida na tabela) quanto por {@link ItemDetailView} (botao
 * "Desativar" do modal) para nao duplicar essa logica de despacho em dois
 * lugares - a UI nunca reimplementa a regra de negocio, apenas chama o
 * metodo certo do backend ja validado nas Fases 1 a 3.
 */
public final class ItemActionDispatcher {

    private ItemActionDispatcher() {
        // Classe utilitaria, nao deve ser instanciada.
    }

    /** Rotulo do botao de acao principal, adaptado ao tipo do item. */
    public static String primaryActionLabel(String type) {
        return switch (type) {
            case "process" -> "Finalizar";
            case "powerplan" -> "Ativar";
            case "bloatware" -> "Desinstalar";
            default -> "Desativar";
        };
    }

    /**
     * Executa a acao principal do item chamando o metodo correto do {@link
     * ActionExecutor} (com backup + historico + checagem de lock, tudo ja
     * garantido pelo backend). Nunca deixa uma excecao escapar para a UI -
     * qualquer falha inesperada (ex: cast incorreto) vira um {@code
     * ActionResult} de falha com mensagem clara, como qualquer outra falha
     * de negocio.
     */
    public static ActionExecutor.ActionResult performPrimaryAction(ActionExecutor executor, ScannedItem item) {
        try {
            return switch (item.type()) {
                case "process" -> executor.killProcess(((ProcessScanner.ProcessInfo) item.source()).pid());
                case "service" -> executor.disableService(item.name());
                case "startup" -> executor.disableStartupItem((StartupScanner.StartupItemInfo) item.source());
                case "task" -> executor.disableScheduledTask((TaskSchedulerScanner.TaskInfo) item.source());
                case "powerplan" -> {
                    var plan = (PowerPlanScanner.PowerPlanInfo) item.source();
                    yield executor.switchPowerPlan(plan.guid(), plan.name());
                }
                case "telemetry" ->
                        executor.setTelemetryValue((TelemetryScanner.TelemetryKeyDefinition) item.source(), "0");
                // allUsers=false, whatIf=false: acao real de desinstalacao restrita ao usuario atual
                // (nao exige elevacao extra) - decisao pragmatica para a Fase 4, documentada em PROGRESS.md.
                case "bloatware" ->
                        executor.uninstallBloatwareApp((BloatwareScanner.AppxInfo) item.source(), false, false);
                case "performance" -> {
                    var definition = (PerformanceScanner.PerformanceKeyDefinition) item.source();
                    yield executor.setPerformanceValue(definition, definition.recommendedValue());
                }
                case "gaming" -> {
                    var definition = (GamingScanner.GamingKeyDefinition) item.source();
                    yield executor.setGamingValue(definition, definition.recommendedValue());
                }
                // O arquivo de hibernacao so tem sentido como um "toggle": a acao principal desativa
                // (caso comum de liberar espaco em disco) - se ja estiver desativado, e um no-op seguro.
                case "hibernation" -> executor.setHibernationEnabled(false);
                // Mesmo raciocinio do item de hibernacao acima: a acao principal desativa o
                // Armazenamento Reservado (caso de uso do documento - liberar espaco em disco).
                case "reservedstorage" -> executor.setReservedStorageEnabled(false);
                case "ai" -> {
                    var definition = (AiFeatureScanner.AiFeatureKeyDefinition) item.source();
                    yield executor.setAiFeatureValue(definition, definition.recommendedValue());
                }
                case "consumer" -> {
                    var definition = (ConsumerFeatureScanner.ConsumerFeatureKeyDefinition) item.source();
                    yield executor.setConsumerFeatureValue(definition, definition.recommendedValue());
                }
                default -> new ActionExecutor.ActionResult(false, "Tipo de item desconhecido: " + item.type(), null);
            };
        } catch (Exception e) {
            return new ActionExecutor.ActionResult(false, "Erro inesperado ao executar a acao: " + e.getMessage(), null);
        }
    }
}
