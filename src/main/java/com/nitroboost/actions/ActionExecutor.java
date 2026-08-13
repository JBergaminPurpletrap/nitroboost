package com.nitroboost.actions;

import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.BloatwareScanner;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.GamingScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.core.PowerPlanScanner;
import com.nitroboost.core.ProcessScanner;
import com.nitroboost.core.RegistryValueUtils;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.core.StartupScanner;
import com.nitroboost.core.TaskSchedulerScanner;
import com.nitroboost.core.TelemetryScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.db.ItemRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Executa acoes reais que alteram o estado do sistema (matar processo,
 * parar/desativar servico, desativar item de startup) e as respectivas
 * reversoes a partir de um backup.
 *
 * Regras de ouro aplicadas em TODO metodo publico desta classe:
 *  1) nunca interage com o SO sem tratamento de erro (try/catch) - falha de
 *     permissao (comum quando o app nao roda como Administrador nesta fase
 *     de desenvolvimento) e reportada de forma graciosa, nunca derruba a
 *     aplicacao;
 *  2) nunca executa uma acao destrutiva sem antes salvar o estado atual via
 *     {@link BackupManager#snapshotBeforeAction};
 *  3) toda acao (sucesso ou falha) e gravada em {@code actions_history}.
 *
 * IMPORTANTE (seguranca): {@link #killProcess(int)} so deve ser usado, nesta
 * fase do projeto, contra processos de teste iniciados pelo proprio
 * desenvolvedor - nunca contra processos essenciais do Windows real.
 */
public class ActionExecutor {

    public record ActionResult(boolean success, String message, Long backupId) {
    }

    private static final int COMMAND_TIMEOUT_SECONDS = 20;

    private final BackupManager backupManager;
    private final ActionHistoryRepository historyRepository;
    private final ItemRepository itemRepository;
    private final LockManager lockManager;
    private final ProcessScanner processScanner = new ProcessScanner();
    private final ServiceScanner serviceScanner = new ServiceScanner();
    private final PowerPlanScanner powerPlanScanner = new PowerPlanScanner();
    private final TelemetryScanner telemetryScanner = new TelemetryScanner();
    private final PerformanceScanner performanceScanner = new PerformanceScanner();
    private final GamingScanner gamingScanner = new GamingScanner();
    private final AiFeatureScanner aiFeatureScanner = new AiFeatureScanner();
    private final ConsumerFeatureScanner consumerFeatureScanner = new ConsumerFeatureScanner();
    private final BloatwareScanner bloatwareScanner = new BloatwareScanner();
    private final StartupScanner startupScanner = new StartupScanner();
    private final TaskSchedulerScanner taskSchedulerScanner = new TaskSchedulerScanner();

    /** DISM pode demorar bem mais que os outros comandos (reg/schtasks/powercfg) - timeout maior e explicito. */
    private static final int DISM_TIMEOUT_SECONDS = 120;

    /** Nome fixo de catalogo para o "conceito" de plano de energia ativo (so existe um por vez). */
    private static final String POWER_PLAN_ITEM_NAME = "ActivePowerPlan";

    /** Nome fixo de catalogo para o "conceito" de arquivo de hibernacao (so existe um por vez). */
    private static final String HIBERNATION_ITEM_NAME = "Arquivo de Hibernacao";

    public ActionExecutor(DatabaseManager databaseManager) {
        this.backupManager = new BackupManager(databaseManager);
        this.historyRepository = new ActionHistoryRepository(databaseManager);
        this.itemRepository = new ItemRepository(databaseManager);
        this.lockManager = new LockManager(databaseManager);
    }

    // ------------------------------------------------------------------
    // Processos
    // ------------------------------------------------------------------

    /** Finaliza um processo pelo PID, com backup previo e registro no historico. */
    public ActionResult killProcess(int pid) {
        String itemType = "process";
        Optional<ProcessScanner.ProcessInfo> processInfo = processScanner.findByPid(pid);
        String itemName = processInfo.map(ProcessScanner.ProcessInfo::name).orElse("pid-" + pid);

        Long itemId = upsertItemQuiet(itemName, itemType, null, "running");

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, itemName, itemType, "kill", "running");
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("pid", pid);
            snapshot.put("name", itemName);
            processInfo.ifPresent(p -> {
                snapshot.put("commandLine", p.commandLine());
                snapshot.put("arguments", p.arguments());
            });
            backupId = backupManager.snapshotBeforeAction(itemId, itemName, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de matar processo PID " + pid + ": " + e.getMessage());
        }

        try {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            boolean destroyed = false;
            if (handle.isPresent()) {
                destroyed = handle.get().destroy();
                if (!destroyed) {
                    destroyed = handle.get().destroyForcibly();
                }
            }
            String message = destroyed
                    ? "Processo '" + itemName + "' (PID " + pid + ") finalizado com sucesso."
                    : "Processo PID " + pid + " nao encontrado ou ja havia finalizado.";
            recordHistory(itemId, itemName, itemType, "kill", "running", destroyed ? "killed" : "unknown", destroyed, destroyed ? null : message, backupId);
            return new ActionResult(destroyed, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao tentar finalizar processo PID " + pid + ": " + e.getMessage();
            recordHistory(itemId, itemName, itemType, "kill", "running", null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /**
     * Reverte um "kill" de processo relancando o mesmo executavel com os
     * mesmos argumentos, a partir do snapshot salvo no backup. So funciona
     * quando o backup capturou os argumentos do processo - por isso a regra
     * de seguranca do projeto de testar kill/restore apenas em processos de
     * teste que sabemos exatamente como relancar.
     */
    @SuppressWarnings("unchecked")
    public ActionResult restoreProcess(long backupId) {
        String itemType = "process";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            List<String> arguments = (List<String>) backup.stateSnapshot().get("arguments");

            if (arguments == null || arguments.isEmpty()) {
                String message = "Nao foi possivel reverter: backup nao possui a linha de comando do processo.";
                recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "killed", null, false, message, null);
                return new ActionResult(false, message, backupId);
            }

            Process relaunched = new ProcessBuilder(arguments).start();
            long newPid = relaunched.pid();

            backupManager.markRestored(backupId);
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "killed",
                    "running (novo PID " + newPid + ")", true, null, null);

            return new ActionResult(true,
                    "Processo '" + backup.itemName() + "' relancado com sucesso (novo PID " + newPid + ").", backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao tentar restaurar processo a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Servicos
    // ------------------------------------------------------------------

    public ActionResult stopService(String serviceName) {
        return runServiceAction(serviceName, "stop", "Stop-Service -Name '%s' -Force -ErrorAction Stop");
    }

    public ActionResult disableService(String serviceName) {
        return runServiceAction(serviceName, "disable", "Set-Service -Name '%s' -StartupType Disabled -ErrorAction Stop");
    }

    /** Reabilita um servico com um tipo de inicializacao especifico (usado em restores). */
    public ActionResult enableService(String serviceName, String startupType) {
        String safeStartupType = (startupType == null || startupType.isBlank()) ? "Manual" : startupType;
        return runServiceAction(serviceName, "enable", "Set-Service -Name '%s' -StartupType " + safeStartupType + " -ErrorAction Stop");
    }

    private ActionResult runServiceAction(String serviceName, String actionType, String scriptTemplate) {
        String itemType = "service";
        Optional<ServiceScanner.ServiceInfo> before = serviceScanner.findByName(serviceName);
        String previousState = before.map(s -> s.state() + "/" + s.startMode()).orElse("desconhecido");
        Long itemId = upsertItemQuiet(serviceName, itemType, null, previousState);

        // Bloqueio so se aplica a acoes que desativam/param o servico - "enable" (reativacao,
        // usada em restores) deve sempre ser permitido, mesmo que o item esteja bloqueado.
        if (!"enable".equals(actionType)) {
            Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, serviceName, itemType, actionType, previousState);
            if (lockRefusal.isPresent()) {
                return lockRefusal.get();
            }
        }

        // "enable" so acontece durante um restore (que ja aponta para um backup de origem),
        // entao nao criamos um novo backup nesse caso especifico.
        Long backupId = null;
        if (!"enable".equals(actionType)) {
            try {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("name", serviceName);
                before.ifPresent(s -> {
                    snapshot.put("state", s.state());
                    snapshot.put("startMode", s.startMode());
                });
                backupId = backupManager.snapshotBeforeAction(itemId, serviceName, itemType, snapshot);
            } catch (SQLException e) {
                System.err.println("[NITRO BOOST] Falha ao criar backup antes de alterar servico '" + serviceName + "': " + e.getMessage());
            }
        }

        try {
            String script = String.format(scriptTemplate, escapePowerShellSingleQuoted(serviceName));
            CommandResult result = runPowerShell(script);
            boolean commandSucceeded = result.exitCode() == 0;
            String commandMessage = commandSucceeded
                    ? "Acao '" + actionType + "' aplicada com sucesso ao servico '" + serviceName + "'."
                    : "Falha ao aplicar acao '" + actionType + "' ao servico '" + serviceName + "' "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            ActionResult verified = verifyServiceAction(actionType, serviceName, commandSucceeded, commandMessage, backupId);

            recordHistory(itemId, serviceName, itemType, actionType, previousState, verified.success() ? actionType : null,
                    verified.success(), verified.success() ? null : verified.message(), backupId);
            return verified;
        } catch (Exception e) {
            String errorMessage = "Erro ao executar acao '" + actionType + "' no servico '" + serviceName + "': " + e.getMessage();
            recordHistory(itemId, serviceName, itemType, actionType, previousState, null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /**
     * Confirma por releitura ({@link ServiceScanner#findByName}) as acoes "disable" (startMode
     * deve virar "Disabled") e "stop" (state deve virar "Stopped") - os dois casos onde uma falha
     * silenciosa (comando diz sucesso, mas nada mudou de verdade) importa de verdade. "enable" NAO
     * e verificado aqui de proposito: so acontece durante um restore, com o tipo de inicializacao
     * pedido variavel (extraido dinamicamente do {@code scriptTemplate}), e o passo seguinte de
     * {@link #restoreService} ja tenta iniciar o servico, o que exercitaria o servico de qualquer
     * forma - mantido no padrao antigo (so codigo de saida) por simplicidade.
     */
    private ActionResult verifyServiceAction(String actionType, String serviceName, boolean commandSucceeded,
                                              String commandMessage, Long backupId) {
        boolean checkStartMode = "disable".equals(actionType);
        boolean checkState = "stop".equals(actionType);
        if (!checkStartMode && !checkState) {
            return new ActionResult(commandSucceeded, commandMessage, backupId);
        }
        String expected = checkStartMode ? "Disabled" : "Stopped";
        return verifyPostAction(commandSucceeded, commandMessage, backupId,
                () -> serviceScanner.findByName(serviceName)
                        .map(checkStartMode ? ServiceScanner.ServiceInfo::startMode : ServiceScanner.ServiceInfo::state)
                        .orElseThrow(() -> new IllegalStateException("Servico '" + serviceName + "' nao encontrado na releitura")),
                actual -> expected.equalsIgnoreCase(actual),
                expected);
    }

    public ActionResult restoreService(long backupId) {
        String itemType = "service";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            Object startModeObj = backup.stateSnapshot().get("startMode");
            Object stateObj = backup.stateSnapshot().get("state");
            String startMode = startModeObj != null ? startModeObj.toString() : "Manual";

            ActionResult enableResult = enableService(backup.itemName(), startMode);
            if (!enableResult.success()) {
                return enableResult;
            }
            if ("Running".equalsIgnoreCase(String.valueOf(stateObj))) {
                runPowerShell(String.format("Start-Service -Name '%s' -ErrorAction Stop",
                        escapePowerShellSingleQuoted(backup.itemName())));
            }

            backupManager.markRestored(backupId);
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "disabled", startMode, true, null, null);
            return new ActionResult(true, "Servico '" + backup.itemName() + "' restaurado para o estado anterior (" + startMode + ").", backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar servico a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Startup
    // ------------------------------------------------------------------

    /**
     * Desativa um item de inicializacao. Nunca de forma irreversivel:
     *  - valor de registro: remove o valor (reg delete), mas o backup guarda
     *    nome/tipo/dado originais para poder recriar exatamente igual;
     *  - arquivo na pasta de Startup: renomeia adicionando o sufixo
     *    ".nitroboost-disabled" (o Windows deixa de executa-lo, mas o
     *    arquivo continua no disco e pode ser renomeado de volta).
     */
    public ActionResult disableStartupItem(StartupScanner.StartupItemInfo item) {
        String itemType = "startup";
        Long itemId = upsertItemQuiet(item.name(), itemType, null, "enabled");

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, item.name(), itemType, "disable", "enabled");
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("name", item.name());
            snapshot.put("command", item.command());
            snapshot.put("source", item.source().name());
            if (item.registryKey() != null) {
                snapshot.put("registryKey", item.registryKey());
                snapshot.put("regType", item.regType());
            }
            if (item.filePath() != null) {
                snapshot.put("filePath", item.filePath().toString());
            }
            backupId = backupManager.snapshotBeforeAction(itemId, item.name(), itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de desativar item de startup '" + item.name() + "': " + e.getMessage());
            backupId = null;
        }

        try {
            boolean success;
            String message;
            if (item.registryKey() != null) {
                CommandResult result = runCommand("reg", "delete", item.registryKey(), "/v", item.name(), "/f");
                boolean commandSucceeded = result.exitCode() == 0;
                String commandMessage = commandSucceeded
                        ? "Entrada de registro '" + item.name() + "' removida de " + item.registryKey() + "."
                        : "Falha ao remover entrada de registro '" + item.name() + "' "
                            + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

                ActionResult verified = verifyPostAction(commandSucceeded, commandMessage, backupId,
                        () -> startupItemStillPresent(item.name(), item.registryKey()) ? "presente" : "ausente",
                        actual -> "ausente".equals(actual),
                        "ausente");
                success = verified.success();
                message = verified.message();
            } else if (item.filePath() != null) {
                Path original = item.filePath();
                Path disabled = original.resolveSibling(original.getFileName() + ".nitroboost-disabled");
                Files.move(original, disabled);
                success = true;
                message = "Atalho de startup '" + item.name() + "' desativado (renomeado para " + disabled.getFileName() + ").";
            } else {
                success = false;
                message = "Item de startup sem origem reconhecida (nem registro nem arquivo).";
            }

            recordHistory(itemId, item.name(), itemType, "disable", "enabled", success ? "disabled" : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao desativar item de startup '" + item.name() + "': " + e.getMessage();
            recordHistory(itemId, item.name(), itemType, "disable", "enabled", null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /**
     * Releitura de confirmacao para {@link #disableStartupItem} (caso de registro): reaproveita
     * {@link StartupScanner#scan()} (mesma varredura completa ja usada para exibir os itens de
     * startup na UI) para checar se o item ainda aparece pelo mesmo nome/chave de registro.
     */
    private boolean startupItemStillPresent(String name, String registryKey) {
        return startupScanner.scan().stream()
                .anyMatch(i -> registryKey.equalsIgnoreCase(i.registryKey()) && name.equalsIgnoreCase(i.name()));
    }

    public ActionResult restoreStartupItem(long backupId) {
        String itemType = "startup";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            Map<String, Object> snapshot = backup.stateSnapshot();

            boolean success;
            String message;
            if (snapshot.containsKey("registryKey")) {
                String registryKey = String.valueOf(snapshot.get("registryKey"));
                String regType = String.valueOf(snapshot.getOrDefault("regType", "REG_SZ"));
                String data = String.valueOf(snapshot.get("command"));
                CommandResult result = runCommand("reg", "add", registryKey, "/v", backup.itemName(),
                        "/t", regType, "/d", data, "/f");
                success = result.exitCode() == 0;
                message = success
                        ? "Entrada de registro '" + backup.itemName() + "' recriada em " + registryKey + "."
                        : "Falha ao recriar entrada de registro '" + backup.itemName() + "': " + result.output().trim();
            } else if (snapshot.containsKey("filePath")) {
                Path original = Path.of(String.valueOf(snapshot.get("filePath")));
                Path disabled = original.resolveSibling(original.getFileName() + ".nitroboost-disabled");
                if (Files.exists(disabled)) {
                    Files.move(disabled, original);
                    success = true;
                    message = "Atalho de startup '" + backup.itemName() + "' reativado.";
                } else {
                    success = false;
                    message = "Arquivo desativado nao encontrado em " + disabled + " (pode ja ter sido restaurado).";
                }
            } else {
                success = false;
                message = "Backup nao possui informacao suficiente para restaurar o item de startup.";
            }

            if (success) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "disabled", success ? "enabled" : null, success, success ? null : message, null);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar item de startup a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Tarefas Agendadas (Task Scheduler)
    // ------------------------------------------------------------------

    /** Desativa uma tarefa agendada via {@code schtasks /change /disable}, com backup previo. */
    public ActionResult disableScheduledTask(com.nitroboost.core.TaskSchedulerScanner.TaskInfo task) {
        String itemType = "task";
        String itemName = task.name();
        Long itemId = upsertItemQuiet(itemName, itemType, null, task.status());

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, itemName, itemType, "disable", task.status());
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("name", itemName);
            snapshot.put("status", task.status());
            snapshot.put("nextRunTime", task.nextRunTime());
            backupId = backupManager.snapshotBeforeAction(itemId, itemName, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de desativar a tarefa '" + itemName + "': " + e.getMessage());
        }

        try {
            CommandResult result = runCommand("schtasks", "/change", "/tn", itemName, "/disable");
            boolean commandSucceeded = result.exitCode() == 0;
            String commandMessage = commandSucceeded
                    ? "Tarefa agendada '" + itemName + "' desativada com sucesso."
                    : "Falha ao desativar a tarefa '" + itemName + "' "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            ActionResult verified = verifyPostAction(commandSucceeded, commandMessage, backupId,
                    () -> taskSchedulerScanner.scan().stream()
                            .filter(t -> itemName.equalsIgnoreCase(t.name()))
                            .findFirst()
                            .map(TaskSchedulerScanner.TaskInfo::status)
                            .orElseThrow(() -> new IllegalStateException("Tarefa '" + itemName + "' nao encontrada na releitura")),
                    actual -> "Disabled".equalsIgnoreCase(actual),
                    "Disabled");

            recordHistory(itemId, itemName, itemType, "disable", task.status(), verified.success() ? "Disabled" : null,
                    verified.success(), verified.success() ? null : verified.message(), backupId);
            return verified;
        } catch (Exception e) {
            String errorMessage = "Erro ao desativar a tarefa agendada '" + itemName + "': " + e.getMessage();
            recordHistory(itemId, itemName, itemType, "disable", task.status(), null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /** Reabilita uma tarefa agendada pelo nome (usado em restores - nunca recusado por bloqueio). */
    public ActionResult enableScheduledTask(String taskName) {
        try {
            CommandResult result = runCommand("schtasks", "/change", "/tn", taskName, "/enable");
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Tarefa agendada '" + taskName + "' reativada com sucesso."
                    : "Falha ao reativar a tarefa '" + taskName + "': " + result.output().trim();
            return new ActionResult(success, message, null);
        } catch (Exception e) {
            String errorMessage = "Erro ao reativar a tarefa agendada '" + taskName + "': " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, null);
        }
    }

    public ActionResult restoreScheduledTask(long backupId) {
        String itemType = "task";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();

            ActionResult enableResult = enableScheduledTask(backup.itemName());
            if (enableResult.success()) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "Disabled",
                    enableResult.success() ? "Ready" : null, enableResult.success(), enableResult.success() ? null : enableResult.message(), null);
            return new ActionResult(enableResult.success(),
                    "Tarefa agendada '" + backup.itemName() + "' restaurada (reativada).", backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar tarefa agendada a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Plano de Energia
    // ------------------------------------------------------------------

    /** Troca o plano de energia ativo, com backup do plano anterior. */
    public ActionResult switchPowerPlan(String targetGuid, String targetName) {
        String itemType = "powerplan";
        Optional<PowerPlanScanner.PowerPlanInfo> before = powerPlanScanner.findActive();
        String previousState = before.map(p -> p.name() + " (" + p.guid() + ")").orElse("desconhecido");
        Long itemId = upsertItemQuiet(POWER_PLAN_ITEM_NAME, itemType, null, previousState);

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, POWER_PLAN_ITEM_NAME, itemType, "switch", previousState);
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            before.ifPresent(p -> {
                snapshot.put("guid", p.guid());
                snapshot.put("name", p.name());
            });
            backupId = backupManager.snapshotBeforeAction(itemId, POWER_PLAN_ITEM_NAME, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de trocar o plano de energia: " + e.getMessage());
        }

        try {
            CommandResult result = runCommand("powercfg", "/setactive", targetGuid);
            boolean commandSucceeded = result.exitCode() == 0;
            String commandMessage = commandSucceeded
                    ? "Plano de energia alterado para '" + targetName + "'."
                    : "Falha ao trocar o plano de energia para '" + targetName + "': " + result.output().trim();

            ActionResult verified = verifyPostAction(commandSucceeded, commandMessage, backupId,
                    () -> powerPlanScanner.findActive()
                            .map(PowerPlanScanner.PowerPlanInfo::guid)
                            .orElseThrow(() -> new IllegalStateException("Nenhum plano de energia ativo encontrado na releitura")),
                    actual -> actual.equalsIgnoreCase(targetGuid),
                    targetGuid);

            recordHistory(itemId, POWER_PLAN_ITEM_NAME, itemType, "switch", previousState,
                    verified.success() ? targetName + " (" + targetGuid + ")" : null,
                    verified.success(), verified.success() ? null : verified.message(), backupId);
            return verified;
        } catch (Exception e) {
            String errorMessage = "Erro ao trocar o plano de energia: " + e.getMessage();
            recordHistory(itemId, POWER_PLAN_ITEM_NAME, itemType, "switch", previousState, null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    public ActionResult restorePowerPlan(long backupId) {
        String itemType = "powerplan";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            Object guidObj = backup.stateSnapshot().get("guid");
            Object nameObj = backup.stateSnapshot().get("name");
            if (guidObj == null) {
                String message = "Backup nao possui o plano de energia anterior (nenhum plano ativo foi detectado no momento da troca).";
                recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "switched", null, false, message, null);
                return new ActionResult(false, message, backupId);
            }

            CommandResult result = runCommand("powercfg", "/setactive", guidObj.toString());
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Plano de energia restaurado para '" + nameObj + "'."
                    : "Falha ao restaurar o plano de energia: " + result.output().trim();

            if (success) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "switched",
                    success ? nameObj + " (" + guidObj + ")" : null, success, success ? null : message, null);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar plano de energia a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Telemetria (chaves de registro)
    // ------------------------------------------------------------------

    /** Altera um valor de telemetria conhecido, com backup previo do valor atual (ou de sua ausencia). */
    public ActionResult setTelemetryValue(TelemetryScanner.TelemetryKeyDefinition definition, String newValue) {
        String itemType = "telemetry";
        // friendlyName (nao id) e a chave canonica do item no projeto inteiro: a UI
        // (SystemScanTask.scanTelemetry, SystemAuditEngine.auditTelemetry) sempre exibiu e gravou o
        // bloqueio sob esse nome. Enquanto aqui se usava definition.id(), o lock gravado pela tela
        // nunca era encontrado por refuseIfLocked - ou seja, bloquear um item de Telemetria pela
        // interface NAO impedia a alteracao (lock orfao). Ver ActionExecutorTelemetryLockTest.
        String itemName = definition.friendlyName();
        TelemetryScanner.TelemetryKeyInfo before = telemetryScanner.readValue(definition);
        String previousState = before.exists() ? before.currentValue() : "nao definido";
        Long itemId = upsertItemQuiet(itemName, itemType, null, previousState);

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, itemName, itemType, "set", previousState);
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("registryPath", definition.registryPath());
            snapshot.put("valueName", definition.valueName());
            snapshot.put("existed", before.exists());
            if (before.exists()) {
                snapshot.put("previousValue", before.currentValue());
            }
            backupId = backupManager.snapshotBeforeAction(itemId, itemName, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de alterar '" + definition.friendlyName() + "': " + e.getMessage());
        }

        try {
            CommandResult result = runCommand("reg", "add", definition.registryPath(),
                    "/v", definition.valueName(), "/t", "REG_DWORD", "/d", newValue, "/f");
            boolean commandSucceeded = result.exitCode() == 0;
            String commandMessage = commandSucceeded
                    ? "Valor de '" + definition.friendlyName() + "' alterado para " + newValue + "."
                    : "Falha ao alterar '" + definition.friendlyName() + "' "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            ActionResult verified = verifyPostAction(commandSucceeded, commandMessage, backupId,
                    () -> queryRegistryDwordValue(definition.registryPath(), definition.valueName()).orElse("nao definido"),
                    actual -> RegistryValueUtils.dwordValuesEqual(actual, newValue),
                    newValue);

            recordHistory(itemId, itemName, itemType, "set", previousState, verified.success() ? newValue : null,
                    verified.success(), verified.success() ? null : verified.message(), backupId);
            return verified;
        } catch (Exception e) {
            String errorMessage = "Erro ao alterar valor de telemetria '" + definition.friendlyName() + "': " + e.getMessage();
            recordHistory(itemId, itemName, itemType, "set", previousState, null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    public ActionResult restoreTelemetryValue(long backupId) {
        String itemType = "telemetry";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            Map<String, Object> snapshot = backup.stateSnapshot();
            String registryPath = String.valueOf(snapshot.get("registryPath"));
            String valueName = String.valueOf(snapshot.get("valueName"));
            boolean existed = Boolean.TRUE.equals(snapshot.get("existed"));

            CommandResult result;
            if (existed) {
                String previousValue = String.valueOf(snapshot.get("previousValue"));
                // O valor lido de "reg query" vem em formato hexadecimal (ex: "0x1") - "reg add"
                // com REG_DWORD aceita tanto decimal quanto hexadecimal (0x...) como dado, entao
                // podemos recriar exatamente o valor original sem precisar converter.
                result = runCommand("reg", "add", registryPath, "/v", valueName, "/t", "REG_DWORD", "/d", previousValue, "/f");
            } else {
                result = runCommand("reg", "delete", registryPath, "/v", valueName, "/f");
            }
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Valor de telemetria '" + backup.itemName() + "' restaurado ao estado anterior."
                    : "Falha ao restaurar valor de telemetria '" + backup.itemName() + "': " + result.output().trim();

            if (success) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "set", success ? "restored" : null, success, success ? null : message, null);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar valor de telemetria a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Bloatware (apps UWP)
    // ------------------------------------------------------------------

    /**
     * Desinstala um app UWP/Appx conhecido como bloatware, com backup previo
     * (nome do pacote e pasta de instalacao, usados em uma tentativa de
     * restore "melhor esforco" - ver {@link #restoreBloatwareApp(long)}).
     *
     * @param whatIf quando {@code true}, adiciona {@code -WhatIf} ao comando PowerShell:
     *               o comando roda de verdade, mas o PowerShell apenas relata o que SERIA
     *               feito, sem alterar nada no sistema - usado para validar com seguranca
     *               que o comando esta correto, sem desinstalar nenhum app de verdade
     *               (ver regra de seguranca da Fase 3 no BLOCKERS.md/PROGRESS.md).
     */
    public ActionResult uninstallBloatwareApp(BloatwareScanner.AppxInfo app, boolean allUsers, boolean whatIf) {
        return performAppxUninstall("bloatware", app.name(), app, allUsers, whatIf);
    }

    /**
     * Mecanica compartilhada por {@link #uninstallBloatwareApp} e {@link #uninstallAiFeatureApp}
     * (Fase 8 - Ajuste): as duas sao, por baixo, o mesmo {@code Remove-AppxPackage} ja validado
     * desde a Fase 4 - extraido aqui para a acao de "Desinstalar" do Windows Copilot nao duplicar
     * essa logica, sem alterar o comportamento ja testado de {@code uninstallBloatwareApp}.
     */
    private ActionResult performAppxUninstall(String itemType, String itemName, BloatwareScanner.AppxInfo app,
                                                boolean allUsers, boolean whatIf) {
        Long itemId = upsertItemQuiet(itemName, itemType, null, "installed");

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, itemName, itemType, "uninstall", "installed");
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("name", itemName);
            snapshot.put("packageFullName", app.packageFullName());
            snapshot.put("installLocation", app.installLocation());
            snapshot.put("allUsers", allUsers);
            backupId = backupManager.snapshotBeforeAction(itemId, itemName, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de desinstalar o app '" + itemName + "': " + e.getMessage());
        }

        try {
            StringBuilder script = new StringBuilder("Remove-AppxPackage -Package '")
                    .append(escapePowerShellSingleQuoted(app.packageFullName())).append("'");
            if (allUsers) {
                script.append(" -AllUsers");
            }
            if (whatIf) {
                script.append(" -WhatIf");
            }
            script.append(" -ErrorAction Stop");

            CommandResult result = runPowerShell(script.toString());
            boolean commandSucceeded = result.exitCode() == 0;
            String actionLabel = whatIf ? "uninstall-simulado" : "uninstall";
            String commandMessage = commandSucceeded
                    ? (whatIf
                        ? "[SIMULACAO -WhatIf] Comando de desinstalacao valido para '" + itemName + "' - nada foi alterado. " + result.output().trim()
                        : "App '" + itemName + "' desinstalado com sucesso.")
                    : "Falha ao desinstalar app '" + itemName + "' "
                        + "(comum se o app nao estiver rodando como Administrador, quando -AllUsers e usado, ou se for "
                        + "um pacote protegido do sistema que o Windows recusa remover): " + result.output().trim();

            // -WhatIf nunca altera nada de verdade (simulacao) - releitura nao faz sentido nesse caso,
            // so no uninstall real.
            ActionResult verified = whatIf
                    ? new ActionResult(commandSucceeded, commandMessage, backupId)
                    : verifyPostAction(commandSucceeded, commandMessage, backupId,
                            () -> bloatwareScanner.scan().stream()
                                    .anyMatch(a -> app.packageFullName().equalsIgnoreCase(a.packageFullName())) ? "presente" : "ausente",
                            actual -> "ausente".equals(actual),
                            "ausente");

            recordHistory(itemId, itemName, itemType, actionLabel, "installed",
                    verified.success() ? (whatIf ? "installed (simulado)" : "uninstalled") : null,
                    verified.success(), verified.success() ? null : verified.message(), backupId);
            return verified;
        } catch (Exception e) {
            String errorMessage = "Erro ao desinstalar app '" + itemName + "': " + e.getMessage();
            recordHistory(itemId, itemName, itemType, whatIf ? "uninstall-simulado" : "uninstall", "installed", null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /**
     * Tentativa de restauracao "melhor esforco" de um app UWP desinstalado,
     * re-registrando o pacote a partir do manifesto na pasta de instalacao
     * capturada no backup. SO funciona se os arquivos do pacote ainda
     * existirem em disco (o que nao e garantido - ver nota tecnica no
     * PROGRESS.md sobre a limitacao inerente da plataforma Appx aqui).
     */
    public ActionResult restoreBloatwareApp(long backupId) {
        return performAppxRestore("bloatware", backupId);
    }

    /**
     * Mecanica compartilhada por {@link #restoreBloatwareApp} e {@link #restoreAiFeatureApp}
     * (Fase 8 - Ajuste): mesma tentativa de restauracao "melhor esforco" via re-registro do
     * manifesto Appx, extraida para nao duplicar a logica entre as duas categorias.
     */
    private ActionResult performAppxRestore(String itemType, long backupId) {
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            Object installLocationObj = backup.stateSnapshot().get("installLocation");
            String installLocation = installLocationObj == null ? "" : installLocationObj.toString();
            if (installLocation.isBlank()) {
                String message = "Nao e possivel restaurar '" + backup.itemName() + "': backup nao possui a pasta de "
                        + "instalacao original. Reinstale manualmente pela Microsoft Store, se necessario.";
                recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "uninstalled", null, false, message, null);
                return new ActionResult(false, message, backupId);
            }

            String manifestPath = installLocation + "\\AppxManifest.xml";
            String script = "if (Test-Path -LiteralPath '" + escapePowerShellSingleQuoted(manifestPath) + "') { "
                    + "Add-AppxPackage -DisableDevelopmentMode -Register '" + escapePowerShellSingleQuoted(manifestPath) + "' } "
                    + "else { throw 'Manifesto do pacote nao encontrado em disco - provavelmente removido junto com o app.' }";
            CommandResult result = runPowerShell(script);
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "App '" + backup.itemName() + "' re-registrado a partir dos arquivos originais (restauracao melhor-esforco)."
                    : "Nao foi possivel restaurar '" + backup.itemName() + "' automaticamente: " + result.output().trim()
                        + " Reinstale manualmente pela Microsoft Store, se necessario.";

            if (success) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "uninstalled", success ? "installed" : null, success, success ? null : message, null);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao tentar restaurar app UWP a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Performance/Energia (chaves de registro - Fase 9)
    // ------------------------------------------------------------------

    /** Altera um valor de performance/energia conhecido, com backup previo do valor atual (ou de sua ausencia). */
    public ActionResult setPerformanceValue(PerformanceScanner.PerformanceKeyDefinition definition, String newValue) {
        PerformanceScanner.PerformanceKeyInfo before = performanceScanner.readValue(definition);
        String previousState = before.exists() ? before.currentValue() : "nao definido";
        return applyRegistryDwordChange("performance", definition.friendlyName(), definition.friendlyName(),
                definition.registryPath(), definition.valueName(), previousState, before.exists(), newValue);
    }

    public ActionResult restorePerformanceValue(long backupId) {
        return restoreRegistryDwordChange("performance", backupId);
    }

    // ------------------------------------------------------------------
    // Jogos (chaves de registro - Fase 9)
    // ------------------------------------------------------------------

    /** Altera um valor de otimizacao para jogos conhecido, com backup previo do valor atual (ou de sua ausencia). */
    public ActionResult setGamingValue(GamingScanner.GamingKeyDefinition definition, String newValue) {
        GamingScanner.GamingKeyInfo before = gamingScanner.readValue(definition);
        String previousState = before.exists() ? before.currentValue() : "nao definido";
        return applyRegistryDwordChange("gaming", definition.friendlyName(), definition.friendlyName(),
                definition.registryPath(), definition.valueName(), previousState, before.exists(), newValue);
    }

    public ActionResult restoreGamingValue(long backupId) {
        return restoreRegistryDwordChange("gaming", backupId);
    }

    // ------------------------------------------------------------------
    // IA (chaves de registro/politica - Fase 8 Parte 2)
    // ------------------------------------------------------------------

    /** Altera um valor de recurso de IA conhecido (Copilot/Recall/Click to Do/Cocreator/Edge), com backup previo. */
    public ActionResult setAiFeatureValue(AiFeatureScanner.AiFeatureKeyDefinition definition, String newValue) {
        AiFeatureScanner.AiFeatureKeyInfo before = aiFeatureScanner.readValue(definition);
        String previousState = before.exists() ? before.currentValue() : "nao definido";
        return applyRegistryDwordChange("ai", definition.friendlyName(), definition.friendlyName(),
                definition.registryPath(), definition.valueName(), previousState, before.exists(), newValue);
    }

    public ActionResult restoreAiFeatureValue(long backupId) {
        return restoreRegistryDwordChange("ai", backupId);
    }

    // ------------------------------------------------------------------
    // IA - Desinstalar/remover de verdade (Fase 8 - Ajuste, alem de so desativar por politica)
    // ------------------------------------------------------------------

    /**
     * Desinstala o pacote Appx do Windows Copilot desta maquina, quando ele existir separadamente
     * (mesmo mecanismo de {@link #uninstallBloatwareApp} - so muda o {@code itemType} para "ai",
     * para o bloqueio/historico ficarem sob o mesmo nome usado pela acao "Desativar" deste item).
     *
     * Builds recentes do Windows integraram o Copilot ao shell/Explorer sem um pacote Appx proprio
     * (confirmado nesta maquina - ver {@code BLOCKERS.md} e {@code Phase8AiUninstallConsoleDemo}):
     * nesse caso, a acao retorna falha "nao aplicavel" de forma graciosa, sem criar backup.
     *
     * @param allUsers {@code true} remove o pacote de todos os usuarios do PC (equivalente a
     *                 "windows_copilot_allusers" - normalmente exige Administrador); {@code false}
     *                 remove so para o usuario atual (equivalente a "windows_copilot_user").
     */
    public ActionResult uninstallAiFeatureApp(AiFeatureScanner.AiFeatureKeyDefinition definition, boolean allUsers, boolean whatIf) {
        String itemType = "ai";
        String itemName = definition.friendlyName();

        Optional<BloatwareScanner.AppxInfo> appOpt = bloatwareScanner.scan().stream()
                .filter(a -> a.category() == BloatwareScanner.Category.AI_COPILOT)
                .findFirst();
        if (appOpt.isEmpty()) {
            Long itemId = upsertItemQuiet(itemName, itemType, null, "nao instalado como pacote separado");
            String message = "Nenhum pacote Appx do Windows Copilot foi encontrado nesta maquina - "
                    + "builds recentes do Windows integram o Copilot ao shell/Explorer sem um app separado "
                    + "para remover, ou o pacote ja foi desinstalado antes. Nao ha nada para desinstalar; "
                    + "use a acao 'Desativar' para remover o botao/painel via politica.";
            recordHistory(itemId, itemName, itemType, "uninstall", "nao instalado como pacote separado", null, false, message, null);
            return new ActionResult(false, message, null);
        }

        return performAppxUninstall(itemType, itemName, appOpt.get(), allUsers, whatIf);
    }

    public ActionResult restoreAiFeatureApp(long backupId) {
        return performAppxRestore("ai", backupId);
    }

    /**
     * "Desinstala" o Windows Recall desativando o recurso opcional do Windows via
     * {@code DISM /Online /Disable-Feature /FeatureName:Recall} - mecanismo alternativo a politica
     * de registro ja coberta por {@link #setAiFeatureValue} (chave {@code windows_recall}),
     * documentado desde a secao 1 do documento da Fase 8 e agora implementado como acao real (antes
     * so um tutorial manual).
     *
     * NUNCA passa {@code /Restart} para o DISM (regra de seguranca do projeto: a maquina do usuario
     * nunca e reiniciada automaticamente) - se o efeito completo exigir reinicio, isso fica explicito
     * na mensagem devolvida, para o usuario decidir quando reiniciar.
     *
     * Se o recurso opcional "Recall" nao existir nesta edicao/versao do Windows (esperado na grande
     * maioria das maquinas - Recall e exclusivo de Copilot+ PCs com NPU) ou o comando falhar por
     * falta de privilegio de Administrador (DISM exige elevacao mesmo so para consultar), a acao e
     * recusada de forma graciosa - nenhuma das duas e tratada como erro do NITRO BOOST.
     *
     * @param definition a definicao {@code windows_recall} do {@link AiFeatureScanner} - usada so
     *                    para pegar {@code friendlyName()}, que precisa ser IDENTICO ao nome usado
     *                    por {@link #setAiFeatureValue} para este mesmo item: e por esse nome que o
     *                    {@link LockManager} bloqueia o item, e as duas acoes ("Desativar" e
     *                    "Desinstalar") tem que respeitar o MESMO bloqueio.
     */
    public ActionResult disableRecallFeature(AiFeatureScanner.AiFeatureKeyDefinition definition) {
        String itemType = "ai";
        String itemName = definition.friendlyName();
        Long itemId = upsertItemQuiet(itemName, itemType, null, "desconhecido");

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, itemName, itemType, "uninstall-feature", "desconhecido");
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            backupId = backupManager.snapshotBeforeAction(itemId, itemName, itemType, new LinkedHashMap<>());
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de desativar o recurso opcional Recall: " + e.getMessage());
        }

        try {
            CommandResult result = runDism("/Online", "/Disable-Feature", "/FeatureName:Recall", "/NoRestart");
            ActionResult outcome = interpretDismResult(result, "desativar");
            recordHistory(itemId, itemName, itemType, "uninstall-feature", "desconhecido",
                    outcome.success() ? "disabled" : null, outcome.success(), outcome.success() ? null : outcome.message(), backupId);
            return new ActionResult(outcome.success(), outcome.message(), backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao chamar o DISM para desativar o recurso Recall: " + e.getMessage();
            recordHistory(itemId, itemName, itemType, "uninstall-feature", "desconhecido", null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /** Reversao do {@link #disableRecallFeature}: reativa o recurso opcional via DISM {@code /Enable-Feature}. */
    public ActionResult restoreRecallFeature(long backupId) {
        String itemType = "ai";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();

            CommandResult result = runDism("/Online", "/Enable-Feature", "/FeatureName:Recall", "/NoRestart");
            ActionResult outcome = interpretDismResult(result, "reativar");

            if (outcome.success()) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "disabled",
                    outcome.success() ? "enabled" : null, outcome.success(), outcome.success() ? null : outcome.message(), null);
            return new ActionResult(outcome.success(), outcome.message(), backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao chamar o DISM para reativar o recurso Recall a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /**
     * Interpreta o codigo de saida do DISM para uma acao de habilitar/desabilitar recurso opcional,
     * traduzindo os casos conhecidos para uma mensagem clara em portugues:
     *  - {@code 0}: sucesso, sem reinicio necessario;
     *  - {@code 3010}: sucesso, MAS reinicio e necessario para concluir (nunca reiniciamos sozinhos -
     *    avisamos o usuario e deixamos a decisao com ele);
     *  - {@code 740}: exige elevacao (DISM recusa rodar sem Administrador, mesmo so para consultar);
     *  - qualquer outro codigo, com "desconhecido"/"unknown" na saida: recurso nao existe nesta
     *    edicao/versao do Windows (esperado - Recall e exclusivo de Copilot+ PCs).
     */
    private ActionResult interpretDismResult(CommandResult result, String acaoLabel) {
        int code = result.exitCode();
        String output = result.output().trim();
        if (code == 0) {
            return new ActionResult(true, "Recurso opcional 'Recall' " + acaoLabel + " com sucesso via DISM. "
                    + "Pode ser necessario reiniciar o Windows para o efeito completo (o NITRO BOOST nunca reinicia "
                    + "a maquina automaticamente - reinicie quando for conveniente).", null);
        }
        if (code == 3010) {
            return new ActionResult(true, "Recurso opcional 'Recall' " + acaoLabel + " via DISM - REINICIO NECESSARIO "
                    + "para concluir o efeito (o NITRO BOOST nunca reinicia a maquina automaticamente por seguranca - "
                    + "reinicie o Windows quando for conveniente).", null);
        }
        if (code == 740) {
            return new ActionResult(false, "Falha ao " + acaoLabel + " o recurso Recall via DISM: exige privilegios "
                    + "de Administrador (o DISM recusa rodar, ate mesmo para consultar, sem elevacao) - execute o "
                    + "NITRO BOOST como Administrador e tente novamente. " + output, null);
        }
        String lowerOutput = output.toLowerCase(java.util.Locale.ROOT);
        if (lowerOutput.contains("desconhecido") || lowerOutput.contains("unknown") || code == 87 || code == 11) {
            return new ActionResult(false, "O recurso opcional 'Recall' nao existe nesta edicao/versao do Windows "
                    + "(esperado - Recall e exclusivo de Copilot+ PCs com NPU) - nao ha nada para " + acaoLabel + ". "
                    + output, null);
        }
        return new ActionResult(false, "Falha ao " + acaoLabel + " o recurso Recall via DISM (codigo " + code + "): " + output, null);
    }

    // ------------------------------------------------------------------
    // Recursos de Consumidor / Segundo Plano (chaves de registro - Fase 8 Parte 2)
    // ------------------------------------------------------------------

    /** Altera um valor de recurso de consumidor/segundo plano conhecido, com backup previo. */
    public ActionResult setConsumerFeatureValue(ConsumerFeatureScanner.ConsumerFeatureKeyDefinition definition, String newValue) {
        ConsumerFeatureScanner.ConsumerFeatureKeyInfo before = consumerFeatureScanner.readValue(definition);
        String previousState = before.exists() ? before.currentValue() : "nao definido";
        return applyRegistryDwordChange("consumer", definition.friendlyName(), definition.friendlyName(),
                definition.registryPath(), definition.valueName(), previousState, before.exists(), newValue);
    }

    public ActionResult restoreConsumerFeatureValue(long backupId) {
        return restoreRegistryDwordChange("consumer", backupId);
    }

    /**
     * Mecanica compartilhada por {@link #setPerformanceValue}, {@link #setGamingValue},
     * {@link #setAiFeatureValue} e {@link #setConsumerFeatureValue}: todas essas categorias sao, por
     * baixo, o mesmo mecanismo generico ja validado em {@code setTelemetryValue} desde a Fase 3 (uma
     * chave/valor DWORD alterada via {@code reg add}, com backup previo e historico) - extraido aqui
     * para nao duplicar essa logica a cada categoria nova, sem alterar o metodo original de telemetria
     * (ja testado, sem motivo para arriscar uma regressao nele).
     *
     * @param itemName usado tanto como identificador no catalogo/lock/historico quanto como nome de
     *                 exibicao - as chamadas usam o nome amigavel da definicao, o mesmo valor exposto
     *                 pelo {@code ScannedItem} na UI, para que bloquear um item pela tela funcione
     *                 corretamente contra a mesma chave usada aqui.
     */
    private ActionResult applyRegistryDwordChange(String itemType, String itemName, String friendlyName,
                                                    String registryPath, String valueName,
                                                    String previousState, boolean existed, String newValue) {
        Long itemId = upsertItemQuiet(itemName, itemType, null, previousState);

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, itemName, itemType, "set", previousState);
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("registryPath", registryPath);
            snapshot.put("valueName", valueName);
            snapshot.put("existed", existed);
            if (existed) {
                snapshot.put("previousValue", previousState);
            }
            backupId = backupManager.snapshotBeforeAction(itemId, itemName, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de alterar '" + friendlyName + "': " + e.getMessage());
        }

        try {
            CommandResult result = runCommand("reg", "add", registryPath,
                    "/v", valueName, "/t", "REG_DWORD", "/d", newValue, "/f");
            boolean commandSucceeded = result.exitCode() == 0;
            String commandMessage = commandSucceeded
                    ? "Valor de '" + friendlyName + "' alterado para " + newValue + "."
                    : "Falha ao alterar '" + friendlyName + "' "
                        + "(comum se o app nao estiver rodando como Administrador, para chaves em HKLM): " + result.output().trim();

            ActionResult verified = verifyPostAction(commandSucceeded, commandMessage, backupId,
                    () -> queryRegistryDwordValue(registryPath, valueName).orElse("nao definido"),
                    actual -> RegistryValueUtils.dwordValuesEqual(actual, newValue),
                    newValue);

            recordHistory(itemId, itemName, itemType, "set", previousState, verified.success() ? newValue : null,
                    verified.success(), verified.success() ? null : verified.message(), backupId);
            return verified;
        } catch (Exception e) {
            String errorMessage = "Erro ao alterar valor de '" + friendlyName + "': " + e.getMessage();
            recordHistory(itemId, itemName, itemType, "set", previousState, null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /** Reversao generica compartilhada por {@link #restorePerformanceValue} e {@link #restoreGamingValue}. */
    private ActionResult restoreRegistryDwordChange(String itemType, long backupId) {
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            Map<String, Object> snapshot = backup.stateSnapshot();
            String registryPath = String.valueOf(snapshot.get("registryPath"));
            String valueName = String.valueOf(snapshot.get("valueName"));
            boolean existed = Boolean.TRUE.equals(snapshot.get("existed"));

            CommandResult result;
            if (existed) {
                String previousValue = String.valueOf(snapshot.get("previousValue"));
                // O valor lido de "reg query" vem em formato hexadecimal (ex: "0x2") - "reg add" com
                // REG_DWORD aceita tanto decimal quanto hexadecimal (0x...) como dado, entao podemos
                // recriar exatamente o valor original sem precisar converter.
                result = runCommand("reg", "add", registryPath, "/v", valueName, "/t", "REG_DWORD", "/d", previousValue, "/f");
            } else {
                result = runCommand("reg", "delete", registryPath, "/v", valueName, "/f");
            }
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Valor de '" + backup.itemName() + "' restaurado ao estado anterior."
                    : "Falha ao restaurar valor de '" + backup.itemName() + "': " + result.output().trim();

            if (success) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "set", success ? "restored" : null, success, success ? null : message, null);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar valor a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Arquivo de Hibernacao (Fase 9)
    // ------------------------------------------------------------------

    /** Habilita/desabilita o arquivo de hibernacao via {@code powercfg /hibernate on|off}, com backup do estado anterior. */
    public ActionResult setHibernationEnabled(boolean enable) {
        String itemType = "hibernation";
        PerformanceScanner.HibernationStatus before = performanceScanner.checkHibernationFile();
        String previousState = before.fileExists() ? "ativado" : "desativado";
        Long itemId = upsertItemQuiet(HIBERNATION_ITEM_NAME, itemType, null, previousState);

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, HIBERNATION_ITEM_NAME, itemType, "set", previousState);
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("wasEnabled", before.fileExists());
            backupId = backupManager.snapshotBeforeAction(itemId, HIBERNATION_ITEM_NAME, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de alterar o arquivo de hibernacao: " + e.getMessage());
        }

        try {
            CommandResult result = runCommand("powercfg", "/hibernate", enable ? "on" : "off");
            boolean commandSucceeded = result.exitCode() == 0;
            String newState = enable ? "ativado" : "desativado";
            String commandMessage = commandSucceeded
                    ? "Arquivo de hibernacao " + newState + " com sucesso."
                    : "Falha ao " + (enable ? "ativar" : "desativar") + " o arquivo de hibernacao "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            // powercfg /hibernate cria/apaga o hiberfil.sys de forma sincrona (nao exige reinicio) -
            // diferente do Armazenamento Reservado (ver setReservedStorageEnabled), a releitura aqui
            // reflete o efeito real imediatamente.
            ActionResult verified = verifyPostAction(commandSucceeded, commandMessage, backupId,
                    () -> {
                        PerformanceScanner.HibernationStatus status = performanceScanner.checkHibernationFile();
                        if (status.checkFailed()) {
                            throw new IllegalStateException("Nao foi possivel verificar o arquivo de hibernacao na releitura");
                        }
                        return status.fileExists() ? "ativado" : "desativado";
                    },
                    actual -> newState.equals(actual),
                    newState);

            recordHistory(itemId, HIBERNATION_ITEM_NAME, itemType, "set", previousState, verified.success() ? newState : null,
                    verified.success(), verified.success() ? null : verified.message(), backupId);
            return verified;
        } catch (Exception e) {
            String errorMessage = "Erro ao alterar o arquivo de hibernacao: " + e.getMessage();
            recordHistory(itemId, HIBERNATION_ITEM_NAME, itemType, "set", previousState, null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    public ActionResult restoreHibernationState(long backupId) {
        String itemType = "hibernation";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            boolean wasEnabled = Boolean.TRUE.equals(backup.stateSnapshot().get("wasEnabled"));

            CommandResult result = runCommand("powercfg", "/hibernate", wasEnabled ? "on" : "off");
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Arquivo de hibernacao restaurado ao estado anterior (" + (wasEnabled ? "ativado" : "desativado") + ")."
                    : "Falha ao restaurar o arquivo de hibernacao: " + result.output().trim();

            if (success) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "set",
                    success ? (wasEnabled ? "ativado" : "desativado") : null, success, success ? null : message, null);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar o arquivo de hibernacao a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // Armazenamento Reservado (Reserved Storage) - Fase 10 Parte 2
    // ------------------------------------------------------------------

    /** Nome fixo de catalogo para o "conceito" de Armazenamento Reservado (so existe um estado por vez). */
    private static final String RESERVED_STORAGE_ITEM_NAME = "Armazenamento Reservado (Reserved Storage)";

    /**
     * Habilita/desabilita o Armazenamento Reservado via {@code Set-WindowsReservedStorageState}
     * (cmdlet, nao uma chave de registro simples - por isso ganha um metodo dedicado em vez de
     * reaproveitar {@code applyRegistryDwordChange}), com backup do estado anterior. O efeito
     * completo requer reinicio do Windows.
     *
     * Se o cmdlet nao for reconhecido nesta versao/edicao do Windows, a acao e recusada de forma
     * graciosa (sem tentar escrever nada, sem criar backup) e registrada no historico como falha
     * "nao suportado" - nunca tratado como excecao/erro fatal.
     */
    public ActionResult setReservedStorageEnabled(boolean enable) {
        String itemType = "reservedstorage";
        PerformanceScanner.ReservedStorageStatus before = performanceScanner.checkReservedStorageState();

        if (!before.supported()) {
            String message = "Armazenamento Reservado nao e suportado/reconhecido nesta versao do Windows "
                    + "(cmdlet Get-WindowsReservedStorageState indisponivel) - nenhuma alteracao foi tentada.";
            Long itemId = upsertItemQuiet(RESERVED_STORAGE_ITEM_NAME, itemType, null, "nao suportado");
            recordHistory(itemId, RESERVED_STORAGE_ITEM_NAME, itemType, "set", "nao suportado", null, false, message, null);
            return new ActionResult(false, message, null);
        }

        String previousState = before.state() != null ? before.state() : "desconhecido";
        Long itemId = upsertItemQuiet(RESERVED_STORAGE_ITEM_NAME, itemType, null, previousState);

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, RESERVED_STORAGE_ITEM_NAME, itemType, "set", previousState);
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("previousState", previousState);
            backupId = backupManager.snapshotBeforeAction(itemId, RESERVED_STORAGE_ITEM_NAME, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de alterar o Armazenamento Reservado: " + e.getMessage());
        }

        try {
            String targetState = enable ? "Enabled" : "Disabled";
            CommandResult result = runPowerShell("Set-WindowsReservedStorageState -State " + targetState);
            boolean commandSucceeded = result.exitCode() == 0;
            String commandMessage = commandSucceeded
                    ? "Armazenamento Reservado " + (enable ? "ativado" : "desativado") + " com sucesso "
                        + "(efeito completo requer reinicio do Windows)."
                    : "Falha ao " + (enable ? "ativar" : "desativar") + " o Armazenamento Reservado "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            // Nota: o EFEITO COMPLETO deste cmdlet so aparece apos reiniciar o Windows (ver Javadoc de
            // setReservedStorageEnabled), mas Get-WindowsReservedStorageState e a fonte de verdade mais
            // proxima disponivel sem exigir reinicio - se ela ainda mostrar o estado antigo, a mensagem
            // de mismatch generica de verifyPostAction ja avisa que pode ser so questao de reinicio (nao
            // necessariamente falha real), entao nao ha risco de mensagem enganosa.
            ActionResult verified = verifyPostAction(commandSucceeded, commandMessage, backupId,
                    () -> {
                        PerformanceScanner.ReservedStorageStatus status = performanceScanner.checkReservedStorageState();
                        if (!status.supported() || status.checkFailed()) {
                            throw new IllegalStateException("Nao foi possivel confirmar o estado do Armazenamento Reservado na releitura");
                        }
                        return status.state();
                    },
                    actual -> targetState.equalsIgnoreCase(actual),
                    targetState);

            recordHistory(itemId, RESERVED_STORAGE_ITEM_NAME, itemType, "set", previousState, verified.success() ? targetState : null,
                    verified.success(), verified.success() ? null : verified.message(), backupId);
            return verified;
        } catch (Exception e) {
            String errorMessage = "Erro ao alterar o Armazenamento Reservado: " + e.getMessage();
            recordHistory(itemId, RESERVED_STORAGE_ITEM_NAME, itemType, "set", previousState, null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    public ActionResult restoreReservedStorageState(long backupId) {
        String itemType = "reservedstorage";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            Object previousStateObj = backup.stateSnapshot().get("previousState");
            String previousState = previousStateObj == null ? "Enabled" : previousStateObj.toString();
            boolean wasEnabled = !"Disabled".equalsIgnoreCase(previousState);

            CommandResult result = runPowerShell("Set-WindowsReservedStorageState -State " + (wasEnabled ? "Enabled" : "Disabled"));
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Armazenamento Reservado restaurado ao estado anterior (" + previousState + ")."
                    : "Falha ao restaurar o Armazenamento Reservado: " + result.output().trim();

            if (success) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "set", success ? previousState : null, success, success ? null : message, null);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar o Armazenamento Reservado a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    // ------------------------------------------------------------------
    // OneDrive - Desinstalacao Completa (Fase 12 Parte A, secao A.4)
    // ------------------------------------------------------------------

    /** Nome fixo de catalogo para o item de desinstalacao completa do OneDrive (distinto do item de startup "OneDrive"). */
    private static final String ONEDRIVE_UNINSTALL_ITEM_NAME = "OneDrive - Desinstalacao Completa";

    /**
     * Resolve dinamicamente o caminho do {@code OneDriveSetup.exe} nesta maquina - verifica se
     * existe primeiro em {@code SysWOW64} (localizacao usual em Windows 64-bit, onde o OneDrive e
     * um instalador 32-bit) e cai para {@code System32} (versoes 32-bit do Windows), conforme regra
     * de ouro do projeto de nunca hardcodar caminhos especificos de uma maquina. Retorna {@code null}
     * se o instalador nao for encontrado em nenhum dos dois locais (OneDrive pode ja estar
     * desinstalado, ou esta instalacao do Windows nunca teve o OneDrive).
     *
     * Metodo {@code static} e sem dependencias externas (so verifica arquivos em disco) - por isso
     * pode ser chamado tanto pela varredura ({@link com.nitroboost.ui.SystemScanTask}, para exibir o
     * estado atual) quanto por um teste isolado, sem precisar instanciar {@link ActionExecutor}.
     */
    public static String resolveOneDriveSetupPath() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            systemRoot = "C:\\Windows";
        }
        Path sysWow64Path = Path.of(systemRoot, "SysWOW64", "OneDriveSetup.exe");
        if (Files.exists(sysWow64Path)) {
            return sysWow64Path.toString();
        }
        Path system32Path = Path.of(systemRoot, "System32", "OneDriveSetup.exe");
        if (Files.exists(system32Path)) {
            return system32Path.toString();
        }
        return null;
    }

    /**
     * Desinstala o OneDrive POR COMPLETO desta maquina via {@code OneDriveSetup.exe /uninstall} -
     * acao BEM MAIS DRASTICA que o item "OneDrive" de tipo {@code startup} (que so desativa a
     * inicializacao automatica, sem remover nada). Por isso usa um {@code itemType} proprio
     * ({@code onedrive_uninstall}) e um nome de catalogo distinto - nunca deve ser confundida com o
     * item de startup, inclusive para fins de bloqueio (travar um NAO trava o outro).
     *
     * Segue o mesmo contrato de sempre (lock -> backup -> acao -> historico), mas a reversao real
     * NAO e garantida (ver {@link #restoreOneDriveInstallation(long)}) - mesma excecao documentada
     * ja aceita no projeto para acoes cuja plataforma nao oferece um "desfazer" 100% confiavel (ex:
     * restore de bloatware Appx na Fase 3/4, limpeza de RAM na Fase 10). Ainda assim, um backup e
     * sempre criado antes (regra de ouro do projeto), registrando o caminho do instalador usado, para
     * a melhor tentativa de reinstalacao possivel.
     */
    public ActionResult uninstallOneDriveCompletely() {
        String itemType = "onedrive_uninstall";
        String itemName = ONEDRIVE_UNINSTALL_ITEM_NAME;
        Long itemId = upsertItemQuiet(itemName, itemType, null, "instalado");

        Optional<ActionResult> lockRefusal = refuseIfLocked(itemId, itemName, itemType, "uninstall", "instalado");
        if (lockRefusal.isPresent()) {
            return lockRefusal.get();
        }

        String setupPath = resolveOneDriveSetupPath();
        if (setupPath == null) {
            String message = "OneDriveSetup.exe nao foi encontrado nesta maquina (nem em SysWOW64, nem em System32) - "
                    + "o OneDrive ja pode estar desinstalado, ou esta instalacao do Windows nunca teve o OneDrive.";
            recordHistory(itemId, itemName, itemType, "uninstall", "instalado", null, false, message, null);
            return new ActionResult(false, message, null);
        }

        Long backupId = null;
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("setupPath", setupPath);
            backupId = backupManager.snapshotBeforeAction(itemId, itemName, itemType, snapshot);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao criar backup antes de desinstalar o OneDrive: " + e.getMessage());
        }

        try {
            CommandResult result = runCommand(DISM_TIMEOUT_SECONDS, setupPath, "/uninstall");
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "OneDrive desinstalado por completo desta maquina. Se voce tinha arquivos que existiam "
                        + "SOMENTE na nuvem do OneDrive (nao copiados para outra pasta), reinstale o OneDrive e "
                        + "sincronize novamente para recupera-los."
                    : "Falha ao desinstalar o OneDrive (comum se o app nao estiver rodando como Administrador): "
                        + result.output().trim();

            recordHistory(itemId, itemName, itemType, "uninstall", "instalado", success ? "uninstalled" : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao desinstalar o OneDrive: " + e.getMessage();
            recordHistory(itemId, itemName, itemType, "uninstall", "instalado", null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /**
     * Tentativa de reversao "melhor esforco" da desinstalacao do OneDrive, reinvocando o MESMO
     * instalador capturado no backup, sem o parametro {@code /uninstall} (o comportamento padrao do
     * {@code OneDriveSetup.exe} sem argumentos e (re)instalar o OneDrive para o usuario atual).
     *
     * LIMITACAO DOCUMENTADA (regra de ouro do projeto: nunca prometer uma reversao que nao pode
     * garantir): diferente de uma chave de registro (onde o valor antigo e recriado com certeza),
     * reinstalar um programa nao restaura automaticamente configuracoes de sincronizacao, contas
     * vinculadas ou arquivos que so existiam na nuvem - o usuario precisara reconfigurar o OneDrive
     * (fazer login novamente) apos a reinstalacao. Alem disso, o instalador pode abrir uma janela
     * propria de configuracao inicial (nao e um processo 100% silencioso) - o exit code de sucesso
     * aqui confirma apenas que o INSTALADOR foi executado com sucesso, nao que a sincronizacao ja
     * esta configurada como antes.
     */
    public ActionResult restoreOneDriveInstallation(long backupId) {
        String itemType = "onedrive_uninstall";
        try {
            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findById(backupId);
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Backup #" + backupId + " nao encontrado.", backupId);
            }
            BackupManager.BackupRecord backup = backupOpt.get();
            Object setupPathObj = backup.stateSnapshot().get("setupPath");
            String setupPath = setupPathObj == null ? null : setupPathObj.toString();
            if (setupPath == null || setupPath.isBlank()) {
                String message = "Nao e possivel reinstalar: backup nao possui o caminho do instalador original.";
                recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "uninstalled", null, false, message, null);
                return new ActionResult(false, message, backupId);
            }

            CommandResult result = runCommand(DISM_TIMEOUT_SECONDS, setupPath);
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Instalador do OneDrive executado novamente com sucesso (reinstalacao 'melhor esforco' - "
                        + "voce pode precisar fazer login na sua conta Microsoft novamente para retomar a "
                        + "sincronizacao, exatamente como estava antes)."
                    : "Falha ao reinstalar o OneDrive: " + result.output().trim();

            if (success) {
                backupManager.markRestored(backupId);
            }
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "uninstalled", success ? "reinstalled" : null, success, success ? null : message, null);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao reinstalar o OneDrive a partir do backup #" + backupId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, backupId);
        }
    }

    /**
     * Comando usado por {@link #openDisplaySettings()} - extraido para um metodo separado (visibilidade
     * de pacote) para que possa ser validado por teste sem executar de verdade (Fase 14 Parte 2:
     * regra de ouro do projeto - nunca abrir de fato uma tela de configuracoes real num teste
     * automatizado via console).
     */
    public static String[] displaySettingsCommand() {
        return new String[] {"cmd", "/c", "start", "ms-settings:display"};
    }

    /**
     * Abre a pagina nativa de Configuracoes de Tela do Windows ({@code ms-settings:display}) - Nivel
     * 1 da Fase 14 Parte 2 ("sempre seguro"): o usuario troca a taxa de atualizacao manualmente pela
     * propria tela de configuracao nativa do Windows, nunca automaticamente pelo NITRO BOOST (o
     * Nivel 2 - troca automatica via {@code ChangeDisplaySettingsEx} - foi deliberadamente NAO
     * implementado nesta fase, pelo risco de tela preta se a taxa aplicada nao for suportada pelo
     * monitor - ver {@code docs/PROGRESS.md}).
     *
     * <p>Excecao deliberada ao contrato padrao desta classe (mesmo raciocinio ja documentado em
     * {@code core.MemoryCleaner}): NAO passa por {@link LockManager}/{@link BackupManager}/{@code
     * actions_history} - nao ha "item"/estado do sistema sendo alterado aqui, apenas abrindo uma tela
     * nativa do proprio Windows (nenhum registro/servico/configuracao e tocado por este metodo).
     */
    public ActionResult openDisplaySettings() {
        try {
            new ProcessBuilder(displaySettingsCommand()).start();
            return new ActionResult(true,
                    "Configuracoes de Tela do Windows abertas. Escolha a taxa de atualizacao desejada por la.", null);
        } catch (Exception e) {
            return new ActionResult(false, "Nao foi possivel abrir as Configuracoes de Tela: " + e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------
    // Utilitarios internos
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Confirmacao pos-acao por releitura (Melhoria de Confiabilidade)
    // ------------------------------------------------------------------
    //
    // Motivacao (ver docs/PROGRESS.md): ate aqui, quase toda acao desta classe decidia sucesso/falha
    // olhando SO o codigo de saida do comando (result.exitCode() == 0). Isso funciona na maioria dos
    // casos (reg add/Set-Service/powercfg retornam codigo != 0 quando falham por falta de elevacao),
    // mas ja foi encontrado um caso real onde isso mente: "arp -d *" (NetworkRepairTool, Fase 15)
    // sempre retorna 0 mesmo falhando por falta de elevacao. O mesmo tipo de mentira e possivel aqui
    // (ex: uma politica de grupo pode sobrescrever um valor de registro exatamente como "reg add"
    // termina, sem o comando notar). {@link #verifyPostAction} adiciona uma releitura real do estado
    // (reaproveitando os scanners ja existentes) depois que o comando reporta sucesso, so confirmando
    // o resultado se o valor relido realmente bater com o esperado.

    /**
     * Confirma, por releitura do estado real, um resultado que ja foi determinado com sucesso pelo
     * codigo de saida do comando.
     *
     * <p>Fluxo:
     * <ul>
     *   <li>comando ja falhou pelo codigo de saida -&gt; devolve a falha original, SEM tentar reler
     *       (nao ha o que confirmar);</li>
     *   <li>comando reportou sucesso E a releitura confirma o valor esperado -&gt; sucesso mantido,
     *       mensagem original preservada;</li>
     *   <li>comando reportou sucesso MAS a releitura mostra um valor diferente -&gt; falha "de
     *       verdade" e reportada, com o valor atual e o esperado na mensagem (o codigo de saida
     *       sozinho nunca foi garantia suficiente);</li>
     *   <li>a propria releitura falha (excecao) -&gt; cai de volta, gracioso, para o resultado
     *       original baseado no codigo de saida (nunca inventa uma falha nova so por nao conseguir
     *       confirmar), mas a mensagem deixa isso explicito para quem le.</li>
     * </ul>
     *
     * <p>Metodo estatico, sem estado de instancia - testavel isoladamente com valores fixos (ver
     * {@code ActionExecutorVerificationTest}), sem chamar nenhum comando real.
     *
     * @param commandSucceeded  resultado do comando original, baseado so no codigo de saida
     * @param commandMessage    mensagem ja formatada para o caso de sucesso OU falha do comando
     * @param backupId          repassado sem alteracao para o {@link ActionResult} devolvido
     * @param rereadCurrentState releitura do estado real (via scanner ja existente) - pode lancar
     *                          qualquer excecao para sinalizar "nao foi possivel confirmar"
     * @param matchesExpected   compara o valor relido com o esperado
     * @param expectedDescription descricao do valor esperado, usada na mensagem de mismatch
     */
    static ActionResult verifyPostAction(boolean commandSucceeded, String commandMessage, Long backupId,
                                          Callable<String> rereadCurrentState,
                                          Predicate<String> matchesExpected,
                                          String expectedDescription) {
        if (!commandSucceeded) {
            return new ActionResult(false, commandMessage, backupId);
        }
        try {
            String actual = rereadCurrentState.call();
            if (matchesExpected.test(actual)) {
                return new ActionResult(true, commandMessage, backupId);
            }
            String message = commandMessage + " ATENCAO: o comando reportou sucesso, mas a releitura do "
                    + "estado real mostrou um valor diferente do esperado (esperado: " + expectedDescription
                    + ", atual: " + actual + ") - pode estar sendo controlado por uma politica de grupo, ou o "
                    + "efeito pode exigir reinicio do Windows para aparecer.";
            return new ActionResult(false, message, backupId);
        } catch (Exception e) {
            return new ActionResult(true, commandMessage + " (nao foi possivel confirmar a mudanca por "
                    + "releitura do estado real - resultado baseado apenas no codigo de saida do comando)", backupId);
        }
    }

    /**
     * Le o valor atual de uma chave/valor DWORD via {@code reg query} - usado SO pela releitura de
     * confirmacao pos-acao ({@link #verifyPostAction}) depois que {@code reg add} ja reportou
     * sucesso pelo codigo de saida. Optional vazio = valor nao encontrado (reg query terminou com
     * codigo != 0) - um resultado LEGITIMO (chave/valor removido ou nunca existiu), nao uma falha de
     * leitura. So lanca excecao em falha real de execucao do comando (IOException/timeout).
     */
    private Optional<String> queryRegistryDwordValue(String registryPath, String valueName) throws IOException, InterruptedException {
        CommandResult result = runCommand("reg", "query", registryPath, "/v", valueName);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        return RegistryValueUtils.parseRegQueryValue(result.output(), valueName);
    }

    private Long upsertItemQuiet(String name, String type, String classification, String currentState) {
        try {
            return itemRepository.upsertItem(name, type, classification, null, currentState);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao registrar item '" + name + "' na tabela items: " + e.getMessage());
            return null;
        }
    }

    /**
     * Grava a acao no historico e, se um backup foi criado para ela, vincula
     * o backup a entrada de historico gerada (necessario para a reversao por
     * {@code historyId} em {@link #restoreFromHistory}, ja que o backup e
     * criado ANTES de existir uma entrada de historico para referenciar).
     */
    private void recordHistory(Long itemId, String itemName, String itemType, String actionType,
                                String previousState, String newState, boolean success, String errorMessage,
                                Long backupId) {
        try {
            long historyId = historyRepository.record(itemId, itemName, itemType, actionType, previousState, newState, success, errorMessage);
            if (backupId != null) {
                backupManager.linkToHistory(backupId, historyId);
            }
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao gravar historico da acao '" + actionType + "' sobre '" + itemName + "': " + e.getMessage());
        }
    }

    /**
     * Verifica se um item esta bloqueado antes de uma acao destrutiva
     * (kill/stop/disable). Se estiver, recusa a acao SEM excecao: grava a
     * recusa no historico (regra de ouro: toda acao, inclusive recusada, e
     * registrada) e devolve um {@link ActionResult} de falha com mensagem
     * clara, sem criar backup nem tocar no sistema operacional.
     */
    private Optional<ActionResult> refuseIfLocked(Long itemId, String itemName, String itemType,
                                                    String actionType, String currentState) {
        if (!lockManager.isLocked(itemName, itemType)) {
            return Optional.empty();
        }
        String message = "Acao '" + actionType + "' recusada: o item '" + itemName + "' esta bloqueado (protegido). "
                + "Desbloqueie o item manualmente antes de tentar novamente.";
        recordHistory(itemId, itemName, itemType, actionType, currentState, null, false, message, null);
        return Optional.of(new ActionResult(false, message, null));
    }

    /**
     * Reverte a acao registrada em uma entrada especifica do historico
     * ({@code historyId}), buscando o backup associado a ela (ou, na
     * ausencia de vinculo direto, o backup mais recente ainda nao restaurado
     * do mesmo item) e chamando o metodo de restore apropriado para o tipo
     * do item.
     */
    public ActionResult restoreFromHistory(long historyId) {
        try {
            Optional<ActionHistoryRepository.HistoryEntry> entryOpt = historyRepository.findById(historyId);
            if (entryOpt.isEmpty()) {
                return new ActionResult(false, "Entrada de historico #" + historyId + " nao encontrada.", null);
            }
            ActionHistoryRepository.HistoryEntry entry = entryOpt.get();

            Optional<BackupManager.BackupRecord> backupOpt = backupManager.findByActionHistoryId(historyId);
            if (backupOpt.isEmpty()) {
                backupOpt = backupManager.findLatestNotRestored(entry.itemName(), entry.itemType());
            }
            if (backupOpt.isEmpty()) {
                return new ActionResult(false, "Nenhum backup associado a entrada de historico #" + historyId
                        + " foi encontrado (ou ja foi restaurado).", null);
            }
            long backupId = backupOpt.get().id();

            return switch (entry.itemType()) {
                case "process" -> restoreProcess(backupId);
                case "service" -> restoreService(backupId);
                case "startup" -> restoreStartupItem(backupId);
                case "task" -> restoreScheduledTask(backupId);
                case "powerplan" -> restorePowerPlan(backupId);
                case "telemetry" -> restoreTelemetryValue(backupId);
                case "bloatware" -> restoreBloatwareApp(backupId);
                case "performance" -> restorePerformanceValue(backupId);
                case "gaming" -> restoreGamingValue(backupId);
                case "hibernation" -> restoreHibernationState(backupId);
                // itemType "ai" cobre TRES mecanismos diferentes por baixo (mesma categoria exibida na UI,
                // para o bloqueio funcionar igual para as duas acoes de um mesmo item - ver Fase 8 Ajuste):
                // "set" e a alteracao de politica de registro (Copilot/Recall/Click to Do/Cocreator/Edge),
                // "uninstall"/"uninstall-simulado" e a remocao do pacote Appx do Copilot, e
                // "uninstall-feature" e a desativacao do recurso opcional Recall via DISM - cada um precisa
                // do seu proprio restore, senao a reversao tentaria o mecanismo errado.
                case "ai" -> switch (entry.actionType()) {
                    case "uninstall", "uninstall-simulado" -> restoreAiFeatureApp(backupId);
                    case "uninstall-feature" -> restoreRecallFeature(backupId);
                    default -> restoreAiFeatureValue(backupId);
                };
                case "consumer" -> restoreConsumerFeatureValue(backupId);
                case "reservedstorage" -> restoreReservedStorageState(backupId);
                case "onedrive_uninstall" -> restoreOneDriveInstallation(backupId);
                default -> new ActionResult(false,
                        "Tipo de item '" + entry.itemType() + "' nao possui reversao automatica implementada.", backupId);
            };
        } catch (SQLException e) {
            String errorMessage = "Erro ao reverter a partir da entrada de historico #" + historyId + ": " + e.getMessage();
            System.err.println("[NITRO BOOST] " + errorMessage);
            return new ActionResult(false, errorMessage, null);
        }
    }

    private record CommandResult(int exitCode, String output) {
    }

    private CommandResult runPowerShell(String script) throws IOException, InterruptedException {
        String fullScript = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $OutputEncoding = [System.Text.Encoding]::UTF8; " + script;
        return runCommand("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", fullScript);
    }

    private CommandResult runCommand(String... command) throws IOException, InterruptedException {
        return runCommand(COMMAND_TIMEOUT_SECONDS, command);
    }

    /** {@code dism.exe} pode demorar bem mais que reg/schtasks/powercfg - timeout proprio, mais generoso. */
    private CommandResult runDism(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "dism.exe";
        System.arraycopy(args, 0, command, 1, args.length);
        return runCommand(DISM_TIMEOUT_SECONDS, command);
    }

    private CommandResult runCommand(int timeoutSeconds, String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        String output = readStream(process.getInputStream());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, "Timeout apos " + timeoutSeconds + "s executando: " + String.join(" ", command));
        }
        return new CommandResult(process.exitValue(), output);
    }

    private String readStream(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private String escapePowerShellSingleQuoted(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
