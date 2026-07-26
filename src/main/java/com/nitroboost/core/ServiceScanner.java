package com.nitroboost.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Le a lista de servicos do Windows via PowerShell ({@code Get-CimInstance
 * Win32_Service}), conforme recomendado no guia de skills tecnicas do
 * projeto (PowerShell e preferivel a {@code sc query} por devolver dados
 * estruturados, mais faceis/seguros de parsear do que texto tabular).
 *
 * Responsabilidade unica: apenas ESCANEAR/LER servicos. Acoes de
 * parar/desativar ficam em {@link com.nitroboost.actions.ActionExecutor}.
 */
public class ServiceScanner {

    /**
     * @param name        nome tecnico do servico (ex: "wuauserv")
     * @param displayName nome amigavel exibido no painel de servicos do Windows
     * @param state       estado atual (ex: "Running", "Stopped")
     * @param startMode   tipo de inicializacao (ex: "Auto", "Manual", "Disabled")
     */
    public record ServiceInfo(String name, String displayName, String state, String startMode) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Executa a varredura completa de servicos. Nunca lanca excecao para
     * fora - qualquer falha (comando nao encontrado, timeout, JSON invalido)
     * e reportada no console e uma lista vazia e retornada.
     *
     * NOTA TECNICA: o resultado do PowerShell e escrito em um arquivo
     * temporario com {@code Out-File -Encoding utf8} em vez de ser lido
     * diretamente do stdout redirecionado. Isso porque o Windows PowerShell
     * 5.1 nao aplica de forma confiavel {@code [Console]::OutputEncoding}
     * quando a saida esta sendo redirecionada (nao ha console de verdade) -
     * na pratica, isso corrompia nomes de servicos com acentuacao (ex:
     * "Informa��es" em vez de "Informacoes"). Escrever em arquivo com
     * encoding explicito e uma forma robusta de evitar esse problema.
     */
    public List<ServiceInfo> scan() {
        List<ServiceInfo> result = new ArrayList<>();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("nitroboost-services-", ".json");
            String escapedPath = tempFile.toAbsolutePath().toString().replace("'", "''");
            String script =
                    "Get-CimInstance -ClassName Win32_Service | " +
                    "Select-Object Name, DisplayName, State, StartMode | " +
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
                System.err.println("[NITRO BOOST] Timeout ao consultar servicos via PowerShell (30s).");
                return result;
            }
            if (process.exitValue() != 0) {
                System.err.println("[NITRO BOOST] PowerShell retornou codigo " + process.exitValue()
                        + " ao listar servicos. Saida: " + consoleOutput);
                return result;
            }

            String json = Files.readString(tempFile, StandardCharsets.UTF_8);
            // Out-File -Encoding utf8 grava um BOM no inicio do arquivo - removemos antes do parse.
            if (!json.isEmpty() && json.charAt(0) == '﻿') {
                json = json.substring(1);
            }
            result.addAll(parseJson(json));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao escanear servicos do Windows: " + e.getMessage());
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

    public Optional<ServiceInfo> findByName(String serviceName) {
        return scan().stream()
                .filter(s -> s.name().equalsIgnoreCase(serviceName))
                .findFirst();
    }

    public List<ServiceInfo> runningServices() {
        return scan().stream()
                .filter(s -> "Running".equalsIgnoreCase(s.state()))
                .toList();
    }

    /**
     * Parseia a saida JSON do PowerShell de forma defensiva: quando
     * {@code ConvertTo-Json} recebe apenas 1 objeto ele NAO retorna um array
     * (retorna o objeto solto) - um erro classico de quem assume sempre
     * array. Tratamos os dois formatos aqui.
     *
     * Visibilidade de pacote (nao {@code private}) de proposito: permite testar o parsing
     * isoladamente via JUnit (Fase 12 Parte B) com strings de exemplo fixas, sem chamar o
     * PowerShell de verdade.
     */
    List<ServiceInfo> parseJson(String json) {
        List<ServiceInfo> services = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return services;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    services.add(nodeToServiceInfo(node));
                }
            } else if (root.isObject()) {
                services.add(nodeToServiceInfo(root));
            }
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao parsear JSON de servicos retornado pelo PowerShell: " + e.getMessage());
        }
        return services;
    }

    private ServiceInfo nodeToServiceInfo(JsonNode node) {
        return new ServiceInfo(
                textOrEmpty(node, "Name"),
                textOrEmpty(node, "DisplayName"),
                textOrEmpty(node, "State"),
                textOrEmpty(node, "StartMode")
        );
    }

    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? "" : value.asText();
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.ServiceScanner
     */
    public static void main(String[] args) {
        ServiceScanner scanner = new ServiceScanner();
        List<ServiceInfo> services = scanner.scan();
        List<ServiceInfo> running = services.stream().filter(s -> "Running".equalsIgnoreCase(s.state())).toList();

        System.out.println("===== NITRO BOOST - Servicos do Windows =====");
        System.out.println("Total de servicos encontrados: " + services.size());
        System.out.println("Servicos em execucao (\"Running\"): " + running.size());
        System.out.println();
        System.out.printf("%-28s %-12s %-10s %s%n", "NOME", "ESTADO", "INICIO", "NOME EXIBIDO");
        running.stream().limit(20).forEach(s ->
                System.out.printf("%-28s %-12s %-10s %s%n", s.name(), s.state(), s.startMode(), s.displayName()));
    }
}
