package com.nitroboost;

import com.nitroboost.core.HardwareIdentityScanner;
import com.nitroboost.core.HardwareIdentityScanner.BoardIdentity;
import com.nitroboost.core.HardwareIdentityScanner.DriverInfo;
import com.nitroboost.updates.VendorLinkStrategy;

import java.util.List;

/**
 * Demonstracao de integracao da Fase 11 - Parte 1 (Nivel 1: Deteccao Local + Link Direto), via
 * console: le a identidade real da placa-mae desta maquina (fabricante/modelo/BIOS) via
 * {@link HardwareIdentityScanner}, le os drivers relevantes instalados, resolve a
 * {@link VendorLinkStrategy} correspondente ao fabricante detectado e imprime as URLs das 3
 * camadas de fallback (link profundo / busca do fabricante / busca externa).
 *
 * IMPORTANTE: este demo NUNCA chama {@code Desktop.browse()} - so monta e imprime as URLs como
 * texto, para conferencia manual (regra de ouro do projeto: nunca abrir de verdade um navegador
 * a partir de um teste automatizado via console).
 *
 * Rodar via:
 *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase11Part1ConsoleDemo
 */
public final class Phase11Part1ConsoleDemo {

    private Phase11Part1ConsoleDemo() {
    }

    public static void main(String[] args) {
        section("NITRO BOOST - Demonstracao de Integracao da Fase 11 Parte 1 (Nivel 1: BIOS/Drivers)");

        HardwareIdentityScanner scanner = new HardwareIdentityScanner();

        BoardIdentity identity = scanner.scanBoardIdentity();
        System.out.println("Fabricante: " + (identity.manufacturerDetected() ? identity.manufacturer() : "(nao detectado)"));
        System.out.println("Modelo: " + (identity.modelDetected() ? identity.model() : "(nao detectado)"));
        System.out.println("Versao da BIOS: " + (identity.biosVersion().isBlank() ? "(nao detectada)" : identity.biosVersion()));
        System.out.println("Data de release da BIOS: " + (identity.biosReleaseDate().isBlank() ? "(nao detectada)" : identity.biosReleaseDate()));

        section("Drivers relevantes (rede / audio-video / sistema)");
        List<DriverInfo> drivers = scanner.scanRelevantDrivers();
        System.out.println("Total encontrado: " + drivers.size());
        System.out.println();
        System.out.printf("%-8s %-45s %-16s %s%n", "CLASSE", "DISPOSITIVO", "VERSAO", "FABRICANTE");
        for (DriverInfo d : drivers) {
            System.out.printf("%-8s %-45s %-16s %s%n",
                    d.deviceClass(), d.deviceName(), d.version().isBlank() ? "?" : d.version(),
                    d.manufacturer().isBlank() ? "?" : d.manufacturer());
        }

        section("Estrategia de link de suporte resolvida");
        VendorLinkStrategy strategy = VendorLinkStrategy.resolve(identity.manufacturer());
        String model = identity.modelDetected() ? identity.model() : identity.manufacturer();
        System.out.println("Fabricante reconhecido pela estrategia: " + strategy.vendorName());
        System.out.println("Modelo usado para montar as URLs: " + (model.isBlank() ? "(nenhum - fabricante/modelo nao detectados)" : model));
        System.out.println();
        System.out.println("[Camada 1 - Link profundo]      " + orNull(strategy.deepLinkUrl(model)));
        System.out.println("[Camada 2 - Busca no site]       " + orNull(strategy.vendorSearchUrl(model)));
        System.out.println("[Camada 3 - Busca externa]       " + orNull(strategy.externalSearchUrl(model)));

        section("URLs geradas para os 4 fabricantes cobertos (usando o modelo real detectado, para comparacao)");
        for (VendorLinkStrategy s : List.of(new com.nitroboost.updates.AsusLinkStrategy(),
                new com.nitroboost.updates.MsiLinkStrategy(),
                new com.nitroboost.updates.GigabyteLinkStrategy(),
                new com.nitroboost.updates.AsRockLinkStrategy())) {
            String sampleModel = model.isBlank() ? "ROG STRIX B650-A GAMING WIFI" : model;
            System.out.println();
            System.out.println(s.vendorName() + " (modelo de exemplo: " + sampleModel + ")");
            System.out.println("  Camada 1: " + s.deepLinkUrl(sampleModel));
            System.out.println("  Camada 2: " + s.vendorSearchUrl(sampleModel));
            System.out.println("  Camada 3: " + s.externalSearchUrl(sampleModel));
        }

        System.out.println();
        System.out.println("NOTA: nenhuma URL acima foi aberta no navegador - este demo so monta e imprime as strings,");
        System.out.println("conforme a regra de ouro do projeto (nunca abrir Desktop.browse() num teste automatizado).");
        System.out.println();
        System.out.println("Fim da demonstracao da Fase 11 Parte 1.");
    }

    private static String orNull(String value) {
        return value == null ? "(nao aplicavel - fabricante nao reconhecido)" : value;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("===== " + title + " =====");
    }
}
