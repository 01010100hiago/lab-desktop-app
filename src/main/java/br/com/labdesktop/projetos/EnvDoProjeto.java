package br.com.labdesktop.projetos;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * O {@code .env} do projeto: onde ficam as senhas e as variáveis que o compose só referencia. O app nunca sobrescreve uma chave que o usuário já
 * tem; só ACRESCENTA o que falta, com uma senha aleatória (que ele não mostra) quando a variável é uma senha.
 */
public final class EnvDoProjeto {

    private static final String ALFABETO = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TAMANHO_DA_SENHA = 24;

    private EnvDoProjeto() { }

    /** O que mudou no texto do {@code .env}. */
    public record Mescla(String texto, List<String> acrescentadas, List<String> jaExistiam) {
        public boolean mudou() {
            return !acrescentadas.isEmpty();
        }
    }

    /** Uma senha aleatória (24 letras e números, sem os que se confundem), de {@link SecureRandom}; vale em qualquer arquivo e URL. */
    public static String novaSenha(SecureRandom azar) {
        StringBuilder sb = new StringBuilder(TAMANHO_DA_SENHA);
        for (int i = 0; i < TAMANHO_DA_SENHA; i++) {
            sb.append(ALFABETO.charAt(azar.nextInt(ALFABETO.length())));
        }
        return sb.toString();
    }

    /** O texto com as chaves que faltam acrescentadas ao fim (nada do que já havia é tocado); {@code atual} {@code null} é arquivo novo. */
    public static Mescla acrescentar(String atual, Map<String, String> novas) {
        String base = atual == null ? "" : atual;
        List<String> acrescentadas = new ArrayList<>();
        List<String> jaExistiam = new ArrayList<>();
        StringBuilder sb = new StringBuilder(base);
        for (Map.Entry<String, String> e : new LinkedHashMap<>(novas).entrySet()) {
            Pattern achar = Pattern.compile("(?m)^[ \\t]*(?:export[ \\t]+)?" + Pattern.quote(e.getKey()) + "[ \\t]*=");
            if (achar.matcher(base).find()) {
                jaExistiam.add(e.getKey());
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            acrescentadas.add(e.getKey());
        }
        return new Mescla(sb.toString(), acrescentadas, jaExistiam);
    }
}
