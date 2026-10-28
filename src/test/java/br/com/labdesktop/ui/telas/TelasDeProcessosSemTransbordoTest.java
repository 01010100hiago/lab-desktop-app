package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.AmbienteDeProcessos;
import br.com.labdesktop.processos.AvaliadorDeProcessos;
import br.com.labdesktop.processos.Oferta;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * As telas de Processos e Backups em 1100 x 700 nos dois temas: nada sai da largura, nenhum emoji, e os PNGs (Processos do servidor e do projeto, Máquina com o Git, Internet com Adicionar
 * domínio, a barra do projeto) para o olho conferir.
 */
@ExtendWith(VerificaEdt.class)
class TelasDeProcessosSemTransbordoTest {

    private static final AcoesDeProcessos ACOES = new AcoesDeProcessos() {
        @Override
        public boolean liberadas() {
            return true;
        }

        @Override
        public Oferta avaliar(ProcessoAlvo alvo) {
            return AvaliadorDeProcessos.avaliar(alvo, AmbienteDeProcessos.servidor("labuser", Set.of()));
        }

        @Override
        public void abrir(ProcessoAlvo alvo, String origem, Acao acao, Runnable depois) { }
    };

    private static final String PROCESSOS = "{\"processos\":[{\"pid\":2000,\"nome\":\"node\",\"usuario\":\"labuser\",\"cpu\":3,\"mem\":1000},"
        + "{\"pid\":2001,\"nome\":\"nginx\",\"usuario\":\"www-data\",\"cpu\":2,\"mem\":1000},{\"pid\":2002,\"nome\":\"sshd\",\"usuario\":\"labuser\",\"cpu\":1,\"mem\":1000},"
        + "{\"pid\":1001,\"nome\":\"claude\",\"usuario\":\"labuser\",\"cpu\":9,\"mem\":422277120,\"container\":\"lab-claude-alfa\"},"
        + "{\"pid\":1003,\"nome\":\"postgres\",\"usuario\":\"999\",\"cpu\":8.1,\"mem\":96468992,\"container\":\"gama-db\"}]}";

    private static final String SEM_EMOJI = "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]";

    private static String nome(String base, Tema t) {
        return base + "-" + (t == Tema.CLARO ? "claro" : "escuro");
    }

    @Test
    void processosDoServidorEmMilECemPorSetecentosSemTransbordoNosDoisTemas() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            PaginaProcessos p = Visual.naEdt(() -> new PaginaProcessos(null, () -> { }, ACOES));
            Visual.naEdt(() -> {
                p.aoMudarEstado(TelaProjetoTest.estado(EstadoConexao.CONECTADO));
                p.aoReceberServidor(FixturesDeApi.de(PROCESSOS, InfoServidor.class));
                p.aoReceberDetalhe(FixturesDeApi.detalheSintetico());
            });
            assertEquals(List.of(), Visual.naEdt(() -> SemTransbordo.achados(prepararLargura(p, 1100, 700))), "tema " + t);
            String texto = Visual.naEdt(() -> TextosDeTela.de(p));
            assertFalse(java.util.regex.Pattern.compile(SEM_EMOJI).matcher(texto).find(), "sem emoji: " + texto);
            Visual.salvar(Visual.renderizar(p, 1100, 700), nome("servidor-processos-encerrar", t));
        }
        Visual.tema(Tema.CLARO);
    }

    private static javax.swing.JComponent prepararLargura(javax.swing.JComponent c, int largura, int altura) {
        c.setSize(largura, altura);
        return c;
    }

    @Test
    void paginaDoProjetoComABarraNovaEAbasNovasEmMilECemPorSetecentos() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            for (Aba aba : new Aba[] {Aba.VISAO_GERAL, Aba.BACKUPS, Aba.INTERNET, Aba.PROCESSOS}) {
                TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(true, aba).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
                if (aba == Aba.PROCESSOS) {
                    Visual.naEdt(() -> c.tela.processos().receber(FixturesDeApi.de("{\"processos\":[{\"pid\":3100,\"nome\":\"node\",\"usuario\":\"node\",\"cpu\":5,\"mem\":1000,\"container\":\"orbita-web-1\","
                        + "\"projeto\":\"orbita\"},{\"pid\":3101,\"nome\":\"postgres\",\"usuario\":\"999\",\"cpu\":4,\"mem\":5000,\"container\":\"orbita-db-1\",\"projeto\":\"orbita\"}]}", InfoServidor.class), null));
                }
                if (aba == Aba.INTERNET) {
                    Visual.naEdt(() -> c.tela.internet().recarregar());
                }
                assertEquals(List.of(), Visual.naEdt(() -> SemTransbordo.achados(prepararLargura(c.tela, 1100, 700))), aba + " " + t);
                String texto = Visual.naEdt(() -> TextosDeTela.de(c.tela));
                assertFalse(java.util.regex.Pattern.compile(SEM_EMOJI).matcher(texto).find(), "sem emoji em " + aba);
                assertTrue(texto.contains("Adicionar domínio…"), "o botão está na barra de cima de toda aba: " + aba);
                Visual.salvar(Visual.renderizar(c.tela, 1100, 700), nome("projeto-" + aba.id() + "-pacote-j", t));
            }
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void abaMaquinaComOBlocoDoGitNosTresEstados() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            PaginaMaquina p = Visual.naEdt(() -> new PaginaMaquina(null, () -> { }, null));
            Visual.naEdt(() -> {
                p.aoMudarEstado(TelaProjetoTest.estado(EstadoConexao.CONECTADO));
                p.aoReceberServidor(FixturesDeApi.servidorSintetico());
                p.aoReceberDetalhe(FixturesDeApi.detalheSintetico());
            });
            for (var par : List.of(java.util.Map.entry(BlocoDoGit.Modo.INSTALADO, "git version 2.43.0"), java.util.Map.entry(BlocoDoGit.Modo.AUSENTE, ""),
                java.util.Map.entry(BlocoDoGit.Modo.SEM_CONEXAO, ""))) {
                Visual.naEdt(() -> p.git().definir(par.getKey(), par.getValue()));
                assertEquals(List.of(), Visual.naEdt(() -> SemTransbordo.achados(prepararLargura(p, 1100, 700))), par.getKey() + " " + t);
                int fundoDoBloco = Visual.naEdt(() -> javax.swing.SwingUtilities.convertPoint(p.git(), 0, 0, p).y + p.git().getHeight());
                assertTrue(fundoDoBloco <= 700, "o bloco do Git aparece sem rolar na janela de 1100 por 700 (termina em " + fundoDoBloco + ") " + par.getKey() + " " + t);
                Visual.salvar(Visual.renderizar(p, 1100, 700), nome("servidor-maquina-git-" + par.getKey().name().toLowerCase().replace('_', '-'), t));
            }
            assertEquals("SEM CONEXÃO", Visual.naEdt(() -> p.git().textoDoSelo()));
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void blocoDoGitDizOQueFazCadaEstado() {
        Visual.tema(Tema.CLARO);
        BlocoDoGit b = Visual.naEdt(() -> new BlocoDoGit(null, null));
        Visual.naEdt(() -> b.definir(BlocoDoGit.Modo.INSTALADO, "git version 2.43.0"));
        assertEquals("INSTALADO", Visual.naEdt(b::textoDoSelo));
        assertTrue(Visual.naEdt(b::textoMostrado).contains("git version 2.43.0"));
        assertFalse(Visual.naEdt(() -> b.botaoInstalar().isVisible()));
        Visual.naEdt(() -> b.definir(BlocoDoGit.Modo.AUSENTE, ""));
        assertEquals("NÃO INSTALADO", Visual.naEdt(b::textoDoSelo));
        assertTrue(Visual.naEdt(b::textoMostrado).contains("sem ele o app não clona repositórios"));
        assertFalse(Visual.naEdt(() -> b.botaoInstalar().isVisible()), "sem as ações da janela, sem o botão (os testes não têm janela)");
        assertTrue(Visual.naEdt(() -> b.botaoConferirDeNovo().isVisible()));
    }
}
