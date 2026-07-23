package com.nitroboost.ui;

import com.nitroboost.knowledge.ItemClassification;

/**
 * Representacao unificada de um item encontrado por QUALQUER um dos 7
 * scanners do backend (processo, servico, startup, tarefa agendada, plano de
 * energia, telemetria, bloatware), ja cruzada com a {@link
 * com.nitroboost.knowledge.KnowledgeBase} - usada pela {@link ScanResultsView}
 * (tabela/filtros) e pelo {@link ItemDetailView} (modal de detalhes).
 *
 * @param category         categoria exibida ao usuario (ex: "Processos", "Servicos") - usada nos filtros
 * @param type             tipo interno usado pelo backend (process/service/startup/task/powerplan/telemetry/bloatware),
 *                         o mesmo valor de {@code item_type} gravado em {@code actions_history}
 * @param name             nome do item
 * @param currentState     estado atual, formatado para leitura humana (ex: "PID 1234 | 120 MB")
 * @param classification   classificacao de risco vinda da base de conhecimento (ou {@code DEPENDE} se nao catalogado)
 * @param description      explicacao em portugues claro do que o item faz
 * @param disableImpact    o que acontece se o item for desativado
 * @param keepImpact       o que acontece se o item for mantido como esta
 * @param source           o registro original do scanner (ex: {@code ProcessScanner.ProcessInfo}) - necessario
 *                         para chamar o metodo certo do {@code ActionExecutor} (ver {@link ItemActionDispatcher})
 */
public record ScannedItem(String category, String type, String name, String currentState,
                           ItemClassification classification, String description,
                           String disableImpact, String keepImpact, Object source) {
}
