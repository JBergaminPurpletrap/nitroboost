package com.nitroboost.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Le os programas configurados para iniciar junto com o Windows, a partir de
 * duas fontes:
 * <ol>
 *   <li>Chaves de registro "Run" (usuario atual - HKCU - e todos os usuarios
 *       - HKLM), via {@code reg query};</li>
 *   <li>Pastas de Startup do Menu Iniciar (usuario atual e "todos os
 *       usuarios"), resolvidas dinamicamente por variaveis de ambiente -
 *       NUNCA hardcodadas (regra de ouro do projeto).</li>
 * </ol>
 *
 * Responsabilidade unica: apenas ESCANEAR/LER itens de inicializacao. Acoes
 * de desativar ficam em {@link com.nitroboost.actions.ActionExecutor}.
 */
public class StartupScanner {

    /** Fonte de onde o item de inicializacao foi lido. */
    public enum Source {
        REGISTRY_HKCU_RUN,
        REGISTRY_HKLM_RUN,
        STARTUP_FOLDER_CURRENT_USER,
        STARTUP_FOLDER_ALL_USERS
    }

    /**
     * @param name          nome do valor de registro ou nome do arquivo de atalho
     * @param command       comando/caminho executado na inicializacao
     * @param source        de onde este item veio
     * @param registryKey   caminho completo da chave de registro (nulo se for item de pasta)
     * @param regType       tipo do valor de registro (ex: "REG_SZ") - nulo se for item de pasta;
     *                      guardado para permitir recriar o valor exatamente igual em um restore
     * @param filePath      caminho completo do arquivo (nulo se for item de registro)
     */
    public record StartupItemInfo(String name, String command, Source source,
                                   String registryKey, String regType, Path filePath) {
    }

    private static final String HKCU_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String HKLM_RUN_KEY = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    /**
     * Executa a varredura completa (registro + pastas de startup). Nunca
     * lanca excecao para fora - cada fonte e lida de forma isolada, entao a
     * falha em uma (ex: chave de registro que nao existe) nao impede a
     * leitura das outras.
     */
    public List<StartupItemInfo> scan() {
        List<StartupItemInfo> items = new ArrayList<>();
        items.addAll(queryRegistryRunKey(HKCU_RUN_KEY, Source.REGISTRY_HKCU_RUN));
        items.addAll(queryRegistryRunKey(HKLM_RUN_KEY, Source.REGISTRY_HKLM_RUN));
        items.addAll(scanStartupFolder(resolveCurrentUserStartupFolder(), Source.STARTUP_FOLDER_CURRENT_USER));
        items.addAll(scanStartupFolder(resolveAllUsersStartupFolder(), Source.STARTUP_FOLDER_ALL_USERS));
        return items;
    }

    /**
     * Resolve a pasta de Startup do usuario atual dinamicamente via a
     * variavel de ambiente %APPDATA% (equivalente a abrir "shell:startup") -
     * nunca hardcoda "C:\Users\<nome>\...".
     */
    public Path resolveCurrentUserStartupFolder() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            // Fallback defensivo: ainda evita hardcode de usuario, usa user.home do Java.
            appData = Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString();
        }
        return Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    }

    /**
     * Resolve a pasta de Startup "Todos os Usuarios" dinamicamente via
     * %ProgramData% (equivalente ao antigo "All Users\Startup").
     */
    public Path resolveAllUsersStartupFolder() {
        String programData = System.getenv("ProgramData");
        if (programData == null || programData.isBlank()) {
            programData = "C:\\ProgramData"; // caminho padrao do Windows, nao especifico de usuario
        }
        return Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    }

    private List<StartupItemInfo> scanStartupFolder(Path folder, Source source) {
        List<StartupItemInfo> items = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) {
            return items; // pasta pode nao existir (ex: nenhuma "all users" configurada) - nao e erro
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (fileName.equalsIgnoreCase("desktop.ini") || Files.isDirectory(file)) {
                    continue;
                }
                items.add(new StartupItemInfo(fileName, file.toAbsolutePath().toString(), source, null, null, file));
            }
        } catch (IOException e) {
            System.err.println("[NITRO BOOST] Erro ao ler pasta de startup " + folder + ": " + e.getMessage());
        }
        return items;
    }

    /**
     * Consulta uma chave "Run" do registro via {@code reg query} e parseia a
     * saida de forma defensiva (nunca assume posicao fixa de coluna, apenas
     * separa por 2+ espacos - o formato real usado pelo reg.exe).
     *
     * Se a chave nao existir (comum em maquinas sem nenhum programa
     * registrado ali, ou sem permissao), o comando retorna codigo de saida
     * != 0 - isso e tratado como "sem itens", nao como erro fatal.
     */
    private List<StartupItemInfo> queryRegistryRunKey(String registryKey, Source source) {
        List<StartupItemInfo> items = new ArrayList<>();
        try {
            // "chcp 65001" forca a saida do reg.exe para UTF-8 nesta sessao do cmd,
            // evitando texto corrompido em nomes com acentuacao.
            String command = "chcp 65001>nul && reg query " + registryKey;
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String output = readStream(process.getInputStream());

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[NITRO BOOST] Timeout ao consultar registro " + registryKey);
                return items;
            }
            if (process.exitValue() != 0) {
                // Comum e esperado quando a chave nao existe nesta maquina - nao e um bloqueio.
                return items;
            }

            for (String line : output.split("\\r?\\n")) {
                if (line.isBlank() || !line.startsWith("    ")) {
                    continue; // pula a linha do caminho da chave e linhas em branco
                }
                String[] parts = line.trim().split(" {2,}", 3);
                if (parts.length == 3) {
                    items.add(new StartupItemInfo(parts[0], parts[2], source, registryKey, parts[1], null));
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao consultar registro " + registryKey + ": " + e.getMessage());
        }
        return items;
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
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.StartupScanner
     */
    public static void main(String[] args) {
        StartupScanner scanner = new StartupScanner();
        List<StartupItemInfo> items = scanner.scan();

        System.out.println("===== NITRO BOOST - Itens de inicializacao (Startup) =====");
        System.out.println("Pasta do usuario atual: " + scanner.resolveCurrentUserStartupFolder());
        System.out.println("Pasta de todos os usuarios: " + scanner.resolveAllUsersStartupFolder());
        System.out.println("Total de itens encontrados: " + items.size());
        System.out.println();
        for (StartupItemInfo item : items) {
            System.out.printf("[%s] %-30s -> %s%n", item.source(), item.name(), item.command());
        }
    }
}
