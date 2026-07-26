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
        return "https://www.asus.com/supportonly/" + encode(model) + "/HelpDesk_BIOS/";
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
}
