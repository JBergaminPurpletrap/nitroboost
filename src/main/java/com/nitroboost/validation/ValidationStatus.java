package com.nitroboost.validation;

/**
 * Resultado possivel de um item verificado pelo Autoteste (Fase 16) - ver {@link Fase16ValidationRunner}.
 */
public enum ValidationStatus {
    /** O item foi verificado de verdade nesta execucao e o comportamento esperado foi confirmado. */
    PASSOU,
    /** O item foi verificado de verdade nesta execucao e o resultado NAO bateu com o esperado. */
    FALHOU,
    /**
     * O item nunca e executado automaticamente por decisao deliberada de seguranca (ex: acoes
     * irreversiveis, que exigem reinicio, ou que dependem de item nao encontrado nesta maquina) -
     * o motivo especifico fica sempre no campo {@code details} de {@link ValidationResult}.
     */
    PULADO_DELIBERADAMENTE,
    /**
     * O item so pode ser confirmado por um humano olhando a tela (tema visual, animacao suave,
     * comparacao com uma tela nativa do Windows) - nunca e fingido como verificado automaticamente.
     */
    REQUER_CONFIRMACAO_VISUAL
}
