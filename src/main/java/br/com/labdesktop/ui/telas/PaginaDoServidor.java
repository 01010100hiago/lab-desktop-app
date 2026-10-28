package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.Topico;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.ComponenteSimples;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.MioloRolavel;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A base de cada aba de dados da página do servidor (Resumo, Segurança, Containers, Processos, Máquina). Cuida do que é
 * igual em todas: assinar SÓ os tópicos que a aba usa e SÓ enquanto ela está visível ({@code HierarchyListener}), guardar
 * o último retrato de cada dado, e os estados da página (carregando com esqueleto, sem conexão com os últimos dados
 * esmaecidos e a hora, agente fora, sem dado ainda). Cada aba só diz quais dados usa, monta o seu conteúdo e o atualiza.
 *
 * <p>Só desenha snapshots imutáveis e tudo acontece na EDT; a rede fica no {@code Sondador}. Aba escondida (outra aba na
 * frente, ou outra tela) não consulta nada.
 */
public abstract class PaginaDoServidor extends Painel {

    private static final long serialVersionUID = 1L;

    private final transient ServidorLab servidor;
    private final transient Runnable aoAtualizar;
    private final FaixaDeDados faixaDeDados = new FaixaDeDados();
    private final Esqueleto esqueleto = new Esqueleto();
    private final SemDados semDados;
    private final Esmaecivel corpo = new Esmaecivel();
    private final JPanel pilha = new Painel();
    private MioloRolavel rolagem;

    private transient Supplier<Instant> relogio = Instant::now;
    private ZoneId fuso = ZoneId.systemDefault();
    EstadoServidor estado;
    InfoServidor info;
    DetalheServidor detalhe;
    ResumoDeSeguranca seguranca;
    Instant ultimaLeitura;
    boolean detalheFalhou;
    private ErroApi erroSemDados;
    private SituacaoDaVisao situacao = SituacaoDaVisao.de(null, false, null, Instant.now(), ZoneId.systemDefault());
    private transient Inscricao inscricaoDoServidor;
    private transient Inscricao inscricaoDoDetalhe;
    private transient Inscricao inscricaoDaSeguranca;
    private transient Inscricao inscricaoDoEstado;

    /**
     * @param servidor    o servidor vivo ({@code null} nos testes que alimentam a aba com dados fixos)
     * @param aoAtualizar o que o botão "Tentar de novo" faz (consulta já tudo o que está assinado)
     */
    protected PaginaDoServidor(ServidorLab servidor, Runnable aoAtualizar) {
        super(new BorderLayout());
        this.servidor = servidor;
        this.aoAtualizar = aoAtualizar == null ? () -> { } : aoAtualizar;
        this.semDados = new SemDados(this.aoAtualizar);
    }

    // ---------------------------------------------------------------------------------------------
    // O que cada aba define
    // ---------------------------------------------------------------------------------------------

    /** Usa {@code /api/servidor}. */
    abstract boolean usaServidor();

    /** Usa {@code /api/servidor/detalhe}. */
    abstract boolean usaDetalhe();

    /** Usa a leitura de segurança (SSH exec). */
    abstract boolean usaSeguranca();

    /** O conteúdo da aba (montado UMA vez); é o que fica esmaecido com dados antigos. */
    abstract JComponent montarConteudo();

    /** Algo que fica sempre à vista acima do aviso e do conteúdo (o cabeçalho do Resumo), ou {@code null}. */
    JComponent montarTopo() {
        return null;
    }

    /** A aba estica o conteúdo até o fim da janela quando sobra altura (só o Resumo: a caixa do Registro ocupa o que sobra). */
    boolean preencheAAltura() {
        return false;
    }

    /**
     * A aba mostra o corpo mesmo sem a leitura do agente? O Resumo de um servidor SSH SEM o agente mostra o que não precisa dele (Segurança, Registro) e diz nas Tendências "Instale o
     * agente"; as outras abas continuam com o cartão "sem dados".
     */
    boolean mostraCorpoSemLeitura(SituacaoDaVisao situacao) {
        return false;
    }

    /** Gancho: a aba acabou de assinar os seus tópicos (visível). */
    void aoAssinarMais() { }

    /** Gancho: a aba vai soltar os seus tópicos (escondida). */
    void aoSoltarMais() { }

    /** Gancho: o estado da conexão mudou (na EDT), depois de a aba se redesenhar. */
    void aoMudarEstadoMais(EstadoServidor novo) { }

    /** O servidor vivo ({@code null} nos testes que alimentam a aba com dados fixos). */
    final ServidorLab servidor() {
        return servidor;
    }

    /** Refaz o conteúdo com os dados de agora (só quando a aba já tem dados). */
    abstract void atualizarConteudo();

    /** Refaz o topo (sempre, com ou sem dados). */
    void atualizarTopo() { }

    /** A aba é rolável; o Resumo não pede rolagem (cabe numa tela), mas a mantém por segurança em janelas pequenas. */
    boolean rolavel() {
        return true;
    }

    /** Termina a montagem: chamado pela aba no fim do SEU construtor (os campos dela já existem). */
    final void iniciar() {
        corpo.setLayout(new BoxLayout(corpo, BoxLayout.Y_AXIS));
        JComponent conteudo = montarConteudo();
        conteudo.setAlignmentX(LEFT_ALIGNMENT);
        corpo.add(conteudo);

        pilha.setLayout(new BoxLayout(pilha, BoxLayout.Y_AXIS));
        pilha.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 20, 16, 20));
        JComponent topo = montarTopo();
        boolean primeiro = true;
        for (JComponent c : new JComponent[] {topo, faixaDeDados, esqueleto, semDados, corpo}) {
            if (c != null) {
                empilhar(c, !primeiro);
                primeiro = false;
            }
        }
        corpo.preencher(preencheAAltura());
        rolagem = new MioloRolavel(pilha, () -> Integer.MAX_VALUE, preencheAAltura());
        rolagem.getVerticalScrollBar().setUnitIncrement(18);
        add(rolagem, BorderLayout.CENTER);
        redesenhar();
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) {
                    assinar();
                } else {
                    soltar();
                }
            }
        });
    }

    /** Põe o componente na coluna com 12 px em cima; o espaço some junto com o componente escondido. */
    private void empilhar(JComponent c, boolean comEspaco) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        if (comEspaco) {
            Component folga = Box.createRigidArea(new Dimension(0, 12));
            pilha.add(folga);
            folga.setVisible(c.isVisible());
            c.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentShown(java.awt.event.ComponentEvent e) {
                    folga.setVisible(true);
                    pilha.revalidate();
                }

                @Override
                public void componentHidden(java.awt.event.ComponentEvent e) {
                    folga.setVisible(false);
                    pilha.revalidate();
                }
            });
        }
        pilha.add(c);
    }

    // ---------------------------------------------------------------------------------------------
    // Assinatura: só consulta o servidor enquanto esta aba está na tela
    // ---------------------------------------------------------------------------------------------

    /** Os tópicos que esta aba assina: {@code servidor}, {@code detalhe} e/ou {@code seguranca}. */
    public final Set<String> topicos() {
        Set<String> s = new LinkedHashSet<>();
        if (usaServidor()) {
            s.add("servidor");
        }
        if (usaDetalhe()) {
            s.add("detalhe");
        }
        if (usaSeguranca()) {
            s.add("seguranca");
        }
        return s;
    }

    /** Passa a receber os dados (e a consultar o servidor). Chamado ao ficar visível; idempotente. */
    public final void assinar() {
        if (servidor == null || inscricaoDoEstado != null) {
            return;
        }
        if (usaServidor()) {
            inscricaoDoServidor = servidor.assinar(Topico.SERVIDOR, new OuvinteDeTopico<InfoServidor>() {
                @Override
                public void aoAtualizar(InfoServidor valor) {
                    aoReceberServidor(valor);
                }

                @Override
                public void aoFalhar(ErroApi erro) {
                    aoFalharServidor(erro);
                }
            });
        }
        if (usaDetalhe()) {
            inscricaoDoDetalhe = servidor.assinar(Topico.DETALHE_SERVIDOR, new OuvinteDeTopico<DetalheServidor>() {
                @Override
                public void aoAtualizar(DetalheServidor valor) {
                    aoReceberDetalhe(valor);
                }

                @Override
                public void aoFalhar(ErroApi erro) {
                    aoFalharDetalhe();
                }
            });
        }
        if (usaSeguranca()) {
            inscricaoDaSeguranca = servidor.assinarSeguranca(new OuvinteDeTopico<ResumoDeSeguranca>() {
                @Override
                public void aoAtualizar(ResumoDeSeguranca valor) {
                    aoReceberSeguranca(valor);
                }
            });
        }
        inscricaoDoEstado = servidor.observarEstado(this::aoMudarEstado);
        aoAssinarMais();
    }

    /** Para de consultar. Chamado ao esconder; idempotente. */
    public final void soltar() {
        aoSoltarMais();
        for (Inscricao i : new Inscricao[] {inscricaoDoServidor, inscricaoDoDetalhe, inscricaoDaSeguranca, inscricaoDoEstado}) {
            if (i != null) {
                i.close();
            }
        }
        inscricaoDoServidor = null;
        inscricaoDoDetalhe = null;
        inscricaoDaSeguranca = null;
        inscricaoDoEstado = null;
    }

    public final boolean assinada() {
        return inscricaoDoEstado != null;
    }

    // ---------------------------------------------------------------------------------------------
    // Snapshots (na EDT). Públicos para os testes alimentarem a aba com dados fixos.
    // ---------------------------------------------------------------------------------------------

    public final void aoReceberServidor(InfoServidor novo) {
        this.info = novo;
        this.erroSemDados = null;
        this.ultimaLeitura = novo.quando() != null ? novo.quando() : relogio.get();
        redesenhar();
    }

    public final void aoReceberDetalhe(DetalheServidor novo) {
        this.detalhe = novo;
        this.detalheFalhou = false;
        redesenhar();
    }

    public final void aoReceberSeguranca(ResumoDeSeguranca novo) {
        this.seguranca = novo;
        redesenhar();
    }

    public final void aoMudarEstado(EstadoServidor novo) {
        this.estado = novo;
        redesenhar();
        aoMudarEstadoMais(novo);
    }

    private void aoFalharServidor(ErroApi erro) {
        if (info != null || erro instanceof ErroApi.ErroRede) {
            return; // a faixa de conexão explica; a aba mantém a última leitura
        }
        this.erroSemDados = erro;
        redesenhar();
    }

    private void aoFalharDetalhe() {
        if (detalhe == null) {
            detalheFalhou = true;
            redesenhar();
        }
    }

    /** O que o botão "Instalar agente" do aviso faz (abre a confirmação; o app nunca instala sozinho). Sem isto, o aviso não leva o botão. */
    public final void aoInstalarAgente(Runnable acao) {
        faixaDeDados.definirAoInstalar(acao);
        semDados.definirAoInstalar(acao);
        redesenhar();
    }

    /** Troca o relógio e o fuso (os testes fixam a hora para o "há 4 d" não variar). */
    final void relogio(Supplier<Instant> novo, ZoneId novoFuso) {
        this.relogio = novo;
        this.fuso = novoFuso;
        redesenhar();
    }

    final Instant agora() {
        return relogio.get();
    }

    final ZoneId fuso() {
        return fuso;
    }

    // ---------------------------------------------------------------------------------------------
    // Desenho
    // ---------------------------------------------------------------------------------------------

    private boolean temDados() {
        if (usaServidor()) {
            return info != null;
        }
        return usaSeguranca() ? seguranca != null : detalhe != null;
    }

    private Instant leitura() {
        return usaServidor() ? ultimaLeitura : seguranca == null ? null : seguranca.lidoEm();
    }

    final void redesenhar() {
        if (rolagem == null) {
            return; // ainda montando
        }
        boolean tem = temDados();
        situacao = SituacaoDaVisao.de(estado, tem, leitura(), relogio.get(), fuso);
        boolean semLeituraMasComCorpo = !tem && mostraCorpoSemLeitura(situacao);
        boolean corpoVisivel = tem || semLeituraMasComCorpo;
        atualizarTopo();
        faixaDeDados.mostrar(situacao);
        if (!tem && situacao.modo().naoInstalado() && !semLeituraMasComCorpo) {
            faixaDeDados.setVisible(false); // o cartão "sem dados" já diz isto, com o botão: sem repetir o mesmo aviso em cima
        }
        boolean erro = erroSemDados != null && !tem;
        esqueleto.setVisible(!corpoVisivel && !erro && situacao.modo() == SituacaoDaVisao.Modo.CARREGANDO);
        semDados.setVisible(!corpoVisivel && (erro || situacao.modo() != SituacaoDaVisao.Modo.CARREGANDO));
        semDados.mostrar(erro ? MensagensDeErro.daApi(erroSemDados) : null, estado == null ? null : situacao);
        corpo.setVisible(corpoVisivel);
        corpo.esmaecer(situacao.esmaecer() && tem);
        if (corpoVisivel) {
            atualizarConteudo();
        }
        refazerLayout();
    }

    final void refazerLayout() {
        pilha.revalidate();
        pilha.repaint();
        rolagem.revalidate();
    }

    /** A área rolável (para os testes conferirem se pede rolagem). */
    final MioloRolavel rolagem() {
        return rolagem;
    }

    /** A coluna de conteúdo desta aba (a altura que ela pede é o que o teste do Resumo mede). */
    final JPanel pilha() {
        return pilha;
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public final SituacaoDaVisao situacao() {
        return situacao;
    }

    final FaixaDeDados faixaDeDados() {
        return faixaDeDados;
    }

    final boolean mostraEsqueleto() {
        return esqueleto.isVisible();
    }

    final boolean mostraCorpo() {
        return corpo.isVisible();
    }

    final boolean corpoEsmaecido() {
        return corpo.esmaecido();
    }

    final SemDados semDados() {
        return semDados;
    }

    // ---------------------------------------------------------------------------------------------
    // Peças internas
    // ---------------------------------------------------------------------------------------------

    /** O corpo que pode ser pintado apagado (dados antigos), sem mexer em nenhum componente filho. */
    public static final class Esmaecivel extends Painel {
        private static final long serialVersionUID = 1L;
        private boolean esmaecido;
        private boolean preencher;

        /** Estica com a janela (o Resumo) em vez de ficar do tamanho que pede. */
        public void preencher(boolean sim) {
            this.preencher = sim;
        }

        public void esmaecer(boolean sim) {
            if (sim != esmaecido) {
                esmaecido = sim;
                repaint();
            }
        }

        public boolean esmaecido() {
            return esmaecido;
        }

        @Override
        public void paint(Graphics g) {
            if (!esmaecido) {
                super.paint(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(AlphaComposite.SrcOver.derive(0.5f));
                super.paint(g2);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, preencher ? Integer.MAX_VALUE : getPreferredSize().height);
        }
    }

    /** O aviso sobre os dados: sem conexão, agente fora ou agente incompatível, em texto e ícone. */
    public static final class FaixaDeDados extends Painel {
        private static final long serialVersionUID = 1L;
        private final Rotulo titulo = new Rotulo().negrito().tamanho(13f).truncavel();
        private final TextoCorrido texto = new TextoCorrido("", 600).tamanho(12.5f).corDoToken(Tokens.TEXTO2);
        private final Botao instalar = Botoes.miniPrimario("Instalar agente", "servidor");
        private Runnable aoInstalar;

        public FaixaDeDados() {
            super(new BorderLayout(0, 4));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(9, 14, 9, 14));
            add(titulo, BorderLayout.NORTH);
            add(texto, BorderLayout.CENTER);
            instalar.setVisible(false);
            instalar.addActionListener(e -> {
                if (aoInstalar != null) {
                    aoInstalar.run();
                }
            });
            add(Caixa.linha(0, instalar, Caixa.mola()), BorderLayout.SOUTH);
            setVisible(false);
            setName("faixa-de-dados");
        }

        /** O que o botão Instalar agente faz (a tela que a usa). */
        public void definirAoInstalar(Runnable acao) {
            this.aoInstalar = acao;
        }

        public Botao botaoInstalar() {
            return instalar;
        }

        public void mostrar(SituacaoDaVisao s) {
            setVisible(s.temAviso());
            instalar.setText(s.modo() == SituacaoDaVisao.Modo.LOCAL_A_PREPARAR ? "Preparar este computador" : "Instalar agente");
            instalar.setVisible(s.temAviso() && s.oferecerInstalar() && aoInstalar != null);
            if (!s.temAviso()) {
                return;
            }
            Gravidade g = s.gravidade();
            fundo(g.tokenDoFundo()).borda(g == Gravidade.NEUTRO ? Tokens.BORDA : g.tokenDaBorda()).raio(10);
            titulo.setText(s.titulo());
            titulo.corDoToken(g.tokenDoTexto());
            titulo.setIcon(Icones.colorido(s.icone() == null ? "alerta" : s.icone(), 16, g.tokenDoTexto()));
            titulo.setIconTextGap(8);
            texto.setText(s.texto());
        }

        public String textoDoTitulo() {
            return titulo.getText();
        }

        public String textoDaExplicacao() {
            return texto.getText();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** O cartão de "ainda não há dados": a conexão caiu antes da primeira leitura, ou a primeira leitura falhou. */
    static final class SemDados extends Painel {
        private static final long serialVersionUID = 1L;
        private final Rotulo titulo = new Rotulo().negrito().tamanho(15f);
        private final TextoCorrido texto = new TextoCorrido("", 520).corDoToken(Tokens.TEXTO2);
        private final Botao tentar;
        private final Botao instalar = Botoes.primario("Instalar agente", "servidor");
        private Runnable aoInstalar;
        private final String assunto;

        SemDados(Runnable aoTentar) {
            this(aoTentar, "do servidor");
        }

        /** @param assunto "do servidor", "do projeto": de quem são as informações que não vieram */
        SemDados(Runnable aoTentar, String assunto) {
            super(new BorderLayout(0, 8));
            this.assunto = assunto;
            fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 24, 24, 24));
            tentar = Botoes.secundario("Tentar de novo", "atualizar");
            tentar.addActionListener(e -> aoTentar.run());
            add(titulo, BorderLayout.NORTH);
            add(texto, BorderLayout.CENTER);
            instalar.setVisible(false);
            instalar.addActionListener(e -> {
                if (aoInstalar != null) {
                    aoInstalar.run();
                }
            });
            add(Caixa.linha(8, instalar, tentar, Caixa.mola()), BorderLayout.SOUTH);
            setVisible(false);
            setName("sem-dados");
        }

        void definirAoInstalar(Runnable acao) {
            this.aoInstalar = acao;
        }

        Botao botaoInstalar() {
            return instalar;
        }

        void mostrar(String erro, SituacaoDaVisao situacao) {
            instalar.setText(situacao != null && situacao.modo() == SituacaoDaVisao.Modo.LOCAL_A_PREPARAR ? "Preparar este computador" : "Instalar agente");
            instalar.setVisible(erro == null && situacao != null && situacao.oferecerInstalar() && aoInstalar != null);
            tentar.setVisible(!instalar.isVisible());
            titulo.setIcon(Icones.colorido("rede", 20, Tokens.TEXTO3));
            titulo.setIconTextGap(8);
            if (erro != null) {
                titulo.setText("Não consegui carregar as informações " + assunto);
                texto.setText(erro);
            } else if (situacao != null && situacao.temAviso()) {
                titulo.setText(situacao.titulo());
                texto.setText(situacao.texto());
            } else {
                titulo.setText("Ainda não há informações " + assunto.replaceFirst("^do ", "deste "));
                texto.setText("Assim que a conexão estiver de pé, esta aba aparece aqui.");
            }
        }

        String textoDoTitulo() {
            return titulo.getText();
        }

        String textoDaExplicacao() {
            return texto.getText();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Barras cinzas no lugar da aba, até a primeira resposta. */
    static final class Esqueleto extends ComponenteSimples {
        private static final long serialVersionUID = 1L;

        Esqueleto() {
            this("do servidor");
        }

        /** @param assunto "do servidor", "do projeto": de quem são as informações que estão chegando */
        Esqueleto(String assunto) {
            getAccessibleContext().setAccessibleName("Carregando as informações " + assunto);
            setName("esqueleto");
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(400, 300);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 300);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Tokens.cor(Tokens.ESQUELETO_A));
                int w = getWidth();
                for (int b = 0; b < 3; b++) {
                    int y = b * 96;
                    g2.fillRoundRect(0, y, 160, 12, 6, 6);
                    g2.fillRoundRect(0, y + 28, Math.max(80, w - 40), 10, 6, 6);
                    g2.fillRoundRect(0, y + 48, Math.max(80, (w - 40) * 2 / 3), 10, 6, 6);
                    g2.fillRoundRect(0, y + 68, Math.max(80, (w - 40) / 2), 10, 6, 6);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
