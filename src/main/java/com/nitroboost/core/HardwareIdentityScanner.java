package com.nitroboost.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import oshi.SystemInfo;
import oshi.hardware.ComputerSystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Le a identidade da placa-mae (fabricante/modelo) e a versao/data da BIOS instalada, via OSHI
 * ({@code ComputerSystem.getBaseboard()}/{@code getFirmware()} - API confirmada lendo o jar da
 * versao 7.4.1 do OSHI ja usada pelo projeto, nao supomos os nomes dos metodos).
 *
 * Tambem le uma lista filtrada de drivers relevantes (chipset/rede/audio/video/sistema) via
 * PowerShell ({@code Get-CimInstance Win32_PnPSignedDriver}) - mesmo padrao de temp-file + UTF-8
 * ja usado em {@link ServiceScanner}/{@link BloatwareScanner}/{@link MemorySpeedScanner}.
 *
 * Responsabilidade unica: apenas ESCANEAR/LER. A Fase 11 - Nivel 1 nao tem nenhuma acao associada
 * (nao existe "desativar"/"reverter" identidade de hardware) - por isso, diferente dos demais
 * scanners do projeto, esta classe nunca interage com ActionExecutor/LockManager/BackupManager.
 */
public class HardwareIdentityScanner {

    private static final String UNKNOWN = "";

    /**
     * @param manufacturer     fabricante da placa-mae (ex: "ASUSTeK COMPUTER INC."), vazio se nao detectado
     * @param model            modelo exato (ex: "ROG STRIX B650-A GAMING WIFI"), vazio se nao detectado
     * @param biosVersion      versao atual da BIOS instalada, vazio se nao detectada
     * @param biosReleaseDate  data de release da BIOS atual (formato bruto devolvido pelo SMBIOS via OSHI), vazio se nao detectada
     */
    public record BoardIdentity(String manufacturer, String model, String biosVersion, String biosReleaseDate) {

        public boolean manufacturerDetected() {
            return manufacturer != null && !manufacturer.isBlank();
        }

        public boolean modelDetected() {
            return model != null && !model.isBlank();
        }
    }

    /**
     * @param deviceName    nome do dispositivo (ex: "Realtek PCIe GbE Family Controller")
     * @param deviceClass   categoria WMI do driver (ex: "NET", "MEDIA", "DISPLAY", "SYSTEM")
     * @param manufacturer  fabricante do driver
     * @param version       versao do driver instalado
     * @param driverDate    data do driver (formato bruto WMI)
     */
    public record DriverInfo(String deviceName, String deviceClass, String manufacturer, String version, String driverDate) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Le fabricante/modelo/versao e data da BIOS via OSHI. Nunca lanca excecao para fora - comum
     * em VMs e hardware generico o SMBIOS vir vazio/com valores placeholder (ex: "To be filled by
     * O.E.M."); esses casos sao tratados como "nao detectado", nunca como erro.
     */
    public BoardIdentity scanBoardIdentity() {
        try {
            ComputerSystem computerSystem = new SystemInfo().getHardware().getComputerSystem();
            String manufacturer = sanitize(computerSystem.getBaseboard().getManufacturer());
            String model = sanitize(computerSystem.getBaseboard().getModel());
            String biosVersion = sanitize(computerSystem.getFirmware().getVersion());
            String biosReleaseDate = sanitize(computerSystem.getFirmware().getReleaseDate());
            return new BoardIdentity(manufacturer, model, biosVersion, biosReleaseDate);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao ler identidade da placa-mae via OSHI: " + e.getMessage());
            return new BoardIdentity(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        }
    }

    /**
     * Normaliza valores "placeholder" que o SMBIOS costuma devolver em VMs/hardware generico em
     * vez de vazio (ex: "To Be Filled By O.E.M.", "Default string") - tratados como nao detectado.
     */
    private String sanitize(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()
                || lower.contains("to be filled by")
                || lower.equals("default string")
                || lower.equals("system manufacturer")
                || lower.equals("system product name")
                || lower.equals("unknown")) {
            return UNKNOWN;
        }
        return trimmed;
    }

    /**
     * Le os drivers instalados relevantes (rede/audio-media/video/chipset-sistema) via PowerShell,
     * filtrando por {@code DeviceClass} diretamente na consulta (evita trazer centenas de drivers
     * irrelevantes - impressoras, HID, USB genericos etc). Nunca lanca excecao para fora - qualquer
     * falha (comando indisponivel, timeout) devolve lista vazia.
     */
    public List<DriverInfo> scanRelevantDrivers() {
        List<DriverInfo> result = new ArrayList<>();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("nitroboost-drivers-", ".json");
            String escapedPath = tempFile.toAbsolutePath().toString().replace("'", "''");
            String script =
                    "Get-CimInstance -ClassName Win32_PnPSignedDriver | " +
                    "Where-Object { $_.DeviceClass -in @('NET','MEDIA','DISPLAY','SYSTEM') -and $_.DeviceName } | " +
                    "Select-Object DeviceName, DeviceClass, Manufacturer, DriverVersion, DriverDate | " +
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
                System.err.println("[NITRO BOOST] Timeout ao consultar drivers via PowerShell (45s).");
                return result;
            }
            if (process.exitValue() != 0) {
                System.err.println("[NITRO BOOST] PowerShell retornou codigo " + process.exitValue()
                        + " ao listar drivers. Saida: " + consoleOutput);
                return result;
            }

            String json = Files.readString(tempFile, StandardCharsets.UTF_8);
            if (!json.isEmpty() && json.charAt(0) == '﻿') {
                json = json.substring(1);
            }
            result.addAll(parseDriverJson(json));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao escanear drivers instalados: " + e.getMessage());
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

    private List<DriverInfo> parseDriverJson(String json) {
        List<DriverInfo> drivers = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return drivers;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    drivers.add(nodeToDriverInfo(node));
                }
            } else if (root.isObject()) {
                drivers.add(nodeToDriverInfo(root));
            }
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao parsear JSON de drivers retornado pelo PowerShell: " + e.getMessage());
        }
        return drivers;
    }

    private DriverInfo nodeToDriverInfo(JsonNode node) {
        return new DriverInfo(
                textOrEmpty(node, "DeviceName"),
                textOrEmpty(node, "DeviceClass"),
                textOrEmpty(node, "Manufacturer"),
                textOrEmpty(node, "DriverVersion"),
                textOrEmpty(node, "DriverDate")
        );
    }

    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? "" : value.asText();
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.HardwareIdentityScanner
     */
    public static void main(String[] args) {
        HardwareIdentityScanner scanner = new HardwareIdentityScanner();
        BoardIdentity identity = scanner.scanBoardIdentity();

        System.out.println("===== NITRO BOOST - Identidade da Placa-Mae =====");
        System.out.println("Fabricante: " + (identity.manufacturerDetected() ? identity.manufacturer() : "(nao detectado)"));
        System.out.println("Modelo: " + (identity.modelDetected() ? identity.model() : "(nao detectado)"));
        System.out.println("Versao da BIOS: " + (identity.biosVersion().isBlank() ? "(nao detectada)" : identity.biosVersion()));
        System.out.println("Data de release da BIOS: " + (identity.biosReleaseDate().isBlank() ? "(nao detectada)" : identity.biosReleaseDate()));

        System.out.println();
        List<DriverInfo> drivers = scanner.scanRelevantDrivers();
        System.out.println("Drivers relevantes encontrados (rede/audio-video/sistema): " + drivers.size());
        System.out.println();
        System.out.printf("%-8s %-45s %-16s %s%n", "CLASSE", "DISPOSITIVO", "VERSAO", "FABRICANTE");
        for (DriverInfo d : drivers) {
            System.out.printf("%-8s %-45s %-16s %s%n",
                    d.deviceClass(), d.deviceName(), d.version().isBlank() ? "?" : d.version(), d.manufacturer());
        }
    }
}
