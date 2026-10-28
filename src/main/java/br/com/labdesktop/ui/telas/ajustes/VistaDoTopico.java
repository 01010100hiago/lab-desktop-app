package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ajuda.AtalhoDoMenu;
import br.com.labdesktop.ajuda.Destino;
import br.com.labdesktop.ajuda.GuiaDeAjuda;
import br.com.labdesktop.ajuda.TopicoDeAjuda;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Bloco;
import br.com.labdesktop.ajuda.TopicoDeAjuda.OndeFica;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Paragrafo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Passo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.Subtitulo;
import br.com.labdesktop.ajuda.TopicoDeAjuda.TabelaDeAtalhos;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.SecaoRecolhivel;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;

/**
 * Monta, com componentes do app (nunca HTML), o que a Ajuda mostra à direita: a vista de UM tópico (título e botão Copiar, "Resumo rápido", passos, "Mais detalhes" que
 * recolhe, botões que abrem telas e "Veja também") e a vista da lista rápida "Onde fica...?". Todo texto QUEBRA linha e nada passa da largura que o painel dá; só a tabela de
 * atalhos tem rolagem, e é por dentro dela. A busca destaca os trechos achados.
 */
final class VistaDoTopico {

    /** A largura preferida do texto corrido: em janela larga a linha não passa disto (legível); em janela estreita ela encolhe até caber. */
    static final int LARGURA_DO_TEXTO = 640;
    /** A altura máxima da tabela de atalhos antes de rolar por dentro dela. */
    private static final int ALTURA_MAXIMA_DA_TABELA = 260;

    /** O que a vista precisa de fora. */
    interface Fora {
        List<AtalhoDoMenu> atalhos();

        void irPara(Destino destino);

        void abrirTopico(String id);

        String rotuloDoTopico(String id);

        String textoParaCopiar(TopicoDeAjuda topico);

        br.com.labdesktop.ui.componentes.CopiadorDeTexto copiador();

        br.com.labdesktop.ui.componentes.Notificacoes avisos();
    }

    /** O resultado: o componente e o que os testes querem saber dele. */
    record Montada(JComponent componente, SecaoRecolhivel detalhes, int destaques, int destaquesNosDetalhes) { }

    private final Fora fora;
    private final String consulta;
    private int destaques;
    private int destaquesNosDetalhes;
    private SecaoRecolhivel detalhes;

    private VistaDoTopico(Fora fora, String consulta) {
        this.fora = fora;
        this.consulta = consulta == null ? "" : consulta;
    }

    /** A vista de um tópico. */
    static Montada topico(TopicoDeAjuda t, Fora fora, String consulta) {
        VistaDoTopico v = new VistaDoTopico(fora, consulta);
        return v.montarTopico(t);
    }

    /** A vista da lista rápida "Onde fica...?" (as linhas que a consulta achar; sem consulta, todas). */
    static Montada indice(List<OndeFica> linhas, Fora fora, String consulta) {
        VistaDoTopico v = new VistaDoTopico(fora, consulta);
        return v.montarIndice(linhas);
    }

    // ---------------------------------------------------------------------------------------------

    private Montada montarTopico(TopicoDeAjuda t) {
        List<Component> filhos = new ArrayList<>();
        TextoCorrido titulo = texto(t.titulo(), 20f, Tokens.TEXTO, true);
        Botao copiar = BotaoCopiar.criar("Copiar", () -> fora.textoParaCopiar(t), fora.copiador(), fora.avisos());
        copiar.setToolTipText("Copia este tópico como texto simples");
        JPanel cabecalho = new Painel(new BorderLayout(12, 0));
        cabecalho.add(titulo, BorderLayout.CENTER);
        cabecalho.add(Caixa.coluna(0, copiar), BorderLayout.EAST);
        filhos.add(cabecalho);
        if (!t.resumo().isBlank()) {
            filhos.add(resumoRapido(t.resumo()));
        }
        for (Bloco b : t.corpo()) {
            filhos.add(bloco(b, false));
        }
        if (!t.detalhes().isEmpty()) {
            List<Component> internos = new ArrayList<>();
            int antes = destaques;
            for (Bloco b : t.detalhes()) {
                internos.add(bloco(b, true));
            }
            destaquesNosDetalhes = destaques - antes;
            JComponent miolo = Caixa.coluna(8, internos.toArray(Component[]::new));
            detalhes = new SecaoRecolhivel("Mais detalhes", "Esconder os detalhes", miolo, () -> miolo.revalidate());
            detalhes.setBorder(new EmptyBorder(4, 0, 0, 0));
            filhos.add(detalhes);
        }
        // embaixo, lado a lado para o tópico caber numa tela: à esquerda os botões que abrem telas, à direita o "Veja também"
        List<Component> botoes = new ArrayList<>();
        for (var l : t.telas()) {
            Botao b = Botoes.secundario(l.texto(), "avancar");
            b.setToolTipText("Abre a tela " + l.destino().nomeDaTela());
            b.addActionListener(e -> fora.irPara(l.destino()));
            botoes.add(b);
        }
        List<Component> links = new ArrayList<>();
        if (!t.veja().isEmpty()) {
            links.add(new Rotulo("VEJA TAMBÉM").corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f));
            for (String id : t.veja()) {
                Botao l = Botoes.link(fora.rotuloDoTopico(id));
                l.addActionListener(e -> fora.abrirTopico(id));
                links.add(l);
            }
        }
        if (!botoes.isEmpty() || !links.isEmpty()) {
            JPanel rodape = new Painel(new java.awt.GridLayout(1, 2, 16, 0));
            rodape.add(Caixa.coluna(6, botoes.toArray(Component[]::new)));
            rodape.add(Caixa.coluna(2, links.toArray(Component[]::new)));
            filhos.add(rodape);
        }
        return pronta(filhos);
    }

    private Montada montarIndice(List<OndeFica> linhas) {
        List<Component> filhos = new ArrayList<>();
        filhos.add(texto("Onde fica...?", 20f, Tokens.TEXTO, true));
        filhos.add(resumoRapido("A lista rápida do caminho exato de cada coisa. O botão Abrir leva à tela, quando dá."));
        JPanel grade = new Painel(new GridBagLayout());
        int y = 0;
        for (OndeFica o : linhas) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = y;
            c.anchor = GridBagConstraints.NORTHWEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(5, 0, 5, 12);
            c.gridx = 0;
            c.weightx = 0.34;
            grade.add(texto(GuiaDeAjuda.resolver(o.oQue(), fora.atalhos()), 13.5f, Tokens.TEXTO, true), c);
            c.gridx = 1;
            c.weightx = 0.66;
            grade.add(texto(GuiaDeAjuda.resolver(o.caminho(), fora.atalhos()), 13.5f, Tokens.TEXTO2, false), c);
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.insets = new Insets(2, 0, 2, 0);
            if (o.destino() != null) {
                Botao ir = Botoes.mini("Abrir", "avancar");
                ir.setToolTipText("Abre a tela " + o.destino().nomeDaTela());
                ir.addActionListener(e -> fora.irPara(o.destino()));
                grade.add(ir, c);
            } else {
                grade.add(Caixa.espaco(1), c);
            }
            y++;
        }
        if (linhas.isEmpty()) {
            filhos.add(texto("Nenhuma linha com essas palavras.", 13.5f, Tokens.TEXTO2, false));
        } else {
            filhos.add(grade);
        }
        return pronta(filhos);
    }

    private Montada pronta(List<Component> filhos) {
        JComponent coluna = Caixa.coluna(9, filhos.toArray(Component[]::new));
        coluna.setBorder(new EmptyBorder(14, 20, 16, 20));
        return new Montada(coluna, detalhes, destaques, destaquesNosDetalhes);
    }

    private JComponent resumoRapido(String frase) {
        Rotulo etiqueta = new Rotulo("RESUMO RÁPIDO").corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f);
        TextoCorrido t = texto(GuiaDeAjuda.resolver(frase, fora.atalhos()), 14f, Tokens.TEXTO, false);
        Painel p = new Painel(new BorderLayout());
        p.fundo(Tokens.SUPERFICIE2).borda(Tokens.BORDA).raio(10);
        JComponent miolo = Caixa.coluna(4, etiqueta, t);
        miolo.setBorder(new EmptyBorder(8, 12, 10, 12));
        p.add(miolo, BorderLayout.CENTER);
        return p;
    }

    private Component bloco(Bloco b, boolean emDetalhes) {
        return switch (b) {
            case Paragrafo p -> texto(GuiaDeAjuda.resolver(p.texto(), fora.atalhos()), 14f, Tokens.TEXTO, false);
            case Subtitulo s -> texto(s.texto(), 15f, Tokens.TEXTO, true);
            case Passo p -> passo(p);
            case TabelaDeAtalhos x -> tabelaDeAtalhos();
        };
    }

    private JComponent passo(Passo p) {
        Rotulo numero = new Rotulo(p.numero() + ".").corDoToken(Tokens.TEXTO2).negrito();
        numero.setPreferredSize(new Dimension(26, numero.getPreferredSize().height));
        numero.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        JPanel linha = new Painel(new BorderLayout(6, 0));
        linha.add(numero, BorderLayout.WEST);
        linha.add(texto(GuiaDeAjuda.resolver(p.texto(), fora.atalhos()), 14f, Tokens.TEXTO, false), BorderLayout.CENTER);
        return linha;
    }

    /** A tabela dos atalhos, gerada do menu: duas colunas (tecla e o que faz); passa de certa altura, rola por DENTRO dela. */
    private JComponent tabelaDeAtalhos() {
        Painel grade = new GradeQueAcompanhaALargura();
        grade.fundo(Tokens.SUPERFICIE);
        int y = 0;
        for (AtalhoDoMenu a : fora.atalhos()) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = y++;
            c.anchor = GridBagConstraints.NORTHWEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(4, 12, 4, 12);
            c.gridx = 0;
            c.weightx = 0.3;
            Rotulo tecla = new Rotulo(a.tecla()).mono(13f);
            grade.add(tecla, c);
            c.gridx = 1;
            c.weightx = 0.7;
            grade.add(texto(a.descricao(), 13.5f, Tokens.TEXTO, false), c);
        }
        JScrollPane rolagem = new JScrollPane(grade, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                // a vista acompanha a largura; a altura é a da tabela, até o máximo (e a rolagem é por dentro)
                int altura = Math.min(ALTURA_MAXIMA_DA_TABELA, grade.getPreferredSize().height + 2);
                return new Dimension(super.getPreferredSize().width, altura);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        rolagem.setBorder(br.com.labdesktop.ui.componentes.Bordas.contorno(Tokens.BORDA));
        rolagem.getVerticalScrollBar().setUnitIncrement(18);
        rolagem.getAccessibleContext().setAccessibleName("Tabela de atalhos de teclado");
        return rolagem;
    }

    /** A grade da tabela dentro da rolagem: acompanha a LARGURA da janela dela (o texto quebra, nunca aparece rolagem para o lado). */
    private static final class GradeQueAcompanhaALargura extends Painel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;

        GradeQueAcompanhaALargura() {
            super(new GridBagLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visivel, int orientacao, int direcao) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visivel, int orientacao, int direcao) {
            return Math.max(24, visivel.height - 24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private TextoCorrido texto(String conteudo, float tamanho, String token, boolean negrito) {
        TextoCorrido t = new TextoCorrido(conteudo, LARGURA_DO_TEXTO).corDoToken(token).tamanho(tamanho);
        if (negrito) {
            t.negrito();
        }
        destacar(t);
        return t;
    }

    /** Marca os trechos que a busca achou (sem diferença de maiúscula nem de acento). */
    private void destacar(TextoCorrido t) {
        if (consulta.isBlank()) {
            return;
        }
        for (int[] trecho : GuiaDeAjuda.trechos(t.getText(), consulta)) {
            try {
                t.getHighlighter().addHighlight(trecho[0], trecho[1], new DefaultHighlighter.DefaultHighlightPainter(null) {
                    @Override
                    public java.awt.Color getColor() {
                        return Tokens.cor(Tokens.ATENCAO_SUAVE); // lida na hora de pintar: a troca de tema vale sem refazer a vista
                    }
                });
                destaques++;
            } catch (BadLocationException e) {
                // um trecho que não cabe no texto (não deve acontecer): ignora
            }
        }
    }

    /** Aplica o que o chamador precisa depois de pronta (hoje, nada: existe para os testes percorrerem os componentes). */
    static void percorrer(Container c, Consumer<Component> acao) {
        acao.accept(c);
        for (Component f : c.getComponents()) {
            if (f instanceof Container k) {
                percorrer(k, acao);
            } else {
                acao.accept(f);
            }
        }
    }
}
