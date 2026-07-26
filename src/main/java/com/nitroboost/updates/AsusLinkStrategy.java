package com.nitroboost.updates;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Estrategia de link de suporte para placas ASUS/ROG ({@code asus.com}). Reconhece o texto
 * "ASUSTeK COMPUTER INC." (o valor real devolvido pelo SMBIOS na maioria das placas ASUS), alem de
 * variantes mais curtas ("ASUS", "ROG").
 */
public class AsusLinkStrategy implements VendorLinkStrategy {

    @Override
    public String vendorName() {
        return "ASUS";
    }

    @Override
    public boolean matches(String manufacturerRaw) {
        String lower = manufacturerRaw == null ? "" : manufacturerRaw.toLowerCase(Locale.ROOT);
        return lower.contains("asus") || lower.contains("asustek");
    }

    @Override
    public String deepLinkUrl(String model) {
        // Padrao historico do portal de suporte da ASUS que leva direto a aba de download de
        // BIOS de um modelo (melhor esforco - pode nao resolver se a ASUS tiver reestruturado o
        // site, mesma ressalva documentada na Fase 11 para a camada 1).
        //
        // Minusculas de proposito (model + sufixo do path): o site da ASUS canonicaliza essa URL
        // para minusculas e responde com um redirect 301 para qualquer variacao de maiusculas -
        // encontrado testando o Nivel 2 desta fase contra a URL real. O "Location" desse redirect
        // vem com espacos literais (nao url-encoded), que o Java HttpClient recusa a seguir
        // (URI invalida) - o navegador do usuario tolera isso sem problema (por isso nunca foi
        // notado no Nivel 1), mas montar a URL ja em minusculas evita o redirect por completo,
        // tanto para o navegador (uma requisicao a menos) quanto para o HttpClient do Nivel 2.
        String lowerModel = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return "https://www.asus.com/supportonly/" + encodePathSegment(lowerModel) + "/helpdesk_bios/";
    }

    @Override
    public String vendorSearchUrl(String model) {
        return "https://www.asus.com/support/Search/?keyword=" + encode(model);
    }

    @Override
    public String externalSearchUrl(String model) {
        return "https://www.google.com/search?q=" + encode("site:asus.com " + model + " BIOS driver");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * {@link URLEncoder} codifica espaco como "+", correto para query string mas ERRADO dentro de
     * um segmento de path (servidores nao decodificam "+" como espaco ali, so em query strings) -
     * bug encontrado testando o Nivel 2 desta fase contra a URL real da camada 1 (o servidor
     * devolvia uma pagina generica, sem a secao "BIOS", em vez da pagina do modelo). Corrigido
     * substituindo "+" por "%20" apos a codificacao padrao - unico lugar do projeto que embute o
     * modelo dentro do path da URL (as demais camadas/fabricantes usam query string).
     */
    private String encodePathSegment(String value) {
        return encode(value).replace("+", "%20");
    }
}
