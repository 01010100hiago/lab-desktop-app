package br.com.labdesktop.ui.galeria;

import java.util.Set;

/**
 * O que identifica o computador que roda os testes de galeria (nome do usuário e pasta pessoal), para os testes conferirem que nada disso aparece nas imagens. Quando o nome do usuário
 * é um dos nomes inventados das imagens (por exemplo {@code ana} ou {@code admin}) ou é curto demais para uma conferência segura, não há o que proibir: devolve um valor que nunca aparece.
 */
final class ComputadorDeTeste {

    /** Um texto que nenhuma tela mostra. */
    static final String NUNCA_APARECE = "\u0000sem-valor";

    private static final Set<String> NOMES_DAS_IMAGENS = Set.of("ana", "beto", "admin", "root", "labuser", "ubuntu", "debian", "user", "test", "teste", "demo", "runner", "app", "usuario");

    private ComputadorDeTeste() { }

    /** O nome do usuário do computador, ou {@link #NUNCA_APARECE} se ele se confunde com um nome inventado das imagens. */
    static String usuario() {
        String u = System.getProperty("user.name");
        return u == null || u.length() < 4 || NOMES_DAS_IMAGENS.contains(u.toLowerCase(java.util.Locale.ROOT)) ? NUNCA_APARECE : u;
    }

    /** A pasta pessoal do usuário, ou {@link #NUNCA_APARECE} quando o usuário é um dos nomes inventados. */
    static String casa() {
        String h = System.getProperty("user.home");
        return h == null || h.length() < 6 || NUNCA_APARECE.equals(usuario()) ? NUNCA_APARECE : h;
    }
}
