package com.nitroboost.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nitroboost.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Responsavel por salvar (e depois recuperar) o "estado anterior" de um item
 * antes de qualquer acao destrutiva ser aplicada nele. Regra de ouro do
 * projeto: NENHUMA acao destrutiva (kill/stop/disable) pode ser feita sem
 * que o backup correspondente ja tenha sido salvo primeiro.
 *
 * O BackupManager so cuida de PERSISTIR o snapshot (na tabela {@code
 * backups}) e de marcar quando ele foi usado para reverter algo. Ele
 * deliberadamente NAO sabe "como" reverter cada tipo de item no sistema
 * operacional (isso e responsabilidade do {@link ActionExecutor}, que
 * conhece os comandos especificos de processo/servico/startup) - assim
 * cada classe mantem uma unica responsabilidade.
 */
public class BackupManager {

    /**
     * @param id             id do backup no banco
     * @param itemId         id do item na tabela items (pode ser nulo)
     * @param itemName       nome do item no momento do backup
     * @param itemType       tipo do item ('process' | 'service' | 'startup' | ...)
     * @param stateSnapshot  estado salvo, ja desserializado do JSON
     * @param restored       se este backup ja foi usado para reverter a acao
     */
    public record BackupRecord(long id, Long itemId, String itemName, String itemType,
                                Map<String, Object> stateSnapshot, boolean restored) {
    }

    private final DatabaseManager databaseManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BackupManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Salva o estado atual de um item ANTES de uma acao ser executada nele.
     * Deve ser chamado sempre antes de qualquer chamada em
     * {@link ActionExecutor} que altere o estado real do sistema.
     *
     * @return o id do backup criado (para referencia futura / reversao)
     */
    public long snapshotBeforeAction(Long itemId, String itemName, String itemType, Map<String, Object> stateSnapshot)
            throws SQLException {
        String json;
        try {
            json = objectMapper.writeValueAsString(stateSnapshot);
        } catch (Exception e) {
            throw new SQLException("Falha ao serializar snapshot do item '" + itemName + "' para JSON", e);
        }

        String sql = "INSERT INTO backups (item_id, item_name, item_type, state_snapshot) VALUES (?, ?, ?, ?)";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (itemId != null) {
                statement.setLong(1, itemId);
            } else {
                statement.setNull(1, Types.INTEGER);
            }
            statement.setString(2, itemName);
            statement.setString(3, itemType);
            statement.setString(4, json);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    long backupId = keys.getLong(1);
                    System.out.printf("[NITRO BOOST] Backup #%d criado para '%s' (%s) antes da acao.%n",
                            backupId, itemName, itemType);
                    return backupId;
                }
            }
        }
        throw new SQLException("Nao foi possivel obter o id gerado do backup para '" + itemName + "'");
    }

    public Optional<BackupRecord> findById(long backupId) throws SQLException {
        String sql = "SELECT id, item_id, item_name, item_type, state_snapshot, restored FROM backups WHERE id = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, backupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(toRecord(resultSet));
                }
            }
        }
        return Optional.empty();
    }

    /** Backup mais recente ainda nao restaurado para um item, se houver. */
    public Optional<BackupRecord> findLatestNotRestored(String itemName, String itemType) throws SQLException {
        String sql = "SELECT id, item_id, item_name, item_type, state_snapshot, restored FROM backups "
                + "WHERE item_name = ? AND item_type = ? AND restored = 0 "
                + "ORDER BY created_at DESC, id DESC LIMIT 1";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemName);
            statement.setString(2, itemType);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(toRecord(resultSet));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Marca um backup como restaurado. So deve ser chamado DEPOIS que a
     * reversao de fato no sistema operacional foi bem sucedida (feita pelo
     * {@link ActionExecutor}) - o BackupManager confia no chamador para essa
     * ordem, pois ele mesmo nao executa nenhuma acao no SO.
     */
    public void markRestored(long backupId) throws SQLException {
        String sql = "UPDATE backups SET restored = 1, restored_at = datetime('now') WHERE id = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, backupId);
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private BackupRecord toRecord(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("id");
        long rawItemId = resultSet.getLong("item_id");
        Long itemId = resultSet.wasNull() ? null : rawItemId;
        String itemName = resultSet.getString("item_name");
        String itemType = resultSet.getString("item_type");
        String json = resultSet.getString("state_snapshot");
        boolean restored = resultSet.getInt("restored") == 1;

        Map<String, Object> snapshot;
        try {
            snapshot = objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao desserializar snapshot do backup #" + id + ": " + e.getMessage());
            snapshot = Map.of();
        }
        return new BackupRecord(id, itemId, itemName, itemType, snapshot, restored);
    }
}
