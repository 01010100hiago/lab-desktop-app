package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ProtecaoDoServidor;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarServidor;
import br.com.labdesktop.ui.dialogos.DialogoEditarServidor;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
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
 * Servidor protegido na janela de verdade (sem JFrame): o selo "Protegido" no cartão e no cabeçalho da página, o aviso fixo sem o botão Instalar
 * agente (com a frase de como mudar), as ações do app recusando mesmo se uma tela as chamar, o aviso único da marca automática e os PNGs claro e escuro.
 */
@ExtendWith(VerificaEdt.class)
class ServidorProtegidoNaJanelaTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private PainelPrincipal painel;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        agente.fora = true;
        agente.diagnostico = "ausente";
        ssh.comandoRemoto(agente.comandosPorSsh());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
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
    }

    private ServidorLab servidor(String nome, boolean protegido) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
        cofre.adicionarChave(chave);
        ssh.aceitar(chave.paraKeyPair().getPublic());
        TokenAgente token = TokenAgente.gerar();
        agente.tokens.put(token.hashSha256Hex(), "mac-" + nome);
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), nome, "127.0.0.1", ssh.porta(), "labuser", chave.id(), agente.porta(),
            List.of(ssh.pin()), Transporte.DIRETO, "mac-" + nome, Estado2fa.DESLIGADO, protegido, false);
        cofre.adicionarServidor(cfg, token);
        ServidorLab lab = gerenciador.adicionar(cfg.id());
        lab.iniciar();
        return lab;
    }

    private void abrirAJanela() {
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, null, avisos, dialogos, new NavegadorGravado(avisos)));
    }

    private TelaServidores.CartaoDeServidor cartaoDe(ServidorLab lab) {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        Espera.ate(PRAZO, "cartão", () -> Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id())) != null);
        return Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id()));
    }

    private static List<AbstractButton> botoes(java.awt.Component c, List<AbstractButton> acc) {
        if (c instanceof AbstractButton b && b.isVisible()) {
            acc.add(b);
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                if (f.isVisible()) {
                    botoes(f, acc);
                }
            }
        }
        return acc;
    }

    private static String textosDe(java.awt.Component c) {
        if (!c.isVisible()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (c instanceof javax.swing.text.JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof AbstractButton b && b.getText() != null) {
            sb.append(b.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                sb.append(textosDe(f));
            }
        }
        return sb.toString();
    }

    private static java.awt.Component porNome(java.awt.Component c, String nome) {
        if (nome.equals(c.getName())) {
            return c;
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component filho : k.getComponents()) {
                java.awt.Component achou = porNome(filho, nome);
                if (achou != null) {
                    return achou;
                }
            }
        }
        return null;
    }

    private void renderizarNosDoisTemas(java.util.function.Supplier<javax.swing.JComponent> componente, int largura, int altura, String nome) {
        if (nome.equals("janela-servidores-protegido-com-agente")) { // galeria do README: o selo "Protegido" no cartão, na janela inteira
            // o cartão é o do vps-1 da lista de servidores da galeria (o terceiro): o mesmo endereço e a mesma impressão digital de exemplo
            ExemplosDaGaleria.definirDeslocamento(1, 2);
            try {
                Visual.galeriaNosDoisTemas(componente.get(), largura, altura, "servidor-protegido", "Lab Desktop", MolduraDeJanela.Tipo.JANELA);
            } finally {
                ExemplosDaGaleria.definirDeslocamento(0, 0);
            }
        }
        Visual.salvar(Visual.renderizar(componente.get(), largura, altura), nome + "-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(componente.get()));
        Visual.salvar(Visual.renderizar(componente.get(), largura, altura), nome + "-escuro");
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(componente.get()));
    }

    // ---------------------------------------------------------------------------------------------
    // O cartão
    // ---------------------------------------------------------------------------------------------

    @Test
    void oCartaoDeUmServidorProtegidoSemAgenteTemSeloAvisoSemBotaoESemMenuDeInstalar() throws Exception {
        ServidorLab lab = servidor("vps-1", true);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, () -> "aviso no cartão (estado=" + lab.estado() + ")", () -> Visual.naEdt(cartao::textoDoAvisoDoAgente).contains("servidor protegido"));

        assertTrue(Visual.naEdt(cartao::mostraOSeloProtegido));
        assertEquals("PROTEGIDO", Visual.naEdt(() -> cartao.seloProtegido().texto()));
        assertEquals("Agente não instalado, servidor protegido: " + ProtecaoDoServidor.TEXTO_DO_AVISO_SEM_AGENTE, Visual.naEdt(cartao::textoDoAvisoDoAgente));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.INSTALAR_AGENTE));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.ATUALIZAR_AGENTE));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.REMOVER_AGENTE));
        assertTrue(Visual.naEdt(cartao::acoesMostradas).contains(Item.EDITAR), "o caminho para desmarcar está à mão");
        assertEquals(List.of(Item.ATUALIZAR, Item.CONECTAR, Item.EDITAR, Item.REMOVER), Visual.naEdt(cartao::acoesMostradas));
        assertFalse(Visual.naEdt(() -> botoes(cartao, new ArrayList<>()).stream().anyMatch(b -> b.getText() != null && b.getText().startsWith("Instalar agente"))));
        Visual.naEdt(() -> MenuDoServidor.popup(lab, painel.acoes())); // monta o menu de contexto sem estourar
        assertFalse(Visual.naEdt(() -> MenuDoServidor.itensPara(lab.estado())).contains(Item.INSTALAR_AGENTE));

        Thread.sleep(1200);
        assertTrue(dialogos.mostrados.isEmpty(), "nada abre sozinho");
        for (String c : ssh.comandos) {
            assertTrue(ComandosRemotos.ehLeitura(c), "só leituras: " + c);
        }
        renderizarNosDoisTemas(() -> painel, 1280, 560, "janela-servidores-protegido-sem-agente");
    }

    @Test
    void oCartaoDeServidorComumNaoTemSeloEOBotaoDeSempre() throws Exception {
        ServidorLab lab = servidor("vps-1", false);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, () -> "aviso (estado=" + lab.estado() + ")", () -> Visual.naEdt(cartao::textoDoAvisoDoAgente).contains("Agente não instalado"));
        assertFalse(Visual.naEdt(cartao::mostraOSeloProtegido));
        assertTrue(Visual.naEdt(cartao::acoesMostradas).contains(Item.INSTALAR_AGENTE));
    }

    @Test
    void ligarAMarcaPeloGerenciadorTiraOBotaoDoCartaoNaHoraEDesligarOTrazDeVolta() throws Exception {
        ServidorLab lab = servidor("vps-1", false);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, () -> "botão (estado=" + lab.estado() + ")", () -> Visual.naEdt(cartao::acoesMostradas).contains(Item.INSTALAR_AGENTE));

        br.com.labdesktop.teste.EdicaoDaProtecao.definir(gerenciador, lab.id(), true);
        Espera.ate(PRAZO, "o botão saiu e o selo entrou", () -> !Visual.naEdt(cartao::acoesMostradas).contains(Item.INSTALAR_AGENTE) && Visual.naEdt(cartao::mostraOSeloProtegido));

        br.com.labdesktop.teste.EdicaoDaProtecao.definir(gerenciador, lab.id(), false);
        Espera.ate(PRAZO, "o botão voltou e o selo saiu", () -> Visual.naEdt(cartao::acoesMostradas).contains(Item.INSTALAR_AGENTE) && !Visual.naEdt(cartao::mostraOSeloProtegido));
    }

    @Test
    void oCartaoDeUmMonitorQueOAppInstalouNaoOferecePAtualizarNemRemoverQuandoProtegido() throws Exception {
        agente.fora = false;
        agente.perfil = "monitor";
        ServidorLab lab = servidor("vps-1", true);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "selo", () -> Visual.naEdt(cartao::mostraOSeloProtegido));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.ATUALIZAR_AGENTE));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.REMOVER_AGENTE));
        assertTrue(Visual.naEdt(cartao::acoesMostradas).contains(Item.EDITAR));
        assertEquals("", Visual.naEdt(cartao::textoDoAvisoDoAgente), "com o agente respondendo não há aviso de falta");
        renderizarNosDoisTemas(() -> painel, 1280, 560, "janela-servidores-protegido-com-agente");
    }

    // ---------------------------------------------------------------------------------------------
    // A página do servidor
    // ---------------------------------------------------------------------------------------------

    @Test
    void aPaginaDoServidorProtegidoTemSeloELinhaEExplicaNoLugarDoBotaoESemRolagem() throws Exception {
        ServidorLab lab = servidor("vps-1", true);
        abrirAJanela();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        TelaServidor tela = Visual.naEdt(() -> painel.navegacao().telaServidorDe(lab.id()));
        Visual.naEdt(() -> tela.resumo().aoMudarEstado(lab.estado()));
        Visual.renderizar(tela, 1100, 700);
        Espera.ate(PRAZO, "o aviso aparece no Resumo", () -> Visual.naEdt(() -> textosDe(tela)).contains("Servidor protegido, sem agente"));

        String texto = Visual.naEdt(() -> textosDe(tela));
        assertFalse(Visual.naEdt(() -> botoes(tela, new ArrayList<>()).stream().anyMatch(b -> "Instalar agente".equals(b.getText()))), "sem o botão Instalar agente");
        java.awt.Component selo = Visual.naEdt(() -> porNome(tela, "selo-protegido"));
        assertTrue(selo != null && Visual.naEdt(selo::isVisible), "o cabeçalho da página tem o selo");
        // sem agente, a linha da página É o aviso (no lugar do botão): título curto com "protegido" e "Editar servidor", e a explicação inteira na dica
        assertTrue(texto.contains("Servidor protegido, sem agente: o app não instala nada aqui. Para mudar, abra Editar servidor."), texto);
        java.awt.Component faixa = Visual.naEdt(() -> porNome(tela, "faixa-de-dados"));
        assertEquals(ProtecaoDoServidor.TEXTO_DO_AVISO_SEM_AGENTE, Visual.naEdt(() -> ((javax.swing.JComponent) faixa).getToolTipText()));
        assertFalse(Visual.naEdt(() -> porNome(tela, "linha-da-protecao").isVisible()), "e o cabeçalho não repete a frase");
        assertEquals(ProtecaoDoServidor.TEXTO_DO_AVISO_SEM_AGENTE, Visual.naEdt(() -> tela.resumo().situacao().texto()));
        assertFalse(Visual.naEdt(() -> tela.resumo().situacao().oferecerInstalar()));

        // em 1100 x 700 o Resumo deste estado continua cabendo sem rolagem, mesmo com o selo, a linha e a explicação à vista
        int largura = 1100 - br.com.labdesktop.ui.BarraServidores.LARGURA;
        assertTrue(Visual.alturaPreferida(tela, largura) <= 700 - 28, "pede " + Visual.alturaPreferida(tela, largura) + " px");
        renderizarNosDoisTemas(() -> tela, 1100, 700, "servidor-protegido-sem-agente-pagina");
    }

    @Test
    void aPaginaDeServidorComumNaoTemSeloNemLinha() throws Exception {
        ServidorLab lab = servidor("vps-1", false);
        abrirAJanela();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        TelaServidor tela = Visual.naEdt(() -> painel.navegacao().telaServidorDe(lab.id()));
        Visual.naEdt(() -> tela.resumo().aoMudarEstado(lab.estado()));
        Visual.renderizar(tela, 1100, 700);
        assertFalse(Visual.naEdt(() -> porNome(tela, "selo-protegido").isVisible()));
        assertFalse(Visual.naEdt(() -> porNome(tela, "linha-da-protecao").isVisible()));
        assertTrue(Visual.naEdt(() -> botoes(tela, new ArrayList<>()).stream().anyMatch(b -> "Instalar agente".equals(b.getText()))));
    }

    // ---------------------------------------------------------------------------------------------
    // As ações recusam mesmo se uma tela as chamar
    // ---------------------------------------------------------------------------------------------

    @Test
    void asAcoesDaInterfaceRecusamEmServidorProtegidoSemAbrirDialogoNenhum() throws Exception {
        ServidorLab lab = servidor("vps-1", true);
        abrirAJanela();
        AcoesDaInterface acoes = painel.acoes();
        List<Runnable> chamadas = List.of(
            () -> acoes.instalarAgente(lab),
            () -> acoes.instalarAgente(lab, () -> { }, () -> { }),
            () -> acoes.atualizarAgente(lab),
            () -> acoes.removerAgente(lab),
            () -> acoes.executar(lab, ConteudoDosAvisos.Acao.INSTALAR_AGENTE),
            () -> MenuDoServidor.executar(Item.INSTALAR_AGENTE, lab, acoes),
            () -> MenuDoServidor.executar(Item.ATUALIZAR_AGENTE, lab, acoes),
            () -> MenuDoServidor.executar(Item.REMOVER_AGENTE, lab, acoes),
            () -> acoes.instalarGit(lab, () -> { }),
            () -> acoes.instalarChaveComSenha(lab),
            () -> MenuDoServidor.executar(Item.INSTALAR_CHAVE, lab, acoes),
            () -> acoes.executar(lab, ConteudoDosAvisos.Acao.INSTALAR_CHAVE),
            () -> acoes.desligarSenha(lab),
            () -> acoes.desligarSenha(lab, () -> { }));
        for (Runnable chamada : chamadas) {
            // a recusa tem de sair DENTRO da própria chamada (na mesma passada da EDT): é a primeira guarda, que não espera o pacote do agente carregar em segundo plano
            Visual.naEdt(() -> {
                long antes = avisos.todos.stream().filter(a -> a.texto().equals(ProtecaoDoServidor.RECUSA)).count();
                chamada.run();
                long depois = avisos.todos.stream().filter(a -> a.texto().equals(ProtecaoDoServidor.RECUSA)).count();
                assertEquals(antes + 1, depois, "a recusa não saiu na hora");
            });
        }
        Thread.sleep(500);
        assertTrue(dialogos.mostrados.isEmpty(), "nenhum diálogo de instalar abriu: " + dialogos.mostrados);
        long recusas = avisos.todos.stream().filter(a -> a.tipo().equals("atencao") && a.texto().equals(ProtecaoDoServidor.RECUSA)).count();
        assertEquals(chamadas.size(), recusas, "cada tentativa foi recusada com a frase certa: " + avisos.todos);
        for (String c : ssh.comandos) {
            assertTrue(ComandosRemotos.ehLeitura(c), "só leituras: " + c);
        }
    }

    @Test
    void seOServidorForProtegidoDepoisDoCliqueEAntesDeAConfirmacaoAbrirNadaAbreEODonoLeAFrase() throws Exception {
        ServidorLab lab = servidor("vps-1", false);
        abrirAJanela();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
        java.util.concurrent.CountDownLatch edtParada = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch soltar = new java.util.concurrent.CountDownLatch(1);
        // a EDT roda o clique (a primeira guarda passa: ainda não é protegido), carrega o pacote em segundo plano e para; o resto fica na fila atrás do bloqueio
        javax.swing.SwingUtilities.invokeLater(() -> painel.acoes().instalarAgente(lab));
        javax.swing.SwingUtilities.invokeLater(() -> {
            edtParada.countDown();
            try {
                soltar.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtParada.await(PRAZO.toSeconds(), java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(800); // o pacote carrega e a abertura da confirmação entra na fila, atrás do bloqueio
        cofre.alterarServidor(lab.id(), c -> c.comProtegido(true)); // o usuário protegeu entre o clique e a abertura
        soltar.countDown();

        Espera.ate(PRAZO, () -> "recusa (avisos=" + avisos.todos + ")", () -> avisos.disse("atencao", ProtecaoDoServidor.RECUSA));
        assertTrue(dialogos.mostrados.isEmpty(), "a confirmação não abriu");
    }

    @Test
    void semAMarcaOClicarEmInstalarAbreAConfirmacaoComoSempre() throws Exception {
        ServidorLab lab = servidor("vps-1", false);
        abrirAJanela();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
        Visual.naEdt(() -> painel.acoes().instalarAgente(lab));
        Espera.ate(PRAZO, "a confirmação abriu", () -> !dialogos.mostrados.isEmpty());
        assertTrue(avisos.todos.stream().noneMatch(a -> a.texto().equals(ProtecaoDoServidor.RECUSA)));
    }

    // ---------------------------------------------------------------------------------------------
    // A marca automática: o aviso sai uma vez, na janela
    // ---------------------------------------------------------------------------------------------

    @Test
    void oAgenteDoLabDeForaMarcaOServidorEODonoLeUmAvisoSoUmaVez() throws Exception {
        agente.fora = false;
        agente.perfil = "lab";
        ServidorLab lab = servidor("lab", false);
        abrirAJanela();
        Espera.ate(PRAZO, () -> "aviso (avisos=" + avisos.todos + ")", () -> avisos.disse("info", "instalado fora do app"));
        assertEquals("lab: Este servidor já tem o agente do lab, instalado fora do app. Marquei como protegido: o app não vai instalar nada aqui. "
            + "Para desfazer, abra Editar servidor.", avisos.ultimo().texto());
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "selo no cartão", () -> Visual.naEdt(cartao::mostraOSeloProtegido));
        for (int i = 0; i < 3; i++) {
            lab.consultarVersao();
        }
        Thread.sleep(400);
        assertEquals(1, avisos.todos.stream().filter(a -> a.texto().contains("instalado fora do app")).count(), "um aviso só: " + avisos.todos);
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.ATUALIZAR_AGENTE));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.REMOVER_AGENTE));
    }

    // ---------------------------------------------------------------------------------------------
    // Os diálogos nos PNGs
    // ---------------------------------------------------------------------------------------------

    @Test
    void pngsDosDialogosComACaixa() throws Exception {
        ServidorLab lab = servidor("vps-1", true);
        DialogoEditarServidor editar = Visual.naEdt(() -> new DialogoEditarServidor(lab.config(), dados -> null, avisos,
            new DialogoEditarServidor.Acesso("lab", "SHA256:5MGLihSpPv/h6+y5NnTZfr2nnOaG8vJ9pEy1z3+jIQg", "ssh-ed25519 AAAAexemplo lab", () -> { }),
            new CopiadorDeTexto(() -> new Clipboard("teste"))));
        assertTrue(Visual.naEdt(() -> editar.caixaProtegido().isSelected()));
        renderizarNosDoisTemas(editar::raiz, 560, Visual.alturaPreferida(editar.raiz(), 560), "dialogo-editar-servidor-protegido");

        var remover = Visual.naEdt(() -> new br.com.labdesktop.ui.dialogos.DialogoRemoverServidor("vps-1", "labuser", true, List.of(),
            br.com.labdesktop.cofre.InfoChave.Origem.GERADA_PELO_APP, opcoes -> null, avisos, true));
        renderizarNosDoisTemas(remover::raiz, 560, Visual.alturaPreferida(remover.raiz(), 560), "dialogo-remover-servidor-protegido");

        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        DialogoAdicionarServidor adicionar = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, false, avisos, l -> { }, null, null,
            new CopiadorDeTexto(() -> new Clipboard("teste")), null));
        assertFalse(Visual.naEdt(() -> adicionar.caixaProtegido().isSelected()));
        renderizarNosDoisTemas(adicionar::raiz, 560, Visual.alturaPreferida(adicionar.raiz(), 560), "dialogo-adicionar-servidor-passo-1-com-caixa");
    }
}
