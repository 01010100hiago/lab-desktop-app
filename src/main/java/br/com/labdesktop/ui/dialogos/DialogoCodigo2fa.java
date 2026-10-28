package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Component;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;

/**
 * "Código do autenticador" (ARQUITETURA 10.1): pede os seis dígitos do Google Authenticator. Aceita o espaço do meio
 * ("123 456"), mostra "Código incorreto ou já usado" quando o pedido volta com aviso e a contagem de "Aguarde N s"
 * (o agente bloqueia 60 s depois de 5 erros seguidos), com o botão desabilitado até acabar.
 *
 * <p>O código nunca vai para log, cofre ou preferências: sai só pelo callback {@code aoDecidir}, uma vez
 * ({@code Optional.empty()} = o usuário cancelou).
 */
public final class DialogoCodigo2fa extends Dialogo {

    private final JTextField campo = Campos.codigoDeAutenticador();
    private final Rotulo aviso = new Rotulo().corDoToken(Tokens.PERIGO).tamanho(12.5f);
    private final Rotulo espera = new Rotulo().corDoToken(Tokens.ATENCAO).tamanho(12.5f);
    private final Botao confirmar = Botoes.primario("Confirmar", null);
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final Consumer<Optional<String>> aoDecidir;
    private final Timer relogio;
    private int restante;
    private boolean decidiu;

    /**
     * @param servidor        nome do servidor, para o título
     * @param avisoInicial    {@code null} no primeiro pedido; depois, o motivo do novo pedido
     * @param aguardeSegundos segundos de bloqueio do agente ({@code 0} = sem bloqueio)
     * @param aoDecidir       recebe o código (6 dígitos, já sem espaço) ou vazio se cancelou; chamado uma vez
     */
    public DialogoCodigo2fa(String servidor, String avisoInicial, int aguardeSegundos, Consumer<Optional<String>> aoDecidir) {
        super("Código do autenticador");
        this.aoDecidir = aoDecidir;
        this.restante = Math.max(0, aguardeSegundos);

        TextoCorrido explicacao = paragrafo("O servidor " + servidor + " pede o código de 6 dígitos do autenticador "
            + "(Google Authenticator) para este computador.");
        JComponent campoCentral = Caixa.linha(0, Caixa.mola(), campo, Caixa.mola());
        TextoCorrido dica = dicaCorrida("Se o código está certo e a tela diz que não, confira a hora do celular.");

        JPanel corpo = Caixa.coluna(14,
            cabecalho("chave", "Código do autenticador"),
            explicacao,
            campoCentral,
            Caixa.linha(6, new Rotulo().icone(Icones.colorido("alerta", 14, Tokens.PERIGO)), aviso),
            espera,
            dica,
            rodape(null, cancelar, confirmar));
        conteudo(corpo);
        botaoPadrao(confirmar);
        mostrarAviso(avisoInicial);

        cancelar.addActionListener(e -> aoCancelar());
        confirmar.addActionListener(e -> confirmar());
        campo.addActionListener(e -> confirmar());
        campo.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(this::atualizarBotao));

        relogio = new Timer(1000, e -> passou());
        atualizarEspera();
        if (restante > 0) {
            relogio.start();
        }
        atualizarBotao();
    }

    /** O campo onde se digita (para os testes). */
    public JTextField campo() {
        return campo;
    }

    public Botao botaoConfirmar() {
        return confirmar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    /** O texto da mensagem de erro/aviso agora (vazio se não há). */
    public String textoDoAviso() {
        return aviso.getText();
    }

    public String textoDaEspera() {
        return espera.getText();
    }

    private void mostrarAviso(String texto) {
        aviso.setText(texto == null ? "" : texto);
        Component pai = aviso.getParent();
        if (pai != null) {
            pai.setVisible(texto != null && !texto.isBlank());
        }
    }

    private void passou() {
        if (restante > 0) {
            restante--;
        }
        atualizarEspera();
        atualizarBotao();
        if (restante == 0) {
            relogio.stop();
        }
    }

    private void atualizarEspera() {
        espera.setText(restante > 0 ? "Muitas tentativas. Aguarde " + restante + " s para tentar de novo." : "");
        espera.setVisible(restante > 0);
    }

    private void atualizarBotao() {
        confirmar.setEnabled(restante == 0 && ValidadorDeNomes.normalizarCodigoTotp(campo.getText()) != null);
    }

    private void confirmar() {
        if (!confirmar.isEnabled()) {
            return;
        }
        String codigo = ValidadorDeNomes.normalizarCodigoTotp(campo.getText());
        if (codigo == null) {
            mostrarAviso("O código tem 6 dígitos.");
            return;
        }
        decidir(Optional.of(codigo));
    }

    @Override
    protected void aoCancelar() {
        decidir(Optional.empty());
    }

    private void decidir(Optional<String> resultado) {
        if (decidiu) {
            return;
        }
        decidiu = true;
        campo.setText(""); // o código não fica no campo
        aoDecidir.accept(resultado);
        fechar();
    }

    @Override
    protected void antesDeMostrar() {
        campo.requestFocusInWindow();
    }

    @Override
    protected void aoFechar() {
        relogio.stop();
        if (!decidiu) {
            decidiu = true;
            aoDecidir.accept(Optional.empty());
        }
    }
}
