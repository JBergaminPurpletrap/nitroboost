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
 * telemetria/relatorios/personalizacao do Windows 11 e le o valor atual de
 * cada uma via {@code reg query}.
 *
 * Responsabilidade unica: apenas ESCANEAR/LER valores de telemetria. A acao
 * de alterar um valor (com backup previo) fica em
 * {@link com.nitroboost.actions.ActionExecutor}.
 */
public class TelemetryScanner {

    /**
     * Uma chave de registro conhecida relacionada a telemetria, com metadados
     * suficientes para ler e (depois, via ActionExecutor) alterar o valor com
     * seguranca.
     *
     * @param id            identificador curto e estavel (usado como "nome" do item no catalogo/backup/historico)
     * @param friendlyName  nome amigavel exibido ao usuario
     * @param registryPath  caminho completo da chave (ex: "HKLM\SOFTWARE\Policies\Microsoft\Windows\DataCollection")
     * @param valueName     nome do valor dentro da chave (ex: "AllowTelemetry")
     * @param description   explicacao curta em portugues do que esse valor controla
     */
    public record TelemetryKeyDefinition(String id, String friendlyName, String registryPath,
                                          String valueName, String description) {
    }

    /** Estado atual lido de uma chave conhecida (valor pode ser nulo se a chave/valor nao existir na maquina). */
    public record TelemetryKeyInfo(TelemetryKeyDefinition definition, String currentValue, boolean exists) {
    }

    /**
     * Lista de chaves conhecidas relacionadas a telemetria/relatorios/CEIP no Windows 11,
     * levantadas a partir da documentacao publica da Microsoft sobre configuracao de
     * privacidade e coleta de dados de diagnostico. Todos os valores sao DWORD (0/1, ou
     * 0-3 no caso de AllowTelemetry).
     */
    public static final List<TelemetryKeyDefinition> KNOWN_KEYS = List.of(
            new TelemetryKeyDefinition(
                    "allow_telemetry_policy",
                    "Nivel de Telemetria (Politica de Grupo)",
                    "HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\DataCollection",
                    "AllowTelemetry",
                    "Controla, via politica de grupo, quantos dados de diagnostico o Windows envia para a Microsoft "
                            + "(0 = minimo/seguranca, 1 = basico, 3 = completo). Tem prioridade sobre a configuracao normal."
            ),
            new TelemetryKeyDefinition(
                    "allow_telemetry",
                    "Nivel de Telemetria (Configuracao do Sistema)",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\DataCollection",
                    "AllowTelemetry",
                    "Mesmo controle de telemetria acima, mas na localizacao usada quando nao ha uma politica de grupo "
                            + "aplicada (a maioria dos PCs domesticos)."
            ),
            new TelemetryKeyDefinition(
                    "advertising_id",
                    "ID de Publicidade",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\AdvertisingInfo",
                    "Enabled",
                    "Identificador unico usado pelos aplicativos para mostrar anuncios mais 'personalizados' com base "
                            + "no seu uso do PC."
            ),
            new TelemetryKeyDefinition(
                    "tailored_experiences",
                    "Experiencias Personalizadas com Dados de Diagnostico",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Privacy",
                    "TailoredExperiencesWithDiagnosticDataEnabled",
                    "Permite que a Microsoft personalize dicas, sugestoes e anuncios dentro do Windows com base nos "
                            + "dados de diagnostico enviados pelo seu PC."
            ),
            new TelemetryKeyDefinition(
                    "feedback_frequency",
                    "Frequencia de Pedidos de Feedback",
                    "HKCU\\Software\\Microsoft\\Siuf\\Rules",
                    "NumberOfSIUFInPeriod",
                    "Controla com que frequencia o Windows pede para voce avaliar sua experiencia de uso (programa de "
                            + "feedback). Zerar reduz bastante os popups de pesquisa."
            ),
            new TelemetryKeyDefinition(
                    "error_reporting_disabled",
                    "Relatorio de Erros do Windows",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows\\Windows Error Reporting",
                    "Disabled",
                    "Quando um programa trava, o Windows pode enviar um relatorio automatico do erro para a Microsoft. "
                            + "Valor 1 aqui desativa esse envio."
            ),
            new TelemetryKeyDefinition(
                    "app_launch_tracking",
                    "Rastreamento de Apps Mais Usados",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced",
                    "Start_TrackProgs",
                    "Controla se o Windows rastreia quais programas voce mais abre, para personalizar sugestoes no "
                            + "menu Iniciar e na busca."
            )
    );

    // Exemplo de linha real do "reg query": "    AllowTelemetry    REG_DWORD    0x1"
    private static final Pattern VALUE_LINE_PATTERN = Pattern.compile("REG_[A-Z_]+\\s+(\\S+)");

    /** Le o estado atual de todas as chaves conhecidas listadas em {@link #KNOWN_KEYS}. */
    public List<TelemetryKeyInfo> scan() {
        List<TelemetryKeyInfo> result = new ArrayList<>();
        for (TelemetryKeyDefinition definition : KNOWN_KEYS) {
            result.add(readValue(definition));
        }
        return result;
    }

    /**
     * Le o valor atual de uma unica chave/valor de registro. Nunca lanca
     * excecao para fora: se a chave ou o valor nao existirem (comum - muitas
     * dessas chaves so existem se o usuario ou uma politica ja mexeu nelas
     * antes), retorna {@code exists=false} em vez de erro, pois isso e um
     * estado valido (equivale ao padrao de fabrica do Windows para aquele item).
     */
    public TelemetryKeyInfo readValue(TelemetryKeyDefinition definition) {
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
                return new TelemetryKeyInfo(definition, null, false);
            }
            if (process.exitValue() != 0) {
                // Chave/valor nao existe nesta maquina - estado valido, nao e erro.
                return new TelemetryKeyInfo(definition, null, false);
            }

            for (String line : output.split("\\r?\\n")) {
                if (line.contains(definition.valueName())) {
                    Matcher matcher = VALUE_LINE_PATTERN.matcher(line);
                    if (matcher.find()) {
                        return new TelemetryKeyInfo(definition, matcher.group(1), true);
                    }
                }
            }
            return new TelemetryKeyInfo(definition, null, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao ler chave de telemetria '" + definition.friendlyName() + "': " + e.getMessage());
            return new TelemetryKeyInfo(definition, null, false);
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
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.TelemetryScanner
     */
    public static void main(String[] args) {
        TelemetryScanner scanner = new TelemetryScanner();
        List<TelemetryKeyInfo> keys = scanner.scan();

        System.out.println("===== NITRO BOOST - Chaves de Telemetria Conhecidas =====");
        System.out.println("Total de chaves mapeadas: " + keys.size());
        System.out.println();
        for (TelemetryKeyInfo info : keys) {
            System.out.printf("- %-45s valor atual=%-10s (existe=%s)%n",
                    info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : "(nao definido)",
                    info.exists());
        }
    }
}
