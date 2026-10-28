package br.com.labdesktop.ui.telas;

import br.com.labdesktop.tendencias.Amostra;
import br.com.labdesktop.tendencias.DadosDeTendencia;
import br.com.labdesktop.tendencias.EstatisticaDeSerie;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.tendencias.Metrica;
import br.com.labdesktop.tendencias.UnidadesDeMedida;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Sparkline;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * "Tendências": os SEIS gráficos de linha numa grade 3 x 2, na ordem CPU, Memória, Disco, Escrita no disco, Download e Upload, com um seletor de período (10 min, 1 hora, 1 dia,
 * 1 semana, 1 mês) e o botão Relatório. Cada gráfico tem o nome, o valor de agora (fonte mono, a unidade em cor secundária), a curva e "mín · méd · máx" do período escrito (ninguém
 * deveria depender de ler uma curva). Um período sem dado diz "sem dado ainda"; um período que está chegando mostra o esqueleto.
 *
 * <p>Não consulta nada: quem escolhe o período e traz os dados é a {@link PaginaResumo}, que entrega uma {@link Visao} pronta; o bloco só desenha (na EDT) e avisa quando o usuário troca o
 * período ou pede o relatório.
 */
final class BlocoDeTendencias extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;
    private static final int ALTURA_DO_GRAFICO = 30;
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    /** O que o bloco mostra de cada vez. */
    enum Situacao {
        /** O período escolhido ainda não respondeu: esqueleto. */
        CARREGANDO,
        /** Dados na tela. */
        OK,
        /** O servidor não tem o agente: "Instale o agente para ver a tendência" (sem vermelho). */
        SEM_AGENTE,
        /** O agente é de antes do histórico de longo prazo: só os 10 minutos de sempre. */
        AGENTE_SO_DEZ_MINUTOS,
        /** O período falhou, mas há um dado antigo na tela. */
        FALHOU_COM_DADO,
        /** O período falhou e não há nada para mostrar. */
        FALHOU_SEM_DADO
    }

    /**
     * @param janela        o período escolhido
     * @param dados         os seis gráficos ({@code null} sem nada ainda)
     * @param situacao      o estado
     * @param podeLongoPrazo o agente tem o histórico de até um mês (senão só o botão de 10 min vale)
     * @param fuso          para as horas escritas
     */
    record Visao(JanelaDeTempo janela, DadosDeTendencia dados, Situacao situacao, boolean podeLongoPrazo, ZoneId fuso) { }

    private final Map<JanelaDeTempo, JToggleButton> botoesDeJanela = new EnumMap<>(JanelaDeTempo.class);
    private final Botao relatorio = Botoes.mini("Relatório", "lista");
    private final Rotulo aviso = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private final Map<Metrica, Celula> celulas = new EnumMap<>(Metrica.class);
    private Consumer<JanelaDeTempo> aoMudarJanela = j -> { };
    private Runnable aoAbrirRelatorio = () -> { };
    private JanelaDeTempo janela = JanelaDeTempo.padrao();
    private boolean atualizandoSeletor;
    private Visao ultima;
    private boolean avisoComTexto;

    BlocoDeTendencias() {
        super("Tendências", "pulso");
        ButtonGroup grupo = new ButtonGroup();
        List<java.awt.Component> seletor = new ArrayList<>();
        for (JanelaDeTempo j : JanelaDeTempo.values()) {
            JToggleButton b = Botoes.filtro(j.rotuloCurto(), "Mostrar " + j.rotuloLongo());
            b.setName("periodo-" + j.codigo());
            b.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 8; minimumHeight: 26; margin: 1,9,1,9; selectedBackground: $Lab.destaqueSuave; "
                + "selectedForeground: $Lab.destaque; pressedBackground: $Lab.destaqueSuave");
            b.getAccessibleContext().setAccessibleName("Período: " + j.rotuloLongo());
            grupo.add(b);
            botoesDeJanela.put(j, b);
            seletor.add(b);
            b.addActionListener(e -> {
                if (!atualizandoSeletor && b.isSelected()) {
                    janela = j;
                    aoMudarJanela.accept(j);
                }
            });
        }
        botoesDeJanela.get(janela).setSelected(true);
        relatorio.setToolTipText("Abre o relatório deste período: mínimo, média, máximo, pico e a estimativa do disco, para copiar ou salvar");
        relatorio.addActionListener(e -> aoAbrirRelatorio.run());
        relatorio.setName("botao-relatorio");
        JPanel linhaDoSeletor = Caixa.linha(4, seletor.toArray(new java.awt.Component[0]));
        linhaDoSeletor.add(Caixa.mola());
        linhaDoSeletor.add(relatorio);
        linha(linhaDoSeletor, 0);

        aviso.setName("aviso-das-tendencias");
        apoio(aviso); // o aviso mora na linha do título (à direita), no lugar de "últimos 10 minutos": nenhuma altura a mais, nunca empurra a página para a rolagem

        JPanel grade = new Painel(new GridLayout(2, 3, 14, 4));
        for (Metrica m : Metrica.values()) {
            Celula c = new Celula(m);
            celulas.put(m, c);
            grade.add(c.painel());
        }
        linha(grade, 4);
        mostrar(new Visao(janela, null, Situacao.CARREGANDO, false, ZoneId.systemDefault()));
    }

    void definirAoMudarJanela(Consumer<JanelaDeTempo> quem) {
        this.aoMudarJanela = quem == null ? j -> { } : quem;
    }

    void definirAoAbrirRelatorio(Runnable quem) {
        this.aoAbrirRelatorio = quem == null ? () -> { } : quem;
    }

    /** O período que está marcado no seletor. */
    JanelaDeTempo janela() {
        return janela;
    }

    /** Marca um período sem avisar ninguém (a memória do usuário, ao abrir; ou a troca que a própria página fez). */
    void marcarJanela(JanelaDeTempo nova) {
        atualizandoSeletor = true;
        try {
            janela = nova;
            botoesDeJanela.get(nova).setSelected(true);
        } finally {
            atualizandoSeletor = false;
        }
    }

    /** Desenha o que a página entregou. */
    void mostrar(Visao v) {
        this.ultima = v;
        marcarJanela(v.janela());
        for (JanelaDeTempo j : JanelaDeTempo.values()) {
            // agente de antes do histórico de longo prazo: só o botão de 10 min vale (os outros ficam apagados, com a razão na dica)
            boolean semAgente = v.situacao() == Situacao.SEM_AGENTE;
            boolean vale = !semAgente && (v.podeLongoPrazo() || j == JanelaDeTempo.DEZ_MINUTOS);
            botoesDeJanela.get(j).setEnabled(vale);
            botoesDeJanela.get(j).setToolTipText(vale ? "Mostrar " + j.rotuloLongo()
                : semAgente ? "Sem o agente não há tendência para mostrar." : "Este agente só guarda os últimos 10 minutos. Atualize o agente para ver " + j.rotuloLongo() + ".");
        }
        boolean carregando = v.situacao() == Situacao.CARREGANDO && (v.dados() == null || !v.dados().temAlgumDado());
        DadosDeTendencia d = v.dados();
        for (Metrica m : Metrica.values()) {
            celulas.get(m).mostrar(d, carregando, v.fuso());
        }
        String texto = switch (v.situacao()) {
            case SEM_AGENTE -> "Instale o agente para ver a tendência.";
            case AGENTE_SO_DEZ_MINUTOS -> "Este agente só guarda 10 min. Atualize o agente.";
            case FALHOU_COM_DADO -> "Não consegui carregar: mostrando o último dado.";
            case FALHOU_SEM_DADO -> "Não consegui carregar este período. Tentando de novo.";
            case CARREGANDO, OK -> d != null && d.deLongoPrazo() ? desdeQuando(d, v.fuso()) : "";
        };
        boolean temAviso = !texto.isEmpty();
        String mostrado = temAviso ? texto : v.situacao() == Situacao.SEM_AGENTE ? "" : v.janela().rotuloLongo();
        aviso.setText(mostrado);
        aviso.setToolTipText(mostrado.isEmpty() ? null : mostrado);
        aviso.corDoToken(v.situacao() == Situacao.FALHOU_COM_DADO || v.situacao() == Situacao.FALHOU_SEM_DADO ? Tokens.ATENCAO : temAviso ? Tokens.TEXTO2 : Tokens.TEXTO3);
        avisoComTexto = temAviso;
        relatorio.setEnabled(v.situacao() != Situacao.SEM_AGENTE);
        terminar();
    }

    /** "Só há dado desde 28/10 11:00." quando o começo do período está vazio (o agente começou a gravar depois); vazio quando o período está cheio. */
    private static String desdeQuando(DadosDeTendencia d, ZoneId fuso) {
        Instant primeiro = null;
        for (Metrica m : Metrica.values()) {
            for (Amostra a : d.amostras(m)) {
                if (a.valida() && (primeiro == null || a.quando().isBefore(primeiro))) {
                    primeiro = a.quando();
                }
            }
        }
        if (primeiro == null) {
            return "";
        }
        long vazio = java.time.Duration.between(d.inicio(), primeiro).toSeconds();
        return vazio > d.janela().duracao().toSeconds() / 8 ? "Só há dado desde " + DIA_E_HORA.format(primeiro.atZone(fuso)) + "." : "";
    }

    /** O texto "mín · méd · máx" de uma série (para a célula e para os testes). {@code null}: sem dado. */
    static String legendaDe(Metrica m, EstatisticaDeSerie.Resumo r, String unidadeDoValorDeAgora) {
        if (r.vazio()) {
            return "sem dado ainda";
        }
        // cabe numa célula estreita: a unidade só aparece quando é diferente da do número grande da célula ("mín 4 · méd 8 · máx 12"; com unidades mistas: "mín 0 b/s · méd 40 · máx 3 Mb/s")
        String menor = curto(m, r.minimo());
        String media = curto(m, r.media());
        String maior = curto(m, r.maximo());
        String un = unidadeDe(maior);
        if (!un.isEmpty() && unidadeDe(menor).equals(un) && unidadeDe(media).equals(un)) {
            menor = semUnidade(menor, un);
            media = semUnidade(media, un);
            if (un.strip().equals(unidadeDoValorDeAgora)) {
                maior = semUnidade(maior, un); // a unidade do número grande da célula, logo acima, é a mesma: não repete (cabe em uma célula estreita)
            }
        }
        return "mín " + menor + " · méd " + media + " · máx " + maior;
    }

    /** O valor da legenda: porcentagem sem casas (o número grande da célula já mostra a precisão), o resto como no relatório. */
    private static String curto(Metrica m, Double v) {
        return m.ehPorcentagem() ? UnidadesDeMedida.porcentagem(v, 0) : m.formatar(v);
    }

    /** A unidade no fim do texto ("kb/s", "%"); vazia sem unidade. */
    private static String unidadeDe(String v) {
        if (v.endsWith("%")) {
            return "%";
        }
        int i = v.lastIndexOf(' ');
        return i > 0 ? v.substring(i) : "";
    }

    private static String semUnidade(String v, String unidade) {
        return v.substring(0, v.length() - unidade.length());
    }

    /** A cor (token do tema) da linha de cada gráfico: tons foscos e sem azul; o que distingue um gráfico do outro não depende só da cor (o nome, a seta e o traço). */
    static String tokenDaLinha(Metrica m) {
        return switch (m) {
            case CPU -> Tokens.DESTAQUE;
            case MEMORIA -> Tokens.SERIE6;
            case DISCO -> Tokens.SERIE7;
            case ESCRITA_NO_DISCO -> Tokens.SERIE5;
            case DOWNLOAD -> Tokens.SERIE8;
            case UPLOAD -> Tokens.DESTAQUE;
        };
    }

    /** O Upload usa linha tracejada: acompanha o Download e não se confunde com ele (nem com a CPU, que tem a mesma cor) sem depender da cor. */
    static boolean tracejada(Metrica m) {
        return m == Metrica.UPLOAD;
    }

    /** Uma célula da grade: o nome e o valor de agora na mesma linha, a curva e a legenda embaixo. */
    private static final class Celula {
        private final Metrica metrica;
        private final Rotulo nome;
        private final Rotulo numero = new Rotulo().mono(14f).negrito().aDireita();
        private final Rotulo unidade = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f);
        private final Sparkline grafico = new Sparkline();
        private final Rotulo legenda = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(11f).truncavel();
        private final JPanel painel = new Painel();
        private String textoDaLegenda = "";
        private String valorAgora = "";

        Celula(Metrica m) {
            this.metrica = m;
            this.nome = new Rotulo(m.nome()).tamanho(12.5f).corDoToken(Tokens.TEXTO2).truncavel();
            if (m == Metrica.DOWNLOAD || m == Metrica.UPLOAD) {
                nome.setIcon(Icones.colorido(m == Metrica.DOWNLOAD ? "seta-baixo" : "seta-cima", 12, Tokens.TEXTO2));
                nome.setIconTextGap(4);
            }
            String cor = tokenDaLinha(m);
            // CPU e Memória e as taxas partem do zero (uma onda pequena não vira montanha); o Disco segue os dados, para uma subida lenta aparecer
            grafico.cor(cor).altura(ALTURA_DO_GRAFICO).tracejado(tracejada(m));
            if (m == Metrica.DISCO) {
                grafico.escala(null, null);
            } else if (m == Metrica.CPU || m == Metrica.MEMORIA) {
                grafico.escala(0.0, null);
            } else {
                grafico.escala(0.0, null);
            }
            painel.setLayout(new BoxLayout(painel, BoxLayout.Y_AXIS));
            JPanel linha1 = Caixa.linha(4, nome, Caixa.mola(), numero, unidade);
            linha1.setAlignmentX(LEFT_ALIGNMENT);
            grafico.setAlignmentX(LEFT_ALIGNMENT);
            legenda.setAlignmentX(LEFT_ALIGNMENT);
            painel.add(linha1);
            painel.add(javax.swing.Box.createRigidArea(new Dimension(0, 1)));
            painel.add(grafico);
            painel.add(javax.swing.Box.createRigidArea(new Dimension(0, 1)));
            painel.add(legenda);
            painel.setName("grafico-" + m.nome());
        }

        JPanel painel() {
            return painel;
        }

        void mostrar(DadosDeTendencia d, boolean carregando, ZoneId fuso) {
            List<Amostra> amostras = d == null ? List.of() : d.amostras(metrica);
            List<Amostra> maximos = d == null || !metrica.temMaximo() ? null : d.amostrasDeMaximo(metrica);
            EstatisticaDeSerie.Resumo r = EstatisticaDeSerie.de(amostras, maximos);
            grafico.definirComLacunas(amostras.stream().map(Amostra::valor).toList());
            grafico.carregando(carregando);
            grafico.descricaoDoPonto(i -> {
                if (d == null || i >= amostras.size() || !amostras.get(i).valida()) {
                    return null;
                }
                return DIA_E_HORA.format(amostras.get(i).quando().atZone(fuso)) + ": " + metrica.formatar(amostras.get(i).valor());
            });
            Double agora = ultimoValido(amostras);
            valorAgora = agora == null ? "—" : metrica.formatar(agora);
            int corte = corteDaUnidade(valorAgora);
            numero.setText(corte < 0 ? valorAgora : valorAgora.substring(0, corte).strip());
            unidade.setText(corte < 0 ? "" : valorAgora.substring(corte).strip());
            textoDaLegenda = carregando ? "carregando…" : legendaDe(metrica, r, corte < 0 ? "" : valorAgora.substring(corte).strip());
            legenda.setText(textoDaLegenda);
            String completo = r.vazio() ? "" : metrica.nome() + ": agora " + valorAgora + ", mínimo " + metrica.formatar(r.minimo()) + ", média " + metrica.formatar(r.media())
                + ", máximo " + metrica.formatar(r.maximo());
            legenda.setToolTipText(completo.isEmpty() ? null : completo);
            painel.setToolTipText(null);
            grafico.descrever(metrica.nome() + ": " + (r.vazio() ? "sem dado ainda" : "agora " + valorAgora + "; " + textoDaLegenda));
        }

        /** Onde o número termina e a unidade começa ("12 kb/s" -> 2; "76%" -> 2); -1 sem unidade. */
        private static int corteDaUnidade(String v) {
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (!(Character.isDigit(c) || c == ',' || c == '.' || c == '-' || c == ' ')) {
                    return v.indexOf(' ') > 0 && v.indexOf(' ') < i ? v.indexOf(' ') : i;
                }
            }
            return -1;
        }

        private static Double ultimoValido(List<Amostra> a) {
            for (int i = a.size() - 1; i >= 0; i--) {
                if (a.get(i).valida()) {
                    return a.get(i).valor();
                }
            }
            return null;
        }
    }

    // ---- para os testes ----

    /** Os seis gráficos, na ordem: CPU, Memória, Disco, Escrita no disco, Download, Upload. */
    List<Sparkline> graficos() {
        List<Sparkline> l = new ArrayList<>();
        for (Metrica m : Metrica.values()) {
            l.add(celulas.get(m).grafico);
        }
        return l;
    }

    /** A legenda "mín · méd · máx" de cada gráfico. */
    List<String> legendas() {
        List<String> l = new ArrayList<>();
        for (Metrica m : Metrica.values()) {
            l.add(celulas.get(m).textoDaLegenda);
        }
        return l;
    }

    /** O valor de agora de cada gráfico, com a unidade ("12 kb/s"). */
    List<String> valoresDeAgora() {
        List<String> l = new ArrayList<>();
        for (Metrica m : Metrica.values()) {
            l.add(celulas.get(m).valorAgora);
        }
        return l;
    }

    /** Os nomes dos seis gráficos, na ordem em que a grade os mostra. */
    List<String> nomes() {
        List<String> l = new ArrayList<>();
        for (Metrica m : Metrica.values()) {
            l.add(celulas.get(m).nome.getText());
        }
        return l;
    }

    Rotulo aviso() {
        return aviso;
    }

    /** O rótulo do título mostra um aviso (e não só o nome do período)? */
    boolean temAviso() {
        return avisoComTexto;
    }

    Botao botaoRelatorio() {
        return relatorio;
    }

    JToggleButton botaoDaJanela(JanelaDeTempo j) {
        return botoesDeJanela.get(j);
    }

    Visao ultimaVisao() {
        return ultima;
    }
}
