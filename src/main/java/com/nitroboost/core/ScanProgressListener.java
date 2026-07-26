package com.nitroboost.core;

/**
 * Callback de progresso usado pelos scanners de {@code core/} (Fase 12 Parte C) para reportar o
 * andamento de uma varredura, sem que o scanner precise saber nada de JavaFX - quem traduz essas
 * chamadas para a UI (ex: {@code ProgressBar}/{@code Task.updateProgress}) fica do lado de fora
 * (ver {@code ui.SystemScanTask}, que implementa esse papel de ponte).
 *
 * <p><b>Nota tecnica importante - "progresso real", nao simulado:</b> a maioria dos scanners deste
 * projeto faz UMA unica chamada bloqueante ao PowerShell/{@code reg query}/{@code schtasks} (nao ha
 * como reportar progresso PARCIAL durante essa chamada em si - o processo externo nao devolve
 * nada ate terminar) e SO DEPOIS itera sobre a lista ja parseada para montar os resultados finais.
 * Quando um scanner reporta progresso durante esse loop de pos-processamento (ex: "processando
 * item 45 de 130" enquanto converte cada item bruto em algo pronto para a interface), isso NAO e
 * progresso fake/simulado - e o trabalho de verdade que a JVM esta fazendo item a item, so que a
 * parte mais demorada (a chamada ao sistema operacional) ja tinha terminado antes desse loop
 * comecar. Categorias com poucos itens (ex: {@code PowerPlanScanner}, {@code TelemetryScanner})
 * reportam apenas no inicio ({@code current=0}) e no fim ({@code current=total}), ja que nao ha
 * granularidade util o suficiente para um relato intermediario valer a pena.
 *
 * @see com.nitroboost.ui.SystemScanTask
 */
public interface ScanProgressListener {

    /**
     * @param category nome amigavel da categoria sendo escaneada agora (ex: "Servicos do Windows")
     * @param current  quantos itens dessa categoria ja foram processados
     * @param total    quantos itens essa categoria tem no total (0 se ainda desconhecido)
     * @param message  texto curto e amigavel para exibir (ex: "Analisando NvTelemetryContainer...")
     */
    void onProgress(String category, int current, int total, String message);
}
