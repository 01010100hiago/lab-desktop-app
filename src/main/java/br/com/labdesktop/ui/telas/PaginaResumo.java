package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.Topico;
import br.com.labdesktop.tendencias.DadosDeTendencia;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.ui.componentes.ListaDeAtencao;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.dialogos.DialogoRegistro;
import br.com.labdesktop.ui.dialogos.DialogoRelatorio;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Resumo": UMA tela, sem rolagem (regra dura, com teste: cabe em 1100 x 700 e em 1440 x 860). De cima para baixo: o cabeçalho enxuto, a linha de 6 indicadores (cada um clicável,
 * abre a aba do assunto) e, em duas colunas: à ESQUERDA as Tendências (seis gráficos 3 x 2, de 10 minutos a 1 mês, com o relatório) e, logo abaixo, o Registro do servidor (as linhas
 * mais novas em cima, a lista rola POR DENTRO e a caixa ocupa a altura que sobrar); à DIREITA "Precisa de atenção" (no máximo 5 linhas, do mais grave ao menos grave).
 *
 * <p>Usa {@code /api/servidor}, o detalhe do servidor, a leitura de segurança, o histórico da janela escolhida (só se o agente diz {@code recursos.historico}; senão os 10 minutos de
 * sempre) e o registro (por SSH ou do container do laboratório local). Cada um assina só enquanto esta aba está visível. Num servidor SSH SEM o agente o Resumo continua: mostra a
 * Segurança e o Registro e diz nas Tendências "Instale o agente para ver a tendência".
 */
public final class PaginaResumo extends PaginaDoServidor {

    private static final long serialVersionUID = 1L;

    private final transient Consumer<Aba> aoNavegar;
    private final transient ApoioDoResumo apoio;
    private final CabecalhoDoServidor cabecalho;
    private final FaixaDeKpis kpis = new FaixaDeKpis();
    private final BlocoDeTendencias tendencias = new BlocoDeTendencias();
    private final CaixaDoRegistro registro;
    private final ListaDeAtencao atencao = new ListaDeAtencao();
    private final CaixaDoServidor caixaDeAtencao = new CaixaDoServidor("Precisa de atenção", "alerta");

    // o histórico de longo prazo: o período escolhido, o último dado de cada período e a assinatura do período que está na tela
    private JanelaDeTempo janela = JanelaDeTempo.padrao();
    private final Map<JanelaDeTempo, DadosDeTendencia> historicos = new EnumMap<>(JanelaDeTempo.class);
    private boolean historicoFalhou;
    private transient Inscricao inscricaoDoHistorico;
    private JanelaDeTempo janelaAssinada;
    /** Os testes sem servidor dizem se o agente tem o histórico de longo prazo (com servidor, quem diz é o estado da conexão). */
    private Boolean longoPrazoParaTeste;

    /**
     * @param aoNavegar abre uma aba da página (os indicadores e as linhas de atenção a chamam)
     */
    public PaginaResumo(ServidorLab servidor, Runnable aoAtualizar, Consumer<Aba> aoNavegar) {
        this(servidor, aoAtualizar, aoNavegar, ApoioDoResumo.padrao());
    }

    PaginaResumo(ServidorLab servidor, Runnable aoAtualizar, Consumer<Aba> aoNavegar, ApoioDoResumo apoio) {
        super(servidor, aoAtualizar);
        this.aoNavegar = aoNavegar;
        this.apoio = apoio;
        this.cabecalho = new CabecalhoDoServidor(aoAtualizar == null ? () -> { } : aoAtualizar);
        PainelDoRegistro.Fonte fonte = servidor == null ? null : new PainelDoRegistro.Fonte() {
            @Override
            public Inscricao assinar(OuvinteDeTopico<br.com.labdesktop.registro.ResultadoDoRegistro> ouvinte, boolean repetirUltimo) {
                return servidor.assinarRegistro(ouvinte, repetirUltimo);
            }

            @Override
            public void atualizarAgora() {
                servidor.atualizarRegistroAgora();
            }
        };
        this.registro = new CaixaDoRegistro(fonte, apoio.copiador(), apoio.avisos(), apoio.fuso());
        this.registro.painel().aoVerTudo(() -> apoio.apresentador().mostrar(
            new DialogoRegistro(servidor == null ? "" : servidor.estado().nome(), fonte, apoio.copiador(), apoio.avisos(), apoio.fuso())));
        kpis.ligar(Map.of("seguranca", Aba.SEGURANCA.rotulo(), "containers", Aba.CONTAINERS.rotulo(), "cpu", Aba.PROCESSOS.rotulo(),
            "memoria", Aba.PROCESSOS.rotulo(), "disco", Aba.MAQUINA.rotulo()), this::abrirDoIndicador);
        String lembrada = servidor == null ? null : apoio.memoria().janelaDasTendencias(servidor.id());
        if (lembrada != null) {
            janela = JanelaDeTempo.doCodigo(lembrada).orElse(janela);
            tendencias.marcarJanela(janela);
        }
        tendencias.definirAoMudarJanela(this::aoEscolherJanela);
        tendencias.definirAoAbrirRelatorio(this::abrirRelatorio);
        iniciar();
    }

    @Override
    boolean usaServidor() {
        return true;
    }

    @Override
    boolean usaDetalhe() {
        return true;
    }

    @Override
    boolean usaSeguranca() {
        return true;
    }

    @Override
    boolean preencheAAltura() {
        return true;
    }

    /**
     * Um servidor por SSH em que o agente NÃO ESTÁ INSTALADO: o Resumo segue com o que não depende dele (Segurança e Registro, por SSH) e diz nas Tendências "Instale o agente para ver a
     * tendência"; o aviso de cima leva o botão Instalar agente. Agente instalado mas mudo, ou antigo, continua com o cartão "sem dados" de sempre (a explicação e o "Tentar de novo"
     * valem mais ali). "Este computador" também não: sem o laboratório não há o que mostrar, e o cartão "Preparar este computador" explica.
     */
    @Override
    boolean mostraCorpoSemLeitura(SituacaoDaVisao situacao) {
        EstadoServidor e = estado;
        return e != null && !e.local() && situacao.modo() == SituacaoDaVisao.Modo.AGENTE_NAO_INSTALADO;
    }

    @Override
    JComponent montarTopo() {
        return cabecalho;
    }

    @Override
    JComponent montarConteudo() {
        caixaDeAtencao.linha(atencao, 0);
        JPanel esquerda = new Painel(new GridBagLayout());
        GridBagConstraints e = new GridBagConstraints();
        e.gridx = 0;
        e.gridy = 0;
        e.weightx = 1;
        e.weighty = 0;
        e.fill = GridBagConstraints.HORIZONTAL;
        esquerda.add(tendencias, e);
        e.gridy = 1;
        e.weighty = 1;
        e.fill = GridBagConstraints.BOTH;
        e.insets = new Insets(12, 0, 0, 0);
        esquerda.add(registro, e);

        JPanel colunas = new Painel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        g.gridx = 0;
        g.weightx = 0.69;
        g.insets = new Insets(0, 0, 0, 7);
        colunas.add(esquerda, g);
        g.gridx = 1;
        g.weightx = 0.31;
        g.insets = new Insets(0, 7, 0, 0);
        colunas.add(caixaDeAtencao, g);
        JPanel conteudo = new Painel(new java.awt.BorderLayout(0, 12)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE); // estica com a janela: o Registro ocupa o que sobra
            }
        };
        conteudo.add(kpis, java.awt.BorderLayout.NORTH);
        conteudo.add(colunas, java.awt.BorderLayout.CENTER);
        return conteudo;
    }

    @Override
    void atualizarTopo() {
        cabecalho.atualizar(estado, info, ultimaLeitura, fuso());
    }

    @Override
    void atualizarConteudo() {
        kpis.atualizar(ModeloDeKpis.de(info, seguranca));
        tendencias.mostrar(visaoDasTendencias());
        List<ListaDeAtencao.Item> itens = new ArrayList<>();
        for (ModeloDeAtencao.Atencao a : ModeloDeAtencao.de(info, detalhe, seguranca)) {
            itens.add(new ListaDeAtencao.Item(a.gravidade(), a.texto(), a.destino().rotulo(), () -> aoNavegar.accept(a.destino())));
        }
        atencao.definir(itens);
        caixaDeAtencao.apoio(Rotulo.dica("clique para abrir"));
        caixaDeAtencao.terminar();
    }

    private void abrirDoIndicador(String chave) {
        Aba destino = switch (chave) {
            case "seguranca" -> Aba.SEGURANCA;
            case "containers" -> Aba.CONTAINERS;
            case "cpu", "memoria" -> Aba.PROCESSOS;
            case "disco" -> Aba.MAQUINA;
            default -> null;
        };
        if (destino != null) {
            aoNavegar.accept(destino);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // O histórico: de onde vem cada período
    // ---------------------------------------------------------------------------------------------

    /** O agente tem o histórico de longo prazo? (com servidor, quem diz é o {@code recursos.historico} de {@code /api/versao}.) */
    boolean temHistoricoDeLongoPrazo() {
        if (longoPrazoParaTeste != null) {
            return longoPrazoParaTeste;
        }
        return estado != null && estado.entendeHistorico();
    }

    /** O período que de fato aparece: o escolhido, ou os 10 minutos quando o agente não guarda mais que isso. */
    JanelaDeTempo janelaEfetiva() {
        return temHistoricoDeLongoPrazo() || janela == JanelaDeTempo.DEZ_MINUTOS ? janela : JanelaDeTempo.DEZ_MINUTOS;
    }

    private BlocoDeTendencias.Visao visaoDasTendencias() {
        boolean longo = temHistoricoDeLongoPrazo();
        if (info == null) {
            // o corpo só aparece sem a leitura do agente quando o servidor é SSH sem ele
            return new BlocoDeTendencias.Visao(janela, null, BlocoDeTendencias.Situacao.SEM_AGENTE, false, fuso());
        }
        if (!longo) {
            DadosDeTendencia d = DadosDeTendencia.dosDezMinutos(info.historico(), info.quando() != null ? info.quando() : agora());
            return new BlocoDeTendencias.Visao(JanelaDeTempo.DEZ_MINUTOS, d,
                janela == JanelaDeTempo.DEZ_MINUTOS ? BlocoDeTendencias.Situacao.OK : BlocoDeTendencias.Situacao.AGENTE_SO_DEZ_MINUTOS, false, fuso());
        }
        DadosDeTendencia d = historicos.get(janela);
        BlocoDeTendencias.Situacao s = d == null ? (historicoFalhou ? BlocoDeTendencias.Situacao.FALHOU_SEM_DADO : BlocoDeTendencias.Situacao.CARREGANDO)
            : historicoFalhou ? BlocoDeTendencias.Situacao.FALHOU_COM_DADO : BlocoDeTendencias.Situacao.OK;
        return new BlocoDeTendencias.Visao(janela, d, s, true, fuso());
    }

    private void aoEscolherJanela(JanelaDeTempo nova) {
        this.janela = nova;
        this.historicoFalhou = false;
        if (servidor() != null) {
            apoio.memoria().definirJanelaDasTendencias(servidor().id(), nova.codigo());
        }
        reassinarHistorico();
        tendencias.mostrar(visaoDasTendencias());
        refazerLayout();
    }

    /** Assina o período que está na tela (e só ele), enquanto a aba está visível e o agente tem o histórico; solta o anterior. */
    private void reassinarHistorico() {
        ServidorLab s = servidor();
        JanelaDeTempo quer = s != null && assinada() && temHistoricoDeLongoPrazo() ? janelaEfetiva() : null;
        if (quer == janelaAssinada) {
            return;
        }
        soltarHistorico();
        if (quer != null) {
            JanelaDeTempo j = quer;
            janelaAssinada = j;
            inscricaoDoHistorico = s.assinar(Topico.historico(j), new OuvinteDeTopico<DadosDeTendencia>() {
                @Override
                public void aoAtualizar(DadosDeTendencia valor) {
                    aoReceberHistorico(valor);
                }

                @Override
                public void aoFalhar(ErroApi erro) {
                    if (!(erro instanceof ErroApi.ErroRede)) {
                        aoFalharHistorico();
                    }
                }
            });
        }
    }

    private void soltarHistorico() {
        if (inscricaoDoHistorico != null) {
            inscricaoDoHistorico.close();
            inscricaoDoHistorico = null;
        }
        janelaAssinada = null;
    }

    /** Chegou o histórico de um período (o Sondador, ou o teste). */
    public void aoReceberHistorico(DadosDeTendencia novo) {
        historicos.put(novo.janela(), novo);
        historicoFalhou = false;
        redesenhar();
    }

    private void aoFalharHistorico() {
        historicoFalhou = true;
        redesenhar();
    }

    @Override
    void aoAssinarMais() {
        reassinarHistorico();
    }

    @Override
    void aoSoltarMais() {
        soltarHistorico();
    }

    @Override
    void aoMudarEstadoMais(EstadoServidor novo) {
        reassinarHistorico(); // o agente acabou de dizer (ou deixou de dizer) que tem o histórico
    }

    // ---------------------------------------------------------------------------------------------
    // O relatório
    // ---------------------------------------------------------------------------------------------

    private void abrirRelatorio() {
        JanelaDeTempo inicial = janelaEfetiva();
        boolean longo = temHistoricoDeLongoPrazo();
        DadosDeTendencia ja = longo ? historicos.get(inicial) : DadosDeTendencia.dosDezMinutos(info == null ? null : info.historico(), agora());
        DialogoRelatorio.Fonte fonte = apoio.fonteDoRelatorio() != null ? apoio.fonteDoRelatorio() : janelaPedida -> {
            if (!temHistoricoDeLongoPrazo()) {
                if (janelaPedida != JanelaDeTempo.DEZ_MINUTOS) {
                    throw new IllegalStateException("Este agente só guarda os últimos 10 minutos.");
                }
                return DadosDeTendencia.dosDezMinutos(info == null ? null : info.historico(), Instant.now());
            }
            return servidor().acoes().historico(janelaPedida);
        };
        apoio.apresentador().mostrar(new DialogoRelatorio(fonte, inicial, ja, longo, servidor() == null ? "" : servidor().estado().nome(), apoio.fuso().get(), apoio.relogio(),
            apoio.escolhedor(), apoio.gravador(), apoio.copiador(), apoio.avisos()));
    }

    // ---- para os testes ----

    FaixaDeKpis faixaDeKpis() {
        return kpis;
    }

    BlocoDeTendencias blocoDeTendencias() {
        return tendencias;
    }

    CaixaDoRegistro caixaDoRegistro() {
        return registro;
    }

    ListaDeAtencao listaDeAtencao() {
        return atencao;
    }

    CabecalhoDoServidor cabecalho() {
        return cabecalho;
    }

    /** Os testes sem servidor dizem se o agente tem o histórico de longo prazo; {@code null} volta a perguntar ao estado. */
    void definirHistoricoDeLongoPrazoParaTeste(Boolean tem) {
        this.longoPrazoParaTeste = tem;
        redesenhar();
    }

    JanelaDeTempo janelaEscolhida() {
        return janela;
    }

    boolean historicoAssinado() {
        return inscricaoDoHistorico != null;
    }

    JanelaDeTempo janelaAssinada() {
        return janelaAssinada;
    }

    boolean historicoFalhou() {
        return historicoFalhou;
    }

    /** Como se a memória do app tivesse lembrado este período ao abrir (sem clique). */
    void lembrarJanelaParaTeste(JanelaDeTempo j) {
        this.janela = j;
        tendencias.marcarJanela(j);
        redesenhar();
    }

    /** Escolhe um período como o usuário faria (clica no botão). */
    void escolherJanelaParaTeste(JanelaDeTempo j) {
        tendencias.botaoDaJanela(j).doClick();
    }
}
