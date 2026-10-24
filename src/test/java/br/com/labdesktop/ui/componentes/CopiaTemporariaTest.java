package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.teste.AgendadorFalso;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A chave do 2FA copiada não pode ficar na área de transferência (Raycast, Maccy e a Área de Transferência Universal
 * guardam o histórico): sai em 30 s e ao fechar, e nunca leva junto o que o usuário copiou depois.
 */
class CopiaTemporariaTest {

    private static final String SEGREDO = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private final Clipboard area = new Clipboard("teste");
    private final AgendadorFalso relogio = new AgendadorFalso();
    private final CopiaTemporaria copia = new CopiaTemporaria(() -> area, relogio, Duration.ofSeconds(30));

    /**
     * O {@code Fundo.naEdt} é inline sem ninguém ter ligado a EDT e vai para a EDT depois que um teste de tela a ligou
     * (a ligação é global na JVM de teste). Esvaziar a fila da EDT torna os dois casos iguais.
     */
    private static void esvaziarEdt() throws Exception {
        java.awt.EventQueue.invokeAndWait(() -> { });
    }

    private void avancar(Duration d) throws Exception {
        relogio.avancar(d);
        esvaziarEdt();
    }

    private String naArea() throws Exception {
        var t = area.getContents(null);
        if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return null;
        }
        return (String) t.getTransferData(DataFlavor.stringFlavor);
    }

    @Test
    void copiaEDepoisDe30SegundosATiraDeLa() throws Exception {
        assertTrue(copia.copiar(SEGREDO));
        assertEquals(SEGREDO, naArea());
        assertTrue(copia.temCopiaPendente());

        avancar(Duration.ofSeconds(29));
        assertEquals(SEGREDO, naArea(), "ainda no prazo");

        avancar(Duration.ofSeconds(2));
        assertEquals("", naArea(), "passou o prazo: a área de transferência fica vazia");
        assertFalse(copia.temCopiaPendente());
    }

    @Test
    void fecharOuConfirmarLimpaNaHoraELimpezaAgendadaNaoLimpaDeNovo() throws Exception {
        copia.copiar(SEGREDO);
        copia.limparAgora();
        esvaziarEdt();
        assertEquals("", naArea());

        // o usuário copiou outra coisa DEPOIS da limpeza: o timer velho não pode levar isso embora
        area.setContents(new StringSelection("meu texto"), null);
        avancar(Duration.ofMinutes(5));
        assertEquals("meu texto", naArea(), "o timer foi cancelado junto com a limpeza");
    }

    @Test
    void naoApagaOQueODonoCopiouDepois() throws Exception {
        copia.copiar(SEGREDO);
        area.setContents(new StringSelection("outra coisa que o usuário copiou"), null);

        avancar(Duration.ofSeconds(31));
        assertEquals("outra coisa que o usuário copiou", naArea());
        copia.limparAgora();
        assertEquals("outra coisa que o usuário copiou", naArea(), "fechar o diálogo também respeita o que é do usuário");
    }

    @Test
    void avisoDePosseTiraAPosseEImpedeALimpezaMesmoComOMesmoTexto() throws Exception {
        copia.copiar(SEGREDO);
        var posto = area.getContents(null);
        // outro programa copiou o MESMO texto: o conteúdo igual não basta para apagar, o que vale é quem é o usuário
        area.setContents(new StringSelection(SEGREDO), null);
        copia.lostOwnership(area, posto); // o que o Clipboard faz, de forma assíncrona, com o conteúdo que perdeu a posse
        esvaziarEdt();

        assertFalse(copia.temCopiaPendente());
        avancar(Duration.ofSeconds(31));
        assertEquals(SEGREDO, naArea(), "era do usuário (outra cópia do mesmo texto): não se apaga");
    }

    @Test
    void avisoAtrasadoDeUmaCopiaAntigaNaoTiraAPosseDaCopiaNova() throws Exception {
        copia.copiar(SEGREDO);
        var antigo = area.getContents(null);
        copia.copiar(SEGREDO); // o usuário clicou Copiar de novo
        copia.lostOwnership(area, antigo); // o aviso da primeira só chega agora
        esvaziarEdt();

        assertTrue(copia.temCopiaPendente(), "a cópia nova continua sendo nossa");
        avancar(Duration.ofSeconds(31));
        assertEquals("", naArea());
    }

    @Test
    void umaSegundaCopiaReiniciaOPrazo() throws Exception {
        copia.copiar(SEGREDO);
        avancar(Duration.ofSeconds(20));
        copia.copiar(SEGREDO);
        avancar(Duration.ofSeconds(20));
        assertEquals(SEGREDO, naArea(), "40 s depois da primeira, mas só 20 s depois da segunda");
        avancar(Duration.ofSeconds(11));
        assertEquals("", naArea());
    }

    @Test
    void semNadaCopiadoNaoTocaNaAreaDeTransferencia() {
        CopiaTemporaria sem = new CopiaTemporaria(() -> {
            throw new AssertionError("a área de transferência do sistema não deveria ser tocada");
        }, relogio, Duration.ofSeconds(30));
        sem.limparAgora();
        sem.limparAgora();
        assertFalse(sem.temCopiaPendente());
    }

    @Test
    void areaDeTransferenciaIndisponivelDevolveFalsoENaoDeixaNada() {
        CopiaTemporaria quebrada = new CopiaTemporaria(() -> new Clipboard("quebrada") {
            @Override
            public synchronized void setContents(java.awt.datatransfer.Transferable contents,
                                                 java.awt.datatransfer.ClipboardOwner owner) {
                throw new IllegalStateException("ocupada");
            }
        }, relogio, Duration.ofSeconds(30));
        assertFalse(quebrada.copiar(SEGREDO));
        assertFalse(quebrada.temCopiaPendente());
        quebrada.limparAgora();
    }
}
