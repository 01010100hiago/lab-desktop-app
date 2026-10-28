package br.com.labdesktop.ui;

import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.componentes.Teclas;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.ButtonGroup;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/**
 * O menu do app (a barra de menus do macOS, por {@code apple.laf.useScreenMenuBar}). Regra de ouro (ARQUITETURA 7.4):
 * Cmd é do app e o resto é do terminal, e os atalhos nativos do JediTerm (Cmd+C, Cmd+V, Cmd+K, Cmd+Setas) NÃO viram
 * item de menu, para o menu da tela do macOS não engolir o atalho. Aqui só entram Cmd+N, Cmd+Shift+N (Novo projeto), Cmd+R, Cmd+1 a
 * Cmd+6, Cmd+W, Cmd+M e Cmd+Shift+L (bloquear o cofre); "Cmd" é a tecla de comando do sistema (Ctrl no Windows e no Linux).
 *
 * <p>"Servidor" age sobre o servidor em foco (o da lista na tela, ou o único cadastrado) e só habilita o que faz
 * sentido para o estado dele: o "Configurar 2FA" daqui vale para o servidor selecionado (a tela Servidor tem o mesmo botão).
 */
public final class MenuApp {

    private final JFrame janela;
    private final PainelPrincipal painel;
    private final GerenciadorTema tema;
    private final PreferenciasAtuais preferencias;
    private final Runnable bloquearCofre;
    private final Runnable mostrarSobre;
    private final Map<Item, JMenuItem> itensDoServidor = new EnumMap<>(Item.class);
    private final JMenu menuServidor = new JMenu("Servidor");
    private JMenuItem itemSair;
    private JMenuItem itemAumentar;
    private JMenuItem itemDiminuir;
    private JMenuItem itemNormal;
    private JMenuItem itemNovoProjeto;
    private JMenuItem itemUltimoProjeto;
    private br.com.labdesktop.servidor.Inscricao inscricaoDoEstado;
    private JMenuItem itemVoltarAosProjetos;
    private final JMenuBar barra = new JMenuBar();
    private final Map<Tema, JRadioButtonMenuItem> itensDeTema = new EnumMap<>(Tema.class);

    private final ComportamentoDaJanela comportamento;

    public MenuApp(JFrame janela, PainelPrincipal painel, GerenciadorTema tema, PreferenciasAtuais preferencias,
                   Runnable bloquearCofre, Runnable mostrarSobre) {
        this(janela, painel, tema, preferencias, bloquearCofre, mostrarSobre, ComportamentoDaJanela.padrao());
    }

    public MenuApp(JFrame janela, PainelPrincipal painel, GerenciadorTema tema, PreferenciasAtuais preferencias,
                   Runnable bloquearCofre, Runnable mostrarSobre, ComportamentoDaJanela comportamento) {
        this.comportamento = comportamento;
        this.janela = janela;
        this.painel = painel;
        this.tema = tema;
        this.preferencias = preferencias;
        this.bloquearCofre = bloquearCofre;
        this.mostrarSobre = mostrarSobre;
        montar();
    }

    /** A barra pronta para {@code JFrame.setJMenuBar}. */
    public JMenuBar barra() {
        return barra;
    }

    /** A tecla de comando do sistema (Cmd no Mac, Ctrl nos outros): {@link Teclas#comando()}, decidida pelo sistema e não pelo Toolkit. */
    private static int atalho() {
        return Teclas.comando();
    }

    private static JMenuItem item(String texto, KeyStroke tecla, Runnable acao) {
        JMenuItem i = new JMenuItem(texto);
        i.putClientProperty("html.disable", Boolean.TRUE);
        if (tecla != null) {
            i.setAccelerator(tecla);
        }
        i.addActionListener(e -> acao.run());
        return i;
    }

    private void montar() {
        // --- Cofre ---
        JMenu cofre = new JMenu("Cofre");
        cofre.add(item("Bloquear o cofre", KeyStroke.getKeyStroke(KeyEvent.VK_L, atalho() | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
            bloquearCofre));
        cofre.addSeparator();
        cofre.add(item("Sobre o Lab Desktop", null, mostrarSobre));
        if (comportamento.temItemSair()) {
            // Windows e Linux: não há o Cmd+Q do menu do app. No Windows o atalho do sistema é Alt+F4 (fechar a janela também sai); no Linux, Ctrl+Q.
            cofre.addSeparator();
            itemSair = item("Sair", comportamento.sistema() == br.com.labdesktop.plataforma.SistemaOperacional.LINUX ? KeyStroke.getKeyStroke(KeyEvent.VK_Q, atalho()) : null,
                comportamento.sair());
            cofre.add(itemSair);
        }
        barra.add(cofre);

        // --- Arquivo ---
        JMenu arquivo = new JMenu("Arquivo");
        // Cmd+Shift+N no Mac, Ctrl+Shift+N nos outros (a tecla de comando do sistema; Cmd+N é "Adicionar servidor")
        itemNovoProjeto = item("Novo projeto…", KeyStroke.getKeyStroke(KeyEvent.VK_N, atalho() | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
            () -> painel.novoProjetoNoServidorEmFoco());
        arquivo.add(itemNovoProjeto);
        arquivo.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                atualizarHabilitacaoDoNovoProjeto();
            }

            @Override
            public void menuDeselected(MenuEvent e) { }

            @Override
            public void menuCanceled(MenuEvent e) { }
        });
        barra.add(arquivo);

        // --- Servidor ---
        menuServidor.add(item("Adicionar servidor…", KeyStroke.getKeyStroke(KeyEvent.VK_N, atalho()),
            () -> painel.acoes().adicionarServidor()));
        menuServidor.addSeparator();
        for (Item i : Item.values()) {
            if (i == Item.EDITAR) {
                menuServidor.addSeparator();
            }
            JMenuItem mi = item(i.rotulo(), i == Item.ATUALIZAR ? KeyStroke.getKeyStroke(KeyEvent.VK_R, atalho()) : null,
                () -> executarNoServidorEmFoco(i));
            itensDoServidor.put(i, mi);
            menuServidor.add(mi);
        }
        menuServidor.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                atualizarHabilitacao();
            }

            @Override
            public void menuDeselected(MenuEvent e) { }

            @Override
            public void menuCanceled(MenuEvent e) { }
        });
        barra.add(menuServidor);

        // --- Visualizar ---
        JMenu ver = new JMenu("Visualizar");
        ver.add(item("Servidores", KeyStroke.getKeyStroke(KeyEvent.VK_2, atalho()), painel::mostrarServidores));
        ver.add(item("Resumo do servidor", KeyStroke.getKeyStroke(KeyEvent.VK_3, atalho()), painel::mostrarResumo));
        ver.add(item("Projetos do servidor", KeyStroke.getKeyStroke(KeyEvent.VK_1, atalho()), painel::mostrarProjetos));
        ver.add(item("Arquivos do servidor", KeyStroke.getKeyStroke(KeyEvent.VK_5, atalho()), painel::mostrarArquivos));
        ver.add(item("Terminal do servidor", KeyStroke.getKeyStroke(KeyEvent.VK_6, atalho()), painel::mostrarTerminal));
        itemUltimoProjeto = item("Último projeto aberto", KeyStroke.getKeyStroke(KeyEvent.VK_4, atalho()), painel::mostrarUltimoProjeto);
        ver.add(itemUltimoProjeto);
        itemVoltarAosProjetos = item("Voltar à lista de projetos", KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, atalho()),
            painel::mostrarProjetos);
        ver.add(itemVoltarAosProjetos);
        ver.addSeparator();
        itemAumentar = item("Aumentar texto", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, atalho()), () -> mudarTexto(Fontes.maior(Fontes.percentual())));
        itemDiminuir = item("Diminuir texto", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, atalho()), () -> mudarTexto(Fontes.menor(Fontes.percentual())));
        itemNormal = item("Tamanho normal", KeyStroke.getKeyStroke(KeyEvent.VK_0, atalho()), () -> mudarTexto(Fontes.ESCALAS[0]));
        ver.add(itemAumentar);
        ver.add(itemDiminuir);
        ver.add(itemNormal);
        instalarAtalhoDoMais();
        atualizarItensDoTexto();
        ver.addSeparator();
        JMenu aparencia = new JMenu("Aparência");
        ButtonGroup grupo = new ButtonGroup();
        for (Object[] par : new Object[][] {{Tema.SISTEMA, "Seguir o sistema"}, {Tema.CLARO, "Claro"}, {Tema.ESCURO, "Escuro"}}) {
            Tema t = (Tema) par[0];
            JRadioButtonMenuItem r = new JRadioButtonMenuItem((String) par[1], t == tema.escolha());
            r.putClientProperty("html.disable", Boolean.TRUE);
            r.addActionListener(e -> trocarTema(t));
            grupo.add(r);
            aparencia.add(r);
            itensDeTema.put(t, r);
        }
        ver.add(aparencia);
        ver.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                atualizarHabilitacaoDoProjeto();
            }

            @Override
            public void menuDeselected(MenuEvent e) { }

            @Override
            public void menuCanceled(MenuEvent e) { }
        });
        barra.add(ver);

        // --- Janela ---
        JMenu menuJanela = new JMenu("Janela");
        menuJanela.add(item("Minimizar", KeyStroke.getKeyStroke(KeyEvent.VK_M, atalho()),
            () -> janela.setExtendedState(janela.getExtendedState() | JFrame.ICONIFIED)));
        if (comportamento.fecharEscondeAJanela()) {
            // Só onde fechar a janela é esconder (Mac): Cmd+W. Fora do Mac Ctrl+W NÃO existe (fechar a janela sai do app; quem quer sair usa "Sair" e a pergunta de pendências).
            menuJanela.add(item("Fechar a janela", KeyStroke.getKeyStroke(KeyEvent.VK_W, atalho()),
                () -> janela.dispatchEvent(new java.awt.event.WindowEvent(janela, java.awt.event.WindowEvent.WINDOW_CLOSING))));
        }
        barra.add(menuJanela);
        atualizarHabilitacao();
        atualizarHabilitacaoDoNovoProjeto();
        // O atalho de um item desabilitado não faz nada: a habilitação acompanha o servidor em foco (troca de tela) e o estado dele (o agente
        // que passa a responder), e não só o abrir do menu.
        painel.navegacao().aoMudar(id -> religarAoServidorEmFoco());
        religarAoServidorEmFoco();
    }

    /** Passa a observar o estado do servidor em foco (e solta o do anterior); cada mudança recalcula o que o menu oferece. */
    private void religarAoServidorEmFoco() {
        ServidorLab lab = painel.servidorEmFoco();
        if (inscricaoDoEstado != null) {
            inscricaoDoEstado.close();
            inscricaoDoEstado = null;
        }
        atualizarHabilitacao();
        atualizarHabilitacaoDoNovoProjeto();
        if (lab != null) {
            inscricaoDoEstado = lab.observarEstado(e -> {
                atualizarHabilitacao();
                atualizarHabilitacaoDoNovoProjeto();
            });
        }
    }

    private void trocarTema(Tema t) {
        tema.definirEscolha(t);
        preferencias.atualizar(p -> p.comTema(t));
        tema.conferirSistema();
    }

    private void executarNoServidorEmFoco(Item item) {
        ServidorLab lab = painel.servidorEmFoco();
        if (lab != null) {
            MenuDoServidor.executar(item, lab, painel.acoes());
        }
    }

    /** Habilita só o que vale para o servidor em foco, agora. */
    public void atualizarHabilitacao() {
        ServidorLab lab = painel.servidorEmFoco();
        List<Item> validos = lab == null ? List.of() : MenuDoServidor.itensPara(lab.estado());
        itensDoServidor.forEach((i, mi) -> mi.setEnabled(validos.contains(i)));
    }

    /**
     * Muda o tamanho do texto de toda a interface (100, 115 ou 130 %): refaz as fontes na hora, lembra nas preferências e
     * ajusta o que o menu oferece.
     */
    void mudarTexto(int porcento) {
        Fontes.definirPercentual(porcento);
        tema.reaplicarFontes();
        int atual = Fontes.percentual();
        preferencias.atualizar(p -> p.comEscalaTexto(atual));
        atualizarItensDoTexto();
    }

    private void atualizarItensDoTexto() {
        int p = Fontes.percentual();
        itemAumentar.setEnabled(p < Fontes.ESCALAS[Fontes.ESCALAS.length - 1]);
        itemDiminuir.setEnabled(p > Fontes.ESCALAS[0]);
        itemNormal.setEnabled(p != Fontes.ESCALAS[0]);
    }

    /**
     * Cmd e o sinal de mais: o item do menu usa Cmd+= (a mesma tecla, sem o Shift), e aqui o "+" de verdade (Cmd+Shift+=, ou o
     * "+" do teclado numérico) faz o mesmo, como nos outros apps do Mac.
     */
    private void instalarAtalhoDoMais() {
        if (janela == null) {
            return;
        }
        var mapa = janela.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        mapa.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, atalho()), "aumentar-texto");
        mapa.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, atalho() | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "aumentar-texto");
        mapa.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, atalho()), "aumentar-texto");
        janela.getRootPane().getActionMap().put("aumentar-texto", new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                mudarTexto(Fontes.maior(Fontes.percentual()));
            }
        });
    }

    /** "Novo projeto…" só com as ações liberadas e um servidor em foco que cria projeto (o agente do lab, ou este computador). */
    public void atualizarHabilitacaoDoNovoProjeto() {
        itemNovoProjeto.setEnabled(painel.podeCriarProjetoNoServidorEmFoco());
    }

    /** "Último projeto aberto" só com um projeto para reabrir; "Voltar à lista" só com uma página de projeto aberta. */
    public void atualizarHabilitacaoDoProjeto() {
        itemUltimoProjeto.setEnabled(painel.temUltimoProjeto());
        itemVoltarAosProjetos.setEnabled(painel.temProjetoAberto());
    }

    // ---- para os testes ----

    /** O item "Sair" (só no Windows e no Linux); {@code null} no Mac. */
    public JMenuItem itemSair() {
        return itemSair;
    }

    public JMenuItem itemAumentarTexto() {
        return itemAumentar;
    }

    public JMenuItem itemDiminuirTexto() {
        return itemDiminuir;
    }

    public JMenuItem itemTamanhoNormal() {
        return itemNormal;
    }

    public JMenuItem itemNovoProjeto() {
        return itemNovoProjeto;
    }

    public JMenuItem itemUltimoProjeto() {
        return itemUltimoProjeto;
    }

    public JMenuItem itemVoltarAosProjetos() {
        return itemVoltarAosProjetos;
    }

    public JMenuItem itemDoServidor(Item i) {
        return itensDoServidor.get(i);
    }

    public JRadioButtonMenuItem itemDeTema(Tema t) {
        return itensDeTema.get(t);
    }
}
