package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * "Confiar neste servidor?" (ARQUITETURA 6.2): mostra a impressão digital ({@code SHA256:...}) que a sondagem capturou
 * ANTES de qualquer credencial ser enviada, com o comando que o usuário roda NO servidor para conferir. Em palavras simples:
 * a impressão identifica o servidor (como um RG), só é pedida agora e, se mudar depois, o app recusa conectar por
 * segurança. Só habilita o botão "Confere, confiar neste servidor" depois de marcar que conferiu. Confirmado, o pin vai
 * para o cofre; recusado, nada é gravado e nada foi autenticado.
 *
 * <p>Este diálogo é só para o PRIMEIRO contato. Chave que MUDOU não passa por aqui: não existe "aceitar mesmo
 * assim" (ARQUITETURA 6.2); redefinir o pin é uma ação explícita e à parte, com a senha-mestra.
 */
public final class DialogoConfiarHost extends Dialogo {

    private final SondagemDeHost.Resultado sonda;
    private final Consumer<Boolean> aoDecidir;
    private final JCheckBox conferi = new JCheckBox("Rodei o comando no servidor e a impressão é a mesma");
    private final Botao confiar = Botoes.primario("Confere, confiar neste servidor", "ok");
    private final Botao cancelar = Botoes.secundario("Não confere, cancelar", null);
    private final Botao copiarImpressao;
    private final Botao copiarComando;
    private final TextoCorrido explicacao;
    private final TextoCorrido comandoDeConferencia;

    private boolean decidiu;

    /**
     * @param servidor  nome do servidor
     * @param endereco  {@code host:porta}, como o usuário digitou
     * @param aoDecidir {@code true} = o usuário confirmou a impressão; {@code false} = cancelou; chamado uma vez
     */
    public DialogoConfiarHost(String servidor, String endereco, SondagemDeHost.Resultado sonda, Notificacoes avisos,
                              Consumer<Boolean> aoDecidir) {
        this(servidor, endereco, sonda, avisos, aoDecidir, CopiadorDeTexto.doSistema());
    }

    /** Com a área de transferência trocável (testes). */
    public DialogoConfiarHost(String servidor, String endereco, SondagemDeHost.Resultado sonda, Notificacoes avisos,
                              Consumer<Boolean> aoDecidir, CopiadorDeTexto copiador) {
        super("Confiar no servidor");
        this.sonda = sonda;
        this.aoDecidir = aoDecidir;

        explicacao = paragrafo("O app ainda não conhece o servidor " + servidor + " (" + endereco + "). A impressão digital "
            + "abaixo identifica o servidor, como um RG. Ela só é pedida agora, na primeira conexão. Se um dia ela mudar, o "
            + "app recusa conectar, por segurança: pode ser outra máquina se passando pelo seu servidor.");

        JPanel quadro = new Painel(new BorderLayout(0, 8)).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(10);
        quadro.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14));
        // A impressão em texto corrido: quebra em vez de cortar, e dá para selecionar com o mouse.
        TextoCorrido impressao = new TextoCorrido(sonda.impressao(), LARGURA_DO_TEXTO - 40).mono(13f);
        impressao.setToolTipText("Impressão digital (SHA-256) da chave do servidor");
        copiarImpressao = BotaoCopiar.criar("Copiar", sonda::impressao, copiador, avisos);
        quadro.add(Caixa.coluna(6,
            Caixa.linha(8, Rotulo.rotuloDeCampo("Impressão digital do servidor"), Caixa.mola(), copiarImpressao),
            impressao,
            Caixa.linha(8, Rotulo.rotuloDeCampo("Tipo de chave"), Rotulo.monoTexto(sonda.algoritmo(), 13f))), BorderLayout.CENTER);

        TextoCorrido instrucao = paragrafo("Para conferir, rode este comando no próprio servidor (por exemplo, numa sessão "
            + "SSH aberta nele). O resultado tem de mostrar a mesma impressão que está acima:");
        comandoDeConferencia = new TextoCorrido(sonda.comandoDeConferencia(), LARGURA_DO_TEXTO - 110).mono(12.5f);
        copiarComando = BotaoCopiar.criar("Copiar", sonda::comandoDeConferencia, copiador, avisos);
        JPanel linhaDoComando = new Painel(new BorderLayout(10, 0));
        linhaDoComando.add(comandoDeConferencia, BorderLayout.CENTER);
        JPanel lado = new Painel(new BorderLayout());
        lado.add(copiarComando, BorderLayout.NORTH);
        linhaDoComando.add(lado, BorderLayout.EAST);

        JComponent corpo = Caixa.coluna(14,
            cabecalho("chave", "Confiar neste servidor?"),
            explicacao,
            quadro,
            instrucao,
            linhaDoComando,
            conferi,
            rodape(null, cancelar, confiar));
        conteudo(corpo);
        botaoPadrao(confiar);
        confiar.setEnabled(false);

        conferi.addActionListener(e -> confiar.setEnabled(conferi.isSelected()));
        confiar.addActionListener(e -> decidir(true));
        cancelar.addActionListener(e -> aoCancelar());
    }

    public JCheckBox caixaDeConferencia() {
        return conferi;
    }

    public Botao botaoConfiar() {
        return confiar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoCopiarImpressao() {
        return copiarImpressao;
    }

    public Botao botaoCopiarComando() {
        return copiarComando;
    }

    /** O texto explicativo do alto (para os testes conferirem a linguagem). */
    public String textoDaExplicacao() {
        return explicacao.getText();
    }

    /** O comando mostrado numa linha só, o mesmo que o botão copia. */
    public String comandoMostrado() {
        return comandoDeConferencia.getText();
    }

    public SondagemDeHost.Resultado sondagem() {
        return sonda;
    }

    @Override
    protected void aoCancelar() {
        decidir(false);
    }

    private void decidir(boolean confiou) {
        if (decidiu) {
            return;
        }
        decidiu = true;
        aoDecidir.accept(confiou && conferi.isSelected());
        fechar();
    }

    @Override
    protected void aoFechar() {
        if (!decidiu) {
            decidiu = true;
            aoDecidir.accept(false);
        }
    }
}
