package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.telas.TelaProjetos;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O conteúdo da janela principal (barra de servidores, navegação e telas) montado sobre um servidor VIVO, sem a janela:
 * estado da conexão em texto e ícone, % da semana, a lista de projetos que chega pelo túnel e a tela de servidores.
 */
@ExtendWith(VerificaEdt.class)
class JanelaPrincipalConteudoTest {

    private CenarioDeUi cenario;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private PainelPrincipal painel;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = new CenarioDeUi();
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, dialogos, new NavegadorGravado(avisos)));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(() -> painel.encerrar());
        cenario.close();
    }

    private TelaProjetos telaDoLab() {
        return Visual.naEdt(() -> painel.navegacao().telaDe(CofreFalso.ID));
    }

    @Test
    void comServidoresNoCofreAbreDireitoNaListaDoPrimeiroENaBarraEleApareceSelecionado() {
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
        assertEquals(CofreFalso.ID, Visual.naEdt(() -> painel.barra().selecionado()));
        assertEquals(List.of(CofreFalso.ID), Visual.naEdt(() -> painel.barra().idsExibidos()));
        assertNotNull(telaDoLab());
    }

    @Test
    void aBarraMostraOEstadoEmTextoEIconeEOPercentualDaSemana() {
        var item = Visual.naEdt(() -> painel.barra().item(CofreFalso.ID));
        Espera.ate(Duration.ofSeconds(15), () -> "estado na barra: " + Visual.naEdt(item::textoDoEstado),
            () -> "Conectado".equals(Visual.naEdt(item::textoDoEstado)));
        assertEquals("lab", Visual.naEdt(item::textoDoNome));
        Espera.ate(Duration.ofSeconds(15), () -> "semana", () -> !Visual.naEdt(item::textoDaSemana).isEmpty());
        assertEquals("92%", Visual.naEdt(item::textoDaSemana), "o mock de limites tem a semana em 92%");
        assertTrue(Visual.naEdt(item::estaSelecionado));
    }

    @Test
    void aListaDeProjetosChegaPeloTunelQuandoATelaAssina() {
        TelaProjetos tela = telaDoLab();
        Visual.naEdt(tela::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "projetos (cartão=" + Visual.naEdt(tela::cartaoAtual) + ")",
            () -> "lista".equals(Visual.naEdt(tela::cartaoAtual)));
        assertEquals(5, Visual.naEdt(tela::nomesExibidos).size());
        assertTrue(Visual.naEdt(tela::textoDoResumo).startsWith("5 projetos"));
        assertTrue(Visual.naEdt(() -> tela.faixa().isVisible()), "o mock está com o crédito em 92% e sessões pausadas");
        assertTrue(cenario.agente.consultasDeProjetos.get() >= 1);
        Visual.naEdt(tela::soltar);
        int consultas = cenario.agente.consultasDeProjetos.get();
        Espera.naoAcontece(Duration.ofMillis(1500), "consulta depois de soltar", () -> cenario.agente.consultasDeProjetos.get() > consultas + 1);
    }

    @Test
    void janelaCompletaNosDoisTemasComServidorVivo() {
        TelaProjetos tela = telaDoLab();
        Visual.naEdt(tela::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "lista", () -> "lista".equals(Visual.naEdt(tela::cartaoAtual)));
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
            BufferedImage img = Visual.renderizar(painel, 1440, 860);
            Visual.salvar(img, "janela-principal-" + t.name().toLowerCase());
        }
    }

    @Test
    void gerenciarServidoresMostraATelaDeServidoresComOCartaoDoLab() {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));
        assertNull(Visual.naEdt(() -> painel.barra().selecionado()));
        assertTrue(Visual.naEdt(() -> painel.barra().gerenciarSelecionado()));
        TelaServidores tela = Visual.naEdt(() -> painel.navegacao().telaDeServidores());
        var cartao = Visual.naEdt(() -> tela.cartao(CofreFalso.ID));
        assertNotNull(cartao);
        Espera.ate(Duration.ofSeconds(15), () -> "conectado", () -> "Conectado".equals(Visual.naEdt(cartao::textoDoEstado)));
        assertEquals("lab", Visual.naEdt(cartao::textoDoNome));
        assertEquals("labuser@127.0.0.1:" + cenario.ssh.porta() + "  ·  agente em 127.0.0.1:" + cenario.agente.porta(),
            Visual.naEdt(cartao::textoDoEndereco));
        assertEquals("mac-de-teste", Visual.naEdt(cartao::textoDoToken));
        assertEquals("2FA DESLIGADO", Visual.naEdt(cartao::textoDoSelo2fa));
        assertTrue(Visual.naEdt(cartao::textoDaImpressao).startsWith("ssh-ed25519  SHA256:"), Visual.naEdt(cartao::textoDaImpressao));
        assertEquals(List.of(Item.ATUALIZAR, Item.CONFIGURAR_2FA, Item.EDITAR, Item.REMOVER), Visual.naEdt(cartao::acoesMostradas),
            "conectado e com token: atualizar e configurar o 2FA, mais Editar e Remover");
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
            Visual.salvar(Visual.renderizar(painel, 1440, 520), "janela-servidores-" + t.name().toLowerCase());
        }
    }

    @Test
    void botaoConfigurar2faDoCartaoAbreODialogoDoServidor() {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        var cartao = Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(CofreFalso.ID));
        Espera.ate(Duration.ofSeconds(15), () -> "botão", () -> Visual.naEdt(() -> cartao.botaoDa(Item.CONFIGURAR_2FA)) != null);
        Visual.naEdt(() -> cartao.botaoDa(Item.CONFIGURAR_2FA).doClick());
        assertTrue(dialogos.ultimo() instanceof br.com.labdesktop.ui.dialogos.Configurar2fa);
    }

    @Test
    void servidorQuePerdeAConexaoApareceReconectandoNaBarraENaTelaComFaixa() {
        TelaProjetos tela = telaDoLab();
        Visual.naEdt(tela::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "lista", () -> "lista".equals(Visual.naEdt(tela::cartaoAtual)));
        cenario.agente.fora = true;
        cenario.lab.conexao().verificarAgora();
        var item = Visual.naEdt(() -> painel.barra().item(CofreFalso.ID));
        Espera.ate(Duration.ofSeconds(25), () -> "estado: " + Visual.naEdt(item::textoDoEstado),
            () -> !"Conectado".equals(Visual.naEdt(item::textoDoEstado)));
        assertEquals(5, Visual.naEdt(tela::nomesExibidos).size(), "a tela mantém a última leitura, nunca fica vazia");
        assertEquals("lista", Visual.naEdt(tela::cartaoAtual));
        Espera.ate(Duration.ofSeconds(10), () -> "faixa de conexão", () -> Visual.naEdt(() -> tela.faixa().avisosMostrados().stream()
            .anyMatch(a -> a instanceof ConteudoDosAvisos.AvisoDeConexao)));
        cenario.agente.fora = false;
        Espera.ate(Duration.ofSeconds(30), () -> "volta", () -> cenario.lab.estado().conexao() == EstadoConexao.CONECTADO);
    }

    @Test
    void semServidoresAParteDeServidoresMostraOConviteParaAdicionarOPrimeiro() throws Exception {
        cenario.gerenciador.remover(CofreFalso.ID).join();
        Espera.ate(Duration.ofSeconds(5), () -> "tela", () -> "servidores".equals(Visual.naEdt(() -> painel.navegacao().atual())));
        assertTrue(Visual.naEdt(() -> painel.navegacao().telaDeServidores().mostraOVazio()));
        assertEquals(List.of(), Visual.naEdt(() -> painel.barra().idsExibidos()));
        Visual.salvar(Visual.renderizar(painel, 1440, 520), "janela-sem-servidores");
        assertFalse(Visual.naEdt(() -> painel.navegacao().telaDeServidores().botaoAdicionar().isEnabled()) == false);
    }

    @Test
    void ositensDoMenuDeContextoDependemDoEstado() {
        ServidorLab lab = cenario.lab;
        List<Item> itens = MenuDoServidor.itensPara(lab.estado());
        assertEquals(List.of(Item.ATUALIZAR, Item.CONFIGURAR_2FA, Item.EDITAR, Item.REMOVER), itens);
        List<String> rotulos = new ArrayList<>();
        Visual.naEdt(() -> {
            for (var c : MenuDoServidor.popup(lab, painel.acoes()).getComponents()) {
                if (c instanceof javax.swing.JMenuItem m) {
                    rotulos.add(m.getText());
                } else if (c instanceof javax.swing.JPopupMenu.Separator) {
                    rotulos.add("---");
                }
            }
        });
        assertEquals(List.of("Atualizar agora", "Configurar 2FA…", "---", "Editar…", "Remover…"), rotulos,
            "Editar e Remover ficam separados das ações do dia a dia");
    }
}
