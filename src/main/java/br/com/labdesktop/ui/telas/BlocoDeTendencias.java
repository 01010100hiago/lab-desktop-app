package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.InfoServidor.Historico;
import br.com.labdesktop.servidor.RegrasDeSaude;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Sparkline;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Function;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * "Tendências": os gráficos pequenos dos últimos minutos (o histórico de 60 pontos do agente, um a cada {@code passo_s}
 * segundos): CPU, memória, rede (descida e subida) e temperatura. Cada gráfico traz o valor de agora e o mínimo e o
 * máximo escritos embaixo, porque ninguém deveria depender de ler uma curva.
 */
final class BlocoDeTendencias extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;

    private final Painel cpu = new Painel();
    private final Painel memoria = new Painel();
    private final Painel descida = new Painel();
    private final Painel subida = new Painel();
    private final Painel temperatura = new Painel();
    private final Serie serieCpu = new Serie("Processador (CPU)", Tokens.ACENTO, 0.0, null, null);
    private final Serie serieMemoria = new Serie("Memória em uso", Tokens.SERIE6, 0.0, null, null);
    private final Serie serieDescida = new Serie("Rede: descida", Tokens.SERIE5, 0.0, null, null);
    private final Serie serieSubida = new Serie("Rede: subida", Tokens.SERIE8, 0.0, null, null);
    private final Serie serieTemp = new Serie("Temperatura da CPU", Tokens.ATENCAO, 30.0, 100.0, RegrasDeSaude.TEMPERATURA_ATENCAO);

    BlocoDeTendencias() {
        super("Tendências", "pulso");
        JPanel grade = new Painel(new GridLayout(1, 5, 24, 0));
        grade.add(coluna(cpu, serieCpu));
        grade.add(coluna(memoria, serieMemoria));
        grade.add(coluna(descida, serieDescida));
        grade.add(coluna(subida, serieSubida));
        grade.add(coluna(temperatura, serieTemp));
        linha(grade, 0);
        atualizar(null);
    }

    private static Painel coluna(Painel p, Serie... series) {
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        boolean primeira = true;
        for (Serie s : series) {
            if (!primeira) {
                p.add(javax.swing.Box.createRigidArea(new java.awt.Dimension(0, 12)));
            }
            s.adicionarEm(p);
            primeira = false;
        }
        return p;
    }

    void atualizar(InfoServidor info) {
        Historico h = info == null ? null : info.historico();
        Integer passo = h == null ? null : h.passoS();
        apoio(Rotulo.dica(h == null ? "" : "últimos " + minutos(h.cpu().size(), passo) + " min"));
        serieCpu.mostrar(h == null ? List.of() : h.cpu(), passo, v -> FormatosDoServidor.porcentagem(v), "CPU");
        serieMemoria.mostrar(h == null ? List.of() : h.mem(), passo, v -> FormatosDoServidor.porcentagem(v), "memória");
        serieDescida.mostrar(h == null ? List.of() : h.rxBps(), passo, v -> FormatosDoServidor.bitsPorSegundo(v), "descida");
        serieSubida.mostrar(h == null ? List.of() : h.txBps(), passo, v -> FormatosDoServidor.bitsPorSegundo(v), "subida");
        serieTemp.mostrar(h == null ? List.of() : h.temp(), passo, v -> FormatosDoServidor.temperatura(v), "temperatura");
        terminar();
    }

    /** Quantos minutos o histórico cobre: pontos vezes o passo. */
    static int minutos(int pontos, Integer passoS) {
        int passo = passoS == null || passoS <= 0 ? 10 : passoS;
        return Math.max(1, Math.round(pontos * (float) passo / 60f));
    }

    /**
     * A legenda de uma série: "agora 12% · mín 4% · máx 30%", ou que não há histórico ainda. Usa só os valores válidos.
     */
    static String legendaDaSerie(List<Double> valores, Function<Double, String> formato) {
        List<Double> v = valores.stream().filter(x -> x != null && !x.isNaN() && !x.isInfinite()).toList();
        if (v.isEmpty()) {
            return "sem histórico ainda";
        }
        double min = v.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = v.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return "agora " + formato.apply(v.get(v.size() - 1)) + " · mín " + formato.apply(min) + " · máx " + formato.apply(max);
    }

    /** Um gráfico com título e legenda. */
    private static final class Serie {
        private final String titulo;
        private final Rotulo cabecalho;
        private final Sparkline grafico = new Sparkline();
        private final Rotulo legenda = new Rotulo().corDoToken(Tokens.TEXTO).tamanho(11.5f).truncavel();
        private final Rotulo extremos = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(11.5f).truncavel();
        private String legendaCompleta = "";

        Serie(String titulo, String token, Double min, Double max, Double referencia) {
            this.titulo = titulo;
            this.cabecalho = new Rotulo(titulo).negrito().tamanho(12.5f);
            grafico.cor(token).escala(min, max).referencia(referencia).altura(48);
        }

        void adicionarEm(JPanel p) {
            cabecalho.setAlignmentX(LEFT_ALIGNMENT);
            grafico.setAlignmentX(LEFT_ALIGNMENT);
            legenda.setAlignmentX(LEFT_ALIGNMENT);
            p.add(cabecalho);
            p.add(javax.swing.Box.createRigidArea(new java.awt.Dimension(0, 4)));
            p.add(grafico);
            p.add(javax.swing.Box.createRigidArea(new java.awt.Dimension(0, 2)));
            p.add(legenda);
            extremos.setAlignmentX(LEFT_ALIGNMENT);
            p.add(extremos);
        }

        void mostrar(List<Double> valores, Integer passo, Function<Double, String> formato, String nome) {
            grafico.definir(valores);
            String texto = legendaDaSerie(valores, formato);
            legendaCompleta = texto;
            String[] partes = texto.split(" · ", 2); // "agora X" numa linha, "mín Y · máx Z" na de baixo (cabe em 5 colunas)
            legenda.setText(partes[0]);
            extremos.setText(partes.length > 1 ? partes[1] : "");
            legenda.setToolTipText(titulo + ": " + texto);
            grafico.descrever("Histórico de " + nome + ": " + texto);
        }

        String textoDaLegenda() {
            return legendaCompleta;
        }

        Sparkline grafico() {
            return grafico;
        }
    }

    /** Os gráficos, na ordem: CPU, memória, descida, subida, temperatura (para os testes). */
    List<Sparkline> graficos() {
        return List.of(serieCpu.grafico(), serieMemoria.grafico(), serieDescida.grafico(), serieSubida.grafico(), serieTemp.grafico());
    }

    List<String> legendas() {
        return List.of(serieCpu.textoDaLegenda(), serieMemoria.textoDaLegenda(), serieDescida.textoDaLegenda(),
            serieSubida.textoDaLegenda(), serieTemp.textoDaLegenda());
    }
}
