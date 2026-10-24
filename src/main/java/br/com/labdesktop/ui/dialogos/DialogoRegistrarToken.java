package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import javax.swing.JCheckBox;
import javax.swing.JTextField;

/**
 * Registrar o token deste Mac no agente (ARQUITETURA 8.1 e CONTRATO-API seção 6). O app gera o token (nunca sai do
 * Mac), registra só o HASH dele por SSH e o guarda no cofre. Duas situações:
 * <ul>
 *   <li><b>primeiro registro</b>: o agente ainda não tem entrada para este Mac;</li>
 *   <li><b>substituir</b>: o agente já tem uma entrada com este nome e o app não tem mais o token dela (cofre
 *       recriado, ou token revogado: o estado {@code TOKEN_RECUSADO}). A entrada antiga é revogada e um token novo
 *       registrado. Só roda DEPOIS de o usuário confirmar aqui: a entrada antiga deixa de valer na hora.</li>
 * </ul>
 * Se o registro simples esbarra num nome que já existe, o próprio diálogo oferece a substituição (caixa de
 * marcar), em vez de falhar sem saída.
 */
public final class DialogoRegistrarToken extends Dialogo {

    /** O registro de verdade (ex.: {@code ServidorLab::registrarToken}). Bloqueia: roda fora da EDT. */
    @FunctionalInterface
    public interface Operacao {
        void executar(String nomeDoDispositivo) throws Exception;
    }

    private final String servidor;
    private final Operacao registrar;
    private final Operacao substituir;
    private final Runnable aoConcluir;
    private final Notificacoes avisos;
    private final JTextField nome = Campos.mono(24, "nome-do-mac");
    private final JCheckBox substituirAntigo = new JCheckBox("Revogar o token que já existe com este nome e registrar outro");
    private final Rotulo erro = erro();
    private final Botao confirmar;
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private Runnable desfazerOcupado = () -> { };

    /**
     * @param nomeSugerido {@code nome_do_dispositivo} sugerido pelo nome do Mac
     * @param substituir   {@code true}: o usuário já sabe que é para substituir (token recusado)
     */
    public DialogoRegistrarToken(String servidor, String nomeSugerido, boolean substituir, Operacao registrar,
                                 Operacao substituirOperacao, Runnable aoConcluir, Notificacoes avisos) {
        super(substituir ? "Substituir o token" : "Registrar o token");
        this.servidor = servidor;
        this.registrar = registrar;
        this.substituir = substituirOperacao;
        this.aoConcluir = aoConcluir;
        this.avisos = avisos;
        this.confirmar = Botoes.primario(substituir ? "Substituir token" : "Registrar token", "chave");

        TextoCorrido explicacao = paragrafo(substituir
            ? "O agente de " + servidor + " não aceita mais o token deste Mac (foi revogado, ou o cofre foi recriado). "
                + "O app revoga a entrada antiga com este nome e registra um token novo. A entrada antiga deixa de valer agora."
            : "O app gera um token só para este Mac, registra o hash dele no agente de " + servidor
                + " por SSH e guarda o token no cofre. O token em si nunca sai do Mac.");
        substituirAntigo.setSelected(substituir);
        substituirAntigo.setVisible(false);

        conteudo(Caixa.coluna(14,
            cabecalho("chave", substituir ? "Substituir o token" : "Registrar o token"),
            explicacao,
            Caixa.coluna(5, Rotulo.rotuloDeCampo("Nome deste Mac no agente"), nome,
                Rotulo.dica("Letras minúsculas, números e hífen (até 40).")),
            substituirAntigo,
            erro,
            rodape(null, cancelar, confirmar)));
        botaoPadrao(confirmar);
        nome.setText(nomeSugerido);

        nome.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(() -> {
            erro.setText("");
            atualizar();
        }));
        confirmar.addActionListener(e -> executar());
        nome.addActionListener(e -> executar());
        cancelar.addActionListener(e -> aoCancelar());
        atualizar();
    }

    public JTextField campoDoNome() {
        return nome;
    }

    public JCheckBox caixaDeSubstituicao() {
        return substituirAntigo;
    }

    public Botao botaoConfirmar() {
        return confirmar;
    }

    public String textoDoErro() {
        return erro.getText();
    }

    private void atualizar() {
        confirmar.setEnabled(ValidadorDeNomes.dispositivoValido(nome.getText().strip()));
    }

    private void executar() {
        String escolhido = nome.getText().strip();
        if (!confirmar.isEnabled() || !ValidadorDeNomes.dispositivoValido(escolhido)) {
            return;
        }
        erro.setText("");
        boolean substituindo = substituirAntigo.isSelected();
        Operacao op = substituindo ? substituir : registrar;
        desfazerOcupado = confirmar.ocupar();
        Tarefas.rodar(() -> op.executar(escolhido), () -> {
            desfazerOcupado.run();
            avisos.ok("Token registrado em " + servidor + " (" + escolhido + ").");
            fechar();
            aoConcluir.run();
        }, falha -> {
            desfazerOcupado.run();
            erro.setText(MensagensDeErro.de(falha));
            if (falha instanceof ErroProvisionamento e && ErroProvisionamento.TOKEN_CONFLITO.equals(e.codigo())) {
                substituirAntigo.setVisible(true);
                substituirAntigo.setSelected(false);
            }
            reajustar();
        });
    }

    @Override
    protected void antesDeMostrar() {
        nome.requestFocusInWindow();
        nome.selectAll();
    }
}
