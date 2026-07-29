package com.nitroboost.core;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilitarios de comparacao/parsing de valores de registro do Windows, compartilhados entre
 * {@link com.nitroboost.audit.SystemAuditEngine} (comparacao com o valor recomendado da base de
 * conhecimento, Fase 9) e {@link com.nitroboost.actions.ActionExecutor} (confirmacao pos-acao por
 * releitura - Melhoria de Confiabilidade solicitada pelo usuario, ver docs/PROGRESS.md).
 *
 * Extraido para ca porque {@code dwordValuesEqual}/{@code parseDword} originalmente viviam como
 * metodos {@code static} de visibilidade de pacote dentro de {@code SystemAuditEngine} (pacote
 * {@code audit}) - inacessiveis para o pacote {@code actions}. Nenhuma logica de comparacao foi
 * reescrita aqui, apenas movida/exposta; {@code SystemAuditEngine.dwordValuesEqual} agora delega
 * para {@link #dwordValuesEqual}, preservando 100% do comportamento ja validado pelos testes
 * existentes ({@code SystemAuditEngineTest}).
 */
public final class RegistryValueUtils {

    // Exemplo de linha real do "reg query": "    AllowTelemetry    REG_DWORD    0x1"
    private static final Pattern VALUE_LINE_PATTERN = Pattern.compile("REG_[A-Z_]+\\s+(\\S+)");

    private RegistryValueUtils() {
    }

    /**
     * Compara dois valores DWORD de registro numericamente (nao como texto puro): {@code reg
     * query} sempre devolve hexadecimal (ex: "0x26"), mas um valor esperado/recomendado as vezes
     * esta documentado em decimal (ex: "38") - "38" e "0x26" sao numericamente iguais e devem
     * contar como iguais. Cai para comparacao de texto se algum dos dois nao for numerico (nao
     * deveria acontecer para chaves DWORD reais, mas evita excecao no caso raro).
     */
    public static boolean dwordValuesEqual(String current, String recommended) {
        try {
            return parseDword(current) == parseDword(recommended);
        } catch (NumberFormatException e) {
            return current.trim().equalsIgnoreCase(recommended.trim());
        }
    }

    private static long parseDword(String value) {
        String trimmed = value.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("0x")) {
            return Long.parseLong(trimmed.substring(2), 16);
        }
        return Long.parseLong(trimmed, 10);
    }

    /**
     * Extrai o valor de uma linha de saida do {@code reg query} para o {@code valueName} pedido
     * (mesmo padrao ja usado por {@link TelemetryScanner#readValue}) - usado pela releitura de
     * confirmacao pos-acao do {@code ActionExecutor}. Optional vazio = valor nao encontrado na
     * saida (chave/valor nao existe nesta maquina, ou a saida nao tem o formato esperado).
     */
    public static Optional<String> parseRegQueryValue(String output, String valueName) {
        if (output == null || valueName == null) {
            return Optional.empty();
        }
        for (String line : output.split("\\r?\\n")) {
            if (line.contains(valueName)) {
                Matcher matcher = VALUE_LINE_PATTERN.matcher(line);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }
}
