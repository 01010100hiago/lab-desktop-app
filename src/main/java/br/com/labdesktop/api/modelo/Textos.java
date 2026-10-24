package br.com.labdesktop.api.modelo;

import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Higiene dos DTOs. TODO texto que vem do servidor é não confiável (ARQUITETURA 1.3, princípio 4): descrição de
 * projeto, README, branch, nome de container, texto de tela. Limpar já na construção do DTO garante que nenhuma
 * tela esqueça: controles C0/C1 e bidi saem, e um texto que começaria com {@code <html} é neutralizado (o Swing
 * renderizaria HTML em rótulos).
 *
 * <p>Os números e as datas não passam por aqui: já são tipos fortes.
 */
final class Textos {

    private Textos() { }

    /** Texto de uma linha (nome, branch, status, URL). */
    static String linha(String texto) {
        return texto == null ? null : TextoSeguro.limparLinha(texto);
    }

    /** Texto de várias linhas (README, STATUS). */
    static String bloco(String texto) {
        return texto == null ? null : TextoSeguro.limpar(texto);
    }

    /** Lista imutável, sem elementos nulos e sem {@code null} no lugar da lista. */
    static <T> List<T> lista(List<T> origem) {
        return origem == null ? List.of() : origem.stream().filter(Objects::nonNull).toList();
    }

    /** Lista de textos de uma linha. */
    static List<String> linhas(List<String> origem) {
        return origem == null ? List.of()
            : origem.stream().filter(Objects::nonNull).map(TextoSeguro::limparLinha).toList();
    }

    /** Mapa imutável sem valores nulos, com a chave limpa. */
    static <V> Map<String, V> mapa(Map<String, V> origem) {
        if (origem == null) {
            return Map.of();
        }
        return origem.entrySet().stream()
            .filter(e -> e.getKey() != null && e.getValue() != null)
            .collect(Collectors.toUnmodifiableMap(e -> TextoSeguro.limparLinha(e.getKey()), Map.Entry::getValue,
                (a, b) -> b));
    }

    /** Aplica {@code f} a cada elemento (DTOs aninhados) tolerando {@code null}. */
    static <T, R> List<R> mapear(List<T> origem, Function<T, R> f) {
        return origem == null ? List.of() : origem.stream().filter(Objects::nonNull).map(f).toList();
    }
}
