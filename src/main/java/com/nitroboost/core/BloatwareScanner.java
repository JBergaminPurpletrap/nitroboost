package com.nitroboost.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Lista os aplicativos UWP/Appx instalados para o usuario atual via
 * PowerShell ({@code Get-AppxPackage}) e classifica os conhecidos como
 * "bloatware" (Widgets, Copilot, Game Bar, apps do Xbox, entre outros).
 *
 * NOTA: {@code Get-AppxPackage} (sem {@code -AllUsers}) lista apenas os
 * pacotes do usuario atual e nao exige privilegio de Administrador - por
 * isso e o modo usado aqui para a varredura (somente leitura). A remocao
 * (fica em {@link com.nitroboost.actions.ActionExecutor}) pode exigir
 * elevacao dependendo do pacote.
 *
 * Responsabilidade unica: apenas ESCANEAR/LER apps UWP. A acao de
 * desinstalar fica em {@link com.nitroboost.actions.ActionExecutor}.
 */
public class BloatwareScanner {

    /**
     * Categoria de bloatware conhecida, conforme pedido na Fase 3 do projeto.
     * As categorias {@code AI_*} foram adicionadas na Fase 8 - Parte 2, para
     * identificar pacotes Appx especificamente ligados a recursos de IA do
     * Windows 11 (complementar aos itens de politica lidos por
     * {@link AiFeatureScanner}, que cobre o mecanismo de registro/politica -
     * aqui e so a deteccao do PACOTE instalado pelo nome).
     */
    public enum Category {
        WIDGETS,
        AI_COPILOT,
        AI_RECALL,
        AI_CLICK_TO_DO,
        AI_COCREATOR,
        GAME_BAR,
        XBOX,
        ONEDRIVE,
        OTHER_KNOWN_BLOAT,
        UNKNOWN
    }

    /**
     * @param name              nome curto do pacote (ex: "Microsoft.XboxGamingOverlay")
     * @param packageFullName   nome completo/versionado do pacote (usado para desinstalar)
     * @param installLocation   pasta onde o pacote esta instalado (usada em uma tentativa de restore)
     * @param category          categoria de bloatware identificada (ou UNKNOWN se nao reconhecido)
     */
    public record AppxInfo(String name, String packageFullName, String installLocation, Category category) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Executa a varredura completa de apps UWP do usuario atual. Nunca lanca
     * excecao para fora - qualquer falha e reportada no console e uma lista
     * vazia e retornada. Segue o mesmo padrao de temp-file + UTF-8 usado em
     * {@link ServiceScanner}, pelo mesmo motivo (evitar corrupcao de
     * acentuacao na saida redirecionada do PowerShell).
     */
    public List<AppxInfo> scan() {
        List<AppxInfo> result = new ArrayList<>();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("nitroboost-appx-", ".json");
            String escapedPath = tempFile.toAbsolutePath().toString().replace("'", "''");
            String script =
                    "Get-AppxPackage | " +
                    "Select-Object Name, PackageFullName, InstallLocation | " +
                    "ConvertTo-Json -Compress | " +
                    "Out-File -FilePath '" + escapedPath + "' -Encoding utf8";

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String consoleOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[NITRO BOOST] Timeout ao consultar apps UWP via PowerShell (45s).");
                return result;
            }
            if (process.exitValue() != 0) {
                System.err.println("[NITRO BOOST] PowerShell retornou codigo " + process.exitValue()
                        + " ao listar apps UWP. Saida: " + consoleOutput);
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
            System.err.println("[NITRO BOOST] Erro ao escanear apps UWP: " + e.getMessage());
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

    /**
     * Sobrecarga de {@link #scan()} que reporta progresso via {@link ScanProgressListener} (Fase 12
     * Parte C) - delega 100% para {@link #scan()} (a chamada ao PowerShell ja aconteceu ali) e
     * depois itera sobre a lista ja parseada reportando o andamento real do processamento, a cada 10
     * itens (categoria com ~134 apps UWP nesta maquina de desenvolvimento) - ver nota tecnica no
     * Javadoc de {@link ScanProgressListener} sobre por que isso nao e progresso simulado.
     * {@code scan()} continua inalterado, usado pelos testes JUnit da Fase 12 Parte B.
     */
    public List<AppxInfo> scan(ScanProgressListener listener) {
        List<AppxInfo> result = scan();
        if (listener != null) {
            int total = result.size();
            for (int i = 0; i < total; i++) {
                if (i % 10 == 0 || i == total - 1) {
                    listener.onProgress("Aplicativos (Bloatware)", i + 1, total, "Processando " + result.get(i).name() + "...");
                }
            }
        }
        return result;
    }

    /** Apenas os apps reconhecidos como bloatware conhecido (ignora UNKNOWN). */
    public List<AppxInfo> knownBloatware() {
        return scan().stream().filter(a -> a.category() != Category.UNKNOWN).toList();
    }

    /**
     * Classifica um pacote pelo nome, de forma defensiva (busca por
     * substring, sem diferenciar maiusculas/minusculas) - nomes de pacote da
     * Microsoft mudam de versao para versao do Windows, entao nao da para
     * depender de um nome exato.
     */
    private Category classify(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.contains("webexperience")) {
            return Category.WIDGETS; // MicrosoftWindows.Client.WebExperience
        }
        // Deteccao de recursos de IA pelo nome do pacote Appx (Fase 8 - Parte 2). Padroes
        // mais especificos (recall/click to do/cocreator) sao checados antes do generico
        // "copilot", ja que a Microsoft pode empacotar esses recursos com nomes derivados
        // do proprio Copilot (ex: "...Copilot.Recall...") em builds futuras.
        if (lower.contains("recall")) {
            return Category.AI_RECALL;
        }
        if (lower.contains("clicktodo") || lower.contains("click2do")) {
            return Category.AI_CLICK_TO_DO;
        }
        if (lower.contains("cocreator") || lower.contains("imagecreator")) {
            return Category.AI_COCREATOR;
        }
        if (lower.contains("copilot")) {
            return Category.AI_COPILOT;
        }
        if (lower.contains("xboxgamingoverlay") || lower.contains("xboxgameoverlay")) {
            return Category.GAME_BAR;
        }
        if (lower.contains("xbox") || lower.contains("gamingapp")) {
            return Category.XBOX;
        }
        if (lower.contains("onedrive")) {
            return Category.ONEDRIVE; // raro como Appx (OneDrive normalmente e um app Win32, ver StartupScanner)
        }
        if (lower.contains("549981c3f5f10") // Cortana
                || lower.contains("solitairecollection")
                || lower.contains("bingnews")
                || lower.contains("bingweather")
                || lower.contains("zunemusic")
                || lower.contains("zunevideo")
                || lower.contains("getstarted")
                || lower.contains("gethelp")
                || lower.contains("officehub")
                || lower.contains("people")
                || lower.contains("windowsfeedbackhub")
                || lower.contains("windowscommunicationsapps") // Mail and Calendar
                || lower.contains("todos")) {
            return Category.OTHER_KNOWN_BLOAT;
        }
        return Category.UNKNOWN;
    }

    private List<AppxInfo> parseJson(String json) {
        List<AppxInfo> apps = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return apps;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    apps.add(nodeToAppxInfo(node));
                }
            } else if (root.isObject()) {
                apps.add(nodeToAppxInfo(root));
            }
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao parsear JSON de apps UWP retornado pelo PowerShell: " + e.getMessage());
        }
        return apps;
    }

    private AppxInfo nodeToAppxInfo(JsonNode node) {
        String name = textOrEmpty(node, "Name");
        String packageFullName = textOrEmpty(node, "PackageFullName");
        String installLocation = textOrEmpty(node, "InstallLocation");
        return new AppxInfo(name, packageFullName, installLocation, classify(name));
    }

    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? "" : value.asText();
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.BloatwareScanner
     */
    public static void main(String[] args) {
        BloatwareScanner scanner = new BloatwareScanner();
        List<AppxInfo> all = scanner.scan();
        List<AppxInfo> known = all.stream().filter(a -> a.category() != Category.UNKNOWN).toList();

        System.out.println("===== NITRO BOOST - Apps UWP (Bloatware) =====");
        System.out.println("Total de apps UWP instalados (usuario atual): " + all.size());
        System.out.println("Apps reconhecidos como bloatware conhecido: " + known.size());
        System.out.println();
        for (AppxInfo app : known) {
            System.out.printf("[%-16s] %s%n", app.category(), app.name());
        }
    }
}
