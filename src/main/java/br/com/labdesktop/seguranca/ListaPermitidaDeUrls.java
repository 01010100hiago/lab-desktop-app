package br.com.labdesktop.seguranca;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lista de permitidos para {@code Desktop.browse} (ARQUITETURA 8.2). O servidor manda URLs (login do Claude,
 * pull request, hostname de projeto) e uma URL hostil abriria no navegador do usuário com um clique.
 *
 * <p>Regras comuns: só {@code https}, sem usuário na URL ({@code https://claude.ai@malvado.example}), porta
 * padrão, host comparado por igualdade exata (nunca por "termina com", exceto os hostnames de projeto,
 * que são por domínio e rótulos DNS válidos).
 */
public final class ListaPermitidaDeUrls {

    /** O que o app aceita abrir. */
    public enum Tipo {
        /** Página de login do Claude: {@code https://claude.ai/...} ou {@code https://claude.com/...}. */
        LOGIN,
        /** Pull request: {@code https://github.com/<usuario>/<repo>/pull/<n>}. */
        PULL_REQUEST,
        /** Hostname de projeto: {@code https://*.example.com/...}. */
        HOST_DE_PROJETO
    }

    private static final Set<String> HOSTS_DE_LOGIN = Set.of("claude.ai", "claude.com");
    private static final String SUFIXO_DE_PROJETO = ".example.com";
    private static final Pattern ROTULO_DNS = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern CAMINHO_DE_PR = Pattern.compile(
        "^/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/pull/[0-9]{1,9}(/(files|commits|checks))?/?$");

    private ListaPermitidaDeUrls() { }

    /** Devolve a URI se ela passa na lista do {@code tipo}; vazio se não. Nunca lança. */
    public static Optional<URI> validar(Tipo tipo, String url) {
        if (url == null || url.length() > 2048 || contemControle(url)) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getRawUserInfo() != null
            || uri.getHost() == null
            || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String caminho = uri.getRawPath() == null ? "" : uri.getRawPath();
        boolean ok = switch (tipo) {
            case LOGIN -> HOSTS_DE_LOGIN.contains(host);
            case PULL_REQUEST -> host.equals("github.com") && CAMINHO_DE_PR.matcher(caminho).matches();
            case HOST_DE_PROJETO -> hostDeProjetoValido(host);
        };
        return ok ? Optional.of(uri) : Optional.empty();
    }

    /** Atalho de {@link #validar}. */
    public static boolean permitida(Tipo tipo, String url) {
        return validar(tipo, url).isPresent();
    }

    private static boolean hostDeProjetoValido(String host) {
        if (!host.endsWith(SUFIXO_DE_PROJETO)) {
            return false;
        }
        String prefixo = host.substring(0, host.length() - SUFIXO_DE_PROJETO.length());
        if (prefixo.isEmpty()) {
            return false;
        }
        for (String rotulo : prefixo.split("\\.", -1)) {
            if (!ROTULO_DNS.matcher(rotulo).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean contemControle(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || Character.isWhitespace(c) && c != ' ') {
                return true;
            }
        }
        return false;
    }
}
