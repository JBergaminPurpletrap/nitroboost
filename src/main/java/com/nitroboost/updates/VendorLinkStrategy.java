package com.nitroboost.updates;

import java.util.List;

/**
 * Estrategia de geracao de link de suporte para um fabricante de placa-mae, conforme a secao 1.2
 * do documento da Fase 11 (3 camadas, da mais especifica para a mais garantida):
 *
 * <ol>
 *   <li><b>Link profundo conhecido</b> - tentativa de URL direta para a pagina/aba de BIOS do
 *       modelo, construida a partir de padroes conhecidos do site do fabricante. E a mais util
 *       quando acerta, mas tambem a mais fragil - o proprio documento da fase avisa que essa
 *       camada "e o que mais quebra com o tempo", e isso e esperado, nao um bug.</li>
 *   <li><b>Busca interna do site do fabricante</b> - URL da busca/pesquisa do proprio site,
 *       historicamente mais estavel que uma pagina de produto especifica.</li>
 *   <li><b>Busca externa (Google, com {@code site:<dominio>})</b> - camada que NUNCA falha
 *       tecnicamente, pois e apenas montagem de string (nenhuma requisicao HTTP e feita pelo
 *       Nivel 1 do NITRO BOOST - a URL so e aberta quando o usuario clica no botao).</li>
 * </ol>
 *
 * Nivel 1 da Fase 11: nenhuma implementacao aqui faz requisicao de rede. Tudo e montagem de URL.
 */
public interface VendorLinkStrategy {

    /** Nome amigavel do fabricante, para exibir na UI (ex: "ASUS"). */
    String vendorName();

    /**
     * Verifica se esta estrategia atende ao texto bruto devolvido por
     * {@code Baseboard.getManufacturer()} (ex: "ASUSTeK COMPUTER INC."). Comparacao por
     * substring, case-insensitive - o mesmo padrao ja usado em {@code BloatwareScanner.classify()},
     * necessario porque o texto exato varia entre placas do mesmo fabricante.
     */
    boolean matches(String manufacturerRaw);

    /** Camada 1: link profundo (melhor esforco, pode nao resolver para a pagina exata). */
    String deepLinkUrl(String model);

    /** Camada 2: busca interna do site do fabricante. */
    String vendorSearchUrl(String model);

    /** Camada 3: busca externa (Google), sempre disponivel mesmo sem fabricante reconhecido. */
    String externalSearchUrl(String model);

    /** As 3 camadas, na ordem, prontas para exibir na UI (ex: "tentar layout profundo primeiro"). */
    default List<String> allLayers(String model) {
        return List.of(deepLinkUrl(model), vendorSearchUrl(model), externalSearchUrl(model));
    }

    /**
     * Resolve a estrategia correta a partir do texto bruto do fabricante (Baseboard.getManufacturer()).
     * Cai no {@link GenericLinkStrategy} (camada 3 apenas, busca externa sem filtro de dominio)
     * quando nenhum dos 4 fabricantes cobertos no lancamento reconhece o texto.
     */
    static VendorLinkStrategy resolve(String manufacturerRaw) {
        List<VendorLinkStrategy> known = List.of(
                new AsusLinkStrategy(),
                new MsiLinkStrategy(),
                new GigabyteLinkStrategy(),
                new AsRockLinkStrategy()
        );
        for (VendorLinkStrategy strategy : known) {
            if (strategy.matches(manufacturerRaw)) {
                return strategy;
            }
        }
        return new GenericLinkStrategy();
    }
}
