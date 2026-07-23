package com.nitroboost.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Relatorio completo do diagnostico: todos os {@link AuditFinding} + agregados uteis para a UI. */
public record AuditReport(List<AuditFinding> findings) {

    public long totalOptimized() {
        return findings.stream().filter(f -> f.status() == AuditFinding.Status.JA_OTIMIZADO).count();
    }

    /** Total de itens com comparacao objetiva possivel (exclui NAO_APLICAVEL) - denominador do placar geral. */
    public long totalApplicable() {
        return findings.stream().filter(f -> f.status() != AuditFinding.Status.NAO_APLICAVEL).count();
    }

    public int totalFindings() {
        return findings.size();
    }

    /** Agrupa por categoria, preservando a ordem em que as categorias foram adicionadas ao relatorio. */
    public Map<String, List<AuditFinding>> findingsByCategory() {
        return findings.stream()
                .collect(Collectors.groupingBy(AuditFinding::category, LinkedHashMap::new, Collectors.toList()));
    }
}
