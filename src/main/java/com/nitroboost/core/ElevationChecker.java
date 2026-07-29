package com.nitroboost.core;

import oshi.SystemInfo;

/**
 * Verifica se o processo atual do NITRO BOOST esta rodando com privilegio de Administrador.
 *
 * Extraido de {@code Phase1ConsoleDemo.printElevationStatus()} (Fase 1) para um lugar reutilizavel -
 * essa mesma checagem ({@code new SystemInfo().getOperatingSystem().isElevated()}) ja era repetida
 * em varios {@code PhaseXConsoleDemo}, e agora e usada tambem pelo autoteste da Fase 16
 * ({@code validation.Fase16ValidationRunner}).
 *
 * Nunca lanca excecao para fora: qualquer falha ao consultar o SO retorna {@code false} (assume "nao
 * elevado", a opcao mais conservadora - o resto do app ja trata "nao elevado" de forma graciosa).
 */
public final class ElevationChecker {

    private ElevationChecker() {
        // Classe utilitaria, nao deve ser instanciada.
    }

    public static boolean isElevated() {
        try {
            return new SystemInfo().getOperatingSystem().isElevated();
        } catch (Exception e) {
            System.err.println("[NITRO BOOST] Nao foi possivel determinar se o app roda como Administrador: " + e.getMessage());
            return false;
        }
    }
}
