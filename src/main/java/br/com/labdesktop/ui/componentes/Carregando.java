package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.componentes.EstadoDeCarga.Fase;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * O "carregando" padrão do app: um arco que gira com a frase ("Carregando projetos..."), sobre barras cinzas no lugar do que vai chegar. É o que toda tela que
 * espera o servidor mostra, NA HORA em que a tela aparece (sem esperar nada), no lugar das barras mudas de antes. O estado ({@link EstadoDeCarga}) é um modelo
 * puro: passado o prazo (15 s) o arco some e entra "Demorou mais que o normal." com o botão "Tentar de novo", que a tela liga ao que ela faz para consultar de novo;
 * um erro da consulta usa a mesma saída. Quem usa só chama {@link #iniciar()} quando começa a esperar, {@link #chegou()} quando o dado chega e {@link #falhou}.
 *
 * <p>O arco e o relógio do prazo só andam enquanto a tela está à vista (uma tela escondida não gasta nada).
 */
public final class Carregando extends Painel {

    private static final long serialVersionUID = 1L;
    /** Quantas barras cinzas e de que altura (a lista usa as suas, em linhas). */
    private static final int ALTURA_DAS_BARRAS = 220;

    private final String assunto;
    private final Supplier<Instant> relogio;
    private final Duration prazo;
    private final Rotulo frase = new Rotulo().corDoToken(Tokens.TEXTO2).negrito();
    private final Rotulo dica = Rotulo.dica("");
    private final Botao tentarDeNovo = Botoes.mini(EstadoDeCarga.BOTAO_TENTAR_DE_NOVO, "atualizar");
    private final Barras barras = new Barras();
    private final IconeOcupado girando = new IconeOcupado(16, Tokens.TEXTO2);
    private final javax.swing.Icon alerta = Icones.colorido("alerta", 16, Tokens.ATENCAO);
    private final Timer relogioDoPrazo;
    private final Timer quadros;
    private Runnable aoTentarDeNovo = () -> { };
    private EstadoDeCarga estado;

    /** O prazo padrão e o relógio de verdade. */
    public Carregando(String assunto) {
        this(assunto, Instant::now, EstadoDeCarga.PRAZO_PADRAO);
    }

    /**
     * @param assunto o que está chegando, no plural ou com artigo: "projetos", "o projeto", "as informações do servidor" (a frase é "Carregando " + assunto + "...")
     * @param relogio o "agora" (os testes andam o relógio sem esperar)
     * @param prazo   quanto esperar antes de dizer que demorou
     */
    public Carregando(String assunto, Supplier<Instant> relogio, Duration prazo) {
        super(new BorderLayout());
        this.assunto = assunto;
        this.relogio = relogio;
        this.prazo = prazo;
        this.estado = EstadoDeCarga.carregando(relogio.get(), prazo);
        setOpaque(false);
        setName("esqueleto");
        frase.setIcon(girando);
        frase.setIconTextGap(8);
        tentarDeNovo.setToolTipText("Pergunta ao servidor de novo");
        tentarDeNovo.addActionListener(e -> {
            Runnable acao = aoTentarDeNovo;
            tentarDeNovo();
            acao.run();
        });
        JComponent cabecalho = Caixa.coluna(4, Caixa.linha(12, frase, tentarDeNovo, Caixa.mola()), dica);
        cabecalho.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 4, 14, 4));
        add(cabecalho, BorderLayout.NORTH);
        add(barras, BorderLayout.CENTER);
        relogioDoPrazo = new Timer(500, e -> passouOTempo());
        quadros = new Timer(60, e -> frase.repaint());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing() && estado.fase() == Fase.CARREGANDO) {
                    // a espera conta desde que a tela está à vista (uma página montada de manhã e aberta de tarde não nasce "demorando")
                    estado = EstadoDeCarga.carregando(relogio.get(), prazo);
                }
                ligarOuDesligarRelogios();
            }
        });
        mostrar();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** O que o botão "Tentar de novo" faz além de voltar a "Carregando" (a tela pergunta ao servidor de novo). */
    public Carregando aoTentarDeNovo(Runnable acao) {
        this.aoTentarDeNovo = acao == null ? () -> { } : acao;
        return this;
    }

    /** Começa a esperar agora (a tela apareceu, ou o usuário pediu para atualizar sem nada na tela). */
    public void iniciar() {
        estado = EstadoDeCarga.carregando(relogio.get(), prazo);
        mostrar();
        ligarOuDesligarRelogios();
    }

    /** O dado chegou: o chamador esconde este componente; aqui só para os relógios. */
    public void chegou() {
        estado = estado.chegou();
        mostrar();
        ligarOuDesligarRelogios();
    }

    /** A consulta falhou: mostra a frase do erro com a saída "Tentar de novo". */
    public void falhou(String mensagem) {
        estado = estado.falhou(mensagem);
        mostrar();
        ligarOuDesligarRelogios();
    }

    private void tentarDeNovo() {
        estado = estado.tentarDeNovo(relogio.get());
        mostrar();
        ligarOuDesligarRelogios();
    }

    /** O relógio andou (o próprio timer chama; os testes também). */
    public void passouOTempo() {
        EstadoDeCarga novo = estado.passou(relogio.get());
        if (novo != estado) {
            estado = novo;
            mostrar();
            ligarOuDesligarRelogios();
        }
    }

    public EstadoDeCarga estado() {
        return estado;
    }

    /** A frase que está na tela (para os testes). */
    public String textoDaFrase() {
        return frase.getText();
    }

    public String textoDaDica() {
        return dica.getText();
    }

    public Botao botaoTentarDeNovo() {
        return tentarDeNovo;
    }

    private void mostrar() {
        Fase f = estado.fase();
        switch (f) {
            case CARREGANDO -> {
                frase.setText(EstadoDeCarga.frase(assunto));
                frase.setIcon(girando);
                dica.setText("");
            }
            case DEMOROU -> {
                frase.setText(EstadoDeCarga.FRASE_DEMOROU);
                frase.setIcon(alerta);
                dica.setText(EstadoDeCarga.DICA_DEMOROU);
            }
            case ERRO -> {
                frase.setText(estado.mensagem().isBlank() ? "Não consegui carregar." : estado.mensagem());
                frase.setIcon(alerta);
                dica.setText("");
            }
            case PRONTO -> { }
        }
        dica.setVisible(!dica.getText().isEmpty());
        tentarDeNovo.setVisible(estado.oferecerTentarDeNovo());
        getAccessibleContext().setAccessibleName(frase.getText());
        revalidate();
        repaint();
    }

    /** O arco gira e o prazo conta só com a tela à vista e numa espera de verdade. */
    private void ligarOuDesligarRelogios() {
        boolean esperando = isShowing() && (estado.fase() == Fase.CARREGANDO || estado.fase() == Fase.DEMOROU);
        if (esperando && estado.fase() == Fase.CARREGANDO) {
            relogioDoPrazo.start();
            quadros.start();
        } else {
            relogioDoPrazo.stop();
            quadros.stop();
        }
    }

    /** As barras cinzas do lugar do conteúdo (a forma de três blocos de texto). */
    private static final class Barras extends ComponenteSimples {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(400, ALTURA_DAS_BARRAS);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Tokens.cor(Tokens.ESQUELETO_A));
                int w = getWidth();
                for (int b = 0; b < 3; b++) {
                    int y = b * 72;
                    g2.fillRoundRect(4, y, 160, 12, 6, 6);
                    g2.fillRoundRect(4, y + 24, Math.max(80, w - 40), 10, 6, 6);
                    g2.fillRoundRect(4, y + 42, Math.max(80, (w - 40) * 2 / 3), 10, 6, 6);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
