package com.nitroboost;

import com.nitroboost.core.HardwareIdentityScanner;
import com.nitroboost.core.HardwareIdentityScanner.BoardIdentity;
import com.nitroboost.db.DatabaseManager;
import com.nitroboost.updates.AsusLinkStrategy;
import com.nitroboost.updates.OnlineUpdateChecker;
import com.nitroboost.updates.RobotsTxtChecker;
import com.nitroboost.updates.VendorLinkStrategy;

import java.sql.SQLException;

/**
 * Demonstracao de integracao da Fase 11 - Nivel 2 (Verificacao Automatica Online, melhor esforco),
 * via console: exercita {@code robots.txt} -> requisicao HTTP real -> parser -> cache de 24h, com
 * um cenario de SUCESSO real contra a pagina da ASUS e um cenario de FALHA proposital (dominio
 * invalido), confirmando em ambos que o app nunca trava e sempre devolve uma mensagem pronta para
 * o usuario.
 *
 * <b>Decisao de escopo (ver PROGRESS.md para o raciocinio completo):</b> a maquina de
 * desenvolvimento real e um notebook Dell ("Dell Inc."), que nao e nenhum dos 4 fabricantes de
 * placa-mae avulsa cobertos pelo Nivel 1 (ASUS/MSI/Gigabyte/ASRock) - a Fase 11 foi desenhada
 * pensando em placas-mae de desktop, nao notebooks OEM (a Dell tem seu proprio mecanismo de
 * atualizacao de BIOS, fora de escopo). Por isso este demo usa um modelo ASUS real e conhecido
 * ("ROG STRIX B650-A GAMING WIFI") para validar o Nivel 2 de verdade, em vez do hardware real desta
 * maquina (que so teria a camada 3/generica disponivel, sem nada para o Nivel 2 verificar).
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase11Nivel2ConsoleDemo
 */
public final class Phase11Nivel2ConsoleDemo {

    private static final String SAMPLE_ASUS_MODEL = "ROG STRIX B650-A GAMING WIFI";

    private Phase11Nivel2ConsoleDemo() {
    }

    public static void main(String[] args) throws SQLException {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 11 Nivel 2 (Verificacao Automatica Online)");

        System.out.println("Hardware real desta maquina de desenvolvimento (Nivel 1, so para contexto):");
        HardwareIdentityScanner scanner = new HardwareIdentityScanner();
        BoardIdentity identity = scanner.scanBoardIdentity();
        System.out.println("  Fabricante: " + (identity.manufacturerDetected() ? identity.manufacturer() : "(nao detectado)"));
        System.out.println("  Modelo: " + (identity.modelDetected() ? identity.model() : "(nao detectado)"));
        VendorLinkStrategy realStrategy = VendorLinkStrategy.resolve(identity.manufacturer());
        System.out.println("  Estrategia resolvida: " + realStrategy.vendorName());
        System.out.println();
        System.out.println("Esta maquina e um notebook Dell - nenhum dos 4 fabricantes cobertos pelo Nivel 1 -");
        System.out.println("entao o Nivel 2 e testado abaixo contra um modelo ASUS real e conhecido, conforme");
        System.out.println("decisao documentada em PROGRESS.md.");

        DatabaseManager databaseManager = new DatabaseManager();
        databaseManager.initializeSchema();

        section("Teste 1/4 - Checagem de robots.txt (requisicao HTTP real ao dominio da ASUS)");
        RobotsTxtChecker robotsTxtChecker = new RobotsTxtChecker();
        String asusDeepLink = new AsusLinkStrategy().deepLinkUrl(SAMPLE_ASUS_MODEL);
        System.out.println("URL a verificar: " + asusDeepLink);
        boolean allowed = robotsTxtChecker.isAllowed(asusDeepLink);
        System.out.println("Permitido pelo robots.txt da ASUS: " + allowed
                + (allowed ? " (nenhum 'Disallow' relevante encontrado)" : " (bloqueado - Nivel 2 cai pro Nivel 1)"));

        section("Teste 2/4 - Cenario de SUCESSO real (requisicao HTTP + parser contra a pagina real da ASUS)");
        OnlineUpdateChecker checker = new OnlineUpdateChecker(databaseManager);
        System.out.println("Modelo consultado: " + SAMPLE_ASUS_MODEL);
        System.out.println("URL consultada (camada 1): " + asusDeepLink);
        OnlineUpdateChecker.CheckResult successAttempt = checker.checkForUpdate("ASUS", SAMPLE_ASUS_MODEL, asusDeepLink);
        System.out.println("sucesso=" + successAttempt.success()
                + " | fromCache=" + successAttempt.fromCache()
                + " | versaoEncontrada=" + successAttempt.latestVersion());
        System.out.println("Mensagem exibida ao usuario: " + successAttempt.message());
        if (!successAttempt.success()) {
            System.out.println("(Nao foi possivel extrair desta vez - pagina da ASUS pode ter mudado de estrutura.");
            System.out.println(" Isso e esperado/aceitavel para o Nivel 2 - o importante e que o fallback funcionou,");
            System.out.println(" sem excecao nem tela travada. Ver mensagem acima, igual a que o usuario veria.)");
        }

        section("Teste 3/4 - Cache de 24h (mesma consulta de novo - NAO deve bater na rede outra vez)");
        long startedAt = System.nanoTime();
        OnlineUpdateChecker.CheckResult cachedAttempt = checker.checkForUpdate("ASUS", SAMPLE_ASUS_MODEL, asusDeepLink);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        System.out.println("sucesso=" + cachedAttempt.success()
                + " | fromCache=" + cachedAttempt.fromCache()
                + " | versaoEncontrada=" + cachedAttempt.latestVersion()
                + " | tempo=" + elapsedMs + "ms");
        System.out.println(cachedAttempt.fromCache()
                ? "[OK] Segunda chamada veio do cache (fromCache=true), nenhuma requisicao HTTP nova foi feita."
                : "[ATENCAO] Segunda chamada NAO veio do cache - verificar UpdateCheckCache.");

        section("Teste 4/4 - Cenario de FALHA proposital (dominio inexistente, para provar o fallback gracioso)");
        String invalidUrl = "https://dominio-que-nao-existe-nitroboost-teste-999.invalid/pagina";
        System.out.println("URL invalida usada de proposito: " + invalidUrl);
        OnlineUpdateChecker.CheckResult failureAttempt = checker.checkForUpdate(
                "ASUS", "MODELO-TESTE-FALHA-FASE11-NIVEL2", invalidUrl);
        System.out.println("sucesso=" + failureAttempt.success()
                + " | fromCache=" + failureAttempt.fromCache()
                + " | versaoEncontrada=" + failureAttempt.latestVersion());
        System.out.println("Mensagem exibida ao usuario: " + failureAttempt.message());
        System.out.println(!failureAttempt.success()
                ? "[OK] Falha tratada de forma graciosa: nenhuma excecao propagou, mensagem clara devolvida."
                : "[ATENCAO] Esperava falha aqui - dominio invalido nao deveria ter sucedido.");

        section("Teste extra - vendor sem parser implementado (MSI/Gigabyte/ASRock ainda nao tem Nivel 2)");
        OnlineUpdateChecker.CheckResult noParserAttempt = checker.checkForUpdate(
                "MSI", "MODELO-TESTE-SEM-PARSER", "https://www.msi.com/Motherboard/MODELO-TESTE-SEM-PARSER");
        System.out.println("sucesso=" + noParserAttempt.success() + " | " + noParserAttempt.message());
        System.out.println(!noParserAttempt.success()
                ? "[OK] Fabricante sem parser cai direto no fallback do Nivel 1, sem tentar rede."
                : "[ATENCAO] Nao deveria ter sucesso para um fabricante sem parser implementado.");

        System.out.println();
        System.out.println("Fim da demonstracao da Fase 11 Nivel 2.");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
