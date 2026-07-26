package com.nitroboost.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Le a taxa de atualizacao (Hz) da tela principal do Windows via {@code EnumDisplaySettings}
 * ({@code user32.dll}), binding manual via JNA - mesmo padrao de mapeamento direto de DLL nativa
 * usado desde a Fase 0 ({@link JnaNativeTest}) e na Fase 10 ({@link MemoryCleaner}).
 *
 * <p>Nao existe binding pronto para {@code EnumDisplaySettings}/{@code DEVMODE} no jna-platform
 * 5.19.1 usado neste projeto (confirmado - {@code User32} so tem {@code EnumDisplayMonitors}), entao
 * a struct {@code DEVMODE} e mapeada manualmente aqui, seguindo o layout oficial da API Win32
 * ({@code DEVMODEA}, variante ANSI - suficiente aqui, ja que os unicos campos usados sao numericos;
 * o nome do dispositivo/formulario nunca e lido).
 *
 * <p>Responsabilidade unica: apenas LER a taxa de atualizacao atual e as taxas suportadas pela
 * resolucao atual - nunca altera nada no sistema (essa e a acao de Nivel 2, deliberadamente NAO
 * implementada nesta fase - ver {@code NITRO-BOOST-fase14-melhorias-diagnostico.md}, secao D.3).
 * Nunca lanca excecao para fora: se a leitura falhar, retorna {@code Optional.empty()}/lista vazia,
 * mesmo padrao defensivo dos demais scanners.
 */
public class DisplayScanner {

    // DEVMODE.dmDisplayFrequency::iModeNum = -1 (ENUM_CURRENT_SETTINGS, definido pela API Win32).
    private static final int ENUM_CURRENT_SETTINGS = -1;

    // Limite defensivo contra um driver de video defeituoso que nunca devolva "false" - nenhum
    // monitor real chega perto disso (drivers comuns tem algumas dezenas de modos, no maximo).
    private static final int MAX_MODES_TO_SCAN = 4096;

    /**
     * Layout oficial da struct {@code DEVMODEA} do Win32 (variante ANSI) - ver
     * https://learn.microsoft.com/windows/win32/api/wingdi/ns-wingdi-devmodea. Os campos de uniao
     * do Win32 (posicao/orientacao de impressora) sao representados aqui pela variante "impressora"
     * (8 campos {@code short}), que ocupa o mesmo tamanho (16 bytes) que a variante "tela" da uniao -
     * o layout de memoria fica correto mesmo sem usarmos esses campos especificos, que so importam
     * para impressoras.
     */
    public static class DEVMODE extends Structure {
        public byte[] dmDeviceName = new byte[32];
        public short dmSpecVersion;
        public short dmDriverVersion;
        public short dmSize;
        public short dmDriverExtra;
        public int dmFields;
        public short dmOrientation;
        public short dmPaperSize;
        public short dmPaperLength;
        public short dmPaperWidth;
        public short dmScale;
        public short dmCopies;
        public short dmDefaultSource;
        public short dmPrintQuality;
        public short dmColor;
        public short dmDuplex;
        public short dmYResolution;
        public short dmTTOption;
        public short dmCollate;
        public byte[] dmFormName = new byte[32];
        public short dmLogPixels;
        public int dmBitsPerPel;
        public int dmPelsWidth;
        public int dmPelsHeight;
        public int dmDisplayFlags;
        public int dmDisplayFrequency;
        public int dmICMMethod;
        public int dmICMIntent;
        public int dmMediaType;
        public int dmDitherType;
        public int dmReserved1;
        public int dmReserved2;
        public int dmPanningWidth;
        public int dmPanningHeight;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dmDeviceName", "dmSpecVersion", "dmDriverVersion", "dmSize", "dmDriverExtra",
                    "dmFields", "dmOrientation", "dmPaperSize", "dmPaperLength", "dmPaperWidth", "dmScale",
                    "dmCopies", "dmDefaultSource", "dmPrintQuality", "dmColor", "dmDuplex", "dmYResolution",
                    "dmTTOption", "dmCollate", "dmFormName", "dmLogPixels", "dmBitsPerPel", "dmPelsWidth",
                    "dmPelsHeight", "dmDisplayFlags", "dmDisplayFrequency", "dmICMMethod", "dmICMIntent",
                    "dmMediaType", "dmDitherType", "dmReserved1", "dmReserved2", "dmPanningWidth", "dmPanningHeight");
        }
    }

    /**
     * Mapeamento manual de {@code EnumDisplaySettingsA} - mesmo raciocinio de {@code Native.load}
     * direto a uma DLL do Windows ja usado em {@link MemoryCleaner.Ntdll}.
     */
    private interface User32Ext extends Library {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class);

        boolean EnumDisplaySettingsA(String lpszDeviceName, int iModeNum, DEVMODE lpDevMode);
    }

    /** Taxa de atualizacao atual da tela principal, em Hz - vazio se nao foi possivel detectar. */
    public Optional<Integer> getCurrentRefreshRate() {
        try {
            DEVMODE current = readMode(ENUM_CURRENT_SETTINGS);
            if (current == null || current.dmDisplayFrequency <= 0) {
                return Optional.empty();
            }
            return Optional.of(current.dmDisplayFrequency);
        } catch (Throwable t) {
            System.err.println("[NITRO BOOST] Erro ao ler a taxa de atualizacao atual da tela: " + t.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Todas as taxas de atualizacao (Hz) distintas suportadas pela MESMA resolucao atual (largura x
     * altura), em ordem crescente - itera {@code iModeNum} de 0 em diante ate a API devolver
     * {@code false} (fim da lista de modos do driver). Lista vazia se a leitura falhar.
     */
    public List<Integer> getAvailableRefreshRates() {
        try {
            DEVMODE current = readMode(ENUM_CURRENT_SETTINGS);
            if (current == null) {
                return List.of();
            }
            int width = current.dmPelsWidth;
            int height = current.dmPelsHeight;

            Set<Integer> rates = new LinkedHashSet<>();
            for (int modeNum = 0; modeNum < MAX_MODES_TO_SCAN; modeNum++) {
                DEVMODE mode = readMode(modeNum);
                if (mode == null) {
                    break;
                }
                if (mode.dmPelsWidth == width && mode.dmPelsHeight == height && mode.dmDisplayFrequency > 0) {
                    rates.add(mode.dmDisplayFrequency);
                }
            }
            List<Integer> sorted = new ArrayList<>(rates);
            sorted.sort(Integer::compareTo);
            return sorted;
        } catch (Throwable t) {
            System.err.println("[NITRO BOOST] Erro ao ler as taxas de atualizacao suportadas pela tela: " + t.getMessage());
            return new ArrayList<>();
        }
    }

    /** Maior taxa dentre {@link #getAvailableRefreshRates()} - vazio se nenhuma taxa foi detectada. */
    public Optional<Integer> getMaxRefreshRate() {
        List<Integer> rates = getAvailableRefreshRates();
        return rates.isEmpty() ? Optional.empty() : Optional.of(rates.get(rates.size() - 1));
    }

    /** {@code null} se a chamada nativa falhar (nunca lanca excecao para fora dos metodos publicos acima). */
    private DEVMODE readMode(int modeNum) {
        DEVMODE mode = new DEVMODE();
        mode.dmSize = (short) mode.size();
        boolean ok = User32Ext.INSTANCE.EnumDisplaySettingsA(null, modeNum, mode);
        return ok ? mode : null;
    }

    /**
     * Permite testar isoladamente via console:
     *   ./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.DisplayScanner
     */
    public static void main(String[] args) {
        DisplayScanner scanner = new DisplayScanner();
        System.out.println("===== NITRO BOOST - Taxa de Atualizacao da Tela =====");
        System.out.println("Taxa atual: " + scanner.getCurrentRefreshRate().map(r -> r + " Hz").orElse("(nao foi possivel detectar)"));
        System.out.println("Taxas suportadas (resolucao atual): " + scanner.getAvailableRefreshRates());
        System.out.println("Taxa maxima suportada: " + scanner.getMaxRefreshRate().map(r -> r + " Hz").orElse("(nao foi possivel detectar)"));
    }
}
