package com.nitroboost.audit;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link SystemAuditEngine#dwordValuesEqual(String, String)} e {@link
 * SystemAuditEngine#resolveDisplayStatus(Optional, Optional)} (ambos com visibilidade de pacote)
 * isoladamente - comparacao numerica de valores DWORD de registro (hex vs decimal) e a comparacao
 * dinamica (atual vs maxima) da taxa de atualizacao da tela (Fase 14 Parte 2) - com valores fixos,
 * sem depender de hardware real nem chamar o {@code DisplayScanner}.
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

    @Test
    void taxaAtual_menorQueMaxima_geraSugestao() {
        assertEquals(AuditFinding.Status.SUGESTAO,
                SystemAuditEngine.resolveDisplayStatus(Optional.of(60), Optional.of(144)));
    }

    @Test
    void taxaAtual_igualMaxima_jaOtimizado() {
        assertEquals(AuditFinding.Status.JA_OTIMIZADO,
                SystemAuditEngine.resolveDisplayStatus(Optional.of(60), Optional.of(60)));
    }

    @Test
    void taxaAtual_maiorQueMaxima_jaOtimizado() {
        // Nao deveria acontecer na pratica (a maxima e sempre >= a atual), mas o metodo nao deve
        // gerar uma "sugestao" enganosa (baixar a taxa) nesse caso hipotetico - so SUGESTAO quando
        // atual < maxima, estritamente.
        assertEquals(AuditFinding.Status.JA_OTIMIZADO,
                SystemAuditEngine.resolveDisplayStatus(Optional.of(144), Optional.of(60)));
    }

    @Test
    void taxaAtualAusente_naoAplicavel() {
        assertEquals(AuditFinding.Status.NAO_APLICAVEL,
                SystemAuditEngine.resolveDisplayStatus(Optional.empty(), Optional.of(144)));
    }

    @Test
    void taxaMaximaAusente_naoAplicavel() {
        assertEquals(AuditFinding.Status.NAO_APLICAVEL,
                SystemAuditEngine.resolveDisplayStatus(Optional.of(60), Optional.empty()));
    }

    @Test
    void ambasAusentes_naoAplicavel() {
        assertEquals(AuditFinding.Status.NAO_APLICAVEL,
                SystemAuditEngine.resolveDisplayStatus(Optional.empty(), Optional.empty()));
    }
}
