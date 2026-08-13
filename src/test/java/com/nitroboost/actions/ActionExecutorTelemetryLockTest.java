package com.nitroboost.actions;

import com.nitroboost.core.TelemetryScanner;
import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressao do bug de identidade inconsistente do item de Telemetria (registrado em
 * {@code docs/BLOCKERS.md}): a UI ({@code SystemScanTask.scanTelemetry} e
 * {@code SystemAuditEngine.auditTelemetry}) sempre usou {@code friendlyName()} como nome do item -
 * e portanto grava o bloqueio sob esse nome - enquanto {@code ActionExecutor.setTelemetryValue}
 * checava o bloqueio sob {@code definition.id()}. Os dois nunca coincidiam, entao bloquear um item
 * de Telemetria pela interface NAO impedia, de fato, que ele fosse alterado (o lock ficava "orfao").
 *
 * <p>Estes testes nunca escrevem no registro real: {@code refuseIfLocked} roda ANTES de qualquer
 * comando {@code reg add}, entao o caminho exercitado aqui para no momento da recusa. O banco usado
 * e um arquivo SQLite temporario ({@link TempDir}), via o construtor
 * {@link DatabaseManager#DatabaseManager(Path)} - o banco real do usuario nunca e tocado.
 */
class ActionExecutorTelemetryLockTest {

    /** Item de Telemetria em HKCU (nao exige elevacao nem para leitura) - ver TelemetryScanner.KNOWN_KEYS. */
    private static final String TEST_ITEM_ID = "advertising_id";

    private DatabaseManager databaseManager;
    private LockManager lockManager;
    private ActionExecutor actionExecutor;
    private ActionHistoryRepository historyRepository;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        databaseManager = new DatabaseManager(tempDir.resolve("nitroboost-test.db"));
        databaseManager.initializeSchema();
        lockManager = new LockManager(databaseManager);
        actionExecutor = new ActionExecutor(databaseManager);
        historyRepository = new ActionHistoryRepository(databaseManager);
    }

    private TelemetryScanner.TelemetryKeyDefinition testDefinition() {
        return TelemetryScanner.KNOWN_KEYS.stream()
                .filter(d -> TEST_ITEM_ID.equals(d.id()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void setTelemetryValue_itemBloqueadoPeloNomeAmigavel_deveRecusarAAcao() {
        TelemetryScanner.TelemetryKeyDefinition definition = testDefinition();

        // Exatamente o que a UI faz ao clicar em "Bloquear" (ItemDetailView/ScanResultsView usam
        // item.name(), que para telemetria e o friendlyName - ver SystemScanTask.scanTelemetry).
        LockManager.LockResult lock = lockManager.lockItem(definition.friendlyName(), "telemetry",
                "Bloqueado pelo usuario via interface grafica");
        assertTrue(lock.success(), "pre-condicao: o bloqueio deveria ter sido gravado com sucesso");

        ActionExecutor.ActionResult result = actionExecutor.setTelemetryValue(definition, "0");
        // Desfaz ANTES de qualquer assercao: se o bug estiver presente, a acao passou direto pelo
        // lock e escreveu no registro de verdade - a limpeza nao pode depender de a assercao passar.
        undoIfActuallyApplied(result);

        assertFalse(result.success(),
                "um item de Telemetria bloqueado pela UI NUNCA pode ser alterado - o lock estava sendo "
                        + "ignorado porque o executor procurava o bloqueio pelo id() em vez do friendlyName()");
        assertTrue(result.message().toLowerCase().contains("bloqueado"),
                "a recusa deve dizer explicitamente que o item esta bloqueado, mas foi: " + result.message());
    }

    /**
     * Rede de seguranca do proprio teste: se a acao tiver sido aplicada de verdade (ou seja, o bug
     * de bloqueio esta presente), reverte imediatamente pelo backup, para o teste nunca deixar uma
     * alteracao real para tras na maquina de quem rodou a suite - regra de ouro do projeto.
     */
    private void undoIfActuallyApplied(ActionExecutor.ActionResult result) {
        if (result.success() && result.backupId() != null) {
            actionExecutor.restoreTelemetryValue(result.backupId());
        }
    }

    @Test
    void setTelemetryValue_itemBloqueado_registraARecusaNoHistoricoComONomeAmigavel() throws SQLException {
        TelemetryScanner.TelemetryKeyDefinition definition = testDefinition();
        lockManager.lockItem(definition.friendlyName(), "telemetry", "Bloqueado pelo usuario via interface grafica");

        undoIfActuallyApplied(actionExecutor.setTelemetryValue(definition, "0"));

        List<ActionHistoryRepository.HistoryEntry> recent = historyRepository.findRecent(10);
        boolean recusaRegistrada = recent.stream().anyMatch(h ->
                "telemetry".equals(h.itemType())
                        && definition.friendlyName().equals(h.itemName())
                        && !h.success());
        assertTrue(recusaRegistrada,
                "a recusa por bloqueio deve aparecer no historico sob o MESMO nome que a UI usa "
                        + "(friendlyName), para o usuario conseguir relacionar a entrada ao item da tela");
    }

    @Test
    void setTelemetryValue_itemNaoBloqueado_naoDeveSerRecusadoPorBloqueio() {
        TelemetryScanner.TelemetryKeyDefinition definition = testDefinition();

        // Sem nenhum lock gravado, a acao nao pode ser recusada POR BLOQUEIO. Ela ainda pode falhar
        // por outro motivo (ex: permissao do Windows), mas nunca com a mensagem de item bloqueado -
        // isso protege contra um "fix" exagerado que passasse a recusar tudo.
        ActionExecutor.ActionResult result = actionExecutor.setTelemetryValue(definition, "0");

        // Deixa a maquina como estava: aqui a escrita real E esperada (nao ha lock), entao a
        // reversao acontece sempre, antes da assercao.
        undoIfActuallyApplied(result);

        assertFalse(result.message().toLowerCase().contains("bloqueado"),
                "sem lock gravado, a acao nunca deveria ser recusada por bloqueio, mas foi: " + result.message());
    }
}
