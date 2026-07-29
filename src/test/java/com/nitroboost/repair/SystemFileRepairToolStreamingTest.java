package com.nitroboost.repair;

import com.nitroboost.db.DatabaseManager;
import com.nitroboost.repair.SystemFileRepairTool.SfcOutcome;
import com.nitroboost.repair.SystemFileRepairTool.Stage;
import com.nitroboost.repair.SystemFileRepairTool.StageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>IMPORTANTE (regra de seguranca da Fase 15):</b> este teste NUNCA roda o DISM ou o SFC de
 * verdade - {@code sfc /scannow} sozinho pode levar 10-20 minutos e {@code DISM /RestoreHealth}
 * exige internet, o que travaria/tornaria nao-determinista uma sessao de desenvolvimento
 * automatizada sem supervisao.
 *
 * <p>Em vez disso, valida o MECANISMO de {@link SystemFileRepairTool#runStage} de ponta a ponta -
 * rodar um processo externo real via {@link ProcessBuilder}, ler a saida linha a linha em tempo
 * real e repassar cada linha (e o percentual extraido dela) ao {@link
 * SystemFileRepairTool.ProgressListener} - usando um comando {@code cmd /c echo ...} rapido e
 * seguro (poucos segundos) no lugar do SFC/DISM real, com linhas que imitam o formato de saida
 * deles (percentuais crescentes + a mensagem final "sem violacao de integridade" em portugues).
 * Isso comprova que o pipeline processo->linha->callback->regex de percentual funciona; NAO
 * comprova nada sobre o comportamento real do SFC/DISM (isso fica para o teste manual do usuario -
 * ver comando documentado em PROGRESS.md).
 */
class SystemFileRepairToolStreamingTest {

    @Test
    @Timeout(30)
    void runStage_comandoRapidoSimulado_streamaLinhasEPercentualEmTempoReal(@TempDir Path tempDir) {
        SystemFileRepairTool tool = new SystemFileRepairTool(new DatabaseManager(tempDir.resolve("test.db")));

        // Comando rapido e seguro (nao e o SFC/DISM real) que imita o FORMATO da saida real:
        // linhas de percentual crescente + a mensagem final de "nenhum problema encontrado" em
        // portugues (secao A.3 da Fase 15).
        List<String> fakeCommand = List.of("cmd", "/c",
                "echo Iniciando verificacao simulada & "
                        + "echo Verificacao 10% concluida & "
                        + "echo Verificacao 50% concluida & "
                        + "echo Verificacao 100% concluida & "
                        + "echo A Protecao de Recursos do Windows nao encontrou nenhuma violacao de integridade.");

        AtomicInteger stageStartedCount = new AtomicInteger(0);
        List<String> loggedLines = new CopyOnWriteArrayList<>();
        List<Double> progressValues = new ArrayList<>();

        SystemFileRepairTool.ProgressListener listener = new SystemFileRepairTool.ProgressListener() {
            @Override
            public void onStageStarted(Stage stage) {
                stageStartedCount.incrementAndGet();
            }

            @Override
            public void onLogLine(Stage stage, String line) {
                loggedLines.add(line);
            }

            @Override
            public void onProgress(Stage stage, double progress) {
                progressValues.add(progress);
            }
        };

        StageResult result = tool.runStage(Stage.SFC, fakeCommand, listener);

        // Mecanismo de execucao/streaming funcionou de ponta a ponta.
        assertTrue(result.processStarted(), "o processo simulado deveria ter iniciado normalmente");
        assertEquals(0, result.exitCode(), "comando 'echo' simulado deveria sempre sair com codigo 0");
        assertTrue(result.success());
        assertEquals(1, stageStartedCount.get(), "onStageStarted deveria ter sido chamado exatamente 1 vez");

        // As linhas chegaram ao listener, na ordem esperada (streaming linha a linha real).
        assertTrue(loggedLines.stream().anyMatch(l -> l.contains("Iniciando verificacao simulada")));
        assertTrue(loggedLines.stream().anyMatch(l -> l.contains("10%")));
        assertTrue(loggedLines.stream().anyMatch(l -> l.contains("50%")));
        assertTrue(loggedLines.stream().anyMatch(l -> l.contains("100%")));

        // O percentual foi extraido via regex e repassado ao listener em tempo real (3 linhas com "%").
        assertEquals(3, progressValues.size(), "deveria ter recebido 3 atualizacoes de progresso (10%/50%/100%)");
        assertEquals(0.10, progressValues.get(0), 0.0001);
        assertEquals(0.50, progressValues.get(1), 0.0001);
        assertEquals(1.00, progressValues.get(2), 0.0001);

        // O log bruto completo foi acumulado corretamente e a classificacao melhor-esforco (mesma
        // funcao usada pelo fluxo real do SFC) reconhece a mensagem final simulada.
        assertTrue(result.rawLog().contains("nenhuma violacao de integridade"));
        assertEquals(SfcOutcome.NO_PROBLEMS, SystemFileRepairTool.classifySfcResult(result.rawLog()));
    }

    @Test
    @Timeout(30)
    void runStage_comandoInexistente_retornaFalhaSemLancarExcecao(@TempDir Path tempDir) {
        SystemFileRepairTool tool = new SystemFileRepairTool(new DatabaseManager(tempDir.resolve("test.db")));

        StageResult result = tool.runStage(Stage.DISM, List.of("nitroboost-comando-que-nao-existe-de-verdade.exe"), null);

        assertTrue(!result.processStarted() || !result.success());
        assertEquals(false, result.success());
    }
}
