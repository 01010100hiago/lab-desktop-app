package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.TextosDeLimite;
import br.com.labdesktop.ui.componentes.BarraSeveridade;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Instant;
import java.time.ZoneId;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A barra de crédito do servidor (ARQUITETURA 8.2 e 9): as duas janelas do plano, de 5 horas e da semana, cada uma com
 * barra, percentual e "zera em ...", e o traço do freio global sobre a barra da semana. A cor muda a PARTIR de 75 %
 * (âmbar) e de 90 % (terracota), a mesma régua da faixa de aviso; e a severidade vem sempre com o número e um ícone de
 * alerta ao lado, nunca só como cor. Sem leitura válida (nenhuma sessão gravou uma), a barra diz isso em texto em vez
 * de fingir 0 %.
 *
 * <p>Só desenha o {@link LimitesConta} que o {@code Sondador} entregou ({@code LIMITES}, a cada 60 s).
 */
final class PainelDeCredito extends Painel {

    private static final long serialVersionUID = 1L;

    private final Rotulo titulo = Rotulo.titulo("Plano do Claude").tamanho(15f);
    private final Rotulo situacao = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Janela cincoHoras = new Janela("5 horas");
    private final Janela semana = new Janela("Semana");
    private final Rotulo freio = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f);
    private final Rotulo semLeitura = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JPanel janelas = new Painel(new GridBagLayout());

    PainelDeCredito() {
        super(new BorderLayout(0, 10));
        fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(12);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 16, 12, 16));

        janelas.add(cincoHoras.rotulo, restricoes(0, 0, 0, new Insets(0, 0, 6, 12)));
        janelas.add(cincoHoras.barra, restricoes(1, 0, 1, new Insets(0, 0, 6, 12)));
        janelas.add(cincoHoras.percentual, restricoes(2, 0, 0, new Insets(0, 0, 6, 12)));
        janelas.add(cincoHoras.zera, restricoes(3, 0, 0, new Insets(0, 0, 6, 0)));
        janelas.add(semana.rotulo, restricoes(0, 1, 0, new Insets(0, 0, 0, 12)));
        janelas.add(semana.barra, restricoes(1, 1, 1, new Insets(0, 0, 0, 12)));
        janelas.add(semana.percentual, restricoes(2, 1, 0, new Insets(0, 0, 0, 12)));
        janelas.add(semana.zera, restricoes(3, 1, 0, new Insets(0, 0, 0, 0)));

        JComponent cabecalho = Caixa.linha(10, titulo, Caixa.mola(), situacao);
        JComponent rodape = Caixa.linha(8, freio, Caixa.mola());
        add(cabecalho, BorderLayout.NORTH);
        add(Caixa.coluna(8, semLeitura, janelas), BorderLayout.CENTER);
        add(rodape, BorderLayout.SOUTH);
        getAccessibleContext().setAccessibleName("Plano do Claude: crédito das janelas de 5 horas e da semana");
        atualizar(null, Instant.now(), ZoneId.systemDefault());
    }

    private static GridBagConstraints restricoes(int x, int y, double peso, Insets insets) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x;
        g.gridy = y;
        g.weightx = peso;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        g.insets = insets;
        return g;
    }

    /** Uma janela do plano: rótulo, barra, percentual (com ícone quando acende) e quando zera. */
    private static final class Janela {
        static final int ALTURA = 18;
        final Rotulo rotulo;
        final BarraSeveridade barra = new BarraSeveridade(BarraSeveridade.Tamanho.GROSSA);
        final Rotulo percentual = new Rotulo().negrito().aDireita();
        final Rotulo zera = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);

        Janela(String nome) {
            rotulo = Rotulo.rotuloDeCampo(nome);
            // altura fixa: um rótulo sem texto (ainda sem leitura) pediria altura 0 e sumiria do GridBagLayout
            rotulo.setPreferredSize(new Dimension(62, ALTURA));
            percentual.setPreferredSize(new Dimension(86, ALTURA));
            percentual.setMinimumSize(percentual.getPreferredSize());
            zera.setPreferredSize(new Dimension(260, ALTURA));
        }

        void mostrar(LimitesConta.Janela j, boolean leituraUtil, Instant agora, ZoneId fuso, Double marcaDoFreio) {
            Double pct = j == null ? null : j.usadoPct();
            boolean tem = leituraUtil && pct != null;
            if (!tem) {
                barra.definir(0, Severidade.NORMAL, "sem leitura");
                barra.marca(null);
                percentual.setText(Formato.SEM_DADO);
                percentual.setIcon(null);
                percentual.corDoToken(Tokens.TEXTO3);
                zera.setText("");
                return;
            }
            double frac = pct / 100.0;
            Severidade s = Severidade.doCredito(frac);
            String texto = Formato.fmtNum(pct, 0) + "%";
            String zeraTexto = TextosDeLimite.zeraTexto(j.zeraEm(), agora, fuso);
            barra.definir(frac, s, texto + (zeraTexto.isEmpty() ? "" : ", " + zeraTexto));
            barra.marca(marcaDoFreio);
            percentual.setText(texto);
            percentual.setIcon(s == Severidade.NORMAL ? null : Icones.colorido("alerta", 14, s.tokenDaCor()));
            percentual.setIconTextGap(5);
            percentual.corDoToken(s == Severidade.NORMAL ? Tokens.TEXTO : s.tokenDaCor());
            percentual.setToolTipText(s == Severidade.CRITICA ? "Crédito quase no fim (90% ou mais)"
                : s == Severidade.ALTA ? "Crédito acima de 75%" : null);
            zera.setText(zeraTexto);
        }
    }

    /** Mostra o retrato dos limites do plano (ou a falta dele). */
    void atualizar(LimitesConta l, Instant agora, ZoneId fuso) {
        boolean util = l != null && l.leituraUtil();
        Double marca = l != null && l.freio() != null && l.freio().pct() != null && Boolean.TRUE.equals(l.freio().ativo())
            ? l.freio().pct() / 100.0 : null;
        cincoHoras.mostrar(l == null ? null : l.cincoHoras(), util, agora, fuso, null);
        semana.mostrar(l == null ? null : l.semana(), util, agora, fuso, marca);

        if (l == null) {
            situacao.setText("lendo…");
            semLeitura.setText("Ainda sem leitura do plano deste servidor.");
        } else if (!Boolean.TRUE.equals(l.disponivel())) {
            situacao.setText("sem leitura");
            semLeitura.setText("Nenhuma sessão do Claude gravou uma leitura do plano ainda. Abra ou reabra uma sessão e o painel passa a mostrar.");
        } else if (Boolean.TRUE.equals(l.antiga())) {
            situacao.setText("leitura antiga" + (l.lidoEm() == null ? "" : " · " + Formato.fmtHa(l.lidoEm(), agora)));
            semLeitura.setText("A leitura do plano tem mais de 30 minutos: os números abaixo não são mostrados até uma sessão ler de novo.");
        } else {
            situacao.setText(l.lidoEm() == null ? "" : "leitura " + Formato.fmtHa(l.lidoEm(), agora));
            semLeitura.setText("");
        }
        semLeitura.setVisible(!semLeitura.getText().isEmpty());
        janelas.setVisible(util);

        if (l != null && l.freio() != null && l.freio().pct() != null && Boolean.TRUE.equals(l.freio().ativo())) {
            freio.setText("Freio global ligado: pausa todas as sessões ao chegar em " + l.freio().pct() + "% da semana (o traço na barra)");
        } else if (l != null) {
            freio.setText("Freio global desligado");
        } else {
            freio.setText("");
        }
        revalidate();
        repaint();
    }

    // ---- para os testes ----

    BarraSeveridade barraDeCincoHoras() {
        return cincoHoras.barra;
    }

    BarraSeveridade barraDaSemana() {
        return semana.barra;
    }

    String percentualDeCincoHoras() {
        return cincoHoras.percentual.getText();
    }

    String percentualDaSemana() {
        return semana.percentual.getText();
    }

    String textoDeSituacao() {
        return situacao.getText();
    }

    String textoSemLeitura() {
        return semLeitura.getText();
    }

    String textoDoFreio() {
        return freio.getText();
    }

    boolean mostraAsJanelas() {
        return janelas.isVisible();
    }
}
