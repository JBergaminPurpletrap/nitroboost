package com.nitroboost.repair;

import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verificacao e reparo de arquivos do sistema via {@code DISM /Online /Cleanup-Image /RestoreHealth}
 * seguido de {@code sfc /scannow} (Fase 15 Parte A) - a ordem importa: o DISM corrige o "armazenamento
 * de componentes" que o SFC usa como referencia para reparar arquivos.
 *
 * <p><b>Excecao documentada a regra de ouro do projeto (mesmo padrao ja estabelecido em
 * {@code core.MemoryCleaner}, Fase 10):</b> assim como a limpeza de RAM, estas sao acoes de
 * diagnostico/reparo pontuais - nao existe "estado anterior" significativo para reverter (nao faz
 * sentido reverter uma correcao de arquivo corrompido). Os metodos desta classe NAO criam backup nem
 * checam bloqueio (nao ha item a bloquear). A execucao ainda e registrada em {@code actions_history}
 * (tipo {@code system_repair}), apenas para fins informativos/de auditoria de uso - nunca havera um
 * backup vinculado a essas entradas, e {@code ActionExecutor.restoreFromHistory} nunca despacha para
 * este tipo.
 *
 * <p>Execucao via {@link ProcessBuilder}, com leitura da saida linha a linha - o chamador (UI) e
 * responsavel por invocar estes metodos em uma thread de fundo, nunca na JavaFX Application Thread,
 * ja que tanto o DISM quanto o SFC podem levar varios minutos para concluir.
 */
public class SystemFileRepairTool {

    /** Etapa do reparo em andamento - usada para rotular o log e a barra de progresso na UI. */
    public enum Stage {
        DISM, SFC
    }

    /** Classificacao melhor-esforco do resultado final do {@code sfc /scannow} (secao A.3 da Fase 15). */
    public enum SfcOutcome {
        NO_PROBLEMS, FIXED, UNABLE_TO_FIX, UNKNOWN
    }

    /** Resultado bruto de uma unica etapa (DISM ou SFC): codigo de saida e log completo capturado. */
    public record StageResult(Stage stage, boolean processStarted, int exitCode, boolean success, String rawLog) {
    }

    /** Resultado do SFC, combinando o {@link StageResult} bruto com a classificacao melhor-esforco. */
    public record SfcRunResult(StageResult stageResult, SfcOutcome outcome) {
    }

    /** Resultado do fluxo completo (DISM seguido de SFC, botao principal "Verificar e Reparar Sistema"). */
    public record RepairResult(StageResult dismResult, SfcRunResult sfcRunResult) {
        public boolean overallSuccess() {
            return dismResult.success() && sfcRunResult.stageResult().success();
        }
    }

    /**
     * Callback de progresso em tempo real - todos os metodos sao chamados na MESMA thread de fundo
     * que executa o processo (nunca a JavaFX Application Thread), entao a UI que implementar esta
     * interface deve sempre repassar para {@code Platform.runLater} antes de tocar em qualquer no
     * de cena, exatamente como as demais views ja fazem para outras chamadas em thread de fundo.
     */
    public interface ProgressListener {
        default void onStageStarted(Stage stage) {
        }

        default void onLogLine(Stage stage, String line) {
        }

        default void onProgress(Stage stage, double progress) {
        }
    }

    // Casa tanto "Verificacao 45% concluida." / "Verification 45% complete." (SFC) quanto
    // "[==========60.0%==========          ]" (DISM) - os dois formatos sempre tem um numero
    // (com ou sem casas decimais, virgula ou ponto) seguido de "%" em algum ponto da linha.
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)\\s*%");

    private static final List<String> UNABLE_TO_FIX_KEYWORDS = List.of(
            "was unable to fix", "nao foi possivel corrigir"
    );
    private static final List<String> NO_PROBLEMS_KEYWORDS = List.of(
            "did not find any integrity violations", "nao encontrou nenhuma violacao de integridade"
    );
    private static final List<String> FIXED_KEYWORDS = List.of(
            "successfully repaired", "reparou", "com exito"
    );

    private final ActionHistoryRepository historyRepository;

    public SystemFileRepairTool(DatabaseManager databaseManager) {
        this.historyRepository = new ActionHistoryRepository(databaseManager);
    }

    /** Botao principal "Verificar e Reparar Sistema": roda DISM e, na sequencia, SFC. */
    public RepairResult runFullRepair(ProgressListener listener) {
        StageResult dismResult = runStage(Stage.DISM, dismCommand(), listener);
        SfcRunResult sfcRunResult = runSfcStage(listener);
        RepairResult result = new RepairResult(dismResult, sfcRunResult);
        recordHistoryQuiet("Reparo Completo (DISM + SFC)",
                result.overallSuccess(), summarize(result));
        return result;
    }

    /** Botao secundario "Rodar so DISM" (avancado). */
    public StageResult runDismOnly(ProgressListener listener) {
        StageResult result = runStage(Stage.DISM, dismCommand(), listener);
        recordHistoryQuiet("DISM (somente)", result.success(),
                result.success() ? "DISM concluido (codigo " + result.exitCode() + ")."
                        : "DISM falhou (codigo " + result.exitCode() + ").");
        return result;
    }

    /** Botao secundario "Rodar so SFC" (avancado). */
    public SfcRunResult runSfcOnly(ProgressListener listener) {
        SfcRunResult result = runSfcStage(listener);
        recordHistoryQuiet("SFC (somente)", result.stageResult().success(), describeOutcome(result.outcome()));
        return result;
    }

    private SfcRunResult runSfcStage(ProgressListener listener) {
        StageResult stageResult = runStage(Stage.SFC, sfcCommand(), listener);
        SfcOutcome outcome = classifySfcResult(stageResult.rawLog());
        return new SfcRunResult(stageResult, outcome);
    }

    private List<String> dismCommand() {
        return List.of("dism", "/Online", "/Cleanup-Image", "/RestoreHealth");
    }

    private List<String> sfcCommand() {
        return List.of("sfc", "/scannow");
    }

    /**
     * Executa um comando externo via {@link ProcessBuilder}, lendo a saida (stdout+stderr
     * combinados) linha a linha e repassando cada linha - e o percentual extraido dela, se houver -
     * ao {@code listener} em tempo real, na mesma thread que chamou este metodo. Nunca lanca
     * excecao para o chamador: qualquer falha ao iniciar/ler o processo vira um {@link StageResult}
     * com {@code success=false}.
     *
     * <p>Pacote-visivel (nao private) de proposito, para o mecanismo de streaming poder ser testado
     * de ponta a ponta com um comando rapido e seguro (ex: uma sequencia de {@code echo} via
     * {@code cmd /c}) no lugar do DISM/SFC reais - ver {@code SystemFileRepairToolStreamingTest}.
     */
    StageResult runStage(Stage stage, List<String> command, ProgressListener listener) {
        if (listener != null) {
            listener.onStageStarted(stage);
        }
        StringBuilder fullLog = new StringBuilder();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    fullLog.append(line).append(System.lineSeparator());
                    if (listener != null) {
                        listener.onLogLine(stage, line);
                        extractPercent(line).ifPresent(percent -> listener.onProgress(stage, percent));
                    }
                }
            }

            int exitCode = process.waitFor();
            return new StageResult(stage, true, exitCode, exitCode == 0, fullLog.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String errorLine = "[NITRO BOOST] Erro ao executar " + stage + ": " + e.getMessage();
            fullLog.append(errorLine);
            if (listener != null) {
                listener.onLogLine(stage, errorLine);
            }
            return new StageResult(stage, false, -1, false, fullLog.toString());
        }
    }

    /**
     * Extrai o primeiro percentual encontrado em uma linha de saida do SFC ou do DISM, como um
     * valor entre 0.0 e 1.0 (pronto para {@code NitroProgressBar.setProgress}). Retorna vazio se a
     * linha nao contiver percentual algum - a maioria das linhas de log nao contem.
     */
    public static Optional<Double> extractPercent(String line) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher matcher = PERCENT_PATTERN.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
            return Optional.of(Math.max(0.0, Math.min(1.0, value / 100.0)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Classificacao melhor-esforco (secao A.3 da Fase 15) do resultado final do {@code sfc /scannow}
     * a partir do log bruto completo, reconhecendo palavras-chave em portugues e ingles (a mensagem
     * exata varia conforme o idioma de instalacao do Windows). O log bruto completo deve SEMPRE ser
     * exibido ao usuario independente desta classificacao funcionar ou nao - ela e apenas um atalho
     * de leitura, nunca a unica fonte de informacao.
     */
    public static SfcOutcome classifySfcResult(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return SfcOutcome.UNKNOWN;
        }
        String normalized = stripAccents(rawLog.toLowerCase(Locale.ROOT));
        if (containsAny(normalized, UNABLE_TO_FIX_KEYWORDS)) {
            return SfcOutcome.UNABLE_TO_FIX;
        }
        if (containsAny(normalized, NO_PROBLEMS_KEYWORDS)) {
            return SfcOutcome.NO_PROBLEMS;
        }
        if (containsAny(normalized, FIXED_KEYWORDS)) {
            return SfcOutcome.FIXED;
        }
        return SfcOutcome.UNKNOWN;
    }

    /** Mensagem pronta em portugues claro para cada {@link SfcOutcome}, usada no historico e na UI. */
    public static String describeOutcome(SfcOutcome outcome) {
        return switch (outcome) {
            case NO_PROBLEMS -> "Nenhum problema encontrado nos arquivos do sistema.";
            case FIXED -> "Problemas encontrados e corrigidos com sucesso.";
            case UNABLE_TO_FIX -> "Problemas encontrados, mas nao foi possivel corrigir tudo. Consulte "
                    + "%windir%\\Logs\\CBS\\CBS.log para detalhes, ou rode o comando novamente apos reiniciar o computador.";
            case UNKNOWN -> "Nao foi possivel identificar automaticamente o resultado - confira o log completo acima.";
        };
    }

    private static boolean containsAny(String normalizedText, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalizedText.contains(stripAccents(keyword))) {
                return true;
            }
        }
        return false;
    }

    /** Remove acentos (NFD + descarta marcas diacriticas) para casar "não" com "nao", "êxito" com "exito" etc. */
    private static String stripAccents(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private String summarize(RepairResult result) {
        return "DISM: " + (result.dismResult().success() ? "concluido" : "falhou (codigo " + result.dismResult().exitCode() + ")")
                + " | SFC: " + describeOutcome(result.sfcRunResult().outcome());
    }

    /**
     * Grava a execucao em {@code actions_history} (tipo {@code system_repair}) direto via
     * {@link ActionHistoryRepository} - sem passar por {@code LockManager}/{@code BackupManager},
     * mesma excecao documentada na classe (ver Javadoc de topo). Uma falha ao gravar o historico e
     * apenas logada no console, nunca sobrescreve o resultado real do reparo ja entregue ao usuario.
     */
    private void recordHistoryQuiet(String actionLabel, boolean success, String detail) {
        try {
            historyRepository.record(null, actionLabel, "system", "system_repair", null, detail, success, success ? null : detail);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao registrar reparo do sistema no historico: " + e.getMessage());
        }
    }
}
