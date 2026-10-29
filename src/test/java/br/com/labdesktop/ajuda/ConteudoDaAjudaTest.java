package br.com.labdesktop.ajuda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ajuda.TopicoDeAjuda.Bloco;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Paragrafo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Passo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * O que está ESCRITO na Ajuda (os arquivos do jar): cada tópico completo e no molde de uma tela, no máximo 7 categorias de nome curto com no máximo 6 tópicos, links só
 * para telas que existem, "Veja também" para tópicos que existem, nenhum emoji, nenhum TODO, nenhum HTML e nenhuma tecla digitada à mão (as teclas saem do menu).
 */
class ConteudoDaAjudaTest {

    private static final Path PASTA = Path.of("src", "main", "resources", "br", "com", "labdesktop", "ajuda");
    /** Um passo de no máximo 2 linhas: com a linha de 640 px (cerca de 90 letras) são uns 180 caracteres. */
    private static final int MAXIMO_DE_LETRAS_POR_PASSO = 180;
    private static final int MAXIMO_DE_LETRAS_DO_RESUMO = 220;

    private static final GuiaDeAjuda GUIA = GuiaDeAjuda.doJar();

    private static boolean temEmoji(String texto) {
        return texto.codePoints().anyMatch(cp -> cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || cp == 0xFE0F || cp == 0x200D || (cp >= 0x2B00 && cp <= 0x2BFF));
    }

    /**
     * A Ajuda dizia "No passo 3, clique em Testar conexão" e o botão ficava no passo 2. Todo "passo N" que um tópico cita tem de existir no assistente
     * (o título "Passo N de 3" no código) e todo botão citado entre aspas depois de "clique em" tem de ser um texto de botão que existe no código.
     */
    @Test
    void osPassosEOsBotoesCitadosNosTopicosExistemNoCodigoDoApp() throws IOException {
        StringBuilder fontes = new StringBuilder();
        try (Stream<Path> s = Files.walk(Path.of("src", "main", "java"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                fontes.append(Files.readString(p)).append('\n');
            }
        }
        String codigo = fontes.toString();
        java.util.regex.Pattern passo = java.util.regex.Pattern.compile("(?i)\\bno passo (\\d)\\b");
        java.util.regex.Pattern botao = java.util.regex.Pattern.compile("(?i)clique em \"([^\"]+)\"");
        List<String> ruins = new ArrayList<>();
        for (TopicoDeAjuda t : GUIA.topicos()) {
            List<String> textos = new ArrayList<>();
            for (Bloco b : t.corpo()) {
                if (b instanceof Passo p) {
                    textos.add(p.texto());
                } else if (b instanceof Paragrafo p) {
                    textos.add(p.texto());
                }
            }
            for (String texto : textos) {
                java.util.regex.Matcher m = passo.matcher(texto);
                while (m.find()) {
                    if (!codigo.contains("Passo " + m.group(1) + " de 3")) {
                        ruins.add(t.id() + ": cita o passo " + m.group(1) + ", que o assistente não tem: " + texto);
                    }
                }
                m = botao.matcher(texto);
                while (m.find()) {
                    String rotulo = m.group(1).replace("…", "").replace("...", "").strip();
                    if (!codigo.contains("\"" + rotulo)) {
                        ruins.add(t.id() + ": cita o botão \"" + m.group(1) + "\", que não existe no código");
                    }
                }
            }
        }
        assertTrue(ruins.isEmpty(), String.join("\n", ruins));
    }

    @Test
    void osTopicosQueFalamDeBotoesDeEscritaTemAFraseFixaDoTopo() {
        for (String id : List.of("projetos", "publicar", "arquivos", "logs-e-consumo")) {
            assertTrue(GUIA.topico(id).soComAcoes(), id + " tem @acoes: sim");
        }
        assertTrue(GuiaDeAjuda.FRASE_DAS_ACOES.contains("só aparecem") && GuiaDeAjuda.FRASE_DAS_ACOES.contains("ações de escrita"));
        assertFalse(GuiaDeAjuda.chavesCitadas(GUIA.topico("projetos")).contains("novo-projeto"), "o Novo projeto não cita atalho que some na versão padrão");
    }

    @Test
    void todoArquivoDaPastaEstaNoIndiceETodoNomeDoIndiceTemArquivo() throws IOException {
        Set<String> nosArquivos = new HashSet<>();
        try (Stream<Path> s = Files.list(PASTA)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt") && !n.equals("indice.txt")).forEach(n -> nosArquivos.add(n.substring(0, n.length() - 4)));
        }
        List<String> noIndice = GUIA.categorias().stream().flatMap(c -> c.ids().stream()).toList();
        assertEquals(new HashSet<>(noIndice).size(), noIndice.size(), "um tópico não pode estar em duas categorias: " + noIndice);
        assertEquals(nosArquivos, new HashSet<>(noIndice), "os arquivos da pasta e o índice têm de ser os mesmos");
        assertEquals(noIndice.size(), GUIA.topicos().size());
    }

    @Test
    void poucasCategoriasDeNomeCurtoComPoucosTopicosCada() {
        assertTrue(GUIA.categorias().size() <= 7, "no máximo 7 categorias: " + GUIA.categorias().size());
        assertEquals("Comece aqui", GUIA.categorias().get(0).nome(), "a primeira é a de quem chega agora");
        for (CategoriaDeAjuda c : GUIA.categorias()) {
            assertTrue(c.nome().length() <= 24, "nome curto: " + c.nome());
            assertTrue(c.ids().size() >= 1 && c.ids().size() <= 6, c.nome() + " tem " + c.ids().size() + " tópicos (de 1 a 6)");
        }
    }

    @Test
    void cadaTopicoTemTituloDePerguntaRotuloCurtoResumoEPassosNoMolde() {
        for (TopicoDeAjuda t : GUIA.topicos()) {
            String quem = t.id() + ": ";
            assertFalse(t.titulo().isBlank(), quem + "sem título");
            assertTrue(t.titulo().endsWith("?"), quem + "o título é uma pergunta (\"Como faço...?\", \"Onde fica...?\"): " + t.titulo());
            assertTrue(t.rotulo().length() > 0 && t.rotulo().length() <= 32, quem + "o rótulo curto tem até 32 letras: " + t.rotulo());
            assertFalse(t.resumo().isBlank(), quem + "sem resumo rápido");
            assertTrue(t.resumo().length() <= MAXIMO_DE_LETRAS_DO_RESUMO, quem + "o resumo é UMA frase curta: " + t.resumo());
            assertTrue(t.resumo().indexOf(". ") < 0, quem + "o resumo tem uma frase só: " + t.resumo());
            boolean temCorpo = !t.corpo().isEmpty();
            assertTrue(temCorpo, quem + "sem corpo");
            List<Passo> passos = t.passos();
            assertTrue(passos.size() <= 7, quem + "no máximo 7 passos: " + passos.size());
            for (int i = 0; i < passos.size(); i++) {
                assertEquals(i + 1, passos.get(i).numero(), quem + "os passos são numerados em sequência");
                assertTrue(passos.get(i).texto().length() <= MAXIMO_DE_LETRAS_POR_PASSO, quem + "passo de mais de 2 linhas (" + passos.get(i).texto().length() + " letras): " + passos.get(i).texto());
            }
            assertTrue(t.veja().size() <= 3, quem + "o Veja também tem até 3 tópicos: " + t.veja());
            for (String id : t.veja()) {
                assertTrue(GUIA.topico(id) != null && !id.equals(t.id()), quem + "o Veja também aponta para um tópico que não existe (ou para ele mesmo): " + id);
            }
            assertTrue(t.telas().size() <= 3, quem + "no máximo 3 botões de tela");
        }
    }

    @Test
    void nenhumEmojiNenhumTodoNenhumHtmlNenhumaTeclaDigitadaAMao() throws IOException {
        List<String> ruins = new ArrayList<>();
        try (Stream<Path> s = Files.list(PASTA)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".txt")).toList()) {
                String texto = Files.readString(p);
                String nome = p.getFileName().toString();
                if (temEmoji(texto)) {
                    ruins.add(nome + ": tem emoji");
                }
                if (texto.matches("(?s).*\\bTODO\\b.*") || texto.contains("FIXME") || texto.contains("XXX")) {
                    ruins.add(nome + ": tem TODO");
                }
                if (texto.matches("(?s).*<\\s*/?\\s*[a-zA-Z][^>]*>.*")) {
                    ruins.add(nome + ": parece HTML");
                }
                String semMarcadores = texto.replaceAll("\\{atalho:[a-z0-9-]+}", "");
                if (semMarcadores.matches("(?s).*\\b(Cmd|Ctrl|Control|Command|Alt|Option)\\s*\\+.*") || semMarcadores.matches("(?s).*\\bF1\\b.*")) {
                    ruins.add(nome + ": tecla digitada à mão (use {atalho:chave}, que sai do menu)");
                }
                if (texto.contains("\t")) {
                    ruins.add(nome + ": tem tabulação");
                }
            }
        }
        assertTrue(ruins.isEmpty(), String.join("\n", ruins));
    }

    @Test
    void osMarcadoresDeAtalhoCitamChavesComFormatoValidoEOsTextosNaoTemMarcadorSolto() {
        for (TopicoDeAjuda t : GUIA.topicos()) {
            for (String chave : GuiaDeAjuda.chavesCitadas(t)) {
                assertTrue(chave.matches("[a-z0-9-]+"), t.id() + ": chave " + chave);
            }
            for (List<Bloco> lista : List.of(t.corpo(), t.detalhes())) {
                for (Bloco b : lista) {
                    String texto = b instanceof Paragrafo p ? p.texto() : b instanceof Passo p ? p.texto() : "";
                    assertFalse(texto.replaceAll("\\{atalho:[a-z0-9-]+}", "").contains("{"), t.id() + ": marcador desconhecido em: " + texto);
                }
            }
        }
    }

    @Test
    void aListaRapidaOndeFicaTemLinhasComCaminhoExato() {
        List<TopicoDeAjuda.OndeFica> linhas = GUIA.ondeFica();
        assertTrue(linhas.size() >= 20, "linhas na lista rápida: " + linhas.size());
        for (TopicoDeAjuda.OndeFica o : linhas) {
            assertFalse(o.oQue().isBlank() || o.caminho().isBlank(), "linha vazia");
            assertTrue(o.caminho().contains(">") || o.caminho().startsWith("Menu ") || o.caminho().startsWith("Faixa ") || o.caminho().startsWith("Botão ") || o.caminho().startsWith("Aba ")
                || o.caminho().startsWith("Barra "), "o caminho diz onde clicar: " + o.caminho());
        }
        assertTrue(linhas.stream().anyMatch(o -> o.caminho().equals("Servidor > Resumo > Registro do servidor")), "o exemplo do usuário: Servidor > Resumo > Registro");
    }

    /** O tópico da instalação do agente conta as três escolhas e diz o que "Só SSH" significa. */
    @Test
    void oTopicoDoAgenteExplicaAsTresEscolhasEOSoSsh() {
        TopicoDeAjuda t = GUIA.topico("agente");
        StringBuilder texto = new StringBuilder();
        for (List<Bloco> lista : List.of(t.corpo(), t.detalhes())) {
            for (Bloco b : lista) {
                texto.append(b instanceof Paragrafo p ? p.texto() : b instanceof Passo p ? p.texto() : "").append('\n');
            }
        }
        String tudo = texto.toString();
        assertTrue(tudo.contains("Completo") && tudo.contains("Escolher") && tudo.contains("Só SSH"), tudo);
        assertTrue(tudo.contains("Só SSH não instala nada"), "a frase do Só SSH: " + tudo);
        assertTrue(tudo.contains("nunca instala metade"), tudo);
        assertFalse(tudo.toLowerCase().contains("perfil"), "a Ajuda não usa a palavra perfil");
        assertTrue(t.passos().size() <= 7);
    }
}
