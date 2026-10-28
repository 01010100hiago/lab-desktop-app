package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.modelo.TelaClaude;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.Topico;
import br.com.labdesktop.servidor.VisaoDaTela;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.telas.ModeloDaSessao.Atividade;
import br.com.labdesktop.ui.telas.ModeloDaSessao.Situacao;
import br.com.labdesktop.terminal.ComandoDeTerminal;
import br.com.labdesktop.terminal.EstadoDoTerminal;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.ui.terminal.PainelTerminal;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * A aba "Terminal" do projeto. Abre SOZINHA, sem clique extra, e funciona SEM Claude: o modo Shell é o shell de login do servidor, já na
 * pasta do projeto. O Claude é opcional (regra de produto: um app só, IA opcional): se o servidor tem sessões do Claude
 * ({@link ModeloDoProjeto#temClaude()}), aparece o seletor "Shell | Claude" e a sessão aberta vem selecionada; sem sessão aberta o
 * Shell vem selecionado e o Claude é só um botão do seletor. Sem Claude no servidor nada do Claude aparece: nem seletor, nem selos,
 * nem botões, nem caixa de mensagem.
 *
 * <p><b>Fronteira do Claude</b> (para um corte fácil numa versão pública): tudo o que é só do Claude está nos campos e métodos
 * marcados "Claude" abaixo (estado da sessão, login, tela em texto, caixa de mensagem, o terminal que anexa ao tmux) e em
 * {@link ModeloDaSessao}; o resto da classe é o shell. Mover isto para um pacote próprio custaria abrir a visibilidade de
 * {@code PaginaDoProjeto}, {@code ContextoDoProjeto} e {@code FluxosDeProjeto}, por isso ficou aqui com esta marca (ARQUITETURA 7.8).
 *
 * <p>Modo Claude: em que pé a sessão está (aberta, desde quando, atualizada ou desatualizada, pausada, login pendente com o campo para
 * o código), os botões Abrir, Fechar, Reabrir (com o aviso de que interrompe a tarefa) e Retomar, o terminal do Claude (JediTerm sobre
 * SSH, ligado à sessão tmux, aberto sozinho se a sessão está aberta), a TELA atual da sessão em texto somente leitura (a cada 3 s, só
 * com a aba visível e a sessão aberta) como alternativa e uma caixa para escrever ao Claude com os botões Enter e Esc. "Tela em texto"
 * volta à leitura e "Fechar terminal" solta o canal (a conversa do Claude continua: só o cliente do tmux é solto). Nenhum terminal
 * depende da trava {@code labdesktop.acoes}: o usuário quer usá-los para operar. Se a sessão deixa de existir e depois volta a estar
 * aberta (Retomar, Abrir e Reabrir produzem um container novo), o terminal do Claude reanexa sozinho.
 *
 * <p>Assina {@code TELA(p)} só enquanto a aba está visível, no modo Claude, o detalhe diz que a sessão está aberta E a tela em texto
 * está à vista; sessão fechada, shell ou terminal na frente não custam um {@code docker exec} no servidor. A tela cresce com a janela
 * e rola por dentro.
 */
final class PaginaTerminalDoProjeto extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    private static final String CARTAO_TELA = "tela";
    private static final String CARTAO_MENSAGEM = "mensagem";
    private static final String MODO_TEXTO = "texto";
    private static final String MODO_TERMINAL = "terminal";
    private static final String MODO_SHELL = "shell";
    private static final String ID_SHELL = "shell";
    private static final String ID_CLAUDE = "claude";
    private static final String LEGENDA_DO_SHELL_NA_PASTA = "Shell no servidor, na pasta do projeto";
    private static final String LEGENDA_DO_SHELL_NA_PASTA_PESSOAL = "Shell no servidor, na pasta pessoal (a pasta do projeto não pôde ser usada)";

    /** O que a aba mostra: o shell do projeto ou a sessão do Claude. */
    enum Modo {
        SHELL, CLAUDE
    }

    private final Selo seloDaSituacao = new Selo("", Gravidade.NEUTRO);
    private final Selo seloDeLogin = new Selo("login pendente", Gravidade.ATENCAO, "chave");
    private final Selo seloDeAtualizacao = new Selo("", Gravidade.OK);
    private final Rotulo quando = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f).truncavel();
    private final Botao abrir = Botoes.miniPrimario("Abrir sessão", "terminal");
    private final Botao fechar = Botoes.mini("Fechar", "fechar");
    private final Botao reabrir = Botoes.mini("Reabrir", "atualizar");
    private final Botao retomar = Botoes.mini("Retomar", "play");
    private final Botao terminal = Botoes.miniPrimario("Terminal interativo", "terminal");
    private final Botao telaEmTexto = Botoes.mini("Tela em texto", "texto");
    private final Botao fecharTerminal = Botoes.mini("Fechar terminal", "fechar");
    private final Rotulo somenteLeitura = new Rotulo("Somente leitura nesta versão: abrir, fechar e as caixas de envio pelo painel web. O terminal funciona.")
        .corDoToken(Tokens.TEXTO3).tamanho(11.5f).truncavel();

    private final JPanel faixaDeLogin = new Painel(new BorderLayout(12, 0)).fundo(Tokens.ATENCAO_SUAVE).borda(Tokens.ATENCAO).raio(10);
    private final Rotulo tituloDoLogin = new Rotulo("O login do Claude está pendente").negrito().tamanho(13f);
    private final TextoCorrido ajudaDoLogin = new TextoCorrido("", 560).corDoToken(Tokens.TEXTO2).tamanho(12f);
    private final Botao abrirLogin = Botoes.mini("Abrir página de login", "externo");
    private final JTextField campoDoCodigo = Campos.mono(18, "cole o código aqui");
    private final Botao enviarCodigo = Botoes.miniPrimario("Enviar código", null);

    private final Rotulo tituloDaTela = new Rotulo("Tela atual da sessão").negrito().tamanho(13f);
    private final Selo seloDeAtividade = new Selo("", Gravidade.NEUTRO);
    private final Rotulo leitura = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(11.5f);
    private final AreaMonoespacada area = new AreaMonoespacada("Tela atual da sessão do Claude", true);
    private final JScrollPane rolagemDaTela = AreaMonoespacada.emRolagem(area);
    private final Rotulo mensagemDaTela = new Rotulo().corDoToken(Tokens.LOGS_TEXTO2).tamanho(13f);
    private final CardLayout cartoes = new CardLayout();
    private final JPanel corpoDaTela = new Painel(cartoes);
    private final CardLayout modos = new CardLayout();
    private final JPanel areaPrincipal = new Painel(modos);
    private final Abas seletor = new Abas(true);
    private final Rotulo legendaDoShell = new Rotulo(LEGENDA_DO_SHELL_NA_PASTA).corDoToken(Tokens.TEXTO3).tamanho(12.5f).truncavel();
    private final Painel cartaoDoShell = new Painel(new BorderLayout());
    private final Rotulo mensagemDoShell = new Rotulo().corDoToken(Tokens.LOGS_TEXTO2).tamanho(13f).aoCentro();
    private JPanel blocoDeEstadoDoClaude;
    private JPanel cabecalhoDaTela;
    private JPanel blocoDaMensagemDoClaude;

    private final JTextField mensagem = Campos.texto(40, "Escreva uma mensagem para o Claude…");
    private final Botao enviar = Botoes.miniPrimario("Enviar", "enviar");
    private final Botao teclaEnter = Botoes.mini("Enter", "avancar");
    private final Botao teclaEsc = Botoes.mini("Esc", "fechar");
    private final JPanel linhaDeMensagem;

    private transient Inscricao inscricaoDaTela;
    private transient Inscricao ocupacao;
    /** O terminal do Claude (Claude). */
    private transient PainelTerminal painelDoTerminal;
    /** O shell do projeto. */
    private transient PainelTerminal painelDoShell;
    private Modo modo = Modo.SHELL;
    private boolean modoDefinido;
    private boolean sincronizandoOSeletor;
    /** O usuário fechou o terminal do Claude de propósito: não reabre sozinho enquanto a sessão não fechar e abrir de novo. */
    private boolean terminalDoClaudeFechadoPeloDono;
    private boolean temClaude;
    /** O aviso de "limite de terminais" já foi dado nesta falta de vaga: o resto fica no texto do cartão (reinicia ao conseguir uma vaga). */
    private boolean avisouDoLimiteDoShell;
    private boolean avisouDoLimiteDoClaude;
    /** Quantas vezes a aba devolveu o foco a um terminal que já existia (os testes não têm foco de verdade). */
    private int pedidosDeFoco;
    private boolean terminalAFrente;
    /** Quando o terminal viu a sessão sumir: se o detalhe já dizia aberta e desde quando (só uma sessão NOVA o faz reanexar). */
    private boolean eraAbertaNaQueda;
    private java.time.Instant desdeNaQueda;
    private TelaClaude ultimaTela;
    private boolean recebeuTela;
    private Situacao situacaoAtual = Situacao.FECHADA;
    private boolean loginAtual;

    PaginaTerminalDoProjeto(ContextoDoProjeto ctx) {
        super(ctx);
        linhaDeMensagem = Caixa.linha(8, mensagem, enviar, teclaEnter, teclaEsc);
        iniciar();
    }

    @Override
    boolean preencheAAltura() {
        return true;
    }

    /** O shell é SSH: abre mesmo sem o detalhe do projeto (agente fora, primeira leitura que ainda não veio). */
    @Override
    boolean independeDoDetalhe() {
        return true;
    }

    @Override
    Set<String> topicosProprios() {
        return inscricaoDaTela != null ? Set.of(Topico.tela(ctx.projeto).chave()) : Set.of();
    }

    @Override
    JComponent montarConteudo() {
        seletor.adicionar(ID_SHELL, "Shell");
        seletor.adicionar(ID_CLAUDE, "Claude");
        seletor.aoSelecionar(id -> {
            if (!sincronizandoOSeletor) {
                escolher(ID_CLAUDE.equals(id) ? Modo.CLAUDE : Modo.SHELL);
            }
        });
        seletor.getAccessibleContext().setAccessibleName("Escolha do terminal: shell ou Claude");
        seletor.botao(ID_SHELL).setToolTipText("Um shell do servidor, já na pasta do projeto");
        seletor.botao(ID_CLAUDE).setToolTipText("A sessão do Claude deste projeto");
        mensagemDoShell.setText("O terminal não está disponível: este servidor ainda não tem conexão.");
        cartaoDoShell.fundo(Tokens.LOGS_BG);
        cartaoDoShell.setOpaque(true);
        cartaoDoShell.add(mensagemDoShell, BorderLayout.CENTER);
        terminal.setToolTipText("Abre o terminal do Claude aqui dentro. A tela em texto continua disponível.");
        terminal.getAccessibleContext().setAccessibleDescription(terminal.getToolTipText());
        telaEmTexto.setToolTipText("Volta a mostrar a tela da sessão em texto somente leitura. O terminal continua aberto.");
        fecharTerminal.setToolTipText("Fecha o terminal desta aba. A conversa do Claude continua: só o cliente do tmux é solto.");
        terminal.addActionListener(e -> abrirOuMostrarOTerminal());
        telaEmTexto.addActionListener(e -> mostrarATelaEmTexto());
        fecharTerminal.addActionListener(e -> fecharOTerminalDoClaude());
        abrir.setToolTipText("Abre a sessão do Claude neste projeto");
        fechar.setToolTipText("Fecha a sessão do Claude (para o container dela)");
        reabrir.setToolTipText("Fecha e abre de novo continuando a conversa. A tarefa em andamento é interrompida.");
        retomar.setToolTipText("Tira a pausa e reabre o Claude continuando a conversa");
        teclaEnter.setToolTipText("Aperta Enter na sessão (confirma a opção marcada)");
        teclaEsc.setToolTipText("Aperta Esc na sessão (cancela a pergunta ou interrompe o que o Claude faz)");
        enviar.setToolTipText("Escreve a mensagem na sessão e aperta Enter");
        mensagem.getAccessibleContext().setAccessibleName("Mensagem para o Claude");
        campoDoCodigo.getAccessibleContext().setAccessibleName("Código de login do Claude");

        abrir.addActionListener(e -> ctx.fluxos.abrirSessao(ctx.projeto));
        fechar.addActionListener(e -> ctx.fluxos.fecharSessao(ctx.projeto));
        reabrir.addActionListener(e -> ctx.fluxos.pedirReabrir(ctx.projeto));
        retomar.addActionListener(e -> ctx.fluxos.retomar(ctx.projeto, ctx.detalhe == null ? null : ctx.detalhe.limite()));
        enviar.addActionListener(e -> enviarAMensagem());
        mensagem.addActionListener(e -> enviarAMensagem());
        teclaEnter.addActionListener(e -> ctx.fluxos.enviarTecla(ctx.projeto, "Enter"));
        teclaEsc.addActionListener(e -> ctx.fluxos.enviarTecla(ctx.projeto, "Escape"));
        abrirLogin.addActionListener(e -> {
            String url = ctx.urlDeLogin == null ? null : ctx.urlDeLogin.get();
            if (url != null) {
                ctx.navegador.abrirLogin(url);
            }
        });
        campoDoCodigo.getDocument().addDocumentListener(new br.com.labdesktop.ui.dialogos.SimplesOuvinteDeDocumento(
            () -> enviarCodigo.setEnabled(!campoDoCodigo.getText().isBlank() && ctx.acoesLiberadas())));
        enviarCodigo.setEnabled(false);
        Runnable mandarCodigo = () -> {
            String codigo = campoDoCodigo.getText().strip();
            if (!codigo.isEmpty() && enviarCodigo.isEnabled()) {
                ctx.fluxos.enviarCodigoDeLogin(ctx.projeto, codigo);
                campoDoCodigo.setText("");
            }
        };
        enviarCodigo.addActionListener(e -> mandarCodigo.run());
        campoDoCodigo.addActionListener(e -> mandarCodigo.run());

        // linha do estado: selos e tempo à esquerda, botões à direita
        JPanel estado = new Painel(new BorderLayout(16, 0));
        estado.add(Caixa.fluxo(java.awt.FlowLayout.LEFT, 10, seloDaSituacao, seloDeAtualizacao, seloDeLogin, quando), BorderLayout.CENTER);
        estado.add(Caixa.fluxo(java.awt.FlowLayout.RIGHT, 8, abrir, fechar, reabrir, retomar, somenteLeitura),
            BorderLayout.EAST);

        // faixa de login pendente
        faixaDeLogin.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        Rotulo marca = new Rotulo().icone(Icones.colorido("alerta", 16, Tokens.ATENCAO));
        marca.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        faixaDeLogin.add(marca, BorderLayout.WEST);
        faixaDeLogin.add(Caixa.coluna(6, tituloDoLogin, ajudaDoLogin, Caixa.fluxo(java.awt.FlowLayout.LEFT, 8, abrirLogin, campoDoCodigo, enviarCodigo)),
            BorderLayout.CENTER);
        faixaDeLogin.setVisible(false);

        // a tela da sessão
        rolagemDaTela.setPreferredSize(new Dimension(400, 220));
        rolagemDaTela.setMinimumSize(new Dimension(100, 120));
        JPanel cartaoDaMensagem = new Painel(new java.awt.GridBagLayout()).fundo(Tokens.LOGS_BG).borda(Tokens.LOGS_BORDA);
        cartaoDaMensagem.add(mensagemDaTela);
        corpoDaTela.add(cartaoDaMensagem, CARTAO_MENSAGEM);
        corpoDaTela.add(rolagemDaTela, CARTAO_TELA);
        cartoes.show(corpoDaTela, CARTAO_MENSAGEM);
        areaPrincipal.add(corpoDaTela, MODO_TEXTO);
        modos.show(areaPrincipal, MODO_TEXTO);
        areaPrincipal.add(cartaoDoShell, MODO_SHELL);
        cabecalhoDaTela = new Painel(new BorderLayout(10, 0));
        cabecalhoDaTela.add(Caixa.fluxo(java.awt.FlowLayout.LEFT, 10, tituloDaTela, seloDeAtividade), BorderLayout.WEST);
        cabecalhoDaTela.add(Caixa.fluxo(java.awt.FlowLayout.RIGHT, 8, leitura, telaEmTexto, terminal, fecharTerminal), BorderLayout.EAST);
        JPanel caixaDaTela = new Painel(new BorderLayout(0, 8));
        caixaDaTela.add(cabecalhoDaTela, BorderLayout.NORTH);
        caixaDaTela.add(areaPrincipal, BorderLayout.CENTER);

        blocoDeEstadoDoClaude = Caixa.coluna(10, estado, faixaDeLogin);
        blocoDaMensagemDoClaude = Caixa.coluna(6, linhaDeMensagem);
        JPanel linhaDoSeletor = new Painel(new BorderLayout(12, 0));
        linhaDoSeletor.add(seletor, BorderLayout.WEST);
        linhaDoSeletor.add(legendaDoShell, BorderLayout.CENTER);
        JPanel norte = new Painel(new BorderLayout(0, 10));
        norte.add(linhaDoSeletor, BorderLayout.NORTH);
        norte.add(blocoDeEstadoDoClaude, BorderLayout.CENTER);
        JPanel conteudo = new Painel(new BorderLayout(0, 10));
        conteudo.add(norte, BorderLayout.NORTH);
        conteudo.add(caixaDaTela, BorderLayout.CENTER);
        conteudo.add(blocoDaMensagemDoClaude, BorderLayout.SOUTH);
        aplicarOModo();
        return conteudo;
    }

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------------

    @Override
    void aoAparecer() {
        if (ctx.fluxos != null) {
            ocupacao = ctx.fluxos.observar(projeto -> {
                if (projeto.equals(ctx.projeto)) {
                    aplicarOcupacao();
                }
            });
        }
        // O shell não espera o detalhe do projeto: SSH puro, tem de abrir mesmo com o agente fora do ar.
        decidirOModoInicial();
        atualizarOSeletor();
        aplicarOModo();
        ajustarATela();
        abrirSozinhaSePreciso();
        focarOQueEstaNaFrente(); // voltar para a aba devolve o foco ao terminal que já estava aberto
    }

    /**
     * O modo em que a aba abre: com a sessão do Claude aberta ela vem selecionada; senão o shell. Acompanha o que o servidor diz até
     * a aba aparecer pela primeira vez (ou o usuário escolher): a sessão pode ter aberto ou fechado desde a primeira leitura, e "já
     * aberta ao entrar" vale para o que está aberto NA HORA de entrar.
     */
    private void decidirOModoInicial() {
        if (modoDefinido) {
            return;
        }
        modo = temClaude && situacaoAtual == Situacao.ABERTA ? Modo.CLAUDE : Modo.SHELL;
        modoDefinido = assinada();
    }

    @Override
    void aoSumir() {
        inscricaoDaTela = soltar(inscricaoDaTela);
        ocupacao = soltar(ocupacao);
    }

    /** A sessão está aberta e a aba está na tela: assina a tela (3 s). Senão, solta. */
    private void ajustarATela() {
        boolean quer = modo == Modo.CLAUDE && situacaoAtual == Situacao.ABERTA && assinada() && ctx.servidor != null && !terminalAFrente;
        if (quer && inscricaoDaTela == null) {
            inscricaoDaTela = ctx.servidor.assinar(Topico.tela(ctx.projeto), new OuvinteDeTopico<VisaoDaTela>() {
                @Override
                public void aoAtualizar(VisaoDaTela v) {
                    receberTela(v.tela());
                }

                @Override
                public void aoConfirmar() {
                    confirmarATela();
                }
            });
        } else if (!quer && inscricaoDaTela != null) {
            inscricaoDaTela = soltar(inscricaoDaTela);
        }
    }

    @Override
    void atualizarConteudo() {
        ModeloDoProjeto m = ctx.modelo(); // null enquanto o detalhe não veio: só o shell
        Situacao nova = m == null ? Situacao.FECHADA : ModeloDaSessao.situacao(m);
        if (nova != Situacao.ABERTA) {
            ultimaTela = null;
            recebeuTela = false;
            terminalDoClaudeFechadoPeloDono = false; // a próxima sessão aberta volta a abrir o terminal sozinha
        }
        situacaoAtual = nova;
        temClaude = ctx.temClaude();
        decidirOModoInicial();
        if (!temClaude && modo == Modo.CLAUDE) {
            modo = Modo.SHELL;
        }
        atualizarOSeletor();
        boolean aberta = nova == Situacao.ABERTA;
        boolean acoes = ctx.acoesLiberadas();
        if (m != null) {
            atualizarOEstadoDoClaude(m, nova, aberta, acoes);
        } else {
            abrir.setVisible(false);
            fechar.setVisible(false);
            reabrir.setVisible(false);
            retomar.setVisible(false);
            seloDeAtualizacao.setVisible(false);
        }
        atualizarBotoesDoTerminal(aberta);
        somenteLeitura.setVisible(!acoes);
        linhaDeMensagem.setVisible(true);
        mensagem.setEnabled(aberta && acoes);
        enviar.setEnabled(aberta && acoes);
        teclaEnter.setEnabled(aberta && acoes);
        teclaEsc.setEnabled(aberta && acoes);
        mensagem.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT,
            !acoes ? "Somente leitura nesta versão" : aberta ? "Escreva uma mensagem para o Claude…" : "Abra a sessão para escrever ao Claude");
        aplicarOcupacao();
        mostrarLoginSeHouver();
        mostrarATela();
        if (m != null) {
            reanexarSeASessaoVoltou(m);
        }
        aplicarOModo();
        if (assinada()) {
            ajustarATela();
            abrirSozinhaSePreciso();
        }
    }

    /** Os selos, o "desde quando" e os botões da sessão do Claude (só existem com o detalhe). */
    private void atualizarOEstadoDoClaude(ModeloDoProjeto m, Situacao nova, boolean aberta, boolean acoes) {
        seloDaSituacao.atualizar(nova.rotulo(), nova.gravidade(), nova.icone());
        String pausa = nova == Situacao.PAUSADA_PELO_LIMITE || nova == Situacao.PAUSADA_PELO_FREIO
            ? br.com.labdesktop.ui.TextosDeLimite.texto(m.limite()) : null;
        quando.setText(textoDoQuando(m, pausa));
        quando.setToolTipText(quando.getText());
        seloDeAtualizacao.setVisible(aberta && m.detalhe().sessao() != null && m.detalhe().sessao().info() != null);
        if (seloDeAtualizacao.isVisible()) {
            boolean velha = m.sessaoDesatualizada();
            seloDeAtualizacao.atualizar(velha ? "desatualizada" : "atualizada", velha ? Gravidade.ATENCAO : Gravidade.OK,
                velha ? "alerta" : "ok");
            seloDeAtualizacao.setToolTipText(velha
                ? "A sessão não está na imagem atual" + (m.motivoDaSessaoDesatualizada().isEmpty() ? "" : ". Falta: " + m.motivoDaSessaoDesatualizada())
                : "A sessão está na imagem atual do Claude");
        }
        abrir.setVisible(acoes && m.oferecerAbrirClaude());
        fechar.setVisible(acoes && aberta);
        reabrir.setVisible(acoes && aberta);
        retomar.setVisible(acoes && m.oferecerRetomar());
    }

    private String textoDoQuando(ModeloDoProjeto m, String pausa) {
        return switch (situacaoAtual) {
            case ABERTA -> m.sessaoDesde() == null ? "aberta agora há pouco"
                : "aberta desde " + FormatosDoServidor.desde(m.sessaoDesde(), ctx.agora(), ctx.fuso);
            case FECHADA -> "Nenhuma sessão do Claude aberta neste projeto.";
            case PAUSADA_PELO_LIMITE, PAUSADA_PELO_FREIO -> pausa + ". A sessão só volta com Retomar.";
        };
    }

    /** Aplica o "ocupado" dos botões a partir do {@link FluxosDeProjeto}. */
    void aplicarOcupacao() {
        if (ctx.fluxos == null) {
            return;
        }
        Set<String> o = ctx.fluxos.ocupadas(ctx.projeto);
        abrir.definirOcupado(o.contains(FluxosDeProjeto.ABRIR));
        fechar.definirOcupado(o.contains(FluxosDeProjeto.FECHAR));
        reabrir.definirOcupado(o.contains(FluxosDeProjeto.REABRIR));
        retomar.definirOcupado(o.contains(FluxosDeProjeto.RETOMAR));
        enviar.definirOcupado(o.contains(FluxosDeProjeto.MENSAGEM));
        teclaEnter.definirOcupado(o.contains(FluxosDeProjeto.PREFIXO_DE_TECLA + "Enter"));
        teclaEsc.definirOcupado(o.contains(FluxosDeProjeto.PREFIXO_DE_TECLA + "Escape"));
        enviarCodigo.definirOcupado(o.contains(FluxosDeProjeto.CODIGO));
    }

    // ---------------------------------------------------------------------------------------------
    // Tela
    // ---------------------------------------------------------------------------------------------

    /** Recebe a tela (na EDT). {@code null}: o agente diz que a sessão não está aberta. Público para os testes. */
    void receberTela(TelaClaude tela) {
        this.ultimaTela = tela;
        this.recebeuTela = true;
        mostrarLoginSeHouver();
        mostrarATela();
    }

    /** A tela foi lida de novo e não mudou: a hora da leitura anda (a tela parada continua sendo a de agora). */
    void confirmarATela() {
        if (ultimaTela != null && situacaoAtual == Situacao.ABERTA && !terminalAFrente) {
            leitura.setText("lida às " + Formato.fmtHora(ctx.agora(), ctx.fuso));
        }
    }

    private void mostrarATela() {
        if (terminalAFrente) {
            return; // o terminal está na frente: o cabeçalho é dele, e a tela em texto reaparece (atualizada) ao voltar
        }
        if (situacaoAtual != Situacao.ABERTA) {
            seloDeAtividade.setVisible(false);
            leitura.setText("");
            mostrarMensagemDaTela(switch (situacaoAtual) {
                case PAUSADA_PELO_LIMITE, PAUSADA_PELO_FREIO ->
                    "A sessão está pausada: não há tela para ver. Use Retomar para voltar.";
                default -> "A sessão está fechada, então não há tela para ver. Use Abrir sessão para começar.";
            });
            return;
        }
        if (!recebeuTela) {
            seloDeAtividade.setVisible(false);
            mostrarMensagemDaTela("Lendo a tela da sessão…");
            return;
        }
        if (ultimaTela == null) {
            seloDeAtividade.setVisible(false);
            mostrarMensagemDaTela("O servidor diz que a sessão não está aberta. Confira a aba Visão geral.");
            return;
        }
        Atividade a = ModeloDaSessao.atividade(ultimaTela);
        seloDeAtividade.setVisible(true);
        seloDeAtividade.atualizar(a.rotulo(), a.gravidade(), a.icone());
        seloDeAtividade.setToolTipText(descricaoDaAtividade(ultimaTela, a));
        leitura.setText("lida às " + Formato.fmtHora(ctx.agora(), ctx.fuso));
        String texto = ultimaTela.tela() == null ? "" : ultimaTela.tela();
        if (texto.isBlank()) {
            mostrarMensagemDaTela("A tela da sessão está vazia agora.");
            return;
        }
        if (!texto.equals(area.getText())) {
            area.setText(texto);
        }
        cartoes.show(corpoDaTela, CARTAO_TELA);
        SwingUtilities.invokeLater(() -> {
            var barra = rolagemDaTela.getVerticalScrollBar();
            barra.setValue(barra.getMaximum()); // a parte que importa de um terminal é o fim
        });
    }

    private static String descricaoDaAtividade(TelaClaude t, Atividade a) {
        return switch (a) {
            case PERGUNTA_PENDENTE -> "O Claude fez uma pergunta: " + (t.pergunta().titulo() == null ? "" : t.pergunta().titulo());
            case EXECUTANDO -> "Executando: " + t.executando();
            case ESPERANDO_VOCE -> "O Claude terminou e espera a sua mensagem";
            case OCUPADO -> "O Claude está ocupado";
        };
    }

    private void mostrarMensagemDaTela(String texto) {
        mensagemDaTela.setText(texto);
        area.setText("");
        cartoes.show(corpoDaTela, CARTAO_MENSAGEM);
    }

    // ---------------------------------------------------------------------------------------------
    // Modo: shell ou Claude
    // ---------------------------------------------------------------------------------------------

    /** Passa a aba para o modo Claude (se o servidor tem Claude). Usado pelo botão "Ver sessão do Claude" da Visão geral. */
    void escolherOClaude() {
        escolher(Modo.CLAUDE);
    }

    private void escolher(Modo novo) {
        modoDefinido = true; // a escolha do usuário vale
        modo = novo == Modo.CLAUDE && !temClaude ? Modo.SHELL : novo;
        atualizarOSeletor();
        aplicarOModo();
        if (assinada()) {
            ajustarATela();
            abrirSozinhaSePreciso();
            focarOQueEstaNaFrente(); // trocar Shell/Claude leva o foco ao terminal que ficou à vista
        }
    }

    /** O seletor Shell | Claude só existe com Claude no servidor; a escolhida acompanha o modo. */
    private void atualizarOSeletor() {
        seletor.setVisible(temClaude);
        sincronizandoOSeletor = true;
        try {
            seletor.selecionar(modo == Modo.CLAUDE ? ID_CLAUDE : ID_SHELL);
        } finally {
            sincronizandoOSeletor = false;
        }
    }

    /** Mostra o que é do modo e esconde o resto: o Claude tem o estado, o login, o cabeçalho da tela e a caixa de mensagem. */
    private void aplicarOModo() {
        boolean claude = modo == Modo.CLAUDE;
        legendaDoShell.setVisible(!claude);
        if (blocoDeEstadoDoClaude != null) {
            blocoDeEstadoDoClaude.setVisible(claude);
            cabecalhoDaTela.setVisible(claude);
            blocoDaMensagemDoClaude.setVisible(claude);
        }
        modos.show(areaPrincipal, claude ? (terminalAFrente ? MODO_TERMINAL : MODO_TEXTO) : MODO_SHELL);
        areaPrincipal.revalidate();
        areaPrincipal.repaint();
        if (claude) {
            mostrarLoginSeHouver();
            mostrarATela();
        }
    }

    /**
     * "Já aberta ao entrar": com a aba na tela e o detalhe lido, abre sozinha o terminal do modo em que ela está (o shell, ou o do Claude
     * se a sessão está aberta e o usuário não o fechou). Chamada de novo a cada leitura do detalhe: só faz algo se falta o terminal do modo.
     */
    private void abrirSozinhaSePreciso() {
        if (!assinada() || ctx.terminal == null) {
            return;
        }
        if (modo == Modo.SHELL) {
            boolean novo = painelDoShell == null;
            garantirOShell();
            if (novo && painelDoShell != null) {
                focarOShellDepois(painelDoShell);
            }
        } else if (painelDoTerminal == null && situacaoAtual == Situacao.ABERTA && !terminalDoClaudeFechadoPeloDono) {
            abrirOTerminalDoClaude();
        }
    }

    /** Dá o foco ao shell recém-aberto (digitar já vale), se ele ainda é o que está na frente. */
    private void focarOShellDepois(PainelTerminal p) {
        SwingUtilities.invokeLater(() -> {
            if (assinada() && p == painelDoShell && modo == Modo.SHELL) {
                p.focar();
            }
        });
    }

    /**
     * Dá o foco ao terminal que está à vista agora, se já existe um (o shell no modo Shell, o do Claude no modo Claude com ele na
     * frente): voltar para a aba ou trocar o seletor não pode deixar o foco num botão, com as teclas indo para lugar nenhum.
     */
    private void focarOQueEstaNaFrente() {
        PainelTerminal p = modo == Modo.SHELL ? painelDoShell : terminalAFrente ? painelDoTerminal : null;
        if (p == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            PainelTerminal atual = modo == Modo.SHELL ? painelDoShell : terminalAFrente ? painelDoTerminal : null;
            if (assinada() && atual == p) {
                pedidosDeFoco++;
                p.focar();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Terminal do shell
    // ---------------------------------------------------------------------------------------------

    /** Abre o shell do projeto se ainda não há um (e se couber no limite do servidor). */
    private void garantirOShell() {
        if (painelDoShell != null || ctx.terminal == null) {
            return;
        }
        if (!ctx.terminal.limite().reservar()) {
            mensagemDoShell.setText(LimiteDeAbas.mensagemDoLimite());
            if (!avisouDoLimiteDoShell && ctx.avisos != null) {
                avisouDoLimiteDoShell = true; // uma vez só por falta de vaga: o texto do cartão diz o resto
                ctx.avisos.atencao(LimiteDeAbas.mensagemDoLimite());
            }
            return;
        }
        avisouDoLimiteDoShell = false;
        String pasta = ctx.pastaDoProjeto.get();
        boolean naPasta = ComandoDeTerminal.ShellDoProjeto.valida(pasta);
        ComandoDeTerminal comando = naPasta ? new ComandoDeTerminal.ShellDoProjeto(pasta) : ComandoDeTerminal.ShellDoServidor.INSTANCIA;
        legendaDoShell.setText(naPasta ? LEGENDA_DO_SHELL_NA_PASTA : LEGENDA_DO_SHELL_NA_PASTA_PESSOAL);
        painelDoShell = new PainelTerminal(ctx.terminal, comando, new PainelTerminal.Saida() {
            @Override
            public void aoMudarTitulo(String titulo) { }

            @Override
            public void aoMudarEstado(EstadoDoTerminal estado) { }
        });
        cartaoDoShell.removeAll();
        cartaoDoShell.add(painelDoShell, BorderLayout.CENTER);
        cartaoDoShell.revalidate();
        cartaoDoShell.repaint();
    }

    private void encerrarOShell() {
        PainelTerminal p = painelDoShell;
        if (p == null) {
            return;
        }
        painelDoShell = null;
        p.descartar();
        cartaoDoShell.removeAll();
        mensagemDoShell.setText("O terminal foi fechado.");
        cartaoDoShell.add(mensagemDoShell, BorderLayout.CENTER);
        if (ctx.terminal != null) {
            ctx.terminal.limite().liberar();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Terminal do Claude
    // ---------------------------------------------------------------------------------------------

    /** O botão "Terminal" (Claude): abre o terminal da sessão (se ainda não há um) e o põe na frente da tela em texto. */
    private void abrirOuMostrarOTerminal() {
        terminalDoClaudeFechadoPeloDono = false;
        avisouDoLimiteDoClaude = false; // foi um clique do usuário: o aviso, se faltar vaga, é a resposta dele
        abrirOTerminalDoClaude();
    }

    private void abrirOTerminalDoClaude() {
        if (ctx.terminal == null) {
            return;
        }
        if (painelDoTerminal == null) {
            ComandoDeTerminal.SessaoDoClaude comando;
            try {
                comando = new ComandoDeTerminal.SessaoDoClaude(ctx.projeto);
            } catch (IllegalArgumentException e) {
                if (ctx.avisos != null) {
                    ctx.avisos.atencao("Não abri o terminal: o nome do projeto não é válido para o terminal.");
                }
                return;
            }
            if (!ctx.terminal.limite().reservar()) {
                if (!avisouDoLimiteDoClaude && ctx.avisos != null) {
                    avisouDoLimiteDoClaude = true; // uma vez só por falta de vaga, como no shell
                    ctx.avisos.atencao(LimiteDeAbas.mensagemDoLimite());
                }
                return;
            }
            avisouDoLimiteDoClaude = false;
            painelDoTerminal = new PainelTerminal(ctx.terminal, comando, new PainelTerminal.Saida() {
                @Override
                public void aoMudarTitulo(String titulo) { }

                @Override
                public void aoMudarEstado(EstadoDoTerminal estado) {
                    aoMudarOEstadoDoTerminal(estado);
                }
            });
            areaPrincipal.add(painelDoTerminal, MODO_TERMINAL);
        }
        terminalAFrente = true;
        modos.show(areaPrincipal, modo == Modo.CLAUDE ? MODO_TERMINAL : MODO_SHELL);
        tituloDaTela.setText("Terminal da sessão");
        seloDeAtividade.setVisible(false);
        leitura.setText("");
        ajustarATela(); // com o terminal na frente, a tela em texto não consulta o servidor
        atualizarBotoesDoTerminal(situacaoAtual == Situacao.ABERTA);
        areaPrincipal.revalidate();
        areaPrincipal.repaint();
        PainelTerminal p = painelDoTerminal;
        SwingUtilities.invokeLater(() -> {
            if (terminalAFrente && painelDoTerminal == p) {
                p.focar();
            }
        });
    }

    /** O botão "Tela em texto": volta à leitura somente leitura; o terminal continua aberto por trás. */
    private void mostrarATelaEmTexto() {
        terminalAFrente = false;
        modos.show(areaPrincipal, modo == Modo.CLAUDE ? MODO_TEXTO : MODO_SHELL);
        tituloDaTela.setText("Tela atual da sessão");
        mostrarATela();
        ajustarATela();
        atualizarBotoesDoTerminal(situacaoAtual == Situacao.ABERTA);
        areaPrincipal.revalidate();
        areaPrincipal.repaint();
    }

    /** O botão "Fechar terminal" (Claude): o usuário fechou de propósito, então a aba não o reabre sozinha. */
    private void fecharOTerminalDoClaude() {
        terminalDoClaudeFechadoPeloDono = true;
        encerrarTerminal();
    }

    /** Fecha o shell e o terminal do Claude e devolve as vagas: sair da página do projeto ou bloquear o cofre. */
    void encerrarTodos() {
        encerrarOShell();
        encerrarTerminal();
    }

    /**
     * Fecha o terminal do Claude (o canal e o widget) e devolve a vaga ao limite do servidor. A conversa do Claude continua: só o
     * cliente do tmux é solto.
     */
    void encerrarTerminal() {
        PainelTerminal p = painelDoTerminal;
        if (p == null) {
            return;
        }
        painelDoTerminal = null;
        p.descartar();
        areaPrincipal.remove(p);
        if (ctx.terminal != null) {
            ctx.terminal.limite().liberar();
        }
        mostrarATelaEmTexto();
    }

    private void atualizarBotoesDoTerminal(boolean aberta) {
        boolean disponivel = ctx.terminal != null;
        boolean existe = painelDoTerminal != null;
        terminal.setVisible(!terminalAFrente);
        terminal.setEnabled(disponivel && (aberta || existe));
        terminal.setToolTipText(!disponivel
            ? "O terminal não está disponível: este servidor ainda não tem conexão."
            : !aberta && !existe ? "Abra a sessão do Claude para usar o terminal."
            : existe ? "Volta ao terminal que já está aberto nesta aba."
            : "Abre o terminal do Claude aqui dentro. A tela em texto continua disponível.");
        telaEmTexto.setVisible(terminalAFrente);
        fecharTerminal.setVisible(existe);
    }

    private void aoMudarOEstadoDoTerminal(EstadoDoTerminal estado) {
        if (estado.tipo() == EstadoDoTerminal.Tipo.SESSAO_FECHADA) {
            ModeloDoProjeto m = ctx.modelo();
            eraAbertaNaQueda = situacaoAtual == Situacao.ABERTA;
            desdeNaQueda = m == null ? null : m.sessaoDesde();
        }
    }

    /**
     * A sessão tinha sumido e o detalhe agora diz aberta DE NOVO (estava fechada ou pausada, ou é uma sessão com outro "desde"):
     * Retomar, Abrir e Reabrir produzem um container novo, e o terminal anexa nele sem esperar o usuário. Uma sessão que o detalhe
     * já dava como aberta quando o terminal falhou NÃO reanexa em laço: o tmux dela pode simplesmente não existir.
     */
    private void reanexarSeASessaoVoltou(ModeloDoProjeto m) {
        PainelTerminal p = painelDoTerminal;
        if (p == null || p.estadoMostrado().tipo() != EstadoDoTerminal.Tipo.SESSAO_FECHADA) {
            return;
        }
        if (situacaoAtual != Situacao.ABERTA) {
            eraAbertaNaQueda = false; // o servidor também viu a sessão fechar: a próxima vez que disser aberta é uma sessão nova
            return;
        }
        java.time.Instant desde = m.sessaoDesde();
        if (!eraAbertaNaQueda || !java.util.Objects.equals(desde, desdeNaQueda)) {
            eraAbertaNaQueda = true;
            desdeNaQueda = desde;
            p.sessao().aoSessaoAberta();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Login pendente
    // ---------------------------------------------------------------------------------------------

    private void mostrarLoginSeHouver() {
        String url = ctx.urlDeLogin == null ? null : ctx.urlDeLogin.get();
        boolean pendente = situacaoAtual == Situacao.ABERTA && ModeloDaSessao.loginPendente(ultimaTela, url);
        loginAtual = pendente;
        seloDeLogin.setVisible(pendente);
        faixaDeLogin.setVisible(pendente);
        if (!pendente) {
            return;
        }
        boolean acoes = ctx.acoesLiberadas();
        abrirLogin.setVisible(acoes && url != null);
        campoDoCodigo.setVisible(acoes);
        enviarCodigo.setVisible(acoes);
        ajudaDoLogin.setText(acoes
            ? (url != null ? "Abra a página, entre na conta e copie o código; depois cole aqui e o app envia para a sessão."
                : "A sessão espera o código de login. Cole aqui o código que a página de login mostrou.")
            : "Conclua o login pelo painel web: enviar o código de dentro do app é uma ação que esta versão não liga.");
    }

    // ---------------------------------------------------------------------------------------------
    // Mensagem
    // ---------------------------------------------------------------------------------------------

    private void enviarAMensagem() {
        String texto = mensagem.getText().strip();
        if (texto.isEmpty() || !enviar.isEnabled()) {
            return;
        }
        if (texto.length() > ClienteApi.TEXTO_MAXIMO_DA_MENSAGEM) {
            ctx.avisos.atencao("A mensagem passa de " + ClienteApi.TEXTO_MAXIMO_DA_MENSAGEM
                + " caracteres, o máximo que o Claude recebe de uma vez. Divida em duas.");
            return;
        }
        ctx.fluxos.enviarMensagem(ctx.projeto, texto, () -> mensagem.setText(""));
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    Selo seloDaSituacao() {
        return seloDaSituacao;
    }

    Selo seloDeLogin() {
        return seloDeLogin;
    }

    Selo seloDeAtualizacao() {
        return seloDeAtualizacao;
    }

    Selo seloDeAtividade() {
        return seloDeAtividade;
    }

    String textoDoQuando() {
        return quando.getText();
    }

    Botao botaoAbrir() {
        return abrir;
    }

    Botao botaoFechar() {
        return fechar;
    }

    Botao botaoReabrir() {
        return reabrir;
    }

    Botao botaoRetomar() {
        return retomar;
    }

    Botao botaoTerminal() {
        return terminal;
    }

    Botao botaoTelaEmTexto() {
        return telaEmTexto;
    }

    Botao botaoFecharTerminal() {
        return fecharTerminal;
    }

    /** O terminal do Claude aberto nesta aba, ou {@code null}. */
    PainelTerminal painelDoTerminal() {
        return painelDoTerminal;
    }

    int pedidosDeFoco() {
        return pedidosDeFoco;
    }

    String legendaDoShell() {
        return legendaDoShell.getText();
    }

    String textoDoShellSemTerminal() {
        return mensagemDoShell.getText();
    }

    /** O shell do projeto aberto nesta aba, ou {@code null}. */
    PainelTerminal painelDoShell() {
        return painelDoShell;
    }

    Modo modo() {
        return modo;
    }

    Abas seletor() {
        return seletor;
    }

    boolean terminalAFrente() {
        return terminalAFrente;
    }

    String tituloDaAreaPrincipal() {
        return tituloDaTela.getText();
    }

    Botao botaoEnviar() {
        return enviar;
    }

    Botao botaoEnter() {
        return teclaEnter;
    }

    Botao botaoEsc() {
        return teclaEsc;
    }

    JTextField campoDaMensagem() {
        return mensagem;
    }

    JTextField campoDoCodigo() {
        return campoDoCodigo;
    }

    Botao botaoEnviarCodigo() {
        return enviarCodigo;
    }

    Botao botaoAbrirLogin() {
        return abrirLogin;
    }

    boolean loginPendente() {
        return loginAtual;
    }

    JPanel faixaDeLogin() {
        return faixaDeLogin;
    }

    String textoDaTela() {
        return area.getText();
    }

    String textoDaMensagemDaTela() {
        return mensagemDaTela.getText();
    }

    boolean mostraATela() {
        return rolagemDaTela.isVisible() && cartoes != null && corpoDaTela.getComponent(0).isVisible();
    }

    boolean telaAssinada() {
        return inscricaoDaTela != null;
    }

    boolean linhaDeMensagemVisivel() {
        return linhaDeMensagem.isVisible();
    }

    boolean somenteLeituraVisivel() {
        return somenteLeitura.isVisible();
    }

    JScrollPane rolagemDaTela() {
        return rolagemDaTela;
    }
}
