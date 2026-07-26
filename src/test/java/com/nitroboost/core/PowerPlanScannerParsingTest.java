package com.nitroboost.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link PowerPlanScanner#parseListOutput(String)} isoladamente, com strings de exemplo
 * fixas simulando a saida real de {@code powercfg /list} em ingles e em portugues (Fase 12
 * Parte B) - nenhum comando de verdade e chamado aqui.
 */
class PowerPlanScannerParsingTest {

    private final PowerPlanScanner scanner = new PowerPlanScanner();

    @Test
    void parseListOutput_emIngles_identificaPlanoAtivo() {
        String output = "Existing Power Schemes (* Active)\n"
                + "-----------------------------------\n"
                + "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced) *\n"
                + "Power Scheme GUID: 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c  (High performance)\n";

        List<PowerPlanScanner.PowerPlanInfo> plans = scanner.parseListOutput(output);

        assertEquals(2, plans.size());
        assertEquals("381b4222-f694-41f0-9685-ff5bb260df2e", plans.get(0).guid());
        assertEquals("Balanced", plans.get(0).name());
        assertTrue(plans.get(0).active());
        assertFalse(plans.get(1).active());
    }

    @Test
    void parseListOutput_emPortugues_mesmoParsingFuncionaSemDependerDoTextoFixo() {
        // Bug real corrigido na Fase 3: o parser antigo buscava o texto fixo "Power Scheme GUID:",
        // que so existe em ingles. A versao atual busca so o padrao do GUID, funcionando em
        // qualquer idioma - este teste e a regressao para esse bug.
        String output = "Combinacoes de energia existentes (* Ativa)\n"
                + "-----------------------------------\n"
                + "GUID do Esquema de Energia: 381b4222-f694-41f0-9685-ff5bb260df2e  (Equilibrado) *\n";

        List<PowerPlanScanner.PowerPlanInfo> plans = scanner.parseListOutput(output);

        assertEquals(1, plans.size());
        assertEquals("Equilibrado", plans.get(0).name());
        assertTrue(plans.get(0).active());
    }

    @Test
    void parseListOutput_semNenhumPlanoAtivo() {
        String output = "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced)\n";

        List<PowerPlanScanner.PowerPlanInfo> plans = scanner.parseListOutput(output);

        assertEquals(1, plans.size());
        assertFalse(plans.get(0).active());
    }

    @Test
    void parseListOutput_saidaSemGuid_retornaListaVazia() {
        assertTrue(scanner.parseListOutput("nada relevante aqui\nnenhum guid nesta linha\n").isEmpty());
    }
}
