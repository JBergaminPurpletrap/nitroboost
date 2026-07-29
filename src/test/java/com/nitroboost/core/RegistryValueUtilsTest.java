package com.nitroboost.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link RegistryValueUtils#dwordValuesEqual(String, String)} (movido de
 * {@code SystemAuditEngine}, Fase 9 -&gt; Melhoria de Confiabilidade, agora publico e reaproveitado
 * pelo {@code ActionExecutor}) e {@link RegistryValueUtils#parseRegQueryValue(String, String)}
 * (parsing da saida de texto do {@code reg query}, com strings fixas simulando a saida real do
 * comando - nenhum processo real e chamado aqui).
 */
class RegistryValueUtilsTest {

    @Test
    void dwordValuesEqual_hexEDecimal_numericamenteIguais() {
        assertTrue(RegistryValueUtils.dwordValuesEqual("0x26", "38"));
        assertTrue(RegistryValueUtils.dwordValuesEqual("38", "0x26"));
    }

    @Test
    void dwordValuesEqual_valoresDiferentes_naoSaoIguais() {
        assertFalse(RegistryValueUtils.dwordValuesEqual("0x1", "0x2"));
    }

    @Test
    void dwordValuesEqual_naoNumerico_caiParaComparacaoDeTexto() {
        assertTrue(RegistryValueUtils.dwordValuesEqual("nao definido", "nao definido"));
        assertFalse(RegistryValueUtils.dwordValuesEqual("nao definido", "0"));
    }

    @Test
    void parseRegQueryValue_saidaRealDeRegQuery_encontraOValor() {
        // Exemplo real de "reg query <chave> /v AllowTelemetry":
        String output = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Policies\\Microsoft\\Windows\\DataCollection\r\n"
                + "    AllowTelemetry    REG_DWORD    0x1\r\n\r\n";

        Optional<String> value = RegistryValueUtils.parseRegQueryValue(output, "AllowTelemetry");

        assertTrue(value.isPresent());
        assertEquals("0x1", value.get());
    }

    @Test
    void parseRegQueryValue_valorAusenteNaSaida_devolveOptionalVazio() {
        // "reg query" quando a chave existe mas o valor pedido nao existe: saida generica sem a
        // linha REG_DWORD do valor procurado.
        String output = "ERRO: O sistema nao conseguiu encontrar o valor especificado.\r\n";

        Optional<String> value = RegistryValueUtils.parseRegQueryValue(output, "AllowTelemetry");

        assertTrue(value.isEmpty());
    }

    @Test
    void parseRegQueryValue_multiplosValoresNaChave_pegaSoOPedido() {
        String output = "    OutroValor    REG_SZ    algumacoisa\r\n"
                + "    NumberOfSIUFInPeriod    REG_DWORD    0x0\r\n";

        Optional<String> value = RegistryValueUtils.parseRegQueryValue(output, "NumberOfSIUFInPeriod");

        assertTrue(value.isPresent());
        assertEquals("0x0", value.get());
    }

    @Test
    void parseRegQueryValue_saidaNulaOuVazia_devolveOptionalVazioSemLancarExcecao() {
        assertTrue(RegistryValueUtils.parseRegQueryValue(null, "AllowTelemetry").isEmpty());
        assertTrue(RegistryValueUtils.parseRegQueryValue("", "AllowTelemetry").isEmpty());
    }
}
