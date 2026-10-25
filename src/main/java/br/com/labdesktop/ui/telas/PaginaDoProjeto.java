package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.componentes.AreaDaPagina;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.telas.PaginaDoServidor.Esmaecivel;
import br.com.labdesktop.ui.telas.PaginaDoServidor.Esqueleto;
import br.com.labdesktop.ui.telas.PaginaDoServidor.FaixaDeDados;
import br.com.labdesktop.ui.telas.PaginaDoServidor.SemDados;
import br.com.labdesktop.servidor.Inscricao;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.HierarchyEvent;
import java.util.List;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A base de cada aba da página de UM projeto (Visão geral, Logs, Sessão do Claude, Arquivos, Backups e Consumo), irmã da
 * {@link PaginaDoServidor}. Cuida do que é igual em todas: os estados da página (carregando com esqueleto, sem conexão com os
 * últimos dados esmaecidos e a hora, agente fora, sem dado ainda), a área rolável (rola só se a janela for menor que a aba) e
 * a assinatura dos tópicos PRÓPRIOS da aba, só enquanto ela está visível ({@code HierarchyListener}: uma aba escondida no
 * {@code CardLayout} não está {@code showing}, então a regra vale sozinha).
 *
 * <p>O detalhe do projeto NÃO é assinado aqui: quem assina é a {@link TelaProjeto} (uma vez, para a página toda) e ela manda cada
 * aba se redesenhar com o retrato novo, que mora no {@link ContextoDoProjeto}. Cada aba só diz o que mais consulta
 * ({@link #topicosProprios()}), monta o seu conteúdo e o atualiza. Só desenha snapshots e tudo acontece na EDT.
 */
abstract class PaginaDoProjeto extends Painel {

    private static final long serialVersionUID = 1L;

    protected final transient ContextoDoProjeto ctx;
    private final FaixaDeDados faixaDeDados = new FaixaDeDados();
    private final Esqueleto esqueleto = new Esqueleto("do projeto");
    private final SemDados semDados;
    private final Esmaecivel corpo = new Esmaecivel();
    private final JPanel pilha = new Painel();
    private AreaDaPagina rolagem;
    private SituacaoDaVisao situacao = SituacaoDaVisao.de(null, false, null, java.time.Instant.now(), java.time.ZoneId.systemDefault());
    private boolean assinada;

    protected PaginaDoProjeto(ContextoDoProjeto ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.semDados = new SemDados(ctx.aoAtualizar == null ? () -> { } : ctx.aoAtualizar, "do projeto");
    }

    // ---------------------------------------------------------------------------------------------
    // O que cada aba define
    // ---------------------------------------------------------------------------------------------

    /** O conteúdo da aba (montado UMA vez); é o que fica esmaecido com dados antigos. */
    abstract JComponent montarConteudo();

    /** Refaz o conteúdo com o retrato de agora (só quando já há detalhe). */
    abstract void atualizarConteudo();

    /** Os tópicos que a aba assina ALÉM do detalhe do projeto (vazio: só o detalhe). Só vale com ela visível. */
    Set<String> topicosProprios() {
        return Set.of();
    }

    /** A aba ficou visível: assina o que usa. */
    void aoAparecer() { }

    /** A aba saiu da tela: solta tudo o que assinou (e cancela o que estava em voo). */
    void aoSumir() { }

    /** O conteúdo cresce até o fim da janela (logs, tela do Claude) em vez de ficar colado no topo. */
    boolean preencheAAltura() {
        return false;
    }

    /** Termina a montagem: chamado pela aba no fim do SEU construtor (os campos dela já existem). */
    final void iniciar() {
        boolean preenche = preencheAAltura();
        corpo.setLayout(preenche ? new BorderLayout() : new BoxLayout(corpo, BoxLayout.Y_AXIS));
        JComponent conteudo = montarConteudo();
        if (preenche) {
            corpo.add(conteudo, BorderLayout.CENTER);
        } else {
            conteudo.setAlignmentX(LEFT_ALIGNMENT);
            corpo.add(conteudo);
        }

        pilha.setLayout(preenche ? new BorderLayout() : new BoxLayout(pilha, BoxLayout.Y_AXIS));
        pilha.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 20, 14, 20));
        if (preenche) {
            // avisos e esqueleto em cima; o conteúdo (que cresce com a janela) ocupa o resto
            JPanel avisos = new Painel() {
                private static final long serialVersionUID = 1L;

                /** 10 px de ar entre os avisos e o conteúdo, mas só enquanto há aviso à vista. */
                @Override
                public Dimension getPreferredSize() {
                    Dimension d = super.getPreferredSize();
                    boolean algum = faixaDeDados.isVisible() || esqueleto.isVisible() || semDados.isVisible();
                    return algum ? new Dimension(d.width, d.height + 10) : d;
                }
            };
            avisos.setLayout(new BoxLayout(avisos, BoxLayout.Y_AXIS));
            boolean primeiro = true;
            for (JComponent c : new JComponent[] {faixaDeDados, esqueleto, semDados}) {
                empilhar(avisos, c, !primeiro);
                primeiro = false;
            }
            pilha.add(avisos, BorderLayout.NORTH);
            pilha.add(corpo, BorderLayout.CENTER);

        } else {
            boolean primeiro = true;
            for (JComponent c : new JComponent[] {faixaDeDados, esqueleto, semDados, corpo}) {
                empilhar(pilha, c, !primeiro);
                primeiro = false;
            }
        }
        rolagem = new AreaDaPagina(pilha, preenche);
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
    private static void empilhar(JPanel destino, JComponent c, boolean comEspaco) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        if (comEspaco) {
            Component folga = Box.createRigidArea(new Dimension(0, 12));
            destino.add(folga);
            folga.setVisible(c.isVisible());
            c.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentShown(java.awt.event.ComponentEvent e) {
                    folga.setVisible(true);
                    destino.revalidate();
                }

                @Override
                public void componentHidden(java.awt.event.ComponentEvent e) {
                    folga.setVisible(false);
                    destino.revalidate();
                }
            });
        }
        destino.add(c);
    }

    // ---------------------------------------------------------------------------------------------
    // Assinatura
    // ---------------------------------------------------------------------------------------------

    /** A aba apareceu: assina os tópicos próprios. Idempotente. */
    final void assinar() {
        if (assinada) {
            return;
        }
        assinada = true;
        aoAparecer();
    }

    /** A aba sumiu: solta. Idempotente. */
    final void soltar() {
        if (!assinada) {
            return;
        }
        assinada = false;
        aoSumir();
    }

    final boolean assinada() {
        return assinada;
    }

    /** Fecha uma inscrição (se houver) e devolve {@code null}, para {@code ins = soltar(ins)}. */
    static Inscricao soltar(Inscricao i) {
        if (i != null) {
            i.close();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Desenho
    // ---------------------------------------------------------------------------------------------

    /** Redesenha com o retrato do contexto (a {@link TelaProjeto} chama quando ele muda). */
    final void redesenhar() {
        if (rolagem == null) {
            return; // ainda montando
        }
        boolean tem = ctx.detalhe != null;
        situacao = SituacaoDaVisao.de(ctx.estado, tem, ctx.ultimaLeitura, ctx.agora(), ctx.fuso);
        faixaDeDados.mostrar(situacao);
        boolean erro = ctx.erroSemDados != null && !tem;
        esqueleto.setVisible(!tem && !erro && situacao.modo() == SituacaoDaVisao.Modo.CARREGANDO);
        semDados.setVisible(!tem && (erro || situacao.modo() != SituacaoDaVisao.Modo.CARREGANDO));
        semDados.mostrar(erro ? MensagensDeErro.daApi(ctx.erroSemDados) : null, ctx.estado == null ? null : situacao);
        corpo.setVisible(tem);
        corpo.esmaecer(situacao.esmaecer());
        if (tem) {
            atualizarConteudo();
        }
        pilha.revalidate();
        pilha.repaint();
        rolagem.revalidate();
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    final SituacaoDaVisao situacao() {
        return situacao;
    }

    final AreaDaPagina rolagem() {
        return rolagem;
    }

    final JPanel pilha() {
        return pilha;
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

    /** Os textos de tudo que a aba MOSTRA, sem o que está escondido (para os testes conferirem o conteúdo). */
    final String textos() {
        StringBuilder sb = new StringBuilder();
        juntarVisiveis(corpo, sb);
        return sb.toString();
    }

    private static void juntarVisiveis(Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof javax.swing.JLabel l && l.getText() != null && !l.getText().isEmpty()) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.text.JTextComponent t && t.getText() != null && !t.getText().isEmpty()) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof br.com.labdesktop.ui.componentes.Selo s && !s.texto().isEmpty()) {
            sb.append(s.texto()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton b && b.getText() != null && !b.getText().isEmpty()) {
            sb.append(b.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (Component f : k.getComponents()) {
                juntarVisiveis(f, sb);
            }
        }
    }

    /** O componente está escondido por ele ou por qualquer ancestral dentro da aba (um cartão de outra tabela, por exemplo). */
    final boolean aparece(Component c) {
        for (Component x = c; x != null && x != this; x = x.getParent()) {
            if (!x.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Todos os componentes da aba, em profundidade (para os testes procurarem um botão). */
    final List<Component> componentes() {
        java.util.ArrayList<Component> l = new java.util.ArrayList<>();
        coletar(this, l);
        return l;
    }

    private static void coletar(Component c, List<Component> l) {
        l.add(c);
        if (c instanceof java.awt.Container k) {
            for (Component f : k.getComponents()) {
                coletar(f, l);
            }
        }
    }

}
