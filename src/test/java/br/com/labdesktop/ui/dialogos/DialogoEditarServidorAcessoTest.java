package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.servidor.GerenciadorServidores.ResultadoDaEdicao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** "Editar servidor": a dica simples do botão Salvar, a seção "Acesso" (e a sua ausência) e o botão que só vale sem mudança pendente. */
@ExtendWith(VerificaEdt.class)
class DialogoEditarServidorAcessoTest {

    private static final ConfigServidor CFG = ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", "k1");
    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("teste");
    private final AtomicInteger gravacoes = new AtomicInteger();

    private DialogoEditarServidor comAcesso(Runnable aoInstalar) {
        return Visual.naEdt(() -> new DialogoEditarServidor(CFG, dados -> {
            gravacoes.incrementAndGet();
            return new ResultadoDaEdicao(CFG, false, false);
        }, avisos, new DialogoEditarServidor.Acesso("lab", "SHA256:5MGLihSpPv/h6+y5NnTZfr2nnOaG8vJ9pEy1z3+jIQg", "ssh-ed25519 AAAAexemplo lab", aoInstalar),
            new CopiadorDeTexto(() -> area)));
    }

    @Test
    void semAcessoAsecaoNaoApareceEOEditarDeSempreContinua() {
        DialogoEditarServidor d = Visual.naEdt(() -> new DialogoEditarServidor(CFG, dados -> null, avisos));
        assertFalse(Visual.naEdt(d::mostraAAcesso));
        assertFalse(Visual.naEdt(() -> d.botaoInstalarChave().isVisible()));
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
    }

    @Test
    void aDicaDoSalvarFalaEmTextoSimplesEDesapareceQuandoHaMudanca() {
        DialogoEditarServidor d = comAcesso(() -> { });
        assertEquals("O botão Salvar e reconectar só fica ativo depois que você muda algum campo.", Visual.naEdt(d::textoDaDicaDoSalvar));
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
        Visual.naEdt(() -> d.campoDoNome().setText("lab 2"));
        assertTrue(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
        assertEquals("", Visual.naEdt(d::textoDaDicaDoSalvar));
        Visual.naEdt(() -> d.campoDoNome().setText("lab")); // voltou ao que era: nada a salvar
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
        assertEquals(DialogoEditarServidor.DICA_DO_SALVAR, Visual.naEdt(d::textoDaDicaDoSalvar));
    }

    @Test
    void clicarEmSalvarSemMudarNadaNaoGravaNada() {
        DialogoEditarServidor d = comAcesso(() -> { });
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Visual.naEdt(() -> d.botaoDoEnter().doClick());
        assertEquals(0, gravacoes.get(), "sem mudança o Salvar nem grava");
    }

    @Test
    void copiarAChavePublicaEOBotaoDeInstalarSoValeSemMudancaPendente() throws Exception {
        AtomicInteger instalar = new AtomicInteger();
        DialogoEditarServidor d = comAcesso(instalar::incrementAndGet);
        Visual.naEdt(() -> d.botaoCopiarChave().doClick());
        assertEquals("ssh-ed25519 AAAAexemplo lab", area.getData(DataFlavor.stringFlavor));
        Visual.naEdt(() -> d.campoDoUsuario().setText("outro"));
        assertFalse(Visual.naEdt(() -> d.botaoInstalarChave().isEnabled()));
        Visual.naEdt(() -> d.botaoInstalarChave().doClick());
        assertEquals(0, instalar.get(), "com mudança pendente o botão não vale");
        Visual.naEdt(() -> d.campoDoUsuario().setText("labuser"));
        Visual.naEdt(() -> d.botaoInstalarChave().doClick());
        assertEquals(1, instalar.get());
    }
}
