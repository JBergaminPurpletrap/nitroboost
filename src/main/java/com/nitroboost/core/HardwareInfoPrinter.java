package com.nitroboost.core;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

/**
 * Classe utilitaria para validar a integracao com a biblioteca OSHI.
 *
 * Fase 0: usada apenas para confirmar que conseguimos ler dados reais de
 * hardware (uso de CPU e RAM) sem depender de nenhum comando externo do
 * sistema operacional. Em fases futuras, os scanners de core/ vao consumir
 * a API do OSHI de forma mais completa (processos, sensores, etc).
 *
 * Pode ser rodada isoladamente via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.HardwareInfoPrinter
 */
public final class HardwareInfoPrinter {

    private HardwareInfoPrinter() {
        // Classe utilitaria, nao deve ser instanciada.
    }

    /**
     * Le e imprime no console o uso atual de CPU (%) e RAM (usada/total)
     * usando a biblioteca OSHI. Nao lanca excecao para fora - qualquer falha
     * de leitura de hardware e reportada no console (regra de ouro: nunca
     * deixar uma interacao com o sistema sem tratamento de erro).
     */
    public static void printCpuAndRamUsage() {
        try {
            SystemInfo systemInfo = new SystemInfo();
            HardwareAbstractionLayer hardware = systemInfo.getHardware();

            CentralProcessor processor = hardware.getProcessor();
            // Para calcular o uso de CPU e necessario comparar dois "ticks" com um intervalo.
            long[] previousTicks = processor.getSystemCpuLoadTicks();
            sleepQuietly(500);
            double cpuLoad = processor.getSystemCpuLoadBetweenTicks(previousTicks) * 100.0;

            GlobalMemory memory = hardware.getMemory();
            long totalMemoryBytes = memory.getTotal();
            long availableMemoryBytes = memory.getAvailable();
            long usedMemoryBytes = totalMemoryBytes - availableMemoryBytes;

            System.out.println("===== NITRO BOOST - Diagnostico de Hardware (OSHI) =====");
            System.out.printf("CPU: %s (%d nucleos fisicos)%n",
                    processor.getProcessorIdentifier().getName().trim(),
                    processor.getPhysicalProcessorCount());
            System.out.printf("Uso de CPU: %.1f%%%n", cpuLoad);
            System.out.printf("RAM: %.2f GB usados de %.2f GB total (%.1f%%)%n",
                    bytesToGigabytes(usedMemoryBytes),
                    bytesToGigabytes(totalMemoryBytes),
                    (usedMemoryBytes * 100.0) / totalMemoryBytes);
            System.out.println("==========================================================");
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Erro ao consultar hardware via OSHI: " + e.getMessage());
        }
    }

    private static double bytesToGigabytes(long bytes) {
        return bytes / (1024.0 * 1024.0 * 1024.0);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Permite rodar este teste isoladamente via linha de comando.
     */
    public static void main(String[] args) {
        printCpuAndRamUsage();
    }
}
