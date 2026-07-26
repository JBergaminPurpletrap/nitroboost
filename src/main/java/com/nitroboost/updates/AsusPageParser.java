package com.nitroboost.updates;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai a versao mais recente de BIOS listada na pagina de suporte da ASUS (Fase 11 - Nivel 2).
 *
 * Primeiro fabricante implementado (dos 4 cobertos no Nivel 1) - escolhido para validar o conceito
 * porque a maquina de teste real (notebook Dell) nao se encaixa em nenhum dos 4 fabricantes de
 * placa-mae avulsa do documento da fase (ver PROGRESS.md para a decisao completa). ASUS foi
 * escolhido entre os 4 por ser o mais documentado/estavel para testar contra uma pagina real.
 *
 * Estrategia pragmatica, NAO um parser HTML completo (nenhuma biblioteca de parsing HTML foi
 * adicionada como dependencia - YAGNI, um regex simples resolve o caso real observado). A pagina
 * de suporte da ASUS (renderizada via Nuxt/SSR) lista a secao "BIOS" antes das secoes de driver, e
 * o primeiro arquivo listado dentro dela e sempre o mais recente - confirmado inspecionando
 * manualmente uma pagina real durante o desenvolvimento desta parte (ver PROGRESS.md, secao Fase
 * 11 - Nivel 2, para a URL e o trecho de HTML usados como referencia). O parser:
 *   1. localiza o titulo de secao cujo conteudo e exatamente "BIOS" (heading fechado logo em
 *      seguida por "&lt;", para nao confundir com a aba "BIOS &amp; FIRMWARE" que aparece antes na
 *      mesma pagina);
 *   2. dentro de uma janela de caracteres logo depois desse titulo (para nao vazar para a proxima
 *      secao, ex: "Firmware", que tambem lista "Version X" para outro conteudo), procura o primeiro
 *      "Version XXXX";
 *   3. devolve a versao encontrada, ou vazio se a pagina nao tiver essa estrutura.
 *
 * Deliberadamente o regex NAO depende dos sufixos de hash das classes CSS geradas pelo webpack da
 * ASUS (ex: "__3yZVA", "__GE44d") - esses sufixos mudam a cada deploy do site. So depende do texto
 * visivel "BIOS"/"Version", mais estavel. Ainda assim, se a ASUS reestruturar a pagina de forma mais
 * profunda (ex: passar a exigir JavaScript para renderizar o conteudo, ou remover a palavra
 * "Version"), este parser pode parar de encontrar a versao - isso e esperado e aceitavel (secao 4
 * do documento da Fase 11), o chamador sempre trata {@link Optional#empty()} como "cai para o
 * Nivel 1", nunca como erro fatal.
 */
public class AsusPageParser implements VendorPageParser {

    // Titulo de secao "BIOS" fechado por "<" logo em seguida (evita casar "BIOS &amp; FIRMWARE").
    private static final Pattern BIOS_SECTION_TITLE = Pattern.compile(">BIOS<", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("Version\\s+([A-Za-z0-9][A-Za-z0-9.\\-]*)", Pattern.CASE_INSENSITIVE);

    // Janela de busca apos o titulo da secao BIOS - generosa o suficiente para o layout real
    // observado (a primeira versao aparece poucas centenas de caracteres depois), mas limitada
    // para nao "vazar" e pegar a versao de uma secao de driver bem mais abaixo na pagina.
    private static final int SEARCH_WINDOW_CHARS = 20_000;

    @Override
    public String vendorName() {
        return "ASUS";
    }

    @Override
    public Optional<String> extractLatestBiosVersion(String htmlContent) {
        if (htmlContent == null || htmlContent.isBlank()) {
            return Optional.empty();
        }
        try {
            Matcher titleMatcher = BIOS_SECTION_TITLE.matcher(htmlContent);
            if (!titleMatcher.find()) {
                return Optional.empty();
            }
            int windowStart = titleMatcher.end();
            int windowEnd = Math.min(htmlContent.length(), windowStart + SEARCH_WINDOW_CHARS);
            String window = htmlContent.substring(windowStart, windowEnd);

            Matcher versionMatcher = VERSION_PATTERN.matcher(window);
            if (versionMatcher.find()) {
                String version = versionMatcher.group(1).trim();
                if (!version.isEmpty()) {
                    return Optional.of(version);
                }
            }
        } catch (Exception e) {
            // Melhor esforco: qualquer falha de parsing (ex: regex catastrofico improvavel, HTML
            // gigante) nunca deve propagar - so significa "nao consegui extrair desta vez".
            System.err.println("[NITRO BOOST] Falha ao extrair versao de BIOS da pagina ASUS: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Teste isolado via console com um trecho de HTML minimo que reproduz a estrutura real
     * observada (sem depender de rede) - ver {@code Phase11Nivel2ConsoleDemo} para o teste contra a
     * pagina real da ASUS via HTTP:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.updates.AsusPageParser
     */
    public static void main(String[] args) {
        String sampleRealStructure =
                "<h2 class=\"tabTitle__DT1Qo\">BIOS &amp; FIRMWARE</h2>"
                        + "<div class=\"title__3yZVA\">BIOS</div>"
                        + "<div class=\"box\"><div class=\"fileInfo\"><div>Version 3886</div></div></div>"
                        + "<div class=\"box\"><div class=\"fileInfo\"><div>Version 3881</div></div></div>"
                        + "<div class=\"title__3yZVA\">Firmware</div>"
                        + "<div class=\"box\"><div class=\"fileInfo\"><div>Version 1.02</div></div></div>";

        AsusPageParser parser = new AsusPageParser();
        System.out.println("===== NITRO BOOST - AsusPageParser (teste isolado, HTML de exemplo) =====");
        System.out.println("Versao extraida (esperado 3886): " + parser.extractLatestBiosVersion(sampleRealStructure).orElse("(nenhuma)"));
        System.out.println("HTML vazio -> vazio: " + parser.extractLatestBiosVersion("").isEmpty());
        System.out.println("HTML sem secao BIOS -> vazio: " + parser.extractLatestBiosVersion("<div>nada aqui</div>").isEmpty());
    }
}
