package com.nitroboost.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Testa {@link ActionExecutor#displaySettingsCommand()} isoladamente - so valida que o comando do
 * botao "Abrir Configuracoes de Tela" (Fase 14 Parte 2, Nivel 1) esta montado corretamente, SEM
 * executa-lo de verdade (regra de ouro do projeto: nunca abrir uma tela de configuracoes real num
 * teste automatizado).
 */
class ActionExecutorDisplayCommandTest {

    @Test
    void comandoAbreMsSettingsDisplay() {
        assertArrayEquals(new String[] {"cmd", "/c", "start", "ms-settings:display"},
                ActionExecutor.displaySettingsCommand());
    }
}
