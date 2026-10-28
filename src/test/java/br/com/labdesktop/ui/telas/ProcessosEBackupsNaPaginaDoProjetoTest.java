package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.arquivos.PlanoDeBackups;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Processos e Backups na página de um projeto: o botão "Adicionar domínio…" na barra de cima (em toda aba, desligado com a explicação quando as ações não estão ligadas), a aba Backups com Baixar e
 * Baixar selecionados, e a aba Processos com Encerrar e Reiniciar só dos processos do projeto.
 */
@ExtendWith(VerificaEdt.class)
class ProcessosEBackupsNaPaginaDoProjetoTest {

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    // ---------------------------------------------------------------------------------------------
    // Adicionar domínio
    // ---------------------------------------------------------------------------------------------

    @Test
    void oBotaoAdicionarDominioFicaNaBarraDeCimaEmTodaAbaELigadoComAsAcoes() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        Botao b = ed(c.tela::botaoAdicionarDominio);
        assertEquals("Adicionar domínio…", ed(b::getText));
        assertTrue(ed(b::isVisible));
        assertTrue(ed(b::isEnabled));
        for (Aba aba : Aba.values()) {
            if (ed(() -> c.tela.abas().visivel(aba.id()))) {
                c.naAba(aba);
                assertTrue(ed(b::isShowing) || ed(b::isVisible), "visível na aba " + aba);
                assertTrue(ed(() -> c.tela.topoDaPagina().isAncestorOf(b)), "mora na barra de cima, que não troca com a aba");
            }
        }
        Visual.naEdt(() -> b.doClick(0));
        assertEquals(List.of("adicionar-dominio"), c.saida.pedidos.stream().filter(p -> p.startsWith("adicionar")).toList());
    }

    @Test
    void semAsAcoesOBotaoAparecePorEmBaixoDesligadoComAExplicacao() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(false, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        Botao b = ed(c.tela::botaoAdicionarDominio);
        assertTrue(ed(b::isVisible), "não some");
        assertFalse(ed(b::isEnabled));
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", ed(() -> b.getToolTipText()));
        Visual.naEdt(() -> b.doClick(0));
        assertTrue(c.saida.pedidos.stream().noneMatch(p -> p.startsWith("adicionar")));
    }

    @Test
    void naAbaInternetOsDoisCaminhosExistemEFicamDesligadosSemAsAcoes() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(false, Aba.INTERNET).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaInternet p = ed(c.tela::internet);
        Visual.naEdt(p::recarregar); // sem servidor vivo: lê o cofre (nada) e desenha o estado "não conectada"
        assertEquals("Adicionar domínio…", ed(() -> p.botaoAdicionarDominio().getText()));
        assertFalse(ed(() -> p.botaoAdicionarDominio().isEnabled()));
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", ed(() -> p.botaoAdicionarDominio().getToolTipText()));
    }

    // ---------------------------------------------------------------------------------------------
    // Backups
    // ---------------------------------------------------------------------------------------------

    @Test
    void backupsTemBaixarEmCadaLinhaESelecionadosSoLigaComAlgoMarcado() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(false, Aba.BACKUPS).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaBackups p = ed(c.tela::backups);
        assertEquals(5, ed(() -> p.botoesDeBaixar().size()), "um Baixar por backup");
        assertTrue(ed(() -> p.botoesDeBaixar().stream().allMatch(Botao::isEnabled)), "baixar só lê: não depende da trava das ações (aqui desligada)");
        assertEquals("Baixar selecionados", ed(() -> p.botaoBaixarSelecionados().getText()));
        assertFalse(ed(() -> p.botaoBaixarSelecionados().isEnabled()));
        Visual.naEdt(() -> p.caixasDeMarcar().get(0).doClick());
        Visual.naEdt(() -> p.caixasDeMarcar().get(2).doClick());
        assertEquals("Baixar selecionados (2)", ed(() -> p.botaoBaixarSelecionados().getText()));
        assertTrue(ed(() -> p.botaoBaixarSelecionados().isEnabled()));
        assertEquals("Limpar a seleção".equals(ed(() -> p.botaoSelecionarTodos().getText())), false);
        Visual.naEdt(() -> p.botaoSelecionarTodos().doClick());
        assertEquals("Baixar selecionados (5)", ed(() -> p.botaoBaixarSelecionados().getText()));
        assertEquals("Limpar a seleção", ed(() -> p.botaoSelecionarTodos().getText()));
        Visual.naEdt(() -> p.botaoSelecionarTodos().doClick());
        assertEquals("Baixar selecionados", ed(() -> p.botaoBaixarSelecionados().getText()));
    }

    @Test
    void cliqueEmBaixarEntregaOArquivoCertoEOSelecionadosEntregaTodosOsMarcados() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(false, Aba.BACKUPS).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        List<PlanoDeBackups.Backup> pedidos = new ArrayList<>();
        c.tela.contexto().baixarBackups = pedidos::addAll;
        PaginaBackups p = ed(c.tela::backups);
        String primeiro = ed(() -> p.tabela().linhasDaPagina().get(0).get(0));
        Visual.naEdt(() -> p.botoesDeBaixar().get(0).doClick(0));
        assertEquals(List.of(primeiro), pedidos.stream().map(PlanoDeBackups.Backup::arquivo).toList());
        assertTrue(pedidos.get(0).tamanho() != null && pedidos.get(0).quando() != null, "leva o tamanho e a hora que o servidor disse");
        pedidos.clear();
        Visual.naEdt(() -> p.caixasDeMarcar().get(1).doClick());
        Visual.naEdt(() -> p.caixasDeMarcar().get(3).doClick());
        Visual.naEdt(() -> p.botaoBaixarSelecionados().doClick(0));
        assertEquals(List.of(ed(() -> p.tabela().linhasDaPagina().get(1).get(0)), ed(() -> p.tabela().linhasDaPagina().get(3).get(0))), pedidos.stream().map(PlanoDeBackups.Backup::arquivo).toList());
    }

    @Test
    void semConexaoOsBotoesDeBaixarFicamDesligadosEDizemPorQue() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(true, Aba.BACKUPS).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        Visual.naEdt(() -> c.tela.aoMudarEstado(TelaProjetoTest.estado(EstadoConexao.RECONECTANDO)));
        PaginaBackups p = ed(c.tela::backups);
        assertTrue(ed(() -> p.botoesDeBaixar().stream().noneMatch(Botao::isEnabled)));
        assertTrue(ed(() -> p.botoesDeBaixar().get(0).getToolTipText()).contains("Sem conexão com o servidor"));
    }

    @Test
    void semBackupsAFraseDeSempreENenhumBotaoDeBaixar() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(true, Aba.BACKUPS).com(EstadoConexao.CONECTADO,
            TelaProjetoTest.detalheCom("\"backups\": [", "\"backups\": [], \"backups_antigos\": ["));
        PaginaBackups p = ed(c.tela::backups);
        assertEquals(0, ed(() -> p.botoesDeBaixar().size()));
        assertFalse(ed(() -> p.botaoBaixarSelecionados().isVisible()));
        assertTrue(TelaProjetoTest.textos(p).contains("Nenhum backup ainda"));
    }

    @Test
    void pngsDaAbaBackupsComOsBotoes() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(true, Aba.BACKUPS).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
            PaginaBackups p = ed(c.tela::backups);
            Visual.naEdt(() -> p.caixasDeMarcar().get(0).doClick());
            Visual.naEdt(() -> p.caixasDeMarcar().get(1).doClick());
            Visual.salvar(Visual.renderizar(c.tela, 1100, 700), "projeto-backups-baixar-" + (t == Tema.CLARO ? "claro" : "escuro"));
        }
        Visual.tema(Tema.CLARO);
    }

    // ---------------------------------------------------------------------------------------------
    // Processos do projeto
    // ---------------------------------------------------------------------------------------------

    @Test
    void abaProcessosMostraSoOsDoProjetoComEncerrarEReiniciarEPedeAConfirmacao() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(true, Aba.PROCESSOS).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        // a aba é do projeto "orbita": nenhum dos processos da fixture é dele
        PaginaProcessosDoProjeto p = ed(c.tela::processos);
        InfoServidor info = FixturesDeApi.de("{\"processos\":[{\"pid\":3100,\"nome\":\"node\",\"usuario\":\"node\",\"cpu\":5,\"mem\":1000,\"container\":\"orbita-web-1\",\"projeto\":\"orbita\"},"
            + "{\"pid\":3101,\"nome\":\"node\",\"usuario\":\"node\",\"cpu\":4,\"mem\":1000,\"container\":\"beta-web-1\",\"projeto\":\"beta\"}]}", InfoServidor.class);
        Visual.naEdt(() -> p.receber(info, null));
        assertEquals(List.of("node"), ed(() -> p.bloco().linhas().stream().map(l -> l.get(0)).toList()));
        assertEquals(List.of("Parar", "Reiniciar"), ed(() -> p.bloco().botoes().stream().map(Botao::getText).toList()));
        Visual.naEdt(() -> p.bloco().botoes().get(1).doClick(0));
        assertEquals(List.of("processo:REINICIAR:3100:orbita (orbita-web-1)"), c.saida.pedidos.stream().filter(x -> x.startsWith("processo")).toList());
    }

    @Test
    void abaProcessosSemAcoesTemOsBotoesDesligados() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(false, Aba.PROCESSOS).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaProcessosDoProjeto p = ed(c.tela::processos);
        InfoServidor info = FixturesDeApi.de("{\"processos\":[{\"pid\":3100,\"nome\":\"node\",\"usuario\":\"node\",\"cpu\":5,\"mem\":1000,\"container\":\"orbita-web-1\",\"projeto\":\"orbita\"}]}", InfoServidor.class);
        Visual.naEdt(() -> p.receber(info, null));
        assertTrue(ed(() -> p.bloco().botoes().stream().noneMatch(Botao::isEnabled)));
        assertTrue(ed(p.bloco()::textoDaNota).contains("As ações de escrita estão desligadas"));
    }

    @Test
    void naPaginaDoProjetoAAbaProcessosExisteEAssinaOServidorSoQuandoAparece() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        assertTrue(ed(() -> c.tela.abas().visivel("processos")));
        assertTrue(Visual.naEdt(() -> c.tela.abas().botao("processos").getText()).equals("Processos"));
    }
}
