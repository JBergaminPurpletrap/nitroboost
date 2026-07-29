package com.nitroboost.repair;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link NetworkRepairTool#requiresElevation(String)} com strings fixas, sem executar
 * nenhum processo real - mesmo padrao de {@code SystemFileRepairTool.classifySfcResult} (Fase 15
 * Parte A).
 *
 * <p>Motivacao: rodando {@code clearArpCache()} DE VERDADE nesta maquina sem elevacao (Fase 15
 * Parte 3), descobrimos que {@code arp -d *} sai com codigo 0 mesmo quando falha por falta de
 * permissao - a falha so aparece na saida de texto ("A operacao solicitada requer elevacao."). Este
 * teste trava esse comportamento para nao regredir.
 */
class NetworkRepairToolElevationTest {

    @Test
    void reconheceMensagemDeElevacaoEmPortugues() {
        assertTrue(NetworkRepairTool.requiresElevation(
                "Falha na exclusao da entrada ARP: A operacao solicitada requer elevacao."));
    }

    @Test
    void reconheceMensagemDeElevacaoEmIngles() {
        assertTrue(NetworkRepairTool.requiresElevation("The requested operation requires elevation."));
    }

    @Test
    void reconheceAcessoNegado() {
        assertTrue(NetworkRepairTool.requiresElevation("Acesso negado."));
        assertTrue(NetworkRepairTool.requiresElevation("Access is denied."));
    }

    @Test
    void naoReconheceSaidaDeSucesso() {
        assertFalse(NetworkRepairTool.requiresElevation("Liberacao do Cache do DNS Resolver bem-sucedida."));
    }

    @Test
    void nuloOuVazioNaoExigeElevacao() {
        assertFalse(NetworkRepairTool.requiresElevation(null));
        assertFalse(NetworkRepairTool.requiresElevation(""));
        assertFalse(NetworkRepairTool.requiresElevation("   "));
    }
}
