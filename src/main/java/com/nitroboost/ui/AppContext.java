package com.nitroboost.ui;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.MemoryCleaner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.knowledge.KnowledgeBase;
import com.nitroboost.repair.SystemFileRepairTool;

/**
 * Agrupa as instancias unicas do backend (banco, executor de acoes, base de
 * conhecimento etc.) para injetar nas views da UI sem duplicar a criacao em
 * cada tela - toda a UI e "so uma camada fina" sobre esse backend ja
 * validado nas Fases 1 a 3 (regra de ouro do projeto).
 */
public record AppContext(DatabaseManager databaseManager, ActionExecutor actionExecutor,
                          LockManager lockManager, KnowledgeBase knowledgeBase,
                          ActionHistoryRepository historyRepository, MemoryCleaner memoryCleaner,
                          SystemFileRepairTool systemFileRepairTool) {

    public static AppContext create() {
        DatabaseManager databaseManager = new DatabaseManager();
        return new AppContext(
                databaseManager,
                new ActionExecutor(databaseManager),
                new LockManager(databaseManager),
                new KnowledgeBase(),
                new ActionHistoryRepository(databaseManager),
                new MemoryCleaner(databaseManager),
                new SystemFileRepairTool(databaseManager)
        );
    }
}
