package com.nitroboost.updates;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Estrategia de link de suporte para placas Gigabyte/Aorus ({@code gigabyte.com}). O SMBIOS
 * normalmente devolve "Gigabyte Technology Co., Ltd.".
 */
public class GigabyteLinkStrategy implements VendorLinkStrategy {

    @Override
    public String vendorName() {
        return "Gigabyte";
    }

    @Override
    public boolean matches(String manufacturerRaw) {
        String lower = manufacturerRaw == null ? "" : manufacturerRaw.toLowerCase(Locale.ROOT);
        return lower.contains("gigabyte") || lower.contains("aorus");
    }

    @Override
    public String deepLinkUrl(String model) {
        // Paginas de produto da Gigabyte seguem o padrao /Motherboard/<MODELO-COM-TRACOS>/support
        // com a aba de BIOS acessivel via ancora - melhor esforco, pode faltar o sufixo "-rev-1x"
        // que a Gigabyte as vezes exige (nao ha como descobrir a revisao via OSHI).
        return "https://www.gigabyte.com/Motherboard/" + slugify(model) + "/support#support-dl-bios";
    }

    @Override
    public String vendorSearchUrl(String model) {
        return "https://www.gigabyte.com/Search#gsc.tab=0&gsc.q=" + encode(model);
    }

    @Override
    public String externalSearchUrl(String model) {
        return "https://www.google.com/search?q=" + encode("site:gigabyte.com " + model + " BIOS driver");
    }

    private String slugify(String model) {
        if (model == null || model.isBlank()) {
            return "";
        }
        return model.trim().replaceAll("\\s+", "-");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
