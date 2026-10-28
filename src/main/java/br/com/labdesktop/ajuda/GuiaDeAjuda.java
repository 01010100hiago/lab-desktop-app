package br.com.labdesktop.ajuda;

import br.com.labdesktop.ajuda.TopicoDeAjuda.Bloco;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Link;
import br.com.labdesktop.ajuda.TopicoDeAjuda.OndeFica;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Paragrafo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Passo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Subtitulo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.TabelaDeAtalhos;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * O guia de Ajuda que mora no jar: o índice por categorias, os tópicos (um arquivo por tópico, texto simples e NUNCA HTML), a busca com o trecho a destacar e a conversão
 * para texto de copiar. Modelo puro.
 *
 * <p>Formato de um tópico ({@code ajuda/<id>.txt}):
 * <pre>
 *   # A pergunta que o tópico responde
 *   @rotulo: Nome curto (até 32 letras) para a árvore
 *   @busca: palavras extras para a busca
 *   @resumo: a frase do "Resumo rápido"
 *   @onde: O que o usuário procura | Servidor &gt; Resumo &gt; Registro | resumo     (linha da lista "Onde fica...?"; o último campo é a tela, opcional)
 *   1. Um passo numerado (no máximo 7, cada um com no máximo 2 linhas)
 *   Um parágrafo (linhas seguidas viram um parágrafo só)
 *   [Ir para Projetos](ir:projetos)        (sozinho na linha: botão que abre a tela; ids em {@link Destino})
 *   {tabela-de-atalhos}                    (sozinho na linha: a tabela gerada do menu)
 *   @veja: outro-topico outro-topico       (o "Veja também": até 3 ids)
 *   @acoes: sim                            (o tópico fala de botões que só aparecem com as ações de escrita ligadas: a Ajuda põe a frase fixa no topo)
 *   ## Mais detalhes                       (o que vem depois fica na seção recolhível)
 *   ... {atalho:ajustes} ...               (dentro do texto: a tecla daquele item do menu)
 * </pre>
 *
 * <p>Formato do {@code indice.txt}: {@code [Nome da categoria]} abre uma categoria e os ids dos tópicos vêm nas linhas de baixo.
 */
public final class GuiaDeAjuda {

    private static final String PASTA = "/br/com/labdesktop/ajuda/";
    private static final Pattern LINK = Pattern.compile("^\\[([^\\]]+)]\\(ir:([a-z0-9-]+)\\)$");
    private static final Pattern PASSO = Pattern.compile("^(\\d{1,2})\\.\\s+(.+)$");
    private static final Pattern ATALHO = Pattern.compile("\\{atalho:([a-z0-9-]+)}");
    private static final String MAIS_DETALHES = "Mais detalhes";

    private final List<CategoriaDeAjuda> categorias;
    private final List<TopicoDeAjuda> topicos;

    public GuiaDeAjuda(List<CategoriaDeAjuda> categorias, List<TopicoDeAjuda> topicos) {
        this.categorias = List.copyOf(categorias);
        this.topicos = List.copyOf(topicos);
    }

    /** O guia do jar. */
    public static GuiaDeAjuda doJar() {
        List<CategoriaDeAjuda> categorias = categoriasDoIndice(ler("indice.txt"));
        List<TopicoDeAjuda> lista = new ArrayList<>();
        for (CategoriaDeAjuda c : categorias) {
            for (String id : c.ids()) {
                lista.add(interpretar(id, ler(id + ".txt")));
            }
        }
        return new GuiaDeAjuda(categorias, lista);
    }

    /** As categorias de um {@code indice.txt} (as linhas com {@code #} e as vazias ficam de fora). Id antes de qualquer categoria é erro do índice. */
    public static List<CategoriaDeAjuda> categoriasDoIndice(String indice) {
        List<CategoriaDeAjuda> saida = new ArrayList<>();
        String nome = null;
        List<String> ids = new ArrayList<>();
        for (String bruta : indice.split("\\R")) {
            String linha = bruta.strip();
            if (linha.isEmpty() || linha.startsWith("#")) {
                continue;
            }
            if (linha.startsWith("[") && linha.endsWith("]")) {
                if (nome != null) {
                    saida.add(new CategoriaDeAjuda(nome, List.copyOf(ids)));
                }
                nome = linha.substring(1, linha.length() - 1).strip();
                ids = new ArrayList<>();
            } else if (nome == null) {
                throw new IllegalArgumentException("indice.txt: o tópico " + linha + " vem antes de qualquer [categoria]");
            } else {
                ids.add(linha);
            }
        }
        if (nome != null) {
            saida.add(new CategoriaDeAjuda(nome, List.copyOf(ids)));
        }
        return List.copyOf(saida);
    }

    private static String ler(String arquivo) {
        try (InputStream in = GuiaDeAjuda.class.getResourceAsStream(PASTA + arquivo)) {
            if (in == null) {
                throw new IllegalStateException("Faltou o arquivo da Ajuda " + arquivo + " no jar.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Não consegui ler o arquivo da Ajuda " + arquivo + ".", e);
        }
    }

    /** O texto de um arquivo de tópico em {@link TopicoDeAjuda}. Erro de formato (link para tela que não existe) lança {@link IllegalArgumentException} com o id no texto. */
    public static TopicoDeAjuda interpretar(String id, String texto) {
        String titulo = null;
        String resumo = "";
        String rotulo = null;
        List<String> palavras = new ArrayList<>();
        List<String> veja = new ArrayList<>();
        List<OndeFica> onde = new ArrayList<>();
        List<Link> telas = new ArrayList<>();
        List<Bloco> corpo = new ArrayList<>();
        List<Bloco> detalhes = new ArrayList<>();
        List<Bloco> destino = corpo;
        boolean soComAcoes = false;
        StringBuilder paragrafo = new StringBuilder();
        for (String bruta : texto.split("\\R", -1)) {
            String linha = bruta.strip();
            if (titulo == null && linha.startsWith("# ")) {
                titulo = linha.substring(2).strip();
                continue;
            }
            if (linha.startsWith("@")) {
                fecharParagrafo(paragrafo, destino);
                if (linha.startsWith("@rotulo:")) {
                    rotulo = linha.substring("@rotulo:".length()).strip();
                } else if (linha.startsWith("@busca:")) {
                    palavras.addAll(List.of(linha.substring("@busca:".length()).strip().split("\\s+")));
                } else if (linha.startsWith("@resumo:")) {
                    resumo = linha.substring("@resumo:".length()).strip();
                } else if (linha.startsWith("@acoes:")) {
                    soComAcoes = linha.substring("@acoes:".length()).strip().equalsIgnoreCase("sim");
                } else if (linha.startsWith("@veja:")) {
                    veja.addAll(List.of(linha.substring("@veja:".length()).strip().split("\\s+")));
                } else if (linha.startsWith("@onde:")) {
                    String[] campos = linha.substring("@onde:".length()).split("\\|");
                    if (campos.length < 2) {
                        throw new IllegalArgumentException(id + ": @onde pede \"o que | caminho\": " + linha);
                    }
                    Destino d = campos.length > 2 ? Destino.de(campos[2].strip()).orElseThrow(() -> new IllegalArgumentException(id + ": @onde aponta para uma tela que não existe: " + linha)) : null;
                    onde.add(new OndeFica(campos[0].strip(), campos[1].strip(), d));
                } else {
                    throw new IllegalArgumentException(id + ": linha de controle desconhecida: " + linha);
                }
                continue;
            }
            Matcher link = LINK.matcher(linha);
            Matcher passo = PASSO.matcher(linha);
            if (linha.isEmpty() || linha.startsWith("## ") || link.matches() || passo.matches() || linha.equals("{tabela-de-atalhos}")) {
                fecharParagrafo(paragrafo, destino);
            }
            if (linha.isEmpty()) {
                continue;
            }
            if (linha.startsWith("## ")) {
                String sub = linha.substring(3).strip();
                if (sub.equals(MAIS_DETALHES)) {
                    destino = detalhes;
                } else {
                    destino.add(new Subtitulo(sub));
                }
            } else if (link.matches()) {
                Destino d = Destino.de(link.group(2)).orElseThrow(() -> new IllegalArgumentException(id + ": o link aponta para uma tela que não existe: " + linha));
                telas.add(new Link(link.group(1), d));
            } else if (passo.matches()) {
                destino.add(new Passo(Integer.parseInt(passo.group(1)), passo.group(2).strip()));
            } else if (linha.equals("{tabela-de-atalhos}")) {
                destino.add(new TabelaDeAtalhos());
            } else {
                if (paragrafo.length() > 0) {
                    paragrafo.append(' ');
                }
                paragrafo.append(linha);
            }
        }
        fecharParagrafo(paragrafo, destino);
        return new TopicoDeAjuda(id, titulo == null ? "" : titulo, rotulo == null ? (titulo == null ? id : titulo) : rotulo, List.copyOf(palavras), resumo, List.copyOf(corpo), List.copyOf(detalhes), List.copyOf(telas), List.copyOf(veja),
            List.copyOf(onde), soComAcoes);
    }

    /** A frase fixa do topo dos tópicos que falam de botões de escrita: sem as ações ligadas (o padrão) esses botões nem aparecem, e o usuário não deve achar que errou. */
    public static final String FRASE_DAS_ACOES = "Os botões que mudam coisas no servidor só aparecem quando as ações de escrita estão ligadas.";

    private static void fecharParagrafo(StringBuilder paragrafo, List<Bloco> blocos) {
        if (paragrafo.length() > 0) {
            blocos.add(new Paragrafo(paragrafo.toString()));
            paragrafo.setLength(0);
        }
    }

    public List<CategoriaDeAjuda> categorias() {
        return categorias;
    }

    public List<TopicoDeAjuda> topicos() {
        return topicos;
    }

    /** O tópico de um id, ou {@code null}. */
    public TopicoDeAjuda topico(String id) {
        return topicos.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * A busca: cada palavra da consulta precisa aparecer (sem diferença de maiúscula nem de acento) no título, nas palavras extras, no resumo ou no texto do tópico. Consulta
     * vazia devolve todos, na ordem do índice.
     */
    public List<TopicoDeAjuda> filtrar(String consulta) {
        List<String> palavras = separar(consulta);
        if (palavras.isEmpty()) {
            return topicos;
        }
        return topicos.stream().filter(t -> {
            String palheiro = simplificar(t.titulo() + " " + String.join(" ", t.palavras()) + " " + t.resumo() + " " + textoCorrido(t));
            return palavras.stream().allMatch(palheiro::contains);
        }).toList();
    }

    private static List<String> separar(String consulta) {
        if (consulta == null || consulta.isBlank()) {
            return List.of();
        }
        return List.of(simplificar(consulta.strip()).split("\\s+"));
    }

    /** Minúsculas e sem acento, SEM mudar o tamanho do texto (cada letra vira uma letra): "Configuração" e "configuracao" se encontram e as posições batem. */
    static String simplificar(String texto) {
        StringBuilder b = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            String letra = Normalizer.normalize(texto.substring(i, i + 1), Normalizer.Form.NFD);
            b.append(letra.isEmpty() ? ' ' : Character.toLowerCase(letra.charAt(0)));
        }
        return b.toString();
    }

    /**
     * Onde, num texto, aparecem as palavras da consulta (para destacar o trecho): pares {@code [início, fim)}, em ordem e sem sobreposição. Sem diferença de maiúscula nem
     * de acento. Consulta vazia: nenhum trecho.
     */
    public static List<int[]> trechos(String texto, String consulta) {
        List<String> palavras = separar(consulta);
        List<int[]> achados = new ArrayList<>();
        String palheiro = simplificar(texto);
        for (String p : palavras) {
            int de = 0;
            while (!p.isEmpty() && (de = palheiro.indexOf(p, de)) >= 0) {
                achados.add(new int[] {de, de + p.length()});
                de += p.length();
            }
        }
        achados.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> unidos = new ArrayList<>();
        for (int[] a : achados) {
            if (!unidos.isEmpty() && a[0] <= unidos.get(unidos.size() - 1)[1]) {
                int[] ultimo = unidos.get(unidos.size() - 1);
                ultimo[1] = Math.max(ultimo[1], a[1]);
            } else {
                unidos.add(a);
            }
        }
        return unidos;
    }

    private static String textoCorrido(TopicoDeAjuda t) {
        StringBuilder b = new StringBuilder();
        for (List<Bloco> lista : List.of(t.corpo(), t.detalhes())) {
            for (Bloco bloco : lista) {
                switch (bloco) {
                    case Paragrafo p -> b.append(p.texto());
                    case Subtitulo s -> b.append(s.texto());
                    case Passo p -> b.append(p.texto());
                    case TabelaDeAtalhos x -> b.append("atalhos teclado");
                }
                b.append(' ');
            }
        }
        return b.toString();
    }

    /** Troca cada {@code {atalho:chave}} pela tecla do menu ({@code "Cmd+,"}); chave sem atalho no menu vira "(sem atalho)". */
    public static String resolver(String texto, List<AtalhoDoMenu> atalhos) {
        Map<String, String> porChave = atalhos.stream().collect(Collectors.toMap(AtalhoDoMenu::chave, AtalhoDoMenu::tecla, (a, b) -> a));
        Matcher m = ATALHO.matcher(texto);
        StringBuilder saida = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(saida, Matcher.quoteReplacement(porChave.getOrDefault(m.group(1), "(sem atalho)")));
        }
        m.appendTail(saida);
        return saida.toString();
    }

    /** As chaves de atalho que um texto cita. */
    public static List<String> chavesCitadas(String texto) {
        List<String> chaves = new ArrayList<>();
        Matcher m = ATALHO.matcher(texto);
        while (m.find()) {
            chaves.add(m.group(1));
        }
        return chaves;
    }

    /** Todas as chaves de atalho que o tópico cita (corpo, detalhes e as linhas do "Onde fica...?"). */
    public static List<String> chavesCitadas(TopicoDeAjuda t) {
        List<String> chaves = new ArrayList<>();
        for (List<Bloco> lista : List.of(t.corpo(), t.detalhes())) {
            for (Bloco b : lista) {
                switch (b) {
                    case Paragrafo p -> chaves.addAll(chavesCitadas(p.texto()));
                    case Passo p -> chaves.addAll(chavesCitadas(p.texto()));
                    default -> { }
                }
            }
        }
        t.onde().forEach(o -> chaves.addAll(chavesCitadas(o.caminho())));
        chaves.addAll(chavesCitadas(t.resumo()));
        return chaves;
    }

    /** O tópico como texto simples para o botão Copiar: título, resumo, passos, detalhes e a tabela de atalhos. */
    public static String textoParaCopiar(TopicoDeAjuda t, List<AtalhoDoMenu> atalhos) {
        StringBuilder b = new StringBuilder(t.titulo()).append("\n\n");
        if (!t.resumo().isBlank()) {
            b.append("Resumo rápido: ").append(resolver(t.resumo(), atalhos)).append("\n\n");
        }
        copiar(b, t.corpo(), atalhos);
        if (!t.detalhes().isEmpty()) {
            b.append("\nMAIS DETALHES\n");
            copiar(b, t.detalhes(), atalhos);
        }
        return b.toString().strip();
    }

    private static void copiar(StringBuilder b, List<Bloco> blocos, List<AtalhoDoMenu> atalhos) {
        for (Bloco bloco : blocos) {
            switch (bloco) {
                case Paragrafo p -> b.append(resolver(p.texto(), atalhos)).append("\n\n");
                case Subtitulo s -> b.append(s.texto().toUpperCase(Locale.ROOT)).append('\n');
                case Passo p -> b.append(p.numero()).append(". ").append(resolver(p.texto(), atalhos)).append('\n');
                case TabelaDeAtalhos x -> {
                    for (AtalhoDoMenu a : atalhos) {
                        b.append(a.tecla()).append("  ").append(a.descricao()).append('\n');
                    }
                }
            }
        }
    }

    /** As linhas da lista rápida "Onde fica...?": as de todos os tópicos, na ordem do índice. */
    public List<OndeFica> ondeFica() {
        List<OndeFica> todas = new ArrayList<>();
        topicos.forEach(t -> todas.addAll(t.onde()));
        return todas;
    }
}
