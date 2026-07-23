package com.nitroboost.actions;

import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.db.ItemRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Controla o "bloqueio" (protecao) de itens contra acoes destrutivas.
 *
 * Um item bloqueado nao pode ser desativado/finalizado pelo {@link ActionExecutor}
 * ate que seja desbloqueado explicitamente pelo usuario - regra de ouro do
 * projeto ("itens bloqueados nao podem ser alterados sem desbloqueio manual
 * explicito").
 *
 * Responsabilidade unica: gerenciar a tabela {@code locks} (bloquear,
 * desbloquear, consultar). Quem decide RECUSAR uma acao por causa de um
 * bloqueio e o {@link ActionExecutor}, que apenas consulta
 * {@link #isLocked(String, String)} antes de agir.
 */
public class LockManager {

    public record LockResult(boolean success, String message) {
    }

    private final ItemRepository itemRepository;
    private final ActionHistoryRepository historyRepository;
    private final DatabaseManager databaseManager;

    public LockManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.itemRepository = new ItemRepository(databaseManager);
        this.historyRepository = new ActionHistoryRepository(databaseManager);
    }

    /** Marca um item como protegido. Cria o item no catalogo se ainda nao existir. */
    public LockResult lockItem(String itemName, String itemType, String reason) {
        try {
            long itemId = resolveOrCreateItemId(itemName, itemType);
            String sql = "INSERT INTO locks (item_id, item_name, item_type, reason) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT(item_id) DO UPDATE SET reason = excluded.reason, locked_at = datetime('now')";
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, itemId);
                statement.setString(2, itemName);
                statement.setString(3, itemType);
                statement.setString(4, reason);
                statement.executeUpdate();
            }
            setItemLockedFlag(itemId, true);

            String message = "Item '" + itemName + "' (" + itemType + ") bloqueado com sucesso.";
            historyRepository.record(itemId, itemName, itemType, "lock", "unlocked", "locked", true, null);
            return new LockResult(true, message);
        } catch (SQLException e) {
            String errorMessage = "Erro ao bloquear item '" + itemName + "': " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new LockResult(false, errorMessage);
        }
    }

    /** Remove a protecao de um item, permitindo novamente acoes sobre ele. */
    public LockResult unlockItem(String itemName, String itemType) {
        try {
            var itemIdOpt = itemRepository.findId(itemName, itemType);
            if (itemIdOpt.isEmpty()) {
                return new LockResult(false, "Item '" + itemName + "' (" + itemType + ") nao encontrado no catalogo.");
            }
            long itemId = itemIdOpt.get();

            String sql = "DELETE FROM locks WHERE item_id = ?";
            int rowsDeleted;
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, itemId);
                rowsDeleted = statement.executeUpdate();
            }
            setItemLockedFlag(itemId, false);

            String message = rowsDeleted > 0
                    ? "Item '" + itemName + "' (" + itemType + ") desbloqueado com sucesso."
                    : "Item '" + itemName + "' (" + itemType + ") ja estava desbloqueado.";
            historyRepository.record(itemId, itemName, itemType, "unlock", "locked", "unlocked", true, null);
            return new LockResult(true, message);
        } catch (SQLException e) {
            String errorMessage = "Erro ao desbloquear item '" + itemName + "': " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new LockResult(false, errorMessage);
        }
    }

    /**
     * Verifica se um item esta bloqueado. Nunca lanca excecao: em caso de
     * erro de leitura, assume "nao bloqueado" (falha aberta) e loga o erro -
     * mas registra o erro no console para investigacao.
     */
    public boolean isLocked(String itemName, String itemType) {
        String sql = "SELECT 1 FROM locks WHERE item_name = ? AND item_type = ? LIMIT 1";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemName);
            statement.setString(2, itemType);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Erro ao verificar bloqueio de '" + itemName + "': " + e.getMessage());
            return false;
        }
    }

    private long resolveOrCreateItemId(String itemName, String itemType) throws SQLException {
        var existingId = itemRepository.findId(itemName, itemType);
        if (existingId.isPresent()) {
            return existingId.get();
        }
        // Item ainda nao catalogado (ex: usuario bloqueou algo antes de qualquer varredura
        // persistir esse item) - cria uma entrada minima no catalogo so para poder referencia-la.
        return itemRepository.upsertItem(itemName, itemType, null, null, null);
    }

    private void setItemLockedFlag(long itemId, boolean locked) throws SQLException {
        String sql = "UPDATE items SET is_locked = ? WHERE id = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, locked ? 1 : 0);
            statement.setLong(2, itemId);
            statement.executeUpdate();
        }
    }
}
