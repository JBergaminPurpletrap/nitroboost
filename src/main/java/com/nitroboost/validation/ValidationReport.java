package com.nitroboost.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Relatorio completo de uma execucao do Autoteste (Fase 16): a lista de {@link ValidationResult}
 * agregados por {@link Fase16ValidationRunner#run}, mais o contexto da execucao (elevado ou nao,
 * se o SFC/DISM completo foi incluido, quando foi gerado).
 *
 * @param results             um item por linha do checklist coberto (ver {@link Fase16ValidationRunner})
 * @param elevated            se esta execucao estava rodando como Administrador
 * @param includedFullSfcDism se a secao 6 (SFC/DISM completo, 10-30 minutos) foi incluida
 * @param generatedAt         data/hora de geracao, ja formatada ({@code yyyy-MM-dd HH:mm:ss})
 */
public record ValidationReport(List<ValidationResult> results, boolean elevated, boolean includedFullSfcDism,
                                String generatedAt) {

    public record Summary(int passed, int failed, int skipped, int visual, int total) {
    }

    /** Contagem de itens por status - usado tanto no resumo do relatorio quanto no resumo mostrado na UI. */
    public Summary summary() {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int visual = 0;
        for (ValidationResult result : results) {
            switch (result.status()) {
                case PASSOU -> passed++;
                case FALHOU -> failed++;
                case PULADO_DELIBERADAMENTE -> skipped++;
                case REQUER_CONFIRMACAO_VISUAL -> visual++;
            }
        }
        return new Summary(passed, failed, skipped, visual, results.size());
    }

    /**
     * Gera o relatorio em Markdown, no mesmo estilo de tabela ja usado em {@code TESTING.md}
     * (colunas com |, cabecalho de resumo antes da tabela detalhada).
     */
    public String toMarkdown() {
        Summary summary = summary();
        StringBuilder markdown = new StringBuilder();

        markdown.append("# NITRO BOOST - Autoteste da Fase 16 (Validacao com Administrador)\n\n");
        markdown.append("Gerado automaticamente em: ").append(generatedAt).append("\n\n");
        markdown.append("Cobre o roteiro descrito em `docs/NITRO-BOOST-fase16-validacao-administrador.md`. ")
                .append("Traga este arquivo de volta para finalizar a validacao (os itens marcados como ")
                .append("REQUER_CONFIRMACAO_VISUAL nao foram e nao podem ser verificados automaticamente).\n\n");

        markdown.append("Rodando como Administrador nesta execucao: ").append(elevated ? "SIM" : "NAO").append("\n\n");
        if (!elevated) {
            markdown.append("> **ATENCAO:** esta execucao NAO estava elevada (Administrador). A maioria das acoes ")
                    .append("de escrita abaixo (servicos, chaves de registro em HKLM, DISM, limpeza de RAM) falhou ")
                    .append("ou foi recusada pelo Windows por causa disso - isso e esperado e NAO e um bug do ")
                    .append("NITRO BOOST. Rode como Administrador e execute o autoteste de novo para uma validacao ")
                    .append("completa.\n\n");
        }
        markdown.append("SFC/DISM completo incluido nesta execucao: ")
                .append(includedFullSfcDism ? "SIM" : "NAO (autoteste rapido)").append("\n\n");

        markdown.append("## Resumo\n\n");
        markdown.append("- \u2705 PASSOU: ").append(summary.passed()).append("\n");
        markdown.append("- \uD83D\uDD34 FALHOU: ").append(summary.failed()).append("\n");
        markdown.append("- \u26AA PULADO_DELIBERADAMENTE: ").append(summary.skipped()).append("\n");
        markdown.append("- \uD83D\uDC41 REQUER_CONFIRMACAO_VISUAL: ").append(summary.visual()).append("\n");
        markdown.append("- Total de itens verificados: ").append(summary.total()).append("\n\n");

        markdown.append("## Detalhamento por secao\n\n");
        markdown.append("| Secao | Item | Resultado | Comando de Verificacao | Detalhes |\n");
        markdown.append("|---|---|---|---|---|\n");
        for (ValidationResult result : results) {
            markdown.append("| ").append(result.section())
                    .append(" | ").append(escapeCell(result.item()))
                    .append(" | ").append(statusIcon(result.status())).append(' ').append(result.status())
                    .append(" | ").append(result.verificationCommand() == null ? "-" : escapeCell(result.verificationCommand()))
                    .append(" | ").append(escapeCell(result.details()))
                    .append(" |\n");
        }

        markdown.append("\n---\n\nGerado automaticamente pelo botao \"Autoteste (Fase 16)\" do NITRO BOOST ")
                .append("(tela Reparo do Sistema).\n");
        return markdown.toString();
    }

    /**
     * Salva o relatorio em Markdown na pasta informada (criando-a se necessario), com um nome de
     * arquivo derivado de {@link #generatedAt} (ex: {@code fase16-autoteste-2026-07-29_18-30-00.md}).
     * A pasta sugerida pela Fase 16 e a mesma onde o banco SQLite ja fica ({@code %USERPROFILE%\.nitroboost}) -
     * o chamador (UI) resolve esse caminho via {@code DatabaseManager.getDatabasePath().getParent()},
     * reaproveitando a mesma logica ja usada para nunca hardcodar caminhos especificos de uma maquina.
     */
    public Path save(Path directory) throws IOException {
        Files.createDirectories(directory);
        String fileTimestamp = generatedAt.replace(":", "-").replace(" ", "_");
        Path file = directory.resolve("fase16-autoteste-" + fileTimestamp + ".md");
        Files.writeString(file, toMarkdown(), StandardCharsets.UTF_8);
        return file;
    }

    private static String statusIcon(ValidationStatus status) {
        return switch (status) {
            case PASSOU -> "\u2705";
            case FALHOU -> "\uD83D\uDD34";
            case PULADO_DELIBERADAMENTE -> "\u26AA";
            case REQUER_CONFIRMACAO_VISUAL -> "\uD83D\uDC41";
        };
    }

    /** Escapa "|" e quebras de linha, que quebrariam o layout da tabela Markdown. */
    private static String escapeCell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ");
    }
}
