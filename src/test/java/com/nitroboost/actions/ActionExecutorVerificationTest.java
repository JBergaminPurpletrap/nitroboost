package com.nitroboost.actions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link ActionExecutor#verifyPostAction} isoladamente - o mecanismo central da Melhoria de
 * Confiabilidade solicitada pelo usuario (ver docs/PROGRESS.md): antes, quase toda acao decidia
 * sucesso/falha so pelo codigo de saida do comando; agora, quando o comando reporta sucesso, uma
 * releitura real do estado (via os scanners ja existentes) confirma se a mudanca realmente
 * aconteceu.
 *
 * Todos os casos usam callbacks fixos (nunca chamam PowerShell/reg/etc de verdade) - cobre os 3
 * cenarios pedidos:
 *  (a) comando reporta sucesso E releitura confirma o valor esperado -&gt; sucesso real;
 *  (b) comando reporta sucesso MAS releitura mostra valor diferente -&gt; falha detectada;
 *  (c) releitura falha/lanca excecao -&gt; cai para o resultado baseado no codigo de saida, com nota.
 */
class ActionExecutorVerificationTest {

    @Test
    void comandoFalhouPeloCodigoDeSaida_naoTentaReler_devolveFalhaOriginalSemAlteracao() {
        AtomicBoolean rereadCalled = new AtomicBoolean(false);
        Callable<String> reread = () -> {
            rereadCalled.set(true);
            return "qualquer coisa";
        };

        ActionExecutor.ActionResult result = ActionExecutor.verifyPostAction(
                false, "Falha ao aplicar (comando retornou codigo != 0).", 42L,
                reread, actual -> true, "esperado");

        assertFalse(result.success());
        assertFalse(rereadCalled.get(), "a releitura nao deveria ser tentada quando o comando ja falhou");
        assertEquals("Falha ao aplicar (comando retornou codigo != 0).", result.message());
        assertEquals(42L, result.backupId());
    }

    @Test
    void comandoSucesso_releituraConfirmaValorEsperado_sucessoReal() {
        ActionExecutor.ActionResult result = ActionExecutor.verifyPostAction(
                true, "Valor de 'X' alterado para 1.", 10L,
                () -> "1",
                actual -> "1".equals(actual),
                "1");

        assertTrue(result.success());
        assertEquals("Valor de 'X' alterado para 1.", result.message());
    }

    @Test
    void comandoSucesso_releituraMostraValorDiferente_falhaDetectada() {
        // Caso classico que motivou esta melhoria: "reg add" reporta sucesso, mas uma politica de
        // grupo (ou qualquer outra coisa) sobrescreveu o valor - a releitura pega isso.
        ActionExecutor.ActionResult result = ActionExecutor.verifyPostAction(
                true, "Valor de 'X' alterado para 1.", 10L,
                () -> "0",
                actual -> "1".equals(actual),
                "1");

        assertFalse(result.success());
        assertTrue(result.message().contains("0"), "mensagem deveria conter o valor atual relido");
        assertTrue(result.message().contains("1"), "mensagem deveria conter o valor esperado");
        assertTrue(result.message().toLowerCase().contains("comando reportou sucesso"));
    }

    @Test
    void releituraLancaExcecao_caiParaResultadoOriginal_comNotaNaMensagem() {
        ActionExecutor.ActionResult result = ActionExecutor.verifyPostAction(
                true, "Valor de 'X' alterado para 1.", 10L,
                () -> { throw new IllegalStateException("comando de verificacao nao rodou"); },
                actual -> "1".equals(actual),
                "1");

        assertTrue(result.success(), "sem confirmacao nao deveria inventar uma falha nova");
        assertTrue(result.message().startsWith("Valor de 'X' alterado para 1."),
                "mensagem original deveria ser preservada");
        assertTrue(result.message().contains("nao foi possivel confirmar"),
                "mensagem deveria deixar claro que a confirmacao por releitura falhou");
    }

    @Test
    void releituraDevolveOptionalAusenteViaOrElseThrow_tratadoComoFalhaDeVerificacao() {
        // Padrao usado nos call sites reais (ex: ServiceScanner.findByName(...).orElseThrow(...)):
        // um Optional vazio vira excecao, que cai no fallback gracioso, nao numa falha nova.
        Callable<String> reread = () -> java.util.Optional.<String>empty()
                .orElseThrow(() -> new IllegalStateException("item nao encontrado na releitura"));

        ActionExecutor.ActionResult result = ActionExecutor.verifyPostAction(
                true, "Acao aplicada com sucesso.", null, reread, actual -> true, "esperado");

        assertTrue(result.success());
        assertTrue(result.message().contains("nao foi possivel confirmar"));
    }
}
