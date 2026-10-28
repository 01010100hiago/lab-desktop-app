package br.com.labdesktop.ui;

import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.componentes.Teclas;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/**
 * O menu do app (a barra de menus do macOS, por {@code apple.laf.useScreenMenuBar}). Regra de ouro (ARQUITETURA 7.4):
 * Cmd é do app e o resto é do terminal, e os atalhos nativos do JediTerm (Cmd+C, Cmd+V, Cmd+K, Cmd+Setas) NÃO viram
 * item de menu, para o menu da tela do macOS não engolir o atalho. Aqui entram Cmd+N, Cmd+Shift+N (Novo projeto), Cmd+R, Cmd+, (Ajustes), Cmd+1 a Cmd+9
 * (as abas do servidor, NA ORDEM que o usuário deixou em Ajustes, Navegação), Cmd+Shift+S (Servidores), Cmd+Shift+P (último projeto), Cmd+W, Cmd+M e Cmd+Shift+L (bloquear o
 * cofre); "Cmd" é a tecla de comando do sistema (Ctrl no Windows e no Linux). A Ajuda é a exceção: F1 no Windows e no Linux e Cmd+Shift+/ (Cmd+?) no Mac, o padrão de cada
 * sistema. Todo item com atalho tem uma chave estável ({@link #atalhosDoMenu}): a Ajuda cita as teclas por ela e a tabela de atalhos dela sai daqui.
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
    private JMenuItem itemAdicionarDominio;
    private JMenuItem itemUltimoProjeto;
    private br.com.labdesktop.servidor.Inscricao inscricaoDoEstado;
    private JMenuItem itemVoltarAosProjetos;
    private final JMenuBar barra = new JMenuBar();
    private final List<JMenuItem> itensDasAbas = new ArrayList<>();
    private JMenuItem itemAjustes;
    private JMenuItem itemSobre;
    private JMenuItem itemAparencia;
    private JMenuItem itemAjuda;
    private JMenuItem itemServidores;

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

    /** Todo item do menu pelo nome estável (a Ajuda cita os atalhos por estas chaves), na ordem em que foram criados. */
    private final Map<String, JMenuItem> porChave = new java.util.LinkedHashMap<>();

    private JMenuItem item(String chave, String texto, KeyStroke tecla, Runnable acao) {
        JMenuItem i = item(texto, tecla, acao);
        porChave.put(chave, i);
        return i;
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
        cofre.add(item("bloquear-cofre", "Bloquear o cofre", KeyStroke.getKeyStroke(KeyEvent.VK_L, atalho() | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
            bloquearCofre));
        if (comportamento.temItemSair()) {
            // Windows e Linux: não há o Cmd+Q do menu do app. No Windows o atalho do sistema é Alt+F4 (fechar a janela também sai); no Linux, Ctrl+Q.
            cofre.addSeparator();
            itemSair = item("sair", "Sair", comportamento.sistema() == br.com.labdesktop.plataforma.SistemaOperacional.LINUX ? KeyStroke.getKeyStroke(KeyEvent.VK_Q, atalho()) : null,
                comportamento.sair());
            cofre.add(itemSair);
        }
        barra.add(cofre);

        // --- Arquivo ---
        JMenu arquivo = new JMenu("Arquivo");
        // Cmd+Shift+N no Mac, Ctrl+Shift+N nos outros (a tecla de comando do sistema; Cmd+N é "Adicionar servidor")
        itemNovoProjeto = item("novo-projeto", "Novo projeto…", KeyStroke.getKeyStroke(KeyEvent.VK_N, atalho() | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
            () -> painel.novoProjetoNoServidorEmFoco());
        arquivo.add(itemNovoProjeto);
        // "Adicionar domínio…": no projeto aberto, ou pergunta para qual projeto (sem atalho: não é ação de todo dia)
        itemAdicionarDominio = item("adicionar-dominio", "Adicionar domínio…", null, () -> painel.adicionarDominioNoServidorEmFoco());
        arquivo.add(itemAdicionarDominio);
        arquivo.addSeparator();
        // A tecla de comando do sistema (Cmd no Mac, Ctrl nos outros) e a vírgula: o atalho de Ajustes de todo app.
        itemAjustes = item("ajustes", "Ajustes…", KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, atalho()), () -> painel.mostrarAjustes(null));
        itemSobre = item("sobre", "Sobre o Lab Desktop", null, mostrarSobre);
        arquivo.add(itemAjustes);
        arquivo.add(itemSobre);
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
        menuServidor.add(item("adicionar-servidor", "Adicionar servidor…", KeyStroke.getKeyStroke(KeyEvent.VK_N, atalho()),
            () -> painel.acoes().adicionarServidor()));
        menuServidor.addSeparator();
        for (Item i : Item.values()) {
            if (i == Item.EDITAR) {
                menuServidor.addSeparator();
            }
            JMenuItem mi = i == Item.ATUALIZAR
                ? item("atualizar", i.rotulo(), KeyStroke.getKeyStroke(KeyEvent.VK_R, atalho()), () -> executarNoServidorEmFoco(i))
                : item(i.rotulo(), null, () -> executarNoServidorEmFoco(i));
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
        // Servidores (a tela de gerenciar) deixou o número 2: os números 1 a 9 agora são as abas do servidor, na ordem que o usuário deixou em Ajustes, Navegação.
        itemServidores = item("servidores", "Servidores", KeyStroke.getKeyStroke(KeyEvent.VK_S, atalho() | java.awt.event.InputEvent.SHIFT_DOWN_MASK), painel::mostrarServidores);
        ver.add(itemServidores);
        ver.addSeparator();
        for (int n = 1; n <= 9; n++) {
            int numero = n;
            JMenuItem aba = item("aba-" + n, "Aba " + n, KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, atalho()), () -> painel.mostrarAbaNumero(numero));
            itensDasAbas.add(aba);
            ver.add(aba);
        }
        ver.addSeparator();
        itemUltimoProjeto = item("ultimo-projeto", "Último projeto aberto", KeyStroke.getKeyStroke(KeyEvent.VK_P, atalho() | java.awt.event.InputEvent.SHIFT_DOWN_MASK), painel::mostrarUltimoProjeto);
        ver.add(itemUltimoProjeto);
        itemVoltarAosProjetos = item("voltar-aos-projetos", "Voltar à lista de projetos", KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, atalho()),
            painel::mostrarProjetos);
        ver.add(itemVoltarAosProjetos);
        ver.addSeparator();
        itemAumentar = item("aumentar-texto", "Aumentar texto", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, atalho()), () -> mudarTexto(Fontes.maior(Fontes.percentual())));
        itemDiminuir = item("diminuir-texto", "Diminuir texto", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, atalho()), () -> mudarTexto(Fontes.menor(Fontes.percentual())));
        itemNormal = item("tamanho-normal", "Tamanho normal", KeyStroke.getKeyStroke(KeyEvent.VK_0, atalho()), () -> mudarTexto(Fontes.ESCALAS[0]));
        ver.add(itemAumentar);
        ver.add(itemDiminuir);
        ver.add(itemNormal);
        instalarAtalhoDoMais();
        atualizarItensDoTexto();
        ver.addSeparator();
        // O tema (Sistema, Claro, Escuro) mora em Ajustes: aqui fica só o caminho até lá.
        itemAparencia = item("Aparência…", null, () -> painel.mostrarAjustes(TelaAjustes.Secao.APARENCIA));
        ver.add(itemAparencia);
        ver.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                atualizarHabilitacaoDoProjeto();
                atualizarItensDasAbas();
                atualizarItensDoTexto();
            }

            @Override
            public void menuDeselected(MenuEvent e) { }

            @Override
            public void menuCanceled(MenuEvent e) { }
        });
        barra.add(ver);

        // --- Janela ---
        JMenu menuJanela = new JMenu("Janela");
        menuJanela.add(item("minimizar", "Minimizar", KeyStroke.getKeyStroke(KeyEvent.VK_M, atalho()),
            () -> janela.setExtendedState(janela.getExtendedState() | JFrame.ICONIFIED)));
        if (comportamento.fecharEscondeAJanela()) {
            // Só onde fechar a janela é esconder (Mac): Cmd+W. Fora do Mac Ctrl+W NÃO existe (fechar a janela sai do app; quem quer sair usa "Sair" e a pergunta de pendências).
            menuJanela.add(item("fechar-janela", "Fechar a janela", KeyStroke.getKeyStroke(KeyEvent.VK_W, atalho()),
                () -> janela.dispatchEvent(new java.awt.event.WindowEvent(janela, java.awt.event.WindowEvent.WINDOW_CLOSING))));
        }
        barra.add(menuJanela);

        // --- Ajuda ---
        JMenu ajuda = new JMenu("Ajuda");
        // F1 no Windows e no Linux; no Mac, Cmd+? (Cmd+Shift+/), o atalho de Ajuda do sistema
        KeyStroke teclaDaAjuda = comportamento.sistema() == br.com.labdesktop.plataforma.SistemaOperacional.MAC
            ? KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, atalho() | java.awt.event.InputEvent.SHIFT_DOWN_MASK)
            : KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0);
        itemAjuda = item("ajuda", "Guia do Lab Desktop", teclaDaAjuda, () -> painel.mostrarAjustes(TelaAjustes.Secao.AJUDA));
        ajuda.add(itemAjuda);
        barra.add(ajuda);
        atualizarHabilitacao();
        atualizarHabilitacaoDoNovoProjeto();
        atualizarItensDasAbas();
        // O atalho de um item desabilitado não faz nada: a habilitação acompanha o servidor em foco (troca de tela) e o estado dele (o agente
        // que passa a responder), e não só o abrir do menu.
        painel.navegacao().aoMudar(id -> {
            religarAoServidorEmFoco();
            atualizarItensDasAbas();
        });
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

    /**
     * Os itens "Aba 1" a "Aba 9" ganham o nome da aba daquele número NA ORDEM de agora (a que o usuário deixou em Ajustes, Navegação) para o servidor em foco; sem aba naquele
     * número o item fica apagado. O atalho (a tecla de comando e o número) conta pelas mesmas posições.
     */
    public void atualizarItensDasAbas() {
        List<String> nomes = painel.nomesDasAbasVisiveis();
        for (int i = 0; i < itensDasAbas.size(); i++) {
            JMenuItem mi = itensDasAbas.get(i);
            boolean tem = i < nomes.size();
            mi.setText(tem ? nomes.get(i) : "Aba " + (i + 1));
            mi.setEnabled(tem);
        }
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
        AjustesDoApp.aplicarTamanhoDoTexto(tema, preferencias, porcento);
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
        itemAdicionarDominio.setEnabled(painel.podeAdicionarDominioNoServidorEmFoco());
        itemAdicionarDominio.setToolTipText(painel.motivoDeNaoAdicionarDominio());
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

    public JMenuItem itemAdicionarDominio() {
        return itemAdicionarDominio;
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

    /** O item "Aba N" (de 1 a 9) do menu Visualizar. */
    public JMenuItem itemDaAba(int numero) {
        return itensDasAbas.get(numero - 1);
    }

    public JMenuItem itemAjustes() {
        return itemAjustes;
    }

    /** O item Ajuda (F1 no Windows e no Linux, Cmd+? no Mac). */
    public JMenuItem itemAjuda() {
        return itemAjuda;
    }

    /**
     * Os atalhos do menu como a Ajuda os mostra: a chave estável, o que o item faz e a tecla escrita. Sai do próprio menu (o que está agora no
     * computador do usuário), nunca é digitado; as abas numeradas dizem o nome da aba que o número abre agora.
     */
    public List<br.com.labdesktop.ajuda.AtalhoDoMenu> atalhosDoMenu() {
        atualizarItensDasAbas();
        List<br.com.labdesktop.ajuda.AtalhoDoMenu> lista = new ArrayList<>();
        porChave.forEach((chave, mi) -> {
            KeyStroke tecla = mi.getAccelerator();
            // sem as ações de escrita (o padrão) o Novo projeto não existe: a tabela da Ajuda não mostra uma tecla que não faz nada
            boolean semEfeito = chave.equals("novo-projeto") && !br.com.labdesktop.ui.telas.FluxosDeProjeto.liberadasPorPropriedade();
            if (tecla != null && !semEfeito && !(chave.startsWith("aba-") && !mi.isEnabled())) {
                String descricao = chave.startsWith("aba-") ? "Abrir a aba " + chave.substring(4) + " da barra (" + mi.getText() + ")" : mi.getText().replace("…", "");
                lista.add(new br.com.labdesktop.ajuda.AtalhoDoMenu(chave, descricao, Teclas.descrever(tecla, comportamento.sistema())));
            }
        });
        return lista;
    }

    public JMenuItem itemSobre() {
        return itemSobre;
    }

    public JMenuItem itemAparencia() {
        return itemAparencia;
    }

    public JMenuItem itemServidores() {
        return itemServidores;
    }
}
