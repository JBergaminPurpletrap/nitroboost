package com.nitroboost.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link TaskSchedulerScanner#parseCsvOutput(String)} isoladamente, com strings de
 * exemplo fixas simulando a saida real de {@code schtasks /query /fo CSV} (Fase 12 Parte B) -
 * nenhum comando de verdade e chamado aqui.
 */
class TaskSchedulerScannerParsingTest {

    private final TaskSchedulerScanner scanner = new TaskSchedulerScanner();

    @Test
    void parseCsvOutput_descartaCabecalhoEExtraiTarefas() {
        String output = "\"TaskName\",\"Next Run Time\",\"Status\"\r\n"
                + "\"\\Microsoft\\Windows\\Application Experience\\Microsoft Compatibility Appraiser\",\"N/A\",\"Ready\"\r\n"
                + "\"\\NitroBoostTestTask\",\"01/01/2099 00:00:00\",\"Ready\"\r\n";

        List<TaskSchedulerScanner.TaskInfo> tasks = scanner.parseCsvOutput(output);

        assertEquals(2, tasks.size());
        assertEquals("\\Microsoft\\Windows\\Application Experience\\Microsoft Compatibility Appraiser", tasks.get(0).name());
        assertEquals("N/A", tasks.get(0).nextRunTime());
        assertEquals("Ready", tasks.get(0).status());
    }

    @Test
    void parseCsvOutput_descartaCabecalhoRepetidoEmMultiplasPaginas() {
        // O schtasks repete a linha de cabecalho a cada "pagina" interna quando ha muitas
        // tarefas (274 nesta maquina de desenvolvimento, conforme documentado na Fase 3) -
        // o parser deve descartar TODAS as ocorrencias de cabecalho, nao so a primeira linha.
        String output = "\"TaskName\",\"Next Run Time\",\"Status\"\r\n"
                + "\"\\TarefaA\",\"N/A\",\"Ready\"\r\n"
                + "\"TaskName\",\"Next Run Time\",\"Status\"\r\n"
                + "\"\\TarefaB\",\"N/A\",\"Disabled\"\r\n";

        List<TaskSchedulerScanner.TaskInfo> tasks = scanner.parseCsvOutput(output);

        assertEquals(2, tasks.size());
        assertEquals("\\TarefaA", tasks.get(0).name());
        assertEquals("\\TarefaB", tasks.get(1).name());
    }

    @Test
    void parseCsvOutput_cabecalhoEmPortugues_tambemDescartado() {
        // A linha de cabecalho traduzida ("Nome da Tarefa") tambem nao comeca com "\" -
        // descartada pela mesma regra, sem depender de idioma.
        String output = "\"Nome da Tarefa\",\"Proxima Hora de Execucao\",\"Status\"\r\n"
                + "\"\\TarefaEmPortugues\",\"N/A\",\"Pronto\"\r\n";

        List<TaskSchedulerScanner.TaskInfo> tasks = scanner.parseCsvOutput(output);

        assertEquals(1, tasks.size());
        assertEquals("\\TarefaEmPortugues", tasks.get(0).name());
    }

    @Test
    void parseCsvOutput_vazio_retornaListaVazia() {
        assertTrue(scanner.parseCsvOutput("").isEmpty());
    }
}
