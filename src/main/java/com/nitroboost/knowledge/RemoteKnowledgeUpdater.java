package com.nitroboost.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Verifica e baixa uma versao mais nova da base de conhecimento, hospedada em uma URL HTTP(S)
 * configuravel (Fase 7 - Expansao Online). Reusa exatamente o mesmo schema de
 * {@code knowledge-base.json} (ver {@link KnowledgeBase}) - nao inventa um formato novo.
 *
 * Nunca quebra a aplicacao: qualquer falha (sem internet, host fora do ar, timeout, JSON
 * malformado) e capturada e devolvida como um {@link UpdateCheckResult} com
 * {@code available=false}, mantendo a base local como fallback. E responsabilidade do chamador
 * decidir se aplica a atualizacao (nao ha download/escrita automatica sem chamada explicita a
 * {@link #applyUpdate}).
 */
public class RemoteKnowledgeUpdater {

    /**
     * URL de EXEMPLO, usada nos testes/demo desta fase - aponta para um Gist PUBLICO criado para
     * validar o fluxo de ponta a ponta (ver PROGRESS.md, Fase 7). NAO ha uma URL de producao
     * "oficial" definida ainda - essa decisao de deploy (onde a base remota real vai morar a
     * longo prazo) fica para depois. Pode ser sobrescrita sem recompilar via
     * {@link #CONFIG_FILE_NAME} (chave {@code remoteKnowledgeBaseUrl}).
     */
    public static final String DEFAULT_REMOTE_URL =
            "https://gist.githubusercontent.com/JBergaminPurpletrap/e9690bc766d352089d1ff46f95a3534c/raw/remote-knowledge-base.json";

    private static final String CACHE_FOLDER_NAME = ".nitroboost";
    private static final String CACHE_FILE_NAME = "remote-knowledge-base-cache.json";
    private static final String CONFIG_FILE_NAME = "remote-config.properties";
    private static final String CONFIG_KEY_URL = "remoteKnowledgeBaseUrl";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * @param available       true se o download e o parse do JSON remoto deram certo
     * @param newerThanLocal  true se a base remota parece mais nova que a local (por versao ou data)
     * @param remoteVersion   campo "version" do JSON remoto (vazio se ausente)
     * @param remoteUpdatedAt campo "updatedAt" do JSON remoto (vazio se ausente)
     * @param remoteItemCount quantidade de itens no JSON remoto
     * @param message         mensagem pronta em portugues claro (sucesso ou motivo da falha)
     * @param rawJson         corpo bruto da resposta (usado por {@link #applyUpdate}); null se falhou
     */
    public record UpdateCheckResult(boolean available, boolean newerThanLocal, String remoteVersion,
                                     String remoteUpdatedAt, int remoteItemCount, String message, String rawJson) {
    }

    private final HttpClient httpClient;
    private final Path cacheFilePath;

    public RemoteKnowledgeUpdater() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), resolveCacheFilePath());
    }

    /** Permite injetar client/caminho customizados (util para testes). */
    public RemoteKnowledgeUpdater(HttpClient httpClient, Path cacheFilePath) {
        this.httpClient = httpClient;
        this.cacheFilePath = cacheFilePath;
    }

    public Path getCacheFilePath() {
        return cacheFilePath;
    }

    private static Path resolveCacheFilePath() {
        return Path.of(System.getProperty("user.home"), CACHE_FOLDER_NAME, CACHE_FILE_NAME);
    }

    /**
     * Le a URL configurada em {@code %USERPROFILE%\.nitroboost\remote-config.properties}
     * (chave {@code remoteKnowledgeBaseUrl}). Se o arquivo nao existir ou a chave nao estiver
     * definida, devolve {@link #DEFAULT_REMOTE_URL} (placeholder de exemplo). Nunca lanca excecao.
     */
    public static String resolveConfiguredUrl() {
        Path configPath = Path.of(System.getProperty("user.home"), CACHE_FOLDER_NAME, CONFIG_FILE_NAME);
        if (Files.isRegularFile(configPath)) {
            try (InputStream inputStream = Files.newInputStream(configPath)) {
                Properties properties = new Properties();
                properties.load(inputStream);
                String configured = properties.getProperty(CONFIG_KEY_URL);
                if (configured != null && !configured.isBlank()) {
                    return configured.trim();
                }
            } catch (IOException e) {
                System.err.println("[NITRO BOOST] Nao foi possivel ler " + configPath + ", usando URL padrao: " + e.getMessage());
            }
        }
        return DEFAULT_REMOTE_URL;
    }

    /**
     * Baixa e compara a base remota em {@code url} com a base local (classpath). Trata toda falha
     * de rede/parse de forma graciosa - nunca lanca excecao, sempre devolve um resultado.
     */
    public UpdateCheckResult checkForUpdate(String url) {
        return checkForUpdate(url, new KnowledgeBase());
    }

    /** Mesma verificacao, mas comparando contra uma {@link KnowledgeBase} local ja carregada (evita recarregar). */
    public UpdateCheckResult checkForUpdate(String url, KnowledgeBase localKnowledgeBase) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                return failure("Servidor remoto respondeu com status HTTP " + response.statusCode() + ".");
            }

            KnowledgeBase.LoadedData parsed;
            try (InputStream bodyStream = new java.io.ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8))) {
                parsed = KnowledgeBase.parse(bodyStream);
            }
            boolean newer = isNewer(parsed.version(), parsed.updatedAt(), localKnowledgeBase.version(), localKnowledgeBase.updatedAt());
            String message = newer
                    ? "Ha uma versao mais nova da base de conhecimento disponivel (remota: versao "
                      + parsed.version() + "/" + parsed.updatedAt() + " vs local: versao "
                      + localKnowledgeBase.version() + "/" + localKnowledgeBase.updatedAt() + ")."
                    : "A base local ja esta atualizada (versao " + localKnowledgeBase.version() + ").";

            return new UpdateCheckResult(true, newer, parsed.version(), parsed.updatedAt(),
                    parsed.entries().size(), message, response.body());
        } catch (Exception e) {
            // Cobre IOException (sem internet/host fora do ar), InterruptedException (timeout) e
            // qualquer erro de parse do JSON malformado - nunca deixa a verificacao quebrar a app.
            return failure("Nao foi possivel verificar atualizacao da base remota (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "). Usando a base local.");
        }
    }

    private UpdateCheckResult failure(String message) {
        System.err.println("[NITRO BOOST] " + message);
        return new UpdateCheckResult(false, false, "", "", 0, message, null);
    }

    /** Compara versao (string simples) e data (ISO-8601, comparacao lexica funciona) remota vs local. */
    private boolean isNewer(String remoteVersion, String remoteUpdatedAt, String localVersion, String localUpdatedAt) {
        if (!remoteVersion.isBlank() && !remoteVersion.equals(localVersion)) {
            return true;
        }
        return !remoteUpdatedAt.isBlank() && remoteUpdatedAt.compareTo(localUpdatedAt) > 0;
    }

    /**
     * Salva o JSON bruto de um resultado bem-sucedido no arquivo de cache local
     * ({@code %USERPROFILE%\.nitroboost\remote-knowledge-base-cache.json}) - NUNCA sobrescreve o
     * recurso original do classpath. Depois de aplicado, {@code new KnowledgeBase(cacheFilePath)}
     * passa a carregar a versao baixada. Devolve false (sem lancar excecao) se o resultado nao
     * tiver dados validos ou se a escrita falhar.
     */
    public boolean applyUpdate(UpdateCheckResult result) {
        if (result == null || !result.available() || result.rawJson() == null) {
            System.err.println("[NITRO BOOST] Nao ha atualizacao valida para aplicar.");
            return false;
        }
        try {
            Files.createDirectories(cacheFilePath.getParent());
            Files.writeString(cacheFilePath, result.rawJson(), StandardCharsets.UTF_8);
            System.out.println("[NITRO BOOST] Base de conhecimento remota salva em: " + cacheFilePath);
            return true;
        } catch (IOException e) {
            System.err.println("[NITRO BOOST] Falha ao salvar cache da base remota: " + e.getMessage());
            return false;
        }
    }

    /**
     * Permite testar isoladamente via console (usa a URL configurada ou o placeholder padrao):
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.knowledge.RemoteKnowledgeUpdater
     */
    public static void main(String[] args) {
        String url = resolveConfiguredUrl();
        System.out.println("===== NITRO BOOST - Verificacao de Atualizacao da Base Remota =====");
        System.out.println("URL configurada: " + url);

        RemoteKnowledgeUpdater updater = new RemoteKnowledgeUpdater();
        UpdateCheckResult result = updater.checkForUpdate(url);
        System.out.println("Disponivel: " + result.available());
        System.out.println("Mais nova que a local: " + result.newerThanLocal());
        System.out.println("Mensagem: " + result.message());
        if (result.available() && result.newerThanLocal()) {
            boolean applied = updater.applyUpdate(result);
            System.out.println("Aplicada (salva em cache local): " + applied);
        }
    }
}
