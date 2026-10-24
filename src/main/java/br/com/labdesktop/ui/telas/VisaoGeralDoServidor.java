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
import java.util.function.Supplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A "Visão geral" da página do servidor, para quem não é técnico. De cima para baixo: o cabeçalho (nome, estado,
 * máquina e sistema, tempo ligado), a primeira linha com no máximo seis indicadores (Saúde, CPU, Memória, Disco,
 * Containers, Segurança), e os blocos de monitoramento (Segurança, Containers e sessões do Claude, Tendências, Processos
 * e, recolhidos, os Detalhes da máquina).
 *
 * <p>Só desenha snapshots imutáveis. Assina {@code SERVIDOR}, {@code DETALHE_SERVIDOR} e a leitura de segurança ao ficar
 * visível ({@code HierarchyListener}) e solta ao esconder: sem esta aba na tela, nenhuma dessas consultas (o detalhe é o
 * mais pesado; a segurança roda comandos por SSH) acontece. Estados: carregando (esqueleto), sem conexão ou agente fora
 * (os últimos dados esmaecidos, com a hora e a explicação em texto), sem dado ainda e dado ausente por campo (traço).
 */
public final class VisaoGeralDoServidor extends Painel {

    private static final long serialVersionUID = 1L;

    private final ServidorLab servidor;
    private final CabecalhoDoServidor cabecalho;
    private final FaixaDeDados faixaDeDados = new FaixaDeDados();
    private final Esqueleto esqueleto = new Esqueleto();
    private final SemDados semDados;
    private final FaixaDeKpis kpis = new FaixaDeKpis();
    private final BlocoDeSeguranca seguranca = new BlocoDeSeguranca();
    private final BlocoDeContainers containers = new BlocoDeContainers();
    private final BlocoDeTendencias tendencias = new BlocoDeTendencias();
    private final BlocoDeProcessos processos = new BlocoDeProcessos();
    private final DetalhesDaMaquina detalhes;
    private final Esmaecivel corpo = new Esmaecivel();
    private final JPanel conteudo = new Painel();
    private final MioloRolavel rolagem;

    private Supplier<Instant> relogio = Instant::now;
    private ZoneId fuso = ZoneId.systemDefault();
    private EstadoServidor estado;
    private InfoServidor info;
    private DetalheServidor detalhe;
    private ResumoDeSeguranca resumoDeSeguranca;
    private Instant ultimaLeitura;
    private boolean detalheFalhou;
    private ErroApi erroSemDados;
    private SituacaoDaVisao situacao = SituacaoDaVisao.de(null, false, null, Instant.now(), ZoneId.systemDefault());
    private Inscricao inscricaoDoServidor;
    private Inscricao inscricaoDoDetalhe;
    private Inscricao inscricaoDaSeguranca;
    private Inscricao inscricaoDoEstado;

    /**
     * @param servidor    o servidor vivo ({@code null} nos testes que alimentam a tela com dados fixos)
     * @param aoAtualizar o que o botão Atualizar faz (consulta já tudo o que está assinado)
     */
    public VisaoGeralDoServidor(ServidorLab servidor, Runnable aoAtualizar) {
        super(new BorderLayout());
        this.servidor = servidor;
        this.cabecalho = new CabecalhoDoServidor(aoAtualizar == null ? () -> { } : aoAtualizar);
        this.semDados = new SemDados(aoAtualizar == null ? () -> { } : aoAtualizar);
        this.detalhes = new DetalhesDaMaquina(this::refazerLayout);

        corpo.setLayout(new BoxLayout(corpo, BoxLayout.Y_AXIS));
        JComponent[] blocos = {kpis, seguranca, containers, tendencias, processos, detalhes};
        for (int i = 0; i < blocos.length; i++) {
            empilhar(corpo, blocos[i], i > 0);
        }

        conteudo.setLayout(new BoxLayout(conteudo, BoxLayout.Y_AXIS));
        conteudo.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 20, 20, 20));
        JComponent[] pilha = {faixaDeDados, cabecalho, esqueleto, semDados, corpo};
        for (int i = 0; i < pilha.length; i++) {
            empilhar(conteudo, pilha[i], i > 0);
        }
        rolagem = new MioloRolavel(conteudo, () -> Integer.MAX_VALUE);
        rolagem.getVerticalScrollBar().setUnitIncrement(18);
        add(rolagem, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName("Visão geral do servidor");

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

    /**
     * Põe o componente numa coluna com 14 px de espaço em cima, e o espaço some junto com o componente (uma peça escondida
     * não deixa um buraco na página).
     */
    private static void empilhar(JPanel coluna, JComponent c, boolean comEspaco) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        if (comEspaco) {
            Component folga = Box.createRigidArea(new Dimension(0, 14));
            coluna.add(folga);
            folga.setVisible(c.isVisible());
            c.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentShown(java.awt.event.ComponentEvent e) {
                    folga.setVisible(true);
                    coluna.revalidate();
                }

                @Override
                public void componentHidden(java.awt.event.ComponentEvent e) {
                    folga.setVisible(false);
                    coluna.revalidate();
                }
            });
        }
        coluna.add(c);
    }

    // ---------------------------------------------------------------------------------------------
    // Assinatura: só consulta o servidor enquanto esta aba está na tela
    // ---------------------------------------------------------------------------------------------

    /** Passa a receber os dados (e a consultar o servidor). Chamado ao ficar visível; idempotente. */
    public void assinar() {
        if (servidor == null || inscricaoDoServidor != null) {
            return;
        }
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
        inscricaoDoDetalhe = servidor.assinar(Topico.DETALHE_SERVIDOR, new OuvinteDeTopico<DetalheServidor>() {
            @Override
            public void aoAtualizar(DetalheServidor valor) {
                aoReceberDetalhe(valor);
            }

            @Override
            public void aoFalhar(ErroApi erro) {
                aoFalharDetalhe(erro);
            }
        });
        inscricaoDaSeguranca = servidor.assinarSeguranca(new OuvinteDeTopico<ResumoDeSeguranca>() {
            @Override
            public void aoAtualizar(ResumoDeSeguranca valor) {
                aoReceberSeguranca(valor);
            }
        });
        inscricaoDoEstado = servidor.observarEstado(this::aoMudarEstado);
    }

    /** Para de consultar. Chamado ao esconder; idempotente. */
    public void soltar() {
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

    boolean assinada() {
        return inscricaoDoServidor != null;
    }

    // ---------------------------------------------------------------------------------------------
    // Snapshots (na EDT)
    // ---------------------------------------------------------------------------------------------

    /** Chegou {@code /api/servidor}. Público para os testes alimentarem a tela com dados fixos. */
    public void aoReceberServidor(InfoServidor novo) {
        this.info = novo;
        this.erroSemDados = null;
        this.ultimaLeitura = novo.quando() != null ? novo.quando() : relogio.get();
        redesenhar();
    }

    /** Chegou {@code /api/servidor/detalhe}. */
    public void aoReceberDetalhe(DetalheServidor novo) {
        this.detalhe = novo;
        this.detalheFalhou = false;
        redesenhar();
    }

    /** Chegou a leitura de segurança. */
    public void aoReceberSeguranca(ResumoDeSeguranca novo) {
        this.resumoDeSeguranca = novo;
        redesenhar();
    }

    /** O estado da conexão mudou. */
    public void aoMudarEstado(EstadoServidor novo) {
        this.estado = novo;
        redesenhar();
    }

    private void aoFalharServidor(ErroApi erro) {
        if (info != null || erro instanceof ErroApi.ErroRede) {
            return; // a faixa de conexão explica; a tela mantém a última leitura
        }
        this.erroSemDados = erro;
        redesenhar();
    }

    private void aoFalharDetalhe(ErroApi erro) {
        if (detalhe == null) {
            detalheFalhou = true;
            redesenhar();
        }
    }

    /** Troca o relógio (os testes fixam a hora para o "há 4 d" não variar). */
    void relogio(Supplier<Instant> novo, ZoneId novoFuso) {
        this.relogio = novo;
        this.fuso = novoFuso;
        redesenhar();
    }

    // ---------------------------------------------------------------------------------------------
    // Desenho
    // ---------------------------------------------------------------------------------------------

    private void redesenhar() {
        Instant agora = relogio.get();
        situacao = SituacaoDaVisao.de(estado, info != null, ultimaLeitura, agora, fuso);
        cabecalho.atualizar(estado, info, ultimaLeitura, fuso);
        faixaDeDados.mostrar(situacao);

        boolean temDados = info != null;
        boolean erro = erroSemDados != null && !temDados;
        esqueleto.setVisible(!temDados && !erro && situacao.modo() == SituacaoDaVisao.Modo.CARREGANDO);
        semDados.setVisible(!temDados && (erro || situacao.modo() != SituacaoDaVisao.Modo.CARREGANDO));
        semDados.mostrar(erro ? MensagensDeErro.daApi(erroSemDados) : null, estado == null ? null : situacao);
        corpo.setVisible(temDados);
        corpo.esmaecer(situacao.esmaecer());

        if (temDados) {
            kpis.atualizar(ModeloDeKpis.de(info, resumoDeSeguranca));
            seguranca.atualizar(resumoDeSeguranca, fuso);
            containers.atualizar(info, agora, fuso);
            tendencias.atualizar(info);
            processos.atualizar(info, detalhe);
            detalhes.atualizar(info, detalhe, detalheFalhou);
        }
        refazerLayout();
    }

    private void refazerLayout() {
        conteudo.revalidate();
        conteudo.repaint();
        rolagem.revalidate();
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public SituacaoDaVisao situacao() {
        return situacao;
    }

    FaixaDeKpis faixaDeKpis() {
        return kpis;
    }

    BlocoDeSeguranca blocoDeSeguranca() {
        return seguranca;
    }

    BlocoDeContainers blocoDeContainers() {
        return containers;
    }

    BlocoDeTendencias blocoDeTendencias() {
        return tendencias;
    }

    BlocoDeProcessos blocoDeProcessos() {
        return processos;
    }

    DetalhesDaMaquina detalhesDaMaquina() {
        return detalhes;
    }

    CabecalhoDoServidor cabecalho() {
        return cabecalho;
    }

    FaixaDeDados faixaDeDados() {
        return faixaDeDados;
    }

    boolean mostraEsqueleto() {
        return esqueleto.isVisible();
    }

    boolean mostraCorpo() {
        return corpo.isVisible();
    }

    boolean corpoEsmaecido() {
        return corpo.esmaecido();
    }

    SemDados semDados() {
        return semDados;
    }

    // ---------------------------------------------------------------------------------------------
    // Peças internas
    // ---------------------------------------------------------------------------------------------

    /** O corpo que pode ser pintado apagado (dados antigos), sem mexer em nenhum componente filho. */
    private static final class Esmaecivel extends Painel {
        private static final long serialVersionUID = 1L;
        private boolean esmaecido;

        void esmaecer(boolean sim) {
            if (sim != esmaecido) {
                esmaecido = sim;
                repaint();
            }
        }

        boolean esmaecido() {
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
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** O aviso sobre os dados: sem conexão, agente fora ou agente incompatível, em texto e ícone. */
    static final class FaixaDeDados extends Painel {
        private static final long serialVersionUID = 1L;
        private final Rotulo titulo = new Rotulo().negrito().tamanho(13f).truncavel();
        private final TextoCorrido texto = new TextoCorrido("", 600).tamanho(12.5f).corDoToken(Tokens.TEXTO2);

        FaixaDeDados() {
            super(new BorderLayout(0, 4));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 14, 10, 14));
            add(titulo, BorderLayout.NORTH);
            add(texto, BorderLayout.CENTER);
            setVisible(false);
            setName("faixa-de-dados");
        }

        void mostrar(SituacaoDaVisao s) {
            setVisible(s.temAviso());
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

    /** O cartão de "ainda não há dados": a conexão caiu antes da primeira leitura, ou a primeira leitura falhou. */
    static final class SemDados extends Painel {
        private static final long serialVersionUID = 1L;
        private final Rotulo titulo = new Rotulo().negrito().tamanho(15f);
        private final TextoCorrido texto = new TextoCorrido("", 520).corDoToken(Tokens.TEXTO2);
        private final Botao tentar;

        SemDados(Runnable aoTentar) {
            super(new BorderLayout(0, 8));
            fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 24, 24, 24));
            tentar = Botoes.secundario("Tentar de novo", "atualizar");
            tentar.addActionListener(e -> aoTentar.run());
            add(titulo, BorderLayout.NORTH);
            add(texto, BorderLayout.CENTER);
            add(Caixa.linha(0, tentar, Caixa.mola()), BorderLayout.SOUTH);
            setVisible(false);
            setName("sem-dados");
        }

        void mostrar(String erro, SituacaoDaVisao situacao) {
            titulo.setIcon(Icones.colorido("rede", 20, Tokens.TEXTO3));
            titulo.setIconTextGap(8);
            if (erro != null) {
                titulo.setText("Não consegui carregar as informações do servidor");
                texto.setText(erro);
            } else if (situacao != null && situacao.temAviso()) {
                titulo.setText(situacao.titulo());
                texto.setText(situacao.texto());
            } else {
                titulo.setText("Ainda não há informações deste servidor");
                texto.setText("Assim que a conexão estiver de pé, a visão geral aparece aqui.");
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

    /** Barras cinzas no lugar da página, até a primeira resposta. */
    private static final class Esqueleto extends ComponenteSimples {
        private static final long serialVersionUID = 1L;

        Esqueleto() {
            getAccessibleContext().setAccessibleName("Carregando as informações do servidor");
            setName("esqueleto");
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(400, 330);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 330);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Tokens.cor(Tokens.ESQUELETO_A));
                int w = getWidth();
                int celula = Math.max(60, w / 6);
                for (int i = 0; i < 6; i++) { // a linha de indicadores
                    int x = i * celula;
                    g2.fillRoundRect(x + 14, 8, 60, 10, 6, 6);
                    g2.fillRoundRect(x + 14, 28, Math.min(90, celula - 40), 20, 6, 6);
                    g2.fillRoundRect(x + 14, 58, Math.min(110, celula - 30), 10, 6, 6);
                }
                for (int b = 0; b < 3; b++) { // os blocos de baixo
                    int y = 100 + b * 76;
                    g2.fillRoundRect(0, y, 160, 12, 6, 6);
                    g2.fillRoundRect(0, y + 24, Math.max(80, w - 40), 10, 6, 6);
                    g2.fillRoundRect(0, y + 44, Math.max(80, (w - 40) * 2 / 3), 10, 6, 6);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
