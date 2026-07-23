package com.nitroboost.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Le, via PowerShell ({@code Get-CimInstance Win32_PhysicalMemory}), a
 * velocidade nominal/rotulada de cada modulo de RAM instalado ({@code Speed})
 * e a velocidade que esta realmente configurada/ativa no momento
 * ({@code ConfiguredClockSpeed}).
 *
 * A comparacao entre essas duas velocidades e a base para {@link
 * com.nitroboost.knowledge.XmpAdvisor} decidir se ha indicio de XMP/EXPO
 * desativado - se a velocidade configurada for visivelmente menor que a
 * nominal, o modulo provavelmente esta rodando na velocidade padrao JEDEC
 * (mais baixa) em vez do perfil XMP/EXPO gravado nele.
 *
 * Responsabilidade unica: apenas ESCANEAR/LER as velocidades. A
 * interpretacao (e a decisao de alertar o usuario) fica em {@code
 * XmpAdvisor}, seguindo o mesmo padrao de separacao usado nos demais
 * scanners de {@code core/}.
 */
public class MemorySpeedScanner {

    /**
     * @param manufacturer          fabricante do modulo (pode vir vazio, alguns fabricantes nao preenchem via SMBIOS)
     * @param capacityBytes         capacidade do modulo em bytes
     * @param ratedSpeedMhz         velocidade nominal/rotulada do modulo (SMBIOS "Speed"), em MHz
     * @param configuredClockMhz    velocidade configurada/ativa no momento (SMBIOS "ConfiguredClockSpeed"), em MHz
     */
    public record MemoryModuleSpeed(String manufacturer, long capacityBytes, int ratedSpeedMhz, int configuredClockMhz) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Executa a varredura de todos os modulos de RAM fisicos instalados.
     * Nunca lanca excecao para fora - qualquer falha (comando indisponivel,
     * timeout, permissao) e reportada no console e uma lista vazia e
     * retornada, para que a ausencia dessa informacao nunca quebre o
     * restante da aplicacao (regra de ouro do projeto).
     */
    public List<MemoryModuleSpeed> scan() {
        List<MemoryModuleSpeed> result = new ArrayList<>();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("nitroboost-memspeed-", ".json");
            String escapedPath = tempFile.toAbsolutePath().toString().replace("'", "''");
            String script =
                    "Get-CimInstance Win32_PhysicalMemory | " +
                    "Select-Object Manufacturer, Capacity, Speed, ConfiguredClockSpeed | " +
                    "ConvertTo-Json -Compress | " +
                    "Out-File -FilePath '" + escapedPath + "' -Encoding utf8";

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String consoleOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[NITRO BOOST] Timeout ao consultar velocidade da RAM via PowerShell (30s).");
                return result;
            }
            if (process.exitValue() != 0) {
                System.err.println("[NITRO BOOST] PowerShell retornou codigo " + process.exitValue()
                        + " ao consultar velocidade da RAM. Saida: " + consoleOutput);
                return result;
            }

            String json = Files.readString(tempFile, StandardCharsets.UTF_8);
            if (!json.isEmpty() && json.charAt(0) == '﻿') {
                json = json.substring(1);
            }
            result.addAll(parseJson(json));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao escanear velocidade da RAM: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    System.err.println("[NITRO BOOST] Falha ao remover arquivo temporario " + tempFile + ": " + e.getMessage());
                }
            }
        }
        return result;
    }

    private List<MemoryModuleSpeed> parseJson(String json) {
        List<MemoryModuleSpeed> modules = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return modules;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    modules.add(nodeToModuleSpeed(node));
                }
            } else if (root.isObject()) {
                modules.add(nodeToModuleSpeed(root));
            }
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao parsear JSON de velocidade da RAM retornado pelo PowerShell: " + e.getMessage());
        }
        return modules;
    }

    private MemoryModuleSpeed nodeToModuleSpeed(JsonNode node) {
        String manufacturer = textOrEmpty(node, "Manufacturer");
        long capacity = longOrZero(node, "Capacity");
        int ratedSpeed = intOrZero(node, "Speed");
        int configuredClock = intOrZero(node, "ConfiguredClockSpeed");
        return new MemoryModuleSpeed(manufacturer, capacity, ratedSpeed, configuredClock);
    }

    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? "" : value.asText();
    }

    private long longOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? 0L : value.asLong();
    }

    private int intOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? 0 : value.asInt();
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.MemorySpeedScanner
     */
    public static void main(String[] args) {
        MemorySpeedScanner scanner = new MemorySpeedScanner();
        List<MemoryModuleSpeed> modules = scanner.scan();

        System.out.println("===== NITRO BOOST - Velocidade dos Modulos de RAM =====");
        System.out.println("Modulos detectados: " + modules.size());
        System.out.println();
        for (MemoryModuleSpeed module : modules) {
            System.out.printf("Fabricante=%-12s Capacidade=%.0fGB RatedSpeed=%dMHz ConfiguredClockSpeed=%dMHz%n",
                    module.manufacturer().isBlank() ? "(desconhecido)" : module.manufacturer(),
                    module.capacityBytes() / (1024.0 * 1024.0 * 1024.0),
                    module.ratedSpeedMhz(),
                    module.configuredClockMhz());
        }
    }
}
