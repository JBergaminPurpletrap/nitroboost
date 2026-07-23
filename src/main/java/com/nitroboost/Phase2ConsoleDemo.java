package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.BackupManager;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.ProcessScanner;
import com.nitroboost.core.StartupScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demonstracao de integracao da Fase 2 (Seguranca e Controle), via console,
 * conforme o criterio de conclusao da fase: "nenhuma acao e feita sem passar
 * por backup + historico, e itens bloqueados nao podem ser alterados sem
 * desbloqueio manual explicito".
 *
 * REGRA DE SEGURANCA SEGUIDA AQUI (igual a Fase 1): todo o round-trip e
 * testado apenas contra itens de TESTE criados pelo proprio programa nesta
 * execucao (um processo ping.exe iniciado por nos, e uma entrada de registro
 * de teste propria em HKCU) - nunca contra processo/servico/startup
 * essencial da maquina real.
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase2ConsoleDemo
 */
public final class Phase2ConsoleDemo {

    private static final String TEST_REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String TEST_REGISTRY_VALUE_NAME = "NitroBoostTestEntryFase2";
    private static final Pattern PID_PATTERN = Pattern.compile("PID (\\d+)");

    private Phase2ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 2 (Backup + Lock + Historico)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        ActionExecutor actionExecutor = new ActionExecutor(databaseManager);
        BackupManager backupManager = new BackupManager(databaseManager);
        LockManager lockManager = new LockManager(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);

        testProcessLockRefusalThenUnlockAndRestore(actionExecutor, backupManager, lockManager, historyRepository);
        testStartupLockRefusalThenUnlockAndRestore(actionExecutor, lockManager, historyRepository);

        section("Historico completo de acoes (mais recente primeiro)");
        try {
            historyRepository.printRecentHistory(20);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 2");
    }

    // ------------------------------------------------------------------
    // 1) Processo de teste: bloquear -> tentar matar (recusado) -> desbloquear
    //    -> matar -> reverter usando restoreFromHistory(historyId)
    // ------------------------------------------------------------------

    private static void testProcessLockRefusalThenUnlockAndRestore(ActionExecutor actionExecutor,
            BackupManager backupManager, LockManager lockManager, ActionHistoryRepository historyRepository) {
        section("1) Processo de teste - bloqueio recusa a acao, desbloqueio permite (com reversao via historico)");
        System.out.println("(processo de teste: ping.exe, iniciado e encerrado apenas por este teste)");

        Process testProcess = null;
        Long relaunchedPid = null;
        try {
            testProcess = new ProcessBuilder("ping", "-n", "60", "127.0.0.1").start();
            long testPid = testProcess.pid();
            Thread.sleep(500); // da tempo do SO registrar o processo antes de escanea-lo
            Optional<ProcessScanner.ProcessInfo> processInfo = new ProcessScanner().findByPid((int) testPid);
            if (processInfo.isEmpty()) {
                System.out.println("  [ABORTADO] Processo de teste (PID " + testPid + ") nao encontrado pelo scanner.");
                return;
            }
            String processName = processInfo.get().name();
            System.out.println("  Processo de teste iniciado: " + processName + " (PID " + testPid + ")");

            LockManager.LockResult lockResult = lockManager.lockItem(processName, "process", "teste automatizado Fase 2");
            System.out.println("  lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

            System.out.println("  Tentando matar o processo bloqueado...");
            ActionExecutor.ActionResult refusedKill = actionExecutor.killProcess((int) testPid);
            System.out.println("  killProcess() [item bloqueado] -> sucesso=" + refusedKill.success()
                    + " | " + refusedKill.message());
            if (refusedKill.success() || refusedKill.backupId() != null) {
                System.out.println("  [ATENCAO] A acao deveria ter sido recusada sem criar backup - verificar LockManager/ActionExecutor.");
            } else {
                System.out.println("  [OK] Acao recusada corretamente: nenhum backup criado, processo continua vivo.");
            }
            boolean stillAlive = ProcessHandle.of(testPid).map(ProcessHandle::isAlive).orElse(false);
            System.out.println("  Processo ainda vivo apos tentativa recusada: " + stillAlive);

            LockManager.LockResult unlockResult = lockManager.unlockItem(processName, "process");
            System.out.println("  unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());

            ActionExecutor.ActionResult killResult = actionExecutor.killProcess((int) testPid);
            System.out.println("  killProcess() [item desbloqueado] -> sucesso=" + killResult.success()
                    + " | " + killResult.message());
            if (killResult.backupId() == null) {
                System.out.println("  [ABORTADO] Nenhum backup foi criado - nao e seguro tentar reverter.");
                return;
            }

            // Pega o id da entrada de historico que acabou de ser gravada para essa acao, para
            // demonstrar a reversao por historyId (nao apenas por backupId direto).
            var recentHistory = historyRepository.findRecent(1);
            if (recentHistory.isEmpty()) {
                System.out.println("  [ABORTADO] Nao foi possivel localizar a entrada de historico recem-criada.");
                return;
            }
            long historyId = recentHistory.get(0).id();
            System.out.println("  Entrada de historico #" + historyId + " localizada para a acao 'kill'.");

            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreFromHistory(historyId);
            System.out.println("  restoreFromHistory(#" + historyId + ") -> sucesso=" + restoreResult.success()
                    + " | " + restoreResult.message());

            Optional<BackupManager.BackupRecord> backupAfterRestore = backupManager.findById(killResult.backupId());
            backupAfterRestore.ifPresent(b -> System.out.println("  Backup #" + killResult.backupId()
                    + " marcado como restaurado no banco: " + b.restored()));

            if (restoreResult.success()) {
                relaunchedPid = extractPid(restoreResult.message());
            }
        } catch (Exception e) {
            System.err.println("  [NITRO BOOST] Erro inesperado no teste de lock/kill/restore de processo: " + e.getMessage());
        } finally {
            silentlyDestroy(testProcess);
            silentlyDestroyByPid(relaunchedPid);
        }
    }

    // ------------------------------------------------------------------
    // 2) Item de startup de teste: bloquear -> tentar desativar (recusado)
    //    -> desbloquear -> desativar -> reverter usando restoreFromHistory
    // ------------------------------------------------------------------

    private static void testStartupLockRefusalThenUnlockAndRestore(ActionExecutor actionExecutor,
            LockManager lockManager, ActionHistoryRepository historyRepository) {
        section("2) Item de startup de teste - bloqueio recusa a acao, desbloqueio permite (com reversao via historico)");
        System.out.println("(entrada de registro de TESTE propria em HKCU - nunca uma entrada real do usuario)");

        boolean testEntryCreated = false;
        try {
            int createExit = runAndWait("reg", "add", TEST_REGISTRY_KEY, "/v", TEST_REGISTRY_VALUE_NAME,
                    "/t", "REG_SZ", "/d", "cmd.exe /c exit", "/f");
            if (createExit != 0) {
                System.out.println("  [ABORTADO] Nao foi possivel criar a entrada de registro de teste (exit=" + createExit + ").");
                return;
            }
            testEntryCreated = true;
            System.out.println("  Entrada de registro de teste criada: " + TEST_REGISTRY_VALUE_NAME);

            Optional<StartupScanner.StartupItemInfo> testItem = new StartupScanner().scan().stream()
                    .filter(item -> TEST_REGISTRY_VALUE_NAME.equals(item.name()))
                    .findFirst();
            if (testItem.isEmpty()) {
                System.out.println("  [ABORTADO] Entrada de teste criada, mas nao foi encontrada pelo StartupScanner.");
                return;
            }

            LockManager.LockResult lockResult = lockManager.lockItem(TEST_REGISTRY_VALUE_NAME, "startup", "teste automatizado Fase 2");
            System.out.println("  lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

            System.out.println("  Tentando desativar o item de startup bloqueado...");
            ActionExecutor.ActionResult refusedDisable = actionExecutor.disableStartupItem(testItem.get());
            System.out.println("  disableStartupItem() [item bloqueado] -> sucesso=" + refusedDisable.success()
                    + " | " + refusedDisable.message());
            if (refusedDisable.success() || refusedDisable.backupId() != null) {
                System.out.println("  [ATENCAO] A acao deveria ter sido recusada sem criar backup - verificar LockManager/ActionExecutor.");
            } else {
                System.out.println("  [OK] Acao recusada corretamente: nenhum backup criado, entrada de registro continua intacta.");
            }
            boolean stillPresentAfterRefusal = new StartupScanner().scan().stream()
                    .anyMatch(item -> TEST_REGISTRY_VALUE_NAME.equals(item.name()));
            System.out.println("  Entrada de teste ainda presente apos tentativa recusada: " + stillPresentAfterRefusal);

            LockManager.LockResult unlockResult = lockManager.unlockItem(TEST_REGISTRY_VALUE_NAME, "startup");
            System.out.println("  unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());

            ActionExecutor.ActionResult disableResult = actionExecutor.disableStartupItem(testItem.get());
            System.out.println("  disableStartupItem() [item desbloqueado] -> sucesso=" + disableResult.success()
                    + " | " + disableResult.message());
            if (disableResult.backupId() == null) {
                System.out.println("  [ABORTADO] Nenhum backup foi criado - nao e seguro tentar reverter.");
                return;
            }

            var recentHistory = historyRepository.findRecent(1);
            if (recentHistory.isEmpty()) {
                System.out.println("  [ABORTADO] Nao foi possivel localizar a entrada de historico recem-criada.");
                return;
            }
            long historyId = recentHistory.get(0).id();
            System.out.println("  Entrada de historico #" + historyId + " localizada para a acao 'disable'.");

            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreFromHistory(historyId);
            System.out.println("  restoreFromHistory(#" + historyId + ") -> sucesso=" + restoreResult.success()
                    + " | " + restoreResult.message());

            boolean stillExistsAfterRestore = new StartupScanner().scan().stream()
                    .anyMatch(item -> TEST_REGISTRY_VALUE_NAME.equals(item.name()));
            System.out.println("  Entrada de teste presente no registro apos o restore: " + stillExistsAfterRestore);
        } catch (Exception e) {
            System.err.println("  [NITRO BOOST] Erro inesperado no teste de lock/disable/restore de startup: " + e.getMessage());
        } finally {
            try {
                if (testEntryCreated) {
                    runAndWait("reg", "delete", TEST_REGISTRY_KEY, "/v", TEST_REGISTRY_VALUE_NAME, "/f");
                }
            } catch (Exception ignored) {
                // limpeza de teste - falha aqui nao importa para o resultado da demonstracao
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private static int runAndWait(String... command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        process.getInputStream().readAllBytes(); // drena a saida para o processo nao travar
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    private static Long extractPid(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = PID_PATTERN.matcher(message);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private static void silentlyDestroy(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.destroy();
        } catch (Exception ignored) {
            // limpeza de teste - falha aqui nao importa para o resultado da demonstracao
        }
    }

    private static void silentlyDestroyByPid(Long pid) {
        if (pid == null) {
            return;
        }
        try {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
        } catch (Exception ignored) {
            // limpeza de teste - falha aqui nao importa para o resultado da demonstracao
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
