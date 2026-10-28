package br.com.labdesktop.ajuda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ajuda.TopicoDeAjuda.Link;
import br.com.labdesktop.ajuda.TopicoDeAjuda.OndeFica;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Paragrafo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Passo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Subtitulo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.TabelaDeAtalhos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O modelo puro da Ajuda: o formato dos arquivos, o índice, a busca com o trecho a destacar, os atalhos do menu e o histórico de leitura. */
class GuiaDeAjudaTest {

    private static final String EXEMPLO = """
        # Como faço isto?
        @rotulo: Fazer isto
        @busca: coisa extra
        @resumo: Uma frase de resumo.
        @onde: A coisa | Servidor > Resumo > Registro | resumo
        @onde: Outra coisa | Menu Arquivo > Ajustes… ({atalho:ajustes})
        1. Primeiro passo, com {atalho:ajustes}.
        2. Segundo passo.
        Um parágrafo que continua
        na linha de baixo.
        ## Um subtítulo
        {tabela-de-atalhos}
        [Ir para Projetos](ir:projetos)
        @veja: outro um-terceiro
        ## Mais detalhes
        Texto longo dos detalhes.
        3. Passo que fica nos detalhes.
        """;

    @Test
    void interpretaTodasAsPecasDoFormato() {
        TopicoDeAjuda t = GuiaDeAjuda.interpretar("exemplo", EXEMPLO);
        assertEquals("Como faço isto?", t.titulo());
        assertEquals("Fazer isto", t.rotulo());
        assertEquals(List.of("coisa", "extra"), t.palavras());
        assertEquals("Uma frase de resumo.", t.resumo());
        assertEquals(List.of(new OndeFica("A coisa", "Servidor > Resumo > Registro", Destino.RESUMO),
            new OndeFica("Outra coisa", "Menu Arquivo > Ajustes… ({atalho:ajustes})", null)), t.onde());
        assertEquals(List.of(new Passo(1, "Primeiro passo, com {atalho:ajustes}."), new Passo(2, "Segundo passo.")), t.passos());
        assertEquals(new Paragrafo("Um parágrafo que continua na linha de baixo."), t.corpo().get(2), "linhas seguidas viram um parágrafo");
        assertEquals(new Subtitulo("Um subtítulo"), t.corpo().get(3));
        assertEquals(new TabelaDeAtalhos(), t.corpo().get(4));
        assertEquals(List.of(new Link("Ir para Projetos", Destino.PROJETOS)), t.telas());
        assertEquals(List.of("outro", "um-terceiro"), t.veja());
        assertEquals(List.of(new Paragrafo("Texto longo dos detalhes."), new Passo(3, "Passo que fica nos detalhes.")), t.detalhes(), "o que vem depois de Mais detalhes");
    }

    @Test
    void semRotuloOTituloServeELinkParaTelaInexistenteEErro() {
        assertEquals("Título", GuiaDeAjuda.interpretar("x", "# Título\n@resumo: r\n").rotulo());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> GuiaDeAjuda.interpretar("meu", "# T\n[Ir](ir:tela-que-nao-existe)\n"));
        assertTrue(e.getMessage().contains("meu") && e.getMessage().contains("tela que não existe"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> GuiaDeAjuda.interpretar("meu", "# T\n@onde: so um campo\n"));
        assertThrows(IllegalArgumentException.class, () -> GuiaDeAjuda.interpretar("meu", "# T\n@onde: a | b | tela-ruim\n"));
        assertThrows(IllegalArgumentException.class, () -> GuiaDeAjuda.interpretar("meu", "# T\n@invento: x\n"));
    }

    @Test
    void oIndiceTemCategoriasETopicosNaOrdem() {
        List<CategoriaDeAjuda> c = GuiaDeAjuda.categoriasDoIndice("# comentário\n\n[Comece aqui]\na\nb\n\n[Outra]\nc\n");
        assertEquals(List.of(new CategoriaDeAjuda("Comece aqui", List.of("a", "b")), new CategoriaDeAjuda("Outra", List.of("c"))), c);
        assertThrows(IllegalArgumentException.class, () -> GuiaDeAjuda.categoriasDoIndice("solto\n[Cat]\n"), "tópico antes de qualquer categoria");
    }

    private static GuiaDeAjuda guia() {
        TopicoDeAjuda a = GuiaDeAjuda.interpretar("a", "# Como conecto o servidor?\n@resumo: Usa uma chave.\n1. Clique em Adicionar.\n## Mais detalhes\nA impressão digital é conferida.\n");
        TopicoDeAjuda b = GuiaDeAjuda.interpretar("b", "# Sem conexão\n@busca: wifi rede\n@resumo: O app tenta de novo.\n1. Veja a faixa.\n");
        return new GuiaDeAjuda(List.of(new CategoriaDeAjuda("Um", List.of("a")), new CategoriaDeAjuda("Dois", List.of("b"))), List.of(a, b));
    }

    @Test
    void aBuscaIgnoraMaiusculaEAcentoExigeTodasAsPalavrasEOlhaOTextoTodo() {
        GuiaDeAjuda g = guia();
        assertEquals(2, g.filtrar("").size());
        assertEquals(2, g.filtrar(null).size());
        assertEquals(List.of("b"), g.filtrar("CONEXAO").stream().map(TopicoDeAjuda::id).toList(), "sem acento e em maiúscula acha 'Sem conexão'");
        assertEquals(List.of("a"), g.filtrar("impressao digital").stream().map(TopicoDeAjuda::id).toList(), "acha dentro dos detalhes e exige todas as palavras");
        assertEquals(List.of("b"), g.filtrar("wifi").stream().map(TopicoDeAjuda::id).toList(), "as palavras extras da busca valem");
        assertEquals(List.of(), g.filtrar("wifi impressao"));
        assertEquals(List.of(), g.filtrar("zzz"));
    }

    @Test
    void osTrechosAchadosBatemComAsPosicoesDoTextoOriginal() {
        String texto = "Configuração do SERVIDOR e configuracao";
        List<int[]> t = GuiaDeAjuda.trechos(texto, "configuracao servidor");
        assertEquals(3, t.size());
        assertEquals("Configuração", texto.substring(t.get(0)[0], t.get(0)[1]));
        assertEquals("SERVIDOR", texto.substring(t.get(1)[0], t.get(1)[1]));
        assertEquals("configuracao", texto.substring(t.get(2)[0], t.get(2)[1]));
        assertEquals(0, GuiaDeAjuda.trechos(texto, "").size());
        List<int[]> unidos = GuiaDeAjuda.trechos("abcabc", "abc bc");
        assertEquals(1, unidos.size(), "trechos que se encostam ou se sobrepõem viram um só");
        assertEquals(0, unidos.get(0)[0]);
        assertEquals(6, unidos.get(0)[1]);
    }

    @Test
    void osAtalhosViramATeclaDoMenuELinhaSemChaveDizSemAtalho() {
        List<AtalhoDoMenu> atalhos = List.of(new AtalhoDoMenu("ajustes", "Ajustes", "Cmd+,"), new AtalhoDoMenu("ajuda", "Guia", "F1"));
        assertEquals("Use Cmd+, e depois F1 ou (sem atalho).", GuiaDeAjuda.resolver("Use {atalho:ajustes} e depois {atalho:ajuda} ou {atalho:nada}.", atalhos));
        assertEquals(List.of("ajustes", "a-b"), GuiaDeAjuda.chavesCitadas("{atalho:ajustes} e {atalho:a-b}"));
        TopicoDeAjuda t = GuiaDeAjuda.interpretar("x", EXEMPLO);
        assertTrue(GuiaDeAjuda.chavesCitadas(t).stream().allMatch(c -> c.equals("ajustes")), "as chaves citadas nos passos e no onde");
        assertFalse(GuiaDeAjuda.chavesCitadas(t).isEmpty());
    }

    @Test
    void oTextoParaCopiarLevaTituloResumoPassosDetalhesETabela() {
        List<AtalhoDoMenu> atalhos = List.of(new AtalhoDoMenu("ajustes", "Ajustes", "Cmd+,"));
        String c = GuiaDeAjuda.textoParaCopiar(GuiaDeAjuda.interpretar("x", EXEMPLO), atalhos);
        assertTrue(c.startsWith("Como faço isto?\n\nResumo rápido: Uma frase de resumo."), c);
        assertTrue(c.contains("1. Primeiro passo, com Cmd+,."), c);
        assertTrue(c.contains("Cmd+,  Ajustes"), "a tabela de atalhos entra");
        assertTrue(c.contains("MAIS DETALHES\nTexto longo dos detalhes."), c);
        assertFalse(c.contains("{atalho"), "nenhum marcador sobra");
    }

    @Test
    void ondeFicaJuntaAsLinhasDeTodosOsTopicos() {
        TopicoDeAjuda a = GuiaDeAjuda.interpretar("a", "# A?\n@onde: x | A > B\n");
        TopicoDeAjuda b = GuiaDeAjuda.interpretar("b", "# B?\n@onde: y | C > D | projetos\n@onde: z | E > F\n");
        GuiaDeAjuda g = new GuiaDeAjuda(List.of(new CategoriaDeAjuda("C", List.of("a", "b"))), List.of(a, b));
        assertEquals(List.of("x", "y", "z"), g.ondeFica().stream().map(OndeFica::oQue).toList());
    }

    @Test
    void oHistoricoVoltaEAvancaComoUmNavegador() {
        HistoricoDeAjuda h = new HistoricoDeAjuda();
        assertNull(h.atual());
        assertFalse(h.podeVoltar() || h.podeAvancar());
        h.abrir("a");
        h.abrir("a");
        h.abrir("b");
        h.abrir("c");
        assertEquals(List.of("a", "b", "c"), h.lidos(), "abrir o mesmo de novo não duplica");
        assertEquals("b", h.voltar());
        assertEquals("a", h.voltar());
        assertFalse(h.podeVoltar());
        assertEquals("a", h.voltar(), "no começo não anda");
        assertEquals("b", h.avancar());
        assertTrue(h.podeAvancar());
        h.abrir("d");
        assertEquals(List.of("a", "b", "d"), h.lidos(), "abrir um tópico novo apaga o que estava à frente");
        assertFalse(h.podeAvancar());
        assertEquals("b", h.anterior());
        assertEquals("b", h.maisRecenteQueNaoEOAtual());
        h.voltar();
        assertEquals("d", h.maisRecenteQueNaoEOAtual(), "mesmo voltando, o mais recente que não é o atual");
        assertNull(new HistoricoDeAjuda().anterior());
    }

    @Test
    void osDestinosTemIdsUnicosEOptionalDeId() {
        assertEquals(Destino.values().length, java.util.Arrays.stream(Destino.values()).map(Destino::id).distinct().count());
        assertEquals(Destino.PROJETOS, Destino.de("projetos").orElseThrow());
        assertTrue(Destino.de("nada").isEmpty());
    }
}
