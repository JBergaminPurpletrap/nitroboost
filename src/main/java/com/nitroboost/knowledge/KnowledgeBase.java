package com.nitroboost.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final String version;
    private final String updatedAt;

    /** Carrega sempre a partir do classpath (comportamento original, usado pela UI e pelos demos). */
    public KnowledgeBase() {
        this((Path) null);
    }

    /**
     * Carrega preferencialmente de um arquivo de cache local (JSON baixado por
     * {@link RemoteKnowledgeUpdater}, ex: {@code %USERPROFILE%\.nitroboost\remote-knowledge-base-cache.json}).
     * Se {@code cacheFilePath} for {@code null}, nao existir, ou nao puder ser lido/parseado, cai de
     * volta para a base embutida no classpath - nunca lanca excecao (regra de ouro do projeto).
     */
    public KnowledgeBase(Path cacheFilePath) {
        LoadedData loaded = null;
        if (cacheFilePath != null && Files.isRegularFile(cacheFilePath)) {
            try (InputStream inputStream = Files.newInputStream(cacheFilePath)) {
                loaded = parse(inputStream);
                System.out.println("[NITRO BOOST] Base de conhecimento carregada do cache remoto: " + cacheFilePath);
            } catch (Exception e) {
                System.err.println("[NITRO BOOST] Falha ao carregar cache remoto (" + cacheFilePath
                        + "), usando base local embutida: " + e.getMessage());
            }
        }
        if (loaded == null) {
            try (InputStream inputStream = KnowledgeBase.class.getResourceAsStream(RESOURCE_PATH)) {
                if (inputStream == null) {
                    System.err.println("[NITRO BOOST] Base de conhecimento nao encontrada no classpath: " + RESOURCE_PATH);
                    loaded = new LoadedData(new ArrayList<>(), "", "");
                } else {
                    loaded = parse(inputStream);
                }
            } catch (Exception e) {
                System.err.println("[NITRO BOOST] Erro ao carregar base de conhecimento: " + e.getMessage());
                loaded = new LoadedData(new ArrayList<>(), "", "");
            }
        }
        this.entries = loaded.entries();
        this.version = loaded.version();
        this.updatedAt = loaded.updatedAt();
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

    /** Versao declarada no topo do JSON ({@code "version"}), ou string vazia se ausente. */
    public String version() {
        return version;
    }

    /** Data de atualizacao declarada no topo do JSON ({@code "updatedAt"}), ou string vazia se ausente. */
    public String updatedAt() {
        return updatedAt;
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

    /**
     * Resultado de um parse (entradas + metadados de versao), usado pelo classpath, pelo cache
     * remoto local e por {@link RemoteKnowledgeUpdater} (mesmo pacote) para inspecionar um JSON
     * remoto ja baixado sem precisar salva-lo antes.
     */
    record LoadedData(List<KnowledgeEntry> entries, String version, String updatedAt) {
    }

    /**
     * Faz o parse de um JSON no mesmo schema de {@code knowledge-base.json} (topo com
     * {@code version}/{@code updatedAt} opcionais + array {@code items}). Reusado tanto para o
     * recurso do classpath quanto para o cache baixado por {@link RemoteKnowledgeUpdater} - mesmo
     * schema, duas origens possiveis (YAGNI: nao ha motivo para dois parsers diferentes).
     */
    static LoadedData parse(InputStream inputStream) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(inputStream);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            throw new IOException("JSON nao possui um array 'items' valido.");
        }
        List<KnowledgeEntry> result = new ArrayList<>();
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
        return new LoadedData(result, textOrEmpty(root, "version"), textOrEmpty(root, "updatedAt"));
    }

    private static String textOrEmpty(JsonNode node, String field) {
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
        System.out.println("Versao: " + knowledgeBase.version() + " | Atualizada em: " + knowledgeBase.updatedAt());
        System.out.println("Total de itens carregados: " + knowledgeBase.size());
        System.out.println();
        for (KnowledgeEntry entry : knowledgeBase.all()) {
            System.out.printf("- %-30s [%-8s] classificacao=%-9s -> %s%n",
                    entry.name(), entry.type(), entry.classification(), entry.description());
        }
    }
}
