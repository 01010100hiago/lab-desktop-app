package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.FilaDeTransferencias.Estado;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Retrato;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Tipo;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BarraSeveridade;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

/**
 * A fila de transferências, num painel RECOLHÍVEL na base da tela: sempre um cabeçalho de uma linha ("Transferências", e o que
 * está acontecendo em palavras) e, aberto, uma linha por transferência com a barra, a porcentagem, a velocidade, o que falta e
 * o botão Cancelar. Estado nunca só pela cor: cada linha diz com ícone E palavras se está enviando, esperando, concluída,
 * com problema, falhou ou cancelada. Cada dado é um rótulo separado (nada de "37% · 2 MB/s · 1 min" numa linha que quebra).
 *
 * <p>Atualiza por diferença: cada transferência tem a sua linha, reaproveitada a cada retrato.
 */
final class PainelDeTransferencias extends Painel {

    private static final long serialVersionUID = 1L;
    private static final int ALTURA_MAXIMA_DA_LISTA = 190;

    /** O que o painel pede. */
    interface Acoes {
        void cancelar(long id);

        void limparConcluidas();
    }

    private final Acoes acoes;
    private final Botao alternar = Botoes.fantasma("Transferências", "avancar");
    private final Rotulo resumo = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao limpar = Botoes.mini("Limpar as concluídas", null);
    private final JPanel lista = new Painel();
    private final JScrollPane rolagem;
    private final JPanel corpo = new Painel(new BorderLayout());
    private final Rotulo vazio = new Rotulo("Nenhum envio ou download por enquanto.").corDoToken(Tokens.TEXTO3).tamanho(12.5f);
    private final Map<Long, Linha> linhas = new LinkedHashMap<>();
    private List<Retrato> ultimos = List.of();
    private boolean aberto;

    @SuppressWarnings("this-escape")
    PainelDeTransferencias(Acoes acoes) {
        super(new BorderLayout());
        this.acoes = acoes;
        fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(10);
        setName("painel-de-transferencias");
        alternar.setIconTextGap(6);
        alternar.getAccessibleContext().setAccessibleName("Mostrar ou esconder as transferências");
        alternar.addActionListener(e -> definirAberto(!aberto));
        limpar.addActionListener(e -> acoes.limparConcluidas());
        limpar.setVisible(false);
        JPanel cabecalho = Caixa.linha(10, alternar, resumo, Caixa.mola(), limpar);
        cabecalho.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 8));
        add(cabecalho, BorderLayout.NORTH);

        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        lista.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        rolagem = new JScrollPane(lista, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rolagem.setBorder(null);
        rolagem.setOpaque(false);
        rolagem.getViewport().setOpaque(false);
        rolagem.getVerticalScrollBar().setUnitIncrement(16);
        vazio.setBorder(BorderFactory.createEmptyBorder(2, 16, 10, 12));
        corpo.add(rolagem, BorderLayout.CENTER);
        corpo.setVisible(false);
        add(corpo, BorderLayout.CENTER);
        atualizarCabecalho();
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir e fechar
    // ---------------------------------------------------------------------------------------------

    void definirAberto(boolean sim) {
        if (sim == aberto) {
            return;
        }
        aberto = sim;
        corpo.setVisible(sim);
        alternar.setIcon(Icones.de(sim ? "seta-baixo" : "avancar", 16));
        revalidate();
        repaint();
    }

    boolean aberto() {
        return aberto;
    }

    Botao botaoDoCabecalho() {
        return alternar;
    }

    Botao botaoDeLimpar() {
        return limpar;
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        if (!aberto) {
            return d;
        }
        int altura = Math.min(lista.getPreferredSize().height + 6, ALTURA_MAXIMA_DA_LISTA);
        return new Dimension(d.width, alternar.getPreferredSize().height + 8 + Math.max(altura, linhas.isEmpty() ? 34 : altura));
    }

    // ---------------------------------------------------------------------------------------------
    // Dados
    // ---------------------------------------------------------------------------------------------

    /** Recebe o retrato da fila (na EDT): uma linha por transferência, reaproveitada. */
    void definir(List<Retrato> retratos) {
        boolean tinhaAlgo = !ultimos.isEmpty();
        ultimos = List.copyOf(retratos);
        Map<Long, Linha> antigas = new LinkedHashMap<>(linhas);
        boolean estrutura = antigas.size() != retratos.size();
        lista.removeAll();
        linhas.clear();
        if (retratos.isEmpty()) {
            lista.add(vazio);
        }
        for (Retrato r : retratos) {
            Linha l = antigas.get(r.id());
            if (l == null) {
                l = new Linha(r.id());
                estrutura = true;
            }
            l.atualizar(r);
            linhas.put(r.id(), l);
            lista.add(l);
        }
        limpar.setVisible(retratos.stream().anyMatch(r -> r.estado().terminou()));
        atualizarCabecalho();
        if (!tinhaAlgo && !retratos.isEmpty()) {
            definirAberto(true); // a primeira transferência abre o painel: o usuário quer ver a barra andar
        }
        if (estrutura) {
            lista.revalidate();
            revalidate();
        }
        repaint();
    }

    private void atualizarCabecalho() {
        long andando = ultimos.stream().filter(r -> !r.estado().terminou()).count();
        long problemas = ultimos.stream().filter(r -> r.estado() == Estado.FALHOU || r.estado() == Estado.COM_PROBLEMAS).count();
        String texto;
        if (ultimos.isEmpty()) {
            texto = "Nenhuma por enquanto";
        } else if (andando > 0) {
            texto = andando == 1 ? "1 em andamento" : andando + " em andamento";
        } else if (problemas > 0) {
            texto = problemas == 1 ? "1 terminou com problema" : problemas + " terminaram com problema";
        } else {
            long canceladas = ultimos.stream().filter(r -> r.estado() == Estado.CANCELADA).count();
            long total = ultimos.size();
            if (canceladas == total) {
                texto = total == 1 ? "1 cancelada" : total + " canceladas";
            } else if (canceladas == 0) {
                texto = total == 1 ? "1 concluída" : total + " concluídas";
            } else {
                texto = total + " terminadas";
            }
        }
        resumo.setText(texto);
        alternar.setIcon(Icones.de(aberto ? "seta-baixo" : "avancar", 16));
    }

    /** O texto do resumo do cabeçalho (para os testes). */
    String textoDoResumo() {
        return resumo.getText();
    }

    List<Linha> linhas() {
        return new ArrayList<>(linhas.values());
    }

    Linha linhaDe(long id) {
        return linhas.get(id);
    }

    // ---------------------------------------------------------------------------------------------
    // Uma transferência
    // ---------------------------------------------------------------------------------------------

    /** A linha de uma transferência. */
    final class Linha extends Painel {
        private static final long serialVersionUID = 1L;
        private final long id;
        private final Rotulo titulo = new Rotulo().negrito().truncavel();
        private final Rotulo estado = new Rotulo().tamanho(12.5f);
        private final BarraSeveridade barra = new BarraSeveridade(BarraSeveridade.Tamanho.MEDIA);
        /** Largura fixa para "100%" (as linhas alinham os números), mas a ALTURA é a do texto: um rótulo vazio mede zero e cortaria o número. */
        private final Rotulo porcento = new Rotulo() {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                int h = Math.max(d.height, getFontMetrics(getFont()).getHeight());
                return new Dimension(Math.max(d.width, getFontMetrics(getFont()).stringWidth("100%") + 4), h);
            }
        }.corDoToken(Tokens.TEXTO2).tamanho(12.5f).aDireita();
        private final Rotulo velocidade = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
        private final Rotulo restante = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
        private final Rotulo detalhe = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();
        private final Botao cancelar = Botoes.mini("Cancelar", null);

        Linha(long id) {
            super(new BorderLayout(0, 4));
            this.id = id;
            setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            cancelar.addActionListener(e -> acoes.cancelar(id));
            JPanel topo = Caixa.linha(10, titulo, Caixa.mola(), estado, cancelar);
            barra.setPreferredSize(new Dimension(200, 8));
            barra.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
            JPanel meio = new Painel(new BorderLayout(10, 0));
            meio.add(barra, BorderLayout.CENTER);
            JPanel numeros = Caixa.linha(14, porcento, velocidade, restante);
            meio.add(numeros, BorderLayout.EAST);
            JPanel miolo = new Painel(new BorderLayout(0, 4));
            miolo.add(topo, BorderLayout.NORTH);
            miolo.add(meio, BorderLayout.CENTER);
            miolo.add(detalhe, BorderLayout.SOUTH);
            add(miolo, BorderLayout.CENTER);
        }

        void atualizar(Retrato r) {
            titulo.setText(r.titulo());
            titulo.setToolTipText(r.titulo());
            Gravidade g = switch (r.estado()) {
                case CONCLUIDA -> Gravidade.OK;
                case FALHOU -> Gravidade.PERIGO;
                case COM_PROBLEMAS, ESPERANDO_VOCE -> Gravidade.ATENCAO;
                case CANCELADA, NA_FILA -> Gravidade.NEUTRO;
                case EM_ANDAMENTO -> Gravidade.DESTAQUE;
            };
            String icone = switch (r.estado()) {
                case CONCLUIDA -> "ok-circulo";
                case FALHOU -> "erro";
                case COM_PROBLEMAS -> "alerta";
                case ESPERANDO_VOCE -> "relogio";
                case CANCELADA -> "parar";
                case NA_FILA -> "relogio";
                case EM_ANDAMENTO -> r.tipo() == Tipo.ENVIO ? "enviar-arquivo" : "baixar";
            };
            estado.setText(textoDoEstado(r));
            estado.corDoToken(g.tokenDoTexto());
            estado.setIcon(Icones.colorido(icone, 16, g.tokenDoTexto()));
            estado.setIconTextGap(6);
            double f = r.fracao();
            Severidade sev = r.estado() == Estado.FALHOU ? Severidade.CRITICA : r.estado() == Estado.COM_PROBLEMAS ? Severidade.ALTA : Severidade.NORMAL;
            barra.definir(f < 0 ? 0 : f, sev, r.titulo());
            porcento.setText(f < 0 ? "" : Math.round(f * 100) + "%");
            boolean andando = r.estado() == Estado.EM_ANDAMENTO;
            velocidade.setText(andando && r.bytesPorSegundo() > 0 ? Formato.fmtBps(r.bytesPorSegundo(), 1) : "");
            restante.setText(andando && r.restante() != null ? "faltam " + duracao(r.restante()) : "");
            velocidade.setVisible(!velocidade.getText().isEmpty());
            restante.setVisible(!restante.getText().isEmpty());
            String d = r.estado().terminou() ? r.mensagem() : r.atual().isEmpty() ? "" : r.atual();
            if (r.estado() == Estado.EM_ANDAMENTO && r.bytesTotal() > 0) {
                d = (d.isEmpty() ? "" : d + ": ") + Formato.fmtBytes(r.bytesFeitos()) + " de " + Formato.fmtBytes(r.bytesTotal());
            }
            detalhe.setText(TextoSeguro.limparLinha(d));
            detalhe.setToolTipText(TextoSeguro.limparLinha(d));
            detalhe.setVisible(!detalhe.getText().isEmpty());
            cancelar.setVisible(!r.estado().terminou());
            getAccessibleContext().setAccessibleName(r.titulo() + ", " + textoDoEstado(r));
        }

        long id() {
            return id;
        }

        Botao botaoCancelar() {
            return cancelar;
        }

        String textoDoTitulo() {
            return titulo.getText();
        }

        String estadoEmTexto() {
            return estado.getText();
        }

        String textoDoPorcento() {
            return porcento.getText();
        }

        String textoDaVelocidade() {
            return velocidade.getText();
        }

        String textoDoRestante() {
            return restante.getText();
        }

        String textoDoDetalhe() {
            return detalhe.getText();
        }

        List<Component> componentesDeTexto() {
            return List.of(titulo, estado, porcento, velocidade, restante, detalhe, cancelar);
        }
    }

    static String textoDoEstado(Retrato r) {
        return switch (r.estado()) {
            case NA_FILA -> "Na fila";
            case EM_ANDAMENTO -> r.tipo() == Tipo.ENVIO ? "Enviando" : "Baixando";
            case ESPERANDO_VOCE -> "Esperando sua resposta";
            case CONCLUIDA -> "Concluída";
            case COM_PROBLEMAS -> "Concluída com problemas";
            case FALHOU -> "Falhou";
            case CANCELADA -> "Cancelada";
        };
    }

    /** "45 s", "3 min", "1 h 05 min". */
    static String duracao(Duration d) {
        long s = Math.max(1, d.toSeconds());
        if (s < 90) {
            return s + " s";
        }
        long m = Math.round(s / 60.0);
        if (m < 60) {
            return m + " min";
        }
        return (m / 60) + " h " + String.format("%02d", m % 60) + " min";
    }
}
