package com.nitroboost.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mapeia um conjunto conhecido de chaves de registro relacionadas a
 * performance/energia do Windows 11 (efeitos visuais, transparencia, GPU
 * Hardware-Accelerated Scheduling, Fast Startup, Network Throttling Index,
 * Delivery Optimization) e le o valor atual de cada uma via {@code reg
 * query} - mesmo padrao defensivo do {@link TelemetryScanner}. Le tambem,
 * separadamente, o estado do arquivo de hibernacao (mecanismo diferente,
 * nao e uma chave de registro).
 *
 * Responsabilidade unica: apenas ESCANEAR/LER valores de performance/energia.
 * A acao de alterar um valor (com backup previo) fica em
 * {@link com.nitroboost.actions.ActionExecutor}.
 */
public class PerformanceScanner {

    /**
     * Uma chave de registro conhecida relacionada a performance/energia.
     *
     * @param id               identificador curto e estavel (nome do item no catalogo/backup/historico)
     * @param friendlyName     nome amigavel exibido ao usuario (tambem usado como "nome" na base de conhecimento)
     * @param registryPath     caminho completo da chave
     * @param valueName        nome do valor dentro da chave
     * @param description      explicacao curta em portugues do que esse valor controla
     * @param recommendedValue valor sugerido para melhor desempenho (usado como alvo da acao "aplicar" padrao)
     */
    public record PerformanceKeyDefinition(String id, String friendlyName, String registryPath,
                                            String valueName, String description, String recommendedValue) {
    }

    /** Estado atual lido de uma chave conhecida (valor pode ser nulo se a chave/valor nao existir na maquina). */
    public record PerformanceKeyInfo(PerformanceKeyDefinition definition, String currentValue, boolean exists) {
    }

    /**
     * Estado do arquivo de hibernacao ({@code hiberfil.sys}), verificado por
     * existencia do arquivo (mais simples e independente de idioma do que
     * fazer parsing da saida localizada de {@code powercfg /a}).
     *
     * @param fileExists   se o arquivo de hibernacao existe no disco (hibernacao habilitada)
     * @param filePath     caminho verificado (resolvido dinamicamente pela unidade do sistema, nunca hardcoded)
     * @param checkFailed  se a verificacao nao pode ser concluida (ex: sem permissao para checar o atributo)
     */
    public record HibernationStatus(boolean fileExists, String filePath, boolean checkFailed) {
    }

    /**
     * Estado do Armazenamento Reservado (Reserved Storage), lido via o cmdlet
     * do PowerShell {@code Get-WindowsReservedStorageState} - nao e uma chave
     * de registro simples, e um cmdlet nativo do Windows (parte do sistema,
     * nao requer importar modulo algum).
     *
     * @param state        "Enabled"/"Disabled" (texto bruto devolvido pelo cmdlet), ou {@code null} se nao foi possivel ler
     * @param supported    se o cmdlet existe/e reconhecido nesta versao/edicao do Windows - quando {@code false},
     *                     o recurso deve ser tratado como "nao aplicavel", nunca como erro
     * @param checkFailed  se houve uma falha inesperada ao tentar ler (diferente de "cmdlet nao reconhecido")
     */
    public record ReservedStorageStatus(String state, boolean supported, boolean checkFailed) {
    }

    public static final List<PerformanceKeyDefinition> KNOWN_KEYS = List.of(
            new PerformanceKeyDefinition(
                    "visual_fx_setting",
                    "Efeitos Visuais (Animacoes e Sombras)",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\VisualEffects",
                    "VisualFXSetting",
                    "Controla as animacoes, sombras e efeitos de transicao do Windows. Ajustar para 'melhor "
                            + "desempenho' remove a maioria dos efeitos visuais, liberando um pouco de CPU/GPU.",
                    "2"
            ),
            new PerformanceKeyDefinition(
                    "enable_transparency",
                    "Transparencia da Interface (Acrilico/Fluent)",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "EnableTransparency",
                    "Controla o efeito de transparencia (acrilico) de janelas, menu Iniciar e barra de tarefas. "
                            + "Desativar reduz um pouco o uso de GPU para renderizar a interface.",
                    "0"
            ),
            new PerformanceKeyDefinition(
                    "gpu_hw_scheduling",
                    "GPU Hardware-Accelerated Scheduling",
                    "HKLM\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers",
                    "HwSchMode",
                    "Deixa a propria GPU gerenciar sua fila de memoria de video, reduzindo latencia (ganho real em "
                            + "jogos). Requer GPU/driver compatível e reinicio do Windows para ter efeito.",
                    "2"
            ),
            new PerformanceKeyDefinition(
                    "fast_startup",
                    "Inicializacao Rapida (Fast Startup)",
                    "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Power",
                    "HiberbootEnabled",
                    "Em desktops (nao notebooks), pode causar inconsistencias com dual-boot e drivers. Desativar "
                            + "deixa o boot mais 'limpo', com um leve aumento no tempo de inicializacao.",
                    "0"
            ),
            new PerformanceKeyDefinition(
                    "network_throttling_index",
                    "Indice de Limitacao de Rede (Network Throttling)",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Multimedia\\SystemProfile",
                    "NetworkThrottlingIndex",
                    "Configuracao antiga que limitava a rede para priorizar audio/multimidia. Desativar o limite "
                            + "pode ajudar em jogos online sensiveis a latencia.",
                    "0xffffffff"
            ),
            new PerformanceKeyDefinition(
                    "delivery_optimization_mode",
                    "Otimizacao de Entrega do Windows Update (Delivery Optimization)",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows\\DeliveryOptimization\\Config",
                    "DODownloadMode",
                    "Por padrao pode compartilhar atualizacoes do Windows com outros PCs pela internet (P2P), "
                            + "consumindo banda de upload. Restringir a 'so PCs da minha rede local' (ou desativar) "
                            + "economiza banda.",
                    "1"
            ),
            // ---- Fase 12 Parte A: bloquear atualizacao de driver de video via Windows Update ----
            new PerformanceKeyDefinition(
                    "block_driver_update_search",
                    "Bloquear Atualizacao de Driver de Video pelo Windows Update (Busca)",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\DriverSearching",
                    "DontSearchWindowsUpdate",
                    "Impede que o Windows Update procure sozinho por drivers (inclusive de video/GPU) e instale "
                            + "uma versao diferente da que voce escolheu manualmente - reclamacao comum de gamers.",
                    "1"
            ),
            new PerformanceKeyDefinition(
                    "block_driver_update_prompt",
                    "Bloquear Atualizacao de Driver de Video pelo Windows Update (Aviso)",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\DriverSearching",
                    "DontPromptForWindowsUpdate",
                    "Complementa a chave de busca acima: impede que o Windows Update nem chegue a avisar/perguntar "
                            + "sobre uma atualizacao de driver disponivel (inclusive de video/GPU).",
                    "1"
            )
    );

    // Exemplo de linha real do "reg query": "    VisualFXSetting    REG_DWORD    0x2"
    private static final Pattern VALUE_LINE_PATTERN = Pattern.compile("REG_[A-Z_]+\\s+(\\S+)");

    /** Le o estado atual de todas as chaves conhecidas listadas em {@link #KNOWN_KEYS}. */
    public List<PerformanceKeyInfo> scan() {
        List<PerformanceKeyInfo> result = new ArrayList<>();
        for (PerformanceKeyDefinition definition : KNOWN_KEYS) {
            result.add(readValue(definition));
        }
        return result;
    }

    /**
     * Le o valor atual de uma unica chave/valor de registro. Nunca lanca
     * excecao para fora: se a chave ou o valor nao existirem, retorna
     * {@code exists=false} (estado valido - equivale ao padrao de fabrica do
     * Windows para aquele item), nao e um erro.
     */
    public PerformanceKeyInfo readValue(PerformanceKeyDefinition definition) {
        try {
            String command = "reg query \"" + definition.registryPath() + "\" /v \"" + definition.valueName() + "\"";
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String output = readStream(process.getInputStream());

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[NITRO BOOST] Timeout ao consultar registro " + definition.registryPath());
                return new PerformanceKeyInfo(definition, null, false);
            }
            if (process.exitValue() != 0) {
                // Chave/valor nao existe nesta maquina - estado valido, nao e erro.
                return new PerformanceKeyInfo(definition, null, false);
            }

            for (String line : output.split("\\r?\\n")) {
                if (line.contains(definition.valueName())) {
                    Matcher matcher = VALUE_LINE_PATTERN.matcher(line);
                    if (matcher.find()) {
                        return new PerformanceKeyInfo(definition, matcher.group(1), true);
                    }
                }
            }
            return new PerformanceKeyInfo(definition, null, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao ler chave de performance '" + definition.friendlyName() + "': " + e.getMessage());
            return new PerformanceKeyInfo(definition, null, false);
        }
    }

    /**
     * Verifica se o arquivo de hibernacao existe no disco, resolvendo a
     * unidade do sistema dinamicamente via a variavel de ambiente {@code
     * SystemDrive} (nunca hardcoded "C:") - forma simples e independente de
     * idioma de checar o estado da hibernacao, alternativa ao parsing da
     * saida localizada de {@code powercfg /a}.
     */
    public HibernationStatus checkHibernationFile() {
        String systemDrive = System.getenv("SystemDrive");
        if (systemDrive == null || systemDrive.isBlank()) {
            systemDrive = "C:";
        }
        String filePath = systemDrive + "\\hiberfil.sys";
        try {
            boolean exists = Files.exists(Path.of(filePath));
            return new HibernationStatus(exists, filePath, false);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao verificar arquivo de hibernacao '" + filePath + "': " + e.getMessage());
            return new HibernationStatus(false, filePath, true);
        }
    }

    /**
     * Le o estado atual do Armazenamento Reservado via {@code Get-WindowsReservedStorageState}.
     * Nunca lanca excecao para fora: se o cmdlet nao for reconhecido (nao suportado nesta
     * versao/edicao do Windows), retorna {@code supported=false} - um estado valido de "nao
     * aplicavel", nao um erro. Qualquer outra falha inesperada retorna {@code checkFailed=true}.
     */
    public ReservedStorageStatus checkReservedStorageState() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Get-WindowsReservedStorageState");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String output = readStream(process.getInputStream());

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[NITRO BOOST] Timeout ao consultar o estado do Armazenamento Reservado.");
                return new ReservedStorageStatus(null, false, true);
            }

            String trimmed = output.trim();
            if (process.exitValue() != 0) {
                String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("not recognized") || lower.contains("nao e reconhecido")
                        || lower.contains("nao é reconhecido") || lower.contains("commandnotfound")) {
                    // Cmdlet inexistente nesta versao/edicao do Windows - "nao aplicavel", nao e erro.
                    return new ReservedStorageStatus(null, false, false);
                }
                // O cmdlet EXISTE (ex: falhou por falta de elevacao, "COMException" pedindo
                // administrador) - diferente de "nao reconhecido", entao supported=true aqui;
                // so a LEITURA falhou desta vez (checkFailed=true), nao a disponibilidade do recurso.
                System.err.println("[NITRO BOOST] Falha ao consultar o Armazenamento Reservado (cmdlet existe, leitura falhou): " + trimmed);
                return new ReservedStorageStatus(null, true, true);
            }

            String firstLine = trimmed.isEmpty() ? null : trimmed.split("\\r?\\n")[0].trim();
            return new ReservedStorageStatus(firstLine, true, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao verificar o Armazenamento Reservado: " + e.getMessage());
            return new ReservedStorageStatus(null, false, true);
        }
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
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.PerformanceScanner
     */
    public static void main(String[] args) {
        PerformanceScanner scanner = new PerformanceScanner();
        List<PerformanceKeyInfo> keys = scanner.scan();

        System.out.println("===== NITRO BOOST - Chaves de Performance/Energia Conhecidas =====");
        System.out.println("Total de chaves mapeadas: " + keys.size());
        System.out.println();
        for (PerformanceKeyInfo info : keys) {
            System.out.printf("- %-55s valor atual=%-12s (existe=%s)%n",
                    info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : "(nao definido)",
                    info.exists());
        }

        HibernationStatus hibernation = scanner.checkHibernationFile();
        System.out.println();
        System.out.println("Arquivo de hibernacao (" + hibernation.filePath() + "): "
                + (hibernation.checkFailed() ? "nao foi possivel verificar" : (hibernation.fileExists() ? "existe (habilitada)" : "nao existe (desabilitada)")));

        ReservedStorageStatus reservedStorage = scanner.checkReservedStorageState();
        System.out.println();
        if (!reservedStorage.supported()) {
            System.out.println("Armazenamento Reservado: cmdlet nao suportado/reconhecido nesta versao do Windows (nao aplicavel).");
        } else if (reservedStorage.checkFailed()) {
            System.out.println("Armazenamento Reservado: nao foi possivel verificar.");
        } else {
            System.out.println("Armazenamento Reservado: " + reservedStorage.state());
        }
    }
}
