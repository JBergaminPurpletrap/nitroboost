package com.nitroboost.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Classe utilitaria para validar a integracao com JNA (Java Native Access).
 *
 * Fase 0: confirma que e possivel chamar funcoes nativas do Windows a
 * partir do Java sem escrever JNI em C. Usamos duas abordagens aqui de
 * proposito:
 *
 * 1) {@code Kernel32Util.getComputerName()} - API de alto nivel ja mapeada
 *    pelo modulo jna-platform (usada quando existe um mapeamento pronto).
 * 2) Mapeamento manual de {@code GetTickCount64} da kernel32.dll - mostra
 *    como fazemos um binding direto a uma DLL do Windows quando OSHI e
 *    jna-platform nao cobrirem algo especifico (ver secao 5 do guia de
 *    skills tecnicas do projeto).
 *
 * Pode ser rodada isoladamente via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.JnaNativeTest
 */
public final class JnaNativeTest {

    /**
     * Mapeamento manual de uma funcao simples e inofensiva da kernel32.dll
     * (GetTickCount64 retorna o numero de milissegundos desde que o Windows
     * foi iniciado). Usada aqui apenas como prova de conceito de que o JNA
     * consegue chamar DLLs nativas do Windows diretamente.
     */
    private interface Kernel32Direct extends StdCallLibrary {
        Kernel32Direct INSTANCE = Native.load("kernel32", Kernel32Direct.class);

        long GetTickCount64();
    }

    private JnaNativeTest() {
        // Classe utilitaria, nao deve ser instanciada.
    }

    /**
     * Executa chamadas nativas simples via JNA e imprime o resultado no
     * console. Qualquer falha (ex: rodando fora do Windows) e tratada e
     * reportada, nunca propagada como excecao nao tratada.
     */
    public static void testNativeCalls() {
        System.out.println("===== NITRO BOOST - Teste de chamadas nativas (JNA) =====");

        try {
            String computerName = Kernel32Util.getComputerName();
            System.out.println("Nome do computador (Kernel32Util): " + computerName);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha ao obter nome do computador via JNA: " + e.getMessage());
        }

        try {
            long uptimeMillis = Kernel32Direct.INSTANCE.GetTickCount64();
            System.out.printf("Tempo ligado desde o boot (GetTickCount64): %.2f minutos%n",
                    uptimeMillis / 1000.0 / 60.0);
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Falha na chamada nativa manual (GetTickCount64): " + e.getMessage());
        }

        System.out.println("==========================================================");
    }

    public static void main(String[] args) {
        testNativeCalls();
    }
}
