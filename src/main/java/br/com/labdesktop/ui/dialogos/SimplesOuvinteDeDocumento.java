package br.com.labdesktop.ui.dialogos;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Um {@link DocumentListener} que roda a mesma ação para inserção, remoção e mudança de atributo. */
public final class SimplesOuvinteDeDocumento implements DocumentListener {

    private final Runnable acao;

    public SimplesOuvinteDeDocumento(Runnable acao) {
        this.acao = acao;
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
        acao.run();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        acao.run();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        acao.run();
    }
}
