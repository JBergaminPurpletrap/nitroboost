package com.nitroboost.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le os planos de energia do Windows via {@code powercfg /list} e identifica
 * qual esta ativo no momento.
 *
 * Responsabilidade unica: apenas ESCANEAR/LER planos de energia. A acao de
 * trocar o plano ativo fica em {@link com.nitroboost.actions.ActionExecutor}.
 */
public class PowerPlanScanner {

    /**
     * @param guid   identificador unico do plano (usado para ativa-lo via {@code powercfg /setactive})
     * @param name   nome amigavel do plano (ex: "Balanced"/"Equilibrado", "High performance"/"Alto desempenho")
     * @param active se este e o plano atualmente em uso
     */
    public record PowerPlanInfo(String guid, String name, boolean active) {
    }

    // Exemplo de linha real do "powercfg /list" (o texto antes do GUID muda conforme o
    // idioma do Windows - ex: "Power Scheme GUID:" em ingles, "GUID do Esquema de Energia:"
    // em portugues - por isso o parser busca apenas o padrao do GUID em si, ignorando o
    // texto ao redor, o que funciona em qualquer idioma):
    // GUID do Esquema de Energia: 381b4222-f694-41f0-9685-ff5bb260df2e  (Equilibrado) *
    private static final Pattern PLAN_LINE_PATTERN = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\s*\\(([^)]*)\\)\\s*(\\*)?");

    /**
     * Executa a varredura completa de planos de energia. Nunca lanca excecao
     * para fora - qualquer falha e reportada no console e uma lista vazia e
     * retornada.
     */
    public List<PowerPlanInfo> scan() {
        List<PowerPlanInfo> result = new ArrayList<>();
        try {
            // "chcp 65001" forca UTF-8 nesta sessao do cmd - alguns Windows em portugues
            // usam nomes de plano acentuados (ex: "Economia de energia").
            String command = "chcp 65001>nul && powercfg /list";
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String output = readStream(process.getInputStream());

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[NITRO BOOST] Timeout ao consultar planos de energia via powercfg (15s).");
                return result;
            }
            if (process.exitValue() != 0) {
                System.err.println("[NITRO BOOST] powercfg retornou codigo " + process.exitValue()
                        + " ao listar planos de energia. Saida: " + output);
                return result;
            }

            result.addAll(parseListOutput(output));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao escanear planos de energia: " + e.getMessage());
        }
        return result;
    }

    /**
     * Parseia a saida completa (multi-linha) do {@code powercfg /list}. Extraido de
     * {@link #scan()} com visibilidade de pacote (nao {@code private}) de proposito: permite
     * testar o parsing isoladamente via JUnit (Fase 12 Parte B) com strings de exemplo fixas
     * (inclusive a variante em portugues, "GUID do Esquema de Energia:"), sem chamar o comando
     * de verdade.
     */
    List<PowerPlanInfo> parseListOutput(String output) {
        List<PowerPlanInfo> result = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            Matcher matcher = PLAN_LINE_PATTERN.matcher(line);
            if (matcher.find()) {
                String guid = matcher.group(1);
                String name = matcher.group(2).trim();
                boolean active = matcher.group(3) != null;
                result.add(new PowerPlanInfo(guid, name, active));
            }
        }
        return result;
    }

    public Optional<PowerPlanInfo> findActive() {
        return scan().stream().filter(PowerPlanInfo::active).findFirst();
    }

    public Optional<PowerPlanInfo> findByGuid(String guid) {
        return scan().stream().filter(p -> p.guid().equalsIgnoreCase(guid)).findFirst();
    }

    public Optional<PowerPlanInfo> findByName(String name) {
        return scan().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
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
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.PowerPlanScanner
     */
    public static void main(String[] args) {
        PowerPlanScanner scanner = new PowerPlanScanner();
        List<PowerPlanInfo> plans = scanner.scan();

        System.out.println("===== NITRO BOOST - Planos de Energia do Windows =====");
        System.out.println("Total de planos encontrados: " + plans.size());
        System.out.println();
        for (PowerPlanInfo plan : plans) {
            System.out.printf("%s %-40s %s%n", plan.active() ? "[ATIVO]" : "       ", plan.name(), plan.guid());
        }
    }
}
