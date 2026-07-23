package com.nitroboost.core;

import com.nitroboost.db.ActionHistoryRepository;
import com.nitroboost.db.DatabaseManager;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

/**
 * Limpeza pontual de cache de RAM, estilo RAMMap/EmptyStandbyList (Sysinternals).
 *
 * <p><b>Excecao documentada a regra de ouro do projeto:</b> ao contrario de toda outra acao do
 * NITRO BOOST (que sempre passa por {@code BackupManager}/{@code LockManager} antes de alterar o
 * sistema), os metodos desta classe NAO criam backup nem checam bloqueio - nao existe "estado"
 * anterior a salvar nem a reverter, ja que o unico efeito e esvaziar caches de memoria que o
 * proprio Windows reconstroi sozinho, sob demanda, conforme necessario. A acao ainda e registrada
 * em {@code actions_history} (tipo {@code memory_cleanup}), mas apenas para fins informativos/de
 * auditoria de uso - nunca havera um backup vinculado a essas entradas, e
 * {@code ActionExecutor.restoreFromHistory} nunca despacha para este tipo.
 *
 * <p>Mecanismo: chama a funcao nao documentada oficialmente pela Microsoft
 * {@code NtSetSystemInformation} da {@code ntdll.dll}, com a classe de informacao
 * {@code SystemMemoryListInformation} (valor 80) - a mesma tecnica usada internamente pelo
 * RAMMap/EmptyStandbyList da Sysinternals, uso legitimo e bem conhecido de limpeza de cache de
 * memoria (nao envolve nenhuma acao maliciosa). O binding e manual via JNA, mesmo padrao de
 * mapeamento direto de DLL nativa usado desde a Fase 0 ({@link JnaNativeTest}), ja que essa funcao
 * nao tem mapeamento pronto no {@code jna-platform}. Antes de cada chamada, habilita no processo
 * atual os privilegios {@code SeProfileSingleProcessPrivilege} e {@code SeIncreaseQuotaPrivilege}
 * via {@code AdjustTokenPrivileges} (esses sim ja mapeados pelo {@code jna-platform}) - exigencia
 * padrao do proprio Windows para usar esta familia de funcao, mesmo o processo ja rodando como
 * Administrador.
 */
public class MemoryCleaner {

    /** Resultado de uma tentativa de limpeza - nunca lanca excecao para fora da classe. */
    public record MemoryCleanupResult(boolean success, String message) {
    }

    // SYSTEM_INFORMATION_CLASS::SystemMemoryListInformation (nao documentado oficialmente).
    private static final int SYSTEM_MEMORY_LIST_INFORMATION = 80;

    // MEMORY_LIST_COMMAND (nao documentado oficialmente) - ver tabela da secao 1.1 do documento da Fase 10.
    private static final int MEMORY_EMPTY_WORKING_SETS = 2;
    private static final int MEMORY_FLUSH_MODIFIED_LIST = 3;
    private static final int MEMORY_PURGE_STANDBY_LIST = 4;
    private static final int MEMORY_PURGE_LOW_PRIORITY_STANDBY_LIST = 5;

    private static final String SE_PROFILE_SINGLE_PROCESS_PRIVILEGE = "SeProfileSingleProcessPrivilege";
    private static final String SE_INCREASE_QUOTA_PRIVILEGE = "SeIncreaseQuotaPrivilege";

    /**
     * Mapeamento manual da funcao nao documentada {@code NtSetSystemInformation} - nao existe
     * binding pronto para ela no jna-platform (ao contrario de {@code Advapi32}/{@code Kernel32},
     * usados abaixo para os privilegios). Mesmo padrao de {@code Native.load} direto a uma DLL do
     * Windows demonstrado em {@link JnaNativeTest} desde a Fase 0.
     */
    private interface Ntdll extends Library {
        Ntdll INSTANCE = Native.load("ntdll", Ntdll.class);

        int NtSetSystemInformation(int systemInformationClass, IntByReference systemInformation, int systemInformationLength);
    }

    private final ActionHistoryRepository historyRepository;

    public MemoryCleaner(DatabaseManager databaseManager) {
        this.historyRepository = new ActionHistoryRepository(databaseManager);
    }

    /** Forca processos a devolver memoria fisica nao essencial ao sistema (MemoryEmptyWorkingSets). */
    public MemoryCleanupResult emptyWorkingSets() {
        return runCommand("MemoryEmptyWorkingSets", MEMORY_EMPTY_WORKING_SETS);
    }

    /** Grava em disco paginas modificadas pendentes e libera a memoria delas (MemoryFlushModifiedList). */
    public MemoryCleanupResult flushModifiedPageList() {
        return runCommand("MemoryFlushModifiedList", MEMORY_FLUSH_MODIFIED_LIST);
    }

    /**
     * Libera o cache de arquivos ("standby list") que o Windows guarda por precaucao - a opcao
     * mais parecida com o botao "Empty List" do RAMMap. Priorizada como acao principal do botao
     * de limpeza na UI (ver {@code ui/DashboardView}).
     */
    public MemoryCleanupResult purgeStandbyList() {
        return runCommand("MemoryPurgeStandbyList", MEMORY_PURGE_STANDBY_LIST);
    }

    /** Mesmo efeito de {@link #purgeStandbyList()}, restrito a lista de prioridade mais baixa. */
    public MemoryCleanupResult purgeLowPriorityStandbyList() {
        return runCommand("MemoryPurgeLowPriorityStandbyList", MEMORY_PURGE_LOW_PRIORITY_STANDBY_LIST);
    }

    private MemoryCleanupResult runCommand(String commandName, int commandValue) {
        MemoryCleanupResult result;
        try {
            String privilegeError = enablePrivilege(SE_PROFILE_SINGLE_PROCESS_PRIVILEGE);
            if (privilegeError == null) {
                privilegeError = enablePrivilege(SE_INCREASE_QUOTA_PRIVILEGE);
            }

            if (privilegeError != null) {
                result = new MemoryCleanupResult(false, "Nao foi possivel habilitar um privilegio necessario para "
                        + "limpar a RAM (" + privilegeError + "). O NITRO BOOST precisa estar rodando como "
                        + "Administrador para usar este recurso.");
            } else {
                IntByReference command = new IntByReference(commandValue);
                int status = Ntdll.INSTANCE.NtSetSystemInformation(SYSTEM_MEMORY_LIST_INFORMATION, command, Integer.BYTES);
                if (status == 0) {
                    result = new MemoryCleanupResult(true, "Comando '" + commandName + "' executado com sucesso.");
                } else {
                    result = new MemoryCleanupResult(false, "O Windows recusou o comando '" + commandName
                            + "' (codigo NTSTATUS 0x" + Integer.toHexString(status) + ").");
                }
            }
        } catch (Throwable t) {
            // Throwable (nao so Exception) de proposito: chamadas nativas via JNA podem lancar
            // Error em cenarios raros (ex: DLL/simbolo ausente numa versao futura do Windows) -
            // esta classe nunca deve propagar excecao para o chamador, conforme contrato da Fase 10.
            result = new MemoryCleanupResult(false, "Erro inesperado ao tentar limpar a memoria: " + t.getMessage());
        }

        recordHistoryQuiet(commandName, result);
        return result;
    }

    /**
     * Habilita um privilegio no token do processo atual via {@code AdjustTokenPrivileges}. Retorna
     * {@code null} em caso de sucesso, ou uma mensagem de erro curta em caso de falha - nunca lanca
     * excecao (chamadas ao Windows aqui sao envolvidas por {@code runCommand}, mas cada passo desta
     * funcao ja e defensivo por si so).
     */
    private String enablePrivilege(String privilegeName) {
        WinNT.HANDLEByReference tokenRef = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(),
                WinNT.TOKEN_ADJUST_PRIVILEGES | WinNT.TOKEN_QUERY, tokenRef)) {
            return "falha ao abrir o token do processo (erro " + Kernel32.INSTANCE.GetLastError() + ")";
        }

        WinNT.HANDLE token = tokenRef.getValue();
        try {
            WinNT.LUID luid = new WinNT.LUID();
            if (!Advapi32.INSTANCE.LookupPrivilegeValue(null, privilegeName, luid)) {
                return "privilegio '" + privilegeName + "' desconhecido neste Windows (erro "
                        + Kernel32.INSTANCE.GetLastError() + ")";
            }

            WinNT.TOKEN_PRIVILEGES privileges = new WinNT.TOKEN_PRIVILEGES(1);
            privileges.PrivilegeCount = new WinDef.DWORD(1);
            privileges.Privileges[0] = new WinNT.LUID_AND_ATTRIBUTES(luid, new WinDef.DWORD(WinNT.SE_PRIVILEGE_ENABLED));

            if (!Advapi32.INSTANCE.AdjustTokenPrivileges(token, false, privileges, 0, null, null)) {
                return "AdjustTokenPrivileges falhou para '" + privilegeName + "' (erro "
                        + Kernel32.INSTANCE.GetLastError() + ")";
            }
            // AdjustTokenPrivileges pode retornar sucesso (true) mesmo quando o processo nao possui
            // de fato o privilegio pedido - o unico jeito confiavel de detectar isso e checar
            // GetLastError logo em seguida (comportamento documentado da propria API do Windows).
            int lastError = Kernel32.INSTANCE.GetLastError();
            if (lastError == WinError.ERROR_NOT_ALL_ASSIGNED) {
                return "privilegio '" + privilegeName + "' nao concedido a este processo (erro "
                        + lastError + ") - confirme que o NITRO BOOST esta rodando como Administrador";
            }
            return null;
        } finally {
            Kernel32.INSTANCE.CloseHandle(token);
        }
    }

    /**
     * Grava a tentativa de limpeza em {@code actions_history} (tipo {@code memory_cleanup}),
     * direto via {@link ActionHistoryRepository} - sem passar por {@code LockManager} (nao ha
     * item a bloquear) nem por {@code BackupManager} (nao ha estado a salvar). Uma falha ao gravar
     * o historico e apenas logada no console, nunca sobrescreve o resultado real da limpeza que
     * ja foi entregue ao usuario.
     */
    private void recordHistoryQuiet(String commandName, MemoryCleanupResult result) {
        try {
            historyRepository.record(null, commandName, "memory", "memory_cleanup",
                    null, null, result.success(), result.success() ? null : result.message());
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao registrar limpeza de RAM no historico: " + e.getMessage());
        }
    }
}
