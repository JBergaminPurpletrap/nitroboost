package com.nitroboost.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/**
 * Testa os agregadores de {@link AuditReport} construindo {@link AuditFinding} simulados
 * diretamente (sem rodar o {@link SystemAuditEngine} real contra o registro do Windows) -
 * confirma placar geral e agrupamento por categoria.
 */
class AuditReportTest {

    private AuditFinding finding(String category, AuditFinding.Status status) {
        return new AuditFinding("Item de teste", "telemetry", category, status, "0", "0", null);
    }

    @Test
    void totalOptimized_contaApenasJaOtimizado() {
        AuditReport report = new AuditReport(List.of(
                finding("Telemetria", AuditFinding.Status.JA_OTIMIZADO),
                finding("Telemetria", AuditFinding.Status.SUGESTAO),
                finding("Telemetria", AuditFinding.Status.JA_OTIMIZADO),
                finding("Jogos", AuditFinding.Status.NAO_APLICAVEL)
        ));

        assertEquals(2, report.totalOptimized());
    }

    @Test
    void totalApplicable_excluiNaoAplicavel() {
        AuditReport report = new AuditReport(List.of(
                finding("Telemetria", AuditFinding.Status.JA_OTIMIZADO),
                finding("Telemetria", AuditFinding.Status.SUGESTAO),
                finding("Jogos", AuditFinding.Status.NAO_APLICAVEL)
        ));

        // 2 aplicaveis (otimizado + sugestao), 1 nao aplicavel fora do denominador.
        assertEquals(2, report.totalApplicable());
        assertEquals(3, report.totalFindings());
    }

    @Test
    void findingsByCategory_agrupaEPreservaOrdemDeInsercao() {
        AuditReport report = new AuditReport(List.of(
                finding("Telemetria", AuditFinding.Status.JA_OTIMIZADO),
                finding("Jogos", AuditFinding.Status.SUGESTAO),
                finding("Telemetria", AuditFinding.Status.SUGESTAO),
                finding("IA", AuditFinding.Status.NAO_APLICAVEL)
        ));

        Map<String, List<AuditFinding>> byCategory = report.findingsByCategory();

        assertEquals(3, byCategory.size());
        assertEquals(2, byCategory.get("Telemetria").size());
        assertEquals(1, byCategory.get("Jogos").size());
        assertEquals(1, byCategory.get("IA").size());
        // Ordem de insercao preservada (LinkedHashMap): Telemetria apareceu primeiro, depois Jogos, depois IA.
        assertIterableEquals(List.of("Telemetria", "Jogos", "IA"), byCategory.keySet());
    }

    @Test
    void relatorioVazio_naoLancaExcecaoENaoDivideZero() {
        AuditReport report = new AuditReport(List.of());

        assertEquals(0, report.totalOptimized());
        assertEquals(0, report.totalApplicable());
        assertEquals(0, report.totalFindings());
        assertEquals(0, report.findingsByCategory().size());
    }
}
