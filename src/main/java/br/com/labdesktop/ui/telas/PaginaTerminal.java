package br.com.labdesktop.ui.telas;

import br.com.labdesktop.terminal.ComandoDeTerminal;
import br.com.labdesktop.terminal.EstadoDoTerminal;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.terminal.ContextoDeTerminal;
import br.com.labdesktop.ui.terminal.PainelTerminal;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * A aba "Terminal" da página do servidor: o shell de login do servidor, com várias abas de terminal (botão "Novo terminal" e "Fechar
 * terminal"). O limite é de {@value LimiteDeAbas#MAXIMO} terminais por servidor, contados junto com os das sessões do Claude dos
 * projetos; a nona recebe uma mensagem clara em vez de tentar. Os terminais continuam vivos quando o usuário vai para outra aba ou outro
 * servidor (é o ponto de ter um terminal no app); só fecham ao fechar a aba, ao sair do servidor ou ao bloquear o cofre.
 *
 * <p>Abre um terminal sozinho na primeira vez que a aba aparece. Sem contexto ({@code null}: servidor ainda sem conexão no teste) mostra
 * que o terminal não está disponível.
 */
public final class PaginaTerminal extends Painel {

    private static final long serialVersionUID = 1L;
    private static final String CARTAO_VAZIO = "vazio";

    /** Uma aba de terminal. */
    private record Aba(String id, int numero, PainelTerminal painel) { }

    private final transient ContextoDeTerminal ctx;
    private final transient Notificacoes avisos;
    private final Abas abas = new Abas(true);
    private final CardLayout cartoes = new CardLayout();
    private final JPanel corpo = new Painel(cartoes);
    private final Rotulo contagem = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Botao novo = Botoes.miniPrimario("Novo terminal", "mais");
    private final Botao fechar = Botoes.mini("Fechar terminal", "fechar");
    private final Rotulo aviso = new Rotulo().corDoToken(Tokens.PERIGO).tamanho(12.5f).truncavel();
    private final Rotulo vazio = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(13f).aoCentro();
    private final Map<String, Aba> porId = new LinkedHashMap<>();
    private int proximoNumero = 1;
    private boolean abriuSozinho;
    private boolean encerrada;

    public PaginaTerminal(ContextoDeTerminal ctx, Notificacoes avisos) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        this.avisos = avisos;
        fundo(Tokens.BG);
        setBorder(BorderFactory.createEmptyBorder(8, 20, 12, 20));

        novo.setToolTipText("Abre outro shell do servidor numa aba nova");
        fechar.setToolTipText("Fecha o terminal desta aba (o que estiver rodando nele para)");
        novo.addActionListener(e -> novoTerminal());
        fechar.addActionListener(e -> fecharSelecionado());
        abas.aoSelecionar(this::mostrar);
        aviso.setVisible(false);

        JPanel linha = new Painel(new BorderLayout(12, 0));
        linha.add(abas, BorderLayout.CENTER);
        linha.add(Caixa.fluxo(FlowLayout.RIGHT, 10, contagem, novo, fechar), BorderLayout.EAST);
        JPanel topo = Caixa.coluna(4, linha, aviso);
        add(topo, BorderLayout.NORTH);

        JPanel cartaoVazio = new Painel(new java.awt.GridBagLayout()).fundo(Tokens.LOGS_BG).borda(Tokens.LOGS_BORDA);
        cartaoVazio.add(vazio);
        corpo.add(cartaoVazio, CARTAO_VAZIO);
        add(corpo, BorderLayout.CENTER);
        cartoes.show(corpo, CARTAO_VAZIO);
        getAccessibleContext().setAccessibleName("Terminal do servidor");
        atualizar();

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(this::aoAparecer);
            }
        });
    }

    private void aoAparecer() {
        if (encerrada) {
            return;
        }
        if (ctx != null && !abriuSozinho && porId.isEmpty()) {
            abriuSozinho = true; // só na primeira vez: fechar todas as abas não pode abrir uma de novo sozinho
            novoTerminal();
            return;
        }
        Aba atual = selecionada();
        if (atual != null) {
            atual.painel().focar();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Abas
    // ---------------------------------------------------------------------------------------------

    /** Abre um shell do servidor numa aba nova, se couber. Devolve {@code false} (com a mensagem na tela) se não coube. */
    public boolean novoTerminal() {
        if (ctx == null || encerrada) {
            return false;
        }
        if (!ctx.limite().reservar()) {
            mostrarAviso(LimiteDeAbas.mensagemDoLimite());
            if (avisos != null) {
                avisos.atencao(LimiteDeAbas.mensagemDoLimite());
            }
            return false;
        }
        mostrarAviso(null);
        int numero = proximoNumero++;
        String id = "t" + numero;
        PainelTerminal painel = new PainelTerminal(ctx, ComandoDeTerminal.ShellDoServidor.INSTANCIA, new PainelTerminal.Saida() {
            @Override
            public void aoMudarTitulo(String titulo) {
                abas.rotulo(id, titulo);
            }

            @Override
            public void aoMudarEstado(EstadoDoTerminal estado) {
                atualizar();
            }
        });
        porId.put(id, new Aba(id, numero, painel));
        abas.adicionar(id, "Terminal " + numero);
        corpo.add(painel, id);
        abas.selecionar(id);
        mostrar(id);
        atualizar();
        return true;
    }

    /** Fecha a aba (e o canal do terminal dela) e devolve a vaga ao limite. A vizinha da fechada passa a ser a escolhida. */
    public void fecharTerminal(String id) {
        if (!porId.containsKey(id)) {
            return;
        }
        List<String> ordem = new ArrayList<>(porId.keySet());
        int posicao = ordem.indexOf(id);
        boolean eraAEscolhida = id.equals(abas.selecionada());
        Aba aba = porId.remove(id);
        ordem.remove(id);
        String vizinha = ordem.isEmpty() ? null : ordem.get(Math.min(posicao, ordem.size() - 1));
        aba.painel().descartar();
        corpo.remove(aba.painel());
        if (ctx != null) {
            ctx.limite().liberar();
        }
        abas.remover(id, vizinha == null ? id : vizinha);
        if (vizinha == null) {
            cartoes.show(corpo, CARTAO_VAZIO);
        } else if (eraAEscolhida) {
            mostrar(vizinha);
        }
        mostrarAviso(null); // abriu uma vaga: a mensagem do limite não vale mais
        atualizar();
        corpo.revalidate();
        corpo.repaint();
    }

    private void fecharSelecionado() {
        Aba a = selecionada();
        if (a != null) {
            fecharTerminal(a.id());
        }
    }

    private void mostrar(String id) {
        Aba aba = porId.get(id);
        if (aba == null) {
            return;
        }
        cartoes.show(corpo, id);
        SwingUtilities.invokeLater(() -> {
            if (!encerrada && porId.containsKey(id)) {
                aba.painel().focar();
            }
        });
    }

    private Aba selecionada() {
        String id = abas.selecionada();
        return id == null ? null : porId.get(id);
    }

    private void mostrarAviso(String texto) {
        aviso.setVisible(texto != null);
        if (texto != null) {
            aviso.setText(texto);
            aviso.setToolTipText(texto);
        }
    }

    private void atualizar() {
        int n = porId.size();
        abas.setVisible(n > 0);
        contagem.setText(n == 0 ? "" : n + " de " + LimiteDeAbas.MAXIMO);
        fechar.setVisible(n > 0);
        novo.setEnabled(ctx != null);
        vazio.setText(ctx == null
            ? "O terminal não está disponível: este servidor ainda não tem conexão."
            : "Nenhum terminal aberto. Use Novo terminal para abrir o shell do servidor.");
    }

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------------

    /** Fecha todos os terminais e devolve as vagas: o servidor saiu da lista, o cofre foi bloqueado ou o app encerrou. */
    public void encerrar() {
        if (encerrada) {
            return;
        }
        encerrada = true;
        for (Aba a : new ArrayList<>(porId.values())) {
            a.painel().descartar();
            if (ctx != null) {
                ctx.limite().liberar();
            }
        }
        porId.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public List<PainelTerminal> terminais() {
        List<PainelTerminal> l = new ArrayList<>();
        porId.values().forEach(a -> l.add(a.painel()));
        return l;
    }

    public Abas abas() {
        return abas;
    }

    public Botao botaoNovo() {
        return novo;
    }

    public Botao botaoFechar() {
        return fechar;
    }

    public String textoDoAviso() {
        return aviso.isVisible() ? aviso.getText() : "";
    }

    public String textoDaContagem() {
        return contagem.getText();
    }

    public String textoDoVazio() {
        return vazio.getText();
    }

    public String selecionadaId() {
        return abas.selecionada();
    }

    public PainelTerminal terminalSelecionado() {
        Aba a = selecionada();
        return a == null ? null : a.painel();
    }
}
