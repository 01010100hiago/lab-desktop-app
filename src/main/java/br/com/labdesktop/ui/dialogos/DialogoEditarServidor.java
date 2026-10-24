package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.GerenciadorServidores.ResultadoDaEdicao;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import javax.swing.JComponent;
import javax.swing.JTextField;

/**
 * "Editar servidor": nome, endereço, porta do SSH, usuário e porta do painel de um servidor já cadastrado. Valida com as
 * MESMAS regras do assistente ({@link Dados#validar}). Mudar o endereço ou a porta do SSH apaga a impressão digital guardada, e o diálogo
 * avisa isso numa frase ENQUANTO o usuário digita, antes de salvar: a próxima conexão pede para confirmá-la de novo. Salvar
 * grava no cofre (fora da EDT) e o app reconecta com os dados novos. Mudar só a porta do painel (o erro de digitação mais
 * comum no passo 1 do assistente) NÃO apaga a impressão digital: a máquina é a mesma.
 *
 * <p>A chave SSH e o token não mudam aqui: continuam os mesmos.
 */
public final class DialogoEditarServidor extends Dialogo {

    /** Quem grava a edição (fora da EDT): em produção, {@code GerenciadorServidores.atualizar}. */
    @FunctionalInterface
    public interface Gravacao {
        ResultadoDaEdicao gravar(Dados dados) throws Exception;
    }

    /** O aviso de que a impressão digital será apagada. Uma frase, em palavras simples. */
    public static final String AVISO_DA_IMPRESSAO = "Como o endereço ou a porta mudou, a impressão digital guardada será "
        + "apagada e a próxima conexão pedirá para você confirmá-la de novo.";

    private final ConfigServidor original;
    private final Gravacao gravacao;
    private final Notificacoes avisos;
    private final JTextField nome = Campos.texto(14, "lab");
    private final JTextField host = Campos.mono(16, "192.0.2.5");
    private final JTextField porta = Campos.mono(5, "22");
    private final JTextField usuario = Campos.mono(14, "usuario");
    private final JTextField portaDoAgente = Campos.mono(5, "7000");
    private final TextoCorrido avisoDoEndereco = new TextoCorrido(AVISO_DA_IMPRESSAO, LARGURA_DO_TEXTO)
        .corDoToken(Tokens.ATENCAO).tamanho(12.5f);
    private final TextoCorrido erro = erroCorrido();
    private final Botao salvar = Botoes.primario("Salvar e reconectar", "ok");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private boolean ocupado;
    private Runnable desfazerOcupado = () -> { };

    public DialogoEditarServidor(ConfigServidor atual, Gravacao gravacao, Notificacoes avisos) {
        super("Editar servidor");
        this.original = atual;
        this.gravacao = gravacao;
        this.avisos = avisos;
        nome.setText(atual.nome());
        host.setText(atual.host());
        porta.setText(String.valueOf(atual.porta()));
        usuario.setText(atual.usuario());
        portaDoAgente.setText(String.valueOf(atual.portaAgente()));
        avisoDoEndereco.setVisible(false);
        salvar.setEnabled(false);

        conteudo(Caixa.coluna(12,
            cabecalho("config", "Editar servidor"),
            paragrafo("Mude os dados de " + atual.nome() + ". A chave e o token continuam os mesmos; ao salvar, o app "
                + "reconecta com os dados novos."),
            Caixa.coluna(8,
                linhaDeCampos("Nome", nome, "Usuário", usuario),
                linhaDeCampos("Endereço", host, "Porta do SSH", porta),
                linhaDeCampos("Porta do painel no servidor", portaDoAgente, null, null),
                dicaCorrida(DialogoAdicionarServidor.DICA_DA_PORTA_DO_PAINEL)),
            avisoDoEndereco,
            erro,
            rodape(null, cancelar, salvar)));
        botaoPadrao(salvar);

        SimplesOuvinteDeDocumento aoDigitar = new SimplesOuvinteDeDocumento(this::atualizarTela);
        for (JTextField c : new JTextField[] {nome, host, porta, usuario, portaDoAgente}) {
            c.getDocument().addDocumentListener(aoDigitar);
        }
        salvar.addActionListener(e -> salvar());
        cancelar.addActionListener(e -> aoCancelar());
    }

    private static JComponent linhaDeCampos(String r1, JComponent c1, String r2, JComponent c2) {
        JComponent a = Caixa.coluna(3, Rotulo.rotuloDeCampo(r1), c1);
        if (r2 == null) {
            return Caixa.linha(12, a, Caixa.mola());
        }
        return Caixa.linha(12, a, Caixa.coluna(3, Rotulo.rotuloDeCampo(r2), c2));
    }

    // ---------------------------------------------------------------------------------------------
    // Hooks para os testes
    // ---------------------------------------------------------------------------------------------

    public JTextField campoDoNome() {
        return nome;
    }

    public JTextField campoDoHost() {
        return host;
    }

    public JTextField campoDaPorta() {
        return porta;
    }

    public JTextField campoDoUsuario() {
        return usuario;
    }

    public JTextField campoDaPortaDoAgente() {
        return portaDoAgente;
    }

    public Botao botaoSalvar() {
        return salvar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public boolean mostraOAvisoDaImpressao() {
        return avisoDoEndereco.isVisible();
    }

    public String textoDoAviso() {
        return avisoDoEndereco.getText();
    }

    public String textoDoErro() {
        return erro.getText();
    }

    public boolean ocupado() {
        return ocupado;
    }

    // ---------------------------------------------------------------------------------------------
    // Comportamento
    // ---------------------------------------------------------------------------------------------

    /** Salvar só vale se algo mudou; o aviso da impressão aparece assim que o endereço ou a porta mudam. */
    private void atualizarTela() {
        dizerErro(erro, "");
        avisoDoEndereco.setVisible(enderecoMudou());
        salvar.setEnabled(!ocupado && algoMudou());
        reajustar();
    }

    private boolean algoMudou() {
        return !nome.getText().strip().equals(original.nome()) || !usuario.getText().strip().equals(original.usuario())
            || portaDoPainelMudou() || enderecoMudou();
    }

    private boolean portaDoPainelMudou() {
        try {
            return Integer.parseInt(portaDoAgente.getText().strip()) != original.portaAgente();
        } catch (NumberFormatException e) {
            return !portaDoAgente.getText().strip().equals(String.valueOf(original.portaAgente()));
        }
    }

    /** Mesma regra do cofre ({@link ConfigServidor#mesmoEnderecoQue}): endereço sem diferença de maiúsculas, e a porta. */
    private boolean enderecoMudou() {
        if (!host.getText().strip().equalsIgnoreCase(original.host())) {
            return true;
        }
        try {
            return Integer.parseInt(porta.getText().strip()) != original.porta();
        } catch (NumberFormatException e) {
            return !porta.getText().strip().equals(String.valueOf(original.porta()));
        }
    }

    private void salvar() {
        if (!salvar.isEnabled()) {
            return;
        }
        Dados dados;
        try {
            dados = lerDados();
        } catch (IllegalArgumentException e) {
            dizerErro(erro, e.getMessage());
            reajustar();
            return;
        }
        dizerErro(erro, "");
        ocupar(true);
        Tarefas.rodar(() -> gravacao.gravar(dados), resultado -> {
            ocupar(false);
            fechar();
            if (!resultado.mudouAlgo()) {
                avisos.info("Nada mudou.");
                return;
            }
            avisos.ok("Servidor " + resultado.config().nome() + " salvo. Reconectando…");
            if (resultado.impressaoApagada()) {
                avisos.atencao("O endereço mudou: confirme a impressão digital do servidor quando o app pedir.");
            }
        }, falha -> {
            ocupar(false);
            dizerErro(erro, MensagensDeErro.de(falha));
            reajustar();
        });
    }

    /** As mesmas validações do assistente: {@link Dados#validar}. */
    private Dados lerDados() {
        int p;
        try {
            p = Integer.parseInt(porta.getText().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("A porta do SSH precisa ser um número.");
        }
        int a;
        try {
            a = Integer.parseInt(portaDoAgente.getText().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("A porta do painel precisa ser um número.");
        }
        Dados d = new Dados(nome.getText().strip(), host.getText().strip(), p, usuario.getText().strip(), a);
        d.validar();
        return d;
    }

    private void ocupar(boolean sim) {
        ocupado = sim;
        for (JComponent c : new JComponent[] {nome, host, porta, usuario, portaDoAgente, cancelar}) {
            c.setEnabled(!sim);
        }
        if (sim) {
            desfazerOcupado = salvar.ocupar();
        } else {
            desfazerOcupado.run();
            desfazerOcupado = () -> { };
            salvar.setEnabled(algoMudou());
        }
    }

    @Override
    protected void aoCancelar() {
        if (!ocupado) {
            fechar();
        }
    }

    @Override
    protected void antesDeMostrar() {
        nome.requestFocusInWindow();
    }
}
