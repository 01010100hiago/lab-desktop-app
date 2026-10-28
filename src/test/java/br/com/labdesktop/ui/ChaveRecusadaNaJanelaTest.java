package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.teste.ShellDeTeste;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.dialogos.DialogoEditarServidor;
import br.com.labdesktop.ui.dialogos.DialogoInstalarChave;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A chave recusada pela JANELA, de ponta a ponta: um servidor já cadastrado que o servidor SSH embutido recusa ("Chave recusada") mostra o botão "Instalar a chave com a senha…"
 * no cartão, na página do servidor e no Editar; o clique abre o diálogo; o usuário digita a senha, o app instala a chave, prova e o servidor volta a "Conectado". Nenhum diálogo
 * abre sozinho e nenhuma senha sai antes do clique. PNGs nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class ChaveRecusadaNaJanelaTest {

    private static final char[] SENHA_MESTRA = "uma-senha-mestra-de-teste".toCharArray();
    private static final String SENHA_DO_SERVIDOR = "senha-do-servidor-Zk4!";
    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final String BOTAO = "Instalar a chave com a senha…";

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private ShellDeTeste shell;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private PainelPrincipal painel;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();

    @BeforeEach
    void montar() throws Exception {
        assumeTrue(ShellDeTeste.temShell(), "precisa de /bin/sh");
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        ssh.aceitarSenha("labuser", SENHA_DO_SERVIDOR);
        agente = new AgenteDoLab();
        shell = new ShellDeTeste(Files.createDirectories(tmp.resolve("home")), ssh).outrosComandos(agente.comandosPorSsh());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA_MESTRA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        if (painel != null) {
            Visual.naEdt(() -> painel.encerrar());
        }
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        ssh.close();
        Visual.tema(Tema.CLARO);
    }

    private ServidorLab servidorRecusado() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        ServidorLab lab = assistente.cadastrar(new Dados("lab", "127.0.0.1", ssh.porta(), "labuser", agente.porta()), chave.id(), pin);
        lab.iniciar();
        Espera.ate(PRAZO, () -> "chave recusada (" + lab.estado().conexao() + ")",
            () -> lab.estado().conexao() == EstadoConexao.ERRO_AUTENTICACAO && lab.estado().proximaTentativaEm() == null);
        return lab;
    }

    private void abrirAJanela() {
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, assistente, avisos, dialogos, new NavegadorGravado(avisos)));
    }

    private TelaServidores.CartaoDeServidor cartaoDe(ServidorLab lab) {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        Espera.ate(PRAZO, "cartão", () -> Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id())) != null);
        return Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id()));
    }

    private static List<AbstractButton> botoesVisiveis(java.awt.Component c, List<AbstractButton> acc) {
        if (c instanceof AbstractButton b) {
            acc.add(b);
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                if (f.isVisible()) {
                    botoesVisiveis(f, acc);
                }
            }
        }
        return acc;
    }

    private static void doisTemas(java.awt.Component c, int largura, int altura, String nome) {
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(c));
        Visual.salvar(Visual.renderizar((javax.swing.JComponent) c, largura, altura), nome + "-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(c));
        Visual.salvar(Visual.renderizar((javax.swing.JComponent) c, largura, altura), nome + "-escuro");
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(c));
    }

    @Test
    void oCartaoDoServidorRecusadoTemOBotaoEmDestaqueENadaAbreSozinho() throws Exception {
        ServidorLab lab = servidorRecusado();
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "estado no cartão", () -> "Chave recusada".equals(Visual.naEdt(cartao::textoDoEstado)));

        List<Item> acoes = Visual.naEdt(cartao::acoesMostradas);
        assertTrue(acoes.indexOf(Item.INSTALAR_CHAVE) >= 0 && acoes.indexOf(Item.INSTALAR_CHAVE) < acoes.indexOf(Item.EDITAR),
            "com a chave recusada o botão vem junto das ações do dia a dia: " + acoes);
        assertEquals(BOTAO, Visual.naEdt(() -> cartao.botaoDa(Item.INSTALAR_CHAVE).getText()));
        assertEquals(Botao.Variante.PRIMARIO, Visual.naEdt(() -> cartao.botaoDa(Item.INSTALAR_CHAVE).variante()));
        Espera.naoAcontece(Duration.ofMillis(800), "um diálogo abrir sozinho", () -> !dialogos.mostrados.isEmpty());
        assertEquals(List.of(), ssh.senhasVistas, "nenhuma senha sai antes do clique");
        doisTemas(painel, 1280, 560, "janela-servidores-chave-recusada");
    }

    @Test
    void oBotaoApareceNaPaginaDoServidorNoAvisoESoOCliqueAbreODialogo() throws Exception {
        ServidorLab lab = servidorRecusado();
        abrirAJanela();
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        TelaServidor tela = Visual.naEdt(() -> painel.navegacao().telaServidorDe(lab.id()));
        Visual.naEdt(() -> tela.resumo().aoMudarEstado(lab.estado()));
        Visual.renderizar(tela, 1100, 700);
        Espera.ate(PRAZO, "o botão aparece na página", () -> Visual.naEdt(() -> botoesVisiveis(tela, new ArrayList<>()).stream().anyMatch(b -> BOTAO.equals(b.getText()))));
        assertTrue(Visual.naEdt(() -> tela.resumo().situacao().oferecerInstalarChave()));
        doisTemas(tela, 1100, 700, "servidor-resumo-chave-recusada");
        assertTrue(dialogos.mostrados.isEmpty());

        AbstractButton botao = Visual.naEdt(() -> botoesVisiveis(tela, new ArrayList<>()).stream().filter(b -> BOTAO.equals(b.getText())).findFirst().orElseThrow());
        Visual.naEdt(() -> botao.doClick());
        Espera.ate(PRAZO, "o diálogo abriu", () -> !dialogos.mostrados.isEmpty());
        assertEquals("Instalar a chave com a senha", dialogos.ultimoComo(DialogoInstalarChave.class).titulo());
        assertEquals(List.of(), ssh.senhasVistas, "abrir o diálogo não manda senha nenhuma");
    }

    @Test
    void naoHaBotaoDeChaveNoServidorQueConecta() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        ssh.aceitar(cofre.keyPairDoServidor(assistente.cadastrar(new Dados("lab", "127.0.0.1", ssh.porta(), "labuser", agente.porta()), chave.id(),
            assistente.sondar("127.0.0.1", ssh.porta()).pin()).id()).getPublic());
        ServidorLab lab = gerenciador.servidores().get(0);
        lab.iniciar();
        Espera.ate(PRAZO, "conectado", () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "ações", () -> Visual.naEdt(cartao::acoesMostradas).contains(Item.REMOVER));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.INSTALAR_CHAVE), "o cartão só destaca o botão quando a chave é recusada");
        assertTrue(Visual.naEdt(() -> MenuDoServidor.itensPara(lab.estado())).contains(Item.INSTALAR_CHAVE), "mas o menu Servidor e o Editar o oferecem sempre");
    }

    @Test
    void doClicarEmInstalarAteOServidorVoltarAConectadoPelaJanela() throws Exception {
        ServidorLab lab = servidorRecusado();
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "botão", () -> Visual.naEdt(() -> cartao.botaoDa(Item.INSTALAR_CHAVE)) != null);
        Visual.naEdt(() -> cartao.botaoDa(Item.INSTALAR_CHAVE).doClick());
        Espera.ate(PRAZO, "o diálogo abriu", () -> !dialogos.mostrados.isEmpty());
        DialogoInstalarChave d = dialogos.ultimoComo(DialogoInstalarChave.class);

        Visual.naEdt(() -> d.campoDaSenha().setText(SENHA_DO_SERVIDOR));
        Visual.naEdt(() -> d.botaoInstalar().doClick());
        Espera.ate(PRAZO, () -> "pronto (erro='" + Visual.naEdt(d::textoDoErro) + "')", () -> Visual.naEdt(d::pronto));

        assertEquals(0, Visual.naEdt(() -> d.campoDaSenha().getPassword().length), "a senha saiu do campo");
        assertEquals(List.of(SENHA_DO_SERVIDOR), ssh.senhasVistas, "uma senha só");
        assertTrue(Visual.naEdt(d::textoDoFim).contains("Para desligar o login por senha, use Servidor > Segurança."), Visual.naEdt(d::textoDoFim));
        Espera.ate(PRAZO, () -> "volta a conectar (" + lab.estado().conexao() + ")", () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        Espera.ate(PRAZO, "o cartão diz Conectado", () -> "Conectado".equals(Visual.naEdt(cartao::textoDoEstado)));
        Espera.ate(PRAZO, "o botão sai do cartão", () -> Visual.naEdt(() -> cartao.botaoDa(Item.INSTALAR_CHAVE)) == null);
        assertEquals(1, ssh.senhasVistas.size(), "a reconexão é só por chave");
        assertFalse(avisos.todos.isEmpty());
    }

    @Test
    void oEditarMostraAAcessoComAImpressaoDaChaveECopiaAChavePublica() throws Exception {
        ServidorLab lab = servidorRecusado();
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "botão", () -> Visual.naEdt(() -> cartao.botaoDa(Item.EDITAR)) != null);
        Visual.naEdt(() -> cartao.botaoDa(Item.EDITAR).doClick());
        DialogoEditarServidor d = dialogos.ultimoComo(DialogoEditarServidor.class);
        InfoChave chave = assistente.acessoDo(lab).orElseThrow().chave();

        assertTrue(Visual.naEdt(d::mostraAAcesso));
        assertNotNull(Visual.naEdt(d::botaoCopiarChave));
        assertTrue(Visual.naEdt(() -> d.botaoInstalarChave().isEnabled()), "sem mudança pendente");
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
        assertEquals(DialogoEditarServidor.DICA_DO_SALVAR, Visual.naEdt(d::textoDaDicaDoSalvar));
        String texto = Visual.naEdt(() -> textosDe(d.raiz()));
        assertTrue(texto.contains(chave.impressao()), "a impressão digital da chave do app aparece: " + texto);
        doisTemas(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560), "dialogo-editar-servidor-acesso");

        Visual.naEdt(() -> d.campoDoNome().setText("lab novo")); // há mudança pendente: a chave só se instala depois de salvar
        assertFalse(Visual.naEdt(() -> d.botaoInstalarChave().isEnabled()));
        assertTrue(Visual.naEdt(d::mostraADicaDeSalvarAntes));
        assertEquals("", Visual.naEdt(d::textoDaDicaDoSalvar));
        Visual.naEdt(() -> d.campoDoNome().setText("lab"));
        assertTrue(Visual.naEdt(() -> d.botaoInstalarChave().isEnabled()));
        assertFalse(Visual.naEdt(d::mostraADicaDeSalvarAntes));

        int antes = dialogos.mostrados.size();
        Visual.naEdt(() -> d.botaoInstalarChave().doClick());
        assertEquals(antes + 1, dialogos.mostrados.size(), "o Editar fecha e abre o conserto");
        dialogos.ultimoComo(DialogoInstalarChave.class);
    }

    private static String textosDe(java.awt.Component c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof javax.swing.text.JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                if (f.isVisible()) {
                    sb.append(textosDe(f));
                }
            }
        }
        return sb.toString();
    }
}
