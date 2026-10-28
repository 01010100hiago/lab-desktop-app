package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.InfoServidor.Historico;
import br.com.labdesktop.servidor.RegrasDeSaude;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Sparkline;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Function;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * "Tendências": os gráficos pequenos dos últimos minutos (o histórico de 60 pontos do agente, um a cada {@code passo_s}
 * segundos) numa grade compacta de 3 por linha: CPU, memória, descida, subida e temperatura. Cada célula tem o nome, o
 * valor de agora, o gráfico e o mínimo e o máximo escritos numa linha só, porque ninguém deveria depender de ler uma curva.
 */
final class BlocoDeTendencias extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;
    private static final int ALTURA_DO_GRAFICO = 40;

    private final Serie serieCpu = new Serie("CPU", Tokens.DESTAQUE, 0.0, null, null);
    private final Serie serieMemoria = new Serie("Memória", Tokens.SERIE6, 0.0, null, null);
    private final Serie serieDescida = new Serie("Descida", Tokens.SERIE5, 0.0, null, null);
    private final Serie serieSubida = new Serie("Subida", Tokens.SERIE8, 0.0, null, null);
    private final Serie serieTemp = new Serie("Temperatura", Tokens.ATENCAO, 30.0, 100.0, RegrasDeSaude.TEMPERATURA_ATENCAO);

    BlocoDeTendencias() {
        super("Tendências", "pulso");
        JPanel grade = new Painel(new GridLayout(2, 3, 20, 10));
        for (Serie s : List.of(serieCpu, serieMemoria, serieTemp, serieDescida, serieSubida)) {
            grade.add(s.celula());
        }
        grade.add(new Painel());
        linha(grade, 0);
        atualizar(null);
    }

    void atualizar(InfoServidor info) {
        Historico h = info == null ? null : info.historico();
        Integer passo = h == null ? null : h.passoS();
        apoio(Rotulo.dica(h == null ? "" : "últimos " + minutos(h.cpu().size(), passo) + " min"));
        serieCpu.mostrar(h == null ? List.of() : h.cpu(), v -> FormatosDoServidor.porcentagem(v), "CPU");
        serieMemoria.mostrar(h == null ? List.of() : h.mem(), v -> FormatosDoServidor.porcentagem(v), "memória");
        serieDescida.mostrar(h == null ? List.of() : h.rxBps(), v -> FormatosDoServidor.bitsPorSegundo(v), "descida");
        serieSubida.mostrar(h == null ? List.of() : h.txBps(), v -> FormatosDoServidor.bitsPorSegundo(v), "subida");
        serieTemp.mostrar(h == null ? List.of() : h.temp(), v -> FormatosDoServidor.temperatura(v), "temperatura");
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
        String menor = formato.apply(min);
        String maior = formato.apply(max);
        // "mín 4 kb/s · máx 12 kb/s" vira "mín 4 · máx 12 kb/s" quando a unidade é a mesma (cabe numa célula estreita)
        int a = menor.lastIndexOf(' ');
        int b = maior.lastIndexOf(' ');
        if (a > 0 && b > 0 && menor.substring(a).equals(maior.substring(b))) {
            menor = menor.substring(0, a);
        }
        return "agora " + formato.apply(v.get(v.size() - 1)) + " · mín " + menor + ", máx " + maior;
    }

    /** Uma célula: título e valor de agora na mesma linha, o gráfico e "mín · máx" embaixo. */
    private static final class Serie {
        private final String nomeCompleto;
        private final Rotulo cabecalho;
        private final Rotulo agora = new Rotulo().negrito().tamanho(12.5f).aDireita();
        private final Sparkline grafico = new Sparkline();
        private final Rotulo extremos = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(11f).truncavel();
        private final JPanel celula = new Painel();
        private String legendaCompleta = "";

        Serie(String titulo, String token, Double min, Double max, Double referencia) {
            this.nomeCompleto = switch (titulo) {
                case "Descida" -> "Rede: descida (download)";
                case "Subida" -> "Rede: subida (upload)";
                case "CPU" -> "Processador (CPU)";
                case "Memória" -> "Memória em uso";
                default -> "Temperatura da CPU";
            };
            this.cabecalho = new Rotulo(titulo).tamanho(12f).corDoToken(Tokens.TEXTO2).truncavel();
            grafico.cor(token).escala(min, max).referencia(referencia).altura(ALTURA_DO_GRAFICO);
            celula.setLayout(new BoxLayout(celula, BoxLayout.Y_AXIS));
            JPanel linha1 = Caixa.linha(6, cabecalho, Caixa.mola(), agora);
            linha1.setAlignmentX(LEFT_ALIGNMENT);
            grafico.setAlignmentX(LEFT_ALIGNMENT);
            extremos.setAlignmentX(LEFT_ALIGNMENT);
            celula.add(linha1);
            celula.add(javax.swing.Box.createRigidArea(new Dimension(0, 2)));
            celula.add(grafico);
            celula.add(javax.swing.Box.createRigidArea(new Dimension(0, 2)));
            celula.add(extremos);
        }

        JPanel celula() {
            return celula;
        }

        void mostrar(List<Double> valores, Function<Double, String> formato, String nome) {
            grafico.definir(valores);
            String texto = legendaDaSerie(valores, formato);
            legendaCompleta = texto;
            String[] partes = texto.split(" · ", 2);
            agora.setText(partes[0].startsWith("agora ") ? partes[0].substring(6) : "");
            extremos.setText(partes.length > 1 ? partes[1] : partes[0]);
            celula.setToolTipText(nomeCompleto + ": " + texto);
            grafico.setToolTipText(nomeCompleto + ": " + texto);
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
