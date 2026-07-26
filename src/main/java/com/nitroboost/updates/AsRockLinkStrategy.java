package com.nitroboost.updates;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Estrategia de link de suporte para placas ASRock ({@code asrock.com}).
 *
 * A ASRock organiza paginas de produto sob uma pasta de marca de CPU (ex: /mb/AMD/<modelo>/ ou
 * /mb/Intel/<modelo>/), que o NITRO BOOST nao tem como determinar com confianca so pelo
 * fabricante da placa-mae - por isso a camada 1 (deep link) aqui e deliberadamente mais fraca que
 * a dos demais fabricantes (aponta para a listagem de placas em vez de tentar acertar a pasta),
 * e a camada 2 (busca do proprio site) e o caminho mais confiavel na pratica para a ASRock.
 */
public class AsRockLinkStrategy implements VendorLinkStrategy {

    @Override
    public String vendorName() {
        return "ASRock";
    }

    @Override
    public boolean matches(String manufacturerRaw) {
        String lower = manufacturerRaw == null ? "" : manufacturerRaw.toLowerCase(Locale.ROOT);
        return lower.contains("asrock");
    }

    @Override
    public String deepLinkUrl(String model) {
        return "https://www.asrock.com/mb/index.asp?Model=" + encode(model);
    }

    @Override
    public String vendorSearchUrl(String model) {
        return "https://www.asrock.com/search/index.asp?keyword=" + encode(model);
    }

    @Override
    public String externalSearchUrl(String model) {
        return "https://www.google.com/search?q=" + encode("site:asrock.com " + model + " BIOS driver");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
