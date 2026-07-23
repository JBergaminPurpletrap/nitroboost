package com.nitroboost.knowledge;

import com.nitroboost.core.MemorySpeedScanner;
import com.nitroboost.core.MemorySpeedScanner.MemoryModuleSpeed;

import java.util.List;
import java.util.Locale;

/**
 * Interpreta os dados lidos por {@link MemorySpeedScanner} para dar um
 * indicio (nunca uma certeza absoluta) de que o XMP/EXPO da memoria RAM esta
 * desativado na BIOS: se a velocidade configurada/ativa no momento estiver
 * visivelmente abaixo da velocidade nominal/rotulada do modulo, a RAM
 * provavelmente esta rodando na velocidade padrao JEDEC (mais lenta) em vez
 * do perfil XMP/EXPO gravado nela.
 *
 * IMPORTANTE (limitacao conhecida, documentada aqui de proposito): o campo
 * SMBIOS "Speed" que o Windows reporta como velocidade "nominal" nem sempre
 * corresponde exatamente ao perfil XMP mais alto gravado no modulo - em
 * algumas placas-mae/BIOS ele reflete apenas o que o firmware detectou.
 * Por isso este e um INDICIO, nao uma deteccao 100% confiavel - o texto
 * exibido ao usuario deixa isso claro, e o tutorial sempre recomenda
 * conferir no site do fabricante da memoria.
 */
public class XmpAdvisor {

    /** Tolerancia: velocidade configurada abaixo disso da nominal ja e considerada indicio de XMP desligado. */
    private static final double TOLERANCE = 0.90;

    /**
     * @param dataAvailable      se foi possivel ler dados de velocidade da RAM nesta maquina
     * @param likelyXmpDisabled  true se ha indicio de XMP/EXPO desativado
     * @param ratedSpeedMhz      maior velocidade nominal/rotulada entre os modulos detectados (MHz)
     * @param configuredSpeedMhz menor velocidade configurada/ativa entre os modulos detectados (MHz)
     * @param message            mensagem pronta em portugues claro, para exibir na interface
     */
    public record XmpEvaluation(boolean dataAvailable, boolean likelyXmpDisabled,
                                 int ratedSpeedMhz, int configuredSpeedMhz, String message) {
    }

    private final MemorySpeedScanner memorySpeedScanner;

    public XmpAdvisor() {
        this(new MemorySpeedScanner());
    }

    public XmpAdvisor(MemorySpeedScanner memorySpeedScanner) {
        this.memorySpeedScanner = memorySpeedScanner;
    }

    /** Escaneia a RAM da maquina atual e avalia indicio de XMP desativado. */
    public XmpEvaluation evaluate() {
        try {
            return evaluate(memorySpeedScanner.scan());
        } catch (Exception e) {
            // Regra de ouro: nunca deixar uma falha de leitura do sistema quebrar a aplicacao -
            // apenas nao mostrar o alerta.
            System.err.println("[NITRO BOOST] Erro ao avaliar indicio de XMP: " + e.getMessage());
            return new XmpEvaluation(false, false, 0, 0,
                    "Nao foi possivel verificar a velocidade da memoria RAM nesta maquina.");
        }
    }

    /** Avalia uma lista ja escaneada de modulos (facilita testes e reuso). */
    public XmpEvaluation evaluate(List<MemoryModuleSpeed> modules) {
        List<MemoryModuleSpeed> withData = modules.stream()
                .filter(m -> m.ratedSpeedMhz() > 0 && m.configuredClockMhz() > 0)
                .toList();

        if (withData.isEmpty()) {
            return new XmpEvaluation(false, false, 0, 0,
                    "Nao foi possivel verificar a velocidade da memoria RAM nesta maquina.");
        }

        int ratedSpeed = withData.stream().mapToInt(MemoryModuleSpeed::ratedSpeedMhz).max().orElse(0);
        int configuredSpeed = withData.stream().mapToInt(MemoryModuleSpeed::configuredClockMhz).min().orElse(0);
        boolean likelyDisabled = configuredSpeed < ratedSpeed * TOLERANCE;

        String message = likelyDisabled
                ? String.format(Locale.ROOT,
                        "Sua memoria RAM parece estar rodando a %d MHz, mas o modulo suporta ate %d MHz. "
                        + "Isso e um indicio de que o XMP (ou EXPO, em placas AMD) esta desativado na BIOS - "
                        + "veja o tutorial abaixo para habilitar e aproveitar toda a velocidade da sua RAM.",
                        configuredSpeed, ratedSpeed)
                : String.format(Locale.ROOT,
                        "Sua memoria RAM esta rodando a %d MHz, proximo da velocidade nominal do modulo (%d MHz). "
                        + "Nenhum indicio de XMP/EXPO desativado foi encontrado.",
                        configuredSpeed, ratedSpeed);

        return new XmpEvaluation(true, likelyDisabled, ratedSpeed, configuredSpeed, message);
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.knowledge.XmpAdvisor
     */
    public static void main(String[] args) {
        XmpAdvisor advisor = new XmpAdvisor();
        XmpEvaluation evaluation = advisor.evaluate();

        System.out.println("===== NITRO BOOST - Indicio de XMP/EXPO Desativado =====");
        System.out.println("Dados disponiveis: " + evaluation.dataAvailable());
        System.out.println("Velocidade nominal (rated): " + evaluation.ratedSpeedMhz() + " MHz");
        System.out.println("Velocidade configurada (atual): " + evaluation.configuredSpeedMhz() + " MHz");
        System.out.println("XMP provavelmente desativado: " + evaluation.likelyXmpDisabled());
        System.out.println("Mensagem: " + evaluation.message());
    }
}
