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
 * Mapeia um conjunto conhecido de chaves de registro relacionadas a recursos
 * de "consumidor" do Windows 11 - anuncios/sugestoes (Menu Iniciar, tela de
 * bloqueio, pesquisa com Bing, notificacoes do OneDrive/Chat) e apps em
 * segundo plano/recursos do sistema (apps em segundo plano, Storage Sense,
 * assistencia de digitacao) - e le o valor atual de cada uma via
 * {@code reg query}, mesmo padrao defensivo do {@link TelemetryScanner}/
 * {@link PerformanceScanner}/{@link GamingScanner}/{@link AiFeatureScanner}.
 *
 * Cobre as secoes 2 (Recursos de Consumidor / Anuncios / Sugestoes) e 3 (Apps
 * em Segundo Plano e Recursos do Sistema) de
 * {@code NITRO-BOOST-fase8-debloat-completo.md} - o item "Diagnostico e
 * feedback" da secao 3 nao entra aqui por ja ser coberto pelo
 * {@link TelemetryScanner} desde a Fase 3.
 *
 * Responsabilidade unica: apenas ESCANEAR/LER esses valores. A acao de
 * alterar um valor (com backup previo) fica em
 * {@link com.nitroboost.actions.ActionExecutor}.
 */
public class ConsumerFeatureScanner {

    /**
     * Uma chave de registro conhecida relacionada a um recurso de consumidor/segundo plano.
     *
     * @param id               identificador curto e estavel (nome do item no catalogo/backup/historico)
     * @param friendlyName     nome amigavel exibido ao usuario (tambem usado como "nome" na base de conhecimento)
     * @param registryPath     caminho completo da chave
     * @param valueName        nome do valor dentro da chave
     * @param description      explicacao curta em portugues do que esse valor controla
     * @param recommendedValue valor sugerido para desativar o recurso (usado como alvo da acao "aplicar" padrao)
     */
    public record ConsumerFeatureKeyDefinition(String id, String friendlyName, String registryPath,
                                                String valueName, String description, String recommendedValue) {
    }

    /** Estado atual lido de uma chave conhecida (valor pode ser nulo se a chave/valor nao existir na maquina). */
    public record ConsumerFeatureKeyInfo(ConsumerFeatureKeyDefinition definition, String currentValue, boolean exists) {
    }

    public static final List<ConsumerFeatureKeyDefinition> KNOWN_KEYS = List.of(
            // ---- Secao 2: anuncios/sugestoes ----
            new ConsumerFeatureKeyDefinition(
                    "start_menu_suggestions",
                    "Sugestoes e Anuncios no Menu Iniciar",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager",
                    "SubscribedContent-338388Enabled",
                    "Remove apps sugeridos/patrocinados que aparecem no Menu Iniciar.",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "windows_tips_notifications",
                    "Dicas e Sugestoes do Windows",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager",
                    "SubscribedContent-338389Enabled",
                    "Remove notificacoes promocionais do proprio Windows (do tipo 'Experimente isso').",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "silent_installed_apps",
                    "Instalacao Silenciosa de Apps Sugeridos",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager",
                    "SilentInstalledAppsEnabled",
                    "Impede que o Windows instale sozinho apps 'recomendados' (ex: jogos, redes sociais).",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "settings_suggestions",
                    "Sugestoes no Painel de Configuracoes",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager",
                    "SystemPaneSuggestionsEnabled",
                    "Remove sugestoes promocionais dentro do proprio app Configuracoes do Windows.",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "lock_screen_spotlight",
                    "Tela de Bloqueio com Conteudo Dinamico (Windows Spotlight)",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\ContentDeliveryManager",
                    "RotatingLockScreenEnabled",
                    "Desativa as imagens/dicas promocionais que trocam automaticamente na tela de bloqueio.",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "bing_search_policy",
                    "Pesquisa do Bing no Menu Iniciar (Politica)",
                    "HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\Windows Search",
                    "BingSearchEnabled",
                    "Via politica de grupo, faz a pesquisa do Windows buscar so localmente, sem resultados da "
                            + "web/Bing. Tem prioridade sobre a configuracao de usuario abaixo, quando definida.",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "bing_search_user",
                    "Pesquisa do Bing no Menu Iniciar (Usuario)",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Search",
                    "BingSearchEnabled",
                    "Mesmo controle de pesquisa web acima, mas na configuracao do usuario (usada quando nao ha "
                            + "politica de grupo aplicada - a maioria dos PCs domesticos).",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "onedrive_sync_notifications",
                    "Notificacoes de Sincronizacao do OneDrive",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced",
                    "ShowSyncProviderNotifications",
                    "Remove os pop-ups insistentes pedindo para configurar backup de arquivos no OneDrive.",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "taskbar_chat_icon",
                    "Icone de Chat (Teams Pessoal) na Barra de Tarefas",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced",
                    "TaskbarMn",
                    "Remove o icone de Chat/Teams (versao consumidor) da barra de tarefas.",
                    "0"
            ),
            // ---- Secao 3: apps em segundo plano e recursos do sistema ----
            new ConsumerFeatureKeyDefinition(
                    "apps_background_policy",
                    "Apps em Segundo Plano (Politica Geral)",
                    "HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\AppPrivacy",
                    "LetAppsRunInBackground",
                    "Impede que apps UWP fiquem rodando em segundo plano consumindo bateria/RAM mesmo fechados "
                            + "(valor 2 = nega globalmente para todos os apps).",
                    "2"
            ),
            new ConsumerFeatureKeyDefinition(
                    "storage_sense_auto_cleanup",
                    "Limpeza Automatica de Arquivos (Storage Sense)",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\StorageSense\\Parameters\\StoragePolicy",
                    "01",
                    "ALERTA: controla a limpeza AUTOMATICA de arquivos temporarios e da lixeira feita pelo "
                            + "Storage Sense. Desativar e seguro (so para a limpeza automatica de acontecer sozinha), "
                            + "mas cuidado ao interpretar o oposto: manter ativado significa que o Windows pode "
                            + "apagar arquivos da lixeira/temporarios sozinho, periodicamente, sem perguntar a cada "
                            + "vez - nao e um risco de travar o sistema, e sim de exclusao de arquivos que voce "
                            + "talvez nao esperasse perder.",
                    "0"
            ),
            new ConsumerFeatureKeyDefinition(
                    "typing_insights",
                    "Coleta de Dados de Digitacao (Typing Insights)",
                    "HKCU\\Software\\Microsoft\\Input\\TIPC",
                    "Enabled",
                    "Desativa a coleta de dados de digitacao usada pela Microsoft para 'melhorar sugestoes' de "
                            + "texto e teclado.",
                    "0"
            ),
            // ---- Secao 4 (Fase 10 Parte 2): interface do Windows 11 ----
            new ConsumerFeatureKeyDefinition(
                    "start_menu_recommended",
                    "Secao 'Recomendado' no Menu Iniciar",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced",
                    "Start_IrisRecommendations",
                    "Remove a secao de arquivos/apps 'recomendados' que ocupa boa parte do Menu Iniciar no Windows 11.",
                    "0"
            )
    );

    // Exemplo de linha real do "reg query": "    SubscribedContent-338388Enabled    REG_DWORD    0x1"
    private static final Pattern VALUE_LINE_PATTERN = Pattern.compile("REG_[A-Z_]+\\s+(\\S+)");

    /** Le o estado atual de todas as chaves conhecidas listadas em {@link #KNOWN_KEYS}. */
    public List<ConsumerFeatureKeyInfo> scan() {
        List<ConsumerFeatureKeyInfo> result = new ArrayList<>();
        for (ConsumerFeatureKeyDefinition definition : KNOWN_KEYS) {
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
    public ConsumerFeatureKeyInfo readValue(ConsumerFeatureKeyDefinition definition) {
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
                return new ConsumerFeatureKeyInfo(definition, null, false);
            }
            if (process.exitValue() != 0) {
                // Chave/valor nao existe nesta maquina - estado valido, nao e erro.
                return new ConsumerFeatureKeyInfo(definition, null, false);
            }

            for (String line : output.split("\\r?\\n")) {
                if (line.contains(definition.valueName())) {
                    Matcher matcher = VALUE_LINE_PATTERN.matcher(line);
                    if (matcher.find()) {
                        return new ConsumerFeatureKeyInfo(definition, matcher.group(1), true);
                    }
                }
            }
            return new ConsumerFeatureKeyInfo(definition, null, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao ler chave de consumidor '" + definition.friendlyName() + "': " + e.getMessage());
            return new ConsumerFeatureKeyInfo(definition, null, false);
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
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.ConsumerFeatureScanner
     */
    public static void main(String[] args) {
        ConsumerFeatureScanner scanner = new ConsumerFeatureScanner();
        List<ConsumerFeatureKeyInfo> keys = scanner.scan();

        System.out.println("===== NITRO BOOST - Chaves de Recursos de Consumidor/Segundo Plano =====");
        System.out.println("Total de chaves mapeadas: " + keys.size());
        System.out.println();
        for (ConsumerFeatureKeyInfo info : keys) {
            System.out.printf("- %-55s valor atual=%-12s (existe=%s)%n",
                    info.definition().friendlyName(),
                    info.exists() ? info.currentValue() : "(nao definido)",
                    info.exists());
        }
    }
}
