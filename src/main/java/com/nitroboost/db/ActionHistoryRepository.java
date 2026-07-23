package com.nitroboost.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Acesso a tabela {@code actions_history}. Regra de ouro do projeto: TODA
 * acao (disable/enable/kill/lock/unlock/restore) executada pela aplicacao
 * precisa passar por aqui, sem excecao.
 */
public class ActionHistoryRepository {

    private final DatabaseManager databaseManager;

    public ActionHistoryRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void record(Long itemId, String itemName, String itemType, String actionType,
                        String previousState, String newState, boolean success, String errorMessage)
            throws SQLException {
        String sql = "INSERT INTO actions_history "
                + "(item_id, item_name, item_type, action_type, previous_state, new_state, success, error_message) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (itemId != null) {
                statement.setLong(1, itemId);
            } else {
                statement.setNull(1, Types.INTEGER);
            }
            statement.setString(2, itemName);
            statement.setString(3, itemType);
            statement.setString(4, actionType);
            statement.setString(5, previousState);
            statement.setString(6, newState);
            statement.setInt(7, success ? 1 : 0);
            statement.setString(8, errorMessage);
            statement.executeUpdate();
        }
    }

    /**
     * Imprime as N acoes mais recentes no console (mais recente primeiro) -
     * usado nos testes de integracao via console da Fase 1.
     */
    public void printRecentHistory(int limit) throws SQLException {
        String sql = "SELECT item_name, item_type, action_type, previous_state, new_state, success, performed_at "
                + "FROM actions_history ORDER BY performed_at DESC, id DESC LIMIT ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    System.out.printf("  [%s] %-10s | %-20s (%-8s) | %s -> %s | sucesso=%s%n",
                            resultSet.getString("performed_at"),
                            resultSet.getString("action_type"),
                            resultSet.getString("item_name"),
                            resultSet.getString("item_type"),
                            resultSet.getString("previous_state"),
                            resultSet.getString("new_state"),
                            resultSet.getInt("success") == 1);
                }
            }
        }
    }
}
