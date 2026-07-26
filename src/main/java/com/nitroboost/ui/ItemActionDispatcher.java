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
            case "bloatware", "onedrive_uninstall" -> "Desinstalar";
            default -> "Desativar";
        };
    }

    /**
     * {@code true} se a acao principal deste item exigir uma confirmacao EXTRA, mais explicita que
     * o dialogo de confirmacao padrao ja usado em acoes em lote (Fase 12 Parte A) - hoje, apenas a
     * desinstalacao completa do OneDrive (secao A.4), por ser a acao mais drastica/irreversivel do
     * projeto ate aqui. Usado tanto por {@link com.nitroboost.ui.ScanResultsView} (botao de acao
     * rapida na tabela) quanto por {@link com.nitroboost.ui.ItemDetailView} (botao do modal de
     * detalhes), para os dois pontos de entrada exigirem o mesmo aviso extra sem duplicar a decisao.
     */
    public static boolean requiresExtraConfirmation(ScannedItem item) {
        return "onedrive_uninstall".equals(item.type());
    }

    /**
     * IDs de {@link AiFeatureScanner.AiFeatureKeyDefinition} que, alem da desativacao via politica
     * de registro (acao principal, sempre disponivel), tambem tem um mecanismo de "desinstalar"
     * tecnicamente viavel e distinto (Fase 8 - Ajuste):
     *  - Windows Copilot (usuario/todos os usuarios): remocao do pacote Appx, quando existir nesta
     *    maquina (ver {@link com.nitroboost.actions.ActionExecutor#uninstallAiFeatureApp});
     *  - Windows Recall: desativacao do recurso opcional do Windows via DISM, quando disponivel
     *    nesta edicao/versao (ver {@link com.nitroboost.actions.ActionExecutor#disableRecallFeature}).
     * Click to Do, Cocreator e Copilot no Edge NAO entram aqui de proposito: nao tem um mecanismo de
     * remocao separado da politica (mesma conclusao do documento da Fase 8) - continuam com um unico
     * botao ("Desativar").
     */
    private static final java.util.Set<String> AI_IDS_WITH_UNINSTALL = java.util.Set.of(
            "windows_copilot_user", "windows_copilot_allusers", "windows_recall");

    /** {@code true} se este item tiver, alem de "Desativar", uma segunda acao real de "Desinstalar". */
    public static boolean supportsUninstall(ScannedItem item) {
        return "ai".equals(item.type())
                && item.source() instanceof AiFeatureScanner.AiFeatureKeyDefinition definition
                && AI_IDS_WITH_UNINSTALL.contains(definition.id());
    }

    /**
     * Executa a acao de "Desinstalar" (segunda acao, mais permanente) para os itens de IA que a
     * suportam - so deve ser chamada quando {@link #supportsUninstall} ja confirmou que o item tem
     * essa segunda acao. Segue o mesmo contrato de nunca deixar excecao escapar para a UI.
     */
    public static ActionExecutor.ActionResult performUninstallAction(ActionExecutor executor, ScannedItem item) {
        try {
            if (!(item.source() instanceof AiFeatureScanner.AiFeatureKeyDefinition definition)) {
                return new ActionExecutor.ActionResult(false, "Este item nao suporta desinstalacao.", null);
            }
            return switch (definition.id()) {
                // allUsers=false/true espelha exatamente a distincao ja usada pelas duas entradas de
                // Windows Copilot (usuario atual vs todos os usuarios) - mesma logica de
                // uninstallBloatwareApp, so que sob o itemType "ai" (para o bloqueio valer para as duas
                // acoes do mesmo item). whatIf=false: acao real, mesma decisao pragmatica da Fase 4.
                case "windows_copilot_user" -> executor.uninstallAiFeatureApp(definition, false, false);
                case "windows_copilot_allusers" -> executor.uninstallAiFeatureApp(definition, true, false);
                case "windows_recall" -> executor.disableRecallFeature(definition);
                default -> new ActionExecutor.ActionResult(false, "Este item nao suporta desinstalacao.", null);
            };
        } catch (Exception e) {
            return new ActionExecutor.ActionResult(false, "Erro inesperado ao executar a desinstalacao: " + e.getMessage(), null);
        }
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
                // Item fixo de catalogo (nao vem de um scanner - ver SystemScanTask), sem "source" a
                // fazer cast: a acao mais drastica do projeto (Fase 12 Parte A, secao A.4), por isso
                // SEMPRE exige confirmacao extra explicita antes de chegar aqui (ver
                // requiresExtraConfirmation, checado pela UI antes de despachar esta acao).
                case "onedrive_uninstall" -> executor.uninstallOneDriveCompletely();
                default -> new ActionExecutor.ActionResult(false, "Tipo de item desconhecido: " + item.type(), null);
            };
        } catch (Exception e) {
            return new ActionExecutor.ActionResult(false, "Erro inesperado ao executar a acao: " + e.getMessage(), null);
        }
    }
}
