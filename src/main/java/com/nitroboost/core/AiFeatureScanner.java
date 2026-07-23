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
 * Mapeia um conjunto conhecido de chaves de registro/politicas relacionadas
 * aos recursos de Inteligencia Artificial do Windows 11 (Copilot, Recall,
 * Click to Do, Cocreator, Copilot no Edge) e le o valor atual de cada uma via
 * {@code reg query} - mesmo padrao defensivo do {@link TelemetryScanner}/
 * {@link PerformanceScanner}/{@link GamingScanner}.
 *
 * NOTA DE TRANSPARENCIA (ver secao 1 de {@code NITRO-BOOST-fase8-debloat-completo.md}):
 * a Microsoft nao disponibiliza uma politica isolada so para o Cocreator - ele
 * compartilha a mesma chave {@code DisableAIDataAnalysis} usada pelo Recall.
 * Por isso {@link #KNOWN_KEYS} tem DUAS entradas apontando para a mesma
 * chave/valor (uma para "Recall", outra para "Cocreator"): alterar uma altera
 * a outra tambem de verdade no Windows - a duplicacao aqui existe so para dar
 * visibilidade honesta ao usuario sobre os dois recursos na interface, cada
 * descricao deixa essa limitacao explicita.
 *
 * O Recall tambem pode nao ter a politica de grupo disponivel dependendo da
 * edicao/versao do Windows (recurso novo, exclusivo de Copilot+ PCs) - nesse
 * caso a unica forma de desativar e manual, via Configuracoes > Privacidade e
 * seguranca > Recall e instantaneos (documentado como tutorial em
 * {@code tutorials/recall-configuracoes.md}, nao uma acao automatizada).
 *
 * Responsabilidade unica: apenas ESCANEAR/LER valores de IA. A acao de
 * alterar um valor (com backup previo) fica em
 * {@link com.nitroboost.actions.ActionExecutor}.
 */
public class AiFeatureScanner {

    /**
     * Uma chave de registro/politica conhecida relacionada a um recurso de IA.
     *
     * @param id               identificador curto e estavel (nome do item no catalogo/backup/historico)
     * @param friendlyName     nome amigavel exibido ao usuario (tambem usado como "nome" na base de conhecimento)
     * @param registryPath     caminho completo da chave
     * @param valueName        nome do valor dentro da chave
     * @param description      explicacao curta em portugues do que esse valor controla
     * @param recommendedValue valor sugerido para desativar o recurso (usado como alvo da acao "aplicar" padrao)
     */
    public record AiFeatureKeyDefinition(String id, String friendlyName, String registryPath,
                                          String valueName, String description, String recommendedValue) {
    }

    /** Estado atual lido de uma chave conhecida (valor pode ser nulo se a chave/valor nao existir na maquina). */
    public record AiFeatureKeyInfo(AiFeatureKeyDefinition definition, String currentValue, boolean exists) {
    }

    public static final List<AiFeatureKeyDefinition> KNOWN_KEYS = List.of(
            new AiFeatureKeyDefinition(
                    "windows_copilot_user",
                    "Windows Copilot (Usuario Atual)",
                    "HKCU\\Software\\Policies\\Microsoft\\Windows\\WindowsCopilot",
                    "TurnOffWindowsCopilot",
                    "Politica que desativa o Windows Copilot (assistente de IA integrado ao Windows) para o "
                            + "usuario atual.",
                    "1"
            ),
            new AiFeatureKeyDefinition(
                    "windows_copilot_allusers",
                    "Windows Copilot (Todos os Usuarios)",
                    "HKLM\\Software\\Policies\\Microsoft\\Windows\\WindowsCopilot",
                    "TurnOffWindowsCopilot",
                    "Mesma politica do Windows Copilot acima, mas aplicada a todos os usuarios deste PC "
                            + "(chave em HKLM - normalmente exige Administrador para alterar).",
                    "1"
            ),
            new AiFeatureKeyDefinition(
                    "copilot_taskbar_button",
                    "Botao do Copilot na Barra de Tarefas",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced",
                    "ShowCopilotButton",
                    "Controla se o icone/botao do Copilot aparece na barra de tarefas.",
                    "0"
            ),
            new AiFeatureKeyDefinition(
                    "windows_recall",
                    "Windows Recall (Retomar)",
                    "HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\WindowsAI",
                    "DisableAIDataAnalysis",
                    "Desativa o Recall (Copilot+ PCs, Windows 11 24H2 em diante): recurso que tira 'fotos' "
                            + "periodicas da tela para depois buscar por IA algo que voce viu antes - tem "
                            + "implicacao de privacidade relevante (as capturas ficam guardadas no seu PC). Se "
                            + "esta politica nao existir na sua edicao/versao do Windows, veja o tutorial "
                            + "'Desativando o Recall pelas Configuracoes' na aba Tutoriais para o caminho manual.",
                    "1"
            ),
            new AiFeatureKeyDefinition(
                    "click_to_do",
                    "Click to Do",
                    "HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\WindowsAI",
                    "DisableClickToDo",
                    "Desativa o Click to Do, recurso de IA que sugere acoes contextuais (copiar, resumir, abrir) "
                            + "sobre o que esta na tela.",
                    "1"
            ),
            new AiFeatureKeyDefinition(
                    "cocreator_image_creation",
                    "Cocreator / Criacao de Imagem por IA (Paint e Fotos)",
                    "HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\WindowsAI",
                    "DisableAIDataAnalysis",
                    "Restringe a geracao de imagens por IA (Cocreator) nos apps Paint e Fotos. NOTA DE "
                            + "TRANSPARENCIA: a Microsoft nao tem uma chave exclusiva para o Cocreator - este item "
                            + "usa a MESMA politica do 'Windows Recall' acima (DisableAIDataAnalysis); desativar "
                            + "um desativa o outro tambem de verdade no Windows.",
                    "1"
            ),
            new AiFeatureKeyDefinition(
                    "edge_copilot_sidebar",
                    "Copilot no Microsoft Edge (Barra Lateral)",
                    "HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge",
                    "HubsSidebarEnabled",
                    "Remove a barra lateral de IA (Copilot/Bing Chat) do navegador Microsoft Edge.",
                    "0"
            )
    );

    // Exemplo de linha real do "reg query": "    TurnOffWindowsCopilot    REG_DWORD    0x1"
    private static final Pattern VALUE_LINE_PATTERN = Pattern.compile("REG_[A-Z_]+\\s+(\\S+)");

    /** Le o estado atual de todas as chaves conhecidas listadas em {@link #KNOWN_KEYS}. */
    public List<AiFeatureKeyInfo> scan() {
        List<AiFeatureKeyInfo> result = new ArrayList<>();
        for (AiFeatureKeyDefinition definition : KNOWN_KEYS) {
            result.add(readValue(definition));
        }
        return result;
    }

    /**
     * Le o valor atual de uma unica chave/valor de registro. Nunca lanca
     * excecao para fora: se a chave ou o valor nao existirem, retorna
     * {@code exists=false} (estado valido - equivale ao padrao de fabrica do
     * Windows para aquele item, ou a policy simplesmente nao existir nesta
     * edicao/versao do Windows), nao e um erro.
     */
    public AiFeatureKeyInfo readValue(AiFeatureKeyDefinition definition) {
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
                return new AiFeatureKeyInfo(definition, null, false);
            }
            if (process.exitValue() != 0) {
                // Chave/valor nao existe nesta maquina - estado valido, nao e erro.
                return new AiFeatureKeyInfo(definition, null, false);
            }

            for (String line : output.split("\\r?\\n")) {
                if (line.contains(definition.valueName())) {
                    Matcher matcher = VALUE_LINE_PATTERN.matcher(line);
                    if (matcher.find()) {
                        return new AiFeatureKeyInfo(definition, matcher.group(1), true);
                    }
                }
            }
            return new AiFeatureKeyInfo(definition, null, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao ler chave de IA '" + definition.friendlyName() + "': " + e.getMessage());
            return new AiFeatureKeyInfo(definition, null, false);
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
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.AiFeatureScanner
     */
    public static void main(String[] args) {
        AiFeatureScanner scanner = new AiFeatureScanner();
        List<AiFeatureKeyInfo> keys = scanner.scan();

        System.out.println("===== NITRO BOOST - Chaves de IA (Copilot/Recall/Click to Do/Cocreator/Edge) =====");
        System.out.println("Total de chaves mapeadas: " + keys.size());
        System.out.println();
        for (AiFeatureKeyInfo info : keys) {
            System.out.printf("- %-55s valor atual=%-12s (existe=%s)%n",
                    info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : "(nao definido)",
                    info.exists());
        }
    }
}
