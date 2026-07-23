package com.nitroboost.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acesso a tabela {@code actions_history}. Regra de ouro do projeto: TODA
 * acao (disable/enable/kill/lock/unlock/restore) executada pela aplicacao
 * precisa passar por aqui, sem excecao - inclusive acoes recusadas (ex: por
 * um item estar bloqueado).
 */
public class ActionHistoryRepository {

    /** Uma entrada do historico de acoes, ja lida do banco. */
    public record HistoryEntry(long id, Long itemId, String itemName, String itemType, String actionType,
                                String previousState, String newState, boolean success,
                                String errorMessage, String performedAt) {
    }

    private final DatabaseManager databaseManager;

    public ActionHistoryRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Grava uma acao no historico e retorna o id gerado - usado, por exemplo,
     * para depois vincular o backup correspondente a esta entrada via
     * {@code BackupManager.linkToHistory}.
     */
    public long record(Long itemId, String itemName, String itemType, String actionType,
                        String previousState, String newState, boolean success, String errorMessage)
            throws SQLException {
        String sql = "INSERT INTO actions_history "
                + "(item_id, item_name, item_type, action_type, previous_state, new_state, success, error_message) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Nao foi possivel obter o id gerado da entrada de historico para '" + itemName + "'");
    }

    public Optional<HistoryEntry> findById(long historyId) throws SQLException {
        String sql = "SELECT id, item_id, item_name, item_type, action_type, previous_state, new_state, "
                + "success, error_message, performed_at FROM actions_history WHERE id = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, historyId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(toEntry(resultSet));
                }
            }
        }
        return Optional.empty();
    }

    /** Histórico completo, mais recente primeiro, limitado a {@code limit} entradas. */
    public List<HistoryEntry> findRecent(int limit) throws SQLException {
        String sql = "SELECT id, item_id, item_name, item_type, action_type, previous_state, new_state, "
                + "success, error_message, performed_at FROM actions_history ORDER BY performed_at DESC, id DESC LIMIT ?";
        List<HistoryEntry> entries = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(toEntry(resultSet));
                }
            }
        }
        return entries;
    }

    /**
     * Imprime as N acoes mais recentes no console (mais recente primeiro) -
     * usado nos testes de integracao via console.
     */
    public void printRecentHistory(int limit) throws SQLException {
        for (HistoryEntry entry : findRecent(limit)) {
            System.out.printf("  #%-4d [%s] %-8s | %-24s (%-8s) | %s -> %s | sucesso=%s%s%n",
                    entry.id(),
                    entry.performedAt(),
                    entry.actionType(),
                    entry.itemName(),
                    entry.itemType(),
                    entry.previousState(),
                    entry.newState(),
                    entry.success(),
                    entry.success() ? "" : " | motivo=" + entry.errorMessage());
        }
    }

    private HistoryEntry toEntry(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("id");
        long rawItemId = resultSet.getLong("item_id");
        Long itemId = resultSet.wasNull() ? null : rawItemId;
        return new HistoryEntry(
                id,
                itemId,
                resultSet.getString("item_name"),
                resultSet.getString("item_type"),
                resultSet.getString("action_type"),
                resultSet.getString("previous_state"),
                resultSet.getString("new_state"),
                resultSet.getInt("success") == 1,
                resultSet.getString("error_message"),
                resultSet.getString("performed_at"));
    }
}
