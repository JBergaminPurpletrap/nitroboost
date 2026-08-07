package com.nitroboost.validation;

import com.nitroboost.actions.ActionExecutor;
import com.nitroboost.actions.LockManager;
import com.nitroboost.core.AiFeatureScanner;
import com.nitroboost.core.BloatwareScanner;
import com.nitroboost.core.ConsumerFeatureScanner;
import com.nitroboost.core.DisplayScanner;
import com.nitroboost.core.ElevationChecker;
import com.nitroboost.core.GamingScanner;
import com.nitroboost.core.MemoryCleaner;
import com.nitroboost.core.ServiceScanner;
import com.nitroboost.core.StartupScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.knowledge.ItemClassification;
import com.nitroboost.repair.NetworkRepairTool;
import com.nitroboost.repair.SystemFileRepairTool;
import com.nitroboost.ui.AppContext;
import com.nitroboost.ui.ItemActionDispatcher;
import com.nitroboost.ui.ScannedItem;

import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Orquestra o "Autoteste (Fase 16)": roda automaticamente o maximo possivel do roteiro descrito em
 * {@code docs/NITRO-BOOST-fase16-validacao-administrador.md}, reaproveitando os componentes de
 * backend ja existentes e validados (nunca reimplementa logica de negocio - so chama o que ja existe
 * e registra o resultado), agregando cada item em um {@link ValidationResult}.
 *
 * <p>Cada check e isolado (ver {@link #runCheck}): uma falha real ou uma excecao inesperada em UM
 * item nunca impede os demais de rodar - regra de ouro do projeto, reforcada aqui porque esta
 * ferramenta roda dezenas de acoes em sequencia sem supervisao constante.
 *
 * <p>Itens que so podem ser confirmados visualmente (tema, animacoes, comparar com uma tela nativa
 * do Windows) NUNCA sao fingidos como verificados - ficam marcados {@link ValidationStatus#REQUER_CONFIRMACAO_VISUAL}.
 * Acoes potencialmente irreversiveis ou que exigem reinicio (desinstalar o Copilot, resetar
 * Winsock/TCP-IP, mexer no servico de GPU de maior impacto) NUNCA rodam automaticamente - ficam
 * {@link ValidationStatus#PULADO_DELIBERADAMENTE} com o motivo.
 *
 * <p>Chamador (UI) e responsavel por rodar {@link #run} em uma thread de fundo, nunca a JavaFX
 * Application Thread - mesmo contrato de {@code SystemFileRepairTool}/{@code NetworkRepairTool}.
 */
public class Fase16ValidationRunner {

    /** Callback opcional de progresso - os metodos podem ser chamados em qualquer thread de fundo. */
    public interface Listener {
        default void onCheckStarted(int section, String item) {
        }

        default void onCheckFinished(ValidationResult result) {
        }
    }

    private static final List<String> GPU_SERVICE_NAMES = List.of(
            "NvTelemetryContainer", "NVDisplay.ContainerLocalSystem",
            "AMD External Events Utility", "AMD Crash Defender Service");

    /** Servicos de GPU com impacto potencial em overlay/G-SYNC (Fase 12) - NUNCA tocados automaticamente. */
    private static final Set<String> GPU_RISKY_SERVICES = Set.of(
            "NVDisplay.ContainerLocalSystem", "AMD External Events Utility");

    /** Servicos de telemetria de GPU considerados "seguros" para o round-trip automatico. */
    private static final List<String> GPU_SAFE_TELEMETRY_SERVICES = List.of(
            "NvTelemetryContainer", "AMD Crash Defender Service");

    /** Mesmo item usado desde a Fase 12 Parte B para os testes de servico/bloqueio (seguro, ja parado por padrao). */
    private static final String SERVICE_TEST_ITEM_NAME = "MapsBroker";

    private static final String TEST_STARTUP_REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String TEST_STARTUP_ENTRY_NAME = "NitroBoostFase16AutotesteEntry";

    private final ActionExecutor actionExecutor;
    private final LockManager lockManager;
    private final ActionHistoryRepository historyRepository;
    private final MemoryCleaner memoryCleaner;
    private final SystemFileRepairTool systemFileRepairTool;
    private final NetworkRepairTool networkRepairTool;

    private final ServiceScanner serviceScanner = new ServiceScanner();
    private final GamingScanner gamingScanner = new GamingScanner();
    private final ConsumerFeatureScanner consumerFeatureScanner = new ConsumerFeatureScanner();
    private final AiFeatureScanner aiFeatureScanner = new AiFeatureScanner();
    private final BloatwareScanner bloatwareScanner = new BloatwareScanner();
    private final StartupScanner startupScanner = new StartupScanner();
    private final DisplayScanner displayScanner = new DisplayScanner();

    public Fase16ValidationRunner(AppContext context) {
        this.actionExecutor = context.actionExecutor();
        this.lockManager = context.lockManager();
        this.historyRepository = context.historyRepository();
        this.memoryCleaner = context.memoryCleaner();
        this.systemFileRepairTool = context.systemFileRepairTool();
        this.networkRepairTool = context.networkRepairTool();
    }

    /**
     * Roda o autoteste completo (secoes 0 a 10 do documento da Fase 16, exceto a 10 - empacotamento -
     * que e sempre fora de escopo).
     *
     * @param includeFullSfcDism {@code true} para incluir a secao 6 (SFC/DISM completo, 10-30 minutos);
     *                           {@code false} para o "Autoteste Rapido" (pula a secao 6 deliberadamente)
     * @param listener           opcional, para a UI acompanhar o progresso - pode ser {@code null}
     */
    public ValidationReport run(boolean includeFullSfcDism, Listener listener) {
        List<ValidationResult> results = new ArrayList<>();
        boolean elevated = ElevationChecker.isElevated();

        runCheck(results, listener, 0, "Privilegio de Administrador", () -> checkElevation(elevated));

        runSection1Visual(results, listener);
        runSection2WriteActions(results, listener);

        runCheck(results, listener, 3, "Limpeza de Cache de RAM (purgeStandbyList)", this::checkRamCleanup);
        runCheck(results, listener, 3, "Limpeza de RAM - registrada no historico (tipo memory_cleanup)", this::checkRamCleanupHistory);
        runCheck(results, listener, 3, "UI nao trava durante a limpeza de RAM", () -> visualOnly(3,
                "UI nao trava durante a limpeza de RAM (indicador aparece/some)",
                "Confirme visualmente na tela: o indicador indeterminado deve aparecer durante a limpeza e "
                        + "sumir ao final, sem a interface travar."));
        runCheck(results, listener, 3, "RAM livre comparada com o Gerenciador de Tarefas", () -> visualOnly(3,
                "RAM livre comparada com o Gerenciador de Tarefas",
                "Compare o valor de RAM livre antes/depois (ver item acima, medido via OSHI) com o Gerenciador "
                        + "de Tarefas do Windows, abertos na mesma janela de tempo."));

        runSection4Copilot(results, listener);
        runSection5Gpu(results, listener);

        runCheck(results, listener, 6, "Verificacao e Reparo Completo (SFC + DISM)", () -> checkSfcDism(includeFullSfcDism));

        runSection7Network(results, listener);
        runSection8GameMode(results, listener);
        runSection9RefreshRate(results, listener);

        runCheck(results, listener, 10, "Empacotamento (.exe)", this::checkPackagingOutOfScope);

        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return new ValidationReport(List.copyOf(results), elevated, includeFullSfcDism, generatedAt);
    }

    /**
     * Roda um unico check isolado: uma excecao inesperada dentro de {@code check} vira um resultado
     * {@link ValidationStatus#FALHOU} (nunca propaga e derruba o autoteste inteiro) - regra de ouro
     * do projeto aplicada aqui de forma centralizada, para nao precisar repetir try/catch em cada um
     * dos metodos de check abaixo.
     */
    private void runCheck(List<ValidationResult> results, Listener listener, int section, String itemLabel,
                           Supplier<ValidationResult> check) {
        if (listener != null) {
            listener.onCheckStarted(section, itemLabel);
        }
        ValidationResult result;
        try {
            result = check.get();
        } catch (Throwable t) {
            result = new ValidationResult(section, itemLabel, ValidationStatus.FALHOU,
                    "Erro inesperado durante a verificacao: " + t.getMessage());
        }
        results.add(result);
        if (listener != null) {
            listener.onCheckFinished(result);
        }
    }

    private ValidationResult visualOnly(int section, String item, String details) {
        return new ValidationResult(section, item, ValidationStatus.REQUER_CONFIRMACAO_VISUAL, details);
    }

    // ------------------------------------------------------------------
    // Secao 0: pre-requisitos
    // ------------------------------------------------------------------

    private ValidationResult checkElevation(boolean elevated) {
        String details = elevated
                ? "Sessao rodando como Administrador (confirmado via ElevationChecker.isElevated())."
                : "Sessao SEM privilegio de Administrador. A maioria das acoes de escrita abaixo (servicos, "
                        + "chaves de registro em HKLM, DISM, limpeza de RAM) vai falhar ou ser recusada pelo "
                        + "Windows por causa disso - isso e esperado, e o autoteste continua ate o fim mesmo "
                        + "assim, documentando cada falha real. Rode o NITRO BOOST como Administrador (clique "
                        + "direito -> Executar como administrador) para uma validacao completa.";
        return new ValidationResult(0, "Privilegio de Administrador",
                elevated ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details);
    }

    // ------------------------------------------------------------------
    // Secao 1: checklist visual
    // ------------------------------------------------------------------

    private void runSection1Visual(List<ValidationResult> results, Listener listener) {
        runCheck(results, listener, 1, "Tema visual Carbono & Verde Turbo", () -> visualOnly(1,
                "Tema visual Carbono & Verde Turbo",
                "Confirme visualmente: fundo preto/textura carbono, acentos verde neon, fonte HUD."));
        runCheck(results, listener, 1, "Velocimetro/grafico do Dashboard atualizam sem travar", () -> visualOnly(1,
                "Velocimetro/grafico de CPU/RAM do Dashboard atualizam sem travar",
                "Confirme visualmente: velocimetro e grafico de linha de CPU/RAM atualizam a cada ~2s sem "
                        + "travar a interface."));
        runCheck(results, listener, 1, "Barra de progresso do scan e suave", () -> visualOnly(1,
                "Barra de progresso do scan e suave (nao pula de 0% a 100%)",
                "Confirme visualmente clicando em ESCANEAR SISTEMA: a NitroProgressBar deve se mover de forma "
                        + "gradual e proporcional, nao pular de 0% direto para 100%."));
        runCheck(results, listener, 1, "Todas as telas da barra lateral abrem sem erro", () -> visualOnly(1,
                "Todas as telas da barra lateral abrem sem erro",
                "Navegue manualmente por todas as 7 telas (Painel de Controle, Resultados da Varredura, "
                        + "Diagnostico do Sistema, Reparo do Sistema, BIOS/Drivers, Historico, Tutoriais) e "
                        + "confirme que nenhuma lanca erro ao abrir. O autoteste nao instancia as views fora do "
                        + "ciclo de vida normal de proposito - construir uma view de producao (que pode iniciar "
                        + "timers/threads proprios, ex: o grafico do Dashboard) sem ela ser exibida de verdade "
                        + "arriscaria deixar recursos orfaos rodando em segundo plano."));
    }

    // ------------------------------------------------------------------
    // Secao 2: acoes de escrita - round trip completo por categoria
    // ------------------------------------------------------------------

    private void runSection2WriteActions(List<ValidationResult> results, Listener listener) {
        runCheck(results, listener, 2, "Servicos - aplicar/confirmar/reverter/confirmar (" + SERVICE_TEST_ITEM_NAME + ")",
                this::checkServiceRoundTrip);
        runCheck(results, listener, 2, "Registro (Menu Iniciar) - aplicar/confirmar/reverter/confirmar",
                this::checkRegistryRoundTrip);
        runCheck(results, listener, 2, "Startup - aplicar/confirmar/reverter/confirmar (item de teste proprio)",
                this::checkStartupRoundTrip);
        runCheck(results, listener, 2, "Bloqueio (Lock) - acao recusada enquanto bloqueado", this::checkLockRefusal);
        runCheck(results, listener, 2, "Historico - as acoes acima foram registradas", this::checkSection2History);
    }

    private ValidationResult checkServiceRoundTrip() {
        String itemName = SERVICE_TEST_ITEM_NAME;
        Optional<ServiceScanner.ServiceInfo> before = serviceScanner.findByName(itemName);
        if (before.isEmpty()) {
            return new ValidationResult(2, "Servicos - aplicar/confirmar/reverter/confirmar (" + itemName + ")",
                    ValidationStatus.PULADO_DELIBERADAMENTE,
                    "Servico '" + itemName + "' nao existe nesta maquina - nenhum item substituto foi escolhido "
                            + "automaticamente pelo autoteste.");
        }
        String beforeStartMode = before.get().startMode();

        ActionExecutor.ActionResult apply = actionExecutor.disableService(itemName);
        Optional<ServiceScanner.ServiceInfo> afterApply = serviceScanner.findByName(itemName);
        ActionExecutor.ActionResult revert = apply.backupId() != null ? actionExecutor.restoreService(apply.backupId()) : null;
        Optional<ServiceScanner.ServiceInfo> afterRevert = serviceScanner.findByName(itemName);

        boolean backToOriginal = afterRevert.isPresent() && beforeStartMode.equalsIgnoreCase(afterRevert.get().startMode());
        boolean pass = apply.success() && revert != null && revert.success() && backToOriginal;

        String details = "Antes: startMode=" + beforeStartMode
                + " | aplicar (disable): " + apply.message()
                + " | apos aplicar: " + afterApply.map(s -> s.state() + "/" + s.startMode()).orElse("(nao encontrado)")
                + " | reverter: " + (revert == null ? "nao tentado (sem backup)" : revert.message())
                + " | apos reverter: " + afterRevert.map(s -> s.state() + "/" + s.startMode()).orElse("(nao encontrado)")
                + " | voltou ao estado original: " + backToOriginal;

        return new ValidationResult(2, "Servicos - aplicar/confirmar/reverter/confirmar (" + itemName + ")",
                pass ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details, "Get-Service " + itemName);
    }

    private ValidationResult checkRegistryRoundTrip() {
        ConsumerFeatureScanner.ConsumerFeatureKeyDefinition definition = consumerFeatureDefinition("start_menu_suggestions");
        ConsumerFeatureScanner.ConsumerFeatureKeyInfo before = consumerFeatureScanner.readValue(definition);
        String beforeState = before.exists() ? before.currentValue() : "nao definido";

        ActionExecutor.ActionResult apply = actionExecutor.setConsumerFeatureValue(definition, definition.recommendedValue());
        ConsumerFeatureScanner.ConsumerFeatureKeyInfo afterApply = consumerFeatureScanner.readValue(definition);
        ActionExecutor.ActionResult revert = apply.backupId() != null ? actionExecutor.restoreConsumerFeatureValue(apply.backupId()) : null;
        ConsumerFeatureScanner.ConsumerFeatureKeyInfo afterRevert = consumerFeatureScanner.readValue(definition);
        String afterRevertState = afterRevert.exists() ? afterRevert.currentValue() : "nao definido";

        boolean backToOriginal = beforeState.equals(afterRevertState);
        boolean pass = apply.success() && revert != null && revert.success() && backToOriginal;

        String details = "Item: " + definition.friendlyName() + " | antes: " + beforeState
                + " | aplicar: " + apply.message()
                + " | apos aplicar: " + (afterApply.exists() ? afterApply.currentValue() : "nao definido")
                + " | reverter: " + (revert == null ? "nao tentado (sem backup)" : revert.message())
                + " | apos reverter: " + afterRevertState
                + " | voltou ao estado original: " + backToOriginal;

        return new ValidationResult(2, "Registro (Menu Iniciar) - aplicar/confirmar/reverter/confirmar",
                pass ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details,
                "reg query \"" + definition.registryPath() + "\" /v " + definition.valueName());
    }

    private ValidationResult checkStartupRoundTrip() {
        boolean created = false;
        try {
            int createExit = runRegCommand("add", TEST_STARTUP_REGISTRY_KEY, "/v", TEST_STARTUP_ENTRY_NAME,
                    "/t", "REG_SZ", "/d", "cmd.exe /c exit", "/f");
            if (createExit != 0) {
                return new ValidationResult(2, "Startup - aplicar/confirmar/reverter/confirmar (item de teste proprio)",
                        ValidationStatus.FALHOU,
                        "Nao foi possivel criar a entrada de registro de teste propria (codigo " + createExit
                                + ") - provavel falta de privilegio de Administrador.");
            }
            created = true;

            Optional<StartupScanner.StartupItemInfo> testItem = startupScanner.scan().stream()
                    .filter(item -> TEST_STARTUP_ENTRY_NAME.equals(item.name()))
                    .findFirst();
            if (testItem.isEmpty()) {
                return new ValidationResult(2, "Startup - aplicar/confirmar/reverter/confirmar (item de teste proprio)",
                        ValidationStatus.FALHOU,
                        "Entrada de teste criada no registro, mas nao encontrada pelo StartupScanner logo em seguida.");
            }

            ActionExecutor.ActionResult disable = actionExecutor.disableStartupItem(testItem.get());
            boolean goneAfterDisable = startupScanner.scan().stream().noneMatch(i -> TEST_STARTUP_ENTRY_NAME.equals(i.name()));
            ActionExecutor.ActionResult restore = disable.backupId() != null ? actionExecutor.restoreStartupItem(disable.backupId()) : null;
            boolean backAfterRestore = startupScanner.scan().stream().anyMatch(i -> TEST_STARTUP_ENTRY_NAME.equals(i.name()));

            boolean pass = disable.success() && goneAfterDisable && restore != null && restore.success() && backAfterRestore;
            String details = "Item de teste PROPRIO do autoteste (nunca um item real de inicializacao do usuario): "
                    + TEST_STARTUP_ENTRY_NAME
                    + " | desativar: " + disable.message()
                    + " | sumiu apos desativar: " + goneAfterDisable
                    + " | reverter: " + (restore == null ? "nao tentado (sem backup)" : restore.message())
                    + " | voltou apos reverter: " + backAfterRestore;

            return new ValidationResult(2, "Startup - aplicar/confirmar/reverter/confirmar (item de teste proprio)",
                    pass ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details,
                    "Get-CimInstance Win32_StartupCommand | Where-Object Name -eq " + TEST_STARTUP_ENTRY_NAME);
        } finally {
            // Regra de ouro do projeto: nunca deixar um item de teste para tras, mesmo se algo acima falhou.
            if (created) {
                runRegCommand("delete", TEST_STARTUP_REGISTRY_KEY, "/v", TEST_STARTUP_ENTRY_NAME, "/f");
            }
        }
    }

    private ValidationResult checkLockRefusal() {
        String itemName = SERVICE_TEST_ITEM_NAME;
        String itemType = "service";

        LockManager.LockResult lock = lockManager.lockItem(itemName, itemType, "Autoteste Fase 16 - bloqueio temporario de teste");
        ActionExecutor.ActionResult refusal = actionExecutor.disableService(itemName);
        boolean refusedCorrectly = !refusal.success() && refusal.message() != null
                && refusal.message().toLowerCase(Locale.ROOT).contains("bloqueado");
        LockManager.LockResult unlock = lockManager.unlockItem(itemName, itemType);

        boolean pass = lock.success() && refusedCorrectly && unlock.success();
        String details = "Item bloqueado: " + itemName + " | bloquear: " + lock.message()
                + " | tentativa de desativar enquanto bloqueado (deve ser recusada mesmo com Administrador): "
                + refusal.message()
                + " | recusa correta: " + refusedCorrectly
                + " | desbloquear: " + unlock.message();

        return new ValidationResult(2, "Bloqueio (Lock) - acao recusada enquanto bloqueado",
                pass ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details);
    }

    private ValidationResult checkSection2History() {
        try {
            List<ActionHistoryRepository.HistoryEntry> recent = historyRepository.findRecent(60);
            String registryItemName = consumerFeatureDefinition("start_menu_suggestions").friendlyName();

            boolean hasService = recent.stream().anyMatch(h -> SERVICE_TEST_ITEM_NAME.equals(h.itemName()));
            boolean hasRegistry = recent.stream().anyMatch(h -> registryItemName.equals(h.itemName()));
            boolean hasStartup = recent.stream().anyMatch(h -> TEST_STARTUP_ENTRY_NAME.equals(h.itemName()));
            boolean hasLock = recent.stream().anyMatch(h -> "lock".equals(h.actionType()) && SERVICE_TEST_ITEM_NAME.equals(h.itemName()));

            boolean pass = hasService && hasRegistry && hasStartup && hasLock;
            String details = "Entradas encontradas no historico recente para os itens testados acima: servico="
                    + hasService + ", registro=" + hasRegistry + ", startup=" + hasStartup + ", bloqueio=" + hasLock;

            return new ValidationResult(2, "Historico - as acoes acima foram registradas",
                    pass ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details);
        } catch (SQLException e) {
            return new ValidationResult(2, "Historico - as acoes acima foram registradas", ValidationStatus.FALHOU,
                    "Erro ao consultar o historico de acoes: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Secao 3: limpeza de RAM
    // ------------------------------------------------------------------

    private ValidationResult checkRamCleanup() {
        GlobalMemory memory = new SystemInfo().getHardware().getMemory();
        long freeBefore = memory.getAvailable();
        MemoryCleaner.MemoryCleanupResult cleanupResult = memoryCleaner.purgeStandbyList();
        long freeAfter = memory.getAvailable();

        String details = String.format(Locale.ROOT,
                "RAM livre (OSHI) antes: %.1f MB | RAM livre depois: %.1f MB | resultado: %s",
                freeBefore / 1024.0 / 1024.0, freeAfter / 1024.0 / 1024.0, cleanupResult.message());

        return new ValidationResult(3, "Limpeza de Cache de RAM (purgeStandbyList)",
                cleanupResult.success() ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details);
    }

    private ValidationResult checkRamCleanupHistory() {
        try {
            List<ActionHistoryRepository.HistoryEntry> recent = historyRepository.findRecent(10);
            boolean found = recent.stream().anyMatch(h -> "memory_cleanup".equals(h.actionType()));
            return new ValidationResult(3, "Limpeza de RAM - registrada no historico (tipo memory_cleanup)",
                    found ? ValidationStatus.PASSOU : ValidationStatus.FALHOU,
                    "Entrada tipo 'memory_cleanup' encontrada no historico recente: " + found);
        } catch (SQLException e) {
            return new ValidationResult(3, "Limpeza de RAM - registrada no historico (tipo memory_cleanup)",
                    ValidationStatus.FALHOU, "Erro ao consultar o historico de acoes: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Secao 4: Copilot
    // ------------------------------------------------------------------

    private void runSection4Copilot(List<ValidationResult> results, Listener listener) {
        runCheck(results, listener, 4, "Copilot - pacote Appx separado existe nesta maquina?", this::checkCopilotAppxPresence);
        runCheck(results, listener, 4, "Copilot - Desativar/Reverter (Windows Copilot, Usuario Atual)", this::checkCopilotDisableRoundTrip);
        runCheck(results, listener, 4, "Copilot - Desinstalar pacote Appx", this::checkCopilotUninstallSkipped);
        runCheck(results, listener, 4, "Copilot - icone some/volta na barra de tarefas", () -> visualOnly(4,
                "Copilot - icone some/volta na barra de tarefas",
                "Confirme visualmente: apos desativar (item acima), o botao/icone do Copilot deve sumir da "
                        + "barra de tarefas; apos reverter, deve voltar a aparecer."));
    }

    private ValidationResult checkCopilotAppxPresence() {
        boolean hasAppxPackage = bloatwareScanner.scan().stream()
                .anyMatch(a -> a.category() == BloatwareScanner.Category.AI_COPILOT);
        String details = hasAppxPackage
                ? "Encontrado pacote Appx separado do Copilot nesta maquina - a acao 'Desinstalar' se aplica "
                        + "(ver item seguinte, nao executada automaticamente por seguranca)."
                : "Nenhum pacote Appx separado do Copilot encontrado (integrado ao shell/Explorer nesta build "
                        + "do Windows, ou ja desinstalado) - so a acao 'Desativar' via politica se aplica.";
        return new ValidationResult(4, "Copilot - pacote Appx separado existe nesta maquina?", ValidationStatus.PASSOU, details);
    }

    private ValidationResult checkCopilotDisableRoundTrip() {
        AiFeatureScanner.AiFeatureKeyDefinition definition = aiFeatureDefinition("windows_copilot_user");
        AiFeatureScanner.AiFeatureKeyInfo before = aiFeatureScanner.readValue(definition);
        String beforeState = before.exists() ? before.currentValue() : "nao definido";

        ActionExecutor.ActionResult apply = actionExecutor.setAiFeatureValue(definition, definition.recommendedValue());
        AiFeatureScanner.AiFeatureKeyInfo afterApply = aiFeatureScanner.readValue(definition);
        ActionExecutor.ActionResult revert = apply.backupId() != null ? actionExecutor.restoreAiFeatureValue(apply.backupId()) : null;
        AiFeatureScanner.AiFeatureKeyInfo afterRevert = aiFeatureScanner.readValue(definition);
        String afterRevertState = afterRevert.exists() ? afterRevert.currentValue() : "nao definido";

        boolean backToOriginal = beforeState.equals(afterRevertState);
        boolean pass = apply.success() && revert != null && revert.success() && backToOriginal;

        String details = "Item: " + definition.friendlyName() + " | antes: " + beforeState
                + " | aplicar (Desativar): " + apply.message()
                + " | apos aplicar: " + (afterApply.exists() ? afterApply.currentValue() : "nao definido")
                + " | reverter: " + (revert == null ? "nao tentado (sem backup)" : revert.message())
                + " | apos reverter: " + afterRevertState
                + " | voltou ao estado original: " + backToOriginal;

        return new ValidationResult(4, "Copilot - Desativar/Reverter (Windows Copilot, Usuario Atual)",
                pass ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details,
                "reg query \"" + definition.registryPath() + "\" /v " + definition.valueName());
    }

    private ValidationResult checkCopilotUninstallSkipped() {
        return new ValidationResult(4, "Copilot - Desinstalar pacote Appx", ValidationStatus.PULADO_DELIBERADAMENTE,
                "Nao executado automaticamente pelo autoteste - desinstalar um pacote Appx e uma acao mais dificil "
                        + "de reverter 100% (mesma cautela ja aplicada a desinstalacao do OneDrive na Fase 12). "
                        + "Teste manualmente pela tela de Diagnostico do Sistema se quiser validar este caminho.");
    }

    // ------------------------------------------------------------------
    // Secao 5: GPU
    // ------------------------------------------------------------------

    private void runSection5Gpu(List<ValidationResult> results, Listener listener) {
        runCheck(results, listener, 5, "GPU - varredura dos servicos de telemetria conhecidos", this::checkGpuServicesScan);
        runCheck(results, listener, 5, "GPU - servico(s) de maior impacto NAO tocados", this::checkGpuRiskySkipped);
        runCheck(results, listener, 5, "GPU - desativar/reverter servico de telemetria seguro", this::checkGpuSafeTelemetryRoundTrip);
    }

    private ValidationResult checkGpuServicesScan() {
        StringBuilder details = new StringBuilder();
        int found = 0;
        for (String serviceName : GPU_SERVICE_NAMES) {
            Optional<ServiceScanner.ServiceInfo> info = serviceScanner.findByName(serviceName);
            if (info.isPresent()) {
                found++;
                details.append(serviceName).append("=encontrado (").append(info.get().state()).append('/')
                        .append(info.get().startMode()).append("); ");
            } else {
                details.append(serviceName).append("=nao encontrado; ");
            }
        }
        details.append("Total encontrado nesta maquina: ").append(found).append(" de ").append(GPU_SERVICE_NAMES.size())
                .append(" (esperado 0 em maquinas sem GPU dedicada NVIDIA/AMD).");
        return new ValidationResult(5, "GPU - varredura dos servicos de telemetria conhecidos",
                ValidationStatus.PASSOU, details.toString());
    }

    private ValidationResult checkGpuRiskySkipped() {
        StringBuilder details = new StringBuilder("Estes servicos NUNCA sao desativados automaticamente pelo "
                + "autoteste (impacto potencial em overlay/G-SYNC, ver Fase 12) - decisao manual, apos ler a "
                + "descricao com atencao: ");
        for (String serviceName : GPU_RISKY_SERVICES) {
            Optional<ServiceScanner.ServiceInfo> info = serviceScanner.findByName(serviceName);
            details.append(serviceName).append('=')
                    .append(info.isPresent() ? "ENCONTRADO nesta maquina" : "nao encontrado").append("; ");
        }
        return new ValidationResult(5, "GPU - servico(s) de maior impacto NAO tocados",
                ValidationStatus.PULADO_DELIBERADAMENTE, details.toString());
    }

    private ValidationResult checkGpuSafeTelemetryRoundTrip() {
        Optional<String> safeServiceName = GPU_SAFE_TELEMETRY_SERVICES.stream()
                .filter(name -> serviceScanner.findByName(name).isPresent())
                .findFirst();
        if (safeServiceName.isEmpty()) {
            return new ValidationResult(5, "GPU - desativar/reverter servico de telemetria seguro",
                    ValidationStatus.PULADO_DELIBERADAMENTE,
                    "Nenhum servico de telemetria de GPU 'seguro' (NvTelemetryContainer / AMD Crash Defender "
                            + "Service) foi encontrado nesta maquina - esperado em maquinas sem GPU dedicada "
                            + "NVIDIA/AMD.");
        }

        String itemName = safeServiceName.get();
        Optional<ServiceScanner.ServiceInfo> before = serviceScanner.findByName(itemName);
        String beforeStartMode = before.map(ServiceScanner.ServiceInfo::startMode).orElse("desconhecido");

        ActionExecutor.ActionResult apply = actionExecutor.disableService(itemName);
        ActionExecutor.ActionResult revert = apply.backupId() != null ? actionExecutor.restoreService(apply.backupId()) : null;
        Optional<ServiceScanner.ServiceInfo> afterRevert = serviceScanner.findByName(itemName);
        boolean backToOriginal = afterRevert.isPresent() && beforeStartMode.equalsIgnoreCase(afterRevert.get().startMode());
        boolean pass = apply.success() && revert != null && revert.success() && backToOriginal;

        String details = "Item: " + itemName + " | antes: " + beforeStartMode
                + " | aplicar: " + apply.message()
                + " | reverter: " + (revert == null ? "nao tentado (sem backup)" : revert.message())
                + " | voltou ao original: " + backToOriginal;

        return new ValidationResult(5, "GPU - desativar/reverter servico de telemetria seguro",
                pass ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details, "Get-Service " + itemName);
    }

    // ------------------------------------------------------------------
    // Secao 6: SFC/DISM completo
    // ------------------------------------------------------------------

    private ValidationResult checkSfcDism(boolean includeFullSfcDism) {
        if (!includeFullSfcDism) {
            return new ValidationResult(6, "Verificacao e Reparo Completo (SFC + DISM)",
                    ValidationStatus.PULADO_DELIBERADAMENTE,
                    "Nao incluido no Autoteste Rapido (o SFC sozinho pode levar de 10 a 20 minutos, o DISM mais "
                            + "alguns minutos). Ative a opcao \"Incluir SFC/DISM completo\" separadamente para "
                            + "rodar esta parte.");
        }

        SystemFileRepairTool.RepairResult result = systemFileRepairTool.runFullRepair(null);
        String details = "DISM: " + (result.dismResult().success() ? "concluido"
                        : "falhou (codigo " + result.dismResult().exitCode() + ")")
                + " | SFC: " + SystemFileRepairTool.describeOutcome(result.sfcRunResult().outcome())
                + " | Compare este resultado com o log bruto exibido na tela de Reparo do Sistema, e registre em "
                + "TESTING.md conforme pedido pela secao 6 do documento da Fase 16.";
        return new ValidationResult(6, "Verificacao e Reparo Completo (SFC + DISM)",
                result.overallSuccess() ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details);
    }

    // ------------------------------------------------------------------
    // Secao 7: rede
    // ------------------------------------------------------------------

    private void runSection7Network(List<ValidationResult> results, Listener listener) {
        runCheck(results, listener, 7, "Rede - Limpar Cache de DNS", () -> networkCheck(networkRepairTool.flushDns()));
        runCheck(results, listener, 7, "Rede - Limpar Cache ARP", () -> networkCheck(networkRepairTool.clearArpCache()));
        runCheck(results, listener, 7, "Rede - Renovar IP", () -> networkCheck(networkRepairTool.renewIp()));
        runCheck(results, listener, 7, "Rede - Reset do Winsock", () -> networkResetSkipped("Reset do Winsock"));
        runCheck(results, listener, 7, "Rede - Reset da Pilha TCP/IP", () -> networkResetSkipped("Reset da Pilha TCP/IP"));
    }

    private ValidationResult networkCheck(NetworkRepairTool.NetworkRepairResult result) {
        return new ValidationResult(7, "Rede - " + result.actionLabel(),
                result.success() ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, result.message());
    }

    private ValidationResult networkResetSkipped(String label) {
        return new ValidationResult(7, "Rede - " + label, ValidationStatus.PULADO_DELIBERADAMENTE,
                "Nunca executado automaticamente pelo autoteste - so tem efeito apos reiniciar o Windows, e o "
                        + "NITRO BOOST nunca reinicia a maquina sozinho. Teste manualmente pela tela de Reparo do "
                        + "Sistema se estiver disposto a reiniciar o PC logo em seguida.");
    }

    // ------------------------------------------------------------------
    // Secao 8: Modo de Jogo - teste da logica invertida
    // ------------------------------------------------------------------

    private void runSection8GameMode(List<ValidationResult> results, Listener listener) {
        runCheck(results, listener, 8, "Modo de Jogo - rotulo do botao NAO esta invertido", this::checkGameModeLabelNotInverted);
        runCheck(results, listener, 8, "Modo de Jogo - aplicar ATIVA de verdade", this::checkGameModeRoundTrip);
    }

    private ValidationResult checkGameModeLabelNotInverted() {
        GamingScanner.GamingKeyDefinition definition = gamingFeatureDefinition("game_mode_auto");
        ScannedItem fakeItem = new ScannedItem("Jogos", "gaming", definition.friendlyName(), "",
                ItemClassification.DEPENDE, definition.description(), "", "", definition, true);
        String label = ItemActionDispatcher.primaryActionLabel(fakeItem);
        boolean correct = "Ativar Modo de Jogo".equals(label);

        return new ValidationResult(8, "Modo de Jogo - rotulo do botao NAO esta invertido",
                correct ? ValidationStatus.PASSOU : ValidationStatus.FALHOU,
                "Rotulo retornado por ItemActionDispatcher.primaryActionLabel(): '" + label
                        + "' (esperado: 'Ativar Modo de Jogo').");
    }

    private ValidationResult checkGameModeRoundTrip() {
        GamingScanner.GamingKeyDefinition definition = gamingFeatureDefinition("game_mode_auto");
        GamingScanner.GamingKeyInfo before = gamingScanner.readValue(definition);
        String beforeState = before.exists() ? before.currentValue() : "nao definido";

        ActionExecutor.ActionResult apply = actionExecutor.setGamingValue(definition, definition.recommendedValue());
        GamingScanner.GamingKeyInfo afterApply = gamingScanner.readValue(definition);
        boolean reallyEnabledAfterApply = afterApply.exists() && isTruthyDword(afterApply.currentValue());

        ActionExecutor.ActionResult revert = apply.backupId() != null ? actionExecutor.restoreGamingValue(apply.backupId()) : null;
        GamingScanner.GamingKeyInfo afterRevert = gamingScanner.readValue(definition);
        String afterRevertState = afterRevert.exists() ? afterRevert.currentValue() : "nao definido";
        boolean backToOriginal = beforeState.equals(afterRevertState);

        boolean pass = apply.success() && reallyEnabledAfterApply && revert != null && revert.success() && backToOriginal;

        String details = "Item: " + definition.friendlyName() + " | antes: " + beforeState
                + " | aplicar (deve ATIVAR, nunca desativar): " + apply.message()
                + " | apos aplicar: " + (afterApply.exists() ? afterApply.currentValue() : "nao definido")
                + " (realmente ativado: " + reallyEnabledAfterApply + ")"
                + " | reverter: " + (revert == null ? "nao tentado (sem backup)" : revert.message())
                + " | apos reverter: " + afterRevertState
                + " | voltou ao estado original: " + backToOriginal;

        return new ValidationResult(8, "Modo de Jogo - aplicar ATIVA de verdade (nao desativa por engano)",
                pass ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, details,
                "reg query \"" + definition.registryPath() + "\" /v " + definition.valueName());
    }

    private boolean isTruthyDword(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "0x1".equals(normalized) || "1".equals(normalized);
    }

    // ------------------------------------------------------------------
    // Secao 9: taxa de atualizacao da tela
    // ------------------------------------------------------------------

    private void runSection9RefreshRate(List<ValidationResult> results, Listener listener) {
        runCheck(results, listener, 9, "Taxa de atualizacao da tela - valores detectados", this::checkRefreshRateDetected);
        runCheck(results, listener, 9, "Abrir Configuracoes de Tela do Windows", this::checkOpenDisplaySettings);
    }

    private ValidationResult checkRefreshRateDetected() {
        Optional<Integer> current = displayScanner.getCurrentRefreshRate();
        Optional<Integer> max = displayScanner.getMaxRefreshRate();
        List<Integer> available = displayScanner.getAvailableRefreshRates();

        String details = "Taxa atual detectada: " + current.map(v -> v + " Hz").orElse("(nao foi possivel detectar)")
                + " | Taxa maxima detectada: " + max.map(v -> v + " Hz").orElse("(nao foi possivel detectar)")
                + " | Taxas suportadas (resolucao atual): " + available
                + " | Compare estes valores com Configuracoes > Sistema > Tela > Exibicao avancada do Windows.";

        return new ValidationResult(9, "Taxa de atualizacao da tela - valores detectados",
                ValidationStatus.REQUER_CONFIRMACAO_VISUAL, details);
    }

    private ValidationResult checkOpenDisplaySettings() {
        ActionExecutor.ActionResult result = actionExecutor.openDisplaySettings();
        return new ValidationResult(9, "Abrir Configuracoes de Tela do Windows",
                result.success() ? ValidationStatus.PASSOU : ValidationStatus.FALHOU, result.message());
    }

    // ------------------------------------------------------------------
    // Secao 10: empacotamento (fora de escopo)
    // ------------------------------------------------------------------

    private ValidationResult checkPackagingOutOfScope() {
        return new ValidationResult(10, "Empacotamento (.exe)", ValidationStatus.PULADO_DELIBERADAMENTE,
                "Fora do escopo deste autoteste - rodar scripts\\jpackage-build.bat e testar o .exe empacotado "
                        + "nao faz sentido chamar de dentro do proprio app rodando. Rode scripts\\jpackage-build.bat "
                        + "e run-as-admin.bat manualmente, depois repita pelo menos as secoes 1 e 2 usando o .exe "
                        + "empacotado.");
    }

    // ------------------------------------------------------------------
    // Utilitarios internos
    // ------------------------------------------------------------------

    private ConsumerFeatureScanner.ConsumerFeatureKeyDefinition consumerFeatureDefinition(String id) {
        return ConsumerFeatureScanner.KNOWN_KEYS.stream().filter(d -> id.equals(d.id())).findFirst().orElseThrow();
    }

    private AiFeatureScanner.AiFeatureKeyDefinition aiFeatureDefinition(String id) {
        return AiFeatureScanner.KNOWN_KEYS.stream().filter(d -> id.equals(d.id())).findFirst().orElseThrow();
    }

    private GamingScanner.GamingKeyDefinition gamingFeatureDefinition(String id) {
        return GamingScanner.KNOWN_KEYS.stream().filter(d -> id.equals(d.id())).findFirst().orElseThrow();
    }

    /** Roda {@code reg <regArgs...>} de forma sincrona, sem lancar excecao (retorna -1 em caso de erro/timeout). */
    private int runRegCommand(String... regArgs) {
        try {
            String[] command = new String[regArgs.length + 1];
            command[0] = "reg";
            System.arraycopy(regArgs, 0, command, 1, regArgs.length);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[NITRO BOOST] Erro ao executar comando 'reg' no autoteste da Fase 16: " + e.getMessage());
            return -1;
        }
    }
}
