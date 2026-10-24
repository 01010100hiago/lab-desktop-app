package br.com.labdesktop.seguranca;

import java.util.regex.Pattern;

/**
 * Tira segredo de qualquer texto que vá para log, mensagem de erro ou diálogo (ARQUITETURA 4.2 e 6.5).
 *
 * <p>É a última rede de proteção, não a primeira: o certo é o segredo nem chegar ao texto. Cobre o token do
 * cliente nativo (credencial do app), blocos {@code BEGIN/END} de chave, valores de
 * {@code Authorization}, o {@code secret=} (e a URI inteira) de {@code otpauth://} do 2FA e pares do tipo
 * {@code senha=...}.
 */
public final class Redator {

    /** O que fica no lugar de um segredo. */
    public static final String MASCARA = "***";

    private static final Pattern BLOCO_DE_CHAVE = Pattern.compile(
        "-----BEGIN [A-Z0-9 ]+-----.*?-----END [A-Z0-9 ]+-----", Pattern.DOTALL);
    // Linha cortada no meio do log: começa o bloco e não chega ao END.
    private static final Pattern BLOCO_ABERTO = Pattern.compile(
        "-----BEGIN [A-Z0-9 ]+-----.*", Pattern.DOTALL);
    private static final Pattern TOKEN = Pattern.compile("lab_[A-Za-z0-9_-]{20,}");
    private static final Pattern AUTORIZACAO = Pattern.compile(
        "(?i)(authorization\\s*[:=]\\s*)(?:(?:bearer|basic)\\s+)?[^\\s,;\"']+");
    private static final Pattern BEARER_SOLTO = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern URI_OTPAUTH = Pattern.compile("(?i)otpauth://[^\\s\"'<>]+");
    private static final Pattern SEGREDO_PARAMETRO = Pattern.compile("(?i)([?&;\\s]secret=)[A-Za-z0-9=%]+");
    private static final Pattern PAR_DE_SENHA = Pattern.compile(
        "(?i)\\b(senha|password|passphrase|segredo)\\s*[:=]\\s*[^\\s,;\"']+");

    private Redator() { }

    /** Devolve o texto com os segredos mascarados. {@code null} vira texto vazio. */
    public static String redigir(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        String r = texto;
        r = BLOCO_DE_CHAVE.matcher(r).replaceAll("[bloco de chave removido]");
        r = BLOCO_ABERTO.matcher(r).replaceAll("[bloco de chave removido]");
        r = URI_OTPAUTH.matcher(r).replaceAll("otpauth://" + MASCARA);
        r = SEGREDO_PARAMETRO.matcher(r).replaceAll("$1" + MASCARA);
        r = AUTORIZACAO.matcher(r).replaceAll("$1" + MASCARA);
        r = BEARER_SOLTO.matcher(r).replaceAll("Bearer " + MASCARA);
        r = TOKEN.matcher(r).replaceAll("lab_" + MASCARA);
        r = PAR_DE_SENHA.matcher(r).replaceAll("$1=" + MASCARA);
        return r;
    }

    /** Mensagem de uma exceção já redigida (e sem {@code null}). */
    public static String redigir(Throwable erro) {
        if (erro == null) {
            return "";
        }
        String msg = erro.getMessage();
        return redigir(msg != null ? msg : erro.getClass().getSimpleName());
    }
}
