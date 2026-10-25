package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** O botão Copiar com "Copiado" e a seção que abre e fecha: os dois pedaços novos do assistente. */
@ExtendWith(VerificaEdt.class)
class ComponentesDeCopiaEDetalhesTest {

    private final AvisosGravados avisos = new AvisosGravados();

    @Test
    void copiarPoeOTextoNaAreaDeTransferenciaDizCopiadoEVoltaAoRotulo() throws Exception {
        Visual.tema(Tema.CLARO);
        Clipboard area = new Clipboard("teste");
        String[] texto = {"primeiro"};
        Botao b = Visual.naEdt(() -> BotaoCopiar.criar("Copiar", () -> texto[0], new CopiadorDeTexto(() -> area), avisos));
        assertEquals("Copiar", Visual.naEdt(b::getText));

        Visual.naEdt(() -> b.doClick());
        assertEquals("primeiro", area.getData(DataFlavor.stringFlavor));
        assertEquals("Copiado", Visual.naEdt(b::getText));
        assertTrue(avisos.disse("info", "Copiado"));

        texto[0] = "o texto é pedido a cada clique";
        Visual.naEdt(() -> b.doClick());
        assertEquals("o texto é pedido a cada clique", area.getData(DataFlavor.stringFlavor));
        Espera.ate(Duration.ofSeconds(5), "o botão volta a dizer Copiar", () -> "Copiar".equals(Visual.naEdt(b::getText)));
    }

    @Test
    void seAAreaDeTransferenciaNaoDeixarAvisaQueNaoCopiouEONaoDizCopiado() {
        Visual.tema(Tema.CLARO);
        AtomicInteger tentativas = new AtomicInteger();
        Botao b = Visual.naEdt(() -> BotaoCopiar.criar("Copiar", () -> "x", new CopiadorDeTexto(() -> {
            tentativas.incrementAndGet();
            throw new java.awt.HeadlessException();
        }), avisos));

        Visual.naEdt(() -> b.doClick());

        assertEquals(1, tentativas.get());
        assertEquals("Copiar", Visual.naEdt(b::getText), "não mente: nada foi copiado");
        assertTrue(avisos.disse("atencao", "Não consegui copiar"));
        assertFalse(avisos.disse("info", "Copiado"));
    }

    @Test
    void aSecaoComecaFechadaAbreEFechaEAvisaAQuemUsa() {
        Visual.tema(Tema.CLARO);
        AtomicInteger mudancas = new AtomicInteger();
        Rotulo conteudo = Visual.naEdt(() -> new Rotulo("detalhes"));
        SecaoRecolhivel s = Visual.naEdt(() -> new SecaoRecolhivel("Ver comando", "Esconder o comando", conteudo, mudancas::incrementAndGet));
        assertFalse(Visual.naEdt(s::aberta));
        assertFalse(Visual.naEdt(() -> conteudo.getParent().isVisible()), "o conteúdo começa escondido");
        assertEquals("Ver comando", Visual.naEdt(() -> s.botao().getText()));

        Visual.naEdt(() -> s.botao().doClick());
        assertTrue(Visual.naEdt(s::aberta));
        assertTrue(Visual.naEdt(() -> conteudo.getParent().isVisible()));
        assertEquals("Esconder o comando", Visual.naEdt(() -> s.botao().getText()));
        assertEquals(1, mudancas.get());

        Visual.naEdt(() -> s.definirAberta(true));
        assertEquals(1, mudancas.get(), "pedir o estado que já está não avisa de novo");
        Visual.naEdt(() -> s.botao().doClick());
        assertFalse(Visual.naEdt(() -> conteudo.getParent().isVisible()));
        assertEquals(2, mudancas.get());
    }
}
