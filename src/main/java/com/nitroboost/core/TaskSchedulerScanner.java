package com.nitroboost.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Le as tarefas agendadas do Windows (Agendador de Tarefas) via
 * {@code schtasks /query /fo CSV}, que devolve exatamente as 3 colunas que
 * precisamos (nome, proxima execucao, status) - mais simples e seguro de
 * parsear do que a saida tabular padrao ou o modo verboso ({@code /v}, que
 * traz dezenas de colunas desnecessarias).
 *
 * Responsabilidade unica: apenas ESCANEAR/LER tarefas agendadas. A acao de
 * desativar fica em {@link com.nitroboost.actions.ActionExecutor}.
 */
public class TaskSchedulerScanner {

    /**
     * @param name        caminho completo da tarefa no Agendador (ex: "\Microsoft\Windows\...\Tarefa")
     * @param nextRunTime proxima execucao agendada (texto formatado pelo Windows, ou "N/A"/"Desativada" se nao aplicavel)
     * @param status      estado atual (ex: "Ready"/"Pronto", "Disabled"/"Desativado", "Running"/"Em execucao")
     */
    public record TaskInfo(String name, String nextRunTime, String status) {
    }

    /**
     * Executa a varredura completa de tarefas agendadas. Nunca lanca excecao
     * para fora - qualquer falha (comando indisponivel, timeout, saida
     * inesperada) e reportada no console e uma lista vazia e retornada.
     */
    public List<TaskInfo> scan() {
        List<TaskInfo> result = new ArrayList<>();
        try {
            // "chcp 65001" forca UTF-8 nesta sessao do cmd, evitando texto corrompido em
            // nomes de tarefas com acentuacao (mesma tecnica usada no StartupScanner).
            String command = "chcp 65001>nul && schtasks /query /fo CSV";
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String output = readStream(process.getInputStream());

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[NITRO BOOST] Timeout ao consultar tarefas agendadas via schtasks (30s).");
                return result;
            }
            if (process.exitValue() != 0) {
                System.err.println("[NITRO BOOST] schtasks retornou codigo " + process.exitValue()
                        + " ao listar tarefas. Saida: " + output);
                return result;
            }

            for (String line : output.split("\\r?\\n")) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> columns = parseCsvLine(line);
                if (columns.size() < 3) {
                    continue;
                }
                // Nomes de tarefa reais sempre comecam com "\" (caminho da tarefa no
                // Agendador). Isso descarta de forma robusta a linha de cabecalho
                // ("TaskName"/"Nome da tarefa", dependendo do idioma do Windows) - o
                // schtasks tambem repete o cabecalho a cada "pagina" interna quando ha
                // muitas tarefas, entao nao da para assumir que so a primeira linha e cabecalho.
                if (!columns.get(0).startsWith("\\")) {
                    continue;
                }
                result.add(new TaskInfo(columns.get(0), columns.get(1), columns.get(2)));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao escanear tarefas agendadas: " + e.getMessage());
        }
        return result;
    }

    public Optional<TaskInfo> findByName(String taskName) {
        return scan().stream()
                .filter(t -> t.name().equalsIgnoreCase(taskName))
                .findFirst();
    }

    /**
     * Parseia uma linha CSV no formato usado pelo {@code schtasks /fo CSV}
     * (campos sempre entre aspas, separados por virgula). Divide de forma
     * defensiva: nunca assume posicao fixa, apenas separa por virgulas que
     * estao fora de aspas.
     */
    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean insideQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                insideQuotes = !insideQuotes;
            } else if (c == ',' && !insideQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private String readStream(java.io.InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.TaskSchedulerScanner
     */
    public static void main(String[] args) {
        TaskSchedulerScanner scanner = new TaskSchedulerScanner();
        List<TaskInfo> tasks = scanner.scan();

        System.out.println("===== NITRO BOOST - Tarefas Agendadas do Windows =====");
        System.out.println("Total de tarefas encontradas: " + tasks.size());
        System.out.println();
        System.out.printf("%-70s %-22s %s%n", "NOME", "PROXIMA EXECUCAO", "STATUS");
        tasks.stream().limit(25).forEach(t ->
                System.out.printf("%-70s %-22s %s%n", truncate(t.name(), 70), t.nextRunTime(), t.status()));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
