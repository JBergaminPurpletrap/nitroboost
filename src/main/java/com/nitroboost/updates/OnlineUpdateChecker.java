package com.nitroboost.updates;

import com.nitroboost.db.DatabaseManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Orquestra a verificacao automatica de atualizacao de BIOS (Fase 11 - Nivel 2): cache de 24h -&gt;
 * checagem de {@code robots.txt} -&gt; requisicao HTTP -&gt; {@link VendorPageParser} especifico do
 * fabricante -&gt; resultado - com fallback gracioso (nunca excecao, nunca tela travada) em
 * qualquer etapa que falhar. So deve ser chamado quando o usuario clica no botao "Verificar
 * Atualizacao Online" (nunca automaticamente durante um scan geral - item 4 do Nivel 2).
 */
public class OnlineUpdateChecker {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final String FALLBACK_MESSAGE =
            "Nao foi possivel verificar automaticamente agora - use o link abaixo para checar manualmente.";

    /**
     * Parsers de Nivel 2 disponiveis por fabricante. So ASUS por enquanto - ver PROGRESS.md para a
     * decisao e o estado atual (MSI/Gigabyte/ASRock ainda nao tem parser, mas podem ser adicionados
     * depois seguindo o mesmo padrao do {@link AsusPageParser}).
     */
    private static final Map<String, VendorPageParser> PARSERS = Map.of(
            "ASUS", new AsusPageParser()
    );

    /**
     * @param success       true se a versao mais recente foi extraida com sucesso (ou veio de cache com sucesso)
     * @param message       mensagem pronta em portugues claro para exibir na UI
     * @param latestVersion versao extraida, ou null se nao foi possivel
     * @param fromCache     true se o resultado veio do cache de 24h (nenhuma requisicao HTTP nova foi feita)
     */
    public record CheckResult(boolean success, String message, String latestVersion, boolean fromCache) {
    }

    private final UpdateCheckCache cache;
    private final RobotsTxtChecker robotsTxtChecker;
    private final HttpClient httpClient;

    public OnlineUpdateChecker(DatabaseManager databaseManager) {
        this(new UpdateCheckCache(databaseManager), new RobotsTxtChecker(),
                HttpClient.newBuilder()
                        .connectTimeout(HTTP_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    /** Construtor completo - permite injetar dependencias fake nos testes (ex: URL invalida de proposito). */
    public OnlineUpdateChecker(UpdateCheckCache cache, RobotsTxtChecker robotsTxtChecker, HttpClient httpClient) {
        this.cache = cache;
        this.robotsTxtChecker = robotsTxtChecker;
        this.httpClient = httpClient;
    }

    /**
     * @param vendorName nome do fabricante resolvido (ex: "ASUS", vindo de {@code VendorLinkStrategy#vendorName()})
     * @param model      modelo detectado da placa-mae (pode ser vazio se nao detectado)
     * @param pageUrl    URL da pagina de suporte a consultar (camada 1/2 da {@link VendorLinkStrategy}); pode ser null
     */
    public CheckResult checkForUpdate(String vendorName, String model, String pageUrl) {
        String cacheVendor = vendorName == null ? "" : vendorName;
        String cacheModel = (model == null || model.isBlank()) ? "(desconhecido)" : model;

        Optional<UpdateCheckCache.CachedResult> cached = cache.findFresh(cacheVendor, cacheModel);
        if (cached.isPresent()) {
            UpdateCheckCache.CachedResult c = cached.get();
            String message = c.success()
                    ? "Versao mais recente encontrada no site do fabricante (resultado em cache, ate 24h): " + c.resultVersion()
                    : FALLBACK_MESSAGE + " (ultima tentativa, em cache, tambem nao conseguiu)";
            return new CheckResult(c.success(), message, c.resultVersion(), true);
        }

        VendorPageParser parser = PARSERS.get(cacheVendor);
        if (parser == null || pageUrl == null || pageUrl.isBlank()) {
            // Sem parser para este fabricante (ainda) ou sem URL para consultar - cai direto pro
            // Nivel 1, sem tentar rede nenhuma.
            cache.save(cacheVendor, cacheModel, null, false);
            return new CheckResult(false, FALLBACK_MESSAGE, null, false);
        }

        try {
            if (!robotsTxtChecker.isAllowed(pageUrl)) {
                cache.save(cacheVendor, cacheModel, null, false);
                return new CheckResult(false,
                        "O site do fabricante pede para nao acessar este caminho automaticamente (robots.txt). "
                                + FALLBACK_MESSAGE,
                        null, false);
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(pageUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) NitroBoost/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                cache.save(cacheVendor, cacheModel, null, false);
                return new CheckResult(false, FALLBACK_MESSAGE, null, false);
            }

            Optional<String> version = parser.extractLatestBiosVersion(response.body());
            cache.save(cacheVendor, cacheModel, version.orElse(null), version.isPresent());

            if (version.isPresent()) {
                return new CheckResult(true,
                        "Versao mais recente encontrada no site do fabricante: " + version.get()
                                + ". Confira visualmente se e mais nova que a sua versao atual antes de atualizar "
                                + "(o formato de versao varia entre fabricantes).",
                        version.get(), false);
            }
            return new CheckResult(false, FALLBACK_MESSAGE, null, false);

        } catch (Exception e) {
            // Qualquer falha (timeout, dominio invalido, HTML mudou de estrutura e o parser lancou
            // algo inesperado, etc.) cai de volta pro Nivel 1 silenciosamente - nunca propaga.
            cache.save(cacheVendor, cacheModel, null, false);
            return new CheckResult(false, FALLBACK_MESSAGE, null, false);
        }
    }

    /**
     * Teste isolado via console (requisicao HTTP real contra a ASUS) - ver
     * {@code Phase11Nivel2ConsoleDemo} para o roteiro completo (sucesso real + falha proposital +
     * cache):
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.updates.OnlineUpdateChecker
     */
    public static void main(String[] args) {
        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("Falha ao inicializar schema: " + e.getMessage());
            return;
        }
        OnlineUpdateChecker checker = new OnlineUpdateChecker(databaseManager);
        String model = "ROG STRIX B650-A GAMING WIFI";
        String url = new AsusLinkStrategy().deepLinkUrl(model);

        System.out.println("===== NITRO BOOST - OnlineUpdateChecker (teste isolado, ASUS real) =====");
        CheckResult result = checker.checkForUpdate("ASUS", model, url);
        System.out.println("sucesso=" + result.success() + " | fromCache=" + result.fromCache() + " | " + result.message());
    }
}
