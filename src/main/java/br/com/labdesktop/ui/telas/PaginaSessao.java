package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.modelo.TelaClaude;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.Topico;
import br.com.labdesktop.servidor.VisaoDaTela;
import br.com.labdesktop.ui.Gravidade;
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
 * A aba "Sessão do Claude": em que pé a sessão está (aberta, desde quando, atualizada ou desatualizada, pausada, login
 * pendente com o campo para o código), os botões Abrir, Fechar, Reabrir (com o aviso de que interrompe a tarefa) e Retomar, a
 * TELA atual da sessão em texto somente leitura (a cada 3 s, só com a aba visível e a sessão aberta) e uma caixa para escrever
 * ao Claude com os botões Enter e Esc. O terminal interativo de verdade é de outra etapa: o botão "Terminal (em breve)" fica
 * desabilitado e diz isso.
 *
 * <p>Assina {@code TELA(p)} só enquanto a aba está visível E o detalhe diz que a sessão está aberta; sessão fechada não custa
 * um {@code docker exec} no servidor. A tela cresce com a janela e rola por dentro.
 */
final class PaginaSessao extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    private static final String CARTAO_TELA = "tela";
    private static final String CARTAO_MENSAGEM = "mensagem";

    private final Selo seloDaSituacao = new Selo("", Gravidade.NEUTRO);
    private final Selo seloDeLogin = new Selo("login pendente", Gravidade.ATENCAO, "chave");
    private final Selo seloDeAtualizacao = new Selo("", Gravidade.OK);
    private final Rotulo quando = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f).truncavel();
    private final Botao abrir = Botoes.miniPrimario("Abrir sessão", "terminal");
    private final Botao fechar = Botoes.mini("Fechar", "fechar");
    private final Botao reabrir = Botoes.mini("Reabrir", "atualizar");
    private final Botao retomar = Botoes.mini("Retomar", "play");
    private final Botao terminal = Botoes.mini("Terminal (em breve)", "terminal");
    private final Rotulo somenteLeitura = new Rotulo("Somente leitura nesta versão: abrir, fechar e escrever ao Claude pelo painel web.")
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

    private final JTextField mensagem = Campos.texto(40, "Escreva uma mensagem para o Claude…");
    private final Botao enviar = Botoes.miniPrimario("Enviar", "enviar");
    private final Botao teclaEnter = Botoes.mini("Enter", "avancar");
    private final Botao teclaEsc = Botoes.mini("Esc", "fechar");
    private final JPanel linhaDeMensagem;

    private transient Inscricao inscricaoDaTela;
    private transient Inscricao ocupacao;
    private TelaClaude ultimaTela;
    private boolean recebeuTela;
    private Situacao situacaoAtual = Situacao.FECHADA;
    private boolean loginAtual;

    PaginaSessao(ContextoDoProjeto ctx) {
        super(ctx);
        linhaDeMensagem = Caixa.linha(8, mensagem, enviar, teclaEnter, teclaEsc);
        iniciar();
    }

    @Override
    boolean preencheAAltura() {
        return true;
    }

    @Override
    Set<String> topicosProprios() {
        return inscricaoDaTela != null ? Set.of(Topico.tela(ctx.projeto).chave()) : Set.of();
    }

    @Override
    JComponent montarConteudo() {
        terminal.setEnabled(false);
        terminal.setToolTipText("O terminal interativo do Claude chega na próxima etapa. Por enquanto, leia a tela da sessão aqui "
            + "e escreva mensagens na caixa de baixo.");
        terminal.getAccessibleContext().setAccessibleDescription(terminal.getToolTipText());
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
        estado.add(Caixa.fluxo(java.awt.FlowLayout.RIGHT, 8, abrir, fechar, reabrir, retomar, terminal, somenteLeitura),
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
        JPanel cabecalhoDaTela = new Painel(new BorderLayout(10, 0));
        cabecalhoDaTela.add(Caixa.fluxo(java.awt.FlowLayout.LEFT, 10, tituloDaTela, seloDeAtividade), BorderLayout.WEST);
        cabecalhoDaTela.add(leitura, BorderLayout.EAST);
        JPanel caixaDaTela = new Painel(new BorderLayout(0, 8));
        caixaDaTela.add(cabecalhoDaTela, BorderLayout.NORTH);
        caixaDaTela.add(corpoDaTela, BorderLayout.CENTER);

        JPanel norte = Caixa.coluna(10, estado, faixaDeLogin);
        JPanel sul = Caixa.coluna(6, linhaDeMensagem);
        JPanel conteudo = new Painel(new BorderLayout(0, 10));
        conteudo.add(norte, BorderLayout.NORTH);
        conteudo.add(caixaDaTela, BorderLayout.CENTER);
        conteudo.add(sul, BorderLayout.SOUTH);
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
        if (ctx.detalhe != null) {
            ajustarATela();
        }
    }

    @Override
    void aoSumir() {
        inscricaoDaTela = soltar(inscricaoDaTela);
        ocupacao = soltar(ocupacao);
    }

    /** A sessão está aberta e a aba está na tela: assina a tela (3 s). Senão, solta. */
    private void ajustarATela() {
        boolean quer = situacaoAtual == Situacao.ABERTA && assinada() && ctx.servidor != null;
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
        ModeloDoProjeto m = ctx.modelo();
        Situacao nova = ModeloDaSessao.situacao(m);
        if (nova != Situacao.ABERTA) {
            ultimaTela = null;
            recebeuTela = false;
        }
        situacaoAtual = nova;
        seloDaSituacao.atualizar(nova.rotulo(), nova.gravidade(), nova.icone());
        String pausa = nova == Situacao.PAUSADA_PELO_LIMITE || nova == Situacao.PAUSADA_PELO_FREIO
            ? br.com.labdesktop.ui.TextosDeLimite.texto(m.limite()) : null;
        quando.setText(textoDoQuando(m, pausa));
        quando.setToolTipText(quando.getText());
        boolean aberta = nova == Situacao.ABERTA;
        seloDeAtualizacao.setVisible(aberta && m.detalhe().sessao().info() != null);
        if (seloDeAtualizacao.isVisible()) {
            boolean velha = m.sessaoDesatualizada();
            seloDeAtualizacao.atualizar(velha ? "desatualizada" : "atualizada", velha ? Gravidade.ATENCAO : Gravidade.OK,
                velha ? "alerta" : "ok");
            seloDeAtualizacao.setToolTipText(velha
                ? "A sessão não está na imagem atual" + (m.motivoDaSessaoDesatualizada().isEmpty() ? "" : ". Falta: " + m.motivoDaSessaoDesatualizada())
                : "A sessão está na imagem atual do Claude");
        }

        boolean acoes = ctx.acoesLiberadas();
        abrir.setVisible(acoes && m.oferecerAbrirClaude());
        fechar.setVisible(acoes && aberta);
        reabrir.setVisible(acoes && aberta);
        retomar.setVisible(acoes && m.oferecerRetomar());
        terminal.setVisible(true);
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
        if (assinada()) {
            ajustarATela();
        }
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
        if (ultimaTela != null && situacaoAtual == Situacao.ABERTA) {
            leitura.setText("lida às " + Formato.fmtHora(ctx.agora(), ctx.fuso));
        }
    }

    private void mostrarATela() {
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
