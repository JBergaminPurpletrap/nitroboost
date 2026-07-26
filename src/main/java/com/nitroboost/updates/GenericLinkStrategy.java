package com.nitroboost.updates;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Fallback usado quando o fabricante da placa-mae nao e nenhum dos 4 cobertos no lancamento (ou
 * nao foi detectado - comum em VMs/hardware "white label"). Nao ha site de fabricante conhecido,
 * entao as camadas 1 e 2 nao se aplicam (retornam {@code null}) - so a camada 3 (busca externa
 * generica, sem filtro {@code site:}) esta disponivel, mas essa e a unica que o documento da Fase
 * 11 exige que NUNCA falhe, entao o recurso continua util mesmo neste caso.
 */
public class GenericLinkStrategy implements VendorLinkStrategy {

    @Override
    public String vendorName() {
        return "Fabricante nao reconhecido";
    }

    @Override
    public boolean matches(String manufacturerRaw) {
        // Nunca reconhece nada por conta propria - so e usado como fallback explicito via
        // VendorLinkStrategy.resolve() quando nenhuma das 4 estrategias conhecidas casa.
        return false;
    }

    @Override
    public String deepLinkUrl(String model) {
        return null;
    }

    @Override
    public String vendorSearchUrl(String model) {
        return null;
    }

    @Override
    public String externalSearchUrl(String model) {
        String query = (model == null || model.isBlank())
                ? "placa-mae BIOS driver atualizacao"
                : model + " motherboard BIOS driver download";
        return "https://www.google.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }
}
