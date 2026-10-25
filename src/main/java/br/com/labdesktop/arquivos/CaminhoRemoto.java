package br.com.labdesktop.arquivos;

import java.util.ArrayList;
import java.util.List;

/**
 * Caminhos do SERVIDOR (sempre com barra, sempre absolutos), tratados como texto. Nada aqui toca o disco do Mac nem o
 * servidor: é a validação que vale para qualquer caminho antes de ele virar uma chamada SFTP.
 *
 * <p>Regras: sem NUL nem caractere de controle (um {@code \n} num nome engana a leitura de log e quebra comando de shell);
 * {@code .} e {@code ..} são resolvidos no texto (um {@code ..} no topo fica no topo, como no POSIX); barras repetidas e a
 * barra do fim somem. Quem precisa de mais (ficar dentro de uma pasta, links) usa o {@link Escopo}.
 */
public final class CaminhoRemoto {

    public static final String RAIZ = "/";

    private CaminhoRemoto() { }

    /** {@code true} se o texto não tem NUL, controle C0 nem DEL. */
    public static boolean semControle(String texto) {
        if (texto == null) {
            return false;
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Põe o caminho na forma canônica do texto: absoluto, sem {@code .}, sem {@code ..}, sem barras repetidas.
     *
     * @throws ErroSftp {@code NOME_INVALIDO} se for vazio, relativo ou tiver caractere de controle
     */
    public static String normalizar(String caminho) throws ErroSftp {
        if (caminho == null || caminho.isEmpty()) {
            throw ErroSftp.nomeInvalido("O caminho está vazio.");
        }
        if (!semControle(caminho)) {
            throw ErroSftp.nomeInvalido("O caminho tem caracteres que não posso usar.");
        }
        if (caminho.charAt(0) != '/') {
            throw ErroSftp.nomeInvalido("O caminho precisa começar na raiz do servidor (/).");
        }
        List<String> partes = new ArrayList<>();
        for (String p : caminho.split("/", -1)) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (!partes.isEmpty()) {
                    partes.remove(partes.size() - 1);
                }
                continue;
            }
            partes.add(p);
        }
        return partes.isEmpty() ? RAIZ : "/" + String.join("/", partes);
    }

    /** Resolve um alvo de link: absoluto vale como está; relativo vale a partir da pasta do link. */
    public static String resolverAlvo(String pastaDoLink, String alvo) throws ErroSftp {
        if (alvo == null || alvo.isEmpty()) {
            throw ErroSftp.nomeInvalido("O link está vazio.");
        }
        return normalizar(alvo.startsWith("/") ? alvo : pastaDoLink + "/" + alvo);
    }

    /** Valida UM nome de arquivo ou pasta (o que o usuário digita em "Renomear" e "Nova pasta"). */
    public static String validarNome(String nome) throws ErroSftp {
        if (nome == null) {
            throw ErroSftp.nomeInvalido("Digite um nome.");
        }
        String n = nome.strip();
        if (n.isEmpty()) {
            throw ErroSftp.nomeInvalido("Digite um nome.");
        }
        if (n.equals(".") || n.equals("..")) {
            throw ErroSftp.nomeInvalido("\"" + n + "\" não pode ser usado como nome.");
        }
        if (n.indexOf('/') >= 0) {
            throw ErroSftp.nomeInvalido("O nome não pode ter barra (/). Para mudar de pasta, use Mover para.");
        }
        if (!semControle(n)) {
            throw ErroSftp.nomeInvalido("O nome tem caracteres que não posso usar.");
        }
        if (n.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
            throw ErroSftp.nomeInvalido("O nome é comprido demais (no máximo 255 caracteres).");
        }
        return n;
    }

    /** Pasta + nome, já validados. */
    public static String juntar(String pasta, String nome) throws ErroSftp {
        String p = normalizar(pasta);
        String n = validarNome(nome);
        return p.equals(RAIZ) ? "/" + n : p + "/" + n;
    }

    /** A pasta que contém o caminho ({@code /} contém a si mesma). */
    public static String pai(String caminho) {
        String c = normalizarSemErro(caminho);
        int i = c.lastIndexOf('/');
        return i <= 0 ? RAIZ : c.substring(0, i);
    }

    /** O último trecho ({@code ""} para a raiz). */
    public static String nome(String caminho) {
        String c = normalizarSemErro(caminho);
        return c.equals(RAIZ) ? "" : c.substring(c.lastIndexOf('/') + 1);
    }

    /** Os trechos, de cima para baixo ({@code /srv/lab} dá {@code [srv, lab]}). */
    public static List<String> partes(String caminho) {
        String c = normalizarSemErro(caminho);
        if (c.equals(RAIZ)) {
            return List.of();
        }
        return List.of(c.substring(1).split("/"));
    }

    /** {@code true} se {@code caminho} é a {@code raiz} ou está dentro dela (por trechos: {@code /ab} não está em {@code /a}). */
    public static boolean dentroDe(String raiz, String caminho) {
        String r = normalizarSemErro(raiz);
        String c = normalizarSemErro(caminho);
        if (r.equals(RAIZ)) {
            return true;
        }
        return c.equals(r) || c.startsWith(r + "/");
    }

    /** O caminho de {@code caminho} visto a partir de {@code raiz} ({@code ""} se são o mesmo; o caminho todo se está fora). */
    public static String relativo(String raiz, String caminho) {
        String r = normalizarSemErro(raiz);
        String c = normalizarSemErro(caminho);
        if (!dentroDe(r, c)) {
            return c;
        }
        if (r.equals(RAIZ)) {
            return c.equals(RAIZ) ? "" : c.substring(1);
        }
        return c.equals(r) ? "" : c.substring(r.length() + 1);
    }

    private static String normalizarSemErro(String caminho) {
        try {
            return normalizar(caminho);
        } catch (ErroSftp e) {
            return RAIZ;
        }
    }
}
