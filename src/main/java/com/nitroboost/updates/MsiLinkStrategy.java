package com.nitroboost.updates;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Estrategia de link de suporte para placas MSI ({@code msi.com}). O SMBIOS de placas MSI
 * normalmente devolve "Micro-Star International Co., Ltd." como fabricante, entao o
 * reconhecimento cobre tanto "msi" quanto "micro-star".
 */
public class MsiLinkStrategy implements VendorLinkStrategy {

    @Override
    public String vendorName() {
        return "MSI";
    }

    @Override
    public boolean matches(String manufacturerRaw) {
        String lower = manufacturerRaw == null ? "" : manufacturerRaw.toLowerCase(Locale.ROOT);
        return lower.contains("msi") || lower.contains("micro-star");
    }

    @Override
    public String deepLinkUrl(String model) {
        // As paginas de produto de placa-mae da MSI seguem o padrao /Motherboard/<MODELO-COM-TRACOS>
        // (ex: MAG-B650-TOMAHAWK-WIFI) - melhor esforco de slugificacao do modelo detectado.
        return "https://www.msi.com/Motherboard/" + slugify(model);
    }

    @Override
    public String vendorSearchUrl(String model) {
        return "https://www.msi.com/search?keyword=" + encode(model);
    }

    @Override
    public String externalSearchUrl(String model) {
        return "https://www.google.com/search?q=" + encode("site:msi.com " + model + " BIOS driver");
    }

    private String slugify(String model) {
        if (model == null || model.isBlank()) {
            return "";
        }
        return model.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s/]+", "-");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
