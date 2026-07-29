package com.nitroboost.repair;

import com.nitroboost.repair.SystemFileRepairTool.SfcOutcome;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link SystemFileRepairTool#extractPercent(String)} e
 * {@link SystemFileRepairTool#classifySfcResult(String)} isoladamente, com strings de exemplo
 * fixas simulando a saida REAL do {@code sfc /scannow} e do
 * {@code DISM /Online /Cleanup-Image /RestoreHealth} (secoes A.2/A.3 da Fase 15) - mesmo padrao de
 * "strings fixas, sem chamar comando de verdade" ja usado em
 * {@code ServiceScannerParsingTest}/{@code TaskSchedulerScannerParsingTest}/{@code PowerPlanScannerParsingTest}
 * (Fase 12 Parte B). NENHUM {@code ProcessBuilder} e usado aqui - o DISM/SFC reais nunca sao
 * executados por este teste (ver {@code SystemFileRepairToolStreamingTest} para a validacao do
 * mecanismo de streaming, tambem sem rodar o DISM/SFC de verdade).
 */
class SystemFileRepairToolParsingTest {

    // ------------------------------------------------------------------
    // extractPercent
    // ------------------------------------------------------------------

    @Test
    void extractPercent_sfcPortugues() {
        Optional<Double> percent = SystemFileRepairTool.extractPercent("Verificação 45% concluída.");
        assertTrue(percent.isPresent());
        assertEquals(0.45, percent.get(), 0.0001);
    }

    @Test
    void extractPercent_sfcIngles() {
        Optional<Double> percent = SystemFileRepairTool.extractPercent("Verification 78% complete.");
        assertTrue(percent.isPresent());
        assertEquals(0.78, percent.get(), 0.0001);
    }

    @Test
    void extractPercent_dismBarraDeTexto() {
        Optional<Double> percent = SystemFileRepairTool.extractPercent("[==========60.0%==========          ]");
        assertTrue(percent.isPresent());
        assertEquals(0.60, percent.get(), 0.0001);
    }

    @Test
    void extractPercent_dismComVirgulaDecimal() {
        // Alguns locais do Windows usam virgula como separador decimal em vez de ponto.
        Optional<Double> percent = SystemFileRepairTool.extractPercent("[====================100,0%====================]");
        assertTrue(percent.isPresent());
        assertEquals(1.0, percent.get(), 0.0001);
    }

    @Test
    void extractPercent_linhaSemPercentual_retornaVazio() {
        assertTrue(SystemFileRepairTool.extractPercent("Iniciando Verificacao do sistema...").isEmpty());
    }

    @Test
    void extractPercent_nulo_retornaVazioSemLancarExcecao() {
        assertTrue(SystemFileRepairTool.extractPercent(null).isEmpty());
    }

    @Test
    void extractPercent_valorClampadoEntre0e1() {
        // Nao deveria acontecer na saida real, mas a extracao nao pode devolver algo fora de [0,1].
        Optional<Double> percent = SystemFileRepairTool.extractPercent("progresso 150% (linha invalida hipotetica)");
        assertTrue(percent.isPresent());
        assertEquals(1.0, percent.get(), 0.0001);
    }

    // ------------------------------------------------------------------
    // classifySfcResult - os 3 estados da secao A.3, em portugues e ingles
    // ------------------------------------------------------------------

    @Test
    void classify_nenhumProblemaEncontrado_portugues() {
        String log = "Iniciando a verificacao do sistema. Isso pode levar algum tempo.\n"
                + "Verificação 100% concluída.\n"
                + "A Protecao de Recursos do Windows não encontrou nenhuma violação de integridade.";
        assertEquals(SfcOutcome.NO_PROBLEMS, SystemFileRepairTool.classifySfcResult(log));
    }

    @Test
    void classify_nenhumProblemaEncontrado_ingles() {
        String log = "Beginning system scan. This process will take some time.\n"
                + "Verification 100% complete.\n"
                + "Windows Resource Protection did not find any integrity violations.";
        assertEquals(SfcOutcome.NO_PROBLEMS, SystemFileRepairTool.classifySfcResult(log));
    }

    @Test
    void classify_corrigidoComSucesso_portugues() {
        String log = "Verificação 100% concluída.\n"
                + "O Windows Resource Protection encontrou arquivos corrompidos e os reparou com êxito.";
        assertEquals(SfcOutcome.FIXED, SystemFileRepairTool.classifySfcResult(log));
    }

    @Test
    void classify_corrigidoComSucesso_ingles() {
        String log = "Verification 100% complete.\n"
                + "Windows Resource Protection found corrupt files and successfully repaired them.";
        assertEquals(SfcOutcome.FIXED, SystemFileRepairTool.classifySfcResult(log));
    }

    @Test
    void classify_naoFoiPossivelCorrigirTudo_portugues() {
        String log = "Verificação 100% concluída.\n"
                + "O Windows Resource Protection encontrou arquivos corrompidos, mas não foi possível corrigir "
                + "alguns deles. Os detalhes estao incluidos no arquivo CBS.Log.";
        assertEquals(SfcOutcome.UNABLE_TO_FIX, SystemFileRepairTool.classifySfcResult(log));
    }

    @Test
    void classify_naoFoiPossivelCorrigirTudo_ingles() {
        String log = "Verification 100% complete.\n"
                + "Windows Resource Protection found corrupt files but was unable to fix some of them. "
                + "Details are included in the CBS.Log.";
        assertEquals(SfcOutcome.UNABLE_TO_FIX, SystemFileRepairTool.classifySfcResult(log));
    }

    @Test
    void classify_mensagemDesconhecida_retornaUnknownSemLancarExcecao() {
        assertEquals(SfcOutcome.UNKNOWN, SystemFileRepairTool.classifySfcResult("saida totalmente inesperada, sem nenhuma palavra-chave conhecida"));
    }

    @Test
    void classify_logVazioOuNulo_retornaUnknown() {
        assertEquals(SfcOutcome.UNKNOWN, SystemFileRepairTool.classifySfcResult(""));
        assertEquals(SfcOutcome.UNKNOWN, SystemFileRepairTool.classifySfcResult(null));
    }

    @Test
    void describeOutcome_todosOsEstadosTemMensagemNaoVazia() {
        for (SfcOutcome outcome : SfcOutcome.values()) {
            String description = SystemFileRepairTool.describeOutcome(outcome);
            assertFalse(description == null || description.isBlank(), "descricao vazia para " + outcome);
        }
    }
}
