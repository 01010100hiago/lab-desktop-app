package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import br.com.labdesktop.ui.Visual;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPasswordField;
import javax.swing.text.Document;
import org.junit.jupiter.api.Test;

/** Apagar a senha de um campo de verdade: zera, esvazia e troca o documento (o buffer interno do anterior pode reter pedaços), mantendo quem o ouve. */
class CamposApagarSenhaTest {

    @Test
    void esvaziaTrocaODocumentoEMantemOsOuvintes() {
        JPasswordField c = Visual.naEdt(() -> Campos.senha(10));
        AtomicInteger avisos = new AtomicInteger();
        Visual.naEdt(() -> c.getDocument().addDocumentListener(new br.com.labdesktop.ui.dialogos.SimplesOuvinteDeDocumento(avisos::incrementAndGet)));
        Visual.naEdt(() -> c.setText("segredo"));
        Document antes = c.getDocument();
        Visual.naEdt(() -> Campos.apagarSenha(c));
        assertEquals(0, c.getPassword().length);
        assertNotSame(antes, c.getDocument(), "documento novo");
        int n = avisos.get();
        Visual.naEdt(() -> c.setText("x"));
        assertEquals(true, avisos.get() > n, "o ouvinte continua ligado ao documento novo");
    }
}
