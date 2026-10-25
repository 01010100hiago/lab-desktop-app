package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
import br.com.labdesktop.api.modelo.TokensDoProjeto.Modelo;
import br.com.labdesktop.api.modelo.TokensDoProjeto.SessaoDeConsumo;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.Topico;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.BarraSeveridade;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.ColunasProporcionais;
import br.com.labdesktop.ui.componentes.GraficoDeBarras;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TabelaPaginada;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.telas.ModeloDeConsumo.BlocoDoLimite;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Consumo e limite": os totais (hoje, 7 dias, 30 dias e tudo, em US$ equivalente de API, com tokens de saída e
 * respostas), o bloco "Limite da semana" (barra contra o teto, estado ok, atenção ou pausado, quando zera, Definir limite,
 * Retomar e Liberar), as barras dos últimos 30 dias, a linha do Harness e, em sub-abas paginadas, o consumo por modelo e por
 * sessão. Assina {@code CONSUMO(p)} (ao abrir e a cada 60 s) só enquanto está visível; o limite vem do detalhe do projeto.
 */
final class PaginaConsumo extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    static final String SUB_MODELOS = "modelos";
    static final String SUB_SESSOES = "sessoes";
    private static final int LINHAS_POR_PAGINA = 4;

    private final FaixaDeKpis totais = new FaixaDeKpis();

    // limite da semana
    private final CaixaDoServidor caixaDoLimite = new CaixaDoServidor("Limite da semana", "moeda");
    private final Rotulo valorDoLimite = new Rotulo().mono(22f).negrito();
    private final Selo seloDoLimite = new Selo("", br.com.labdesktop.ui.Gravidade.NEUTRO);
    private final BarraSeveridade barraDoLimite = new BarraSeveridade(BarraSeveridade.Tamanho.GROSSA);
    private final TextoCorrido explicacaoDoLimite = new TextoCorrido("", 320).tamanho(12f).corDoToken(Tokens.TEXTO2);
    private final Botao definirLimite = Botoes.mini("Definir limite", "moeda");
    private final Botao retomar = Botoes.mini("Retomar", "play");
    private final Botao liberar = Botoes.mini("Liberar", "ok-circulo");
    private final Rotulo somenteLeitura = new Rotulo("Somente leitura nesta versão: use o painel web para mudar o limite.")
        .corDoToken(Tokens.TEXTO3).tamanho(11.5f).truncavel();

    // gráfico
    private final CaixaDoServidor caixaDoGrafico = new CaixaDoServidor("Últimos 30 dias", "moeda");
    private final GraficoDeBarras grafico = new GraficoDeBarras();
    private final Rotulo harness = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private final Rotulo avisoDoGrafico = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();

    // tabelas
    private final CaixaDoServidor caixaDasTabelas = new CaixaDoServidor("Detalhes do consumo", "moeda");
    private final Abas subAbas = new Abas(true);
    private final CardLayout cartoes = new br.com.labdesktop.ui.componentes.CartoesAjustaveis();
    private final JPanel cartoesPanel = new Painel(cartoes);
    private final TabelaPaginada<Modelo> tabelaDeModelos;
    private final TabelaPaginada<SessaoDeConsumo> tabelaDeSessoes;

    private transient Inscricao inscricao;
    private TokensDoProjeto tokens;
    private String erro;

    PaginaConsumo(ContextoDoProjeto ctx) {
        super(ctx);
        this.tabelaDeModelos = new TabelaPaginada<>(LINHAS_POR_PAGINA, List.of(
            new Coluna<>("Modelo", 2.0, false, true, Modelo::modelo, Comparator.comparing(m -> m.modelo() == null ? "" : m.modelo())),
            new Coluna<>("Respostas", 0.7, true, false, m -> FormatosDoServidor.inteiro(m.respostas()),
                Comparator.comparingLong(m -> m.respostas() == null ? 0 : m.respostas())),
            new Coluna<>("Saída", 0.8, true, false, m -> m.saida() == null ? null : Formato.fmtCompacto(m.saida()),
                Comparator.comparingLong(m -> m.saida() == null ? 0 : m.saida())),
            new Coluna<>("Custo (US$)", 0.9, true, false, m -> Formato.fmtUsd(m.custoUsd()),
                Comparator.comparingDouble(m -> m.custoUsd() == null ? 0 : m.custoUsd()))));
        this.tabelaDeSessoes = new TabelaPaginada<>(LINHAS_POR_PAGINA, List.of(
            new Coluna<>("Sessão", 0.8, false, true, SessaoDeConsumo::id, null),
            new Coluna<>("Início", 1.2, false, false, s -> FormatosDoServidor.diaEHora(s.inicio(), ctx.fuso), null),
            new Coluna<>("Duração", 0.8, false, false, s -> ModeloDeConsumo.duracao(s.inicio(), s.fim()), null),
            new Coluna<>("Modelos", 1.8, false, true, s -> String.join(", ", s.modelos()), null),
            new Coluna<>("Respostas", 0.7, true, false, s -> FormatosDoServidor.inteiro(s.respostas()),
                Comparator.comparingLong(s -> s.respostas() == null ? 0 : s.respostas())),
            new Coluna<>("Saída", 0.7, true, false, s -> s.saida() == null ? null : Formato.fmtCompacto(s.saida()),
                Comparator.comparingLong(s -> s.saida() == null ? 0 : s.saida())),
            new Coluna<>("Custo (US$)", 0.9, true, false, s -> Formato.fmtUsd(s.custoUsd()),
                Comparator.comparingDouble(s -> s.custoUsd() == null ? 0 : s.custoUsd())),
            new Coluna<>("Atribuição", 0.8, false, false, s -> ModeloDeConsumo.atribuicao(s.atribuicao()), null)));
        iniciar();
    }

    @Override
    Set<String> topicosProprios() {
        return inscricao != null ? Set.of(Topico.consumo(ctx.projeto).chave()) : Set.of();
    }

    @Override
    JComponent montarConteudo() {
        totais.nomeAcessivel("Totais de consumo do projeto");
        totais.atualizar(ModeloDeConsumo.totais(null));

        // --- limite
        explicacaoDoLimite.setToolTipText(null);
        definirLimite.addActionListener(e -> ctx.fluxos.definirLimite(ctx.projeto, limiteAtual(), null));
        definirLimite.setToolTipText("Define quanto da semana do plano este projeto pode usar");
        retomar.addActionListener(e -> ctx.fluxos.retomar(ctx.projeto, limiteAtual()));
        retomar.setToolTipText("Tira a pausa e reabre o Claude continuando a conversa");
        liberar.addActionListener(e -> ctx.fluxos.liberar(ctx.projeto, limiteAtual()));
        liberar.setToolTipText("Liberar até a semana virar: isenta o projeto de limite e de freio até a semana do plano virar");
        liberar.getAccessibleContext().setAccessibleName("Liberar até a semana virar");
        caixaDoLimite.linha(Caixa.linha(10, valorDoLimite, seloDoLimite, Caixa.mola()), 0);
        caixaDoLimite.linha(barraDoLimite, 8);
        caixaDoLimite.linha(explicacaoDoLimite, 8);
        caixaDoLimite.linha(Caixa.fluxo(java.awt.FlowLayout.LEFT, 8, definirLimite, retomar, liberar, somenteLeitura), 10);

        // --- gráfico
        grafico.definir(List.of(), ModeloDeConsumo::valorDoAlto, "Consumo dos últimos 30 dias: ainda calculando.");
        caixaDoGrafico.linha(grafico, 0);
        caixaDoGrafico.linha(avisoDoGrafico, 4);
        caixaDoGrafico.linha(harness, 4);

        // --- tabelas
        subAbas.adicionar(SUB_MODELOS, "Por modelo");
        subAbas.adicionar(SUB_SESSOES, "Por sessão");
        subAbas.aoSelecionar(id -> {
            cartoes.show(cartoesPanel, id);
            cartoesPanel.revalidate();
            cartoesPanel.repaint();
        });
        cartoesPanel.add(tabelaDeModelos, SUB_MODELOS);
        cartoesPanel.add(tabelaDeSessoes, SUB_SESSOES);
        cartoes.show(cartoesPanel, SUB_MODELOS);
        caixaDasTabelas.linha(subAbas, 0);
        caixaDasTabelas.linha(cartoesPanel, 6);

        // 44% e 56% fixos: o texto de uma caixa nunca tira largura da outra
        JPanel linhaDoMeio = new Painel(new ColunasProporcionais(14, 0.44, 0.56));
        linhaDoMeio.add(caixaDoLimite);
        linhaDoMeio.add(caixaDoGrafico);

        JPanel conteudo = new Painel(new BorderLayout(0, 12)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        JPanel baixo = new Painel(new BorderLayout(0, 12));
        baixo.add(linhaDoMeio, BorderLayout.NORTH);
        baixo.add(caixaDasTabelas, BorderLayout.CENTER);
        conteudo.add(totais, BorderLayout.NORTH);
        conteudo.add(baixo, BorderLayout.CENTER);
        return conteudo;
    }

    private br.com.labdesktop.api.modelo.Limite limiteAtual() {
        return ctx.detalhe == null ? null : ctx.detalhe.limite();
    }

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------------

    @Override
    void aoAparecer() {
        if (ctx.servidor == null || inscricao != null) {
            return;
        }
        inscricao = ctx.servidor.assinar(Topico.consumo(ctx.projeto), new OuvinteDeTopico<TokensDoProjeto>() {
            @Override
            public void aoAtualizar(TokensDoProjeto t) {
                receber(t);
            }

            @Override
            public void aoFalhar(ErroApi e) {
                if (!(e instanceof ErroApi.ErroRede) && tokens == null) {
                    erro = MensagensDeErro.daApi(e);
                    mostrarConsumo();
                }
            }
        });
    }

    @Override
    void aoSumir() {
        inscricao = soltar(inscricao);
    }

    /** Recebe o consumo (na EDT). Público para os testes alimentarem a aba com dados fixos. */
    void receber(TokensDoProjeto novo) {
        this.tokens = novo;
        this.erro = null;
        mostrarConsumo();
    }

    @Override
    void atualizarConteudo() {
        mostrarLimite();
        mostrarConsumo();
    }

    // ---------------------------------------------------------------------------------------------
    // Desenho
    // ---------------------------------------------------------------------------------------------

    private void mostrarLimite() {
        BlocoDoLimite b = ModeloDeConsumo.limite(limiteAtual(), ctx.agora(), ctx.fuso);
        valorDoLimite.setText(b.valor());
        valorDoLimite.corDoToken(b.pausado() ? Tokens.PERIGO : b.severidade() == br.com.labdesktop.ui.Severidade.ALTA
            ? Tokens.ATENCAO : Tokens.TEXTO);
        seloDoLimite.atualizar(b.estado(), b.gravidade(), b.icone());
        barraDoLimite.setVisible(b.temBarra());
        if (b.temBarra()) {
            barraDoLimite.definir(b.fracao(), b.severidade(), b.valor() + " do limite da semana");
        }
        explicacaoDoLimite.setText(b.explicacao());
        boolean acoes = ctx.acoesLiberadas();
        definirLimite.setVisible(acoes && ctx.detalhe != null);
        retomar.setVisible(acoes && b.pausado());
        liberar.setVisible(acoes && (b.pausado() || b.temTeto()) && !b.liberado());
        somenteLeitura.setVisible(!acoes);
        if (ctx.fluxos != null) {
            var o = ctx.fluxos.ocupadas(ctx.projeto);
            retomar.definirOcupado(o.contains(FluxosDeProjeto.RETOMAR));
        }
        caixaDoLimite.terminar();
    }

    private void mostrarConsumo() {
        totais.atualizar(ModeloDeConsumo.totais(tokens));
        if (tokens == null) {
            avisoDoGrafico.setText(erro != null ? "Não consegui ler o consumo: " + erro : "Calculando o consumo…");
            harness.setText("");
            harness.setVisible(false);
            tabelaDeModelos.definir(List.of(), "Ainda calculando.");
            tabelaDeSessoes.definir(List.of(), "Ainda calculando.");
            caixaDoGrafico.terminar();
            return;
        }
        grafico.definir(ModeloDeConsumo.barras(tokens), ModeloDeConsumo::valorDoAlto, ModeloDeConsumo.resumoDoGrafico(tokens));
        avisoDoGrafico.setText(tokens.temConsumo() ? ModeloDeConsumo.legendaDoGrafico(tokens)
            : "Nenhum consumo registrado nos últimos 30 dias.");
        avisoDoGrafico.setToolTipText(ModeloDeConsumo.resumoDoGrafico(tokens));
        String h = ModeloDeConsumo.harness(tokens);
        harness.setVisible(h != null);
        harness.setText(h == null ? "" : h);
        tabelaDeModelos.definir(tokens.modelos(), "Nenhum modelo usado ainda neste projeto.");
        tabelaDeSessoes.definir(tokens.sessoes(), "Nenhuma sessão do Claude registrada neste projeto.");
        caixaDoGrafico.terminar();
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    FaixaDeKpis totais() {
        return totais;
    }

    GraficoDeBarras grafico() {
        return grafico;
    }

    BarraSeveridade barraDoLimite() {
        return barraDoLimite;
    }

    Selo seloDoLimite() {
        return seloDoLimite;
    }

    String textoDoValorDoLimite() {
        return valorDoLimite.getText();
    }

    String textoDaExplicacaoDoLimite() {
        return explicacaoDoLimite.getText();
    }

    Botao botaoDefinirLimite() {
        return definirLimite;
    }

    Botao botaoRetomar() {
        return retomar;
    }

    Botao botaoLiberar() {
        return liberar;
    }

    String textoDoHarness() {
        return harness.isVisible() ? harness.getText() : null;
    }

    String textoDoAvisoDoGrafico() {
        return avisoDoGrafico.getText();
    }

    TabelaPaginada<Modelo> tabelaDeModelos() {
        return tabelaDeModelos;
    }

    TabelaPaginada<SessaoDeConsumo> tabelaDeSessoes() {
        return tabelaDeSessoes;
    }

    Abas subAbas() {
        return subAbas;
    }

    boolean somenteLeituraVisivel() {
        return somenteLeitura.isVisible();
    }

    boolean consumoAssinado() {
        return inscricao != null;
    }
}
