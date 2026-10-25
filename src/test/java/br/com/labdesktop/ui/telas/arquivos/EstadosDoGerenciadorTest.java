package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.arquivos.GerenciadorDeArquivos.Situacao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Os estados da lista: carregando, vazia, sem conexão (com e sem dados), SFTP indisponível, sem permissão, não existe, erro. */
@ExtendWith(VerificaEdt.class)
class EstadosDoGerenciadorTest {

    private static final String O = CenarioDeSftp.ORBITA;

    private static CenaDeArquivos cena(Path tmp, CenaDeArquivos.Preparo preparo, boolean iniciar) throws Exception {
        Visual.tema(Tema.CLARO);
        return new CenaDeArquivos(tmp, true, "orbita", preparo, iniciar);
    }

    @Test
    void antesDaPrimeiraLeituraMostraOEsqueletoENuncaATabelaEmBranco(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, CenaDeArquivos::projetoDeExemplo, false)) {
            assertEquals(Situacao.INICIANDO, Visual.naEdt(c.g::situacao));
            assertEquals("esqueleto", Visual.naEdt(c.g::cartaoDaListaVisivel));
            Visual.naEdt(c.g::iniciar);
            c.esperarNormal();
            assertEquals("tabela", Visual.naEdt(c.g::cartaoDaListaVisivel));
        }
    }

    @Test
    void pastaVaziaDizEmTexto(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, s -> { }, true)) {
            c.esperar(Situacao.VAZIA);
            assertEquals("mensagem", Visual.naEdt(c.g::cartaoDaListaVisivel));
            assertEquals("Esta pasta está vazia.", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
        }
    }

    @Test
    void semConexaoComDadosEsmaeceDizAHoraDesabilitaAsAcoesEVoltaSozinho(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, CenaDeArquivos::projetoDeExemplo, true)) {
            c.esperarNormal();
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md"));
            Visual.naEdt(() -> c.g.aoMudarEstadoDaConexao(CenaDeArquivos.estado(EstadoConexao.RECONECTANDO)));
            assertEquals(Situacao.SEM_CONEXAO, Visual.naEdt(c.g::situacao));
            assertEquals("tabela", Visual.naEdt(c.g::cartaoDaListaVisivel), "os últimos dados continuam à vista");
            assertTrue(Visual.naEdt(() -> c.g.tabelaEsmaecivel().esmaecido()), "esmaecidos");
            assertTrue(Visual.naEdt(() -> c.g.faixaDeDados().isVisible()));
            assertTrue(Visual.naEdt(() -> c.g.faixaDeDados().textoDoTitulo()).contains("Sem conexão com o servidor: mostrando os últimos dados de"),
                Visual.naEdt(() -> c.g.faixaDeDados().textoDoTitulo()));
            for (String b : List.of("enviar", "baixar", "nova-pasta", "novo-arquivo", "renomear", "apagar", "mais", "atualizar", "subir", "raiz")) {
                assertFalse(Visual.naEdt(() -> c.g.botao(b).isEnabled()), b + " desabilitado sem conexão");
            }
            // as ações recusam sozinhas, mesmo que alguém as chame
            Visual.naEdt(() -> c.g.criarPasta("nova"));
            assertFalse(Files.exists(c.sftp.orbita().resolve("nova")));

            // a conexão volta: a pasta é lida de novo sozinha (e o que mudou aparece)
            c.sftp.escrever(O + "/apareceu-enquanto-isso.txt", "x");
            Visual.naEdt(() -> c.g.aoMudarEstadoDaConexao(CenaDeArquivos.estado(EstadoConexao.CONECTADO)));
            c.esperarQue("leu de novo sozinho", () -> c.g.situacao() == Situacao.NORMAL && c.nomes().contains("apareceu-enquanto-isso.txt"));
            assertFalse(Visual.naEdt(() -> c.g.tabelaEsmaecivel().esmaecido()));
            assertFalse(Visual.naEdt(() -> c.g.faixaDeDados().isVisible()));
            assertTrue(Visual.naEdt(() -> c.g.botao("baixar").isEnabled()), "a seleção continuou, e baixar voltou");
            assertEquals(List.of("README.md"), Visual.naEdt(() -> c.g.tabela().selecionadas().stream().map(e -> e.nome()).toList()));
        }
    }

    @Test
    void semConexaoSemDadosMostraOCartao(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, CenaDeArquivos::projetoDeExemplo, false)) {
            Visual.naEdt(() -> c.g.aoMudarEstadoDaConexao(CenaDeArquivos.estado(EstadoConexao.RECONECTANDO)));
            Visual.naEdt(c.g::iniciar);
            assertEquals(Situacao.SEM_CONEXAO, Visual.naEdt(c.g::situacao));
            assertEquals("mensagem", Visual.naEdt(c.g::cartaoDaListaVisivel));
            assertEquals("Sem conexão com o servidor", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
            assertTrue(Visual.naEdt(() -> c.g.cartao().textoDaExplicacao()).contains("Assim que a conexão voltar, os arquivos aparecem aqui."));
            // quando volta, abre sozinho
            Visual.naEdt(() -> c.g.aoMudarEstadoDaConexao(CenaDeArquivos.estado(EstadoConexao.CONECTADO)));
            c.esperarNormal();
            assertTrue(c.nomes().contains("README.md"));
        }
    }

    @Test
    void semNenhumaConexaoAteHojeMostraSemConexao() throws Exception {
        Visual.tema(Tema.CLARO);
        var ctx = new ContextoDeArquivos("s1", "lab", "orbita", null, null, new ServicosDeArquivos(MemoriaDeCaminhos.emMemoria(), Path.of("build", "tmp", "nada")), true,
            new AvisosGravados(), new DialogosGravados(), new CenaDeArquivos.EscolhedorFalso(), CopiadorDeTexto.doSistema(), null,
            () -> CenaDeArquivos.AGORA, CenaDeArquivos.SP);
        GerenciadorDeArquivos g = Visual.naEdt(() -> new GerenciadorDeArquivos(ctx));
        Visual.naEdt(g::iniciar);
        assertEquals(Situacao.SEM_CONEXAO, Visual.naEdt(g::situacao));
        assertEquals("mensagem", Visual.naEdt(g::cartaoDaListaVisivel));
        Visual.naEdt(g::encerrar);
    }

    @Test
    void servidorSemSftpDizQueNaoAceitaTransferenciaDeArquivos(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(new CenarioDeSftp(tmp, false), tmp, true, "orbita", null, true)) {
            c.esperar(Situacao.SFTP_INDISPONIVEL);
            assertEquals("Este servidor não aceita transferência de arquivos", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
            assertTrue(Visual.naEdt(() -> c.g.cartao().textoDaExplicacao()).contains("SFTP"));
            assertEquals("Tentar de novo", Visual.naEdt(() -> c.g.cartao().botao().getText()));
            assertFalse(Visual.naEdt(() -> c.g.botao("enviar").isEnabled()));
        }
    }

    @Test
    void semPermissaoParaAbrirAPastaTemVoltar(@TempDir Path tmp) throws Exception {
        Path[] fechada = new Path[1];
        try (CenaDeArquivos c = cena(tmp, s -> {
            CenaDeArquivos.projetoDeExemplo(s);
            fechada[0] = Files.createDirectories(s.local(O + "/privada"));
            Files.setPosixFilePermissions(fechada[0], PosixFilePermissions.fromString("---------"));
        }, true)) {
            try {
                c.esperarNormal();
                Visual.naEdt(() -> c.g.irPara(O + "/privada"));
                c.esperar(Situacao.SEM_PERMISSAO);
                assertEquals("Sem permissão para abrir esta pasta", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
                assertEquals("Voltar", Visual.naEdt(() -> c.g.cartao().botao().getText()));
                assertEquals(List.of("orbita", "privada"), Visual.naEdt(() -> c.g.barraDeCaminho().textos()), "as migalhas mostram onde o usuário tentou ir");
                assertTrue(Visual.naEdt(() -> c.g.botao("subir").isEnabled()), "dá para subir");
                assertTrue(Visual.naEdt(() -> c.g.botao("voltar").isEnabled()));
                Visual.naEdt(() -> c.g.cartao().botao().doClick(0));
                c.esperarQue("voltou", () -> O.equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            } finally {
                Files.setPosixFilePermissions(fechada[0], PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    @Test
    void pastaApagadaPorForaDizQueNaoExisteEOferecerSubir(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, CenaDeArquivos::projetoDeExemplo, true)) {
            c.esperarNormal();
            c.irParaEEsperar(O + "/docs");
            Files.delete(c.sftp.orbita().resolve("docs/arquitetura.md"));
            Files.delete(c.sftp.orbita().resolve("docs"));
            Visual.naEdt(c.g::atualizar);
            c.esperar(Situacao.NAO_ENCONTRADA);
            assertEquals("Esta pasta não existe (mais)", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
            assertEquals("Subir uma pasta", Visual.naEdt(() -> c.g.cartao().botao().getText()));
            Visual.naEdt(() -> c.g.cartao().botao().doClick(0));
            c.esperarQue("subiu", () -> O.equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            assertFalse(c.nomes().contains("docs"));
        }
    }

    @Test
    void erroInesperadoDaPastaViraFraseSimplesComTentarDeNovo(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, CenaDeArquivos::projetoDeExemplo, true)) {
            c.esperarNormal();
            Visual.naEdt(() -> c.g.irPara(O + "/README.md")); // um arquivo no lugar de uma pasta
            c.esperar(Situacao.ERRO);
            assertEquals("Não consegui abrir a pasta", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
            assertFalse(Visual.naEdt(() -> c.g.cartao().textoDaExplicacao()).contains("SSH_FX"), "nada de código técnico na tela");
            assertEquals("Tentar de novo", Visual.naEdt(() -> c.g.cartao().botao().getText()));
        }
    }

    @Test
    void quedaDeVerdadeNoMeioDaOperacaoSeCuraComAReconexao(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, CenaDeArquivos::projetoDeExemplo, true)) {
            c.esperarNormal();
            // o servidor derruba a sessão; o app reconecta sozinho (backoff curto de teste) e a tela se recupera
            c.sftp.ssh.sshd.getActiveSessions().forEach(s -> s.close(true));
            Visual.naEdt(c.g::atualizar);
            c.esperarQue("recuperou", () -> c.g.situacao() == Situacao.NORMAL && c.g.emVoo() == 0 && c.nomes().contains("README.md"));
            assertNotNull(Visual.naEdt(c.g::listagem));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PNGs dos estados (a área da página do projeto/servidor em 1100 x 700)
    // ---------------------------------------------------------------------------------------------

    @Test
    void geraOsPngsDosEstados(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        for (String d : new String[] {"a", "b", "c", "d"}) {
            Files.createDirectories(tmp.resolve(d));
        }
        try (CenaDeArquivos c = new CenaDeArquivos(new CenarioDeSftp(tmp.resolve("a")), tmp.resolve("a"), false, null, CenaDeArquivos::projetoDeExemplo, false)) {
            Visual.salvar(Visual.renderizar(c.g, 884, 634), "arquivos-carregando");
            Visual.naEdt(c.g::iniciar);
            c.esperarNormal();
            c.esperarQue("árvore", () -> c.g.arvore().caminhosCarregados().contains("/srv/lab/projects"));
            Visual.naEdt(() -> c.g.selecionarNaTabela("orbita"));
            Visual.salvar(Visual.renderizar(c.g, 884, 634), "arquivos-somente-leitura");
            c.irParaEEsperar(O);
            Visual.naEdt(() -> c.g.aoMudarEstadoDaConexao(CenaDeArquivos.estado(EstadoConexao.RECONECTANDO)));
            Visual.salvar(Visual.renderizar(c.g, 884, 634), "arquivos-sem-conexao");
        }
        try (CenaDeArquivos c = new CenaDeArquivos(new CenarioDeSftp(tmp.resolve("b"), false), tmp.resolve("b"), true, null, null, true)) {
            c.esperar(Situacao.SFTP_INDISPONIVEL);
            Visual.salvar(Visual.renderizar(c.g, 884, 634), "arquivos-sftp-indisponivel");
        }
        try (CenaDeArquivos c = new CenaDeArquivos(new CenarioDeSftp(tmp.resolve("c")), tmp.resolve("c"), true, "orbita", s -> { }, true)) {
            c.esperar(Situacao.VAZIA);
            Visual.salvar(Visual.renderizar(c.g, 884, 634), "arquivos-pasta-vazia");
        }
        Visual.tema(Tema.ESCURO);
        try (CenaDeArquivos c = new CenaDeArquivos(new CenarioDeSftp(tmp.resolve("d")), tmp.resolve("d"), true, "orbita", CenaDeArquivos::projetoDeExemplo, true)) {
            c.esperarNormal();
            Visual.naEdt(() -> c.g.aoMudarEstadoDaConexao(CenaDeArquivos.estado(EstadoConexao.RECONECTANDO)));
            Visual.salvar(Visual.renderizar(c.g, 884, 634), "arquivos-sem-conexao-escuro");
        }
    }
}
