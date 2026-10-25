package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.Container;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TabelaPaginada;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Visão geral": UMA tela, sem rolagem (regra dura, com teste: cabe em 1100 x 700 e em 1440 x 860). De cima para baixo:
 * o cabeçalho do projeto (descrição, git, tamanho, "mexido há", endereços públicos, composes e redes em uma linha discreta) com
 * os botões de ação; o alerta de sessão desatualizada, quando houver; a linha de cinco indicadores; e a caixa com a tabela de
 * containers (paginada, com Reiniciar por container). Só usa o detalhe do projeto, que a {@link TelaProjeto} assina.
 */
final class PaginaVisaoGeral extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    /** Linhas de containers por página: o que cabe junto com o resto em 1100 x 700, com o alerta de sessão desatualizada à vista e sem rolagem. */
    static final int CONTAINERS_POR_PAGINA = 5;

    private final Rotulo descricao = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(13f).truncavel();
    private final Rotulo git = new Rotulo().mono(12f).corDoToken(Tokens.TEXTO2);
    private final Rotulo gitSujo = new Rotulo("não commitado").corDoToken(Tokens.ATENCAO).negrito().tamanho(11.5f);
    private final Rotulo meta = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();
    private final JPanel urls = new Painel();
    private final Rotulo discreta = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(11.5f).truncavel();
    private final Rotulo somenteLeitura = new Rotulo("Somente leitura nesta versão: use o painel web para iniciar e parar.")
        .corDoToken(Tokens.TEXTO3).tamanho(11.5f).truncavel();

    private final Botao compose = Botoes.mini("Iniciar", "play");
    private final Botao reiniciar = Botoes.mini("Reiniciar", "atualizar");
    private final Botao abrirClaude = Botoes.miniPrimario("Abrir Claude", "terminal");
    private final Botao retomar = Botoes.mini("Retomar", "play");
    private final Botao verSessao = Botoes.mini("Ver sessão", "terminal");

    private final AlertaDaPagina alerta = new AlertaDaPagina();
    private final Botao reabrir = Botoes.mini("Reabrir", "atualizar");
    private final FaixaDeKpis kpis = new FaixaDeKpis();
    private final CaixaDoServidor caixa = new CaixaDoServidor("Containers", "docker");
    private final Rotulo apoio = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final TabelaPaginada<Container> tabela;
    private transient Inscricao ocupacao;
    private List<String> urlsMostradas;

    PaginaVisaoGeral(ContextoDoProjeto ctx) {
        super(ctx);
        List<Coluna<Container>> colunas = List.of(
            new Coluna<>("Container", 1.5, false, true, Container::nome, null),
            new Coluna<>("Estado", 0.7, false, false, FormatosDoProjeto::estadoDoContainer, null),
            new Coluna<>("CPU", 0.45, true, false, c -> FormatosDoServidor.percentual(c.cpu()), null),
            new Coluna<>("Memória", 0.6, true, false, c -> Formato.fmtBytes(c.mem()), null),
            new Coluna<>("Portas", 0.9, false, false, c -> FormatosDoProjeto.portas(c.portas()), null),
            new Coluna<>("Imagem", 1.4, false, true, Container::imagem, null),
            new Coluna<>("Criado", 0.6, false, false, c -> FormatosDoProjeto.criado(c.criado(), ctx.agora()), null));
        this.tabela = new TabelaPaginada<>(CONTAINERS_POR_PAGINA, colunas);
        iniciar();
    }

    @Override
    Set<String> topicosProprios() {
        return Set.of();
    }

    @Override
    JComponent montarConteudo() {
        urls.setLayout(new BoxLayout(urls, BoxLayout.X_AXIS));
        compose.addActionListener(e -> {
            ModeloDoProjeto m = ctx.modelo();
            if (m == null) {
                return;
            }
            if (m.mostrarParar()) {
                ctx.fluxos.parar(ctx.projeto);
            } else {
                ctx.fluxos.iniciar(ctx.projeto);
            }
        });
        reiniciar.addActionListener(e -> ctx.fluxos.reiniciar(ctx.projeto));
        reiniciar.setToolTipText("Reinicia todos os containers do projeto");
        abrirClaude.addActionListener(e -> ctx.fluxos.abrirSessao(ctx.projeto));
        retomar.addActionListener(e -> ctx.fluxos.retomar(ctx.projeto, ctx.detalhe == null ? null : ctx.detalhe.limite()));
        retomar.setToolTipText("Tira a pausa e reabre o Claude continuando a conversa");
        verSessao.addActionListener(e -> ctx.navegar.accept(Aba.SESSAO));
        verSessao.setToolTipText("Abre a aba Sessão do Claude");
        reabrir.addActionListener(e -> ctx.fluxos.pedirReabrir(ctx.projeto));
        reabrir.setToolTipText("Reabre a sessão continuando a conversa. A tarefa em andamento é interrompida.");
        kpis.nomeAcessivel("Indicadores do projeto");
        kpis.ligar(Map.of("consumo", Aba.CONSUMO.rotulo()), chave -> ctx.navegar.accept(Aba.CONSUMO));

        // linha 1: descrição à esquerda, botões à direita
        JPanel acoes = Caixa.fluxo(java.awt.FlowLayout.RIGHT, 8, compose, reiniciar, abrirClaude, retomar, verSessao);
        JPanel linha1 = new Painel(new BorderLayout(16, 0));
        linha1.add(descricao, BorderLayout.CENTER);
        linha1.add(acoes, BorderLayout.EAST);
        // linha 2: git, tamanho e mexido há, e os endereços públicos
        JPanel linha2 = Caixa.linha(10, git, gitSujo, meta, Caixa.espaco(8), urls, Caixa.mola());
        // linha 3: composes e redes à esquerda; sem as ações ligadas, o aviso de somente leitura à direita
        JPanel linha3 = new Painel(new BorderLayout(16, 0));
        linha3.add(discreta, BorderLayout.CENTER);
        linha3.add(somenteLeitura, BorderLayout.EAST);
        JPanel cabecalho = Caixa.coluna(6, linha1, linha2, linha3);
        cabecalho.setName("cabecalho-do-projeto");

        caixa.apoio(apoio);
        caixa.linha(tabela, 0);
        if (ctx.acoesLiberadas()) {
            tabela.acaoPorLinha("", this::botaoDoContainer);
        }

        JPanel conteudo = new Painel(new BorderLayout(0, 12)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        JPanel topo = Caixa.coluna(10, cabecalho, alerta);
        conteudo.add(topo, BorderLayout.NORTH);
        JPanel meio = new Painel(new BorderLayout(0, 12));
        meio.add(kpis, BorderLayout.NORTH);
        meio.add(caixa, BorderLayout.CENTER);
        conteudo.add(meio, BorderLayout.CENTER);
        return conteudo;
    }

    /** O botão "Reiniciar" de uma linha da tabela: ocupado enquanto o {@code docker restart} roda. */
    private JComponent botaoDoContainer(Container c) {
        Botao b = Botoes.mini("Reiniciar", "atualizar");
        b.setToolTipText("Reinicia o container " + c.nome() + " (docker restart)");
        b.getAccessibleContext().setAccessibleName("Reiniciar o container " + c.nome());
        b.definirOcupado(ctx.fluxos.ocupado(ctx.projeto, FluxosDeProjeto.chaveDoContainer(c.nome())));
        b.addActionListener(e -> ctx.fluxos.reiniciarContainer(ctx.projeto, c.nome()));
        return b;
    }

    @Override
    void aoAparecer() {
        if (ctx.fluxos != null) {
            ocupacao = ctx.fluxos.observar(projeto -> {
                if (projeto.equals(ctx.projeto)) {
                    aplicarOcupacao();
                }
            });
        }
    }

    @Override
    void aoSumir() {
        ocupacao = soltar(ocupacao);
    }

    /** Reaplica o "ocupado" dos botões a partir do {@link FluxosDeProjeto} (e refaz a coluna de Reiniciar por container). */
    void aplicarOcupacao() {
        aplicarOcupacaoDosBotoes();
        tabela.redesenhar();
    }

    private void aplicarOcupacaoDosBotoes() {
        if (ctx.fluxos == null) {
            return;
        }
        Set<String> o = ctx.fluxos.ocupadas(ctx.projeto);
        compose.definirOcupado(o.contains(FluxosDeProjeto.INICIAR) || o.contains(FluxosDeProjeto.PARAR));
        reiniciar.definirOcupado(o.contains(FluxosDeProjeto.REINICIAR));
        abrirClaude.definirOcupado(o.contains(FluxosDeProjeto.ABRIR));
        retomar.definirOcupado(o.contains(FluxosDeProjeto.RETOMAR));
        reabrir.definirOcupado(o.contains(FluxosDeProjeto.REABRIR));
    }

    @Override
    void atualizarConteudo() {
        DetalheProjeto d = ctx.detalhe;
        ModeloDoProjeto m = ctx.modelo();
        String desc = d.descricao() == null ? "" : d.descricao();
        descricao.setText(desc.isEmpty() ? "Sem descrição" : desc);
        descricao.setToolTipText(desc);

        git.setText(m.git() == null ? "sem git" : m.git());
        git.setIcon(Icones.colorido("terminal", 12, Tokens.TEXTO3));
        git.setIconTextGap(5);
        gitSujo.setVisible(m.gitSujo());
        List<String> partes = new ArrayList<>();
        partes.add(d.tamanho() == null ? "tamanho ainda não medido" : Formato.fmtBytes(d.tamanho()));
        partes.add(m.mexido() == null ? "sem registro de alteração" : "mexido " + Formato.fmtHa(m.mexido(), ctx.agora()));
        meta.setText("·  " + String.join("  ·  ", partes));

        mostrarEnderecos(d.urls());
        discreta.setText(linhaDiscreta(d));
        discreta.setToolTipText(linhaDiscreta(d));

        boolean acoes = ctx.acoesLiberadas();
        compose.setVisible(acoes && (m.mostrarParar() || m.mostrarIniciar()));
        compose.setText(m.mostrarParar() ? "Parar" : "Iniciar");
        compose.setIcon(Icones.de(m.mostrarParar() ? "parar" : "play", 14));
        compose.setToolTipText((m.mostrarParar() ? "Para" : "Inicia") + " os containers do projeto");
        reiniciar.setVisible(acoes && m.temCompose());
        abrirClaude.setVisible(acoes && m.oferecerAbrirClaude());
        abrirClaude.setToolTipText("Abre a sessão do Claude neste projeto");
        retomar.setVisible(acoes && m.oferecerRetomar());
        verSessao.setVisible(m.sessaoAberta());
        somenteLeitura.setVisible(!acoes);

        if (m.sessaoDesatualizada()) {
            String falta = m.motivoDaSessaoDesatualizada();
            alerta.mostrar(Gravidade.ATENCAO, "alerta", "A sessão do Claude está desatualizada",
                (falta.isEmpty() ? "" : "Falta: " + falta + ". ") + "Reabrir continua a conversa, mas interrompe a tarefa em andamento.",
                acoes ? new Botao[] {reabrir} : new Botao[0]);
        } else {
            alerta.esconder();
        }

        kpis.atualizar(m.indicadores());
        apoio.setText(m.total() == 0 ? "" : m.rodando() + " de " + m.total() + " rodando");
        tabela.definir(d.containers(), m.temCompose()
            ? "Nenhum container criado ainda. Use Iniciar para subir o projeto."
            : "Este projeto não tem arquivo compose, então não há containers.");
        caixa.terminar();
        aplicarOcupacaoDosBotoes();
    }

    /** Os endereços públicos como links (no máximo 3 na linha; o resto vai em "+n" com a lista na dica). */
    private void mostrarEnderecos(List<String> lista) {
        if (lista.equals(urlsMostradas)) {
            return;
        }
        urlsMostradas = List.copyOf(lista);
        urls.removeAll();
        if (lista.isEmpty()) {
            urls.add(new Rotulo("sem endereço público").corDoToken(Tokens.TEXTO3).tamanho(12f));
        }
        for (int i = 0; i < Math.min(3, lista.size()); i++) {
            String host = lista.get(i);
            Botao link = Botoes.link(host);
            link.setIcon(Icones.de("externo", 12));
            link.setIconTextGap(4);
            link.setToolTipText("Abre https://" + host + " no navegador");
            link.getAccessibleContext().setAccessibleName("Abrir o endereço " + host);
            link.addActionListener(e -> ctx.navegador.abrirHostDeProjeto(host));
            if (i > 0) {
                urls.add(Caixa.espaco(10));
            }
            urls.add(link);
        }
        if (lista.size() > 3) {
            Rotulo mais = new Rotulo("+" + (lista.size() - 3)).corDoToken(Tokens.TEXTO3).tamanho(12f);
            mais.setToolTipText(String.join("\n", lista));
            urls.add(Caixa.espaco(8));
            urls.add(mais);
        }
        urls.revalidate();
        urls.repaint();
    }

    /** "Compose: a.yml, b.yml · Redes: x_default": discreta, para quem precisa e invisível para quem não. */
    static String linhaDiscreta(DetalheProjeto d) {
        List<String> partes = new ArrayList<>();
        if (d.composes().isEmpty()) {
            partes.add("sem arquivo compose");
        } else {
            partes.add("Compose: " + String.join(", ", d.composes().stream().map(DetalheProjeto.Compose::arquivo).toList()));
        }
        if (!d.redes().isEmpty()) {
            partes.add("Redes: " + String.join(", ", d.redes()));
        }
        if (Boolean.FALSE.equals(d.acessoDocker())) {
            partes.add("a sessão do Claude não tem acesso ao Docker deste projeto");
        }
        return String.join("  ·  ", partes);
    }

    // ---- para os testes ----

    FaixaDeKpis faixaDeKpis() {
        return kpis;
    }

    TabelaPaginada<Container> tabelaDeContainers() {
        return tabela;
    }

    AlertaDaPagina alerta() {
        return alerta;
    }

    Botao botaoCompose() {
        return compose;
    }

    Botao botaoReiniciar() {
        return reiniciar;
    }

    Botao botaoAbrirClaude() {
        return abrirClaude;
    }

    Botao botaoRetomar() {
        return retomar;
    }

    Botao botaoVerSessao() {
        return verSessao;
    }

    Botao botaoReabrir() {
        return reabrir;
    }

    JPanel enderecos() {
        return urls;
    }

    String textoDaLinhaDiscreta() {
        return discreta.getText();
    }

    String textoDaMeta() {
        return meta.getText();
    }

    String textoDoGit() {
        return git.getText();
    }

    String textoDaDescricao() {
        return descricao.getText();
    }

    boolean mostraAvisoDeSomenteLeitura() {
        return somenteLeitura.isVisible();
    }
}
