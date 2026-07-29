package com.nitroboost.repair;

import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Limpeza e reset pontual de componentes de rede (Fase 15 Parte B): DNS, Winsock, pilha TCP/IP,
 * IP (release/renew) e cache ARP.
 *
 * <p><b>Excecao documentada a regra de ouro do projeto (mesmo padrao ja estabelecido em
 * {@code core.MemoryCleaner}, Fase 10, e {@code repair.SystemFileRepairTool}, Fase 15 Parte A):</b>
 * estas sao acoes de diagnostico/reparo pontuais - nao existe "estado anterior" significativo para
 * reverter (nao faz sentido reverter uma limpeza de cache de DNS ou um reset do Winsock). Os
 * metodos desta classe NAO criam backup nem checam bloqueio (nao ha item a bloquear). A execucao
 * ainda e registrada em {@code actions_history} (tipo {@code network_repair}), apenas para fins
 * informativos/de auditoria de uso - nunca havera um backup vinculado a essas entradas, e
 * {@code ActionExecutor.restoreFromHistory} nunca despacha para este tipo.
 *
 * <p><b>Nivel de impacto de cada metodo (secao B da Fase 15) - IMPORTANTE para quem chama esta
 * classe pela UI:</b>
 * <ul>
 *   <li>{@link #flushDns()} e {@link #clearArpCache()} - seguros, rapidos, NAO derrubam a conexao
 *       de rede. Podem rodar sem confirmacao extra.</li>
 *   <li>{@link #resetWinsock()} e {@link #resetTcpIp()} - alteram configuracao de baixo nivel de
 *       rede e SO fazem efeito depois que o Windows for reiniciado. A UI deve avisar isso e pedir
 *       confirmacao explicita antes de chamar.</li>
 *   <li>{@link #renewIp()} - derruba a conexao de rede momentaneamente (release seguido de renew).
 *       Nao exige reinicio, mas a UI deve avisar isso e pedir confirmacao explicita antes de
 *       chamar.</li>
 * </ul>
 *
 * <p>Execucao via {@link ProcessBuilder}, de forma sincrona (estas acoes sao rapidas, ao contrario
 * do SFC/DISM da Parte A) - o chamador (UI) e responsavel por invocar estes metodos em uma thread
 * de fundo, nunca na JavaFX Application Thread. Nenhum metodo lanca excecao para fora desta classe -
 * qualquer falha ao iniciar/rodar o processo vira um {@link NetworkRepairResult} com
 * {@code success=false}.
 */
public class NetworkRepairTool {

    /** Resultado de uma acao de rede - nunca lanca excecao para fora da classe. */
    public record NetworkRepairResult(String actionLabel, boolean success, String message, String rawOutput) {
    }

    // Descoberto testando clearArpCache() de verdade nesta maquina sem elevacao (Fase 15 Parte 3):
    // "arp -d *" sai com codigo 0 mesmo quando FALHA por falta de permissao, so avisando na propria
    // saida de texto ("A operacao solicitada requer elevacao."). Por isso o codigo de saida sozinho
    // NAO e confiavel para estes comandos de rede - a saida tambem precisa ser inspecionada (mesmo
    // espirito da classificacao melhor-esforco do SFC em SystemFileRepairTool, Parte A desta fase).
    //
    // "eleva" (sem acento) de proposito, em vez de "elevacao"/"elevação" por extenso: ferramentas de
    // console do Windows (arp/netsh/ipconfig) imprimem na codepage OEM do console, que nao bate com
    // o charset padrao usado por ProcessBuilder/new String(bytes) - os caracteres acentuados (ç, ã)
    // saem corrompidos ("elevaç?o"), mas o prefixo ASCII "eleva" sempre sobrevive, e ja e suficiente
    // para casar tanto com "elevação" (PT) quanto com "elevation" (EN) sem falso-positivo plausivel
    // na saida desses comandos de rede.
    private static final List<String> ELEVATION_REQUIRED_KEYWORDS = List.of(
            "eleva", "access is denied", "acesso negado"
    );

    private final ActionHistoryRepository historyRepository;

    public NetworkRepairTool(DatabaseManager databaseManager) {
        this.historyRepository = new ActionHistoryRepository(databaseManager);
    }

    /** Botao principal "Limpar Cache de DNS" - seguro, rapido, nao derruba a conexao. */
    public NetworkRepairResult flushDns() {
        return runSingleCommand("Limpar Cache de DNS", flushDnsCommand());
    }

    /** Reset do Winsock - so tem efeito apos reiniciar o Windows. Exige confirmacao na UI. */
    public NetworkRepairResult resetWinsock() {
        return runSingleCommand("Reset do Winsock", resetWinsockCommand());
    }

    /** Reset da pilha TCP/IP para o padrao de fabrica - so tem efeito apos reiniciar o Windows. Exige confirmacao na UI. */
    public NetworkRepairResult resetTcpIp() {
        return runSingleCommand("Reset da Pilha TCP/IP", resetTcpIpCommand());
    }

    /**
     * Libera e renova o endereco IP ({@code ipconfig /release} seguido de {@code ipconfig /renew},
     * em sequencia). Derruba a conexao de rede momentaneamente enquanto roda. Exige confirmacao na UI.
     * Se o {@code /release} falhar, o {@code /renew} nao e tentado (nao ha IP liberado para renovar).
     */
    public NetworkRepairResult renewIp() {
        String actionLabel = "Renovar IP";
        StringBuilder combinedOutput = new StringBuilder();
        NetworkRepairResult result;
        try {
            ProcessResult release = execute(releaseCommand());
            combinedOutput.append("--- ipconfig /release ---").append(System.lineSeparator()).append(release.output());

            if (!actuallySucceeded(release)) {
                result = new NetworkRepairResult(actionLabel, false,
                        "Falha ao liberar o IP atual" + failureDetail(release) + ". A renovacao nao foi tentada.",
                        combinedOutput.toString());
            } else {
                ProcessResult renew = execute(renewCommand());
                combinedOutput.append(System.lineSeparator()).append("--- ipconfig /renew ---")
                        .append(System.lineSeparator()).append(renew.output());
                boolean renewOk = actuallySucceeded(renew);
                result = new NetworkRepairResult(actionLabel, renewOk,
                        renewOk ? "IP liberado e renovado com sucesso."
                                : "IP liberado, mas a renovacao falhou" + failureDetail(renew) + ".",
                        combinedOutput.toString());
            }
        } catch (Exception e) {
            result = new NetworkRepairResult(actionLabel, false,
                    "Erro inesperado ao renovar o IP: " + e.getMessage(), combinedOutput.toString());
        }
        recordHistoryQuiet(result);
        return result;
    }

    /** Limpa a tabela ARP em cache - seguro, rapido, nao derruba a conexao. */
    public NetworkRepairResult clearArpCache() {
        return runSingleCommand("Limpar Cache ARP", clearArpCacheCommand());
    }

    // ------------------------------------------------------------------
    // Montagem dos comandos - metodos pacote-visiveis de proposito, para poderem ser testados
    // isoladamente (comando montado corretamente) SEM executar o processo real, mesmo padrao de
    // ActionExecutor.displaySettingsCommand() (Fase 14) - ver NetworkRepairToolCommandTest.
    // ------------------------------------------------------------------

    static List<String> flushDnsCommand() {
        return List.of("ipconfig", "/flushdns");
    }

    static List<String> resetWinsockCommand() {
        return List.of("netsh", "winsock", "reset");
    }

    static List<String> resetTcpIpCommand() {
        return List.of("netsh", "int", "ip", "reset");
    }

    static List<String> releaseCommand() {
        return List.of("ipconfig", "/release");
    }

    static List<String> renewCommand() {
        return List.of("ipconfig", "/renew");
    }

    static List<String> clearArpCacheCommand() {
        return List.of("arp", "-d", "*");
    }

    private NetworkRepairResult runSingleCommand(String actionLabel, List<String> command) {
        NetworkRepairResult result;
        try {
            ProcessResult processResult = execute(command);
            boolean ok = actuallySucceeded(processResult);
            result = new NetworkRepairResult(actionLabel, ok,
                    ok ? actionLabel + " concluido com sucesso."
                            : actionLabel + " falhou" + failureDetail(processResult) + ".",
                    processResult.output());
        } catch (Exception e) {
            result = new NetworkRepairResult(actionLabel, false, "Erro inesperado: " + e.getMessage(), "");
        }
        recordHistoryQuiet(result);
        return result;
    }

    private record ProcessResult(boolean success, int exitCode, String output) {
    }

    /** Roda um comando externo de forma sincrona (essas acoes de rede sao rapidas, sem necessidade de streaming). */
    private ProcessResult execute(List<String> command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode == 0, exitCode, output);
    }

    /**
     * Sucesso "de verdade" do comando: exige codigo de saida 0 E a saida de texto nao conter uma
     * mensagem de recusa por falta de elevacao - ver {@link #ELEVATION_REQUIRED_KEYWORDS}. Alguns
     * comandos de rede (ex: {@code arp -d *}, confirmado testando de verdade nesta maquina) sempre
     * saem com codigo 0 mesmo quando a acao pedida foi recusada.
     */
    private static boolean actuallySucceeded(ProcessResult processResult) {
        return processResult.success() && !requiresElevation(processResult.output());
    }

    /** Trecho de mensagem pronto para compor "X falhou<detalhe>." - varia conforme a causa detectada. */
    private static String failureDetail(ProcessResult processResult) {
        if (requiresElevation(processResult.output())) {
            return ": requer permissao de Administrador (abra o NITRO BOOST como Administrador e tente novamente)";
        }
        return " (codigo " + processResult.exitCode() + ")";
    }

    /** Pacote-visivel de proposito, para ser testada com strings fixas - ver {@code NetworkRepairToolElevationTest}. */
    static boolean requiresElevation(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String normalized = stripAccents(output.toLowerCase(Locale.ROOT));
        for (String keyword : ELEVATION_REQUIRED_KEYWORDS) {
            if (normalized.contains(stripAccents(keyword))) {
                return true;
            }
        }
        return false;
    }

    /** Remove acentos (NFD + descarta marcas diacriticas) para casar "elevação" com "elevacao" etc. */
    private static String stripAccents(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /**
     * Grava a execucao em {@code actions_history} (tipo {@code network_repair}) direto via
     * {@link ActionHistoryRepository} - sem passar por {@code LockManager}/{@code BackupManager},
     * mesma excecao documentada na classe (ver Javadoc de topo). Uma falha ao gravar o historico e
     * apenas logada no console, nunca sobrescreve o resultado real da acao ja entregue ao usuario.
     */
    private void recordHistoryQuiet(NetworkRepairResult result) {
        try {
            historyRepository.record(null, result.actionLabel(), "network", "network_repair",
                    null, null, result.success(), result.success() ? null : result.message());
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao registrar reparo de rede no historico: " + e.getMessage());
        }
    }
}
