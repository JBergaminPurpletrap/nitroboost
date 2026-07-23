package com.nitroboost.core;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Le a lista de processos em execucao no Windows usando a biblioteca OSHI
 * (sem depender de nenhum comando externo do sistema para a leitura).
 *
 * Responsabilidade unica: apenas ESCANEAR/LER processos. A acao de matar um
 * processo fica em {@link com.nitroboost.actions.ActionExecutor}.
 */
public class ProcessScanner {

    /**
     * Representa um processo capturado em um instante da varredura.
     *
     * @param pid          identificador do processo (PID)
     * @param name         nome do executavel (ex: "chrome.exe")
     * @param ramBytes     memoria residente (RAM) usada, em bytes
     * @param cpuPercent   uso cumulativo de CPU desde o inicio do processo, em % (0-100, aproximado)
     * @param commandLine  linha de comando completa (quando disponivel)
     * @param arguments    argumentos ja separados (quando disponivel) - mais seguro para reusar
     *                     do que fazer parsing manual de commandLine (evita erro com espacos/aspas)
     */
    public record ProcessInfo(int pid, String name, long ramBytes, double cpuPercent,
                               String commandLine, List<String> arguments) {
    }

    /**
     * Executa a varredura completa de processos. Nunca lanca excecao para
     * fora: qualquer falha de leitura via OSHI e reportada no console e uma
     * lista vazia e retornada (regra de ouro: toda interacao com o SO precisa
     * de tratamento de erro).
     */
    public List<ProcessInfo> scan() {
        try {
            SystemInfo systemInfo = new SystemInfo();
            OperatingSystem operatingSystem = systemInfo.getOperatingSystem();
            List<OSProcess> processes = operatingSystem.getProcesses();

            return processes.stream()
                    .map(ProcessScanner::toProcessInfo)
                    .toList();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao escanear processos via OSHI: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Busca um processo especifico pelo PID (mais eficiente que escanear
     * tudo e filtrar, ja que o OSHI oferece consulta direta por PID).
     */
    public Optional<ProcessInfo> findByPid(int pid) {
        try {
            SystemInfo systemInfo = new SystemInfo();
            OSProcess process = systemInfo.getOperatingSystem().getProcess(pid);
            return Optional.ofNullable(process).map(ProcessScanner::toProcessInfo);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao buscar processo PID " + pid + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retorna os N processos que mais consomem RAM, ordenados de forma
     * decrescente.
     */
    public List<ProcessInfo> topByRam(int limit) {
        return scan().stream()
                .sorted(Comparator.comparingLong(ProcessInfo::ramBytes).reversed())
                .limit(limit)
                .toList();
    }

    private static ProcessInfo toProcessInfo(OSProcess p) {
        return new ProcessInfo(
                p.getProcessID(),
                p.getName(),
                p.getResidentMemory(),
                p.getProcessCpuLoadCumulative() * 100.0,
                p.getCommandLine(),
                p.getArguments()
        );
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.ProcessScanner
     */
    public static void main(String[] args) {
        ProcessScanner scanner = new ProcessScanner();
        List<ProcessInfo> top10 = scanner.topByRam(10);

        System.out.println("===== NITRO BOOST - Top 10 processos por uso de RAM =====");
        System.out.printf("%-8s %-32s %12s %10s%n", "PID", "NOME", "RAM (MB)", "CPU (%)");
        for (ProcessInfo p : top10) {
            System.out.printf("%-8d %-32s %12.1f %10.1f%n",
                    p.pid(), truncate(p.name(), 32), p.ramBytes() / 1024.0 / 1024.0, p.cpuPercent());
        }
        System.out.println("Total de processos encontrados: " + scanner.scan().size());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
