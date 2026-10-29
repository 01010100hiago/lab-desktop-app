package br.com.labdesktop.git;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decide o que o app acrescenta ao {@code .gitignore} do projeto, SEM escrever nada: devolve as linhas novas (para a janela mostrar antes) e o texto final do arquivo. Nunca tira nem muda uma
 * linha do usuário: só acrescenta, no fim, as que faltam (comparadas sem espaço no fim), com um comentário na frente, e mantém o estilo de quebra de linha do arquivo (CRLF continua CRLF).
 *
 * <p>Uma regra de ordem: {@code !.env.example} (a exceção) tem de vir DEPOIS de {@code .env.*}; se o app acrescenta {@code .env.*}, repete a exceção logo depois, mesmo que o usuário já a tivesse mais acima.
 */
public final class PlanejadorDoGitignore {

    /** O comentário que marca o que o app acrescentou. */
    public static final String COMENTARIO = "# Acrescentado pelo lab-desktop: segredos e arquivos que não devem ir para o git";

    /**
     * @param linhasNovas as linhas que serão acrescentadas (sem o comentário e sem a linha em branco)
     * @param textoFinal  o arquivo inteiro como ficará
     * @param criaArquivo o projeto ainda não tem {@code .gitignore}
     */
    public record Plano(List<String> linhasNovas, String textoFinal, boolean criaArquivo) {

        public Plano {
            linhasNovas = List.copyOf(linhasNovas);
        }

        public boolean mudaAlgo() {
            return !linhasNovas.isEmpty();
        }
    }

    private PlanejadorDoGitignore() { }

    /**
     * @param existente o {@code .gitignore} de hoje, ou {@code null} se não existe
     * @param extras    as linhas pedidas além das de segredo e lixo (o repositório aninhado, o arquivo grande)
     */
    public static Plano planejar(String existente, List<String> extras) {
        String base = existente == null ? "" : existente;
        String eol = base.contains("\r\n") ? "\r\n" : "\n";
        Set<String> jaTem = new LinkedHashSet<>();
        for (String l : base.split("\r?\n", -1)) {
            jaTem.add(semEspacoNoFim(l));
        }
        List<String> desejadas = new ArrayList<>(SegredosDoProjeto.todasAsLinhas());
        desejadas.addAll(extras);
        List<String> novas = new ArrayList<>();
        for (String l : desejadas) {
            if (!jaTem.contains(l) && !novas.contains(l)) {
                novas.add(l);
            }
        }
        if (novas.contains(".env.*") && !novas.contains("!.env.example")) {
            novas.add(novas.indexOf(".env.*") + 1, "!.env.example"); // a exceção tem de vir depois da regra que ela libera
        }
        StringBuilder sb = new StringBuilder(base);
        if (!novas.isEmpty()) {
            if (!base.isEmpty() && !base.endsWith("\n")) {
                sb.append(eol);
            }
            if (!base.isEmpty()) {
                sb.append(eol);
            }
            sb.append(COMENTARIO).append(eol);
            for (String l : novas) {
                sb.append(l).append(eol);
            }
        }
        return new Plano(novas, sb.toString(), existente == null);
    }

    private static String semEspacoNoFim(String l) {
        int fim = l.length();
        while (fim > 0 && (l.charAt(fim - 1) == ' ' || l.charAt(fim - 1) == '\t' || l.charAt(fim - 1) == '\r')) {
            fim--;
        }
        return l.substring(0, fim);
    }

    /**
     * A linha do {@code .gitignore} que deixa de fora UM caminho exato (um arquivo grande, um repositório aninhado): ancorada na raiz com {@code /}, com {@code *}, {@code ?}, {@code [} e
     * a barra invertida protegidos, e o espaço do fim protegido. Pasta ganha {@code /} no fim.
     *
     * @throws IllegalArgumentException se o caminho não vale ({@link ValidadorDeGit#caminhoRelativoValido})
     */
    public static String linhaParaCaminho(String caminho, boolean pasta) {
        String c = caminho.endsWith("/") ? caminho.substring(0, caminho.length() - 1) : caminho;
        ValidadorDeGit.exigirCaminhoRelativo(c);
        StringBuilder sb = new StringBuilder("/");
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch == '\\' || ch == '*' || ch == '?' || ch == '[') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        if (sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
            sb.append("\\ ");
        }
        if (pasta) {
            sb.append('/');
        }
        return sb.toString();
    }
}
