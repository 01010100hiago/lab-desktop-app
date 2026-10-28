package br.com.labdesktop.ui;

import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.ApresentacaoDoEstado.Apresentacao;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/**
 * A barra lateral de servidores (ARQUITETURA 8.3): um item por servidor com o ESTADO da conexão em ícone e
 * texto (nunca só cor) e o percentual da semana do plano ao lado, mais "Gerenciar servidores" e o botão de adicionar.
 * Clique abre a lista de projetos daquele servidor; clique com o botão direito abre o menu do servidor (atualizar,
 * conectar, conferir impressão, registrar token, configurar ou desativar o 2FA).
 *
 * <p>Observa a lista de servidores do {@link GerenciadorServidores} e o estado de cada um: tudo chega pela EDT, como
 * retratos imutáveis.
 */
public final class BarraServidores extends Painel {

    private static final long serialVersionUID = 1L;
    /** Largura da barra. */
    public static final int LARGURA = 216;

    /** Quem reage às escolhas. */
    public interface Ouvinte {
        void aoEscolherServidor(ServidorLab lab);

        void aoEscolherGerenciar();
    }

    private final GerenciadorServidores gerenciador;
    private final AcoesDaInterface acoes;
    private final Ouvinte ouvinte;
    private final JPanel itensPanel = new Painel();
    private final Map<String, ItemDeServidor> itens = new LinkedHashMap<>();
    private final ItemFixo gerenciar = new ItemFixo("Gerenciar servidores", "servidor");
    private final Botao adicionar = Botoes.mini("Adicionar", "mais");
    private final Inscricao inscricaoDaLista;
    private String selecionadoId;
    private boolean gerenciarSelecionado;

    public BarraServidores(GerenciadorServidores gerenciador, AcoesDaInterface acoes, Ouvinte ouvinte) {
        super(new BorderLayout());
        this.gerenciador = gerenciador;
        this.acoes = acoes;
        this.ouvinte = ouvinte;
        fundo(Tokens.BG2);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 12, 12, 12));
        setPreferredSize(new Dimension(LARGURA, 200));

        Rotulo titulo = new Rotulo("SERVIDORES").corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f);
        JComponent cabecalho = Caixa.linha(0, titulo, Caixa.mola(), adicionar);
        cabecalho.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 6, 8, 0));
        add(cabecalho, BorderLayout.NORTH);

        itensPanel.setLayout(new BoxLayout(itensPanel, BoxLayout.Y_AXIS));
        add(itensPanel, BorderLayout.CENTER);
        add(gerenciar, BorderLayout.SOUTH);

        adicionar.setToolTipText("Adicionar um servidor");
        adicionar.addActionListener(e -> acoes.adicionarServidor());
        gerenciar.aoClicar(() -> {
            definirSelecionado(null, true);
            ouvinte.aoEscolherGerenciar();
        });
        getAccessibleContext().setAccessibleName("Servidores");
        inscricaoDaLista = gerenciador.observarLista(this::aoMudarALista);
    }

    /** Solta as inscrições (a janela fechou ou o cofre foi bloqueado). */
    public void encerrar() {
        inscricaoDaLista.close();
        itens.values().forEach(ItemDeServidor::soltar);
        itens.clear();
    }

    private void aoMudarALista(List<ServidorLab> labs) {
        Map<String, ItemDeServidor> novos = new LinkedHashMap<>();
        for (ServidorLab lab : labs) {
            ItemDeServidor existente = itens.remove(lab.id());
            if (existente == null) {
                existente = new ItemDeServidor(lab);
                ligarItem(existente);
            }
            novos.put(lab.id(), existente);
        }
        itens.values().forEach(ItemDeServidor::soltar); // os que saíram
        itens.clear();
        itens.putAll(novos);
        itensPanel.removeAll();
        for (ItemDeServidor i : itens.values()) {
            i.setAlignmentX(LEFT_ALIGNMENT);
            itensPanel.add(i);
            itensPanel.add(Caixa.espacoVertical(4));
        }
        if (selecionadoId != null && !itens.containsKey(selecionadoId)) {
            selecionadoId = null;
        }
        itensPanel.revalidate();
        itensPanel.repaint();
        atualizarSelecao();
    }

    /** Marca o servidor cuja lista está na tela (ou "Gerenciar servidores"). */
    public void definirSelecionado(String servidorId, boolean gerenciando) {
        this.selecionadoId = servidorId;
        this.gerenciarSelecionado = gerenciando;
        atualizarSelecao();
    }

    private void atualizarSelecao() {
        itens.forEach((id, i) -> i.definirSelecionado(!gerenciarSelecionado && id.equals(selecionadoId)));
        gerenciar.definirSelecionado(gerenciarSelecionado);
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public List<String> idsExibidos() {
        return new ArrayList<>(itens.keySet());
    }

    public ItemDeServidor item(String id) {
        return itens.get(id);
    }

    public Botao botaoAdicionar() {
        return adicionar;
    }

    public String selecionado() {
        return gerenciarSelecionado ? null : selecionadoId;
    }

    public boolean gerenciarSelecionado() {
        return gerenciarSelecionado;
    }

    public void clicarEmGerenciar() {
        gerenciar.clicar();
    }

    // ---------------------------------------------------------------------------------------------
    // Itens
    // ---------------------------------------------------------------------------------------------

    /** Base visual de um item clicável: fundo suave ao passar o mouse, tom de destaque quando selecionado. */
    private abstract static class ItemClicavel extends Painel {
        private static final long serialVersionUID = 1L;
        private boolean selecionado;
        private boolean sobMouse;
        private Runnable aoClicar = () -> { };

        ItemClicavel() {
            super(new BorderLayout(8, 0));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    sobMouse = true;
                    atualizarFundo();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    sobMouse = false;
                    atualizarFundo();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        aoClicar.run();
                    }
                }
            };
            addMouseListener(mouse);
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "clicar");
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "clicar");
            getActionMap().put("clicar", new javax.swing.AbstractAction() {
                private static final long serialVersionUID = 1L;

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    aoClicar.run();
                }
            });
        }

        void aoClicar(Runnable acao) {
            this.aoClicar = acao;
        }

        void clicar() {
            aoClicar.run();
        }

        void definirSelecionado(boolean sim) {
            this.selecionado = sim;
            atualizarFundo();
        }

        boolean selecionado() {
            return selecionado;
        }

        private void atualizarFundo() {
            fundo(selecionado ? Tokens.DESTAQUE_SUAVE : sobMouse ? Tokens.SUPERFICIE3 : null);
            borda(selecionado ? Tokens.DESTAQUE_BORDA : null);
            raio(10);
            repaint();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** "Gerenciar servidores". */
    private static final class ItemFixo extends ItemClicavel {
        private static final long serialVersionUID = 1L;

        ItemFixo(String texto, String icone) {
            add(new Rotulo(texto).icone(Icones.de(icone, 16)), BorderLayout.CENTER);
            ((Rotulo) getComponent(0)).setIconTextGap(8);
            getAccessibleContext().setAccessibleName(texto);
        }
    }

    /** Um servidor: ícone e texto do estado, nome e % da semana. */
    public static final class ItemDeServidor extends ItemClicavel {
        private static final long serialVersionUID = 1L;

        private final ServidorLab lab;
        private final Rotulo nome = new Rotulo().negrito().truncavel();
        private final Rotulo estado = new Rotulo().tamanho(11.5f).truncavel();
        private final Rotulo semana = new Rotulo().negrito().tamanho(12.5f).aDireita();
        private final Rotulo marca = new Rotulo();
        private final Inscricao inscricao;
        private EstadoServidor ultimo;

        ItemDeServidor(ServidorLab lab) {
            this.lab = lab;
            marca.setVerticalAlignment(javax.swing.SwingConstants.TOP);
            marca.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 0, 0, 0));
            add(marca, BorderLayout.WEST);
            add(Caixa.coluna(1, nome, estado), BorderLayout.CENTER);
            JPanel lado = new Painel(new BorderLayout());
            lado.add(semana, BorderLayout.NORTH);
            add(lado, BorderLayout.EAST);
            inscricao = lab.observarEstado(this::mostrar);
        }

        void soltar() {
            inscricao.close();
        }

        ServidorLab servidor() {
            return lab;
        }

        /** O menu de contexto, montado com o estado de agora. */
        void abrirMenu(JComponent origem, int x, int y, AcoesDaInterface acoes) {
            MenuDoServidor.popup(lab, acoes).show(origem, x, y);
        }

        private void mostrar(EstadoServidor e) {
            this.ultimo = e;
            Apresentacao a = ApresentacaoDoEstado.de(e);
            nome.setText(e.nome());
            nome.setToolTipText(e.nome());
            marca.setIcon(Icones.colorido(a.icone(), 16, a.gravidade().tokenDoTexto()));
            estado.setText(a.texto());
            estado.corDoToken(a.gravidade() == Gravidade.NEUTRO ? Tokens.TEXTO3 : a.gravidade().tokenDoTexto());
            Double pct = e.pctDaSemana();
            if (pct == null) {
                semana.setText("");
                semana.setToolTipText(null);
            } else {
                Severidade s = Severidade.doCredito(pct / 100.0);
                semana.setText(Formato.fmtNum(pct, 0) + "%");
                semana.corDoToken(s == Severidade.NORMAL ? Tokens.TEXTO2 : s.tokenDaCor());
                semana.setToolTipText("Semana do plano do Claude: " + Formato.fmtNum(pct, 0) + "%");
            }
            getAccessibleContext().setAccessibleName(e.nome() + ", " + a.texto()
                + (pct == null ? "" : ", semana em " + Formato.fmtNum(pct, 0) + "%"));
            revalidate();
            repaint();
        }

        String textoDoEstado() {
            return estado.getText();
        }

        public String textoDoNome() {
            return nome.getText();
        }

        public String textoDaSemana() {
            return semana.getText();
        }

        public String textoDoEstadoDaConexao() {
            return estado.getText();
        }

        public EstadoServidor ultimoEstado() {
            return ultimo;
        }

        public boolean estaSelecionado() {
            return selecionado();
        }

        public void clicarComOBotaoEsquerdo() {
            clicar();
        }
    }

    /** Liga o clique do botão direito (menu de contexto) e o clique normal (abrir a lista) a um item recém-criado. */
    private void ligarItem(ItemDeServidor i) {
        i.aoClicar(() -> {
            definirSelecionado(i.servidor().id(), false);
            ouvinte.aoEscolherServidor(i.servidor());
        });
        i.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                talvezAbrirMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                talvezAbrirMenu(e);
            }

            private void talvezAbrirMenu(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    i.abrirMenu(i, e.getX(), e.getY(), acoes);
                }
            }
        });
    }

    /** Pinta o fundo da barra e o filete da borda direita. */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Tokens.cor(Tokens.BORDA));
            g2.fillRect(getWidth() - 1, 0, 1, getHeight());
        } finally {
            g2.dispose();
        }
    }
}
