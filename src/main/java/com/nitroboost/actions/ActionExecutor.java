package com.nitroboost.actions;

import com.nitroboost.core.ProcessScanner;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.core.StartupScanner;
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
    private final ProcessScanner processScanner = new ProcessScanner();
    private final ServiceScanner serviceScanner = new ServiceScanner();

    public ActionExecutor(DatabaseManager databaseManager) {
        this.backupManager = new BackupManager(databaseManager);
        this.historyRepository = new ActionHistoryRepository(databaseManager);
        this.itemRepository = new ItemRepository(databaseManager);
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
            recordHistory(itemId, itemName, itemType, "kill", "running", destroyed ? "killed" : "unknown", destroyed, destroyed ? null : message);
            return new ActionResult(destroyed, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao tentar finalizar processo PID " + pid + ": " + e.getMessage();
            recordHistory(itemId, itemName, itemType, "kill", "running", null, false, errorMessage);
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
                recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "killed", null, false, message);
                return new ActionResult(false, message, backupId);
            }

            Process relaunched = new ProcessBuilder(arguments).start();
            long newPid = relaunched.pid();

            backupManager.markRestored(backupId);
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "killed",
                    "running (novo PID " + newPid + ")", true, null);

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

            recordHistory(itemId, serviceName, itemType, actionType, previousState, success ? actionType : null, success, success ? null : message);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao executar acao '" + actionType + "' no servico '" + serviceName + "': " + e.getMessage();
            recordHistory(itemId, serviceName, itemType, actionType, previousState, null, false, errorMessage);
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
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "disabled", startMode, true, null);
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

            recordHistory(itemId, item.name(), itemType, "disable", "enabled", success ? "disabled" : null, success, success ? null : message);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao desativar item de startup '" + item.name() + "': " + e.getMessage();
            recordHistory(itemId, item.name(), itemType, "disable", "enabled", null, false, errorMessage);
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
            recordHistory(backup.itemId(), backup.itemName(), itemType, "restore", "disabled", success ? "enabled" : null, success, success ? null : message);
            return new ActionResult(success, message, backupId);
        } catch (Exception e) {
            String errorMessage = "Erro ao restaurar item de startup a partir do backup #" + backupId + ": " + e.getMessage();
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

    private void recordHistory(Long itemId, String itemName, String itemType, String actionType,
                                String previousState, String newState, boolean success, String errorMessage) {
        try {
            historyRepository.record(itemId, itemName, itemType, actionType, previousState, newState, success, errorMessage);
        } catch (SQLException e) {
            System.err.println("[NITRO BOOST] Falha ao gravar historico da acao '" + actionType + "' sobre '" + itemName + "': " + e.getMessage());
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
