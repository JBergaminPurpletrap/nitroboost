package com.nitroboost.validation;

/**
 * Resultado de um unico item verificado pelo Autoteste (Fase 16), correspondente a um item do
 * checklist de {@code docs/NITRO-BOOST-fase16-validacao-administrador.md}.
 *
 * @param section              numero da secao do documento da Fase 16 (0 a 10) que este item cobre
 * @param item                 descricao curta do item verificado
 * @param status                resultado (ver {@link ValidationStatus})
 * @param details              explicacao em portugues do que foi feito/observado (sempre preenchido)
 * @param verificationCommand  comando nativo usado para confirmar a mudanca real (ex: {@code "Get-Service MapsBroker"}),
 *                             ou {@code null} se nao houve um comando de confirmacao especifico (ex: item so visual)
 */
public record ValidationResult(int section, String item, ValidationStatus status, String details,
                                String verificationCommand) {

    /** Conveniencia para itens sem comando de verificacao nativo associado. */
    public ValidationResult(int section, String item, ValidationStatus status, String details) {
        this(section, item, status, details, null);
    }
}
