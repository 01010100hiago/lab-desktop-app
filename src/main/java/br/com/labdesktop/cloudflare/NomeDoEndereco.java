package br.com.labdesktop.cloudflare;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.util.Optional;

/**
 * O nome de um endereço público (o {@code hostname}) e o que se monta a partir dele. Só UM nível sob o domínio da zona: {@code lab-demo.example.com},
 * nunca {@code a.b.example.com} (o certificado gratuito da Cloudflare cobre {@code *.example.com} e não dois níveis). Modelo puro, sem rede.
 */
public final class NomeDoEndereco {

    /** O começo do comentário que o app grava no registro de DNS (e no {@code config.yml}) para reconhecer o que é dele. */
    public static final String MARCA = "lab-desktop:";
    /** O sufixo do endereço de um túnel: {@code <UUID>.cfargotunnel.com}. */
    public static final String SUFIXO_DO_TUNEL = ".cfargotunnel.com";
    /** Até onde o rótulo pode ir (a regra do DNS). */
    public static final int MAXIMO = 63;

    private NomeDoEndereco() { }

    /** O resultado de conferir um rótulo: vale, ou a frase do que está errado ({@code ""} para o campo ainda vazio). */
    public record Resultado(boolean valido, String mensagem) {
        static final Resultado OK = new Resultado(true, "");
    }

    /** O rótulo vale ({@code [a-z0-9-]}, 1 a 63, sem hífen nas pontas, sem {@code xn--})? */
    public static boolean valido(String rotulo) {
        return rotulo != null && ValidadorDeNomes.rotuloDnsValido(rotulo) && !rotulo.startsWith("xn--");
    }

    /** Confere o que o usuário digitou (sem cortar espaço nem trocar maiúscula: ele vê o que será usado), em frase simples. */
    public static Resultado conferir(String texto) {
        if (texto == null || texto.isEmpty()) {
            return new Resultado(false, "");
        }
        if (valido(texto)) {
            return Resultado.OK;
        }
        if (texto.length() > MAXIMO) {
            return new Resultado(false, "No máximo " + MAXIMO + " caracteres (este tem " + texto.length() + ").");
        }
        if (texto.indexOf('.') >= 0) {
            return new Resultado(false, "Só um nível: o nome não leva ponto (o resto do endereço é o domínio).");
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return new Resultado(false, "Sem espaços: use hífen no lugar (meu-projeto).");
            }
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (Character.isUpperCase(c) && c < 128) {
                return new Resultado(false, "Use só letras minúsculas (sem maiúsculas).");
            }
        }
        for (int i = 0; i < texto.length(); i++) {
            if (texto.charAt(i) > 127 && Character.isLetter(texto.charAt(i))) {
                return new Resultado(false, "Sem acentos nem letras especiais: use de a a z, números e hífen.");
            }
        }
        if (texto.indexOf('_') >= 0) {
            return new Resultado(false, "Sem sublinhado: use hífen no lugar.");
        }
        if (texto.charAt(0) == '-' || texto.charAt(texto.length() - 1) == '-') {
            return new Resultado(false, "O nome não pode começar nem terminar com hífen.");
        }
        if (texto.startsWith("xn--")) {
            return new Resultado(false, "Nomes que começam com xn-- são reservados a letras de outros alfabetos: escolha outro.");
        }
        return new Resultado(false, "Só letras minúsculas, números e hífen.");
    }

    /**
     * A sugestão {@code <prefixo>-<projeto>} (por exemplo {@code lab-demo}); vazia se não dá para montar um nome válido.
     */
    public static String sugerir(String prefixo, String projeto) {
        String p = prefixo == null || prefixo.isBlank() ? "lab" : prefixo;
        String s = p + "-" + (projeto == null ? "" : projeto);
        if (s.length() > MAXIMO) {
            s = s.substring(0, MAXIMO).replaceAll("-+$", "");
        }
        return valido(s) ? s : "";
    }

    /** O endereço completo: {@code <rotulo>.<zona>}. @throws IllegalArgumentException se o rótulo ou a zona não valem */
    public static String hostname(String rotulo, String zona) {
        if (!valido(rotulo)) {
            throw new IllegalArgumentException("Nome do endereço inválido.");
        }
        if (!ValidadorDeNomes.zonaDnsValida(zona)) {
            throw new IllegalArgumentException("Domínio inválido.");
        }
        return rotulo + "." + zona;
    }

    /** O endereço público, com https. */
    public static String urlPublica(String hostname) {
        return "https://" + hostname + "/";
    }

    /** O alvo do CNAME: SEMPRE o UUID do túnel, nunca o nome (o nome pode ser ignorado em silêncio). */
    public static String alvoDoTunel(String uuid) {
        if (!ValidadorDeNomes.uuidDoTunelValido(uuid)) {
            throw new IllegalArgumentException("O túnel precisa do UUID.");
        }
        return uuid + SUFIXO_DO_TUNEL;
    }

    /** O destino da regra do túnel: {@code http://<container>:<porta>}. */
    public static String servicoDoTunel(String container, int porta) {
        if (container == null || !container.matches("^[a-z0-9][a-z0-9_.-]{0,40}$") || !ValidadorDeNomes.containerValido(container)) {
            throw new IllegalArgumentException("Nome de container inválido.");
        }
        if (porta < 1 || porta > 65535) {
            throw new IllegalArgumentException("Porta fora de 1 a 65535.");
        }
        return "http://" + container + ":" + porta;
    }

    /** O comentário do registro de DNS que marca o que é do app: {@code lab-desktop: <projeto>}. */
    public static String comentarioDoDns(String projeto) {
        if (!ValidadorDeNomes.projetoValido(projeto)) {
            throw new IllegalArgumentException("Nome de projeto inválido.");
        }
        return MARCA + " " + projeto;
    }

    /** O projeto que um comentário marcado pelo app diz, ou vazio se o comentário não é do app. */
    public static Optional<String> projetoDoComentario(String comentario) {
        if (comentario == null || !comentario.startsWith(MARCA)) {
            return Optional.empty();
        }
        String p = comentario.substring(MARCA.length()).strip();
        return ValidadorDeNomes.projetoValido(p) ? Optional.of(p) : Optional.empty();
    }
}
