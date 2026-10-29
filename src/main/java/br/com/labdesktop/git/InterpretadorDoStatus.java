package br.com.labdesktop.git;

import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a saída de {@code git status --porcelain=v2 --branch -z}. Cabeçalhos {@code # branch.oid}, {@code # branch.head}, {@code # branch.upstream}, {@code # branch.ab +n -m}; depois um item por
 * alteração: {@code 1} (comum), {@code 2} (renomeado ou copiado, com o caminho de antes no item seguinte), {@code u} (em conflito) e {@code ?} (não rastreado). Os ignorados ({@code !}) não entram.
 * Com {@code -z} o nome do arquivo vem sem aspas.
 */
public final class InterpretadorDoStatus {

    private static final Pattern AB = Pattern.compile("^\\+(\\d+) -(\\d+)$");

    private InterpretadorDoStatus() { }

    public static StatusDoGit interpretar(String saida) {
        String branch = null;
        boolean solta = false;
        String oid = null;
        String upstream = null;
        int aFrente = -1;
        int atras = -1;
        List<Pendencia> itens = new ArrayList<>();
        if (saida != null && !saida.isEmpty()) {
            String[] t = saida.split("\0", -1);
            for (int i = 0; i < t.length; i++) {
                String l = t[i];
                if (l.isEmpty()) {
                    continue;
                }
                if (l.startsWith("# branch.oid ")) {
                    String v = l.substring("# branch.oid ".length()).strip();
                    oid = v.equals("(initial)") ? null : v;
                } else if (l.startsWith("# branch.head ")) {
                    String v = l.substring("# branch.head ".length()).strip();
                    if (v.equals("(detached)")) {
                        solta = true;
                    } else {
                        branch = TextoSeguro.limparLinha(v);
                    }
                } else if (l.startsWith("# branch.upstream ")) {
                    upstream = TextoSeguro.limparLinha(l.substring("# branch.upstream ".length()));
                } else if (l.startsWith("# branch.ab ")) {
                    Matcher m = AB.matcher(l.substring("# branch.ab ".length()).strip());
                    if (m.matches()) {
                        aFrente = Integer.parseInt(m.group(1));
                        atras = Integer.parseInt(m.group(2));
                    }
                } else if (l.startsWith("# ")) {
                    continue;
                } else if (l.startsWith("1 ") && l.length() > 2) {
                    String[] c = l.split(" ", 9);
                    if (c.length == 9) {
                        itens.add(pendencia(c[1], TextoSeguro.limpar(c[8]), null));
                    }
                } else if (l.startsWith("2 ")) {
                    String[] c = l.split(" ", 10);
                    if (c.length == 10 && i + 1 < t.length) {
                        String antigo = TextoSeguro.limpar(t[i + 1]);
                        i++;
                        itens.add(pendencia(c[1], TextoSeguro.limpar(c[9]), antigo, c[8].startsWith("C")));
                    }
                } else if (l.startsWith("u ")) {
                    String[] c = l.split(" ", 11);
                    if (c.length == 11) {
                        itens.add(new Pendencia(TextoSeguro.limpar(c[10]), null, Pendencia.Tipo.CONFLITO, true, true));
                    }
                } else if (l.startsWith("? ")) {
                    itens.add(new Pendencia(TextoSeguro.limpar(l.substring(2)), null, Pendencia.Tipo.NAO_RASTREADO, false, true));
                }
            }
        }
        return new StatusDoGit(branch, solta, oid, upstream, aFrente, atras, itens);
    }

    private static Pendencia pendencia(String xy, String caminho, String antigo) {
        return pendencia(xy, caminho, antigo, false);
    }

    private static Pendencia pendencia(String xy, String caminho, String antigo, boolean copia) {
        char x = xy.length() > 0 ? xy.charAt(0) : '.';
        char y = xy.length() > 1 ? xy.charAt(1) : '.';
        char decisivo = x != '.' ? x : y;
        Pendencia.Tipo tipo = switch (decisivo) {
            case 'A' -> Pendencia.Tipo.ADICIONADO;
            case 'D' -> Pendencia.Tipo.APAGADO;
            case 'R' -> Pendencia.Tipo.RENOMEADO;
            case 'C' -> Pendencia.Tipo.COPIADO;
            case 'T' -> Pendencia.Tipo.TIPO_MUDOU;
            default -> Pendencia.Tipo.MODIFICADO;
        };
        if (antigo != null && tipo == Pendencia.Tipo.MODIFICADO) {
            tipo = copia ? Pendencia.Tipo.COPIADO : Pendencia.Tipo.RENOMEADO;
        }
        return new Pendencia(caminho, antigo, tipo, x != '.', y != '.');
    }
}
