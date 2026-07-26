package com.nitroboost.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link SystemAuditEngine#dwordValuesEqual(String, String)} (visibilidade de pacote,
 * ja existente desde a Fase 9) isoladamente - comparacao numerica de valores DWORD de registro
 * (hex vs decimal), com casos de formato inesperado que nao devem lancar excecao.
 */
class SystemAuditEngineTest {

    @Test
    void hexEDecimal_numericamenteIguais_saoConsideradosIguais() {
        // "0x26" (hex) == "38" (decimal) == 38 - mesmo exemplo citado no Javadoc do metodo
        // (Win32PrioritySeparation, valor documentado na Fase 9).
        assertTrue(SystemAuditEngine.dwordValuesEqual("0x26", "38"));
        assertTrue(SystemAuditEngine.dwordValuesEqual("38", "0x26"));
    }

    @Test
    void mesmoFormato_decimalIgualDecimal() {
        assertTrue(SystemAuditEngine.dwordValuesEqual("0", "0"));
        assertTrue(SystemAuditEngine.dwordValuesEqual("1", "1"));
    }

    @Test
    void valoresDiferentes_naoSaoIguais() {
        assertFalse(SystemAuditEngine.dwordValuesEqual("1", "0"));
        assertFalse(SystemAuditEngine.dwordValuesEqual("0x1", "0x2"));
    }

    @Test
    void stringVazia_naoLancaExcecao_caiParaComparacaoDeTexto() {
        assertFalse(SystemAuditEngine.dwordValuesEqual("", "0"));
        assertTrue(SystemAuditEngine.dwordValuesEqual("", ""));
    }

    @Test
    void formatoNaoNumerico_naoLancaExcecao_caiParaComparacaoDeTexto() {
        assertTrue(SystemAuditEngine.dwordValuesEqual("abc", "ABC"));
        assertFalse(SystemAuditEngine.dwordValuesEqual("abc", "0"));
    }
}
