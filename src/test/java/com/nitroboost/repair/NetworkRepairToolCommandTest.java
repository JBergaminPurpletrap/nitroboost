package com.nitroboost.repair;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testa a MONTAGEM dos comandos de {@link NetworkRepairTool} isoladamente, SEM executa-los -
 * mesmo padrao de {@code ActionExecutorDisplayCommandTest} (Fase 14).
 *
 * <p><b>Regra de seguranca da Fase 15 Parte B:</b> {@code resetWinsock()}, {@code resetTcpIp()} e
 * {@code renewIp()} alteram configuracao de baixo nivel de rede desta maquina (os dois primeiros so
 * fazem efeito apos reiniciar; o terceiro derruba a conexao momentaneamente) - por isso NUNCA sao
 * chamados de verdade num teste automatizado, so a lista de argumentos que seria passada ao
 * {@link ProcessBuilder} e conferida aqui. {@code flushDns()} e {@code clearArpCache()} sao seguros
 * e rapidos e por isso SAO executados de verdade, mas isso acontece em
 * {@code Phase15Part3ConsoleDemo} (fora da suite JUnit), nao aqui - este arquivo so confere a
 * montagem dos 5 comandos, de forma consistente.
 */
class NetworkRepairToolCommandTest {

    @Test
    void comandoLimparCacheDns() {
        assertEquals(List.of("ipconfig", "/flushdns"), NetworkRepairTool.flushDnsCommand());
    }

    @Test
    void comandoResetWinsock() {
        assertEquals(List.of("netsh", "winsock", "reset"), NetworkRepairTool.resetWinsockCommand());
    }

    @Test
    void comandoResetTcpIp() {
        assertEquals(List.of("netsh", "int", "ip", "reset"), NetworkRepairTool.resetTcpIpCommand());
    }

    @Test
    void comandosRenovarIp_releaseERenewNaOrdemCerta() {
        assertEquals(List.of("ipconfig", "/release"), NetworkRepairTool.releaseCommand());
        assertEquals(List.of("ipconfig", "/renew"), NetworkRepairTool.renewCommand());
    }

    @Test
    void comandoLimparCacheArp() {
        assertEquals(List.of("arp", "-d", "*"), NetworkRepairTool.clearArpCacheCommand());
    }
}
