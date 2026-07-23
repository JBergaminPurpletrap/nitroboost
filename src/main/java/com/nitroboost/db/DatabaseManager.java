package com.nitroboost.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Responsavel por abrir/gerenciar a conexao com o banco SQLite local e por
 * garantir que o schema inicial (tabelas items, actions_history, backups,
 * locks) exista.
 *
 * O caminho do banco NUNCA e fixo para uma maquina especifica: usamos a
 * pasta de perfil do usuario ({@code user.home}), que existe em qualquer
 * instalacao do Windows, seguindo a regra de ouro do projeto de nunca
 * hardcodar caminhos especificos de uma unica maquina.
 */
public class DatabaseManager {

    private static final String DB_FOLDER_NAME = ".nitroboost";
    private static final String DB_FILE_NAME = "nitroboost.db";
    private static final String SCHEMA_RESOURCE_PATH = "/com/nitroboost/db/schema.sql";

    private final Path databasePath;

    public DatabaseManager() {
        this.databasePath = resolveDatabasePath();
    }

    /**
     * Permite injetar um caminho customizado (util para testes).
     */
    public DatabaseManager(Path databasePath) {
        this.databasePath = databasePath;
    }

    private static Path resolveDatabasePath() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, DB_FOLDER_NAME, DB_FILE_NAME);
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    /**
     * Abre (ou cria, se ainda nao existir) uma conexao com o arquivo SQLite.
     * O chamador e responsavel por fechar a conexao (try-with-resources).
     */
    public Connection getConnection() throws SQLException {
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (IOException e) {
            throw new SQLException("Nao foi possivel criar a pasta do banco de dados: " + databasePath.getParent(), e);
        }
        String jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * Le db/schema.sql (empacotado no classpath) e executa cada instrucao
     * CREATE TABLE / CREATE INDEX. E seguro chamar este metodo toda vez que
     * a aplicacao inicia, pois o schema usa "IF NOT EXISTS".
     */
    public void initializeSchema() throws SQLException {
        String schemaSql = readSchemaResource();

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            for (String rawStatement : schemaSql.split(";")) {
                String trimmed = rawStatement.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                statement.execute(trimmed);
            }

            System.out.println("[NITRO BOOST] Banco SQLite inicializado em: " + databasePath.toAbsolutePath());
        }
    }

    private String readSchemaResource() throws SQLException {
        try (InputStream inputStream = DatabaseManager.class.getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
            if (inputStream == null) {
                throw new SQLException("Arquivo schema.sql nao encontrado no classpath: " + SCHEMA_RESOURCE_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    // Remove comentarios de linha (--) antes de juntar o SQL, senao um
                    // ";" dentro de um comentario quebraria o split ingenuo por ";" feito
                    // em initializeSchema().
                    int commentIndex = line.indexOf("--");
                    String withoutComment = commentIndex >= 0 ? line.substring(0, commentIndex) : line;
                    if (withoutComment.isBlank()) {
                        continue;
                    }
                    builder.append(withoutComment).append('\n');
                }
                return builder.toString();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao ler schema.sql do classpath", e);
        }
    }

    /**
     * Permite validar a criacao do banco isoladamente, sem subir a UI:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.db.DatabaseManager
     */
    public static void main(String[] args) {
        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();

            try (Connection connection = databaseManager.getConnection();
                 Statement statement = connection.createStatement()) {
                var resultSet = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name");
                System.out.println("[NITRO BOOST] Tabelas encontradas no banco:");
                while (resultSet.next()) {
                    System.out.println(" - " + resultSet.getString("name"));
                }
            }
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Erro ao validar schema do banco: " + e.getMessage());
        }
    }
}
