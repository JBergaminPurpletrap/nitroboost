package com.nitroboost.audit;

/**
 * Um item comparado pelo diagnostico do sistema: valor atual lido da maquina vs valor
 * recomendado catalogado na base de conhecimento (ver {@link SystemAuditEngine}).
 *
 * @param itemName          nome amigavel do item (mesmo nome usado pelo catalogo/lock/historico do backend)
 * @param itemType          tipo interno (telemetry/performance/gaming/ai/consumer) - mesmo valor usado por
 *                          {@link com.nitroboost.ui.ScannedItem#type()}
 * @param category          categoria exibida ao usuario (ex: "Telemetria")
 * @param status            {@link Status#JA_OTIMIZADO} / {@link Status#SUGESTAO} / {@link Status#NAO_APLICAVEL}
 * @param currentValue      valor bruto atual lido do registro, ou {@code null} se a chave nao estiver definida
 * @param recommendedValue  valor recomendado vindo da base de conhecimento, ou {@code null} se nao houver
 *                          comparacao objetiva possivel para este item (vira {@code NAO_APLICAVEL})
 * @param source            a definicao original do scanner (ex: {@code TelemetryScanner.TelemetryKeyDefinition}) -
 *                          o mesmo objeto que {@code ScannedItem.source()} guardaria, necessario para reconstruir
 *                          um {@code ScannedItem} e disparar a acao certa via {@code ItemActionDispatcher}/{@code ActionExecutor}
 */
public record AuditFinding(String itemName, String itemType, String category, Status status,
                            String currentValue, String recommendedValue, Object source) {

    public enum Status { JA_OTIMIZADO, SUGESTAO, NAO_APLICAVEL }
}
