package com.nitroboost.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link ServiceScanner#parseJson(String)} isoladamente, com strings de exemplo fixas
 * simulando a saida real do {@code Get-CimInstance Win32_Service | ConvertTo-Json -Compress}
 * (Fase 12 Parte B) - nenhum PowerShell/ProcessBuilder e chamado aqui.
 */
class ServiceScannerParsingTest {

    private final ServiceScanner scanner = new ServiceScanner();

    @Test
    void parseJson_arrayComMultiplosServicos() {
        String json = "[{\"Name\":\"Spooler\",\"DisplayName\":\"Print Spooler\",\"State\":\"Running\",\"StartMode\":\"Auto\"},"
                + "{\"Name\":\"Fax\",\"DisplayName\":\"Fax\",\"State\":\"Stopped\",\"StartMode\":\"Manual\"}]";

        List<ServiceScanner.ServiceInfo> services = scanner.parseJson(json);

        assertEquals(2, services.size());
        assertEquals("Spooler", services.get(0).name());
        assertEquals("Running", services.get(0).state());
        assertEquals("Auto", services.get(0).startMode());
        assertEquals("Fax", services.get(1).name());
        assertEquals("Stopped", services.get(1).state());
    }

    @Test
    void parseJson_objetoSolto_quandoSoHaUmServico() {
        // ConvertTo-Json NAO retorna array quando ha apenas 1 objeto - caso especial
        // documentado no Javadoc de ServiceScanner.parseJson, testado aqui explicitamente.
        String json = "{\"Name\":\"Spooler\",\"DisplayName\":\"Print Spooler\",\"State\":\"Running\",\"StartMode\":\"Auto\"}";

        List<ServiceScanner.ServiceInfo> services = scanner.parseJson(json);

        assertEquals(1, services.size());
        assertEquals("Spooler", services.get(0).name());
    }

    @Test
    void parseJson_vazioOuNulo_retornaListaVaziaSemLancarExcecao() {
        assertTrue(scanner.parseJson("").isEmpty());
        assertTrue(scanner.parseJson(null).isEmpty());
    }

    @Test
    void parseJson_jsonInvalido_retornaListaVaziaSemLancarExcecao() {
        assertTrue(scanner.parseJson("isso nao e json valido {{{").isEmpty());
    }
}
