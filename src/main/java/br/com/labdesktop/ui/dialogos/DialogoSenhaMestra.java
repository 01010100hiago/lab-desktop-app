package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cofre.AtrasoDeSenha;
import br.com.labdesktop.cofre.CofreCorrompidoException;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.SenhaIncorretaException;
import br.com.labdesktop.seguranca.ForcaDeSenha;
import br.com.labdesktop.seguranca.ForcaDeSenha.Avaliacao;
import br.com.labdesktop.seguranca.ForcaDeSenha.Nivel;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Fundo;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.Timer;

/**
 * Criar ou destravar o cofre (ARQUITETURA 4.4, passo 5, e 5.4): senha-mestra aprovada pela régua de
 * {@link ForcaDeSenha} (16 caracteres ou uma frase de 4 palavras, e fora das listas de senhas comuns; a régua é mostrada
 * com TEXTO, nunca só cor), aviso claro de que NÃO há recuperação, atraso
 * crescente depois de senha errada ({@link AtrasoDeSenha}) e o Argon2id fora da EDT, com indicador de progresso.
 *
 * <p>A senha vive só em {@code char[]}: vem do {@code JPasswordField}, é zerada logo depois de entregue ao
 * {@link Abridor} (que copia o que precisar) e o campo é limpo. Nenhuma {@code String} com a senha.
 */
public final class DialogoSenhaMestra extends Dialogo {

    /** Quem abre ou cria o cofre numa thread própria (o Argon2id leva cerca de 1 s e 256 MiB). */
    public interface Abridor {
        /** Cria o cofre. Copia a senha: o chamador pode zerar a dele em seguida. */
        CompletableFuture<CofreDeChaves> criar(char[] senha);

        /** Abre o cofre. Copia a senha: o chamador pode zerar a dele em seguida. */
        CompletableFuture<CofreDeChaves> abrir(char[] senha);

        /** O de produção: {@code criarEmThreadPropria} e {@code abrirEmThreadPropria} da pasta de dados. */
        static Abridor doDisco(CaminhosApp caminhos) {
            return new Abridor() {
                @Override
                public CompletableFuture<CofreDeChaves> criar(char[] senha) {
                    return CofreDeChaves.criarEmThreadPropria(caminhos, senha);
                }

                @Override
                public CompletableFuture<CofreDeChaves> abrir(char[] senha) {
                    return CofreDeChaves.abrirEmThreadPropria(caminhos, senha);
                }
            };
        }
    }

    private final boolean criar;
    private final Abridor abridor;
    private final AtrasoDeSenha atraso;
    private final Supplier<Instant> relogio;
    private final Consumer<Optional<CofreDeChaves>> aoDecidir;

    private final JPasswordField senha = Campos.senha(26);
    private final JPasswordField confirmacao = Campos.senha(26);
    private final Rotulo erro = erro();
    private final Rotulo forcaTexto = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final BarraDeForca forcaBarra = new BarraDeForca();
    private final JProgressBar progresso = new JProgressBar();
    private final Rotulo progressoTexto = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao principal;
    private final Botao sair = Botoes.fantasma("Sair do app", null);
    private final Timer atrasoVisual;
    private boolean ocupado;
    private boolean decidiu;

    /**
     * @param criar     {@code true}: primeira vez (cria o cofre, com confirmação e régua de força); {@code false}: destrava
     * @param aoDecidir recebe o cofre aberto, ou vazio se o usuário escolheu sair; chamado uma vez
     */
    public DialogoSenhaMestra(boolean criar, Abridor abridor, AtrasoDeSenha atraso, Supplier<Instant> relogio,
                              Consumer<Optional<CofreDeChaves>> aoDecidir) {
        super(criar ? "Criar o cofre" : "Desbloquear o cofre");
        this.criar = criar;
        this.abridor = abridor;
        this.atraso = atraso;
        this.relogio = relogio;
        this.aoDecidir = aoDecidir;
        this.principal = Botoes.primario(criar ? "Criar cofre" : "Desbloquear", null);

        progresso.setIndeterminate(true);
        progresso.setVisible(false);
        progressoTexto.setVisible(false);

        JPanel corpo = criar ? corpoDeCriacao() : corpoDeAbertura();
        conteudo(corpo);
        botaoPadrao(principal);

        principal.addActionListener(e -> acionar());
        senha.addActionListener(e -> {
            if (criar) {
                confirmacao.requestFocusInWindow();
            } else {
                acionar();
            }
        });
        confirmacao.addActionListener(e -> acionar());
        sair.addActionListener(e -> aoCancelar());
        if (criar) {
            // A régua consulta uma lista de senhas comuns de 1,3 MB: lida aqui, fora da EDT, antes da primeira tecla.
            Fundo.emFundo(ForcaDeSenha::preaquecer);
        }
        SimplesOuvinteDeDocumento reavaliar = new SimplesOuvinteDeDocumento(this::reavaliar);
        senha.getDocument().addDocumentListener(reavaliar);
        confirmacao.getDocument().addDocumentListener(reavaliar);

        atrasoVisual = new Timer(250, e -> atualizarAtraso());
        reavaliar();
        atualizarAtraso();
    }

    private JPanel corpoDeCriacao() {
        TextoCorrido aviso = new TextoCorrido("Não há recuperação. Se você esquecer esta senha, as chaves SSH e os tokens "
            + "guardados neste cofre se perdem (dá para gerar chaves novas e instalá-las de novo nos servidores).",
            LARGURA_DO_TEXTO - 40).corDoToken(Tokens.TEXTO);
        JPanel caixaDoAviso = new Painel(new java.awt.BorderLayout(10, 0)).fundo(Tokens.ATENCAO_SUAVE).borda(Tokens.ATENCAO).raio(10);
        caixaDoAviso.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        caixaDoAviso.add(new Rotulo().icone(Icones.colorido("alerta", 18, Tokens.ATENCAO)), java.awt.BorderLayout.WEST);
        caixaDoAviso.add(aviso, java.awt.BorderLayout.CENTER);

        return Caixa.coluna(14,
            cabecalho("chave", "Criar o cofre"),
            paragrafo("A senha-mestra protege as chaves SSH e os tokens deste computador. Prefira uma frase de 4 palavras ou mais, "
                + "com espaços: é a mais forte e a mais fácil de lembrar. Sem frase, use 16 caracteres ou mais. Senha "
                + "comum (palavra, nome, data ou sequência, mesmo com número no fim) é recusada."),
            caixaDoAviso,
            campo("Senha-mestra", senha),
            campo("Confirmar a senha", confirmacao),
            Caixa.linha(10, forcaBarra, forcaTexto),
            erro,
            progresso(),
            rodape(sair, principal));
    }

    private JPanel corpoDeAbertura() {
        return Caixa.coluna(14,
            cabecalho("chave", "Desbloquear o cofre"),
            paragrafo("Digite a senha-mestra para abrir as chaves e conectar aos servidores."),
            campo("Senha-mestra", senha),
            erro,
            progresso(),
            rodape(sair, principal));
    }

    private JComponent progresso() {
        return Caixa.coluna(4, progresso, progressoTexto);
    }

    private static JComponent campo(String rotulo, JComponent campo) {
        return Caixa.coluna(5, Rotulo.rotuloDeCampo(rotulo), campo);
    }

    // ---------------------------------------------------------------------------------------------
    // Testes e hooks
    // ---------------------------------------------------------------------------------------------

    public JPasswordField campoDaSenha() {
        return senha;
    }

    public JPasswordField campoDeConfirmacao() {
        return confirmacao;
    }

    public Botao botaoPrincipal() {
        return principal;
    }

    public Botao botaoSair() {
        return sair;
    }

    public String textoDoErro() {
        return erro.getText();
    }

    public String textoDaForca() {
        return forcaTexto.getText();
    }

    public boolean estaOcupado() {
        return ocupado;
    }

    // ---------------------------------------------------------------------------------------------
    // Comportamento
    // ---------------------------------------------------------------------------------------------

    private void reavaliar() {
        if (criar) {
            char[] s = senha.getPassword();
            Avaliacao a = ForcaDeSenha.avaliar(s);
            Zeragem.zerar(s);
            forcaBarra.definir(a.nivel());
            forcaTexto.setText(a.mensagem());
        }
        atualizarBotao();
    }

    private void atualizarBotao() {
        boolean podeAcionar = !ocupado && atraso.restante(relogio.get()).isZero() && senha.getPassword().length > 0;
        principal.setEnabled(podeAcionar);
    }

    private void atualizarAtraso() {
        Duration restante = atraso.restante(relogio.get());
        if (restante.isZero()) {
            if (erro.getText().startsWith("Aguarde")) {
                erro.setText("");
            }
            atrasoVisual.stop();
        } else {
            erro.setText("Aguarde " + (restante.toSeconds() + 1) + " s para tentar de novo.");
            if (!atrasoVisual.isRunning()) {
                atrasoVisual.start();
            }
        }
        atualizarBotao();
    }

    private void acionar() {
        if (ocupado || !principal.isEnabled()) {
            return;
        }
        char[] s = senha.getPassword();
        try {
            if (criar) {
                char[] c = confirmacao.getPassword();
                try {
                    Avaliacao a = ForcaDeSenha.avaliar(s);
                    if (!a.aceita()) {
                        mostrarErro(a.mensagem());
                        return;
                    }
                    if (!Arrays.equals(s, c)) {
                        mostrarErro("As duas senhas não são iguais.");
                        return;
                    }
                } finally {
                    Zeragem.zerar(c);
                }
            }
            erro.setText("");
            ocupar(true);
            CompletableFuture<CofreDeChaves> trabalho = criar ? abridor.criar(s) : abridor.abrir(s);
            trabalho.whenComplete((cofre, falha) -> Fundo.naEdt(() -> terminou(cofre, falha)));
        } finally {
            Zeragem.zerar(s);
            senha.setText("");
            confirmacao.setText("");
        }
    }

    private void ocupar(boolean sim) {
        ocupado = sim;
        senha.setEnabled(!sim);
        confirmacao.setEnabled(!sim);
        progresso.setVisible(sim);
        progressoTexto.setVisible(sim);
        progressoTexto.setText(criar ? "Criando o cofre: derivando a chave (cerca de 1 segundo)…"
            : "Abrindo o cofre: derivando a chave (cerca de 1 segundo)…");
        atualizarBotao();
        reajustar();
    }

    private void terminou(CofreDeChaves cofre, Throwable falha) {
        ocupar(false);
        if (decidiu) {
            if (cofre != null) {
                cofre.bloquear(); // o usuário saiu enquanto o Argon2id rodava: não deixa um cofre aberto sem ninguém usando
            }
            return;
        }
        if (falha == null && cofre != null) {
            atraso.registrarSucesso();
            decidir(Optional.of(cofre));
            return;
        }
        Throwable causa = falha instanceof CompletionException && falha.getCause() != null ? falha.getCause() : falha;
        if (causa instanceof SenhaIncorretaException) {
            atraso.registrarFalha(relogio.get());
            mostrarErro("Senha incorreta.");
            atualizarAtraso();
        } else if (causa instanceof CofreCorrompidoException) {
            mostrarErro("O arquivo do cofre está ilegível: " + Redator.redigir(causa));
        } else if (causa instanceof CofreException || causa instanceof IllegalArgumentException) {
            mostrarErro(Redator.redigir(causa));
        } else {
            mostrarErro("Não consegui " + (criar ? "criar" : "abrir") + " o cofre: " + Redator.redigir(causa));
        }
        senha.requestFocusInWindow();
    }

    private void mostrarErro(String texto) {
        erro.setText(texto);
        reajustar();
    }

    @Override
    protected void aoCancelar() {
        decidir(Optional.empty());
    }

    private void decidir(Optional<CofreDeChaves> resultado) {
        if (decidiu) {
            return;
        }
        decidiu = true;
        atrasoVisual.stop();
        senha.setText("");
        confirmacao.setText("");
        aoDecidir.accept(resultado);
        fechar();
    }

    @Override
    protected void antesDeMostrar() {
        senha.requestFocusInWindow();
    }

    @Override
    protected void aoFechar() {
        atrasoVisual.stop();
        if (!decidiu) {
            decidiu = true;
            aoDecidir.accept(Optional.empty());
        }
    }

    /** Três segmentos que acendem conforme o nível; o texto ao lado diz o mesmo (nunca só cor). */
    static final class BarraDeForca extends JComponent {
        private static final long serialVersionUID = 1L;

        private Nivel nivel = Nivel.FRACA;

        void definir(Nivel novo) {
            this.nivel = novo;
            repaint();
        }

        Nivel nivel() {
            return nivel;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(96, 8);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int acesos = switch (nivel) {
                    case FRACA -> 1;
                    case RAZOAVEL -> 2;
                    case FORTE -> 3;
                };
                String token = switch (nivel) {
                    case FRACA -> Tokens.PERIGO;
                    case RAZOAVEL -> Tokens.ATENCAO;
                    case FORTE -> Tokens.OK;
                };
                int largura = (getWidth() - 8) / 3;
                for (int i = 0; i < 3; i++) {
                    g2.setColor(Tokens.cor(i < acesos ? token : Tokens.TRILHO));
                    g2.fillRoundRect(i * (largura + 4), (getHeight() - 6) / 2, largura, 6, 6, 6);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
