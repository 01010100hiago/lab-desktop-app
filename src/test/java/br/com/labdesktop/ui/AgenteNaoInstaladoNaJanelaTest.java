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
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.dialogos.DialogoDoAgente;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
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
 * "Agente não instalado" na janela de verdade (sem JFrame): o aviso FIXO no cartão do servidor, no Resumo e na faixa, com o botão
 * Instalar agente; nenhuma janela abre sozinha; só o clique abre a confirmação. E o que o servidor do perfil monitor muda nas abas
 * (sem Projetos), nos cartões (atualizar e remover agente) e nas telas.
 */
@ExtendWith(VerificaEdt.class)
class AgenteNaoInstaladoNaJanelaTest {

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

    private ServidorLab servidor(String nome, boolean comToken) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
        cofre.adicionarChave(chave);
        ssh.aceitar(chave.paraKeyPair().getPublic());
        TokenAgente token = comToken ? TokenAgente.gerar() : null;
        String dispositivo = comToken ? "mac-" + nome : null;
        if (comToken) {
            agente.tokens.put(token.hashSha256Hex(), dispositivo);
        }
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), nome, "127.0.0.1", ssh.porta(), "labuser", chave.id(),
            agente.porta(), List.of(ssh.pin()), Transporte.DIRETO, dispositivo, Estado2fa.DESLIGADO);
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
        if (c instanceof AbstractButton b && b.isShowing() || c instanceof AbstractButton b2 && b2.isVisible()) {
            acc.add((AbstractButton) c);
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

    // ---------------------------------------------------------------------------------------------
    // Agente não instalado
    // ---------------------------------------------------------------------------------------------

    @Test
    void aoConectarSemAgenteOCartaoMostraOAvisoFixoESoOCliqueAbreAConfirmacao() throws Exception {
        ServidorLab lab = servidor("vps-1", false);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, () -> "aviso no cartão (estado=" + lab.estado() + ")", () -> Visual.naEdt(cartao::textoDoAvisoDoAgente).contains("Agente não instalado"));

        assertEquals("Agente não instalado: " + ConteudoDosAvisos.TEXTO_DO_AGENTE_NAO_INSTALADO, Visual.naEdt(cartao::textoDoAvisoDoAgente));
        assertEquals("Agente não instalado", Visual.naEdt(cartao::textoDoEstado));
        assertTrue(Visual.naEdt(cartao::acoesMostradas).contains(Item.INSTALAR_AGENTE));
        assertEquals("Instalar agente…", Visual.naEdt(() -> cartao.botaoDa(Item.INSTALAR_AGENTE).getText()));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.ATUALIZAR_AGENTE));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.REMOVER_AGENTE));
        assertTrue(Visual.naEdt(cartao::acoesMostradas).contains(Item.CONECTAR), "continua podendo tentar de novo");
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.REGISTRAR_TOKEN), "token e 2FA são do agente: sem ele não há onde registrar");
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.CONFIGURAR_2FA));
        assertEquals(List.of(Item.ATUALIZAR, Item.CONECTAR, Item.INSTALAR_AGENTE, Item.EDITAR, Item.REMOVER), Visual.naEdt(cartao::acoesMostradas));
        // nenhuma janela aparece sozinha, nem depois de esperar
        Thread.sleep(1500);
        assertTrue(dialogos.mostrados.isEmpty(), "nada abre sem o clique: " + dialogos.mostrados);
        for (String c : ssh.comandos) {
            assertTrue(ComandosRemotos.ehLeitura(c), "só leituras até o clique: " + c);
        }
        assertTrue(agente.comandosCli.isEmpty());
        Visual.salvar(Visual.renderizar(painel, 1280, 560), "janela-servidores-agente-nao-instalado-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
        Visual.salvar(Visual.renderizar(painel, 1280, 560), "janela-servidores-agente-nao-instalado-escuro");
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));

        Visual.naEdt(() -> cartao.botaoDa(Item.INSTALAR_AGENTE).doClick());
        Espera.ate(PRAZO, "a confirmação abriu", () -> !dialogos.mostrados.isEmpty());
        DialogoDoAgente d = dialogos.ultimoComo(DialogoDoAgente.class);
        assertEquals("Instalar o agente", d.titulo());
        assertEquals("conferindo", Visual.naEdt(d::paginaAtual), "abre conferindo (só leitura); nada foi instalado");
        assertTrue(ssh.comandos.stream().allMatch(ComandosRemotos::ehLeitura));
    }

    @Test
    void oResumoDoServidorTambemDizEOferecoOBotaoSemJanelaSozinha() throws Exception {
        ServidorLab lab = servidor("vps-1", false);
        abrirAJanela();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        TelaServidor tela = Visual.naEdt(() -> painel.navegacao().telaServidorDe(lab.id()));
        // sem janela de verdade a aba não "aparece" sozinha (não assina): entrega o estado como a assinatura faria
        Visual.naEdt(() -> tela.resumo().aoMudarEstado(lab.estado()));
        Visual.renderizar(tela, 1100, 700);
        Espera.ate(PRAZO, "o aviso aparece no Resumo", () -> Visual.naEdt(() -> textosDe(tela)).contains("O agente ainda não está instalado neste servidor"));

        String texto = Visual.naEdt(() -> textosDe(tela));
        // o aviso ocupa UMA linha (título e botão): a explicação inteira vai na dica, para sobrar altura ao Registro
        java.awt.Component faixa = Visual.naEdt(() -> porNome(tela, "faixa-de-dados"));
        assertEquals(ConteudoDosAvisos.TEXTO_DO_AGENTE_NAO_INSTALADO, Visual.naEdt(() -> ((javax.swing.JComponent) faixa).getToolTipText()));
        assertEquals(ConteudoDosAvisos.TEXTO_DO_AGENTE_NAO_INSTALADO, Visual.naEdt(() -> tela.resumo().situacao().texto()));
        assertFalse(texto.toLowerCase().replace("erros", "").contains("erro"), "é uma pergunta, não um erro (o botão de filtro \"Erros\" do Registro não conta): " + texto);
        assertTrue(texto.contains("Instale o agente para ver a tendência."), "as Tendências dizem o que fazer, sem vermelho: " + texto);
        assertTrue(texto.contains("Registro do servidor"), "o Registro continua (pelo SSH): " + texto);
        List<AbstractButton> instalar = new ArrayList<>();
        Visual.naEdt(() -> botoes(tela, new ArrayList<>()).stream().filter(b -> "Instalar agente".equals(b.getText())).forEach(instalar::add));
        assertFalse(instalar.isEmpty(), "o botão Instalar agente está à mão no Resumo");
        assertTrue(dialogos.mostrados.isEmpty());
        // em 1100 x 700 o Resumo deste estado também cabe sem rolagem (o aviso de uma linha deixa a altura para as Tendências e o Registro)
        int largura = 1100 - br.com.labdesktop.ui.BarraServidores.LARGURA;
        assertTrue(Visual.alturaPreferida(tela, largura) <= 700 - 28, "pede " + Visual.alturaPreferida(tela, largura) + " px");
        Visual.salvar(Visual.renderizar(tela, 1100, 700), "servidor-resumo-agente-nao-instalado-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(tela));
        Visual.salvar(Visual.renderizar(tela, 1100, 700), "servidor-resumo-agente-nao-instalado-escuro");
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(tela));

        Visual.naEdt(() -> instalar.get(0).doClick());
        Espera.ate(PRAZO, "a confirmação abriu", () -> !dialogos.mostrados.isEmpty());
        assertTrue(dialogos.ultimo() instanceof DialogoDoAgente);
    }

    @Test
    void agenteInstaladoMasSemRespostaNaoOferecePorInstalar() throws Exception {
        agente.diagnostico = "existe:running";
        ServidorLab lab = servidor("vps-1", false);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.INSTALADO_SEM_RESPOSTA);
        assertEquals("", Visual.naEdt(cartao::textoDoAvisoDoAgente));
        assertFalse(Visual.naEdt(cartao::acoesMostradas).contains(Item.INSTALAR_AGENTE));
        assertEquals("Agente não responde", Visual.naEdt(cartao::textoDoEstado));
    }

    // ---------------------------------------------------------------------------------------------
    // Menu e perfil monitor
    // ---------------------------------------------------------------------------------------------

    @Test
    void asAcoesDoAgenteSoAparecemOndeFazemSentido() {
        var semAgente = new br.com.labdesktop.servidor.EstadoServidor("s", "x", EstadoConexao.SSH_OK_SEM_AGENTE, null, 0, null, null, 0,
            Estado2fa.DESLIGADO, "mac", null, null, SituacaoDoAgente.NAO_INSTALADO);
        assertTrue(MenuDoServidor.itensPara(semAgente).contains(Item.INSTALAR_AGENTE));
        var semPermissao = new br.com.labdesktop.servidor.EstadoServidor("s", "x", EstadoConexao.SSH_OK_SEM_AGENTE, null, 0, null, null, 0,
            Estado2fa.DESLIGADO, "mac", null, null, SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER);
        assertTrue(MenuDoServidor.itensPara(semPermissao).contains(Item.INSTALAR_AGENTE));
        var instaladoMudo = new br.com.labdesktop.servidor.EstadoServidor("s", "x", EstadoConexao.SSH_OK_SEM_AGENTE, null, 0, null, null, 0,
            Estado2fa.DESLIGADO, "mac", null, null, SituacaoDoAgente.INSTALADO_SEM_RESPOSTA);
        assertFalse(MenuDoServidor.itensPara(instaladoMudo).contains(Item.INSTALAR_AGENTE));

        var versaoMonitor = new br.com.labdesktop.api.modelo.VersaoAgente(1, "v22", false, "mac", "desligado", "monitor",
            br.com.labdesktop.api.modelo.RecursosDoAgente.SO_MONITOR, "0.1.0-abc1234");
        var versaoLab = new br.com.labdesktop.api.modelo.VersaoAgente(1, "v22", false, "mac", "desligado", "lab",
            br.com.labdesktop.api.modelo.RecursosDoAgente.TUDO, null);
        var monitor = new br.com.labdesktop.servidor.EstadoServidor("s", "x", EstadoConexao.CONECTADO, null, 0, null, null, 0,
            Estado2fa.DESLIGADO, "mac", versaoMonitor, null);
        var lab = new br.com.labdesktop.servidor.EstadoServidor("s", "x", EstadoConexao.CONECTADO, null, 0, null, null, 0,
            Estado2fa.DESLIGADO, "mac", versaoLab, null);
        assertTrue(MenuDoServidor.itensPara(monitor).containsAll(List.of(Item.ATUALIZAR_AGENTE, Item.REMOVER_AGENTE)));
        assertFalse(MenuDoServidor.itensPara(lab).contains(Item.ATUALIZAR_AGENTE), "o agente do lab (produção) nunca se atualiza nem se remove daqui");
        assertFalse(MenuDoServidor.itensPara(lab).contains(Item.REMOVER_AGENTE));
        assertFalse(MenuDoServidor.itensPara(lab).contains(Item.INSTALAR_AGENTE));
        // monitor conectado mas só leitura de "perfil": sem estar conectado não há atualizar nem remover
        var monitorCaido = new br.com.labdesktop.servidor.EstadoServidor("s", "x", EstadoConexao.RECONECTANDO, null, 0, null, null, 0,
            Estado2fa.DESLIGADO, "mac", versaoMonitor, null);
        assertFalse(MenuDoServidor.itensPara(monitorCaido).contains(Item.REMOVER_AGENTE));
    }

    @Test
    void servidorComAgenteMonitorNaoTemAbaProjetosNemClaudeEOCartaoOferecAtualizarERemover() throws Exception {
        agente.fora = false;
        agente.perfil = "monitor";
        ServidorLab lab = servidor("vps-1", true);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "ações do monitor", () -> Visual.naEdt(cartao::acoesMostradas).contains(Item.REMOVER_AGENTE));
        assertTrue(Visual.naEdt(cartao::acoesMostradas).contains(Item.ATUALIZAR_AGENTE));
        assertEquals("Atualizar agente…", Visual.naEdt(() -> cartao.botaoDa(Item.ATUALIZAR_AGENTE).getText()));
        assertEquals("Remover agente…", Visual.naEdt(() -> cartao.botaoDa(Item.REMOVER_AGENTE).getText()));
        assertEquals("", Visual.naEdt(cartao::textoDoAvisoDoAgente));

        Visual.naEdt(() -> painel.mostrarServidor(lab));
        TelaServidor tela = Visual.naEdt(() -> painel.navegacao().telaServidorDe(lab.id()));
        Espera.ate(PRAZO, "aba Projetos some", () -> !Visual.naEdt(() -> tela.abas().visivel(TelaServidor.Aba.PROJETOS.id())));
        assertFalse(Visual.naEdt(tela::temProjetos));
        for (TelaServidor.Aba a : List.of(TelaServidor.Aba.RESUMO, TelaServidor.Aba.SEGURANCA, TelaServidor.Aba.CONTAINERS, TelaServidor.Aba.PROCESSOS,
            TelaServidor.Aba.MAQUINA, TelaServidor.Aba.ARQUIVOS, TelaServidor.Aba.TERMINAL)) {
            assertTrue(Visual.naEdt(() -> tela.abas().visivel(a.id())), a + " continua");
        }
        // o atalho de "Projetos" não leva a uma aba que não existe
        Visual.naEdt(() -> tela.mostrarAba(TelaServidor.Aba.PROJETOS));
        assertEquals(TelaServidor.Aba.RESUMO, Visual.naEdt(tela::abaAtual));
        assertTrue(Visual.naEdt(() -> textosDe(cartao)).toLowerCase().contains("agente monitor"), "o cartão diz o perfil do agente");
        Visual.salvar(Visual.renderizar(painel, 1280, 560), "janela-servidores-agente-monitor-claro");
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        Visual.salvar(Visual.renderizar(tela, 1100, 700), "servidor-agente-monitor-sem-projetos-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(tela));
        Visual.salvar(Visual.renderizar(tela, 1100, 700), "servidor-agente-monitor-sem-projetos-escuro");
        Visual.tema(Tema.CLARO);
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
}
