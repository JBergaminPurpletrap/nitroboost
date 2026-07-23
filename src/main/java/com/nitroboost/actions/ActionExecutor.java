package com.nitroboost.actions;

import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.BloatwareScanner;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.GamingScanner;
import com.nitroboost.core.PerformanceScanner;
import com.nitroboost.core.PowerPlanScanner;
import com.nitroboost.core.ProcessScanner;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.core.StartupScanner;
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
import java.util.concurrent.TimeUnit;

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
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Acao '" + actionType + "' aplicada com sucesso ao servico '" + serviceName + "'."
                    : "Falha ao aplicar acao '" + actionType + "' ao servico '" + serviceName + "' "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            recordHistory(itemId, serviceName, itemType, actionType, previousState, success ? actionType : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao executar acao '" + actionType + "' no servico '" + serviceName + "': " + e.getMessage();
            recordHistory(itemId, serviceName, itemType, actionType, previousState, null, false, errorMessage, backupId);
            return new ActionResult(false, errorMessage, backupId);
        }
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
                success = result.exitCode() == 0;
                message = success
                        ? "Entrada de registro '" + item.name() + "' removida de " + item.registryKey() + "."
                        : "Falha ao remover entrada de registro '" + item.name() + "' "
                            + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();
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
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Tarefa agendada '" + itemName + "' desativada com sucesso."
                    : "Falha ao desativar a tarefa '" + itemName + "' "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            recordHistory(itemId, itemName, itemType, "disable", task.status(), success ? "Disabled" : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
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
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Plano de energia alterado para '" + targetName + "'."
                    : "Falha ao trocar o plano de energia para '" + targetName + "': " + result.output().trim();

            recordHistory(itemId, POWER_PLAN_ITEM_NAME, itemType, "switch", previousState,
                    success ? targetName + " (" + targetGuid + ")" : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
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
        String itemName = definition.id();
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
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Valor de '" + definition.friendlyName() + "' alterado para " + newValue + "."
                    : "Falha ao alterar '" + definition.friendlyName() + "' "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            recordHistory(itemId, itemName, itemType, "set", previousState, success ? newValue : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
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
        String itemType = "bloatware";
        String itemName = app.name();
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
            boolean success = result.exitCode() == 0;
            String actionLabel = whatIf ? "uninstall-simulado" : "uninstall";
            String message = success
                    ? (whatIf
                        ? "[SIMULACAO -WhatIf] Comando de desinstalacao valido para '" + itemName + "' - nada foi alterado. " + result.output().trim()
                        : "App '" + itemName + "' desinstalado com sucesso.")
                    : "Falha ao desinstalar app '" + itemName + "' "
                        + "(comum se o app nao estiver rodando como Administrador, quando -AllUsers e usado): " + result.output().trim();

            recordHistory(itemId, itemName, itemType, actionLabel, "installed",
                    success ? (whatIf ? "installed (simulado)" : "uninstalled") : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
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
        String itemType = "bloatware";
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
            boolean success = result.exitCode() == 0;
            String message = success
                    ? "Valor de '" + friendlyName + "' alterado para " + newValue + "."
                    : "Falha ao alterar '" + friendlyName + "' "
                        + "(comum se o app nao estiver rodando como Administrador, para chaves em HKLM): " + result.output().trim();

            recordHistory(itemId, itemName, itemType, "set", previousState, success ? newValue : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
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
            boolean success = result.exitCode() == 0;
            String newState = enable ? "ativado" : "desativado";
            String message = success
                    ? "Arquivo de hibernacao " + newState + " com sucesso."
                    : "Falha ao " + (enable ? "ativar" : "desativar") + " o arquivo de hibernacao "
                        + "(comum se o app nao estiver rodando como Administrador): " + result.output().trim();

            recordHistory(itemId, HIBERNATION_ITEM_NAME, itemType, "set", previousState, success ? newState : null, success, success ? null : message, backupId);
            return new ActionResult(success, message, backupId);
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
    // Utilitarios internos
    // ------------------------------------------------------------------

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
                case "ai" -> restoreAiFeatureValue(backupId);
                case "consumer" -> restoreConsumerFeatureValue(backupId);
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
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        String output = readStream(process.getInputStream());
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, "Timeout apos " + COMMAND_TIMEOUT_SECONDS + "s executando: " + String.join(" ", command));
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
