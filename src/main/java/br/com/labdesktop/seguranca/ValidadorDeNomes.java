package br.com.labdesktop.seguranca;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * As regex do agente (CONTRATO-API 1.3 e 6.2), repetidas no app. Todo nome que vai entrar numa linha de
 * comando de {@code exec} SSH passa por aqui ANTES (ARQUITETURA 13, "injeção de comando nos exec").
 */
public final class ValidadorDeNomes {

    /** Nome de projeto (e de pasta em /srv/lab/projects). */
    public static final Pattern PROJETO = Pattern.compile("^[a-z0-9][a-z0-9-]{0,48}$");
    /** Nome de container do Docker. */
    public static final Pattern CONTAINER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");
    /** {@code nome_do_dispositivo} da entrada do token no agente. */
    public static final Pattern DISPOSITIVO = Pattern.compile("^[a-z0-9-]{1,40}$");
    /** SHA-256 em hexadecimal minúsculo. */
    public static final Pattern HASH_SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    /** Código TOTP: seis dígitos. */
    public static final Pattern CODIGO_TOTP = Pattern.compile("^[0-9]{6}$");
    /** Texto do token do cliente nativo. */
    public static final Pattern TOKEN = Pattern.compile("^lab_[A-Za-z0-9_-]{43}$");
    /** Id interno (chave e servidor no cofre): curto, em minúsculas, nunca sai do Mac. */
    public static final Pattern ID_LOCAL = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    /** Usuário do SSH. Não aceita espaço nem hífen na frente (nunca vira opção de comando). */
    public static final Pattern USUARIO_SSH = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,31}$");
    /** Host: nome DNS, IPv4 ou IPv6 sem colchetes. Sem espaço, controle ou hífen na frente. */
    public static final Pattern HOST = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.:_-]{0,252}$");

    /** Nomes que o agente reserva e que nunca são projeto comum. */
    public static final Set<String> PROJETOS_RESERVADOS = Set.of("infra", "painel");

    private ValidadorDeNomes() { }

    public static boolean projetoValido(String nome) {
        return casa(PROJETO, nome);
    }

    public static boolean containerValido(String nome) {
        return casa(CONTAINER, nome);
    }

    public static boolean dispositivoValido(String nome) {
        return casa(DISPOSITIVO, nome);
    }

    public static boolean hashSha256Valido(String hex) {
        return casa(HASH_SHA256, hex);
    }

    public static boolean idLocalValido(String id) {
        return casa(ID_LOCAL, id);
    }

    public static boolean usuarioSshValido(String usuario) {
        return casa(USUARIO_SSH, usuario);
    }

    public static boolean hostValido(String host) {
        return casa(HOST, host);
    }

    /**
     * Normaliza o que o usuário digitou como código do autenticador (aceita "123 456", como o Google
     * Authenticator mostra) e devolve os seis dígitos, ou {@code null} se não for um código.
     */
    public static String normalizarCodigoTotp(String digitado) {
        if (digitado == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < digitado.length(); i++) {
            char c = digitado.charAt(i);
            if (c == ' ' || c == ' ') {
                continue;
            }
            sb.append(c);
        }
        String limpo = sb.toString();
        return CODIGO_TOTP.matcher(limpo).matches() ? limpo : null;
    }

    /** Lança {@link IllegalArgumentException} se o nome do projeto não passar na regex do agente. */
    public static String exigirProjeto(String nome) {
        if (!projetoValido(nome)) {
            throw new IllegalArgumentException("Nome de projeto inválido.");
        }
        return nome;
    }

    /**
     * Sugere o {@code nome_do_dispositivo} a partir do nome do Mac ("Mac-de-Teste.local" vira
     * "mac-de-teste"): minúsculas, só [a-z0-9-], até 40 caracteres, nunca vazio.
     */
    public static String sugerirNomeDeDispositivo(String nomeDoMac) {
        String base = nomeDoMac == null ? "" : nomeDoMac.strip().toLowerCase(Locale.ROOT);
        int ponto = base.indexOf('.');
        if (ponto > 0) {
            base = base.substring(0, ponto); // ".local", ".home", domínio
        }
        String so = base.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (so.length() > 40) {
            so = so.substring(0, 40).replaceAll("-+$", "");
        }
        return so.isEmpty() ? "mac" : so;
    }

    private static boolean casa(Pattern padrao, String texto) {
        return texto != null && padrao.matcher(texto).matches();
    }
}
