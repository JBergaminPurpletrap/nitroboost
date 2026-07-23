package com.nitroboost.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Carrega o conteudo dos tutoriais internos (texto em Markdown, guardado em
 * {@code src/main/resources/tutorials/}) e disponibiliza para a UI, de forma
 * generica o suficiente para outros itens que precisem de acao manual no
 * futuro - basta adicionar uma linha no mapa {@link #TUTORIALS_BY_KEY}
 * apontando para o novo arquivo, sem precisar reescrever esta classe.
 */
public class TutorialProvider {

    /** Um tutorial carregado: chave interna, titulo (primeira linha "# Titulo" do arquivo) e conteudo completo. */
    public record Tutorial(String key, String title, String content) {
    }

    /**
     * Chave interna -> caminho do arquivo no classpath. Chave usada para
     * referenciar o tutorial a partir de outras partes da UI (ex: o alerta
     * de XMP na tela de Tutoriais aponta para a chave "xmp-bios").
     */
    private static final Map<String, String> TUTORIALS_BY_KEY = Map.of(
            "xmp-bios", "/tutorials/xmp-bios.md"
    );

    private final Map<String, Tutorial> cache = new LinkedHashMap<>();

    public TutorialProvider() {
        for (Map.Entry<String, String> entry : TUTORIALS_BY_KEY.entrySet()) {
            loadTutorial(entry.getKey(), entry.getValue()).ifPresent(t -> cache.put(entry.getKey(), t));
        }
    }

    /** Todos os tutoriais carregados com sucesso, na ordem declarada. */
    public List<Tutorial> all() {
        return new ArrayList<>(cache.values());
    }

    /** Busca um tutorial pela chave interna (ex: "xmp-bios"). */
    public Optional<Tutorial> find(String key) {
        return Optional.ofNullable(cache.get(key));
    }

    private Optional<Tutorial> loadTutorial(String key, String resourcePath) {
        try (InputStream inputStream = TutorialProvider.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                System.err.println("[NITRO BOOST] Tutorial nao encontrado no classpath: " + resourcePath);
                return Optional.empty();
            }
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            String title = extractTitle(content, key);
            return Optional.of(new Tutorial(key, title, content));
        } catch (IOException e) {
            System.err.println("[NITRO BOOST] Erro ao carregar tutorial '" + key + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Usa a primeira linha "# Titulo" do Markdown como titulo; cai para a propria chave se nao encontrar. */
    private String extractTitle(String content, String fallbackKey) {
        for (String line : content.split("\\R", 2)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
            break;
        }
        return fallbackKey;
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.knowledge.TutorialProvider
     */
    public static void main(String[] args) {
        TutorialProvider provider = new TutorialProvider();
        System.out.println("===== NITRO BOOST - Tutoriais Carregados =====");
        System.out.println("Total: " + provider.all().size());
        for (Tutorial tutorial : provider.all()) {
            System.out.println("- [" + tutorial.key() + "] " + tutorial.title()
                    + " (" + tutorial.content().length() + " caracteres)");
        }
    }
}
