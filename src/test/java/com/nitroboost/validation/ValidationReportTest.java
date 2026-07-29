package com.nitroboost.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa a agregacao ({@link ValidationReport#summary()}) e a geracao do relatorio Markdown
 * ({@link ValidationReport#toMarkdown()}/{@link ValidationReport#save(Path)}) construindo {@link
 * ValidationResult} simulados diretamente - sem rodar {@link Fase16ValidationRunner} de verdade
 * contra o Windows real (mesmo padrao ja estabelecido em {@code audit.AuditReportTest}, Fase 12
 * Parte B).
 */
class ValidationReportTest {

    private static final String GENERATED_AT = "2026-07-29 18:30:00";

    private List<ValidationResult> mixedResults() {
        return List.of(
                new ValidationResult(0, "Privilegio de Administrador", ValidationStatus.PASSOU, "Elevado."),
                new ValidationResult(2, "Servicos - round trip", ValidationStatus.FALHOU, "Falhou por falta de admin.",
                        "Get-Service MapsBroker"),
                new ValidationResult(5, "GPU - item de impacto", ValidationStatus.PULADO_DELIBERADAMENTE,
                        "Nunca tocado automaticamente."),
                new ValidationResult(1, "Tema visual", ValidationStatus.REQUER_CONFIRMACAO_VISUAL,
                        "Confirme visualmente."),
                new ValidationResult(8, "Modo de Jogo - round trip", ValidationStatus.PASSOU, "Ativou de verdade.")
        );
    }

    @Test
    void summary_contaCadaStatusCorretamente() {
        ValidationReport report = new ValidationReport(mixedResults(), true, false, GENERATED_AT);

        ValidationReport.Summary summary = report.summary();

        assertEquals(2, summary.passed());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
        assertEquals(1, summary.visual());
        assertEquals(5, summary.total());
    }

    @Test
    void summary_relatorioVazio_naoLancaExcecaoETudoZero() {
        ValidationReport report = new ValidationReport(List.of(), true, false, GENERATED_AT);

        ValidationReport.Summary summary = report.summary();

        assertEquals(0, summary.passed());
        assertEquals(0, summary.failed());
        assertEquals(0, summary.skipped());
        assertEquals(0, summary.visual());
        assertEquals(0, summary.total());
    }

    @Test
    void toMarkdown_elevado_naoContemAvisoDeFaltaDeAdministrador() {
        ValidationReport report = new ValidationReport(mixedResults(), true, false, GENERATED_AT);

        String markdown = report.toMarkdown();

        assertFalse(markdown.contains("NAO estava elevada"));
        assertTrue(markdown.contains("Rodando como Administrador nesta execucao: SIM"));
    }

    @Test
    void toMarkdown_naoElevado_contemAvisoDeFaltaDeAdministrador() {
        ValidationReport report = new ValidationReport(mixedResults(), false, false, GENERATED_AT);

        String markdown = report.toMarkdown();

        assertTrue(markdown.contains("Rodando como Administrador nesta execucao: NAO"));
        assertTrue(markdown.contains("NAO estava elevada"));
    }

    @Test
    void toMarkdown_contemUmaLinhaDeTabelaPorResultadoEOComandoDeVerificacaoQuandoPresente() {
        ValidationReport report = new ValidationReport(mixedResults(), true, false, GENERATED_AT);

        String markdown = report.toMarkdown();

        assertTrue(markdown.contains("Servicos - round trip"));
        assertTrue(markdown.contains("Get-Service MapsBroker"));
        assertTrue(markdown.contains("Modo de Jogo - round trip"));
        // Item sem comando de verificacao (Privilegio de Administrador) deve cair no marcador "-".
        assertTrue(markdown.contains("Privilegio de Administrador"));
    }

    @Test
    void toMarkdown_incluiInformacaoSeSfcDismCompletoFoiIncluido() {
        ValidationReport comSfcDism = new ValidationReport(mixedResults(), true, true, GENERATED_AT);
        ValidationReport semSfcDism = new ValidationReport(mixedResults(), true, false, GENERATED_AT);

        assertTrue(comSfcDism.toMarkdown().contains("SFC/DISM completo incluido nesta execucao: SIM"));
        assertTrue(semSfcDism.toMarkdown().contains("SFC/DISM completo incluido nesta execucao: NAO"));
    }

    @Test
    void save_escreveArquivoComTimestampNoNomeEConteudoMarkdown(@TempDir Path tempDir) throws IOException {
        ValidationReport report = new ValidationReport(mixedResults(), true, false, GENERATED_AT);

        Path savedPath = report.save(tempDir);

        assertTrue(Files.exists(savedPath));
        assertTrue(savedPath.getFileName().toString().startsWith("fase16-autoteste-"));
        assertTrue(savedPath.getFileName().toString().endsWith(".md"));
        String content = Files.readString(savedPath);
        assertTrue(content.contains("Autoteste da Fase 16"));
        assertTrue(content.contains("Servicos - round trip"));
    }

    @Test
    void save_criaDiretorioSeNaoExistir(@TempDir Path tempDir) throws IOException {
        Path nestedDir = tempDir.resolve("nao-existe-ainda").resolve(".nitroboost");
        ValidationReport report = new ValidationReport(mixedResults(), true, false, GENERATED_AT);

        Path savedPath = report.save(nestedDir);

        assertTrue(Files.exists(savedPath));
        assertEquals(nestedDir, savedPath.getParent());
    }
}
