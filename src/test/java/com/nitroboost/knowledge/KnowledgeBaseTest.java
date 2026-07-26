package com.nitroboost.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carrega o {@code knowledge-base.json} REAL do classpath (nao um JSON de teste isolado) - o
 * objetivo e confirmar que a base empacotada continua parseavel e que a busca por nome funciona
 * contra o conteudo de verdade usado pela aplicacao.
 */
class KnowledgeBaseTest {

    private final KnowledgeBase knowledgeBase = new KnowledgeBase();

    @Test
    void carregaBaseComItensReais() {
        assertTrue(knowledgeBase.size() > 0, "A base de conhecimento deveria carregar pelo menos 1 item do classpath.");
    }

    @Test
    void buscaItemExistente_Spooler_retornaClassificacaoEDescricaoCorretas() {
        // Confirmado lendo knowledge-base.json: "Spooler" (tipo "service") existe com
        // classificacao "depende" e uma descricao nao vazia sobre a fila de impressao.
        Optional<KnowledgeBase.KnowledgeEntry> found = knowledgeBase.find("Spooler", "service");

        assertTrue(found.isPresent(), "Item 'Spooler' deveria ser encontrado na base de conhecimento.");
        KnowledgeBase.KnowledgeEntry entry = found.get();
        assertEquals(ItemClassification.DEPENDE, entry.classification());
        assertTrue(entry.description().toLowerCase().contains("impress"),
                "Descricao do Spooler deveria mencionar 'impressao'/'impressoras'. Descricao atual: " + entry.description());
    }

    @Test
    void buscaItemExistente_Fax_retornaClassificacaoSeguro() {
        Optional<KnowledgeBase.KnowledgeEntry> found = knowledgeBase.find("Fax", "service");

        assertTrue(found.isPresent(), "Item 'Fax' deveria ser encontrado na base de conhecimento.");
        assertEquals(ItemClassification.SEGURO, found.get().classification());
    }

    @Test
    void buscaItemInexistente_retornaOptionalVazio() {
        // Nome fabricado, que nao existe em nenhuma forma (exata, sem ".exe", nem parcial) na base.
        Optional<KnowledgeBase.KnowledgeEntry> found = knowledgeBase.find("ProcessoTotalmenteInventadoXYZ987");

        assertFalse(found.isPresent(),
                "Um item inexistente deve resultar em Optional vazio no nivel do KnowledgeBase - "
                        + "o fallback para 'nao catalogado' e responsabilidade de outra camada (SystemScanTask.buildItem).");
    }

    @Test
    void buscaTolerante_removeSufixoExe() {
        // "OneDrive" (tipo "startup") existe na base sem o sufixo .exe - a busca deve
        // encontrar mesmo passando "OneDrive.exe", como os scanners reais capturam.
        Optional<KnowledgeBase.KnowledgeEntry> found = knowledgeBase.find("OneDrive.exe", "startup");

        assertTrue(found.isPresent(), "Busca deveria tolerar o sufixo '.exe' e encontrar 'OneDrive'.");
        assertEquals("OneDrive", found.get().name());
    }
}
