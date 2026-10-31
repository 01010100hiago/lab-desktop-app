package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.teste.SistemaDoTeste;
import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.local.ConfigLocal;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.SondaFalsa;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.dialogos.DialogoEscolherServidor;
import br.com.labdesktop.ui.dialogos.DialogoPrepararComputador;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Este computador" na janela de verdade (sem JFrame): a primeira pergunta (Este computador ou Outro servidor por SSH), o servidor local na lista
 * com o aviso fixo e o botão Preparar este computador, o diálogo que só confere, criar o laboratório, e o que muda nos cartões e menus. O computador é o
 * simulado (nada de Docker de verdade) e o agente, o falso por HTTP real.
 */
@ExtendWith(VerificaEdt.class)
class EsteComputadorNaJanelaTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    private AgenteDoLab agente;
    private ComputadorSimulado pc;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private PainelPrincipal painel;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        agente = new AgenteDoLab();
        agente.fora = true;
        pc = new ComputadorSimulado();
        pc.execDoAgente = agente.comandosPorSsh();
        pc.antesDe(c -> c.contains("run") && c.contains("-d"), () -> {
            agente.perfil = "monitor";
            agente.fora = false;
        });
        SondaFalsa sonda = new SondaFalsa();
        sonda.programas.add("brew");
        PlataformaLocal plataforma = SistemaDoTeste.plataforma(tmp);
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(),
            new ServicosLocais(pc, plataforma, sonda, ConfigLocal.rapida()));
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
    }

    private void abrirAJanela() {
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, assistente, avisos, dialogos, new NavegadorGravado(avisos),
            ServicosDeArquivos.descartaveis()));
    }

    private ServidorLab escolherEsteComputador() {
        Visual.naEdt(() -> painel.acoes().adicionarServidor());
        DialogoEscolherServidor escolha = dialogos.ultimoComo(DialogoEscolherServidor.class);
        assertEquals("Usar este computador", Visual.naEdt(() -> escolha.botaoEsteComputador().getText()));
        Visual.naEdt(() -> escolha.botaoEsteComputador().doClick());
        Espera.ate(PRAZO, "o diálogo de preparar abriu", () -> dialogos.ultimo() instanceof DialogoPrepararComputador);
        Espera.ate(PRAZO, "o servidor entrou na lista", () -> gerenciador.servidores().size() == 1);
        return gerenciador.servidores().get(0);
    }

    private TelaServidores.CartaoDeServidor cartaoDe(ServidorLab lab) {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        Espera.ate(PRAZO, "cartão", () -> Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id())) != null);
        return Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id()));
    }

    private void fotografarJanela(String nome) {
        Visual.salvar(Visual.renderizar(painel, 1280, 560), nome + "-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
        Visual.salvar(Visual.renderizar(painel, 1280, 560), nome + "-escuro");
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
    }

    @Test
    void aPrimeiraPerguntaTemAsDuasEscolhasEEsteComputadorNaoInstalaNemCriaNada() throws Exception {
        abrirAJanela();
        Visual.naEdt(() -> painel.acoes().adicionarServidor());
        DialogoEscolherServidor escolha = dialogos.ultimoComo(DialogoEscolherServidor.class);
        assertEquals("Usar este computador", Visual.naEdt(() -> escolha.botaoEsteComputador().getText()));
        assertEquals("Conectar por SSH", Visual.naEdt(() -> escolha.botaoOutroServidor().getText()));
        assertTrue(Visual.naEdt(() -> escolha.botaoEsteComputador().isEnabled()));
        assertTrue(gerenciador.servidores().isEmpty(), "escolher a pergunta não cadastra nada");
        for (Tema t : List.of(Tema.CLARO, Tema.ESCURO)) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(escolha.raiz()));
            Visual.salvar(Visual.renderizar(escolha.raiz(), 540, Visual.alturaPreferida(escolha.raiz(), 540)),
                "dialogo-escolher-servidor-" + (t == Tema.CLARO ? "claro" : "escuro"));
        }
        Visual.tema(Tema.CLARO);

        Visual.naEdt(() -> escolha.botaoOutroServidor().doClick());
        assertTrue(dialogos.ultimo() instanceof br.com.labdesktop.ui.dialogos.DialogoAdicionarServidor, "Outro servidor abre o assistente de SSH de sempre");
        assertTrue(pc.comandos.isEmpty());
    }

    @Test
    void escolherEsteComputadorCadastraOServidorLocalSemChaveEAbreOPrepararQueSoConfere() throws Exception {
        abrirAJanela();
        ServidorLab lab = escolherEsteComputador();

        assertTrue(lab.ehLocal());
        assertTrue(cofre.chaves().isEmpty(), "nenhuma chave SSH nasce");
        DialogoPrepararComputador d = dialogos.ultimoComo(DialogoPrepararComputador.class);
        assertEquals("Preparar este computador", d.titulo());
        Visual.naEdt(d::iniciarConferencia);
        Espera.ate(PRAZO, () -> "página=" + Visual.naEdt(d::paginaAtual), () -> "resultado".equals(Visual.naEdt(d::paginaAtual)));
        assertTrue(pc.escritas().isEmpty(), "só leituras até o clique: " + pc.escritas());
        assertFalse(Visual.naEdt(() -> d.botaoCriar().getText()).isEmpty());
        assertTrue(Visual.naEdt(() -> d.campoDaPasta().getText()).endsWith(Path.of("LabLocal", "projects").toString()), Visual.naEdt(() -> d.campoDaPasta().getText()));
        assertEquals("7070", Visual.naEdt(() -> d.campoDaPorta().getText()), "a porta padrão do local não é a 7000 (AirPlay)");
    }

    @Test
    void ocartaoDoServidorLocalDizSemSshComOAvisoFixoEOBotaoPrepararECriarDeVerdadeLeva_a_Conectado() throws Exception {
        abrirAJanela();
        ServidorLab lab = escolherEsteComputador();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.LOCAL_NAO_CRIADO);
        Espera.ate(PRAZO, "o cartão se atualizou", () -> Visual.naEdt(cartao::textoDoEstado).equals("Falta criar o laboratório"));

        assertTrue(Visual.naEdt(cartao::textoDoEndereco).startsWith("Este computador (sem SSH), agente em 127.0.0.1:"), Visual.naEdt(cartao::textoDoEndereco));
        assertTrue(Visual.naEdt(cartao::textoDaImpressao).contains("não usa SSH"), Visual.naEdt(cartao::textoDaImpressao));
        String aviso = Visual.naEdt(cartao::textoDoAvisoDoAgente);
        assertTrue(aviso.contains("O laboratório local ainda não foi criado"), aviso);
        assertFalse(aviso.toLowerCase().contains("ssh"), "nada de SSH na fala do local: " + aviso);
        assertEquals(List.of(Item.ATUALIZAR, Item.CONECTAR, Item.PREPARAR_COMPUTADOR, Item.REMOVER), Visual.naEdt(cartao::acoesMostradas),
            "sem Editar (não há endereço), sem token/2FA (não há agente), com Preparar este computador");
        assertEquals("Preparar este computador…", Visual.naEdt(() -> cartao.botaoDa(Item.PREPARAR_COMPUTADOR).getText()));
        fotografarJanela("janela-servidores-este-computador-a-preparar");

        // o botão do cartão abre o diálogo (o mesmo de "Instalar agente" no remoto, com o nome certo)
        int antes = dialogos.mostrados.size();
        Visual.naEdt(() -> cartao.botaoDa(Item.PREPARAR_COMPUTADOR).doClick());
        Espera.ate(PRAZO, "abriu outro", () -> dialogos.mostrados.size() > antes);
        DialogoPrepararComputador d = dialogos.ultimoComo(DialogoPrepararComputador.class);
        Visual.naEdt(d::iniciarConferencia);
        Espera.ate(PRAZO, () -> "página=" + Visual.naEdt(d::paginaAtual), () -> "resultado".equals(Visual.naEdt(d::paginaAtual)));
        // o agente de teste escuta numa porta qualquer: o usuário a escolheria nas opções avançadas
        Visual.naEdt(() -> d.campoDaPorta().setText(String.valueOf(agente.porta())));
        Visual.naEdt(() -> d.botaoCriar().doClick());
        Espera.ate(PRAZO, () -> "fim=" + Visual.naEdt(d::situacaoFinal), () -> "Laboratório local criado".equals(Visual.naEdt(d::situacaoFinal)));

        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        Espera.ate(PRAZO, "o cartão mostra conectado", () -> Visual.naEdt(cartao::textoDoEstado).equals("Conectado"));
        assertTrue(Visual.naEdt(cartao::acoesMostradas).contains(Item.REMOVER_LABORATORIO));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.ATUALIZAR_AGENTE), "o local não tem Atualizar agente remoto");
        assertEquals("", Visual.naEdt(cartao::textoDoAvisoDoAgente));
        assertTrue(Visual.naEdt(cartao::textoDoToken).startsWith("mac") || !Visual.naEdt(cartao::textoDoToken).equals("ainda não registrado"));
        fotografarJanela("janela-servidores-este-computador-no-ar");
    }

    @Test
    void aSegundaVezAOpcaoEsteComputadorJaVemMarcadaComoNaLista() throws Exception {
        abrirAJanela();
        escolherEsteComputador();
        Visual.naEdt(() -> painel.acoes().adicionarServidor());
        DialogoEscolherServidor escolha = dialogos.ultimoComo(DialogoEscolherServidor.class);
        assertEquals("Já está na lista", Visual.naEdt(() -> escolha.botaoEsteComputador().getText()));
        assertFalse(Visual.naEdt(() -> escolha.botaoEsteComputador().isEnabled()), "só existe um Este computador");
    }

    @Test
    void removerOServidorLocalNaoMencionaChaveENaoRemoveOLaboratorio() throws Exception {
        abrirAJanela();
        ServidorLab lab = escolherEsteComputador();
        Visual.naEdt(() -> painel.acoes().remover(lab));
        var d = dialogos.ultimoComo(br.com.labdesktop.ui.dialogos.DialogoRemoverServidor.class);
        assertFalse(Visual.naEdt(() -> d.caixaDeApagarAChave().isVisible()), "não há chave para apagar");
        assertTrue(Visual.naEdt(() -> d.caixaDeRevogar().getText()).contains("token"), Visual.naEdt(() -> d.caixaDeRevogar().getText()));
        for (Tema t : List.of(Tema.CLARO, Tema.ESCURO)) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
            Visual.salvar(Visual.renderizar(d.raiz(), 520, Visual.alturaPreferida(d.raiz(), 520)), "dialogo-remover-este-computador-" + (t == Tema.CLARO ? "claro" : "escuro"));
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void editarEmEsteComputadorExplicaOndeFicamAPortaEAPastaEMenuDeContextoTemOsItensDoLocal() throws Exception {
        abrirAJanela();
        ServidorLab lab = escolherEsteComputador();
        Visual.naEdt(() -> painel.acoes().editar(lab));
        assertTrue(avisos.todos.stream().anyMatch(a -> a.texto().contains("Preparar este computador")), avisos.todos.toString());
        List<String> rotulos = Visual.naEdt(() -> MenuDoServidor.itensPara(lab.estado()).stream().map(Item::rotulo).toList());
        assertTrue(rotulos.contains("Preparar este computador…"), rotulos.toString());
        assertFalse(rotulos.contains("Editar…"));
        assertFalse(rotulos.contains("Instalar agente…"));
    }
}
