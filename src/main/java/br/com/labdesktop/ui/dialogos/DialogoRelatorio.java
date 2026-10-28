package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.GravadorDeArquivoNovo;
import br.com.labdesktop.tendencias.DadosDeTendencia;
import br.com.labdesktop.tendencias.EstatisticaDeSerie;
import br.com.labdesktop.tendencias.ExportadorDeRelatorio;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.tendencias.RelatorioDeTendencias;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.telas.TabelaDeLinhas;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JToggleButton;

/**
 * O relatório dos dados de tendência: o período escolhido (as mesmas cinco janelas do Resumo) e, para cada métrica, mínimo, média, máximo, percentil 95 e a hora do pico, em tabela;
 * as frases de resumo em português simples; a estimativa do disco (só com 24 h de dados, dita como estimativa); e a exportação: copiar o texto, salvar o texto ou salvar o CSV
 * ({@link ExportadorDeRelatorio}: UTF-8 com BOM, separador {@code ;}, vírgula decimal). O arquivo vai para onde o usuário escolher no seletor de arquivos do sistema (começa em Downloads) e
 * NUNCA sobrescreve outro: se o nome já existe, o app acrescenta {@code -2}, {@code -3}.
 *
 * <p>Os dados vêm de {@link Fonte} (bloqueia: roda em fundo, nunca na EDT); trocar de período mostra "Carregando" e uma resposta de um período que o usuário já trocou é descartada.
 */
public final class DialogoRelatorio extends Dialogo {

    /** De onde vêm os dados de cada período (o agente; ou os 10 minutos de sempre num agente antigo). Bloqueia. */
    @FunctionalInterface
    public interface Fonte {
        DadosDeTendencia carregar(JanelaDeTempo janela) throws Exception;
    }

    /** Grava o arquivo (em fundo). Os testes trocam por uma gravação em memória. */
    @FunctionalInterface
    public interface Gravador {
        void gravar(Path destino, byte[] conteudo) throws IOException;
    }

    private static final DateTimeFormatter DATA_E_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final Fonte fonte;
    private final String nomeDoServidor;
    private final ZoneId fuso;
    private final Supplier<Instant> relogio;
    private final EscolhedorLocal escolhedor;
    private final Gravador gravador;
    private final Notificacoes avisos;
    private final boolean podeLongoPrazo;

    private final Map<JanelaDeTempo, JToggleButton> botoesDeJanela = new EnumMap<>(JanelaDeTempo.class);
    private final Rotulo situacao = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f).truncavel();
    private final TextoCorrido erroDeCarga = erroCorrido();
    private final TabelaDeLinhas tabela = new TabelaDeLinhas(new TabelaDeLinhas.Coluna("Métrica", 1.2, false, false), new TabelaDeLinhas.Coluna("Mínimo", 1, true, true),
        new TabelaDeLinhas.Coluna("Média", 1, true, true), new TabelaDeLinhas.Coluna("Máximo", 1, true, true), new TabelaDeLinhas.Coluna("P95", 1, true, true),
        new TabelaDeLinhas.Coluna("Hora do pico", 1.7, true, true));
    private final javax.swing.JTextArea resumo = new javax.swing.JTextArea();
    private final Botao copiarTexto;
    private final Botao salvarCsv = Botoes.secundario("Salvar CSV…", "baixar");
    private final Botao salvarTexto = Botoes.secundario("Salvar texto…", "baixar");
    private final Botao fechar = Botoes.secundario("Fechar", "fechar");
    private final AtomicInteger geracao = new AtomicInteger();

    private JanelaDeTempo janela;
    private DadosDeTendencia dados;
    private RelatorioDeTendencias relatorio;
    private boolean carregando;
    private String ultimoArquivoSalvo = "";

    /**
     * @param inicial   o período que estava no Resumo
     * @param jaTenho   os dados desse período que o Resumo já tinha ({@code null}: carrega)
     * @param podeLongoPrazo o agente tem o histórico de até um mês (senão só o botão de 10 min vale)
     */
    public DialogoRelatorio(Fonte fonte, JanelaDeTempo inicial, DadosDeTendencia jaTenho, boolean podeLongoPrazo, String nomeDoServidor, ZoneId fuso, Supplier<Instant> relogio,
                            EscolhedorLocal escolhedor, Gravador gravador, CopiadorDeTexto copiador, Notificacoes avisos) {
        super("Relatório de tendências");
        this.fonte = fonte;
        this.nomeDoServidor = nomeDoServidor == null ? "" : nomeDoServidor;
        this.fuso = fuso;
        this.relogio = relogio;
        this.escolhedor = escolhedor;
        this.gravador = gravador;
        this.avisos = avisos;
        this.podeLongoPrazo = podeLongoPrazo;
        this.janela = inicial;
        this.copiarTexto = BotaoCopiar.criar("Copiar texto", this::textoDoRelatorio, copiador, avisos);

        ButtonGroup grupo = new ButtonGroup();
        List<Component> seletor = new ArrayList<>();
        for (JanelaDeTempo j : JanelaDeTempo.values()) {
            JToggleButton b = Botoes.filtro(j.rotuloCurto(), "Relatório d" + (j == JanelaDeTempo.UMA_HORA ? "a " : "o ") + j.rotuloLongo());
            b.setName("periodo-" + j.codigo());
            boolean vale = podeLongoPrazo || j == JanelaDeTempo.DEZ_MINUTOS;
            b.setEnabled(vale);
            if (!vale) {
                b.setToolTipText("Este agente só guarda os últimos 10 minutos. Atualize o agente para ver " + j.rotuloLongo() + ".");
            }
            grupo.add(b);
            botoesDeJanela.put(j, b);
            seletor.add(b);
            b.addActionListener(e -> {
                if (b.isSelected() && j != this.janela) {
                    escolher(j, null);
                }
            });
        }
        botoesDeJanela.get(inicial).setSelected(true);

        resumo.setEditable(false);
        resumo.setLineWrap(true);
        resumo.setWrapStyleWord(true);
        resumo.setOpaque(false);
        resumo.setBorder(null);
        resumo.putClientProperty("html.disable", Boolean.TRUE);
        resumo.setName("resumo-do-relatorio");
        br.com.labdesktop.ui.tema.Fontes.aplicar(resumo, br.com.labdesktop.ui.tema.Fontes.TEXTO, java.awt.Font.PLAIN, 12.5f);
        resumo.setColumns(70);

        javax.swing.JScrollPane rolagemDoResumo = new javax.swing.JScrollPane(resumo, javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rolagemDoResumo.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        rolagemDoResumo.setOpaque(false);
        rolagemDoResumo.getViewport().setOpaque(false);
        rolagemDoResumo.setPreferredSize(new java.awt.Dimension(820, 176)); // o resumo rola POR DENTRO: a janela não cresce com as frases
        rolagemDoResumo.setName("rolagem-do-resumo");
        JComponent corpo = Caixa.coluna(10,
            cabecalho("lista", "Relatório de tendências" + (this.nomeDoServidor.isBlank() ? "" : ": " + this.nomeDoServidor)),
            Caixa.linha(4, seletor.toArray(new Component[0])),
            situacao, erroDeCarga,
            tabela,
            new Rotulo("Resumo").negrito(),
            rolagemDoResumo,
            dicaCorrida(RelatorioDeTendencias.NOTA_DOS_NUMEROS + " A estimativa do disco é só uma conta pela tendência do período, e não uma promessa."));
        salvarCsv.addActionListener(e -> salvar("csv", "Planilha CSV", false));
        salvarTexto.addActionListener(e -> salvar("txt", "Texto", true));
        fechar.addActionListener(e -> fechar());
        salvarCsv.setToolTipText("Uma planilha (CSV, separador ponto e vírgula, vírgula decimal) com todos os pontos do período");
        salvarTexto.setToolTipText("O resumo e a tabela, em texto simples");
        javax.swing.JPanel todo = new br.com.labdesktop.ui.componentes.Painel(new java.awt.BorderLayout(0, 12));
        todo.add(corpo, java.awt.BorderLayout.CENTER);
        todo.add(rodape(null, copiarTexto, salvarTexto, salvarCsv, fechar), java.awt.BorderLayout.SOUTH);
        conteudo(todo);
        botaoPadrao(fechar);
        if (jaTenho != null && jaTenho.janela() == inicial) {
            receber(jaTenho);
        } else {
            escolher(inicial, null);
        }
    }

    @Override
    protected boolean redimensionavel() {
        return false;
    }

    // ---------------------------------------------------------------------------------------------

    private void escolher(JanelaDeTempo nova, DadosDeTendencia ja) {
        this.janela = nova;
        botoesDeJanela.get(nova).setSelected(true);
        int minha = geracao.incrementAndGet();
        if (ja != null) {
            receber(ja);
            return;
        }
        carregando = true;
        dados = null;
        relatorio = null;
        dizerErro(erroDeCarga, "");
        situacao.setText("Carregando " + nova.rotuloLongo() + "…");
        tabela.definir(List.of(), "Carregando…");
        resumo.setText("");
        atualizarBotoes();
        Tarefas.rodar(() -> fonte.carregar(nova), d -> {
            if (minha != geracao.get() || jaFechou()) {
                return; // o usuário já trocou de período: esta resposta é de um período que ele não está vendo
            }
            carregando = false;
            receber(d);
        }, falha -> {
            if (minha != geracao.get() || jaFechou()) {
                return;
            }
            carregando = false;
            situacao.setText("");
            dizerErro(erroDeCarga, "Não consegui carregar " + nova.rotuloLongo() + ": " + MensagensDeErro.de(falha) + " Escolha o período de novo para tentar outra vez.");
            tabela.definir(List.of(), "Sem dados para mostrar.");
            resumo.setText("");
            atualizarBotoes();
            reajustar();
        });
    }

    private void receber(DadosDeTendencia d) {
        this.dados = d;
        this.relatorio = RelatorioDeTendencias.de(d, fuso, relogio.get());
        carregando = false;
        dizerErro(erroDeCarga, "");
        situacao.setText(d.janela().rotuloLongo().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + d.janela().rotuloLongo().substring(1) + ", até " + DATA_E_HORA.format(relogio.get().atZone(fuso))
            + (d.deLongoPrazo() ? "." : ". Este agente guarda só 10 minutos."));
        List<List<String>> linhas = new ArrayList<>();
        for (RelatorioDeTendencias.Linha l : relatorio.linhas()) {
            EstatisticaDeSerie.Resumo r = l.resumo();
            linhas.add(List.of(l.metrica().nome(), RelatorioDeTendencias.valorNaTabela(l.metrica(), r.minimo()), RelatorioDeTendencias.valorNaTabela(l.metrica(), r.media()),
                RelatorioDeTendencias.valorNaTabela(l.metrica(), r.maximo()), RelatorioDeTendencias.valorNaTabela(l.metrica(), r.p95()),
                r.instanteDoPico() == null ? "—" : DATA_E_HORA.format(r.instanteDoPico().atZone(fuso))));
        }
        tabela.definir(linhas, "Sem dados.");
        resumo.setText(String.join("\n", relatorio.frases().stream().map(f -> "• " + f).toList()));
        resumo.setCaretPosition(0);
        atualizarBotoes();
        reajustar();
    }

    private void atualizarBotoes() {
        boolean tem = relatorio != null && !carregando;
        copiarTexto.setEnabled(tem);
        salvarCsv.setEnabled(tem);
        salvarTexto.setEnabled(tem);
    }

    private String textoDoRelatorio() {
        return relatorio == null ? "" : ExportadorDeRelatorio.texto(relatorio, nomeDoServidor, relogio.get());
    }

    private void salvar(String extensao, String descricao, boolean texto) {
        if (relatorio == null) {
            return;
        }
        String nome = ExportadorDeRelatorio.nomeSugerido(nomeDoServidor, janela, relogio.get(), fuso, extensao);
        java.util.Optional<Path> escolhido = escolhedor.escolherArquivoParaSalvar(nome, extensao, descricao);
        if (escolhido.isEmpty()) {
            return;
        }
        String conteudo = texto ? textoDoRelatorio() : ExportadorDeRelatorio.csv(dados, fuso);
        byte[] bytes = conteudo.getBytes(StandardCharsets.UTF_8);
        Path pedido = escolhido.get();
        Tarefas.rodar(() -> {
            Path destino = GravadorDeArquivoNovo.livre(pedido);
            gravador.gravar(destino, bytes);
            return destino;
        }, destino -> {
            ultimoArquivoSalvo = destino.toString();
            avisos.ok("Relatório salvo em " + destino + ".");
        }, falha -> avisos.erro("Não consegui salvar o relatório: " + MensagensDeErro.de(falha)));
    }

    /** A gravação de produção: arquivo novo (nunca sobrescreve), só do usuário. */
    public static Gravador gravadorDoSistema() {
        return GravadorDeArquivoNovo::gravar;
    }

    // ---- para os testes ----

    public JanelaDeTempo periodo() {
        return janela;
    }

    public RelatorioDeTendencias relatorio() {
        return relatorio;
    }

    public JToggleButton botaoDaJanela(JanelaDeTempo j) {
        return botoesDeJanela.get(j);
    }

    public Botao botaoCopiarTexto() {
        return copiarTexto;
    }

    public Botao botaoSalvarCsv() {
        return salvarCsv;
    }

    public Botao botaoSalvarTexto() {
        return salvarTexto;
    }

    public Botao botaoFechar() {
        return fechar;
    }

    public boolean carregando() {
        return carregando;
    }

    public String textoDaSituacao() {
        return situacao.getText();
    }

    public String textoDoErro() {
        return erroDeCarga.getText();
    }

    public String textoDoResumo() {
        return resumo.getText();
    }

    public TabelaDeLinhas tabela() {
        return tabela;
    }

    public String ultimoArquivoSalvo() {
        return ultimoArquivoSalvo;
    }

    public boolean podeLongoPrazo() {
        return podeLongoPrazo;
    }
}
