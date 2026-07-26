package com.nitroboost.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mapeia um conjunto conhecido de chaves de registro relacionadas a
 * otimizacoes especificas para jogos no Windows 11 (Fullscreen Optimizations,
 * Game DVR, Game Mode, Prioridade de Processador) e le o valor atual de cada
 * uma via {@code reg query} - mesmo padrao defensivo do {@link TelemetryScanner}
 * e do {@link PerformanceScanner}.
 *
 * Responsabilidade unica: apenas ESCANEAR/LER valores de otimizacao para
 * jogos. A acao de alterar um valor (com backup previo) fica em
 * {@link com.nitroboost.actions.ActionExecutor}.
 */
public class GamingScanner {

    /**
     * Uma chave de registro conhecida relacionada a jogos.
     *
     * @param id               identificador curto e estavel (nome do item no catalogo/backup/historico)
     * @param friendlyName     nome amigavel exibido ao usuario (tambem usado como "nome" na base de conhecimento)
     * @param registryPath     caminho completo da chave
     * @param valueName        nome do valor dentro da chave
     * @param description      explicacao curta em portugues do que esse valor controla
     * @param recommendedValue valor sugerido para melhor desempenho em jogos (usado como alvo da acao "aplicar" padrao)
     */
    public record GamingKeyDefinition(String id, String friendlyName, String registryPath,
                                       String valueName, String description, String recommendedValue) {
    }

    /** Estado atual lido de uma chave conhecida (valor pode ser nulo se a chave/valor nao existir na maquina). */
    public record GamingKeyInfo(GamingKeyDefinition definition, String currentValue, boolean exists) {
    }

    public static final List<GamingKeyDefinition> KNOWN_KEYS = List.of(
            new GamingKeyDefinition(
                    "fullscreen_optimizations",
                    "Otimizacoes de Tela Cheia (Fullscreen Optimizations)",
                    "HKCU\\System\\GameConfigStore",
                    "GameDVR_FSEBehaviorMode",
                    "Forca o modo de tela cheia exclusivo em vez do modo 'borderless' do Windows, o que pode "
                            + "reduzir o input lag em alguns jogos.",
                    "2"
            ),
            new GamingKeyDefinition(
                    "game_dvr_enabled",
                    "Game DVR (Gravacao em Segundo Plano)",
                    "HKCU\\System\\GameConfigStore",
                    "GameDVR_Enabled",
                    "Controla a gravacao em segundo plano de clipes do Xbox Game Bar, que consome recursos mesmo "
                            + "sem gravar ativamente.",
                    "0"
            ),
            new GamingKeyDefinition(
                    "game_mode_auto",
                    "Modo de Jogo (Game Mode) do Windows",
                    "HKCU\\Software\\Microsoft\\GameBar",
                    "AllowAutoGameMode",
                    "Prioriza recursos do sistema para o jogo em primeiro plano automaticamente. Geralmente "
                            + "positivo, mas alguns usuarios avancados preferem controlar manualmente.",
                    "1"
            ),
            new GamingKeyDefinition(
                    "cpu_priority_separation",
                    "Prioridade de Processador para Jogos",
                    "HKLM\\SYSTEM\\CurrentControlSet\\Control\\PriorityControl",
                    "Win32PrioritySeparation",
                    "Prioriza CPU para o programa em primeiro plano (jogo) em vez de dividir igualmente com "
                            + "processos em segundo plano.",
                    "38"
            )
    );

    // Exemplo de linha real do "reg query": "    GameDVR_Enabled    REG_DWORD    0x0"
    private static final Pattern VALUE_LINE_PATTERN = Pattern.compile("REG_[A-Z_]+\\s+(\\S+)");

    /** Le o estado atual de todas as chaves conhecidas listadas em {@link #KNOWN_KEYS}. */
    public List<GamingKeyInfo> scan() {
        List<GamingKeyInfo> result = new ArrayList<>();
        for (GamingKeyDefinition definition : KNOWN_KEYS) {
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
    public GamingKeyInfo readValue(GamingKeyDefinition definition) {
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
                return new GamingKeyInfo(definition, null, false);
            }
            if (process.exitValue() != 0) {
                // Chave/valor nao existe nesta maquina - estado valido, nao e erro.
                return new GamingKeyInfo(definition, null, false);
            }

            for (String line : output.split("\\r?\\n")) {
                if (line.contains(definition.valueName())) {
                    Matcher matcher = VALUE_LINE_PATTERN.matcher(line);
                    if (matcher.find()) {
                        return new GamingKeyInfo(definition, matcher.group(1), true);
                    }
                }
            }
            return new GamingKeyInfo(definition, null, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao ler chave de jogos '" + definition.friendlyName() + "': " + e.getMessage());
            return new GamingKeyInfo(definition, null, false);
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
     * Sobrecarga de {@link #scan()} que reporta progresso via {@link ScanProgressListener} (Fase 12
     * Parte C) - categoria com poucos itens (as chaves listadas em {@link #KNOWN_KEYS}), entao
     * reporta progresso apenas no inicio e no fim, conforme a orientacao da Fase 12 Parte C para
     * categorias pequenas. {@code scan()} continua inalterado, usado pelos testes JUnit da Fase 12
     * Parte B.
     */
    public List<GamingKeyInfo> scan(ScanProgressListener listener) {
        List<GamingKeyInfo> result = scan();
        if (listener != null) {
            int total = result.size();
            listener.onProgress("Otimizacoes para Jogos", 0, total, "Verificando otimizacoes para jogos...");
            listener.onProgress("Otimizacoes para Jogos", total, total, "Verificacao concluida.");
        }
        return result;
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.GamingScanner
     */
    public static void main(String[] args) {
        GamingScanner scanner = new GamingScanner();
        List<GamingKeyInfo> keys = scanner.scan();

        System.out.println("===== NITRO BOOST - Chaves de Otimizacao para Jogos Conhecidas =====");
        System.out.println("Total de chaves mapeadas: " + keys.size());
        System.out.println();
        for (GamingKeyInfo info : keys) {
            System.out.printf("- %-55s valor atual=%-12s (existe=%s)%n",
                    info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : "(nao definido)",
                    info.exists());
        }
    }
}
