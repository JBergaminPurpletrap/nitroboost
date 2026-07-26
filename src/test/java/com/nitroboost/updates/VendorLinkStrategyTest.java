package com.nitroboost.updates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirma que {@link VendorLinkStrategy#resolve(String)} escolhe a estrategia certa a partir do
 * texto bruto devolvido pela OSHI ({@code Baseboard.getManufacturer()}), e que o fallback
 * generico funciona para fabricantes desconhecidos - conforme a Fase 11.
 */
class VendorLinkStrategyTest {

    @Test
    void resolveAsus_apartirDoTextoRealDaOshi() {
        VendorLinkStrategy strategy = VendorLinkStrategy.resolve("ASUSTeK COMPUTER INC.");
        assertInstanceOf(AsusLinkStrategy.class, strategy);
        assertTrue(strategy.deepLinkUrl("ROG STRIX B550-F").contains("asus.com"));
    }

    @Test
    void resolveMsi_apartirDoTextoRealDaOshi() {
        VendorLinkStrategy strategy = VendorLinkStrategy.resolve("Micro-Star International Co., Ltd.");
        assertInstanceOf(MsiLinkStrategy.class, strategy);
    }

    @Test
    void resolveGigabyte_apartirDoTextoRealDaOshi() {
        VendorLinkStrategy strategy = VendorLinkStrategy.resolve("Gigabyte Technology Co., Ltd.");
        assertInstanceOf(GigabyteLinkStrategy.class, strategy);
    }

    @Test
    void resolveAsRock_apartirDoTextoRealDaOshi() {
        VendorLinkStrategy strategy = VendorLinkStrategy.resolve("ASRock Inc.");
        assertInstanceOf(AsRockLinkStrategy.class, strategy);
    }

    @Test
    void resolveFabricanteDesconhecido_caiNoGenericLinkStrategy() {
        VendorLinkStrategy strategy = VendorLinkStrategy.resolve("Some Random White-Label OEM");
        assertInstanceOf(GenericLinkStrategy.class, strategy);
        // Camada 3 (busca externa) nunca falha, mesmo sem fabricante reconhecido.
        assertTrue(strategy.externalSearchUrl("Modelo XYZ").contains("google.com"));
        // Camadas 1 e 2 nao se aplicam sem um site de fabricante conhecido.
        assertNull(strategy.deepLinkUrl("Modelo XYZ"));
        assertNull(strategy.vendorSearchUrl("Modelo XYZ"));
    }

    @Test
    void resolveFabricanteNulo_naoLancaExcecaoECaiNoGenerico() {
        VendorLinkStrategy strategy = VendorLinkStrategy.resolve(null);
        assertInstanceOf(GenericLinkStrategy.class, strategy);
    }
}
