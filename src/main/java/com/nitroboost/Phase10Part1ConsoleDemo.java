package com.nitroboost;

import com.nitroboost.core.MemoryCleaner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;

import java.util.List;

/**
 * Demonstracao de integracao da Fase 10 - Parte 1 (Limpeza de RAM), via console: le a RAM livre
 * real desta maquina via OSHI (mesma leitura ja usada em {@code ui/DashboardView}), roda
 * {@link MemoryCleaner#purgeStandbyList()} (a acao priorizada para o botao principal da UI,
 * equivalente ao "Empty List" do RAMMap), le a RAM livre de novo e imprime a diferenca. Confirma
 * tambem que a acao foi gravada em {@code actions_history} com o tipo {@code memory_cleanup}.
 *
 * Se a chamada nativa falhar (app sem elevacao suficiente, privilegio negado, etc.) o teste NAO
 * quebra - {@link MemoryCleaner} nunca lanca excecao, e o resultado (sucesso=false + motivo) e
 * apenas impresso, conforme contrato documentado no Javadoc da classe.
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase10Part1ConsoleDemo
 */
public final class Phase10Part1ConsoleDemo {

    private Phase10Part1ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 10 Parte 1 (Limpeza de RAM)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        MemoryCleaner memoryCleaner = new MemoryCleaner(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);

        GlobalMemory memory = new SystemInfo().getHardware().getMemory();
        long freeBefore = memory.getAvailable();
        long total = memory.getTotal();
        System.out.printf("RAM livre ANTES da limpeza: %.0f MB de %.0f MB total%n",
                freeBefore / 1024.0 / 1024.0, total / 1024.0 / 1024.0);

        System.out.println();
        System.out.println("Rodando purgeStandbyList() (a acao priorizada - equivalente ao 'Empty List' do RAMMap)...");
        MemoryCleaner.MemoryCleanupResult result = memoryCleaner.purgeStandbyList();
        System.out.println("Resultado -> sucesso=" + result.success() + " | " + result.message());

        long freeAfter = memory.getAvailable();
        System.out.println();
        System.out.printf("RAM livre DEPOIS da limpeza: %.0f MB de %.0f MB total%n",
                freeAfter / 1024.0 / 1024.0, total / 1024.0 / 1024.0);
        double deltaMb = (freeAfter - freeBefore) / 1024.0 / 1024.0;
        System.out.printf("Diferenca: %+.0f MB%n", deltaMb);

        section("Confirmando registro no historico (tipo 'memory_cleanup')");
        try {
            List<ActionHistoryRepository.HistoryEntry> recent = historyRepository.findRecent(5);
            recent.stream()
                    .filter(h -> "memory_cleanup".equals(h.actionType()))
                    .findFirst()
                    .ifPresentOrElse(
                            h -> System.out.println("Entrada encontrada -> #" + h.id() + " | item=" + h.itemName()
                                    + " | tipo=" + h.itemType() + " | acao=" + h.actionType() + " | sucesso=" + h.success()
                                    + " | quando=" + h.performedAt()),
                            () -> System.out.println("[ALERTA] Nenhuma entrada 'memory_cleanup' encontrada no historico recente.")
                    );
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao consultar historico: " + e.getMessage());
        }

        System.out.println();
        System.out.println("Fim da demonstracao da Fase 10 Parte 1.");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
