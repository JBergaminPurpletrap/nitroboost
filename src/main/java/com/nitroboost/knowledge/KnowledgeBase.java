package com.nitroboost.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Carrega a base de conhecimento ({@code knowledge-base.json}, empacotada em
 * {@code src/main/resources}) e permite consultar a classificacao/descricao
 * de um item pelo nome (processo, servico ou entrada de startup).
 *
 * A busca e propositalmente tolerante: nomes reais capturados pelos
 * scanners raramente batem 100% com o nome "bonito" da base de conhecimento
 * (ex: processo "OneDrive.exe" vs entrada da base "OneDrive"), entao {@link
 * #find(String)} tenta, em ordem: (1) match exato, (2) match sem o sufixo
 * ".exe", (3) match parcial (contains) como ultimo recurso.
 */
public class KnowledgeBase {

    /**
     * Um item conhecido da base, com sua classificacao e explicacoes em
     * portugues claro (regra de ouro do projeto: texto para o usuario final,
     * sem jargao tecnico excessivo).
     */
    public record KnowledgeEntry(String name, String type, ItemClassification classification,
                                  String description, String disableImpact, String keepImpact) {
    }

    private static final String RESOURCE_PATH = "/knowledge-base.json";
    private static final int MIN_LENGTH_FOR_FUZZY_MATCH = 4;

    private final List<KnowledgeEntry> entries;
    private final Map<String, KnowledgeEntry> entriesByNormalizedName;

    public KnowledgeBase() {
        this.entries = loadFromClasspath();
        this.entriesByNormalizedName = new HashMap<>();
        for (KnowledgeEntry entry : entries) {
            entriesByNormalizedName.putIfAbsent(normalize(entry.name()), entry);
        }
    }

    public List<KnowledgeEntry> all() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /** Busca um item pelo nome, sem restricao de tipo. */
    public Optional<KnowledgeEntry> find(String rawName) {
        return find(rawName, null);
    }

    /**
     * Busca um item pelo nome, opcionalmente restringindo por tipo
     * ('process' | 'service' | 'startup'). Quando {@code type} e nulo, o
     * tipo e ignorado na busca.
     */
    public Optional<KnowledgeEntry> find(String rawName, String type) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(rawName);
        String withoutExe = normalized.endsWith(".exe") ? normalized.substring(0, normalized.length() - 4) : normalized;

        Optional<KnowledgeEntry> exact = matching(entriesByNormalizedName.get(normalized), type);
        if (exact.isPresent()) {
            return exact;
        }
        Optional<KnowledgeEntry> withoutExeMatch = matching(entriesByNormalizedName.get(withoutExe), type);
        if (withoutExeMatch.isPresent()) {
            return withoutExeMatch;
        }

        // Ultimo recurso: correspondencia parcial (contains) - evita nomes muito curtos
        // para nao gerar falsos positivos (ex: "RM" combinando com qualquer coisa).
        for (KnowledgeEntry entry : entries) {
            if (type != null && !type.equalsIgnoreCase(entry.type())) {
                continue;
            }
            String entryNormalized = normalize(entry.name());
            if (entryNormalized.length() < MIN_LENGTH_FOR_FUZZY_MATCH) {
                continue;
            }
            if (withoutExe.contains(entryNormalized) || entryNormalized.contains(withoutExe)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private Optional<KnowledgeEntry> matching(KnowledgeEntry entry, String type) {
        if (entry == null) {
            return Optional.empty();
        }
        if (type != null && !type.equalsIgnoreCase(entry.type())) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private List<KnowledgeEntry> loadFromClasspath() {
        List<KnowledgeEntry> result = new ArrayList<>();
        try (InputStream inputStream = KnowledgeBase.class.getResourceAsStream(RESOURCE_PATH)) {
            if (inputStream == null) {
                System.err.println("[NITRO BOOST] Base de conhecimento nao encontrada no classpath: " + RESOURCE_PATH);
                return result;
            }
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(inputStream);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                System.err.println("[NITRO BOOST] knowledge-base.json nao possui um array 'items' valido.");
                return result;
            }
            for (JsonNode node : items) {
                result.add(new KnowledgeEntry(
                        textOrEmpty(node, "nome"),
                        textOrEmpty(node, "tipo"),
                        ItemClassification.fromLabel(textOrEmpty(node, "classificacao")),
                        textOrEmpty(node, "descricao"),
                        textOrEmpty(node, "impacto_desativar"),
                        textOrEmpty(node, "impacto_manter")
                ));
            }
        } catch (IOException e) {
            System.err.println("[NITRO BOOST] Erro ao carregar base de conhecimento: " + e.getMessage());
        }
        return result;
    }

    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? "" : value.asText();
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.knowledge.KnowledgeBase
     */
    public static void main(String[] args) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        System.out.println("===== NITRO BOOST - Base de Conhecimento =====");
        System.out.println("Total de itens carregados: " + knowledgeBase.size());
        System.out.println();
        for (KnowledgeEntry entry : knowledgeBase.all()) {
            System.out.printf("- %-30s [%-8s] classificacao=%-9s -> %s%n",
                    entry.name(), entry.type(), entry.classification(), entry.description());
        }
    }
}
