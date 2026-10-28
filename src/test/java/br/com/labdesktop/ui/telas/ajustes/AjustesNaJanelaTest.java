package br.com.labdesktop.ui.telas.ajustes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.ui.AjustesDoApp;
import br.com.labdesktop.ui.AuditoriaDeCores;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.MenuApp;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.TelaProjeto;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.OrdemDeAbas;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tela Ajustes na janela (com servidor vivo de mentira): a engrenagem da barra lateral e o menu, Aparência (tema e tamanho do texto na hora e lembrados), Navegação
 * (ordem e abas escondidas das duas barras, aplicadas na hora, e os atalhos Cmd+1 a Cmd+9 na ordem nova), Terminal e Sobre; mais as cores de cada seção e os PNGs nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class AjustesNaJanelaTest {

    @TempDir
    Path pasta;

    private CenarioDeUi cenario;
    private final AvisosGravados avisos = new AvisosGravados();
    private PainelPrincipal painel;
    private GerenciadorTema tema;
    /** Como o computador responde (o teste decide; nada de rodar o comando do sistema). */
    private final java.util.concurrent.atomic.AtomicBoolean sistemaFalsoEscuro = new java.util.concurrent.atomic.AtomicBoolean(false);
    private PreferenciasAtuais preferencias;
    private CaminhosApp caminhos;
    private final Clipboard area = new Clipboard("teste");
    private TelaAjustes ajustes;
    private MenuApp menu;

    @BeforeEach
    void montar() throws Exception {
        cenario = new CenarioDeUi();
        cenario.agente.detalhes.put("app-loja", MocksDoFront.ler("projetos/app-loja.json"));
        caminhos = CaminhosApp.comPasta(pasta);
        preferencias = new PreferenciasAtuais(caminhos, Preferencias.padrao());
        // o sistema está CLARO neste teste; a escolha é a padrão das preferências (Sistema)
        tema = new GerenciadorTema(Preferencias.padrao().tema(), false, false).comConsultaDoSistema(sistemaFalsoEscuro::get);
        Visual.naEdt(tema::instalar);
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos), br.com.labdesktop.arquivos.ServicosDeArquivos.descartaveis(),
            preferencias::get));
        Visual.naEdt(() -> painel.instalarAjustes(() -> {
            ajustes = new TelaAjustes(new AjustesDoApp(tema, preferencias, painel, new CopiadorDeTexto(() -> area), avisos));
            return ajustes;
        }));
        menu = Visual.naEdt(() -> new MenuApp(null, painel, tema, preferencias, () -> { }, () -> painel.mostrarAjustes(Secao.SOBRE)));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(() -> Fontes.definirPercentual(100));
        Visual.naEdt(painel::encerrar);
        cenario.close();
    }

    private TelaServidor servidor() {
        return Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
    }

    private void abrir(Secao s) {
        Visual.naEdt(() -> painel.mostrarAjustes(s));
    }

    private BlocoDeAbas blocoDoServidor() {
        return Visual.naEdt(() -> ajustes.navegacao().blocoDoServidor());
    }

    private BlocoDeAbas blocoDoProjeto() {
        return Visual.naEdt(() -> ajustes.navegacao().blocoDoProjeto());
    }

    // ---------------------------------------------------------------------------------------------
    // A engrenagem, o menu e o Voltar
    // ---------------------------------------------------------------------------------------------

    @Test
    void aEngrenagemEstaNaBarraLateralAbreEFechaOsAjustes() {
        assertTrue(Visual.naEdt(() -> painel.barra().ajustesVisivel()), "a engrenagem aparece quando a janela tem Ajustes");
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
        Visual.naEdt(() -> painel.barra().clicarEmAjustes());
        assertTrue(Visual.naEdt(() -> painel.navegacao().emAjustes()));
        assertTrue(Visual.naEdt(() -> painel.barra().ajustesSelecionado()), "a engrenagem fica marcada");
        assertNull(Visual.naEdt(() -> painel.barra().selecionado()), "e o servidor deixa de estar marcado");
        assertEquals(CofreFalso.ID, Visual.naEdt(() -> painel.servidorEmFoco().id()), "o menu Servidor segue agindo sobre o servidor de antes");
        Visual.naEdt(() -> painel.barra().clicarEmAjustes());
        assertFalse(Visual.naEdt(() -> painel.navegacao().emAjustes()), "a engrenagem de novo volta para onde o usuário estava");
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
        assertEquals(CofreFalso.ID, Visual.naEdt(() -> painel.barra().selecionado()));
    }

    @Test
    void voltarEEscLevamParaOndeODonoEstava() {
        Visual.naEdt(() -> painel.mostrarServidores());
        abrir(null);
        Visual.naEdt(() -> ajustes.botaoVoltar().doClick());
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));
        abrir(Secao.TERMINAL);
        Visual.naEdt(() -> ajustes.getActionMap().get("voltar-dos-ajustes").actionPerformed(null));
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()), "Esc volta");
        assertEquals(Secao.TERMINAL, Visual.naEdt(() -> ajustes.secaoAtual()), "e a seção em que estava fica lembrada");
    }

    @Test
    void oMenuTemAjustesComAtalhoDeVirgulaESobreEAparenciaLevamAosAjustes() {
        var ajustesItem = Visual.naEdt(menu::itemAjustes);
        assertEquals("Ajustes…", Visual.naEdt(ajustesItem::getText));
        assertEquals(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_COMMA, br.com.labdesktop.ui.componentes.Teclas.comando()), Visual.naEdt(ajustesItem::getAccelerator),
            "a tecla de comando do sistema e a vírgula");
        Visual.naEdt(() -> ajustesItem.doClick());
        assertTrue(Visual.naEdt(() -> painel.navegacao().emAjustes()));
        Visual.naEdt(() -> painel.navegacao().voltarDosAjustes());
        Visual.naEdt(() -> menu.itemSobre().doClick());
        assertEquals(Secao.SOBRE, Visual.naEdt(() -> ajustes.secaoAtual()), "Arquivo, Sobre abre a seção Sobre");
        Visual.naEdt(() -> painel.navegacao().voltarDosAjustes());
        assertEquals("Sobre o Lab Desktop", Visual.naEdt(() -> menu.itemSobre().getText()));
        Visual.naEdt(() -> menu.itemAparencia().doClick());
        assertEquals(Secao.APARENCIA, Visual.naEdt(() -> ajustes.secaoAtual()), "o seletor de tema saiu do menu: sobrou o caminho até os Ajustes");
    }

    // ---------------------------------------------------------------------------------------------
    // Aparência
    // ---------------------------------------------------------------------------------------------

    @Test
    void aparenciaComecaEmSistemaDizOQueOSistemaUsaETrocaOTemaNaHoraELembra() {
        abrir(Secao.APARENCIA);
        SecaoAparencia a = Visual.naEdt(() -> ajustes.aparencia());
        assertTrue(Visual.naEdt(() -> a.opcaoDoTema(Tema.SISTEMA).isSelected()), "o padrão é Sistema");
        assertEquals("Agora o sistema está usando o modo claro.", Visual.naEdt(a::textoDoSistemaAgora));
        Color fundoClaro = Visual.naEdt(() -> Tokens.cor(Tokens.BG));
        assertEquals(new Color(0xF6F7F8), fundoClaro);

        Visual.naEdt(() -> a.opcaoDoTema(Tema.ESCURO).doClick());
        assertTrue(Visual.naEdt(tema::escuroAgora), "trocou na hora, sem reiniciar");
        assertEquals(Tema.ESCURO, preferencias.get().tema());
        assertEquals(new Color(0x16181B), Visual.naEdt(() -> Tokens.cor(Tokens.BG)));
        assertEquals("", Visual.naEdt(a::textoDoSistemaAgora), "a frase do sistema só aparece com a escolha Sistema");

        Visual.naEdt(() -> a.opcaoDoTema(Tema.CLARO).doClick());
        assertFalse(Visual.naEdt(tema::escuroAgora));
        assertEquals(fundoClaro, Visual.naEdt(() -> Tokens.cor(Tokens.BG)));

        Visual.naEdt(() -> a.opcaoDoTema(Tema.SISTEMA).doClick());
        Espera.ate(Duration.ofSeconds(5), "a escolha Sistema valer", () -> preferencias.get().tema() == Tema.SISTEMA);
        Visual.naEdt(() -> tema.definirSistemaEscuro(true));
        assertTrue(Visual.naEdt(tema::escuroAgora), "com Sistema, o tema segue o computador");
        assertEquals("Agora o sistema está usando o modo escuro.", Visual.naEdt(a::textoDoSistemaAgora), "e a frase acompanha, sem reabrir a tela");
        gravado(p -> p.tema() == Tema.SISTEMA);
    }

    @Test
    void escolherSistemaComOComputadorEscuroNaoPiscaNemMostraAFraseErrada() {
        abrir(Secao.APARENCIA);
        SecaoAparencia a = Visual.naEdt(() -> ajustes.aparencia());
        Visual.naEdt(() -> a.opcaoDoTema(Tema.ESCURO).doClick());
        assertTrue(Visual.naEdt(tema::escuroAgora));
        // o computador está ESCURO, mas o app ainda acha que está claro (nunca perguntou: o usuário tinha escolhido Escuro)
        sistemaFalsoEscuro.set(true);
        assertFalse(Visual.naEdt(tema::sistemaEstaEscuro));
        List<String> vistos = new ArrayList<>();
        Visual.naEdt(() -> tema.aoMudar(() -> vistos.add((tema.escuroAgora() ? "escuro" : "claro") + " | " + a.textoDoSistemaAgora())));
        Visual.naEdt(() -> a.opcaoDoTema(Tema.SISTEMA).doClick());
        Espera.ate(Duration.ofSeconds(5), "a escolha Sistema valer", () -> preferencias.get().tema() == Tema.SISTEMA);
        assertTrue(Visual.naEdt(tema::escuroAgora), "continua escuro: era o que o computador usa");
        assertEquals("Agora o sistema está usando o modo escuro.", Visual.naEdt(a::textoDoSistemaAgora));
        for (String v : vistos) {
            assertFalse(v.startsWith("claro"), "o app piscou no claro: " + vistos);
            assertFalse(v.contains("modo claro"), "a frase ficou errada por um instante: " + vistos);
        }
    }

    @Test
    void oTamanhoDoTextoMudaNaHoraELembra() {
        abrir(Secao.APARENCIA);
        SecaoAparencia a = Visual.naEdt(() -> ajustes.aparencia());
        assertTrue(Visual.naEdt(() -> a.opcaoDoTamanho(100).isSelected()));
        Visual.naEdt(() -> a.opcaoDoTamanho(115).doClick());
        assertEquals(115, Visual.naEdt(Fontes::percentual));
        assertEquals(115, preferencias.get().escalaTexto());
        assertTrue(Visual.naEdt(() -> menu.itemAumentarTexto().isEnabled()));
        Visual.naEdt(() -> a.opcaoDoTamanho(130).doClick());
        Visual.naEdt(menu::atualizarItensDasAbas);
        assertEquals(130, preferencias.get().escalaTexto());
        gravado(p -> p.escalaTexto() == 130);
    }

    // ---------------------------------------------------------------------------------------------
    // Terminal
    // ---------------------------------------------------------------------------------------------

    @Test
    void aSecaoSegurancaListaOsContainersMarcadosELiberaCadaUm() {
        preferencias.atualizar(p -> p.comContainerProtegido(CofreFalso.ID, "app-loja-web", true).comContainerProtegido(CofreFalso.ID, "db", true));
        abrir(Secao.SEGURANCA);
        SecaoSeguranca sec = Visual.naEdt(() -> ajustes.seguranca());
        List<String> linhas = Visual.naEdt(sec::linhas);
        assertEquals(2, linhas.size(), linhas.toString());
        assertTrue(linhas.get(0).endsWith("app-loja-web") && linhas.get(1).endsWith("db"), linhas.toString());
        assertTrue(linhas.get(0).startsWith(painel.nomeDoServidor(CofreFalso.ID)), "mostra o nome do servidor, não o id: " + linhas);
        Visual.naEdt(() -> sec.botoesDeLiberar().get(0).doClick(0));
        assertEquals(java.util.Set.of("db"), preferencias.get().containersProtegidosDe(CofreFalso.ID), "Liberar tira só aquele");
        assertEquals(1, Visual.naEdt(sec::linhas).size());
        Visual.naEdt(() -> sec.botoesDeLiberar().get(0).doClick(0));
        assertEquals(java.util.Set.of(), preferencias.get().containersProtegidosDe(CofreFalso.ID));
        assertEquals(0, Visual.naEdt(sec::linhas).size());
    }

    @Test
    void aSecaoTerminalMostraEMudaAsPreferenciasQueJaExistiam() {
        abrir(Secao.TERMINAL);
        SecaoTerminal t = Visual.naEdt(() -> ajustes.terminal());
        assertEquals(13, Visual.naEdt(() -> t.tamanhoDaLetra().getValue()));
        assertFalse(Visual.naEdt(() -> t.opcaoComoMeta().isSelected()));
        Visual.naEdt(() -> t.tamanhoDaLetra().setValue(16));
        Visual.naEdt(() -> t.opcaoComoMeta().doClick());
        Visual.naEdt(() -> t.copiarAoSelecionar().doClick());
        Visual.naEdt(() -> t.donoExclusivo().doClick());
        Preferencias p = preferencias.get();
        assertEquals(16, p.tamanhoFonteTerminal());
        assertTrue(p.opcaoComoMeta());
        assertTrue(p.copiarAoSelecionar());
        assertTrue(p.donoExclusivo());
        gravado(x -> x.tamanhoFonteTerminal() == 16 && x.donoExclusivo());
    }

    // ---------------------------------------------------------------------------------------------
    // Navegação
    // ---------------------------------------------------------------------------------------------

    private List<String> barraDoServidor() {
        return Visual.naEdt(() -> servidor().abas().idsVisiveis());
    }

    @Test
    void aOrdemPadraoDaBarraDoServidorEAConhecida() {
        assertEquals(List.of("resumo", "seguranca", "containers", "processos", "maquina", "projetos", "arquivos", "terminal", "cloudflare"), barraDoServidor());
        abrir(Secao.NAVEGACAO);
        assertEquals(List.of("Resumo", "Segurança", "Containers", "Processos", "Máquina", "Projetos", "Arquivos", "Terminal", "Cloudflare"),
            Visual.naEdt(() -> blocoDoServidor().rotulosEmOrdem()));
        assertEquals(List.of("Visão geral", "Logs", "Terminal", "Arquivos", "Backups", "Consumo e limite", "Internet", "Processos"), Visual.naEdt(() -> blocoDoProjeto().rotulosEmOrdem()));
    }

    @Test
    void subirEDescerMudaAOrdemDaBarraNaHoraELembraNoDisco() {
        abrir(Secao.NAVEGACAO);
        Visual.naEdt(() -> blocoDoServidor().botaoSubir("terminal").doClick());
        Visual.naEdt(() -> blocoDoServidor().botaoSubir("terminal").doClick());
        assertEquals(List.of("resumo", "seguranca", "containers", "processos", "maquina", "terminal", "projetos", "arquivos", "cloudflare"), barraDoServidor(),
            "a barra da página do servidor já está na ordem nova, sem reiniciar");
        Visual.naEdt(() -> blocoDoServidor().botaoDescer("resumo").doClick());
        assertEquals("seguranca", barraDoServidor().get(0));
        assertEquals("resumo", barraDoServidor().get(1));
        assertEquals(List.of("Segurança", "Resumo", "Containers", "Processos", "Máquina", "Terminal", "Projetos", "Arquivos", "Cloudflare"),
            Visual.naEdt(() -> blocoDoServidor().rotulosEmOrdem()), "a lista dos Ajustes acompanha");
        OrdemDeAbas naMemoria = preferencias.get().abasDoServidor();
        gravado(p -> p.abasDoServidor().equals(naMemoria)); // a gravação é em segundo plano: espera o disco ficar igual à memória
        assertTrue(Preferencias.carregar(caminhos).abasDoServidor().ordem().indexOf("terminal") < Preferencias.carregar(caminhos).abasDoServidor().ordem().indexOf("projetos"));
    }

    @Test
    void esconderTiraAbaDaBarraEMostrarDevolveEOrestauraVoltaAoPadrao() {
        abrir(Secao.NAVEGACAO);
        Visual.naEdt(() -> blocoDoServidor().caixaDe("seguranca").doClick());
        Visual.naEdt(() -> blocoDoServidor().caixaDe("cloudflare").doClick());
        assertEquals(List.of("resumo", "containers", "processos", "maquina", "projetos", "arquivos", "terminal"), barraDoServidor());
        assertTrue(Visual.naEdt(() -> blocoDoServidor().caixaDe("seguranca").isEnabled()), "a escondida continua na lista, para voltar");
        assertFalse(Visual.naEdt(() -> blocoDoServidor().caixaDe("seguranca").isSelected()));
        Visual.naEdt(() -> blocoDoServidor().caixaDe("seguranca").doClick());
        assertEquals(List.of("resumo", "seguranca", "containers", "processos", "maquina", "projetos", "arquivos", "terminal"), barraDoServidor());
        assertTrue(Visual.naEdt(() -> blocoDoServidor().botaoRestaurar().isEnabled()));
        Visual.naEdt(() -> blocoDoServidor().botaoSubir("terminal").doClick());
        Visual.naEdt(() -> blocoDoServidor().botaoRestaurar().doClick());
        assertEquals(List.of("resumo", "seguranca", "containers", "processos", "maquina", "projetos", "arquivos", "terminal", "cloudflare"), barraDoServidor());
        assertTrue(preferencias.get().abasDoServidor().ehPadrao());
        assertFalse(Visual.naEdt(() -> blocoDoServidor().botaoRestaurar().isEnabled()), "já está no padrão");
    }

    /** As linhas são refeitas a cada mudança, e o foco do teclado se perdia (quem usa Tab voltava ao começo da tela). */
    @Test
    void depoisDeMarcarCaixaOuRestaurarOFocoVoltaAoControleEquivalente() {
        abrir(Secao.NAVEGACAO);
        Visual.naEdt(() -> blocoDoServidor().caixaDe("seguranca").doClick());
        assertEquals(Visual.naEdt(() -> blocoDoServidor().caixaDe("seguranca")), Visual.naEdt(() -> blocoDoServidor().focoPedido()), "a mesma caixa");
        Visual.naEdt(() -> blocoDoServidor().caixaDe("seguranca").doClick());
        Visual.naEdt(() -> blocoDoServidor().botaoDescer("resumo").doClick());
        assertEquals(Visual.naEdt(() -> blocoDoServidor().botaoDescer("resumo")), Visual.naEdt(() -> blocoDoServidor().focoPedido()), "o botão da aba que se moveu");
        Visual.naEdt(() -> blocoDoServidor().botaoRestaurar().doClick());
        // o botão Restaurar ficou desabilitado (já está no padrão): o foco vai para a primeira caixa que aceita foco
        assertTrue(Visual.naEdt(() -> blocoDoServidor().focoPedido() instanceof javax.swing.JCheckBox c && c.isEnabled()), "uma caixa que aceita foco");
    }

    /** Os itens da barra lateral tinham foco mas nenhum anel e o leitor de tela os via como painéis mudos. */
    @Test
    void itensDaBarraDeServidoresTemAnelDeFocoEPapelDeBotao() {
        BarraServidores barra = Visual.naEdt(() -> painel.barra());
        List<javax.swing.JComponent> itens = Visual.naEdt(() -> {
            List<javax.swing.JComponent> l = new ArrayList<>();
            l.add(barra.itemGerenciar());
            l.add(barra.itemAjustes());
            l.add(barra.item(CofreFalso.ID));
            return l;
        });
        for (javax.swing.JComponent item : itens) {
            assertEquals(javax.accessibility.AccessibleRole.PUSH_BUTTON, Visual.naEdt(() -> item.getAccessibleContext().getAccessibleRole()), "papel de botão");
            assertTrue(Visual.naEdt(() -> item.getAccessibleContext().getAccessibleAction().getAccessibleActionCount()) == 1, "tem a ação clicar");
            assertTrue(!Visual.naEdt(() -> BarraServidores.mostraAnelDeFoco(item)));
            java.awt.image.BufferedImage sem = Visual.renderizar(item, BarraServidores.LARGURA - 24, 48);
            int antes = Visual.contarPixels(sem, Visual.naEdt(() -> Tokens.cor(Tokens.FOCO)), 0);
            Visual.naEdt(() -> { for (java.awt.event.FocusListener l : item.getFocusListeners()) { l.focusGained(new java.awt.event.FocusEvent(item, java.awt.event.FocusEvent.FOCUS_GAINED)); } });
            assertTrue(Visual.naEdt(() -> BarraServidores.mostraAnelDeFoco(item)));
            java.awt.image.BufferedImage com = Visual.renderizar(item, BarraServidores.LARGURA - 24, 48);
            Visual.salvar(com, "barra-item-com-foco");
            int depois = Visual.contarPixels(com, Visual.naEdt(() -> Tokens.cor(Tokens.FOCO)), 0);
            assertTrue(depois - antes > 150, "o anel de 2 px do token FOCO aparece (" + antes + " -> " + depois + " pixels)");
            Visual.naEdt(() -> { for (java.awt.event.FocusListener l : item.getFocusListeners()) { l.focusLost(new java.awt.event.FocusEvent(item, java.awt.event.FocusEvent.FOCUS_LOST)); } });
            assertTrue(!Visual.naEdt(() -> BarraServidores.mostraAnelDeFoco(item)));
        }
    }

    @Test
    void aAbaAbertaNuncaSomeENuncaSobraABarraVazia() {
        abrir(Secao.NAVEGACAO);
        // a página do servidor está na aba Resumo: ela não se esconde
        assertFalse(Visual.naEdt(() -> blocoDoServidor().caixaDe("resumo").isEnabled()), "a aba aberta fica travada marcada");
        assertTrue(Visual.naEdt(() -> blocoDoServidor().caixaDe("resumo").isSelected()));
        assertNotNull(Visual.naEdt(() -> blocoDoServidor().caixaDe("resumo").getToolTipText()));
        // esconde todas as outras: a última que sobra é a aberta, que já está travada
        for (String id : List.of("seguranca", "containers", "processos", "maquina", "projetos", "arquivos", "terminal", "cloudflare")) {
            Visual.naEdt(() -> blocoDoServidor().caixaDe(id).doClick());
        }
        assertEquals(List.of("resumo"), barraDoServidor(), "só a aba aberta sobrou");
        // sem a aba aberta travando, a regra do "pelo menos uma": abre outra aba, esconde a que era a aberta... e a última visível não deixa
        Visual.naEdt(() -> blocoDoServidor().caixaDe("terminal").doClick()); // mostra o Terminal
        Visual.naEdt(() -> painel.navegacao().mostrarServidor(cenario.lab, TelaServidor.Aba.TERMINAL));
        abrir(Secao.NAVEGACAO);
        assertTrue(Visual.naEdt(() -> blocoDoServidor().caixaDe("resumo").isEnabled()), "o Resumo deixou de ser a aba aberta");
        Visual.naEdt(() -> blocoDoServidor().caixaDe("resumo").doClick());
        assertEquals(List.of("terminal"), barraDoServidor());
        assertFalse(Visual.naEdt(() -> blocoDoServidor().caixaDe("terminal").isEnabled()), "a última que sobra não se esconde");
    }

    @Test
    void umaAbaEscondidaQueOutraPaginaEstavaUsandoApareceEnquantoAbertaEVoltaASumirDepois() {
        abrir(Secao.NAVEGACAO);
        // a pagina está no Resumo; o usuário esconde o Terminal; depois alguém abre o Terminal por atalho de código
        Visual.naEdt(() -> blocoDoServidor().caixaDe("terminal").doClick());
        assertFalse(barraDoServidor().contains("terminal"));
        Visual.naEdt(() -> painel.navegacao().mostrarServidor(cenario.lab, TelaServidor.Aba.TERMINAL));
        assertTrue(barraDoServidor().contains("terminal"), "aberta, a aba aparece mesmo escondida na preferência");
        Visual.naEdt(() -> painel.navegacao().mostrarServidor(cenario.lab, TelaServidor.Aba.RESUMO));
        assertFalse(barraDoServidor().contains("terminal"), "saiu dela, volta a sumir");
    }

    @Test
    void osAtalhosNumericosSeguemAOrdemNovaEOMenuMostraONomeCerto() {
        assertEquals("Resumo", Visual.naEdt(() -> menu.itemDaAba(1).getText()));
        assertEquals("Terminal", Visual.naEdt(() -> menu.itemDaAba(8).getText()));
        assertEquals(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_3, br.com.labdesktop.ui.componentes.Teclas.comando()), Visual.naEdt(() -> menu.itemDaAba(3).getAccelerator()));
        abrir(Secao.NAVEGACAO);
        Visual.naEdt(() -> blocoDoServidor().botaoSubir("terminal").doClick()); // Terminal passa na frente de Arquivos
        Visual.naEdt(() -> blocoDoServidor().caixaDe("seguranca").doClick()); // e Segurança sai da barra
        Visual.naEdt(() -> painel.navegacao().voltarDosAjustes());
        Visual.naEdt(menu::atualizarItensDasAbas);
        assertEquals(List.of("Resumo", "Containers", "Processos", "Máquina", "Projetos", "Terminal", "Arquivos", "Cloudflare"), Visual.naEdt(() -> painel.nomesDasAbasVisiveis()));
        assertEquals("Containers", Visual.naEdt(() -> menu.itemDaAba(2).getText()), "o 2 agora é Containers (Segurança saiu)");
        assertEquals("Terminal", Visual.naEdt(() -> menu.itemDaAba(6).getText()));
        assertFalse(Visual.naEdt(() -> menu.itemDaAba(9).isEnabled()), "só 8 abas aparecem: o 9 fica apagado");
        Visual.naEdt(() -> menu.itemDaAba(6).doClick());
        assertEquals(TelaServidor.Aba.TERMINAL, Visual.naEdt(() -> servidor().abaAtual()), "Cmd+6 abre a sexta aba da barra");
        Visual.naEdt(() -> painel.mostrarAbaNumero(2));
        assertEquals(TelaServidor.Aba.CONTAINERS, Visual.naEdt(() -> servidor().abaAtual()));
        Visual.naEdt(() -> painel.mostrarAbaNumero(9));
        assertEquals(TelaServidor.Aba.CONTAINERS, Visual.naEdt(() -> servidor().abaAtual()), "sem a nona aba, nada acontece");
    }

    @Test
    void aOrdemDasAbasDoProjetoVaiParaAPaginaDoProjetoAbertaENaGuardada() {
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        TelaProjeto projeto = Visual.naEdt(() -> servidor().projetoAberto());
        assertEquals(List.of("visao-geral", "logs", "terminal", "arquivos", "backups", "consumo", "internet", "processos"), Visual.naEdt(() -> projeto.abas().idsVisiveis()));
        assertEquals("visao-geral", Visual.naEdt(() -> painel.abaAbertaDoProjeto()));
        abrir(Secao.NAVEGACAO);
        assertFalse(Visual.naEdt(() -> blocoDoProjeto().caixaDe("visao-geral").isEnabled()), "a aba aberta do projeto também não se esconde");
        Visual.naEdt(() -> blocoDoProjeto().botaoSubir("internet").doClick());
        Visual.naEdt(() -> blocoDoProjeto().caixaDe("backups").doClick());
        List<String> barra = Visual.naEdt(() -> projeto.abas().idsVisiveis());
        assertTrue(barra.indexOf("internet") < barra.indexOf("consumo"), "Internet subiu um lugar: " + barra);
        assertFalse(barra.contains("backups"), "Backups saiu da barra: " + barra);
        // uma página aberta DEPOIS também nasce na ordem nova
        Visual.naEdt(() -> painel.navegacao().voltarDosAjustes());
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        TelaProjeto de_novo = Visual.naEdt(() -> servidor().projetoAberto());
        assertFalse(Visual.naEdt(() -> de_novo.abas().idsVisiveis()).contains("backups"));
    }

    @Test
    void ordemGuardadaComLixoNaoPerdeAbaNaBarraDeVerdade() {
        // o que um arquivo de preferências corrompido faria: ids desconhecidos, repetidos e tudo escondido
        Visual.naEdt(() -> preferencias.atualizar(p -> p.comAbasDoServidor(new OrdemDeAbas(List.of("aba-do-futuro", "terminal", "terminal", "resumo"),
            new java.util.LinkedHashSet<>(TelaServidor.IDS_DAS_ABAS)))));
        Visual.naEdt(() -> painel.navegacao().reaplicarAbas(preferencias.get()));
        List<String> barra = barraDoServidor();
        assertEquals(9, barra.size(), "tudo escondido mostra tudo: nenhuma aba se perde");
        assertEquals(List.of("terminal", "resumo"), barra.subList(0, 2));
    }

    // ---------------------------------------------------------------------------------------------
    // Sobre
    // ---------------------------------------------------------------------------------------------

    @Test
    void sobreMostraVersaoSistemaJavaAgenteEPastaSemNadaSensivel() throws Exception {
        abrir(Secao.SOBRE);
        PainelSobre sobre = Visual.naEdt(() -> ajustes.sobre());
        var info = sobre.informacoes();
        assertEquals("Lab Desktop", info.nome());
        assertEquals(br.com.labdesktop.Versao.atual(), info.versao(), "a mesma versão do --versao");
        if (br.com.labdesktop.teste.PacoteDeTeste.temPacoteDeVerdade()) {
            assertTrue(info.agenteVersao().startsWith("0.1.0-"), info.agenteVersao());
            assertEquals(40, info.agenteCommit().length(), "o commit do agente empacotado");
        } else {
            assertEquals("não incluído", info.agenteVersao(), "app compilado sem o pacote do agente");
            assertEquals("não incluído", info.agenteCommit());
        }
        assertEquals(System.getProperty("os.arch"), info.arquitetura());
        assertTrue(info.versaoDoJava().startsWith(System.getProperty("java.version")));
        assertFalse(info.pastaDeDados().contains(System.getProperty("user.home")) && System.getProperty("user.home").length() > 1 && !info.pastaDeDados().startsWith("~"),
            "a pasta do usuário aparece como ~ (nesta pasta de teste, fora da pasta do usuário, vale o caminho inteiro)");
        Visual.naEdt(() -> sobre.botaoCopiarInformacoes().doClick());
        String copiado = (String) area.getData(DataFlavor.stringFlavor);
        assertTrue(copiado.startsWith("Lab Desktop " + info.versao()), copiado);
        assertTrue(copiado.contains("Sistema: ") && copiado.contains("Java: ") && copiado.contains("Agente empacotado: "), copiado);
        assertFalse(copiado.contains("senha") || copiado.contains("token") || copiado.contains("BEGIN"), "nada sensível na cópia");
        Visual.naEdt(() -> sobre.botaoCopiarPasta().doClick());
        assertEquals(info.pastaDeDados(), area.getData(DataFlavor.stringFlavor));
    }

    @Test
    void sobreTemOTextoLivreGratuitoMitSemGarantiaELicencasDeTerceirosDoJar() {
        abrir(Secao.SOBRE);
        PainelSobre sobre = Visual.naEdt(() -> ajustes.sobre());
        assertTrue(sobre.licencas().size() >= 28, "uma linha por biblioteca do build: " + sobre.licencas().size());
        assertFalse(Visual.naEdt(() -> sobre.secaoDaLicenca().aberta()));
        Visual.naEdt(() -> sobre.secaoDaLicenca().botao().doClick());
        Visual.naEdt(() -> sobre.secaoDeTerceiros().botao().doClick());
        assertTrue(Visual.naEdt(() -> sobre.secaoDaLicenca().aberta()));
        assertTrue(Visual.naEdt(() -> sobre.secaoDeTerceiros().aberta()));
        String texto = textoVisivel(sobre);
        assertTrue(texto.contains("software livre e gratuito"), texto);
        assertTrue(texto.contains("licença MIT"));
        assertTrue(texto.contains("Sem garantia"));
        assertTrue(texto.contains("Copyright (c) 2026 Thiago Bezerra"));
        assertTrue(texto.contains("Permission is hereby granted, free of charge"), "o texto oficial da licença, lido do jar");
        assertTrue(texto.contains("FlatLaf") && texto.contains("Apache MINA SSHD"), "as bibliotecas reais do build");
    }

    private static String textoVisivel(java.awt.Component c) {
        StringBuilder b = new StringBuilder();
        Visual.naEdt(() -> juntar(c, b));
        return b.toString();
    }

    private static void juntar(java.awt.Component c, StringBuilder b) {
        if (c instanceof javax.swing.text.JTextComponent t) {
            b.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            b.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton a && a.getText() != null) {
            b.append(a.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                juntar(f, b);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cores e imagens
    // ---------------------------------------------------------------------------------------------

    @Test
    void todaSecaoDosAjustesEnxergaSeNosDoisTemasMontadaETrocadaEGeraOsPngs() {
        List<String> ruins = new java.util.ArrayList<>();
        for (Tema inicial : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.naEdt(() -> {
                tema.definirEscolha(inicial);
                javax.swing.SwingUtilities.updateComponentTreeUI(painel);
            });
            for (Secao s : Secao.values()) {
                abrir(s);
                assertEquals(inicial == Tema.ESCURO, Visual.naEdt(tema::escuroAgora), "a medida das cores não pode mudar o tema (nem clicar em nada)");
                if (s == Secao.SOBRE) {
                    Visual.naEdt(() -> {
                        ajustes.sobre().secaoDaLicenca().definirAberta(true);
                        ajustes.sobre().secaoDeTerceiros().definirAberta(true);
                    });
                }
                for (var m : Visual.naEdt(() -> AuditoriaDeCores.auditar(painel, 1440, s == Secao.SOBRE ? 2100 : 900))) {
                    if (m.falhou()) {
                        ruins.add(inicial + " | " + s + " | " + m);
                    }
                }
                Visual.salvar(Visual.renderizar(painel, 1440, s == Secao.SOBRE ? 2100 : 860), "ajustes-" + s.id() + "-" + inicial.name().toLowerCase());
            }
        }
        assertTrue(ruins.isEmpty(), "controles dos Ajustes abaixo do limite de contraste:\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }

    @Test
    void nenhumaSecaoDosAjustesTransbordaEm900_1100E1440DeLarguraComAlturaDe600_700E860() {
        List<String> ruins = new java.util.ArrayList<>();
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.naEdt(() -> {
                tema.definirEscolha(t);
                javax.swing.SwingUtilities.updateComponentTreeUI(painel);
            });
            for (Secao s : Secao.values()) {
                abrir(s);
                for (int l : new int[] {900, 1100, 1440}) {
                    for (int h : new int[] {600, 700, 860}) {
                        Visual.naEdt(() -> painel.setSize(l, h));
                        for (String achado : Visual.naEdt(() -> br.com.labdesktop.ui.SemTransbordo.achados(painel))) {
                            ruins.add(t + " " + s + " " + l + "x" + h + ": " + achado);
                        }
                    }
                }
            }
        }
        assertTrue(ruins.isEmpty(), ruins.size() + " transbordos (os 15 primeiros):\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }

    // ---------------------------------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------------------------------

    /** A gravação em disco é em segundo plano: espera o arquivo refletir a condição. */
    private void gravado(java.util.function.Predicate<Preferencias> condicao) {
        Espera.ate(Duration.ofSeconds(10), "preferências no disco", () -> condicao.test(Preferencias.carregar(caminhos)));
    }
}
