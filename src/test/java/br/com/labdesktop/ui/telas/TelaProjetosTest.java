package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.VisaoProjetos;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.ConteudoDosAvisos;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.ListagemDeProjetos.Filtro;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import br.com.labdesktop.ui.VerificaEdt;

/** A lista de projetos desenhada de verdade (sem janela) com os dados do mock do front. */
@ExtendWith(VerificaEdt.class)
class TelaProjetosTest {

    static EstadoServidor estado(EstadoConexao c) {
        return new EstadoServidor("s1", "lab", c, null, 0, null, null, 1, Estado2fa.DESLIGADO, "mac-de-teste", null,
            FixturesDeApi.limitesDoMock());
    }

    /** Somente leitura. */
    static TelaProjetos nova(AvisosGravados avisos, DialogosGravados dialogos) {
        return nova(avisos, dialogos, false);
    }

    static TelaProjetos nova(AvisosGravados avisos, DialogosGravados dialogos, boolean acoesLiberadas) {
        return Visual.naEdt(() -> {
            FluxosDeProjeto fluxos = new FluxosDeProjeto(null, avisos, dialogos, acoesLiberadas);
            return new TelaProjetos(null, fluxos, new NavegadorGravado(avisos), null);
        });
    }

    static VisaoProjetos visaoDoMock() {
        return new VisaoProjetos(FixturesDeApi.projetosDoMock(), FixturesDeApi.raizDoMock());
    }

    /** A lista somente leitura: nenhum botão que mude o servidor, nem na linha nem na faixa de pausas. */
    @Test
    void somenteLeituraNaoTemBotaoDeAcaoNemRetomarNaFaixaDePausas() {
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados());
        Visual.naEdt(() -> {
            tela.aoMudarEstado(estado(EstadoConexao.CONECTADO));
            tela.aoReceberProjetos(visaoDoMock());
        });

        assertFalse(tela.nomesExibidos().isEmpty());
        for (String nome : tela.nomesExibidos()) {
            LinhaProjetoVisual l = tela.linha(nome);
            assertFalse(Visual.naEdt(() -> l.botaoCompose().isVisible()), nome + ": iniciar/parar");
            assertFalse(Visual.naEdt(() -> l.botaoAbrirClaude().isVisible()), nome + ": Abrir Claude");
            assertFalse(Visual.naEdt(() -> l.botaoMenu().isVisible()), nome + ": mais ações");
            assertFalse(Visual.naEdt(() -> l.botaoRetomar().isVisible()), nome + ": Retomar");
            assertFalse(Visual.naEdt(() -> l.botaoReabrir().isVisible()), nome + ": Reabrir");
        }
        assertTrue(tela.faixa().avisosMostrados().stream().anyMatch(a -> a instanceof ConteudoDosAvisos.AvisoDePausas),
            "as sessões pausadas continuam informadas na faixa");
        assertNull(tela.faixa().botaoDeRetomar("app-loja"), "mas sem o botão Retomar");
        Visual.salvar(Visual.renderizar(tela, 1280, 820), "tela-projetos-somente-leitura");
    }

    @Test
    void comAsAcoesLiberadasOsBotoesVoltam() {
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados(), true);
        Visual.naEdt(() -> {
            tela.aoMudarEstado(estado(EstadoConexao.CONECTADO));
            tela.aoReceberProjetos(visaoDoMock());
        });

        assertTrue(tela.nomesExibidos().stream().anyMatch(n -> Visual.naEdt(() -> tela.linha(n).botaoCompose().isVisible())));
        assertTrue(tela.nomesExibidos().stream().anyMatch(n -> Visual.naEdt(() -> tela.linha(n).botaoRetomar().isVisible())));
        assertNotNull(tela.faixa().botaoDeRetomar("app-loja"));
    }

    @Test
    void desenhaAListaDoMockNosDoisTemas() {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados());
            Visual.naEdt(() -> {
                tela.aoMudarEstado(estado(EstadoConexao.CONECTADO));
                tela.aoReceberProjetos(visaoDoMock());
            });
            BufferedImage img = Visual.renderizar(tela, 1280, 820);
            Visual.salvar(img, "tela-projetos-" + tema.name().toLowerCase());
            assertEquals("lista", tela.cartaoAtual());
        }
    }

    @Test
    void cadaLinhaOcupaALarguraTodaDaListaEAsColunasAlinhamComOCabecalho() {
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados());
        Visual.naEdt(() -> {
            tela.aoMudarEstado(estado(EstadoConexao.CONECTADO));
            tela.aoReceberProjetos(visaoDoMock());
        });
        Visual.renderizar(tela, 1280, 820);
        Visual.naEdt(() -> {
            int larguraDaLista = tela.linha(tela.nomesExibidos().get(0)).getParent().getWidth();
            for (String nome : tela.nomesExibidos()) {
                LinhaProjetoVisual l = tela.linha(nome);
                assertEquals(0, l.getX(), "a linha de " + nome + " começa na borda da lista (um filho de alinhamento "
                    + "diferente no BoxLayout empurrava as linhas para a direita)");
                assertEquals(larguraDaLista, l.getWidth(), "a linha de " + nome + " tem a largura da lista");
                assertTrue(l.larguraDaColuna(ColunasDaLista.Coluna.PROJETO) > 100, "o nome tem espaço: " + nome);
            }
            // cada coluna começa no mesmo x no cabeçalho e na linha
            assertEquals(tela.xsDasColunasDoCabecalho(), tela.linha(tela.nomesExibidos().get(0)).xsDasColunas());
        });
    }

    @Test
    void ordenaNoArPrimeiroDepoisPorNomeEMostraOResumo() {
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados());
        Visual.naEdt(() -> tela.aoReceberProjetos(visaoDoMock()));
        List<String> nomes = Visual.naEdt(tela::nomesExibidos);
        assertEquals(5, nomes.size());
        // "no ar" = TODOS os containers rodando, e vêm primeiro; o resto por nome
        ListaProjetos lista = FixturesDeApi.projetosDoMock();
        List<String> esperados = lista.projetos().stream()
            .sorted(java.util.Comparator.comparing((br.com.labdesktop.api.modelo.Projeto p) ->
                p.estado() != br.com.labdesktop.api.modelo.EstadoProjeto.NO_AR).thenComparing(p -> p.nome()))
            .map(br.com.labdesktop.api.modelo.Projeto::nome).toList();
        assertEquals(esperados, nomes);
        assertTrue(Visual.naEdt(tela::textoDoResumo).startsWith("5 projetos, "), Visual.naEdt(tela::textoDoResumo));
        assertTrue(Visual.naEdt(tela::textoDoResumo).contains("2 pausados"), Visual.naEdt(tela::textoDoResumo));
    }

    @Test
    void buscaEFiltroRapidoEstreitamAListaEOEstadoVazioAparece() {
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados());
        Visual.naEdt(() -> tela.aoReceberProjetos(visaoDoMock()));

        Visual.naEdt(() -> tela.botaoDeFiltro(Filtro.PAUSADOS).doClick());
        assertEquals(List.of("app-loja", "vendas"), Visual.naEdt(tela::nomesExibidos).stream().sorted().toList());

        Visual.naEdt(() -> tela.botaoDeFiltro(Filtro.TODOS).doClick());
        Visual.naEdt(() -> tela.campoDeBusca().setText("VEN"));
        assertEquals(List.of("vendas"), Visual.naEdt(tela::nomesExibidos));
        assertEquals("lista", Visual.naEdt(tela::cartaoAtual));

        Visual.naEdt(() -> tela.campoDeBusca().setText("nao-existe"));
        assertEquals("nada", Visual.naEdt(tela::cartaoAtual));
        BufferedImage img = Visual.renderizar(tela, 1100, 520);
        Visual.salvar(img, "tela-projetos-nada-encontrado");

        Visual.naEdt(() -> tela.campoDeBusca().setText(""));
        assertEquals(5, Visual.naEdt(tela::nomesExibidos).size());
    }

    @Test
    void semProjetosMostraOEstadoVazioEAntesDaPrimeiraRespostaOEsqueleto() {
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados());
        assertEquals("esqueleto", Visual.naEdt(tela::cartaoAtual), "antes da primeira resposta: linhas cinzas, nunca tela vazia");
        BufferedImage esq = Visual.renderizar(tela, 1100, 360);
        Visual.salvar(esq, "tela-projetos-carregando");

        ListaProjetos vazia = FixturesDeApi.de("{\"projetos\":[],\"servidor\":{}}", ListaProjetos.class);
        Visual.naEdt(() -> tela.aoReceberProjetos(new VisaoProjetos(vazia, null)));
        assertEquals("vazio", Visual.naEdt(tela::cartaoAtual));
        assertTrue(Visual.naEdt(tela::textoDoResumo).startsWith("0 projetos"));
    }

    @Test
    void aMesmaLinhaSeAtualizaNoLugarSemSerRecriada() {
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados());
        Visual.naEdt(() -> tela.aoReceberProjetos(visaoDoMock()));
        LinhaProjetoVisual antes = Visual.naEdt(() -> tela.linha("vendas"));

        // a mesma lista outra vez, e depois com o CPU de um projeto mudado: o componente é o mesmo
        Visual.naEdt(() -> tela.aoReceberProjetos(visaoDoMock()));
        assertTrue(antes == Visual.naEdt(() -> tela.linha("vendas")));
        String json = br.com.labdesktop.teste.MocksDoFront.ler("projetos.json").replace("\"nome\": \"vendas\"", "\"nome\": \"vendas\"");
        ListaProjetos outra = FixturesDeApi.de(json, ListaProjetos.class);
        Visual.naEdt(() -> tela.aoReceberProjetos(new VisaoProjetos(outra, null)));
        assertTrue(antes == Visual.naEdt(() -> tela.linha("vendas")), "atualiza por nome, sem recriar a linha");
        assertFalse(Visual.naEdt(() -> tela.linha("vendas")) == null);
    }

    /** A tela do servidor tem a barra lateral à esquerda e as margens da lista: a lista ganha o que sobra da janela. */
    private static int larguraDaListaNaJanela(int larguraDaJanela) {
        return larguraDaJanela - br.com.labdesktop.ui.BarraServidores.LARGURA;
    }

    @Test
    void asColunasCabemEm1100E1440SemRolarParaOLadoENemCortarTexto() {
        for (int janela : new int[] {1100, 1440}) {
            Visual.tema(Tema.CLARO);
            TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados(), true);
            Visual.naEdt(() -> {
                tela.aoMudarEstado(estado(EstadoConexao.CONECTADO));
                tela.aoReceberProjetos(visaoDoMock());
            });
            Visual.renderizar(tela, larguraDaListaNaJanela(janela), 700 - 28);
            Visual.naEdt(() -> {
                assertFalse(tela.rolagemDaLista().getHorizontalScrollBar().isVisible(), "sem rolagem para o lado em " + janela);
                assertTrue(ColunasDaLista.larguraMinima() <= larguraDaListaNaJanela(1100) - 40 - 16,
                    "a soma dos pisos (" + ColunasDaLista.larguraMinima() + ") cabe na lista de 1100, mesmo com a barra vertical");
                for (String nome : tela.nomesExibidos()) {
                    LinhaProjetoVisual l = tela.linha(nome);
                    for (java.awt.Component x : TelaServidorTest.todos(l)) {
                        if (x instanceof javax.swing.JLabel r && r.isShowing() == false && r.isVisible() && r.getText() != null
                            && !r.getText().isEmpty() && r.getWidth() > 0 && x != l.rotuloDaDescricao()) { // a descrição é livre e comprida: termina em reticências, com a dica inteira
                            assertTrue(r.getWidth() >= r.getPreferredSize().width - 1, janela + ": '" + r.getText() + "' de " + nome
                                + " cortado (" + r.getWidth() + " de " + r.getPreferredSize().width + ")");
                        }
                    }
                }
            });
        }
    }

    @Test
    void aColunaDeConsumoTemNoMinimo150ABarraDoLimite8PxETextoDe13() {
        assertTrue(ColunasDaLista.Coluna.CONSUMO.minimo >= 150, "piso do consumo: " + ColunasDaLista.Coluna.CONSUMO.minimo);
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados());
        Visual.naEdt(() -> {
            tela.aoMudarEstado(estado(EstadoConexao.CONECTADO));
            tela.aoReceberProjetos(visaoDoMock());
        });
        Visual.renderizar(tela, larguraDaListaNaJanela(1100), 700 - 28);
        LinhaProjetoVisual l = tela.linha("app-loja");
        assertTrue(Visual.naEdt(() -> l.barraDoLimite().getHeight()) >= 8, "barra do limite com 8 px ou mais");
        assertTrue(Visual.naEdt(() -> l.barraDoLimite().getWidth()) >= 140, "e larga o bastante para ler");
        assertEquals(13f, Visual.naEdt(() -> l.rotuloDoLimite().getFont().getSize2D()), "o texto do limite tem 13 px");
        assertTrue(Visual.naEdt(() -> l.rotuloDoLimite().getText()).endsWith("da semana"), Visual.naEdt(() -> l.rotuloDoLimite().getText()));
    }

    @Test
    void geraOsPngsDaLista() {
        for (Tema tema : new Tema[] {Tema.ESCURO, Tema.CLARO}) {
            for (int janela : new int[] {1440, 1100}) {
                Visual.tema(tema);
                TelaProjetos tela = nova(new AvisosGravados(), new DialogosGravados(), true);
                Visual.naEdt(() -> {
                    tela.aoMudarEstado(estado(EstadoConexao.CONECTADO));
                    tela.aoReceberProjetos(visaoDoMock());
                });
                BufferedImage img = Visual.renderizar(tela, larguraDaListaNaJanela(janela), 900);
                Visual.salvar(img, "projetos-lista-" + tema.name().toLowerCase() + (janela == 1100 ? "-1100" : ""));
                if (janela == 1440) {
                    Visual.salvar(img, "projetos-lista-" + tema.name().toLowerCase() + "-1440");
                }
            }
        }
    }
}
