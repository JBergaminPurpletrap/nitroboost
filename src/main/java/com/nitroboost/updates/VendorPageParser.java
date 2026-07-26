package com.nitroboost.updates;

import java.util.Optional;

/**
 * Extrai a versao mais recente de BIOS anunciada na pagina de suporte de um fabricante (Fase 11 -
 * Nivel 2, "melhor esforco" - pode parar de funcionar quando o fabricante muda a estrutura do
 * site, e isso e esperado, nao um bug a "consertar as pressas", conforme o proprio documento da
 * fase). Uma implementacao por fabricante, isolada, para que a quebra de uma (ex: ASUS mudou o
 * layout) nunca afete as outras - mesmo espirito ja usado em {@link VendorLinkStrategy}.
 *
 * No lancamento desta parte (Nivel 2), so {@link AsusPageParser} existe. Os outros 3 fabricantes
 * cobertos pelo Nivel 1 (MSI/Gigabyte/ASRock) ainda NAO tem parser de Nivel 2 implementado - ver
 * PROGRESS.md para o estado atual e transparente sobre isso. Nao e uma limitacao permanente: podem
 * ser adicionados depois seguindo exatamente este mesmo padrao.
 */
public interface VendorPageParser {

    /** Nome do fabricante que este parser entende (deve bater com {@link VendorLinkStrategy#vendorName()}). */
    String vendorName();

    /**
     * Tenta extrair a versao mais recente de BIOS anunciada na pagina HTML fornecida. Nunca lanca
     * excecao para fora - qualquer falha de parsing (estrutura mudou, HTML inesperado, modelo nao
     * listado) devolve {@link Optional#empty()}, que o chamador trata como "nao foi possivel
     * verificar automaticamente agora", nunca como erro fatal.
     */
    Optional<String> extractLatestBiosVersion(String htmlContent);
}
