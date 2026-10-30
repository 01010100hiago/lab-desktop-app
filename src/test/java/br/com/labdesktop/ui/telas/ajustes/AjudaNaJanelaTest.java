package br.com.labdesktop.ui.telas.ajustes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ajuda.AtalhoDoMenu;
import br.com.labdesktop.ajuda.Destino;
import br.com.labdesktop.ajuda.GuiaDeAjuda;
import br.com.labdesktop.ajuda.TopicoDeAjuda;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.ui.AjustesDoApp;
import br.com.labdesktop.ui.AuditoriaDeCores;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.ComportamentoDaJanela;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.MenuApp;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Ajuda na janela (com servidor vivo de mentira): a árvore de categorias recolhível, a busca que filtra e destaca, Voltar e Avançar, o "Continuar" de "Comece aqui", a lista
 * rápida "Onde fica...?", os links que abrem telas, a tabela de atalhos que sai do menu, e, nas três larguras e três alturas e nos dois temas, nenhum transbordo (nada de
 * rolagem horizontal, a janela nunca rola, cada painel rola por dentro) e o PNG da Ajuda.
 */
@ExtendWith(VerificaEdt.class)
class AjudaNaJanelaTest {

    @TempDir
    Path pasta;

    private CenarioDeUi cenario;
    private br.com.labdesktop.ui.galeria.ServidorDemo demo;
    private final AvisosGravados avisos = new AvisosGravados();
    private PainelPrincipal painel;
    private GerenciadorTema tema;
    private PreferenciasAtuais preferencias;
    private final Clipboard area = new Clipboard("teste");
    private TelaAjustes ajustes;
    private MenuApp menu;

    @BeforeEach
    void montar() throws Exception {
        cenario = new CenarioDeUi(br.com.labdesktop.api.PedidoDeCodigo2fa.NUNCA, false);
        demo = br.com.labdesktop.ui.galeria.ServidorDemo.preparar(cenario); // servidor-demo com consumo calmo (as imagens da galeria saem daqui)
        preferencias = new PreferenciasAtuais(CaminhosApp.comPasta(pasta), Preferencias.padrao());
        tema = new GerenciadorTema(Tema.CLARO, false, false);
        Visual.naEdt(tema::instalar);
        painel = Visual.naEdt(() -> new PainelPrincipal(demo.gerenciador(), null, avisos, new DialogosGravados(), new NavegadorGravado(avisos),
            br.com.labdesktop.arquivos.ServicosDeArquivos.descartaveis(), preferencias::get));
        Visual.naEdt(() -> painel.instalarAjustes(() -> {
            ajustes = new TelaAjustes(new AjustesDoApp(tema, preferencias, painel, new CopiadorDeTexto(() -> area), avisos, () -> menu.atalhosDoMenu()));
            return ajustes;
        }));
        menu = Visual.naEdt(() -> new MenuApp(null, painel, tema, preferencias, () -> { }, () -> painel.mostrarAjustes(Secao.SOBRE)));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(painel::encerrar);
        preferencias.descarregar(); // termina a gravação em segundo plano antes de a pasta temporária ser apagada
        demo.fechar();
        cenario.close();
    }

    private SecaoAjuda ajuda() {
        return Visual.naEdt(() -> {
            painel.mostrarAjustes(Secao.AJUDA);
            return ajustes.ajuda();
        });
    }

    private static int clicar(SecaoAjuda a, String id) {
        Visual.naEdt(() -> a.clicarNoTopicoDaArvore(id));
        return 0;
    }

    // ---------------------------------------------------------------------------------------------
    // Estrutura e navegação
    // ---------------------------------------------------------------------------------------------

    @Test
    void abreNoPrimeiroTopicoComSoACategoriaDeleAbertaEAListaRapidaNoTopo() {
        SecaoAjuda a = ajuda();
        assertEquals("conectar", Visual.naEdt(a::topicoAberto));
        List<String> arvore = Visual.naEdt(a::linhasDaArvore);
        assertEquals("Onde fica...? (lista rápida)", arvore.get(0));
        assertEquals("[-] Comece aqui", arvore.get(1));
        assertEquals(List.of("    Conectar a um servidor", "    Adicionar servidor", "    Agente e Claude"), arvore.subList(2, 5));
        assertEquals(List.of("[+] Projetos", "[+] Arquivos e backups", "[+] Servidor", "[+] Ajustes e aparência", "[+] Quando algo dá errado"), arvore.subList(5, arvore.size()),
            "as outras categorias ficam recolhidas");
    }

    @Test
    void abrirUmTopicoDeOutraCategoriaRecolheAAnteriorEExpandeANova() {
        SecaoAjuda a = ajuda();
        clicar(a, "sem-conexao");
        List<String> arvore = Visual.naEdt(a::linhasDaArvore);
        assertTrue(arvore.contains("[+] Comece aqui"), arvore.toString());
        assertTrue(arvore.contains("[-] Quando algo dá errado"), arvore.toString());
        assertEquals("sem-conexao", Visual.naEdt(a::topicoAberto));
        assertTrue(arvore.contains("    Sem conexão") && arvore.contains("    Chave recusada"), arvore.toString());
    }

    @Test
    void enterNaArvoreAbreOTopicoSelecionado() {
        SecaoAjuda a = ajuda();
        Visual.naEdt(() -> {
            a.clicarNoTopicoDaArvore("agente");
        });
        // setas movem a seleção sem abrir; Enter abre
        Visual.naEdt(() -> a.arvore().setSelectionRow(a.arvore().getRowForPath(a.arvore().getSelectionPath()) - 1));
        assertEquals("agente", Visual.naEdt(a::topicoAberto), "só mover a seleção não abre");
        Visual.naEdt(a::apertarEnterNaArvore);
        assertEquals("adicionar-servidor", Visual.naEdt(a::topicoAberto), "Enter abre o tópico selecionado");
    }

    @Test
    void voltarEAvancarAndamPeloHistoricoELiberamOsBotoes() {
        SecaoAjuda a = ajuda();
        assertFalse(Visual.naEdt(() -> a.botaoVoltar().isEnabled()));
        clicar(a, "projetos");
        clicar(a, "backups");
        assertTrue(Visual.naEdt(() -> a.botaoVoltar().isEnabled()));
        Visual.naEdt(() -> a.botaoVoltar().doClick());
        assertEquals("projetos", Visual.naEdt(a::topicoAberto));
        assertTrue(Visual.naEdt(() -> a.botaoAvancar().isEnabled()));
        Visual.naEdt(() -> a.botaoAvancar().doClick());
        assertEquals("backups", Visual.naEdt(a::topicoAberto));
        assertFalse(Visual.naEdt(() -> a.botaoAvancar().isEnabled()));
        Visual.naEdt(() -> a.botaoVoltar().doClick());
        Visual.naEdt(() -> a.botaoVoltar().doClick());
        assertEquals("conectar", Visual.naEdt(a::topicoAberto));
        assertEquals(List.of("conectar", "projetos", "backups"), Visual.naEdt(() -> a.historico().lidos()));
    }

    @Test
    void oUltimoLidoApareceEmComeceAquiParaContinuar() {
        SecaoAjuda a = ajuda();
        assertFalse(Visual.naEdt(a::linhasDaArvore).stream().anyMatch(l -> l.contains("Continuar")), "nada lido antes: nada a continuar");
        clicar(a, "terminal");
        List<String> arvore = Visual.naEdt(a::linhasDaArvore);
        assertTrue(arvore.stream().anyMatch(l -> l.contains("Continuar")) || Visual.naEdt(() -> a.historico().maisRecenteQueNaoEOAtual()).equals("conectar"), arvore.toString());
        // com "Comece aqui" recolhida a linha não aparece na lista de linhas abertas: abre a categoria e confere
        Visual.naEdt(() -> a.arvore().expandRow(1));
        assertTrue(Visual.naEdt(() -> {
            for (int i = 0; i < a.arvore().getRowCount(); i++) {
                if (String.valueOf(a.arvore().getPathForRow(i).getLastPathComponent()).contains("Continuar: Conectar a um servidor")) {
                    return true;
                }
            }
            return false;
        }), "o tópico lido antes aparece em Comece aqui como Continuar");
    }

    // ---------------------------------------------------------------------------------------------
    // Busca
    // ---------------------------------------------------------------------------------------------

    @Test
    void aBuscaFiltraOsTopicosSemAcentoEMostraSoAsCategoriasComResultado() {
        SecaoAjuda a = ajuda();
        Visual.naEdt(() -> a.campoDeBusca().setText("conexao"));
        List<String> arvore = Visual.naEdt(a::linhasDaArvore);
        assertTrue(arvore.contains("    Sem conexão"), arvore.toString());
        assertTrue(arvore.stream().noneMatch(l -> l.contains("Tema e abas")), "tópico sem a palavra some: " + arvore);
        assertTrue(arvore.stream().allMatch(l -> l.startsWith("[-]") || l.startsWith("    ") || l.startsWith("Onde")), "com busca, as categorias que têm resultado ficam abertas: " + arvore);
        Visual.naEdt(() -> a.campoDeBusca().setText("zzzzz"));
        assertEquals(List.of(), Visual.naEdt(a::linhasDaArvore), "sem resultado a árvore fica vazia");
        Visual.naEdt(() -> a.campoDeBusca().setText(""));
        assertTrue(Visual.naEdt(a::linhasDaArvore).size() > 8, "limpar a busca devolve tudo");
    }

    @Test
    void aBuscaDestacaOTrechoNoTopicoAberto() {
        SecaoAjuda a = ajuda();
        clicar(a, "sem-conexao");
        Visual.naEdt(() -> a.campoDeBusca().setText("faixa amarela"));
        assertTrue(Visual.naEdt(() -> a.vistaAtual().destaques()) >= 2, "as duas palavras foram marcadas no texto");
        Visual.naEdt(() -> a.campoDeBusca().setText(""));
        assertEquals(0, Visual.naEdt(() -> a.vistaAtual().destaques()), "sem busca não há marca");
    }

    @Test
    void sePeloMenosOTrechoEstaEmMaisDetalhesAbreAsecaoParaMostrar() {
        SecaoAjuda a = ajuda();
        clicar(a, "token-recusado");
        Visual.naEdt(() -> a.campoDeBusca().setText("aguardando"));
        var vista = Visual.naEdt(a::vistaAtual);
        assertTrue(vista.destaquesNosDetalhes() > 0, "a palavra só aparece em Mais detalhes");
        assertTrue(Visual.naEdt(() -> vista.detalhes().aberta()), "a seção abriu sozinha para mostrar o trecho");
    }

    // ---------------------------------------------------------------------------------------------
    // Onde fica, links e atalhos
    // ---------------------------------------------------------------------------------------------

    @Test
    void aListaRapidaOndeFicaMostraOCaminhoExatoEOBotaoIrAbreATela() {
        SecaoAjuda a = ajuda();
        Visual.naEdt(() -> a.abrir(SecaoAjuda.ID_DO_INDICE));
        String texto = textoDe(a);
        assertTrue(texto.contains("Servidor > Resumo > Registro do servidor"), texto);
        assertTrue(texto.contains("Menu Arquivo") || texto.contains("Aba Projetos"), texto);
        // o botão Abrir da linha do Registro do servidor abre a aba Resumo
        Visual.naEdt(() -> painel.navegacao().mostrarServidor(demo.lab(), TelaServidor.Aba.TERMINAL));
        Visual.naEdt(() -> painel.mostrarAjustes(Secao.AJUDA));
        AbstractButton ir = botoes(a).stream().filter(b -> "Abrir".equals(b.getText())).findFirst().orElseThrow();
        Visual.naEdt(() -> ir.doClick());
        assertFalse(Visual.naEdt(() -> painel.navegacao().emAjustes()), "abriu a tela, saiu dos Ajustes");
    }

    @Test
    void todoLinkDaAjudaAbreATelaQueCita() {
        for (Destino d : Destino.values()) {
            Visual.naEdt(() -> painel.navegacao().mostrarServidor(demo.lab()));
            boolean ok = Visual.naEdt(() -> new AjustesDoApp(tema, preferencias, painel, new CopiadorDeTexto(() -> area), avisos, () -> menu.atalhosDoMenu()).irPara(d));
            assertTrue(ok, d + " não abriu");
            switch (d) {
                case SERVIDORES -> assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));
                case RESUMO -> assertAba(TelaServidor.Aba.RESUMO);
                case SEGURANCA -> assertAba(TelaServidor.Aba.SEGURANCA);
                case CONTAINERS -> assertAba(TelaServidor.Aba.CONTAINERS);
                case PROCESSOS -> assertAba(TelaServidor.Aba.PROCESSOS);
                case MAQUINA -> assertAba(TelaServidor.Aba.MAQUINA);
                case PROJETOS -> assertAba(TelaServidor.Aba.PROJETOS);
                case ARQUIVOS -> assertAba(TelaServidor.Aba.ARQUIVOS);
                case TERMINAL -> assertAba(TelaServidor.Aba.TERMINAL);
                case CLOUDFLARE -> assertAba(TelaServidor.Aba.CLOUDFLARE);
                case AJUSTES_APARENCIA -> assertSecao(Secao.APARENCIA);
                case AJUSTES_NAVEGACAO -> assertSecao(Secao.NAVEGACAO);
                case AJUSTES_TERMINAL -> assertSecao(Secao.TERMINAL);
                case AJUSTES_SOBRE -> assertSecao(Secao.SOBRE);
            }
        }
    }

    private void assertAba(TelaServidor.Aba aba) {
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
        assertEquals(aba, Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID).abaAtual()));
    }

    private void assertSecao(Secao s) {
        assertTrue(Visual.naEdt(() -> painel.navegacao().emAjustes()));
        assertEquals(s, Visual.naEdt(() -> ajustes.secaoAtual()));
    }

    @Test
    void osLinksDosTopicosSaoBotoesQueAbremATelaESoAbrem() {
        SecaoAjuda a = ajuda();
        clicar(a, "servidor");
        AbstractButton ir = botoes(a).stream().filter(b -> "Ir para Segurança".equals(b.getText())).findFirst().orElseThrow();
        Visual.naEdt(() -> ir.doClick());
        assertAba(TelaServidor.Aba.SEGURANCA);
        assertNull(Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID).projetoAberto()), "abrir uma tela não abre projeto nem diálogo");
    }

    @Test
    void oVejaTambemAbreOutroTopicoEEntraNoHistorico() {
        SecaoAjuda a = ajuda();
        clicar(a, "conectar");
        AbstractButton veja = botoes(a).stream().filter(b -> "Adicionar servidor".equals(b.getText())).findFirst().orElseThrow();
        Visual.naEdt(() -> veja.doClick());
        assertEquals("adicionar-servidor", Visual.naEdt(a::topicoAberto));
        assertEquals("conectar", Visual.naEdt(() -> a.historico().anterior()));
    }

    @Test
    void aTabelaDeAtalhosSaiDoMenuDeVerdade() {
        SecaoAjuda a = ajuda();
        clicar(a, "atalhos");
        List<AtalhoDoMenu> doMenu = Visual.naEdt(() -> menu.atalhosDoMenu());
        assertTrue(doMenu.size() >= 12, "atalhos do menu: " + doMenu);
        String texto = textoDe(a);
        for (AtalhoDoMenu m : doMenu) {
            assertTrue(texto.contains(m.tecla()) && texto.contains(m.descricao()), "a tabela tem " + m);
        }
        assertTrue(doMenu.stream().anyMatch(m -> m.chave().equals("ajustes") && m.tecla().endsWith("+,")), doMenu.toString());
        assertTrue(doMenu.stream().anyMatch(m -> m.chave().equals("ajuda")), "a própria Ajuda tem atalho");
    }

    /** A tabela mostrava Cmd+Shift+N (Novo projeto) mesmo na versão padrão, onde o Novo projeto não existe. */
    @Test
    void aTabelaDeAtalhosNaoTemONovoProjetoSemAsAcoesDeEscrita() {
        String antes = System.getProperty(br.com.labdesktop.ui.telas.FluxosDeProjeto.PROPRIEDADE_ACOES);
        try {
            System.setProperty(br.com.labdesktop.ui.telas.FluxosDeProjeto.PROPRIEDADE_ACOES, "false");
            assertFalse(Visual.naEdt(() -> menu.atalhosDoMenu()).stream().anyMatch(m -> m.chave().equals("novo-projeto")), "com as ações desligadas: sem Novo projeto");
            System.setProperty(br.com.labdesktop.ui.telas.FluxosDeProjeto.PROPRIEDADE_ACOES, "true");
            assertTrue(Visual.naEdt(() -> menu.atalhosDoMenu()).stream().anyMatch(m -> m.chave().equals("novo-projeto")), "com as ações ligadas, a tecla aparece");
        } finally {
            if (antes == null) {
                System.clearProperty(br.com.labdesktop.ui.telas.FluxosDeProjeto.PROPRIEDADE_ACOES);
            } else {
                System.setProperty(br.com.labdesktop.ui.telas.FluxosDeProjeto.PROPRIEDADE_ACOES, antes);
            }
        }
    }

    /** A frase fixa no topo dos tópicos de botões de escrita, VISÍVEL sem abrir "Mais detalhes". */
    @Test
    void osTopicosDeBotoesDeEscritaDizemNoTopoQueELesSoAparecemComAsAcoesLigadas() {
        SecaoAjuda a = ajuda();
        for (String id : List.of("projetos", "publicar", "arquivos", "logs-e-consumo")) {
            Visual.naEdt(() -> a.abrir(id));
            Visual.naEdt(() -> SemTransbordo.achados(painel));
            assertFalse(Visual.naEdt(() -> a.vistaAtual().detalhes() != null && a.vistaAtual().detalhes().aberta()), id + ": os detalhes estão recolhidos");
            assertTrue(textoDe(a).contains(GuiaDeAjuda.FRASE_DAS_ACOES), id + " tem a frase fixa no topo");
        }
        Visual.naEdt(() -> a.abrir("conectar"));
        assertFalse(textoDe(a).contains(GuiaDeAjuda.FRASE_DAS_ACOES), "só os tópicos de escrita levam a frase");
    }

    @Test
    void todoAtalhoCitadoNaAjudaExisteNoMenuEmCadaSistema() {
        List<String> citados = new ArrayList<>();
        GuiaDeAjuda.doJar().topicos().forEach(t -> citados.addAll(GuiaDeAjuda.chavesCitadas(t)));
        assertFalse(citados.isEmpty());
        for (SistemaOperacional so : SistemaOperacional.values()) {
            MenuApp m = Visual.naEdt(() -> new MenuApp(null, painel, tema, preferencias, () -> { }, () -> { }, new ComportamentoDaJanela(so, () -> { })));
            List<String> chaves = Visual.naEdt(() -> m.atalhosDoMenu().stream().map(AtalhoDoMenu::chave).toList());
            for (String c : citados) {
                assertTrue(chaves.contains(c), "a Ajuda cita {atalho:" + c + "}, que não existe no menu (" + so + "): " + chaves);
            }
        }
    }

    @Test
    void ajudaTemF1NoWindowsELinuxECmdInterrogacaoNoMac() {
        for (SistemaOperacional so : new SistemaOperacional[] {SistemaOperacional.WINDOWS, SistemaOperacional.LINUX, SistemaOperacional.MAC}) {
            MenuApp m = Visual.naEdt(() -> new MenuApp(null, painel, tema, preferencias, () -> { }, () -> { }, new ComportamentoDaJanela(so, () -> { })));
            String tecla = Visual.naEdt(() -> m.atalhosDoMenu().stream().filter(x -> x.chave().equals("ajuda")).findFirst().orElseThrow().tecla());
            assertEquals(so == SistemaOperacional.MAC ? "Cmd+Shift+/" : "F1", tecla, so.toString());
        }
        Visual.naEdt(() -> menu.itemAjuda().doClick());
        assertTrue(Visual.naEdt(() -> painel.navegacao().emAjustes()));
        assertEquals(Secao.AJUDA, Visual.naEdt(() -> ajustes.secaoAtual()));
    }

    @Test
    void oBotaoCopiarCopiaOTopicoComoTextoSimples() throws Exception {
        SecaoAjuda a = ajuda();
        clicar(a, "pedir-ajuda");
        AbstractButton copiar = botoes(a).stream().filter(b -> b.getText() != null && b.getText().startsWith("Copiar")).findFirst().orElseThrow();
        Visual.naEdt(() -> copiar.doClick());
        String copiado = (String) area.getData(DataFlavor.stringFlavor);
        assertTrue(copiado.startsWith("Como copio as informações do app para pedir ajuda?"), copiado);
        assertTrue(copiado.contains("Resumo rápido:") && copiado.contains("1. Abra Ajustes e vá em Sobre"), copiado);
    }

    // ---------------------------------------------------------------------------------------------
    // Sem transbordo
    // ---------------------------------------------------------------------------------------------

    private static final int[] LARGURAS = {900, 1100, 1440};
    private static final int[] ALTURAS = {600, 700, 860};

    @Test
    void emNenhumaLarguraEAlturaNadaTransbordaECadaPainelRolaPorDentro() {
        List<String> ruins = new ArrayList<>();
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.naEdt(() -> {
                tema.definirEscolha(t);
                SwingUtilities.updateComponentTreeUI(painel);
            });
            SecaoAjuda a = ajuda();
            for (int l : LARGURAS) {
                for (int h : ALTURAS) {
                    Visual.naEdt(() -> painel.setSize(l, h));
                    List<String> topicos = new ArrayList<>(Visual.naEdt(() -> a.guia().topicos().stream().map(TopicoDeAjuda::id).toList()));
                    topicos.add(SecaoAjuda.ID_DO_INDICE);
                    for (String id : topicos) {
                        Visual.naEdt(() -> a.abrir(id));
                        Visual.naEdt(() -> a.campoDeBusca().setText(id.equals("atalhos") ? "" : ""));
                        for (String achado : Visual.naEdt(() -> SemTransbordo.achados(painel))) {
                            ruins.add(t + " " + l + "x" + h + " " + id + ": " + achado);
                        }
                        assertEquals(l - 216 < SecaoAjuda.LARGURA_DO_MODO_ESTREITO, Visual.naEdt(a::modoEstreito),
                            "o modo estreito vale abaixo de " + SecaoAjuda.LARGURA_DO_MODO_ESTREITO + " px de painel (largura " + l + ")");
                    }
                    assertNull(Visual.naEdt(() -> SwingUtilities.getAncestorOfClass(JScrollPane.class, a)), "a janela da Ajuda nunca rola: nenhuma rolagem em volta dela");
                }
            }
        }
        assertTrue(ruins.isEmpty(), ruins.size() + " transbordos (os 15 primeiros):\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }

    @Test
    void cadaTopicoCabeNumaTelaComOsDetalhesRecolhidos() {
        SecaoAjuda a = ajuda();
        Visual.naEdt(() -> painel.setSize(1100, 700));
        List<String> altos = new ArrayList<>();
        for (TopicoDeAjuda t : Visual.naEdt(() -> a.guia().topicos())) {
            Visual.naEdt(() -> a.abrir(t.id()));
            Visual.naEdt(() -> SemTransbordo.achados(painel)); // faz o layout
            int altura = Visual.naEdt(() -> a.vistaAtual().componente().getPreferredSize().height);
            int disponivel = Visual.naEdt(() -> ((javax.swing.JViewport) SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, a.vistaAtual().componente())).getHeight());
            if (altura > disponivel) {
                altos.add(t.id() + ": pede " + altura + " px e a janela do texto tem " + disponivel + " px");
            }
        }
        assertTrue(altos.isEmpty(), "tópicos que não cabem sem rolar numa janela de 1100 por 700: " + altos);
    }

    @Test
    void emLarguraPequenaAArvoreViraSeletorDeCategoriaETopico() {
        SecaoAjuda a = ajuda();
        Visual.naEdt(() -> painel.setSize(900, 600));
        Visual.naEdt(() -> SemTransbordo.achados(painel));
        assertTrue(Visual.naEdt(a::modoEstreito));
        assertFalse(Visual.naEdt(() -> a.rolagemDaArvore().isShowing() || a.rolagemDaArvore().getParent() != null && a.rolagemDaArvore().getParent().isAncestorOf(a.rolagemDaArvore())
            && a.rolagemDaArvore().getParent().getComponentCount() > 0 && java.util.Arrays.asList(a.rolagemDaArvore().getParent().getComponents()).contains(a.rolagemDaArvore())),
            "a árvore saiu de cena");
        assertEquals("Onde fica...? (lista rápida)", String.valueOf(Visual.naEdt(() -> a.seletorDeCategoria().getItemAt(0))));
        Visual.naEdt(() -> a.seletorDeCategoria().setSelectedIndex(2)); // Projetos
        assertEquals("projetos", Visual.naEdt(a::topicoAberto), "escolher a categoria abre o primeiro tópico dela");
        assertEquals(4, Visual.naEdt(() -> a.seletorDeTopico().getItemCount()), "projetos, publicar, git e git-do-projeto");
        Visual.naEdt(() -> a.seletorDeTopico().setSelectedIndex(1));
        assertEquals("publicar", Visual.naEdt(a::topicoAberto));
        Visual.naEdt(() -> painel.setSize(1440, 860));
        Visual.naEdt(() -> SemTransbordo.achados(painel));
        assertFalse(Visual.naEdt(a::modoEstreito));
    }

    @Test
    void oSeletorQueEstavaForaDaJanelaAcompanhaOTemaTrocadoEmModoLargo() {
        SecaoAjuda a = ajuda();
        Visual.naEdt(() -> painel.setSize(1440, 860));
        Visual.naEdt(() -> SemTransbordo.achados(painel)); // modo largo: o seletor está fora da janela
        Visual.naEdt(() -> {
            tema.definirEscolha(Tema.ESCURO);
            SwingUtilities.updateComponentTreeUI(painel); // a troca de tema só chega ao que está na janela
        });
        Visual.naEdt(() -> painel.setSize(900, 600));
        Visual.naEdt(() -> SemTransbordo.achados(painel)); // modo estreito: o seletor entra
        assertTrue(Visual.naEdt(a::modoEstreito));
        assertEquals(Visual.naEdt(() -> new javax.swing.JComboBox<String>().getBackground()), Visual.naEdt(() -> a.seletorDeCategoria().getBackground()),
            "o seletor tem as cores do tema de agora, não as de antes da troca");
    }

    @Test
    void todaAAjudaEnxergaSeNosDoisTemasEGeraOsPngsNasTresTelas() {
        List<String> ruins = new ArrayList<>();
        int[][] telas = {{900, 600}, {1100, 700}, {1440, 860}};
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.naEdt(() -> {
                tema.definirEscolha(t);
                SwingUtilities.updateComponentTreeUI(painel);
            });
            SecaoAjuda a = ajuda();
            for (int[] tela : telas) {
                Visual.naEdt(() -> painel.setSize(tela[0], tela[1]));
                for (String id : List.of("conectar", "atalhos", SecaoAjuda.ID_DO_INDICE, "sem-conexao")) {
                    Visual.naEdt(() -> a.abrir(id));
                    if (id.equals("sem-conexao")) {
                        Visual.naEdt(() -> a.campoDeBusca().setText("faixa"));
                    } else {
                        Visual.naEdt(() -> a.campoDeBusca().setText(""));
                    }
                    for (var m : Visual.naEdt(() -> AuditoriaDeCores.auditar(painel, tela[0], tela[1]))) {
                        if (m.falhou()) {
                            ruins.add(t + " " + tela[0] + "x" + tela[1] + " " + id + " | " + m);
                        }
                    }
                    Visual.salvar(Visual.renderizar(painel, tela[0], tela[1]), "ajuda-" + id + "-" + tela[0] + "x" + tela[1] + "-" + t.name().toLowerCase());
                    if (id.equals("conectar") && tela[0] == 1100) { // galeria do README
                        Visual.salvarNaGaleria(Visual.renderizar(painel, tela[0], tela[1]), "ajuda-" + t.name().toLowerCase(), "Lab Desktop", t == Tema.ESCURO, MolduraDeJanela.Tipo.JANELA);
                    }
                }
            }
        }
        assertTrue(ruins.isEmpty(), "controles da Ajuda abaixo do limite de contraste:\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }

    // ---------------------------------------------------------------------------------------------

    private String textoDe(Component raiz) {
        StringBuilder b = new StringBuilder();
        Visual.naEdt(() -> juntar(raiz, b));
        return b.toString();
    }

    private static void juntar(Component c, StringBuilder b) {
        if (c instanceof JTextComponent t) {
            b.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            b.append(l.getText()).append('\n');
        } else if (c instanceof AbstractButton a && a.getText() != null) {
            b.append(a.getText()).append('\n');
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                juntar(f, b);
            }
        }
    }

    private List<AbstractButton> botoes(SecaoAjuda a) {
        List<AbstractButton> lista = new ArrayList<>();
        Visual.naEdt(() -> coletar(a, lista));
        return lista;
    }

    private static void coletar(Component c, List<AbstractButton> lista) {
        if (c instanceof AbstractButton b && c.isVisible()) {
            lista.add(b);
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                coletar(f, lista);
            }
        }
    }

    @SuppressWarnings("unused")
    private void esperar() {
        Espera.dormir(1);
    }
}
