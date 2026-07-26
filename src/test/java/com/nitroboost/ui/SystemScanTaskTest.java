package com.nitroboost.ui;

import com.nitroboost.knowledge.ItemClassification;
import com.nitroboost.knowledge.KnowledgeBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa {@link SystemScanTask#buildItem} (extraido como {@code static} justamente para ser
 * reaproveitado por {@link com.nitroboost.audit.SystemAuditEngine} e testado sem depender de
 * JavaFX/scanners reais) - confirma o comportamento de fallback "nao catalogado" quando o nome
 * nao existe na {@link KnowledgeBase}, complementando o teste de {@code KnowledgeBase.find()} que
 * ja confirma o Optional vazio na camada de baixo.
 */
class SystemScanTaskTest {

    private final KnowledgeBase knowledgeBase = new KnowledgeBase();

    @Test
    void itemConhecido_usaClassificacaoEDescricaoDaBase() {
        ScannedItem item = SystemScanTask.buildItem(knowledgeBase, SystemScanTask.CATEGORY_SERVICE,
                "service", "Spooler", "Running / Auto", null);

        assertTrue(item.catalogued());
        assertEquals(ItemClassification.DEPENDE, item.classification());
        assertTrue(item.description().toLowerCase().contains("impress"));
    }

    @Test
    void itemDesconhecido_caiNoFallbackNaoCatalogado() {
        ScannedItem item = SystemScanTask.buildItem(knowledgeBase, SystemScanTask.CATEGORY_SERVICE,
                "service", "ServicoTotalmenteInventadoXYZ987", "Running / Auto", null);

        assertFalse(item.catalogued(), "Item sem entrada na base deveria ter catalogued=false.");
        assertEquals(ItemClassification.DEPENDE, item.classification(),
                "Sem classificacao conhecida, deve cair no padrao neutro DEPENDE (nunca parecer 'seguro' por engano).");
        assertEquals("Item ainda nao catalogado na base de conhecimento interna do NITRO BOOST.", item.description());
        assertTrue(item.disableImpact().contains("Impacto nao mapeado"));
    }
}
