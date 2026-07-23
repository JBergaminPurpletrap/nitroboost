package com.nitroboost;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.BloatwareScanner;
import com.nitroboost.core.PowerPlanScanner;
import com.nitroboost.core.TaskSchedulerScanner;
import com.nitroboost.core.TelemetryScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Demonstracao de integracao da Fase 3 (Expansao de Varredura), via console:
 * Tarefas Agendadas, Plano de Energia, Telemetria e Bloatware (apps UWP).
 *
 * REGRAS DE SEGURANCA SEGUIDAS NESTE TESTE (ver instrucoes da Fase 3):
 *  - Tarefas agendadas: a listagem e feita contra tarefas REAIS da maquina (leitura,
 *    sem risco). O teste de "desativar/reverter" roda apenas contra uma tarefa de
 *    TESTE propria, criada e removida por este programa.
 *  - Plano de energia: a listagem e feita contra os planos REAIS da maquina (leitura,
 *    sem risco). Esta maquina expoe apenas 1 plano visivel via "powercfg /list"
 *    (Equilibrado/Balanced - provavelmente por politica/OEM). Para testar a troca de
 *    plano sem depender de um segundo plano real pre-existente, o teste cria um plano
 *    de TESTE proprio (duplicado do plano ativo, via "powercfg -duplicatescheme"),
 *    troca para ele, confirma a troca, REVERTE para o plano original via
 *    ActionExecutor.restorePowerPlan (o mesmo caminho de codigo usado em producao) e
 *    remove o plano de teste ao final - o plano de energia original da maquina de
 *    desenvolvimento e sempre confirmado restaurado antes do programa terminar.
 *  - Telemetria: a LEITURA dos 7 valores conhecidos e feita contra o registro real
 *    (somente leitura, sem risco). O teste de "alterar com backup" roda apenas contra
 *    uma chave de TESTE propria em HKCU\Software\NitroBoostTest (nunca uma chave real
 *    de telemetria do sistema).
 *  - Bloatware: a LISTAGEM de apps UWP e feita contra os apps REAIS instalados
 *    (Get-AppxPackage e somente leitura). O metodo de desinstalar e exercido apenas em
 *    modo de SIMULACAO (-WhatIf do PowerShell) contra um app de bloatware real
 *    encontrado na maquina - o comando roda de verdade, mas o PowerShell garante que
 *    nada e alterado; o teste confirma ao final que o app continua instalado.
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase3ConsoleDemo
 */
public final class Phase3ConsoleDemo {

    private static final String TEST_TASK_NAME = "NitroBoostTestTaskFase3";
    private static final String TEST_TASK_PATH = "\\" + TEST_TASK_NAME;
    private static final String TEST_TELEMETRY_REGISTRY_PATH = "HKCU\\Software\\NitroBoostTest";
    private static final String TEST_TELEMETRY_VALUE_NAME = "TelemetryTestFlag";

    private Phase3ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 3 (Tarefas, Energia, Telemetria, Bloatware)");

        DatabaseManager databaseManager = new DatabaseManager();
        try {
            databaseManager.initializeSchema();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao inicializar banco de dados: " + e.getMessage());
            return;
        }

        ActionExecutor actionExecutor = new ActionExecutor(databaseManager);
        LockManager lockManager = new LockManager(databaseManager);
        ActionHistoryRepository historyRepository = new ActionHistoryRepository(databaseManager);

        testTaskScheduler(actionExecutor, lockManager, historyRepository);
        testPowerPlan(actionExecutor, historyRepository);
        testTelemetry(actionExecutor, historyRepository);
        testBloatware(actionExecutor, historyRepository);

        section("Historico completo de acoes (mais recente primeiro)");
        try {
            historyRepository.printRecentHistory(30);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao ler historico de acoes: " + e.getMessage());
        }

        section("Fim da demonstracao da Fase 3");
    }

    // ------------------------------------------------------------------
    // 3.1) Tarefas Agendadas
    // ------------------------------------------------------------------

    private static void testTaskScheduler(ActionExecutor actionExecutor, LockManager lockManager,
                                           ActionHistoryRepository historyRepository) {
        section("3.1) TaskSchedulerScanner - listagem real + bloqueio/desativar/reverter em tarefa de TESTE");

        TaskSchedulerScanner scanner = new TaskSchedulerScanner();
        List<TaskSchedulerScanner.TaskInfo> allTasks = scanner.scan();
        System.out.println("  Total de tarefas agendadas reais encontradas: " + allTasks.size());
        allTasks.stream().limit(5).forEach(t ->
                System.out.printf("    - %-60s status=%-12s proxima=%s%n", truncate(t.name(), 60), t.status(), t.nextRunTime()));

        boolean testTaskCreated = false;
        try {
            int createExit = runAndWait("schtasks", "/create", "/tn", TEST_TASK_NAME, "/tr", "cmd.exe /c exit",
                    "/sc", "once", "/sd", "01/01/2099", "/st", "00:00", "/f");
            if (createExit != 0) {
                System.out.println("  [ABORTADO] Nao foi possivel criar a tarefa de teste (exit=" + createExit + ").");
                return;
            }
            testTaskCreated = true;
            System.out.println("  Tarefa de teste criada: " + TEST_TASK_PATH + " (agendada para 01/01/2099 - nunca executa de fato)");

            Optional<TaskSchedulerScanner.TaskInfo> testTask = scanner.findByName(TEST_TASK_PATH);
            if (testTask.isEmpty()) {
                System.out.println("  [ABORTADO] Tarefa de teste criada, mas nao encontrada pelo TaskSchedulerScanner.");
                return;
            }

            LockManager.LockResult lockResult = lockManager.lockItem(TEST_TASK_PATH, "task", "teste automatizado Fase 3");
            System.out.println("  lockItem() -> sucesso=" + lockResult.success() + " | " + lockResult.message());

            ActionExecutor.ActionResult refusedDisable = actionExecutor.disableScheduledTask(testTask.get());
            System.out.println("  disableScheduledTask() [bloqueada] -> sucesso=" + refusedDisable.success()
                    + " | " + refusedDisable.message());
            if (refusedDisable.success() || refusedDisable.backupId() != null) {
                System.out.println("  [ATENCAO] A acao deveria ter sido recusada sem criar backup.");
            } else {
                System.out.println("  [OK] Acao recusada corretamente: nenhum backup criado.");
            }

            LockManager.LockResult unlockResult = lockManager.unlockItem(TEST_TASK_PATH, "task");
            System.out.println("  unlockItem() -> sucesso=" + unlockResult.success() + " | " + unlockResult.message());

            ActionExecutor.ActionResult disableResult = actionExecutor.disableScheduledTask(testTask.get());
            System.out.println("  disableScheduledTask() [desbloqueada] -> sucesso=" + disableResult.success()
                    + " | " + disableResult.message());
            if (disableResult.backupId() == null) {
                System.out.println("  [ABORTADO] Nenhum backup foi criado - nao e seguro tentar reverter.");
                return;
            }

            Optional<TaskSchedulerScanner.TaskInfo> afterDisable = scanner.findByName(TEST_TASK_PATH);
            System.out.println("  Status apos desativar: " + afterDisable.map(TaskSchedulerScanner.TaskInfo::status).orElse("(nao encontrada)"));

            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreScheduledTask(disableResult.backupId());
            System.out.println("  restoreScheduledTask() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

            Optional<TaskSchedulerScanner.TaskInfo> afterRestore = scanner.findByName(TEST_TASK_PATH);
            System.out.println("  Status apos reverter: " + afterRestore.map(TaskSchedulerScanner.TaskInfo::status).orElse("(nao encontrada)"));
        } catch (Exception e) {
            System.err.println("  [NITRO BOOST] Erro inesperado no teste de tarefa agendada: " + e.getMessage());
        } finally {
            try {
                if (testTaskCreated) {
                    runAndWait("schtasks", "/delete", "/tn", TEST_TASK_NAME, "/f");
                    System.out.println("  [LIMPEZA] Tarefa de teste removida.");
                }
            } catch (Exception ignored) {
                // limpeza de teste - falha aqui nao importa para o resultado da demonstracao
            }
        }
    }

    // ------------------------------------------------------------------
    // 3.2) Plano de Energia
    // ------------------------------------------------------------------

    private static void testPowerPlan(ActionExecutor actionExecutor, ActionHistoryRepository historyRepository) {
        section("3.2) PowerPlanScanner - listagem real + trocar/reverter usando um plano de TESTE proprio");

        PowerPlanScanner scanner = new PowerPlanScanner();
        List<PowerPlanScanner.PowerPlanInfo> plans = scanner.scan();
        System.out.println("  Planos de energia reais encontrados: " + plans.size());
        plans.forEach(p -> System.out.printf("    %s %-30s %s%n", p.active() ? "[ATIVO]" : "       ", p.name(), p.guid()));

        Optional<PowerPlanScanner.PowerPlanInfo> originalPlan = scanner.findActive();
        if (originalPlan.isEmpty()) {
            System.out.println("  [ABORTADO] Nao foi possivel identificar o plano de energia ativo original - "
                    + "por seguranca, nenhuma troca sera testada.");
            return;
        }
        System.out.println("  Plano original confirmado: " + originalPlan.get().name() + " (" + originalPlan.get().guid() + ")");
        System.out.println("  (esta maquina expoe apenas " + plans.size() + " plano(s) visivel(is) via 'powercfg /list' - "
                + "para testar a troca sem depender de um segundo plano real pre-existente, o teste cria um plano de "
                + "TESTE proprio, duplicado do plano ativo, e o remove ao final.)");

        String testPlanGuid = null;
        boolean restoredToOriginal = false;
        try {
            CommandOutput duplicateResult = runAndCapture("powercfg", "-duplicatescheme", originalPlan.get().guid());
            testPlanGuid = extractGuid(duplicateResult.output());
            if (testPlanGuid == null) {
                System.out.println("  [ABORTADO] Nao foi possivel criar um plano de energia de teste (saida: " + duplicateResult.output().trim() + ").");
                return;
            }
            runAndWait("powercfg", "-changename", testPlanGuid, "NitroBoostTestPlan", "Plano de teste do NITRO BOOST (Fase 3)");
            System.out.println("  Plano de teste criado: NitroBoostTestPlan (" + testPlanGuid + ")");

            ActionExecutor.ActionResult switchResult = actionExecutor.switchPowerPlan(testPlanGuid, "NitroBoostTestPlan");
            System.out.println("  switchPowerPlan() -> sucesso=" + switchResult.success() + " | " + switchResult.message());
            if (switchResult.backupId() == null) {
                System.out.println("  [ABORTADO] Nenhum backup foi criado - nao e seguro continuar.");
                return;
            }

            Optional<PowerPlanScanner.PowerPlanInfo> afterSwitch = scanner.findActive();
            System.out.println("  Plano ativo apos a troca: " + afterSwitch.map(p -> p.name() + " (" + p.guid() + ")").orElse("(desconhecido)"));

            ActionExecutor.ActionResult restoreResult = actionExecutor.restorePowerPlan(switchResult.backupId());
            System.out.println("  restorePowerPlan() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

            Optional<PowerPlanScanner.PowerPlanInfo> afterRestore = scanner.findActive();
            restoredToOriginal = afterRestore.isPresent() && afterRestore.get().guid().equalsIgnoreCase(originalPlan.get().guid());
            System.out.println("  Plano ativo apos reverter: " + afterRestore.map(p -> p.name() + " (" + p.guid() + ")").orElse("(desconhecido)"));
            System.out.println("  [" + (restoredToOriginal ? "OK" : "ATENCAO") + "] Plano original " + (restoredToOriginal ? "confirmado restaurado" : "NAO foi confirmado restaurado") + ".");
        } catch (Exception e) {
            System.err.println("  [NITRO BOOST] Erro inesperado no teste de plano de energia: " + e.getMessage());
        } finally {
            // Rede de seguranca final: garante que o plano ATIVO volta a ser o original, mesmo se
            // algo tiver falhado acima, antes de remover o plano de teste.
            try {
                if (!restoredToOriginal) {
                    runAndWait("powercfg", "/setactive", originalPlan.get().guid());
                    Optional<PowerPlanScanner.PowerPlanInfo> finalCheck = scanner.findActive();
                    System.out.println("  [REDE DE SEGURANCA] Forcado 'powercfg /setactive' de volta ao plano original. Ativo agora: "
                            + finalCheck.map(p -> p.name() + " (" + p.guid() + ")").orElse("(desconhecido)"));
                }
                if (testPlanGuid != null) {
                    runAndWait("powercfg", "-delete", testPlanGuid);
                    System.out.println("  [LIMPEZA] Plano de energia de teste removido.");
                }
            } catch (Exception ignored) {
                // limpeza de teste - falha aqui nao importa para o resultado da demonstracao
            }
        }
    }

    // ------------------------------------------------------------------
    // 3.3) Telemetria
    // ------------------------------------------------------------------

    private static void testTelemetry(ActionExecutor actionExecutor, ActionHistoryRepository historyRepository) {
        section("3.3) TelemetryScanner - leitura real das 7 chaves conhecidas + alterar/reverter em chave de TESTE");

        TelemetryScanner scanner = new TelemetryScanner();
        List<TelemetryScanner.TelemetryKeyInfo> keys = scanner.scan();
        for (TelemetryScanner.TelemetryKeyInfo info : keys) {
            System.out.printf("    - %-48s valor=%-10s existe=%s%n",
                    info.definition().friendlyName(), info.exists() ? info.currentValue() : "(nao definido)", info.exists());
        }

        System.out.println("  (o teste de alterar+reverter roda apenas contra uma chave de TESTE propria em "
                + TEST_TELEMETRY_REGISTRY_PATH + " - nunca uma chave real de telemetria do sistema)");

        TelemetryScanner.TelemetryKeyDefinition testDefinition = new TelemetryScanner.TelemetryKeyDefinition(
                "test_telemetry_flag_fase3", "Flag de Teste (Fase 3)", TEST_TELEMETRY_REGISTRY_PATH, TEST_TELEMETRY_VALUE_NAME,
                "Valor de registro fabricado apenas para validar o fluxo de alterar/reverter com seguranca.");

        try {
            TelemetryScanner.TelemetryKeyInfo beforeAny = scanner.readValue(testDefinition);
            System.out.println("  Estado inicial da chave de teste: existe=" + beforeAny.exists());

            ActionExecutor.ActionResult setResult = actionExecutor.setTelemetryValue(testDefinition, "1");
            System.out.println("  setTelemetryValue(1) -> sucesso=" + setResult.success() + " | " + setResult.message());
            if (setResult.backupId() == null) {
                System.out.println("  [ABORTADO] Nenhum backup foi criado - nao e seguro continuar.");
                return;
            }

            TelemetryScanner.TelemetryKeyInfo afterSet = scanner.readValue(testDefinition);
            System.out.println("  Valor apos alterar: " + afterSet.currentValue() + " (existe=" + afterSet.exists() + ")");

            ActionExecutor.ActionResult restoreResult = actionExecutor.restoreTelemetryValue(setResult.backupId());
            System.out.println("  restoreTelemetryValue() -> sucesso=" + restoreResult.success() + " | " + restoreResult.message());

            TelemetryScanner.TelemetryKeyInfo afterRestore = scanner.readValue(testDefinition);
            System.out.println("  Estado apos reverter: existe=" + afterRestore.exists()
                    + " (esperado=false, pois a chave de teste nao existia antes do teste)");
        } catch (Exception e) {
            System.err.println("  [NITRO BOOST] Erro inesperado no teste de telemetria: " + e.getMessage());
        } finally {
            try {
                // Rede de seguranca extra: garante que a arvore de teste inteira e removida do
                // registro, mesmo que algo tenha falhado acima.
                runAndWait("reg", "delete", TEST_TELEMETRY_REGISTRY_PATH, "/f");
            } catch (Exception ignored) {
                // pode falhar se a chave ja nao existir - sem problema
            }
        }
    }

    // ------------------------------------------------------------------
    // 3.4) Bloatware
    // ------------------------------------------------------------------

    private static void testBloatware(ActionExecutor actionExecutor, ActionHistoryRepository historyRepository) {
        section("3.4) BloatwareScanner - listagem real de apps UWP + validacao SEGURA (modo -WhatIf) do metodo de desinstalar");

        BloatwareScanner scanner = new BloatwareScanner();
        List<BloatwareScanner.AppxInfo> allApps = scanner.scan();
        List<BloatwareScanner.AppxInfo> known = allApps.stream().filter(a -> a.category() != BloatwareScanner.Category.UNKNOWN).toList();
        System.out.println("  Total de apps UWP instalados (usuario atual): " + allApps.size());
        System.out.println("  Apps reconhecidos como bloatware conhecido: " + known.size());
        known.forEach(a -> System.out.printf("    [%-16s] %s%n", a.category(), a.name()));

        if (known.isEmpty()) {
            System.out.println("  [INFO] Nenhum app de bloatware conhecido encontrado nesta maquina - nada para validar.");
            return;
        }

        BloatwareScanner.AppxInfo target = known.get(0);
        System.out.println("  App escolhido para validacao SEGURA (nunca desinstalado de verdade): " + target.name());
        System.out.println("  DECISAO DE SEGURANCA: por regra explicita da Fase 3, nenhum app UWP e desinstalado de "
                + "verdade neste teste automatizado. O metodo uninstallBloatwareApp() e chamado com whatIf=true, que "
                + "adiciona '-WhatIf' ao comando PowerShell - o comando roda de verdade e valida que a sintaxe/pacote "
                + "estao corretos, mas o PowerShell garante que NADA e alterado no sistema.");

        try {
            ActionExecutor.ActionResult whatIfResult = actionExecutor.uninstallBloatwareApp(target, false, true);
            System.out.println("  uninstallBloatwareApp(whatIf=true) -> sucesso=" + whatIfResult.success() + " | " + whatIfResult.message());

            List<BloatwareScanner.AppxInfo> afterAttempt = scanner.scan();
            boolean stillInstalled = afterAttempt.stream().anyMatch(a -> a.packageFullName().equals(target.packageFullName()));
            System.out.println("  [" + (stillInstalled ? "OK" : "ATENCAO") + "] App '" + target.name() + "' "
                    + (stillInstalled ? "continua instalado" : "NAO esta mais instalado") + " apos a simulacao.");
        } catch (Exception e) {
            System.err.println("  [NITRO BOOST] Erro inesperado no teste de bloatware: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    private record CommandOutput(int exitCode, String output) {
    }

    private static int runAndWait(String... command) throws Exception {
        return runAndCapture(command).exitCode();
    }

    private static CommandOutput runAndCapture(String... command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandOutput(-1, output);
        }
        return new CommandOutput(process.exitValue(), output);
    }

    private static String extractGuid(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
