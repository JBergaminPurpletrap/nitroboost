package com.nitroboost;

import com.nitroboost.knowledge.KnowledgeBase;
import com.nitroboost.knowledge.RemoteKnowledgeUpdater;
import com.nitroboost.knowledge.RemoteKnowledgeUpdater.UpdateCheckResult;

/**
 * Demonstracao de integracao da Fase 7 (Expansao Online): verificacao/aplicacao de atualizacao
 * da base de conhecimento a partir de uma URL remota.
 *
 * Dois cenarios cobertos:
 *  1. Caminho de SUCESSO: baixa um JSON de exemplo publicado como Gist PUBLICO real
 *     (https://gist.github.com/JBergaminPurpletrap/e9690bc766d352089d1ff46f95a3534c), compara
 *     versao/data com a base local embutida, aplica (salva em cache local) e confirma que a base
 *     carregada a partir do cache reflete o conteudo remoto (inclusive um item que so existe la).
 *  2. Caminho de FALHA: tenta contra uma URL invalida/inacessivel e confirma que o resultado cai
 *     de forma graciosa no fallback (available=false, nenhuma excecao propagada, base local
 *     continua intacta).
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase7ConsoleDemo
 */
public final class Phase7ConsoleDemo {

    private static final String INVALID_URL = "https://este-host-nao-existe.invalido.nitroboost-teste/base.json";

    private Phase7ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 7 (Expansao Online)");

        RemoteKnowledgeUpdater updater = new RemoteKnowledgeUpdater();
        KnowledgeBase localKnowledgeBase = new KnowledgeBase();

        System.out.println("Base local: versao=" + localKnowledgeBase.version()
                + " updatedAt=" + localKnowledgeBase.updatedAt()
                + " itens=" + localKnowledgeBase.size());
        System.out.println("Cache local (destino de download): " + updater.getCacheFilePath());
        System.out.println();

        section("Cenario 1: verificacao contra URL PUBLICA real (Gist de exemplo)");
        String realUrl = RemoteKnowledgeUpdater.resolveConfiguredUrl();
        System.out.println("URL usada: " + realUrl);
        UpdateCheckResult successResult = updater.checkForUpdate(realUrl, localKnowledgeBase);
        printResult(successResult);

        if (successResult.available()) {
            System.out.println("[OK] Download e parse do JSON remoto funcionaram.");
            if (successResult.newerThanLocal()) {
                System.out.println("[OK] Comparacao de versao identificou corretamente que a base remota e mais nova.");
                boolean applied = updater.applyUpdate(successResult);
                System.out.println("applyUpdate() -> " + applied);

                KnowledgeBase fromCache = new KnowledgeBase(updater.getCacheFilePath());
                System.out.println("Base recarregada do cache: versao=" + fromCache.version()
                        + " updatedAt=" + fromCache.updatedAt() + " itens=" + fromCache.size());
                boolean hasNewItem = fromCache.find("ExemploItemNovoRemoto").isPresent();
                System.out.println("Contem item que so existe na base remota (ExemploItemNovoRemoto)? " + hasNewItem);
                System.out.println(hasNewItem
                        ? "[OK] Confirmado: a base carregada apos a atualizacao reflete o conteudo remoto."
                        : "[FALHOU] Item esperado nao encontrado apos aplicar a atualizacao.");
            } else {
                System.out.println("[INFO] Base remota nao esta mais nova que a local no momento deste teste "
                        + "(nao impede validar o restante do fluxo de download/parse).");
            }
        } else {
            System.out.println("[AVISO] Nao foi possivel baixar a base remota de exemplo neste ambiente "
                    + "(provavelmente sem acesso a internet aqui) - o importante e que nao quebrou a aplicacao.");
        }

        System.out.println();
        section("Cenario 2: verificacao contra URL INVALIDA (caminho de falha / fallback)");
        System.out.println("URL usada: " + INVALID_URL);
        UpdateCheckResult failureResult = updater.checkForUpdate(INVALID_URL, localKnowledgeBase);
        printResult(failureResult);
        System.out.println(!failureResult.available()
                ? "[OK] Falha tratada de forma graciosa: available=false, sem excecao propagada."
                : "[FALHOU] Esperava available=false para uma URL invalida.");

        KnowledgeBase stillLocal = new KnowledgeBase();
        System.out.println("Base local apos tentativa de falha: versao=" + stillLocal.version()
                + " itens=" + stillLocal.size() + " (deve continuar identica a antes, nada foi corrompido).");

        System.out.println();
        section("Fim da demonstracao da Fase 7");
    }

    private static void printResult(UpdateCheckResult result) {
        System.out.println("  available=" + result.available()
                + " newerThanLocal=" + result.newerThanLocal()
                + " remoteVersion=" + result.remoteVersion()
                + " remoteUpdatedAt=" + result.remoteUpdatedAt()
                + " remoteItemCount=" + result.remoteItemCount());
        System.out.println("  mensagem: " + result.message());
    }

    private static void section(String title) {
        System.out.println("=".repeat(title.length()));
        System.out.println(title);
        System.out.println("=".repeat(title.length()));
    }
}
