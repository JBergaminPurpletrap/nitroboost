package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.BackupManager;
import com.nitroboost.core.ElevationChecker;
import com.nitroboost.core.ProcessScanner;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.core.StartupScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.knowledge.KnowledgeBase;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demonstracao de integracao da Fase 1, via console (sem UI ainda), conforme
 * o criterio de conclusao da fase: "e possivel escanear processos/servicos/
 * startup, ver a classificacao de cada item, desativar algo, e reverter
 * usando o backup".
 *
 * REGRA DE SEGURANCA SEGUIDA AQUI: o round-trip completo de "desativar ->
 * verificar backup -> reverter" so e testado contra itens de TESTE criados
 * pelo proprio programa nesta execucao (um processo notepad.exe iniciado por
 * nos, e uma entrada de registro de teste tambem criada por nos) - nunca
 * contra processos/servicos essenciais do Windows real, conforme a regra de
 * ouro #3 do projeto.
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase1ConsoleDemo
 */
public final class Phase1ConsoleDemo {

    private Phase1ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 1");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        printElevationStatus();

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        System.out.println("Base de conhecimento carregada com " + knowledgeBase.size() + " itens.");

        var itemRepository = new com.nitroboost.db.ItemRepository(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);
        ActionExecutor actionExecutor = new ActionExecutor(databaseManager);
        BackupManager backupManager = new BackupManager(databaseManager);

        int persistedCount = 0;
        persistedCount += scanClassifyAndPersistProcesses(knowledgeBase, itemRepository);
        persistedCount += scanClassifyAndPersistServices(knowledgeBase, itemRepository);
        persistedCount += scanClassifyAndPersistStartup(knowledgeBase, itemRepository);
        System.out.println();
        System.out.println("Total de itens persistidos/atualizados na tabela 'items': " + persistedCount);

        testProcessKillAndRestoreRoundTrip(actionExecutor, backupManager);
        testStartupDisableAndRestoreRoundTrip(actionExecutor, backupManager);

        section("Historico de acoes registradas (10 mais recentes)");
        try {
            historyRepository.printRecentHistory(10);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 1");
    }

    // ------------------------------------------------------------------
    // Elevacao (Administrador)
    // ------------------------------------------------------------------

    private static void printElevationStatus() {
        boolean elevated = ElevationChecker.isElevated();
        System.out.println("Executando como Administrador: " + (elevated ? "SIM" : "NAO"));
        if (!elevated) {
            System.out.println("(esperado neste ambiente de desenvolvimento - acoes que exigem elevacao "
                    + "vao falhar de forma controlada, sem derrubar a aplicacao, conforme a regra de "
                    + "tratamento de erro do projeto.)");
        }
    }

    // ------------------------------------------------------------------
    // Escanear -> Classificar -> Persistir (Processos / Servicos / Startup)
    // ------------------------------------------------------------------

    private static int scanClassifyAndPersistProcesses(KnowledgeBase knowledgeBase, com.nitroboost.db.ItemRepository itemRepository) {
        section("1) Processos - Top 10 por RAM, com classificacao");
        List<ProcessScanner.ProcessInfo> topProcesses = new ProcessScanner().topByRam(10);
        int persisted = 0;
        for (ProcessScanner.ProcessInfo process : topProcesses) {
            Optional<KnowledgeBase.KnowledgeEntry> entry = knowledgeBase.find(process.name(), "process");
            String classification = entry.map(e -> e.classification().label()).orElse("nao catalogado");
            System.out.printf("  PID %-7d %-28s RAM=%7.1fMB  classificacao=%s%n",
                    process.pid(), truncate(process.name(), 28), process.ramBytes() / 1024.0 / 1024.0, classification);
            persisted += persistQuiet(itemRepository, process.name(), "process",
                    entry.map(e -> e.classification().label()).orElse(null), "running");
        }
        return persisted;
    }

    private static int scanClassifyAndPersistServices(KnowledgeBase knowledgeBase, com.nitroboost.db.ItemRepository itemRepository) {
        section("2) Servicos em execucao (ate 15), com classificacao");
        List<ServiceScanner.ServiceInfo> services = new ServiceScanner().runningServices();
        int persisted = 0;
        int shown = 0;
        for (ServiceScanner.ServiceInfo service : services) {
            Optional<KnowledgeBase.KnowledgeEntry> entry = knowledgeBase.find(service.name(), "service");
            String classification = entry.map(e -> e.classification().label()).orElse("nao catalogado");
            if (shown < 15) {
                System.out.printf("  %-28s estado=%-8s inicio=%-10s classificacao=%s%n",
                        service.name(), service.state(), service.startMode(), classification);
                shown++;
            }
            persisted += persistQuiet(itemRepository, service.name(), "service",
                    entry.map(e -> e.classification().label()).orElse(null), service.state() + "/" + service.startMode());
        }
        System.out.println("  (" + services.size() + " servicos em execucao no total)");
        return persisted;
    }

    private static int scanClassifyAndPersistStartup(KnowledgeBase knowledgeBase, com.nitroboost.db.ItemRepository itemRepository) {
        section("3) Itens de inicializacao (Startup), com classificacao");
        List<StartupScanner.StartupItemInfo> startupItems = new StartupScanner().scan();
        int persisted = 0;
        for (StartupScanner.StartupItemInfo item : startupItems) {
            Optional<KnowledgeBase.KnowledgeEntry> entry = knowledgeBase.find(item.name(), "startup");
            String classification = entry.map(e -> e.classification().label()).orElse("nao catalogado");
            System.out.printf("  [%-26s] %-30s classificacao=%s%n", item.source(), truncate(item.name(), 30), classification);
            persisted += persistQuiet(itemRepository, item.name(), "startup",
                    entry.map(e -> e.classification().label()).orElse(null), "enabled");
        }
        return persisted;
    }

    private static int persistQuiet(com.nitroboost.db.ItemRepository itemRepository, String name, String type,
                                     String classification, String currentState) {
        try {
            itemRepository.upsertItem(name, type, classification, null, currentState);
            return 1;
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao persistir item '" + name + "' (" + type + "): " + e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 4) Round-trip completo: kill + restore de um PROCESSO DE TESTE
    // ------------------------------------------------------------------

    private static void testProcessKillAndRestoreRoundTrip(ActionExecutor actionExecutor, BackupManager backupManager) {
        section("4) Teste completo: matar processo de teste -> backup -> reverter");
        System.out.println("(por seguranca, usamos aqui um processo de teste iniciado por nos mesmos - "
                + "ping.exe - nunca um processo essencial do Windows real)");

        // NOTA: notepad.exe no Windows 11 e um stub de app empacotada (MSIX) que repassa a
        // execucao para um processo host com outro PID e encerra sozinho quase imediatamente -
        // isso causava falha intermitente aqui (o PID capturado ja tinha morrido antes do kill).
        // ping.exe e um executavel classico do System32, mantem o mesmo PID durante toda a
        // execucao (60s), servindo como processo de teste estavel.
        Process testProcess = null;
        Long relaunchedPid = null;
        try {
            testProcess = new ProcessBuilder("ping", "-n", "60", "127.0.0.1").start();
            long testPid = testProcess.pid();
            Thread.sleep(500); // da tempo do SO registrar o processo antes de escanea-lo
            System.out.println("  Processo de teste iniciado: ping.exe (PID " + testPid + ")");

            ActionExecutor.ActionResult killResult = actionExecutor.killProcess((int) testPid);
            System.out.println("  killProcess() -> sucesso=" + killResult.success() + " | " + killResult.message());

            if (killResult.backupId() == null) {
                System.out.println("  [ABORTADO] Nenhum backup foi criado - nao e seguro tentar reverter.");
                return;
            }

            Optional<BackupManager.BackupRecord> backup = backupManager.findById(killResult.backupId());
            if (backup.isPresent()) {
                System.out.println("  Backup #" + killResult.backupId() + " confirmado no banco. Snapshot: "
                        + backup.get().stateSnapshot());
            } else {
                System.out.println("  [ATENCAO] Backup #" + killResult.backupId() + " nao encontrado no banco.");
            }

            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreProcess(killResult.backupId());
            System.out.println("  restoreProcess() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

            Optional<BackupManager.BackupRecord> backupAfterRestore = backupManager.findById(killResult.backupId());
            backupAfterRestore.ifPresent(b -> System.out.println("  Backup #" + killResult.backupId()
                    + " marcado como restaurado no banco: " + b.restored()));

            // Extrai o novo PID da mensagem de resultado (ex: "...novo PID 12345)") so para
            // conseguir encerrar essa segunda instancia de teste na limpeza abaixo.
            if (restoreResult.success()) {
                relaunchedPid = extractPid(restoreResult.message());
            }
        } catch (Exception e) {
            System.err.println("  [NITRO BOOST] Erro inesperado no teste de kill/restore de processo: " + e.getMessage());
        } finally {
            // Limpeza: encerra qualquer ping de teste que tenha ficado rodando (nao faz parte da
            // acao sendo testada, e apenas higiene para nao deixar processos orfaos na maquina).
            silentlyDestroy(testProcess);
            silentlyDestroyByPid(relaunchedPid);
        }
    }

    private static final Pattern PID_PATTERN = Pattern.compile("PID (\\d+)");

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

    // ------------------------------------------------------------------
    // 5) Round-trip completo: desativar + reverter uma ENTRADA DE STARTUP DE TESTE
    // ------------------------------------------------------------------

    private static final String TEST_REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String TEST_REGISTRY_VALUE_NAME = "NitroBoostTestEntry";

    private static void testStartupDisableAndRestoreRoundTrip(ActionExecutor actionExecutor, BackupManager backupManager) {
        section("5) Teste completo: desativar item de startup de teste -> backup -> reverter");
        System.out.println("(por seguranca, criamos uma entrada de registro de TESTE propria em HKCU - "
                + "nunca mexemos em uma entrada real do usuario neste teste automatizado)");

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

            ActionExecutor.ActionResult disableResult = actionExecutor.disableStartupItem(testItem.get());
            System.out.println("  disableStartupItem() -> sucesso=" + disableResult.success() + " | " + disableResult.message());

            if (disableResult.backupId() == null) {
                System.out.println("  [ABORTADO] Nenhum backup foi criado - nao e seguro tentar reverter.");
                return;
            }

            Optional<BackupManager.BackupRecord> backup = backupManager.findById(disableResult.backupId());
            backup.ifPresent(b -> System.out.println("  Backup #" + disableResult.backupId()
                    + " confirmado no banco. Snapshot: " + b.stateSnapshot()));

            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreStartupItem(disableResult.backupId());
            System.out.println("  restoreStartupItem() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

            boolean stillExistsAfterRestore = new StartupScanner().scan().stream()
                    .anyMatch(item -> TEST_REGISTRY_VALUE_NAME.equals(item.name()));
            System.out.println("  Entrada de teste presente no registro apos o restore: " + stillExistsAfterRestore);
        } catch (Exception e) {
            System.err.println("  [NITRO BOOST] Erro inesperado no teste de disable/restore de startup: " + e.getMessage());
        } finally {
            // Limpeza final: garante que a entrada de teste nao fica no registro do usuario,
            // independentemente do resultado do teste acima.
            try {
                if (testEntryCreated) {
                    runAndWait("reg", "delete", TEST_REGISTRY_KEY, "/v", TEST_REGISTRY_VALUE_NAME, "/f");
                }
            } catch (Exception ignored) {
                // limpeza de teste - falha aqui nao importa para o resultado da demonstracao
            }
        }
    }

    private static int runAndWait(String... command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        process.getInputStream().readAllBytes(); // drena a saida para o processo nao travar
        boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    // ------------------------------------------------------------------
    // Utilitarios de exibicao
    // ------------------------------------------------------------------

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
