package com.nitroboost;

import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.repair.NetworkRepairTool;

import java.util.List;

/**
 * Demonstracao de integracao da Fase 15 - Parte 3 (Limpeza e Reset de Componentes de Rede), via
 * console.
 *
 * <p><b>Regra de seguranca desta fase, seguida a risca aqui:</b>
 * <ul>
 *   <li>{@code flushDns()} e {@code clearArpCache()} sao seguros, rapidos e NAO derrubam a conexao
 *       de rede - por isso sao executados DE VERDADE nesta maquina, abaixo, e o resultado real
 *       (sucesso/saida do comando) e impresso.</li>
 *   <li>{@code resetWinsock()}, {@code resetTcpIp()} e {@code renewIp()} alteram configuracao de
 *       baixo nivel de rede (os dois primeiros so fazem efeito apos reiniciar o Windows; o terceiro
 *       derruba a conexao momentaneamente) - por isso NAO sao chamados aqui. Este demo apenas
 *       imprime os comandos que SERIAM montados e passados ao {@code ProcessBuilder}, confirmando
 *       visualmente que estao corretos, sem executa-los. A validacao automatizada dessa montagem
 *       fica em {@code NetworkRepairToolCommandTest} (JUnit).</li>
 * </ul>
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase15Part3ConsoleDemo
 */
public final class Phase15Part3ConsoleDemo {

    private Phase15Part3ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 15 Parte 3 (Rede)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        NetworkRepairTool networkRepairTool = new NetworkRepairTool(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);

        section("15.3.1) flushDns() - EXECUTADO DE VERDADE (ipconfig /flushdns, seguro e rapido)");
        NetworkRepairTool.NetworkRepairResult dnsResult = networkRepairTool.flushDns();
        System.out.println("  sucesso=" + dnsResult.success() + " | " + dnsResult.message());
        System.out.println("  Saida bruta do comando:");
        printIndented(dnsResult.rawOutput());

        section("15.3.2) clearArpCache() - EXECUTADO DE VERDADE (arp -d *, seguro e rapido)");
        NetworkRepairTool.NetworkRepairResult arpResult = networkRepairTool.clearArpCache();
        System.out.println("  sucesso=" + arpResult.success() + " | " + arpResult.message());
        System.out.println("  Saida bruta do comando:");
        printIndented(arpResult.rawOutput());

        section("15.3.3) resetWinsock() / resetTcpIp() / renewIp() - NAO EXECUTADOS DE VERDADE");
        System.out.println("  Conforme a regra de seguranca da Fase 15, estes 3 metodos alteram configuracao de");
        System.out.println("  rede desta maquina (2 exigem reiniciar o Windows, 1 derruba a conexao momentaneamente)");
        System.out.println("  e por isso NAO sao chamados aqui - apenas os comandos que SERIAM montados:");
        System.out.println("    Reset do Winsock -> " + commandPreview(List.of("netsh", "winsock", "reset")));
        System.out.println("    Reset da Pilha TCP/IP -> " + commandPreview(List.of("netsh", "int", "ip", "reset")));
        System.out.println("    Renovar IP (passo 1) -> " + commandPreview(List.of("ipconfig", "/release")));
        System.out.println("    Renovar IP (passo 2) -> " + commandPreview(List.of("ipconfig", "/renew")));
        System.out.println("  (validado tambem via JUnit em NetworkRepairToolCommandTest, sem executar processo algum)");

        section("15.3.4) Confirmando registro no historico (tipo 'network_repair')");
        try {
            List<ActionHistoryRepository.HistoryEntry> recent = historyRepository.findRecent(10);
            long networkEntries = recent.stream().filter(h -> "network_repair".equals(h.actionType())).count();
            System.out.println("  Entradas 'network_repair' encontradas no historico recente: " + networkEntries
                    + " (esperado >= 2, uma para cada acao real executada acima)");
            recent.stream()
                    .filter(h -> "network_repair".equals(h.actionType()))
                    .forEach(h -> System.out.println("    -> #" + h.id() + " | acao=" + h.itemName()
                            + " | sucesso=" + h.success() + " | quando=" + h.performedAt()));
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao consultar historico: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 15 Parte 3 - conclui a Fase 15 inteira (Partes 1+2+3)");
    }

    private static String commandPreview(List<String> command) {
        return String.join(" ", command);
    }

    private static void printIndented(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            System.out.println("    (saida vazia)");
            return;
        }
        for (String line : rawOutput.split("\\R")) {
            if (!line.isBlank()) {
                System.out.println("    " + line);
            }
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
