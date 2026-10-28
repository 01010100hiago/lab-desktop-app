package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.AmbienteDeProcessos;
import br.com.labdesktop.processos.AvaliadorDeProcessos;
import br.com.labdesktop.processos.LeitorDeProcessosLocais;
import br.com.labdesktop.processos.Oferta;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Os botões Encerrar e Reiniciar na lista de processos: quem aparece, quem liga, quem é "Protegido" ou "Sem permissão", a explicação quando as ações estão desligadas (o botão aparece
 * desligado, nunca some) e o que cada clique pede. Sem rede: as ações são de mentira e só anotam.
 */
@ExtendWith(VerificaEdt.class)
class ProcessosNaTelaTest {

    /** Ações de mentira: a regra de verdade (o avaliador), a trava que o teste escolhe e o que foi clicado anotado. */
    private static class Acoes implements AcoesDeProcessos {
        boolean liberadas = true;
        final List<String> cliques = new ArrayList<>();
        final AmbienteDeProcessos ambiente = AmbienteDeProcessos.servidor("labuser", Set.of());

        @Override
        public boolean liberadas() {
            return liberadas;
        }

        @Override
        public Oferta avaliar(ProcessoAlvo alvo) {
            return AvaliadorDeProcessos.avaliar(alvo, ambiente);
        }

        @Override
        public void abrir(ProcessoAlvo alvo, String origem, Acao acao, Runnable depois) {
            cliques.add(acao + ":" + alvo.pid() + ":" + alvo.container() + ":" + origem);
        }
    }

    private static BlocoDeProcessos bloco(Acoes a, String projeto) {
        Visual.tema(Tema.CLARO);
        BlocoDeProcessos b = Visual.naEdt(() -> new BlocoDeProcessos(a, projeto));
        Visual.naEdt(() -> b.atualizar(FixturesDeApi.servidorSintetico(), FixturesDeApi.detalheSintetico()));
        return b;
    }

    private static List<String> rotulos(BlocoDeProcessos b) {
        return Visual.naEdt(() -> b.botoes().stream().map(Botao::getText).toList());
    }

    @Test
    void containerTemPararEReiniciarEODockerdFicaProtegidoDesligadoComOMotivoNaDica() {
        Acoes a = new Acoes();
        BlocoDeProcessos b = bloco(a, null);
        assertEquals(List.of("Parar", "Reiniciar", "Parar", "Reiniciar", "Parar", "Reiniciar", "Protegido"), rotulos(b));
        Botao protegido = Visual.naEdt(() -> b.botoes().get(6));
        assertFalse(Visual.naEdt(protegido::isEnabled));
        assertTrue(Visual.naEdt(() -> protegido.getToolTipText()).contains("motor do Docker"), Visual.naEdt(() -> protegido.getToolTipText()));
        assertTrue(Visual.naEdt(() -> protegido.getAccessibleContext().getAccessibleName()).startsWith("Protegido:"));
        for (int i = 0; i < 6; i++) {
            int j = i;
            assertTrue(Visual.naEdt(() -> b.botoes().get(j).isEnabled()), "ligado com as ações liberadas");
        }
    }

    @Test
    void clicarPedeAConfirmacaoComOProcessoEAOrigem() {
        Acoes a = new Acoes();
        BlocoDeProcessos b = bloco(a, null);
        Visual.naEdt(() -> b.botoes().get(0).doClick(0)); // Parar da claude
        Visual.naEdt(() -> b.botoes().get(1).doClick(0)); // Reiniciar da claude
        assertEquals(List.of("ENCERRAR:1001:lab-claude-alfa:alfa (lab-claude-alfa)", "REINICIAR:1001:lab-claude-alfa:alfa (lab-claude-alfa)"), a.cliques);
    }

    @Test
    void semAsAcoesLigadasOsBotoesAparecemDesligadosComAExplicacaoENaoSomem() {
        Acoes a = new Acoes();
        a.liberadas = false;
        BlocoDeProcessos b = bloco(a, null);
        assertEquals(7, rotulos(b).size(), "os botões continuam na tela");
        for (int i = 0; i < 6; i++) {
            int j = i;
            Botao x = Visual.naEdt(() -> b.botoes().get(j));
            assertFalse(Visual.naEdt(x::isEnabled), "desligado sem as ações");
            assertEquals("As ações de escrita estão desligadas nesta versão do app.", Visual.naEdt(() -> x.getToolTipText()));
        }
        Visual.naEdt(() -> b.botoes().get(0).doClick(0));
        assertTrue(a.cliques.isEmpty(), "desligado não clica");
        assertTrue(Visual.naEdt(b::textoDaNota).contains("As ações de escrita estão desligadas nesta versão do app."), Visual.naEdt(b::textoDaNota));
    }

    @Test
    void semPermissaoContinuaLigadoMesmoSemAsAcoesPoisSoMostraUmComando() {
        Acoes a = new Acoes();
        a.liberadas = false;
        Visual.tema(Tema.CLARO);
        BlocoDeProcessos b = Visual.naEdt(() -> new BlocoDeProcessos(a, null));
        InfoServidor info = FixturesDeApi.de("{\"processos\":[{\"pid\":2000,\"nome\":\"node\",\"usuario\":\"labuser\",\"cpu\":3,\"mem\":1000},"
            + "{\"pid\":2001,\"nome\":\"nginx\",\"usuario\":\"www-data\",\"cpu\":2,\"mem\":1000}]}", InfoServidor.class);
        Visual.naEdt(() -> b.atualizar(info, null));
        assertEquals(List.of("Encerrar", "Sem permissão"), rotulos(b));
        assertFalse(Visual.naEdt(() -> b.botoes().get(0).isEnabled()), "o que age fica desligado");
        Botao semPermissao = Visual.naEdt(() -> b.botoes().get(1));
        assertTrue(Visual.naEdt(semPermissao::isEnabled), "o que só mostra o comando do usuário não é desligado pela trava");
        assertFalse(Visual.naEdt(() -> semPermissao.getToolTipText()).contains("desligadas nesta versão"));
        Visual.naEdt(() -> semPermissao.doClick(0));
        assertEquals(1, a.cliques.size(), "o clique abre a janela do comando");
    }

    @Test
    void anotaDizPorQueProcessoComumNaoTemReiniciar() {
        BlocoDeProcessos b = bloco(new Acoes(), null);
        String nota = Visual.naEdt(b::textoDaNota);
        assertTrue(nota.contains("Reiniciar só aparece para containers e serviços"), nota);
        assertTrue(nota.contains("não sabe como religá-lo"), nota);
        assertTrue(nota.contains("nunca força sozinho"), nota);
    }

    @Test
    void processoComumDoMesmoUsuarioSoTemEncerrarEDeOutroUsuarioViraSemPermissao() {
        Acoes a = new Acoes();
        Visual.tema(Tema.CLARO);
        BlocoDeProcessos b = Visual.naEdt(() -> new BlocoDeProcessos(a, null));
        InfoServidor info = FixturesDeApi.de("{\"processos\":[{\"pid\":2000,\"nome\":\"node\",\"usuario\":\"labuser\",\"cpu\":3,\"mem\":1000},"
            + "{\"pid\":2001,\"nome\":\"nginx\",\"usuario\":\"www-data\",\"cpu\":2,\"mem\":1000},{\"pid\":2002,\"nome\":\"sshd\",\"usuario\":\"labuser\",\"cpu\":1,\"mem\":1000}]}", InfoServidor.class);
        Visual.naEdt(() -> b.atualizar(info, null));
        assertEquals(List.of("Encerrar", "Sem permissão", "Protegido"), rotulos(b));
        Botao semPermissao = Visual.naEdt(() -> b.botoes().get(1));
        assertTrue(Visual.naEdt(semPermissao::isEnabled), "o clique abre o aviso com o comando");
        Visual.naEdt(() -> semPermissao.doClick(0));
        assertEquals(List.of("ENCERRAR:2001:null:o próprio servidor (fora de container)"), a.cliques);
        assertTrue(Visual.naEdt(() -> b.botoes().get(2).getToolTipText()).contains("sshd"));
    }

    @Test
    void naAbaDoProjetoSoAparecemOsProcessosDeleEODeOutroProjetoNao() {
        Acoes a = new Acoes();
        BlocoDeProcessos b = bloco(a, "gama");
        assertEquals(List.of("postgres"), Visual.naEdt(() -> b.linhas().stream().map(l -> l.get(0)).toList()));
        assertEquals(List.of("Parar", "Reiniciar"), rotulos(b));
        BlocoDeProcessos vazio = bloco(a, "nenhum-projeto");
        assertEquals(List.of(), Visual.naEdt(() -> vazio.linhas()));
        assertEquals("Processos do projeto", Visual.naEdt(vazio::getName).replace("bloco-", ""));
    }

    @Test
    void esteComputadorMostraAListaDoSistemaComOsNumerosDeleENaoOsDoAgente() {
        Visual.tema(Tema.CLARO);
        BlocoDeProcessos b = Visual.naEdt(() -> new BlocoDeProcessos(new Acoes() {
            @Override
            public Oferta avaliar(ProcessoAlvo alvo) {
                return AvaliadorDeProcessos.avaliar(alvo, new AmbienteDeProcessos(AmbienteDeProcessos.Tipo.COMPUTADOR_MAC_OU_LINUX, "ana", Set.of(), Set.of(4000)));
            }
        }, null));
        Visual.naEdt(() -> {
            b.modoLocal();
            b.atualizarLocal(List.of(new LeitorDeProcessosLocais.Linha(new ProcessoAlvo(5000, "Safari", "ana", null, null), 12.0, null),
                new LeitorDeProcessosLocais.Linha(new ProcessoAlvo(4000, "java", "ana", null, null), 3.0, null),
                new LeitorDeProcessosLocais.Linha(new ProcessoAlvo(300, "WindowServer", "_windowserver", null, null), 1.0, null),
                new LeitorDeProcessosLocais.Linha(new ProcessoAlvo(777, "slack", "ana", null, null), null, null)));
        });
        assertEquals("Safari", Visual.naEdt(() -> b.linhas().get(0).get(0)));
        assertEquals("este computador", Visual.naEdt(() -> b.linhas().get(0).get(4)));
        assertEquals(List.of("Encerrar", "Protegido", "Protegido", "Encerrar"), rotulos(b), "o app e o WindowServer são protegidos; processo comum só Encerrar");
        assertTrue(Visual.naEdt(() -> b.botoes().get(1).getToolTipText()).contains("próprio app"));
    }

    @Test
    void semAcoesNenhumaColunaDeBotoesComoAntes() {
        Visual.tema(Tema.CLARO);
        BlocoDeProcessos b = Visual.naEdt(() -> new BlocoDeProcessos());
        Visual.naEdt(() -> b.atualizar(FixturesDeApi.servidorSintetico(), null));
        assertEquals(0, rotulos(b).size());
        assertEquals("", Visual.naEdt(b::textoDaNota));
    }

    @Test
    void pngsNosDoisTemasComBotoesProtegidoESemPermissao() {
        Acoes a = new Acoes();
        BlocoDeProcessos b = bloco(a, null);
        InfoServidor info = FixturesDeApi.de("{\"processos\":[{\"pid\":2000,\"nome\":\"node\",\"usuario\":\"labuser\",\"cpu\":3,\"mem\":1000},"
            + "{\"pid\":2001,\"nome\":\"nginx\",\"usuario\":\"www-data\",\"cpu\":2,\"mem\":1000},{\"pid\":2002,\"nome\":\"sshd\",\"usuario\":\"labuser\",\"cpu\":1,\"mem\":1000},"
            + "{\"pid\":1001,\"nome\":\"claude\",\"usuario\":\"labuser\",\"cpu\":9,\"mem\":422277120,\"container\":\"lab-claude-alfa\"}]}", InfoServidor.class);
        Visual.naEdt(() -> b.atualizar(info, FixturesDeApi.detalheSintetico()));
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(b));
            Visual.salvar(Visual.renderizar(b, 1060, 330), "processos-botoes-" + (t == Tema.CLARO ? "claro" : "escuro"));
        }
        a.liberadas = false;
        Visual.naEdt(b::refazerBotoes);
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(b));
            Visual.salvar(Visual.renderizar(b, 1060, 360), "processos-botoes-desligados-" + (t == Tema.CLARO ? "claro" : "escuro"));
        }
        Visual.tema(Tema.CLARO);
    }
}
