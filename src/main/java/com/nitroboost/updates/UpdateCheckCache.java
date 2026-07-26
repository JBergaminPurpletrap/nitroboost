package com.nitroboost.updates;

import com.nitroboost.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Cache local (SQLite, tabela {@code update_check_cache}) do resultado da verificacao online de
 * BIOS (Fase 11 - Nivel 2), com validade de 24h - item 2.2 do documento da fase: "nunca bater no
 * site do fabricante a cada clique repetido em pouco tempo", ainda mais importante pensando que o
 * projeto e pensado para uso por multiplas pessoas no futuro.
 *
 * Reaproveita o {@link DatabaseManager}/banco SQLite ja existente do projeto (mesmo padrao de
 * {@code ItemRepository}), nao cria um banco/arquivo separado so para este cache.
 */
public class UpdateCheckCache {

    private static final Duration VALIDITY = Duration.ofHours(24);

    /** Resultado em cache para um par fabricante+modelo. */
    public record CachedResult(String resultVersion, boolean success, Instant checkedAt) {

        public boolean isFresh() {
            return checkedAt != null && Duration.between(checkedAt, Instant.now()).compareTo(VALIDITY) < 0;
        }
    }

    private final DatabaseManager databaseManager;

    public UpdateCheckCache(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Devolve o resultado em cache se existir e tiver menos de 24h; {@link Optional#empty()} caso
     * contrario (nao encontrado, expirado, ou erro de leitura - tratado como "sem cache", nunca
     * lanca excecao para o chamador).
     */
    public Optional<CachedResult> findFresh(String vendor, String model) {
        String sql = "SELECT result_version, success, checked_at FROM update_check_cache "
                + "WHERE vendor = ? AND model = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vendor);
            statement.setString(2, model);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    Instant checkedAt = parseInstant(resultSet.getString("checked_at"));
                    CachedResult cached = new CachedResult(
                            resultSet.getString("result_version"),
                            resultSet.getInt("success") == 1,
                            checkedAt);
                    return cached.isFresh() ? Optional.of(cached) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Erro ao ler cache de verificacao de atualizacao: " + e.getMessage());
        }
        return Optional.empty();
    }

    /** Grava (ou substitui) o resultado da verificacao para o par fabricante+modelo. */
    public void save(String vendor, String model, String resultVersion, boolean success) {
        String sql = "INSERT INTO update_check_cache (vendor, model, checked_at, result_version, success) "
                + "VALUES (?, ?, datetime('now'), ?, ?) "
                + "ON CONFLICT(vendor, model) DO UPDATE SET checked_at = datetime('now'), "
                + "result_version = excluded.result_version, success = excluded.success";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vendor);
            statement.setString(2, model);
            statement.setString(3, resultVersion);
            statement.setInt(4, success ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Erro ao gravar cache de verificacao de atualizacao: " + e.getMessage());
        }
    }

    /** SQLite {@code datetime('now')} grava "yyyy-MM-dd HH:mm:ss" em UTC. */
    private Instant parseInstant(String sqliteDatetime) {
        if (sqliteDatetime == null || sqliteDatetime.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(sqliteDatetime.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Teste isolado via console (sem rede - so grava/le do banco local):
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.updates.UpdateCheckCache
     */
    public static void main(String[] args) {
        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (SQLException e) {
            System.err.println("Falha ao inicializar schema: " + e.getMessage());
            return;
        }
        UpdateCheckCache cache = new UpdateCheckCache(databaseManager);

        System.out.println("===== NITRO BOOST - UpdateCheckCache (teste isolado) =====");
        System.out.println("Antes de gravar -> presente: " + cache.findFresh("ASUS", "TESTE-CACHE-FASE11").isPresent());

        cache.save("ASUS", "TESTE-CACHE-FASE11", "9999", true);
        Optional<CachedResult> fresh = cache.findFresh("ASUS", "TESTE-CACHE-FASE11");
        System.out.println("Apos gravar -> presente: " + fresh.isPresent()
                + " | versao=" + fresh.map(CachedResult::resultVersion).orElse("?"));
    }
}
