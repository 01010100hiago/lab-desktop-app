package br.com.labdesktop.ui.telas;

import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.AcoesDaInterface;
import br.com.labdesktop.ui.ApresentacaoDoEstado;
import br.com.labdesktop.ui.ApresentacaoDoEstado.Apresentacao;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MenuDoServidor;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * A tela de servidores (ARQUITETURA 8.1): a lista de servidores cadastrados, cada um com o estado da
 * conexão em TEXTO mais ÍCONE (nunca só cor), o endereço, o token deste Mac, o estado do 2FA e a impressão digital do
 * host que o usuário confirmou; e os botões de cada um (conectar, conferir impressão, registrar token, configurar ou
 * desativar o 2FA e, no canto direito, Editar e Remover). "Adicionar servidor" abre o assistente.
 *
 * <p>Só desenha os retratos imutáveis dos servidores ({@link EstadoServidor}); toda ação passa por
 * {@link AcoesDaInterface}, que roda SSH e rede fora da EDT.
 */
public final class TelaServidores extends Painel {

    private static final long serialVersionUID = 1L;

    private final GerenciadorServidores gerenciador;
    private final AcoesDaInterface acoes;
    private final JPanel cartoesPanel = new Painel();
    private final Map<String, CartaoDeServidor> cartoes = new LinkedHashMap<>();
    private final JComponent vazio;
    private final Botao adicionar = Botoes.primario("Adicionar servidor", "mais");
    private final Inscricao inscricaoDaLista;

    public TelaServidores(GerenciadorServidores gerenciador, AcoesDaInterface acoes) {
        super(new BorderLayout());
        this.gerenciador = gerenciador;
        this.acoes = acoes;
        setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 20, 16, 20));

        JComponent cabecalho = Caixa.linha(10, Rotulo.titulo("Servidores"),
            Rotulo.secundario("Onde o app se conecta: uma chave SSH por servidor, guardada no cofre").tamanho(12.5f),
            Caixa.mola(), adicionar);
        cabecalho.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 14, 0));
        add(cabecalho, BorderLayout.NORTH);

        cartoesPanel.setLayout(new BoxLayout(cartoesPanel, BoxLayout.Y_AXIS));
        vazio = montarVazio();
        JPanel vista = new Rolavel(new BorderLayout());
        vista.add(cartoesPanel, BorderLayout.NORTH);
        JScrollPane rolagem = new JScrollPane(vista);
        rolagem.setBorder(null);
        rolagem.setOpaque(false);
        rolagem.getViewport().setOpaque(false);
        rolagem.getVerticalScrollBar().setUnitIncrement(18);
        add(rolagem, BorderLayout.CENTER);

        adicionar.addActionListener(e -> acoes.adicionarServidor());
        getAccessibleContext().setAccessibleName("Servidores");
        inscricaoDaLista = gerenciador.observarLista(this::aoMudarALista);
    }

    private JComponent montarVazio() {
        Botao adicionarNoVazio = Botoes.primario("Adicionar servidor", "mais");
        adicionarNoVazio.addActionListener(e -> acoes.adicionarServidor());
        Rotulo t = Rotulo.titulo("Nenhum servidor ainda").tamanho(15f);
        t.setIcon(Icones.colorido("servidor", 20, Tokens.TEXTO3));
        t.setIconTextGap(8);
        JPanel c = Caixa.coluna(10, t,
            Rotulo.secundario("Cadastre o primeiro: o app gera a chave SSH, você confere a impressão digital do servidor e ele "
                + "registra o token deste Mac."), adicionarNoVazio);
        c.setBorder(javax.swing.BorderFactory.createEmptyBorder(28, 28, 28, 28));
        Painel cartao = new Painel(new BorderLayout()).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
        cartao.add(c, BorderLayout.CENTER);
        return cartao;
    }

    /** Solta as inscrições (a janela fechou ou o cofre foi bloqueado). */
    public void encerrar() {
        inscricaoDaLista.close();
        cartoes.values().forEach(CartaoDeServidor::soltar);
        cartoes.clear();
    }

    private void aoMudarALista(List<ServidorLab> labs) {
        Map<String, CartaoDeServidor> novos = new LinkedHashMap<>();
        for (ServidorLab lab : labs) {
            CartaoDeServidor c = cartoes.remove(lab.id());
            novos.put(lab.id(), c != null ? c : new CartaoDeServidor(lab, acoes));
        }
        cartoes.values().forEach(CartaoDeServidor::soltar);
        cartoes.clear();
        cartoes.putAll(novos);
        cartoesPanel.removeAll();
        if (cartoes.isEmpty()) {
            vazio.setAlignmentX(LEFT_ALIGNMENT);
            cartoesPanel.add(vazio);
        }
        for (CartaoDeServidor c : cartoes.values()) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            cartoesPanel.add(c);
            cartoesPanel.add(Caixa.espacoVertical(12));
        }
        cartoesPanel.revalidate();
        cartoesPanel.repaint();
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public List<String> idsExibidos() {
        return new ArrayList<>(cartoes.keySet());
    }

    public CartaoDeServidor cartao(String id) {
        return cartoes.get(id);
    }

    public boolean mostraOVazio() {
        return cartoes.isEmpty() && vazio.getParent() != null;
    }

    public Botao botaoAdicionar() {
        return adicionar;
    }

    // ---------------------------------------------------------------------------------------------
    // O cartão de um servidor
    // ---------------------------------------------------------------------------------------------

    /** Um servidor: cabeçalho, detalhes e as ações possíveis agora. */
    public static final class CartaoDeServidor extends Painel {
        private static final long serialVersionUID = 1L;

        private final ServidorLab lab;
        private final AcoesDaInterface acoes;
        private final Rotulo nome = Rotulo.titulo("").tamanho(15f);
        private final Rotulo endereco = new Rotulo().mono(12f).corDoToken(Tokens.TEXTO2);
        private final Rotulo estadoDaConexao = new Rotulo().negrito().tamanho(13f);
        private final Rotulo detalheDoEstado = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
        private final Rotulo token = new Rotulo().tamanho(12.5f);
        private final Selo selo2fa = new Selo("2FA desligado", Gravidade.NEUTRO);
        private final Rotulo impressao = new Rotulo().mono(12f).corDoToken(Tokens.TEXTO2).truncavel();
        private final JPanel linhaDeAcoes = new Painel();
        private final Inscricao inscricao;
        private List<Item> itensAtuais = List.of();
        private EstadoServidor ultimo;

        CartaoDeServidor(ServidorLab lab, AcoesDaInterface acoes) {
            super(new BorderLayout(0, 12));
            this.lab = lab;
            this.acoes = acoes;
            fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 18, 14, 18));

            JPanel cabecalho = new Painel(new BorderLayout(12, 0));
            cabecalho.add(Caixa.coluna(2, nome, endereco), BorderLayout.CENTER);
            cabecalho.add(Caixa.coluna(2, estadoDaConexao, detalheDoEstado), BorderLayout.EAST);
            ((JComponent) cabecalho.getComponent(1)).setAlignmentX(RIGHT_ALIGNMENT);
            add(cabecalho, BorderLayout.NORTH);

            JPanel detalhes = new Painel(new GridBagLayout());
            detalhes.add(chave("Token deste Mac"), restricoes(0, 0, 0));
            detalhes.add(token, restricoes(1, 0, 1));
            detalhes.add(chave("2FA"), restricoes(2, 0, 0));
            detalhes.add(selo2fa, restricoes(3, 0, 0));
            detalhes.add(chave("Host confirmado"), restricoes(0, 1, 0));
            GridBagConstraints g = restricoes(1, 1, 1);
            g.gridwidth = 3;
            detalhes.add(impressao, g);
            add(detalhes, BorderLayout.CENTER);

            linhaDeAcoes.setLayout(new BoxLayout(linhaDeAcoes, BoxLayout.X_AXIS));
            add(linhaDeAcoes, BorderLayout.SOUTH);
            inscricao = lab.observarEstado(this::mostrar);
        }

        private static Rotulo chave(String texto) {
            return Rotulo.rotuloDeCampo(texto);
        }

        private static GridBagConstraints restricoes(int x, int y, double peso) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = x;
            g.gridy = y;
            g.weightx = peso;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(2, x == 0 ? 0 : 14, 2, 8);
            return g;
        }

        void soltar() {
            inscricao.close();
        }

        private void mostrar(EstadoServidor e) {
            this.ultimo = e;
            ConfigServidor cfg = lab.config();
            Apresentacao a = ApresentacaoDoEstado.de(e);
            nome.setText(e.nome());
            endereco.setText(cfg.usuario() + "@" + cfg.host() + ":" + cfg.porta() + "  ·  agente em 127.0.0.1:" + cfg.portaAgente());
            estadoDaConexao.setIcon(Icones.colorido(a.icone(), 16, a.gravidade().tokenDoTexto()));
            estadoDaConexao.setIconTextGap(7);
            estadoDaConexao.setText(a.texto());
            estadoDaConexao.corDoToken(a.gravidade().tokenDoTexto());
            estadoDaConexao.setHorizontalAlignment(SwingConstants.RIGHT);
            String detalhe = e.falha() != null ? e.falha().mensagem()
                : e.semConexaoDesde() != null ? "sem conexão desde " + Formato.fmtHora(e.semConexaoDesde()).substring(0, 5)
                : e.versao() != null && e.versao().painel() != null ? "agente " + e.versao().painel() : "";
            detalheDoEstado.setText(detalhe);
            detalheDoEstado.setToolTipText(detalhe);
            detalheDoEstado.setHorizontalAlignment(SwingConstants.RIGHT);

            if (e.nomeDoDispositivo() == null) {
                token.setText("ainda não registrado");
                token.corDoToken(Tokens.ATENCAO);
            } else {
                token.setText(e.nomeDoDispositivo());
                token.corDoToken(Tokens.TEXTO);
            }
            switch (e.estado2fa()) {
                case ATIVO -> selo2fa.atualizar("2FA ativo", Gravidade.OK, "chave");
                case PENDENTE -> selo2fa.atualizar("2FA pendente", Gravidade.ATENCAO, "chave");
                case DESLIGADO -> selo2fa.atualizar("2FA desligado", Gravidade.NEUTRO, "chave");
            }
            selo2fa.setToolTipText(e.estado2fa() == br.com.labdesktop.cofre.Estado2fa.PENDENTE
                ? "Configuração começada e ainda não confirmada: nada é exigido."
                : null);

            if (cfg.pins().isEmpty()) {
                impressao.setText("ainda não confirmado: confira a impressão antes de conectar");
                impressao.corDoToken(Tokens.ATENCAO);
            } else {
                Pin p = cfg.pins().get(0);
                impressao.setText(p.algoritmo() + "  " + p.impressao());
                impressao.setToolTipText(p.algoritmo() + " " + p.impressao());
                impressao.corDoToken(Tokens.TEXTO2);
            }
            getAccessibleContext().setAccessibleName("Servidor " + e.nome() + ", " + a.texto());
            reconstruirAcoes(e);
            revalidate();
            repaint();
        }

        private void reconstruirAcoes(EstadoServidor e) {
            List<Item> itens = MenuDoServidor.itensPara(e);
            if (itens.equals(itensAtuais)) {
                return;
            }
            itensAtuais = itens;
            linhaDeAcoes.removeAll();
            boolean primeiro = true;
            boolean depoisDaMola = false;
            for (Item i : itens) {
                if (!depoisDaMola && (i == Item.EDITAR || i == Item.REMOVER)) {
                    // Editar e Remover ficam no canto direito, longe das ações do dia a dia.
                    linhaDeAcoes.add(Caixa.mola());
                    depoisDaMola = true;
                    primeiro = true;
                }
                Botao b = i == Item.CONFERIR_IMPRESSAO || i == Item.REGISTRAR_TOKEN || i == Item.DIGITAR_CODIGO
                    ? Botoes.miniPrimario(i.rotulo(), i.icone())
                    : i == Item.REMOVER ? Botoes.miniPerigo(i.rotulo(), i.icone()) : Botoes.mini(i.rotulo(), i.icone());
                b.setName("acao-" + i.name());
                b.addActionListener(ev -> MenuDoServidor.executar(i, lab, acoes));
                if (!primeiro) {
                    linhaDeAcoes.add(Caixa.espaco(8));
                }
                linhaDeAcoes.add(b);
                primeiro = false;
            }
            if (!depoisDaMola) {
                linhaDeAcoes.add(Caixa.mola());
            }
        }

        public String textoDoNome() {
            return nome.getText();
        }

        public String textoDoEstado() {
            return estadoDaConexao.getText();
        }

        public String textoDoToken() {
            return token.getText();
        }

        public String textoDoSelo2fa() {
            return selo2fa.texto();
        }

        public String textoDaImpressao() {
            return impressao.getText();
        }

        public String textoDoEndereco() {
            return endereco.getText();
        }

        public List<Item> acoesMostradas() {
            return itensAtuais;
        }

        public Botao botaoDa(Item item) {
            return (Botao) achar(linhaDeAcoes, "acao-" + item.name());
        }

        public EstadoServidor ultimoEstado() {
            return ultimo;
        }

        private static java.awt.Component achar(java.awt.Container raiz, String nomeDoComponente) {
            for (java.awt.Component c : raiz.getComponents()) {
                if (nomeDoComponente.equals(c.getName())) {
                    return c;
                }
            }
            return null;
        }
    }

    /** Acompanha a largura da janela e rola na vertical. */
    private static final class Rolavel extends Painel implements Scrollable {
        private static final long serialVersionUID = 1L;

        Rolavel(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle area, int orientacao, int direcao) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle area, int orientacao, int direcao) {
            return Math.max(40, area.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
        }
    }
}
