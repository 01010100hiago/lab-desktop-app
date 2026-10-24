package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** O diálogo do código do autenticador: seis dígitos (com o espaço do meio), aviso e contagem de "Aguarde N s". */
@ExtendWith(VerificaEdt.class)
class DialogoCodigo2faTest {

    private final List<Optional<String>> decisoes = new ArrayList<>();

    private DialogoCodigo2fa dialogo(String aviso, int aguarde) {
        return Visual.naEdt(() -> new DialogoCodigo2fa("lab", aviso, aguarde, decisoes::add));
    }

    private static void digitar(DialogoCodigo2fa d, String texto) {
        Visual.naEdt(() -> d.campo().setText(texto));
    }

    @Test
    void soHabilitaComSeisDigitosEAceitaOEspacoDoMeio() {
        Visual.tema(Tema.CLARO);
        DialogoCodigo2fa d = dialogo(null, 0);
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        digitar(d, "12345");
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "5 dígitos não bastam");
        digitar(d, "123 456");
        assertTrue(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "o Google Authenticator mostra '123 456'");
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        assertEquals(List.of(Optional.of("123456")), decisoes, "devolve os seis dígitos, sem o espaço");
        assertEquals("", Visual.naEdt(() -> d.campo().getText()), "o código não fica no campo");
    }

    @Test
    void oCampoDescartaLetrasEPasseDe7Caracteres() {
        Visual.tema(Tema.CLARO);
        DialogoCodigo2fa d = dialogo(null, 0);
        digitar(d, "12a3b4 56c789");
        assertEquals("1234 56", Visual.naEdt(() -> d.campo().getText()), "só dígitos e espaço, até 7 caracteres");
    }

    @Test
    void confirmarSoAcontecePorUmaVezECancelarEntregaVazio() {
        Visual.tema(Tema.CLARO);
        DialogoCodigo2fa d = dialogo(null, 0);
        digitar(d, "123456");
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertEquals(1, decisoes.size(), "uma decisão só");

        DialogoCodigo2fa outro = dialogo(null, 0);
        Visual.naEdt(() -> outro.botaoCancelar().doClick());
        assertEquals(Optional.empty(), decisoes.get(1));
    }

    @Test
    void oAvisoDeCodigoIncorretoAparece() {
        Visual.tema(Tema.CLARO);
        DialogoCodigo2fa d = dialogo("Código incorreto ou já usado.", 0);
        assertEquals("Código incorreto ou já usado.", Visual.naEdt(d::textoDoAviso));
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoCodigo2fa visual = dialogo("Código incorreto ou já usado.", 0);
            digitar(visual, "123 456");
            BufferedImage img = Visual.renderizar(visual.raiz(), 520, Visual.alturaPreferida(visual.raiz(), 520));
            Visual.salvar(img, "dialogo-codigo-2fa-" + tema.name().toLowerCase());
        }
    }

    @Test
    void depoisDe5ErrosOAgenteBloqueiaEOBotaoFicaDesabilitadoAteAcabarAContagem() {
        Visual.tema(Tema.CLARO);
        DialogoCodigo2fa d = dialogo("Código incorreto ou já usado.", 2);
        digitar(d, "123456");
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "bloqueado: nem com o código certo");
        assertTrue(Visual.naEdt(d::textoDaEspera).contains("Aguarde 2 s"), Visual.naEdt(d::textoDaEspera));
        Espera.ate(Duration.ofSeconds(5), () -> "o botão voltar a habilitar", () -> Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        assertEquals("", Visual.naEdt(d::textoDaEspera));
    }
}
