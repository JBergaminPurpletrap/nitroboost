package com.nitroboost.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemClassificationTest {

    @Test
    void fromLabel_reconheceSeguro() {
        assertEquals(ItemClassification.SEGURO, ItemClassification.fromLabel("seguro"));
    }

    @Test
    void fromLabel_reconheceEssencial() {
        assertEquals(ItemClassification.ESSENCIAL, ItemClassification.fromLabel("essencial"));
    }

    @Test
    void fromLabel_reconheceDepende() {
        assertEquals(ItemClassification.DEPENDE, ItemClassification.fromLabel("depende"));
    }

    @Test
    void fromLabel_eToleranteAEspacoECaixa() {
        assertEquals(ItemClassification.SEGURO, ItemClassification.fromLabel("  Seguro  "));
    }

    @Test
    void fromLabel_valorDesconhecidoCaiNoPadraoNeutroDepende() {
        assertEquals(ItemClassification.DEPENDE, ItemClassification.fromLabel("valor-que-nao-existe"));
    }

    @Test
    void fromLabel_nuloCaiNoPadraoNeutroDepende() {
        assertEquals(ItemClassification.DEPENDE, ItemClassification.fromLabel(null));
    }
}
