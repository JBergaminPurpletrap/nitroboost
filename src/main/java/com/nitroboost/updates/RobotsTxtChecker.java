package com.nitroboost.updates;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Checagem pragmatica e defensiva de {@code robots.txt} antes de fazer qualquer requisicao HTTP a
 * um dominio de fabricante (item 2 da secao "Nivel 2" do documento da Fase 11).
 *
 * NAO e um parser completo da RFC do robots.txt (nao trata User-agent especifico do NITRO BOOST,
 * prioridade entre "Allow"/"Disallow", nem wildcards complexos) - so verifica, de forma simples, se
 * o caminho pretendido bate com algum "Disallow:" listado sob "User-agent: *". Se o robots.txt nao
 * puder ser lido (timeout, 404, dominio fora do ar) ou o parsing falhar por qualquer motivo, o
 * resultado e "permitido, mas prossiga com cautela" (fail-open) - decisao deliberada: o documento
 * da fase e explicito que o app NUNCA deve travar por causa disso, e o pior caso de um fail-open
 * aqui e uma requisicao a mais que talvez nao devesse ter sido feita, nunca uma tela travada.
 */
public class RobotsTxtChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public RobotsTxtChecker() {
        this(HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public RobotsTxtChecker(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * @param pageUrl URL completa que se pretende acessar (ex:
     *                "https://www.asus.com/supportonly/.../HelpDesk_BIOS/")
     * @return {@code true} se permitido (ou se nao foi possivel checar - fail-open documentado
     *         acima), {@code false} apenas quando um "Disallow" relevante sob "User-agent: *" bate
     *         claramente com o caminho da URL.
     */
    public boolean isAllowed(String pageUrl) {
        try {
            URI uri = URI.create(pageUrl);
            URI robotsUri = new URI(uri.getScheme(), uri.getAuthority(), "/robots.txt", null, null);

            HttpRequest request = HttpRequest.newBuilder(robotsUri).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return true; // sem robots.txt legivel -> segue com cautela, nunca trava
            }
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            return !hasBlockingDisallow(response.body(), path);
        } catch (Exception e) {
            return true; // qualquer falha de rede/parsing -> fail-open, documentado na classe
        }
    }

    private boolean hasBlockingDisallow(String robotsTxt, String path) {
        boolean underWildcardAgent = false;
        for (String rawLine : robotsTxt.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user-agent:")) {
                String agent = line.substring("user-agent:".length()).trim();
                underWildcardAgent = agent.equals("*");
                continue;
            }
            if (underWildcardAgent && lower.startsWith("disallow:")) {
                String rule = line.substring("disallow:".length()).trim();
                if (rule.isEmpty()) {
                    continue; // "Disallow:" vazio significa "permite tudo"
                }
                if (matchesRule(path, rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Comparacao pragmatica: trata "*" da regra como coringa simples; qualquer erro -> nao bate. */
    private boolean matchesRule(String path, String rule) {
        try {
            String regex = quoteWithWildcard(rule);
            return path.matches(regex);
        } catch (Exception e) {
            return false; // regra malformada -> nao bloqueia, mesma filosofia fail-open da classe
        }
    }

    private String quoteWithWildcard(String rule) {
        String[] parts = rule.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            regex.append(java.util.regex.Pattern.quote(parts[i]));
            if (i < parts.length - 1) {
                regex.append(".*");
            }
        }
        regex.append(".*"); // Disallow e um prefixo por definicao - qualquer sufixo depois conta
        return regex.toString();
    }

    /**
     * Teste isolado via console (requisicao HTTP real):
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.updates.RobotsTxtChecker
     */
    public static void main(String[] args) {
        RobotsTxtChecker checker = new RobotsTxtChecker();
        System.out.println("===== NITRO BOOST - RobotsTxtChecker (teste isolado) =====");

        String realUrl = "https://www.asus.com/supportonly/ROG%20STRIX%20B650-A%20GAMING%20WIFI/HelpDesk_BIOS/";
        System.out.println("ASUS (URL real, esperado permitido): " + checker.isAllowed(realUrl));

        String invalidDomainUrl = "https://dominio-que-nao-existe-nitroboost-teste.invalid/pagina";
        System.out.println("Dominio inexistente (esperado permitido - fail-open): " + checker.isAllowed(invalidDomainUrl));
    }
}
