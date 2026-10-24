package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * "Confiar neste servidor?" (ARQUITETURA 6.2): mostra o algoritmo e a impressão digital ({@code SHA256:...}) que a
 * sondagem capturou ANTES de qualquer credencial ser enviada, com o comando que o usuário roda no servidor para
 * conferir. Só habilita o botão depois de marcar que conferiu. Confirmado, o pin vai para o cofre; recusado, nada é
 * gravado e nada foi autenticado.
 *
 * <p>Este diálogo é só para o PRIMEIRO contato. Chave que MUDOU não passa por aqui: não existe "aceitar mesmo
 * assim" (ARQUITETURA 6.2); redefinir o pin é uma ação explícita e à parte, com a senha-mestra.
 */
public final class DialogoConfiarHost extends Dialogo {

    private final SondagemDeHost.Resultado sonda;
    private final Notificacoes avisos;
    private final Consumer<Boolean> aoDecidir;
    private final JCheckBox conferi = new JCheckBox("Conferi a impressão no servidor e ela é igual a esta");
    private final Botao confiar = Botoes.primario("Confiar neste servidor", "ok");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private boolean decidiu;

    /**
     * @param servidor  nome do servidor
     * @param endereco  {@code host:porta}, como o usuário digitou
     * @param aoDecidir {@code true} = o usuário confirmou a impressão; {@code false} = cancelou; chamado uma vez
     */
    public DialogoConfiarHost(String servidor, String endereco, SondagemDeHost.Resultado sonda, Notificacoes avisos,
                              Consumer<Boolean> aoDecidir) {
        super("Confiar no servidor");
        this.sonda = sonda;
        this.avisos = avisos;
        this.aoDecidir = aoDecidir;

        TextoCorrido explicacao = paragrafo("O app ainda não conhece a chave do servidor " + servidor + " (" + endereco
            + "). Antes de enviar qualquer credencial, confira se a impressão digital abaixo é a do SEU servidor. "
            + "Se não for, alguém pode estar no meio do caminho: cancele.");

        JPanel quadro = new Painel(new BorderLayout(0, 8)).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(10);
        quadro.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14));
        Rotulo impressao = Rotulo.monoTexto(sonda.impressao(), 13f);
        impressao.setToolTipText("Impressão digital (SHA-256) da chave do servidor");
        Rotulo algoritmo = Rotulo.monoTexto(sonda.algoritmo(), 13f);
        Botao copiarImpressao = Botoes.mini("Copiar", "copiar");
        copiarImpressao.addActionListener(e -> copiar(sonda.impressao(), "Impressão copiada."));
        quadro.add(Caixa.coluna(6,
            Caixa.linha(8, Rotulo.rotuloDeCampo("Algoritmo"), algoritmo),
            Caixa.linha(8, Rotulo.rotuloDeCampo("Impressão"), impressao, copiarImpressao)), BorderLayout.CENTER);

        Rotulo instrucao = Rotulo.rotuloDeCampo("Para conferir, rode no servidor:");
        Rotulo comando = Rotulo.monoTexto(sonda.comandoDeConferencia(), 12.5f);
        Botao copiarComando = Botoes.mini("Copiar", "copiar");
        copiarComando.addActionListener(e -> copiar(sonda.comandoDeConferencia(), "Comando copiado."));
        JComponent linhaDoComando = Caixa.linha(8, comando, copiarComando);

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

    public SondagemDeHost.Resultado sondagem() {
        return sonda;
    }

    private void copiar(String texto, String aviso) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(texto), null);
        avisos.info(aviso);
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
