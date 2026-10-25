package br.com.labdesktop.arquivos;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * O que a tabela mostra de uma pasta, a partir do que o servidor mandou: esconde o que começa com ponto (se o usuário não pediu),
 * aplica o filtro de texto e ordena. Função pura: a tela chama de novo a cada tecla do filtro, a cada clique de coluna e a cada
 * atualização, e compara o resultado com o anterior para preservar seleção e foco.
 *
 * <p>Pastas vêm SEMPRE antes dos arquivos, qualquer que seja a coluna e a direção (é o que o Finder e o Explorer fazem). O
 * nome é comparado "do jeito humano": sem diferença de maiúscula e acento, e {@code arq2} antes de {@code arq10}.
 */
public final class VisaoDaPasta {

    private VisaoDaPasta() { }

    /** As colunas da tabela. */
    public enum Coluna { NOME, TIPO, TAMANHO, MODIFICADO, PERMISSOES, DONO }

    /** A ordem escolhida pelo usuário. */
    public record Ordem(Coluna coluna, boolean crescente) {

        public static final Ordem PADRAO = new Ordem(Coluna.NOME, true);

        /** O que acontece ao clicar no cabeçalho: outra coluna começa certa para ela; a mesma coluna inverte. */
        public Ordem clicar(Coluna clicada) {
            if (clicada == coluna) {
                return new Ordem(coluna, !crescente);
            }
            // tamanho e data: do maior e do mais novo primeiro, que é o que se procura
            return new Ordem(clicada, clicada != Coluna.TAMANHO && clicada != Coluna.MODIFICADO);
        }
    }

    /** Aplica ocultos, filtro e ordem. A lista devolvida é nova e imutável. */
    public static List<EntradaRemota> montar(List<EntradaRemota> todas, String filtro, boolean mostrarOcultos, Ordem ordem) {
        String f = normalizar(filtro == null ? "" : filtro.strip());
        List<EntradaRemota> v = new ArrayList<>();
        for (EntradaRemota e : todas) {
            if (!mostrarOcultos && e.oculto()) {
                continue;
            }
            if (!f.isEmpty() && !normalizar(e.nome()).contains(f)) {
                continue;
            }
            v.add(e);
        }
        Ordem o = ordem == null ? Ordem.PADRAO : ordem;
        Comparator<EntradaRemota> porColuna = comparadorDe(o.coluna());
        Comparator<EntradaRemota> porNome = (a, b) -> compararNomes(a.nome(), b.nome());
        Comparator<EntradaRemota> doDono = o.crescente() ? porColuna : porColuna.reversed();
        Comparator<EntradaRemota> total = Comparator.comparing((EntradaRemota e) -> e.ehPasta() ? 0 : 1)
            .thenComparing(doDono).thenComparing(porNome);
        v.sort(total);
        return List.copyOf(v);
    }

    private static Comparator<EntradaRemota> comparadorDe(Coluna c) {
        return switch (c) {
            case NOME -> (a, b) -> compararNomes(a.nome(), b.nome());
            case TIPO -> Comparator.comparing(e -> TiposDeArquivo.rotulo(e).toLowerCase(Locale.ROOT));
            case TAMANHO -> Comparator.comparingLong(e -> e.ehPasta() ? -1 : e.tamanho());
            case MODIFICADO -> Comparator.comparing(e -> e.modificadoEm() == null ? java.time.Instant.EPOCH : e.modificadoEm());
            case PERMISSOES -> Comparator.comparingInt(EntradaRemota::permissoes);
            case DONO -> Comparator.comparing(e -> e.dono().toLowerCase(Locale.ROOT));
        };
    }

    /** {@code true} se o nome casa com o filtro (qualquer pedaço, sem diferença de maiúscula e acento). Filtro vazio casa com tudo. */
    public static boolean casaComFiltro(String nome, String filtro) {
        String f = normalizar(filtro == null ? "" : filtro.strip());
        return f.isEmpty() || normalizar(nome).contains(f);
    }

    /** Sem maiúscula, sem acento. */
    static String normalizar(String texto) {
        String sem = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return sem.toLowerCase(Locale.ROOT);
    }

    /** Nome "do jeito humano": números dentro do texto comparam como números. */
    public static int compararNomes(String a, String b) {
        String x = normalizar(a);
        String y = normalizar(b);
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i);
            char cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int fi = i;
                while (fi < x.length() && Character.isDigit(x.charAt(fi))) {
                    fi++;
                }
                int fj = j;
                while (fj < y.length() && Character.isDigit(y.charAt(fj))) {
                    fj++;
                }
                String nx = x.substring(i, fi).replaceFirst("^0+(?=.)", "");
                String ny = y.substring(j, fj).replaceFirst("^0+(?=.)", "");
                if (nx.length() != ny.length()) {
                    return Integer.compare(nx.length(), ny.length());
                }
                int c = nx.compareTo(ny);
                if (c != 0) {
                    return c;
                }
                i = fi;
                j = fj;
            } else {
                if (cx != cy) {
                    return Character.compare(cx, cy);
                }
                i++;
                j++;
            }
        }
        int c = Integer.compare(x.length() - i, y.length() - j);
        return c != 0 ? c : a.compareTo(b); // desempate estável: o texto original
    }

    /** Os rótulos de tipo que a coluna Tipo mostra. */
    public static final class TiposDeArquivo {

        private static final Map<String, String> POR_EXTENSAO = Map.ofEntries(
            Map.entry("txt", "Texto"), Map.entry("md", "Texto"), Map.entry("log", "Registro"),
            Map.entry("json", "Dados"), Map.entry("yml", "Configuração"), Map.entry("yaml", "Configuração"),
            Map.entry("env", "Configuração"), Map.entry("conf", "Configuração"), Map.entry("ini", "Configuração"),
            Map.entry("toml", "Configuração"), Map.entry("xml", "Dados"), Map.entry("csv", "Planilha"),
            Map.entry("js", "Código"), Map.entry("ts", "Código"), Map.entry("java", "Código"), Map.entry("py", "Código"),
            Map.entry("html", "Código"), Map.entry("css", "Código"), Map.entry("sql", "Código"), Map.entry("go", "Código"),
            Map.entry("sh", "Script"), Map.entry("png", "Imagem"), Map.entry("jpg", "Imagem"), Map.entry("jpeg", "Imagem"),
            Map.entry("gif", "Imagem"), Map.entry("svg", "Imagem"), Map.entry("webp", "Imagem"), Map.entry("pdf", "PDF"),
            Map.entry("zip", "Compactado"), Map.entry("gz", "Compactado"), Map.entry("tar", "Compactado"),
            Map.entry("tgz", "Compactado"), Map.entry("7z", "Compactado"));

        private TiposDeArquivo() { }

        public static String rotulo(EntradaRemota e) {
            if (e.tipo() == EntradaRemota.Tipo.LINK_QUEBRADO) {
                return "Link quebrado";
            }
            if (e.link()) {
                return e.ehPasta() ? "Link para pasta" : "Link";
            }
            if (e.ehPasta()) {
                return "Pasta";
            }
            if (e.tipo() == EntradaRemota.Tipo.OUTRO) {
                return "Especial";
            }
            return POR_EXTENSAO.getOrDefault(e.extensao(), "Arquivo");
        }
    }
}
