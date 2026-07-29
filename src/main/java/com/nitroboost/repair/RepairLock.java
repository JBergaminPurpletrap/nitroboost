package com.nitroboost.repair;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trava compartilhada simples (Fase 15): enquanto {@link #isRunning()} for {@code true}, um reparo
 * DISM/SFC esta rodando em segundo plano e nenhuma outra acao administrativa do app deve comecar ao
 * mesmo tempo (evita conflito de processos administrativos simultaneos, conforme secao A.4 da
 * Fase 15). Como o layout principal ({@code Main.java}) so exibe uma tela por vez no centro,
 * bloquear a navegacao lateral enquanto esta travado ja impede o acesso a qualquer outro botao de
 * acao/scan do app - por isso a checagem fica no handler dos botoes da barra lateral em vez de
 * espalhada por cada botao individual de cada tela.
 */
public final class RepairLock {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private RepairLock() {
    }

    public static void start() {
        RUNNING.set(true);
    }

    public static void finish() {
        RUNNING.set(false);
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }
}
