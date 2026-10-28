package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.servidor.OperacoesDe2fa;
import br.com.labdesktop.ssh.SegredoTotp;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CopiaTemporaria;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.QrDaUri;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/**
 * "Configurar 2FA" (ARQUITETURA 10.1): lê o estado do 2FA no agente, e então
 * <ul>
 *   <li><b>desligado ou pendente</b>: "Configurar" configura o 2FA por SSH, desenha o QR (ZXing) e a chave em
 *       texto (grupos de 4, com Copiar) e confirma o primeiro código; este Mac fica confirmado por 30 dias, sem pedir
 *       um segundo código logo em seguida;</li>
 *   <li><b>ativo</b>: "Desativar" pede um código válido.</li>
 * </ul>
 * Fechar antes de confirmar deixa o 2FA {@code pendente}, que não exige nada (o usuário nunca se tranca).
 *
 * <p>O segredo existe na tela só enquanto o diálogo está aberto: ao fechar, o {@link SegredoTotp} é zerado, a
 * imagem do QR e o texto da chave são soltos. A chave copiada com o botão Copiar também sai: a {@link CopiaTemporaria}
 * a tira da área de transferência em 30 s, ao confirmar e ao fechar (senão ela ficaria no histórico de gerenciadores de
 * área de transferência e na Área de Transferência Universal, e quem a lesse geraria códigos válidos para sempre).
 * Todo SSH e toda rede ficam fora da EDT ({@link Tarefas}).
 */
public final class Configurar2fa extends Dialogo {

    private static final String CARREGANDO = "carregando";
    private static final String LIGAR = "ligar";
    private static final String QR = "qr";
    private static final String ATIVO = "ativo";
    private static final String PRONTO = "pronto";
    private static final DateTimeFormatter DATA_E_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final String servidor;
    private final OperacoesDe2fa operacoes;
    private final Notificacoes avisos;
    private final Estado2fa dica;
    private final CopiaTemporaria copia;

    private final CardLayout cartoes = new br.com.labdesktop.ui.componentes.CartoesAjustaveis();
    private final JPanel pilha = new Painel(cartoes);

    // cartão LIGAR
    private final TextoCorrido textoDeLigar = paragrafo("");
    private final Rotulo erroDeLigar = erro();
    private final Botao botaoConfigurar = Botoes.primario("Configurar 2FA", "chave");
    private final Botao fecharLigar = Botoes.secundario("Fechar", null);

    // cartão QR
    private final Rotulo imagemDoQr = new Rotulo();
    private final Rotulo chaveEmTexto = new Rotulo().mono(14f).corDoToken(Tokens.TEXTO);
    private final JTextField codigoDeConfirmacao = Campos.codigoDeAutenticador();
    private final Rotulo erroDeConfirmar = erro();
    private final Botao botaoConfirmar = Botoes.primario("Confirmar", null);
    private final Botao botaoCopiar = Botoes.mini("Copiar", "copiar");
    private final Botao fecharQr = Botoes.secundario("Fechar", null);

    // cartão ATIVO
    private final JTextField codigoParaDesativar = Campos.codigoDeAutenticador();
    private final Rotulo erroDeDesativar = erro();
    private final Botao botaoDesativar = Botoes.perigo("Desativar 2FA", null);
    private final Botao fecharAtivo = Botoes.secundario("Fechar", null);

    // cartão PRONTO
    private final TextoCorrido textoPronto = paragrafo("");
    private final Botao fecharPronto = Botoes.primario("Concluir", "ok");

    private final Rotulo avisoDoCarregamento = new Rotulo("Consultando o estado do 2FA no servidor…").corDoToken(Tokens.TEXTO2);
    private SegredoTotp segredo;
    private String cartaoAtual = CARREGANDO;
    private Runnable desfazerConfigurar = () -> { };
    private Runnable desfazerConfirmar = () -> { };
    private Runnable desfazerDesativar = () -> { };
    private boolean confirmando;
    private boolean desativando;

    /**
     * @param servidor  nome do servidor (título e texto)
     * @param dica      o último estado conhecido do 2FA, usado se o agente não responder agora (por exemplo, enquanto
     *                  espera o código do autenticador)
     * @param operacoes o servidor ({@code ServidorLab}) ou um de mentira nos testes
     * @param avisos    para a mensagem final ("2FA ativado em lab.")
     */
    public Configurar2fa(String servidor, Estado2fa dica, OperacoesDe2fa operacoes, Notificacoes avisos) {
        this(servidor, dica, operacoes, avisos, CopiaTemporaria.doSistema());
    }

    /**
     * @param copia quem copia a chave e a tira da área de transferência depois (a do sistema em produção; os testes
     *              passam uma com área de transferência e relógio de mentira)
     */
    public Configurar2fa(String servidor, Estado2fa dica, OperacoesDe2fa operacoes, Notificacoes avisos,
                         CopiaTemporaria copia) {
        super("2FA do servidor " + servidor);
        this.servidor = servidor;
        this.dica = dica == null ? Estado2fa.DESLIGADO : dica;
        this.operacoes = operacoes;
        this.avisos = avisos;
        this.copia = copia;
        botaoCopiar.setToolTipText("Copia a chave; ela sai da área de transferência em " + copia.validade().toSeconds()
            + " s ou ao fechar esta janela");

        pilha.add(cartaoCarregando(), CARREGANDO);
        pilha.add(cartaoLigar(), LIGAR);
        pilha.add(cartaoQr(), QR);
        pilha.add(cartaoAtivo(), ATIVO);
        pilha.add(cartaoPronto(), PRONTO);
        conteudo(pilha);

        botaoConfigurar.addActionListener(e -> configurar());
        botaoConfirmar.addActionListener(e -> confirmar());
        botaoCopiar.addActionListener(e -> copiarChave());
        botaoDesativar.addActionListener(e -> desativar());
        for (Botao b : new Botao[] {fecharLigar, fecharQr, fecharAtivo, fecharPronto}) {
            b.addActionListener(e -> fechar());
        }
        codigoDeConfirmacao.addActionListener(e -> confirmar());
        codigoParaDesativar.addActionListener(e -> desativar());
        SimplesOuvinteDeDocumento reavaliar = new SimplesOuvinteDeDocumento(this::reavaliarBotoes);
        codigoDeConfirmacao.getDocument().addDocumentListener(reavaliar);
        codigoParaDesativar.getDocument().addDocumentListener(reavaliar);
        reavaliarBotoes();
        mostrarCartao(CARREGANDO);
    }

    // ---------------------------------------------------------------------------------------------
    // Cartões
    // ---------------------------------------------------------------------------------------------

    private JComponent cartaoCarregando() {
        return Caixa.coluna(14, cabecalho("chave", "2FA do servidor " + servidor), avisoDoCarregamento);
    }

    private JComponent cartaoLigar() {
        return Caixa.coluna(14,
            cabecalho("chave", "Configurar o 2FA"),
            textoDeLigar,
            erroDeLigar,
            rodape(null, fecharLigar, botaoConfigurar));
    }

    private JComponent cartaoQr() {
        imagemDoQr.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel moldura = new Painel(new BorderLayout()).fundo(Tokens.QR_FUNDO).borda(Tokens.BORDA).raio(10);
        moldura.add(imagemDoQr, BorderLayout.CENTER);
        moldura.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6));
        moldura.setPreferredSize(new java.awt.Dimension(260, 260));
        moldura.setMaximumSize(new java.awt.Dimension(260, 260));
        return Caixa.coluna(12,
            cabecalho("chave", "Escaneie o QR no autenticador"),
            paragrafo("No Google Authenticator: toque em +, escolha \"Ler código QR\" e aponte para a imagem. "
                + "Sem câmera, digite a chave abaixo à mão."),
            Caixa.linha(0, Caixa.mola(), moldura, Caixa.mola()),
            Rotulo.rotuloDeCampo("Chave, para digitar à mão"),
            Caixa.linha(10, chaveEmTexto, botaoCopiar, Caixa.mola()),
            Rotulo.rotuloDeCampo("Agora digite o código de 6 dígitos que o aplicativo mostra"),
            Caixa.linha(0, Caixa.mola(), codigoDeConfirmacao, Caixa.mola()),
            erroDeConfirmar,
            dicaCorrida("A chave copiada sai da área de transferência em " + copia.validade().toSeconds()
                + " s ou ao fechar esta janela. Se você fechar antes de confirmar, o 2FA continua pendente e nada é exigido."),
            rodape(null, fecharQr, botaoConfirmar));
    }

    private JComponent cartaoAtivo() {
        return Caixa.coluna(14,
            cabecalho("chave", "O 2FA está ativo"),
            paragrafo("Neste servidor, cada computador confirma o código do autenticador a cada 30 dias. Para desativar, "
                + "digite um código válido agora."),
            Caixa.linha(0, Caixa.mola(), codigoParaDesativar, Caixa.mola()),
            erroDeDesativar,
            rodape(null, fecharAtivo, botaoDesativar));
    }

    private JComponent cartaoPronto() {
        return Caixa.coluna(14,
            Caixa.linha(10, new Rotulo().icone(Icones.colorido("ok-circulo", 22, Tokens.OK)), Rotulo.titulo("Pronto")),
            textoPronto,
            rodape(null, fecharPronto));
    }

    private void mostrarCartao(String nome) {
        cartaoAtual = nome;
        cartoes.show(pilha, nome);
        switch (nome) {
            case LIGAR -> botaoPadrao(botaoConfigurar);
            case QR -> botaoPadrao(botaoConfirmar);
            case ATIVO -> botaoPadrao(botaoDesativar);
            case PRONTO -> botaoPadrao(fecharPronto);
            default -> { /* carregando: sem botão padrão */ }
        }
        reajustar();
    }

    // ---------------------------------------------------------------------------------------------
    // Hooks para os testes
    // ---------------------------------------------------------------------------------------------

    public String cartaoAtual() {
        return cartaoAtual;
    }

    public Botao botaoConfigurar() {
        return botaoConfigurar;
    }

    public Botao botaoConfirmar() {
        return botaoConfirmar;
    }

    public Botao botaoCopiar() {
        return botaoCopiar;
    }

    public Botao botaoDesativar() {
        return botaoDesativar;
    }

    public JTextField campoDeConfirmacao() {
        return codigoDeConfirmacao;
    }

    public JTextField campoParaDesativar() {
        return codigoParaDesativar;
    }

    public String textoDoErroDeConfirmar() {
        return erroDeConfirmar.getText();
    }

    public String textoDoErroDeLigar() {
        return erroDeLigar.getText();
    }

    public String textoDoErroDeDesativar() {
        return erroDeDesativar.getText();
    }

    public String textoDaChave() {
        return chaveEmTexto.getText();
    }

    public boolean mostraQr() {
        return imagemDoQr.getIcon() != null;
    }

    public String textoPronto() {
        return textoPronto.getText();
    }

    /** O segredo em memória agora (para provar que foi zerado ao fechar). */
    public SegredoTotp segredoEmMemoria() {
        return segredo;
    }

    // ---------------------------------------------------------------------------------------------
    // Comportamento
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void antesDeMostrar() {
        carregar();
    }

    /** Lê o estado no agente; se ele não responde agora (ou pede código), usa o último estado conhecido. */
    public void carregar() {
        mostrarCartao(CARREGANDO);
        Tarefas.rodar(operacoes::consultarVersao,
            v -> aplicarEstado(Estado2fa.deRotulo(v.totp()), false),
            falha -> aplicarEstado(dica, true));
    }

    private void aplicarEstado(Estado2fa estado, boolean usouDica) {
        if (jaFechou()) {
            return;
        }
        String nota = usouDica ? " (Não consegui confirmar com o agente agora; este é o último estado conhecido.)" : "";
        if (estado == Estado2fa.ATIVO) {
            mostrarCartao(ATIVO);
            codigoParaDesativar.requestFocusInWindow();
        } else {
            textoDeLigar.setText(
                (estado == Estado2fa.PENDENTE
                    ? "Há uma configuração começada e ainda não confirmada em " + servidor + ". Configurar de novo gera um segredo novo."
                    : "O 2FA está desligado em " + servidor + ".")
                    + " Com ele ligado, o agente só atende este computador depois de um código do Google Authenticator, "
                    + "confirmado a cada 30 dias. Enquanto você não confirmar o primeiro código, nada é exigido." + nota);
            mostrarCartao(LIGAR);
        }
    }

    private void configurar() {
        erroDeLigar.setText("");
        desfazerConfigurar = botaoConfigurar.ocupar();
        Tarefas.rodar(operacoes::iniciarConfiguracao2fa, this::mostrarQr, falha -> {
            desfazerConfigurar.run();
            erroDeLigar.setText(MensagensDeErro.de(falha));
            reajustar();
        });
    }

    private void mostrarQr(SegredoTotp novo) {
        desfazerConfigurar.run();
        if (jaFechou()) {
            novo.zerar(); // o usuário fechou enquanto o SSH respondia: o segredo não fica solto
            return;
        }
        try {
            BufferedImage imagem = QrDaUri.gerar(novo.uri(), 240);
            imagemDoQr.setIcon(new ImageIcon(imagem));
            chaveEmTexto.setText(novo.segredoEmGrupos());
        } catch (com.google.zxing.WriterException e) {
            novo.zerar();
            erroDeLigar.setText("Não consegui desenhar o QR: " + e.getMessage());
            reajustar();
            return;
        }
        this.segredo = novo;
        codigoDeConfirmacao.setText("");
        erroDeConfirmar.setText("");
        mostrarCartao(QR);
        codigoDeConfirmacao.requestFocusInWindow();
    }

    private void confirmar() {
        String codigo = ValidadorDeNomes.normalizarCodigoTotp(codigoDeConfirmacao.getText());
        if (codigo == null || !botaoConfirmar.isEnabled()) {
            return;
        }
        erroDeConfirmar.setText("");
        confirmando = true;
        desfazerConfirmar = botaoConfirmar.ocupar();
        Tarefas.rodar(() -> operacoes.confirmarConfiguracao2fa(codigo), this::ficouAtivo, falha -> {
            confirmando = false;
            desfazerConfirmar.run();
            erroDeConfirmar.setText(MensagensDeErro.de(falha));
            codigoDeConfirmacao.setText("");
            reavaliarBotoes();
            reajustar();
        });
    }

    private void ficouAtivo(Optional<Instant> ate) {
        confirmando = false;
        desfazerConfirmar.run();
        zerarSegredo(); // o 2FA está ativo: o segredo já cumpriu seu papel na tela
        textoPronto.setText("O 2FA está ativo em " + servidor + ". "
            + ate.map(t -> "Este computador fica confirmado por 30 dias (até " + DATA_E_HORA.format(t.atZone(ZoneId.systemDefault())) + ")")
                .orElse("Este computador vai pedir o código na próxima vez que o servidor exigir")
            + ". Os próximos Macs vão pedir o código de 6 dígitos ao conectar.");
        avisos.ok("2FA ativado em " + servidor + ".");
        mostrarCartao(PRONTO);
    }

    private void desativar() {
        String codigo = ValidadorDeNomes.normalizarCodigoTotp(codigoParaDesativar.getText());
        if (codigo == null || !botaoDesativar.isEnabled()) {
            return;
        }
        erroDeDesativar.setText("");
        desativando = true;
        desfazerDesativar = botaoDesativar.ocupar();
        Tarefas.rodar(() -> operacoes.desativar2fa(codigo), () -> {
            desativando = false;
            desfazerDesativar.run();
            avisos.ok("2FA desativado em " + servidor + ".");
            fechar();
        }, falha -> {
            desativando = false;
            desfazerDesativar.run();
            erroDeDesativar.setText(MensagensDeErro.de(falha));
            codigoParaDesativar.setText("");
            reavaliarBotoes();
            reajustar();
        });
    }

    private void copiarChave() {
        if (segredo == null || segredo.estaZerado()) {
            return;
        }
        if (copia.copiar(segredo.segredo())) {
            avisos.info("Chave do 2FA copiada. Ela sai da área de transferência em " + copia.validade().toSeconds()
                + " s ou ao fechar esta janela.");
        } else {
            avisos.atencao("Não consegui copiar a chave. Digite-a à mão, a partir do texto na tela.");
        }
    }

    private void reavaliarBotoes() {
        botaoConfirmar.setEnabled(!confirmando && ValidadorDeNomes.normalizarCodigoTotp(codigoDeConfirmacao.getText()) != null);
        botaoDesativar.setEnabled(!desativando && ValidadorDeNomes.normalizarCodigoTotp(codigoParaDesativar.getText()) != null);
    }

    private void zerarSegredo() {
        copia.limparAgora(); // a chave copiada não sobrevive ao segredo na tela (ARQUITETURA 10)
        if (segredo != null) {
            segredo.zerar();
            segredo = null;
        }
        imagemDoQr.setIcon(null);
        chaveEmTexto.setText("");
        codigoDeConfirmacao.setText("");
    }

    @Override
    protected void aoFechar() {
        zerarSegredo();
    }
}
