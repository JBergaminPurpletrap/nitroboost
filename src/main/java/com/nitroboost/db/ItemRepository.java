package com.nitroboost.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Acesso a tabela {@code items} - o catalogo de itens (processos, servicos,
 * entradas de startup, etc.) ja descobertos por algum scanner. Usado pela
 * integracao Scanner -> Knowledge Base (Fase 1, item 1.6) para persistir a
 * classificacao de cada item encontrado, e para dar aos registros de
 * {@code actions_history}/{@code backups} um {@code item_id} de verdade.
 */
public class ItemRepository {

    private final DatabaseManager databaseManager;

    public ItemRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Insere o item se ele ainda nao existir (por nome+tipo), ou atualiza a
     * classificacao/descricao/estado se ja existir. Retorna o id do item.
     */
    public long upsertItem(String name, String type, String classification, String description, String currentState)
            throws SQLException {
        try (Connection connection = databaseManager.getConnection()) {
            Optional<Long> existingId = findId(connection, name, type);
            if (existingId.isPresent()) {
                String updateSql = "UPDATE items SET classification = ?, description = ?, current_state = ?, "
                        + "last_seen_at = datetime('now') WHERE id = ?";
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setString(1, classification);
                    statement.setString(2, description);
                    statement.setString(3, currentState);
                    statement.setLong(4, existingId.get());
                    statement.executeUpdate();
                }
                return existingId.get();
            }

            String insertSql = "INSERT INTO items (name, type, classification, description, current_state) "
                    + "VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, type);
                statement.setString(3, classification);
                statement.setString(4, description);
                statement.setString(5, currentState);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            }
        }
        throw new SQLException("Falha ao inserir/atualizar item '" + name + "' (" + type + ") na tabela items");
    }

    public Optional<Long> findId(String name, String type) throws SQLException {
        try (Connection connection = databaseManager.getConnection()) {
            return findId(connection, name, type);
        }
    }

    private Optional<Long> findId(Connection connection, String name, String type) throws SQLException {
        String sql = "SELECT id FROM items WHERE name = ? AND type = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, type);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(resultSet.getLong(1));
                }
            }
        }
        return Optional.empty();
    }
}
