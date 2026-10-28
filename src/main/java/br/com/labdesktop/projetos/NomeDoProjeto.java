package br.com.labdesktop.projetos;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * O nome de um projeto novo (ou importado): a MESMA regra do agente ({@code nomeValido} do agente: letras minúsculas, números e
 * hífen, começando por letra ou número, de 1 a 49 caracteres, que vira o nome da pasta e o do projeto do compose), com UMA exigência a mais: não
 * termina em hífen (o agente aceitaria, mas o nome fica feio e o compose o guarda como está). O app nunca envia um nome que o agente recusaria
 * pela regra, e diz em frase simples o que está errado enquanto o usuário digita.
 *
 * <p>O nome pode ter até 49 caracteres: é o que o agente de verdade aceita ({@code {0,48}} depois do primeiro), e vale o do agente.
 */
public final class NomeDoProjeto {

    /** Tamanho máximo do nome (o do agente). */
    public static final int MAXIMO = 49;

    /**
     * Nomes que o agente reserva (a infraestrutura do lab e a sessão raiz). O app só os recusa de antemão ao criar no computador local: ao criar
     * pelo agente, quem diz que o nome é reservado é o agente (a frase dele aparece como está).
     */
    public static final Set<String> RESERVADOS = Set.of("infra", "painel", "lab");

    private NomeDoProjeto() { }

    /** O resultado de conferir um nome: vale, ou a frase do que está errado ({@code ""} para o campo ainda vazio). */
    public record Resultado(boolean valido, String mensagem) {
        static final Resultado OK = new Resultado(true, "");
    }

    /** O nome vale para o agente e para o app? */
    public static boolean valido(String nome) {
        return nome != null && ValidadorDeNomes.projetoValido(nome) && !nome.endsWith("-");
    }

    /** É um dos nomes que o agente reserva? */
    public static boolean reservado(String nome) {
        return nome != null && RESERVADOS.contains(nome);
    }

    /**
     * Confere o texto como foi digitado (sem cortar espaço nem trocar maiúscula: o usuário vê o que será usado). Campo vazio não é erro de texto:
     * só não habilita o botão.
     */
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
        if (texto.charAt(0) == '-') {
            return new Resultado(false, "O nome não pode começar com hífen.");
        }
        if (texto.charAt(texto.length() - 1) == '-' && ValidadorDeNomes.projetoValido(texto)) {
            return new Resultado(false, "O nome não pode terminar com hífen.");
        }
        return new Resultado(false, "Só letras minúsculas, números e hífen (sem ponto, barra ou outros símbolos).");
    }

    /**
     * Um nome sugerido a partir de um texto qualquer (o nome do repositório ou da pasta): minúsculas, sem acento, o resto vira hífen, sem hífen
     * nas pontas, até {@link #MAXIMO}. Vazio se não sobrar nada aproveitável (o usuário digita o nome).
     */
    public static String sugerirDe(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String s = semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAXIMO) {
            s = s.substring(0, MAXIMO).replaceAll("-+$", "");
        }
        return valido(s) ? s : "";
    }
}
