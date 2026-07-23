package com.nitroboost.knowledge;

/**
 * Classificacao de risco de um item (processo/servico/startup/etc.)
 * conforme a base de conhecimento do NITRO BOOST.
 */
public enum ItemClassification {

    /** Pode ser desativado sem preocupacao para a maioria dos usuarios. */
    SEGURO("seguro"),

    /** Essencial para o funcionamento do Windows ou do hardware - nao recomendado desativar. */
    ESSENCIAL("essencial"),

    /** Depende do uso que a pessoa faz do PC (ex: jogos, trabalho, perifericos). */
    DEPENDE("depende");

    private final String label;

    ItemClassification(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Converte o texto vindo do JSON (ex: "seguro") para o enum
     * correspondente. Faz uma leitura defensiva: nunca lanca excecao para
     * texto desconhecido, retorna {@code DEPENDE} como valor neutro padrao
     * (evita que um item mal classificado pareca "seguro" por engano).
     */
    public static ItemClassification fromLabel(String rawLabel) {
        if (rawLabel == null) {
            return DEPENDE;
        }
        for (ItemClassification value : values()) {
            if (value.label.equalsIgnoreCase(rawLabel.trim())) {
                return value;
            }
        }
        System.err.println("[NITRO BOOST] Classificacao desconhecida na base de conhecimento: '" + rawLabel + "' - usando 'depende' como padrao.");
        return DEPENDE;
    }

    @Override
    public String toString() {
        return label;
    }
}
