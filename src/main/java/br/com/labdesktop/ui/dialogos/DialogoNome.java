package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Pede UM texto curto (o nome de uma pasta nova, o novo nome de um arquivo, a pasta dos projetos). O botão que confirma leva o
 * verbo da ação ("Criar pasta", "Renomear"), nunca "OK", e só habilita com um valor que passa na validação, que diz em frase
 * simples o que está errado enquanto o usuário digita.
 */
public final class DialogoNome extends Dialogo {

    /** Valida o texto: devolve {@code null} se vale, ou a frase do que está errado. */
    @FunctionalInterface
    public interface Validador {
        String validar(String texto);
    }

    private final Consumer<String> aoConfirmar;
    private final Validador validador;
    private final JTextField campo;
    private final TextoCorrido erro = erroCorrido();
    private final Botao confirmar;
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private boolean decidiu;

    /**
     * @param inicial     o texto que já vem no campo (selecionado: o usuário digita por cima)
     * @param aoConfirmar recebe o texto (já validado); não é chamado se cancelar
     */
    public DialogoNome(String titulo, String icone, String pergunta, String dica, String inicial, String verbo, Validador validador,
                       Consumer<String> aoConfirmar) {
        this(titulo, icone, pergunta, dica, inicial, -1, verbo, validador, aoConfirmar);
    }

    /** @param fimDaSelecao quantos caracteres do começo ficam selecionados (o nome sem a extensão); {@code -1} seleciona tudo */
    public DialogoNome(String titulo, String icone, String pergunta, String dica, String inicial, int fimDaSelecao, String verbo,
                       Validador validador, Consumer<String> aoConfirmar) {
        super(titulo);
        this.aoConfirmar = aoConfirmar;
        this.validador = validador;
        this.campo = Campos.texto(34, dica);
        this.confirmar = Botoes.primario(verbo, null);
        campo.setText(inicial);
        campo.putClientProperty("html.disable", Boolean.TRUE);
        campo.getAccessibleContext().setAccessibleName(pergunta);
        JComponent corpo = Caixa.coluna(12, cabecalho(icone, titulo), new Rotulo(pergunta).corDoToken(br.com.labdesktop.ui.tema.Tokens.TEXTO2),
            campo, erro, rodape(null, cancelar, confirmar));
        conteudo(corpo);
        botaoPadrao(confirmar);
        campo.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                revalidar();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                revalidar();
            }

            @Override
            public void changedUpdate(DocumentEvent e) { }
        });
        confirmar.addActionListener(e -> confirmar());
        cancelar.addActionListener(e -> aoCancelar());
        if (fimDaSelecao >= 0 && fimDaSelecao <= inicial.length()) {
            campo.select(0, fimDaSelecao);
        } else {
            campo.selectAll();
        }
        revalidar();
    }

    private void revalidar() {
        String texto = campo.getText();
        String problema = texto.isBlank() ? "" : validador.validar(texto);
        confirmar.setEnabled(!texto.isBlank() && problema == null);
        dizerErro(erro, problema == null ? "" : problema);
        reajustar();
    }

    private void confirmar() {
        if (decidiu || !confirmar.isEnabled()) {
            return;
        }
        decidiu = true;
        String texto = campo.getText();
        fechar();
        aoConfirmar.accept(texto);
    }

    @Override
    protected void antesDeMostrar() {
        campo.requestFocusInWindow();
    }

    public JTextField campo() {
        return campo;
    }

    public Botao botaoConfirmar() {
        return confirmar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public String textoDoErro() {
        return erro.getText();
    }
}
