package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar.Decisao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.accessibility.AccessibleRole;
import javax.swing.JCheckBox;
import javax.swing.KeyStroke;
import javax.swing.LayoutFocusTraversalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A caixa opcional "Não perguntar de novo" do {@link DialogoConfirmar}: desmarcada de partida, só vale quando o usuário CONFIRMA (cancelar pelo botão, por Esc ou
 * fechando a janela ignora a marca), alcançável pelo teclado, com nome para leitores de tela, e os usos antigos do diálogo seguem sem caixa.
 */
@ExtendWith(VerificaEdt.class)
class DialogoConfirmarNaoPerguntarTest {

    private static final String ROTULO = "Não perguntar de novo";
    private static final String DICA = "Para voltar a ser perguntado: Ajustes, Terminal.";

    private final List<Decisao> decisoes = new ArrayList<>();

    private DialogoConfirmar novo() {
        Visual.tema(Tema.CLARO);
        return Visual.naEdt(() -> DialogoConfirmar.comNaoPerguntarDeNovo("Sair de app-loja?", "alerta", "Este projeto tem um terminal aberto.", "Sair do projeto", true,
            ROTULO, DICA, decisoes::add));
    }

    private static void marcar(DialogoConfirmar d) {
        Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().doClick(0));
    }

    @Test
    void aCaixaNasceDesmarcadaComOTextoPedido() {
        DialogoConfirmar d = novo();
        JCheckBox caixa = d.caixaNaoPerguntarDeNovo();
        assertNotNull(caixa);
        assertFalse(Visual.naEdt(caixa::isSelected), "desmarcada por padrão");
        assertEquals(ROTULO, Visual.naEdt(caixa::getText));
        assertTrue(Visual.naEdt(caixa::isEnabled) && Visual.naEdt(caixa::isFocusable));
        assertTrue(decisoes.isEmpty(), "nada é decidido só de abrir");
    }

    @Test
    void osUsosAntigosDoDialogoContinuamSemCaixa() {
        Visual.tema(Tema.CLARO);
        List<Boolean> respostas = new ArrayList<>();
        DialogoConfirmar a = Visual.naEdt(() -> new DialogoConfirmar("Reabrir", "atualizar", "texto", "Reabrir", false, respostas::add));
        DialogoConfirmar b = Visual.naEdt(() -> new DialogoConfirmar("Salvar", "chave", "texto", "Salvar", false, true, respostas::add));
        assertNull(a.caixaNaoPerguntarDeNovo());
        assertNull(b.caixaNaoPerguntarDeNovo());
        assertTrue(contar(a.raiz(), JCheckBox.class) == 0 && contar(b.raiz(), JCheckBox.class) == 0, "nenhuma caixa na tela");
        Visual.naEdt(() -> a.botaoConfirmar().doClick(0));
        Visual.naEdt(() -> b.botaoCancelar().doClick(0));
        assertEquals(List.of(true, false), respostas);
    }

    @Test
    void marcadaEConfirmarEntregaAsDuasEscolhas() {
        DialogoConfirmar d = novo();
        marcar(d);
        Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
        assertEquals(List.of(new Decisao(true, true)), decisoes);
    }

    @Test
    void desmarcadaEConfirmarSoConfirma() {
        DialogoConfirmar d = novo();
        Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
        assertEquals(List.of(new Decisao(true, false)), decisoes);
    }

    @Test
    void marcadaEDepoisDesmarcadaNaoEntregaMarca() {
        DialogoConfirmar d = novo();
        marcar(d);
        marcar(d);
        Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
        assertEquals(List.of(new Decisao(true, false)), decisoes);
    }

    @Test
    void marcadaECancelarPeloBotaoIgnoraAMarca() {
        DialogoConfirmar d = novo();
        marcar(d);
        Visual.naEdt(() -> d.botaoCancelar().doClick(0));
        assertEquals(List.of(new Decisao(false, false)), decisoes);
    }

    @Test
    void marcadaEEscIgnoraAMarca() {
        DialogoConfirmar d = novo();
        marcar(d);
        Visual.naEdt(d::aoCancelar); // Esc e o botão de fechar da janela chamam isto
        assertEquals(List.of(new Decisao(false, false)), decisoes);
    }

    @Test
    void marcadaEFecharAJanelaIgnoraAMarca() {
        DialogoConfirmar d = novo();
        marcar(d);
        Visual.naEdt(d::marcarAberto); // como se tivesse sido mostrado
        Visual.naEdt(Dialogo::fecharTodosOsAbertos); // o app fecha os diálogos (bloqueio do cofre, sair)
        assertEquals(List.of(new Decisao(false, false)), decisoes);
    }

    @Test
    void depoisDeDecidirNadaMaisEhEntregue() {
        DialogoConfirmar d = novo();
        marcar(d);
        Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
        Visual.naEdt(() -> d.botaoCancelar().doClick(0));
        Visual.naEdt(d::aoCancelar);
        assertEquals(List.of(new Decisao(true, true)), decisoes, "uma decisão só");
    }

    @Test
    void oEnterContinuaCancelandoEACaixaNaoRoubaOEnter() {
        DialogoConfirmar d = novo();
        assertSame(d.botaoCancelar(), d.botaoDoEnter(), "o botão do Enter é Cancelar");
        JCheckBox caixa = d.caixaNaoPerguntarDeNovo();
        assertNull(Visual.naEdt(() -> caixa.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))),
            "a caixa não consome o Enter: ele segue para o botão padrão (Cancelar)");
        assertTrue(decisoes.isEmpty());
    }

    /**
     * O Swing sem janela não entrega Tab de verdade (nada é "exibido", então a política de foco não aceita ninguém); a prova é pela geometria, que é a ordem da
     * política padrão da janela (de cima para baixo, da esquerda para a direita), e por não haver política própria que a mude: a caixa vem antes de Cancelar e de Sair do projeto.
     */
    @Test
    void naOrdemDoTecladoACaixaEhAPrimeiraEDepoisVemCancelarEConfirmar() {
        DialogoConfirmar d = novo();
        Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560) + 20);
        assertFalse(Visual.naEdt(d.raiz()::isFocusTraversalPolicySet), "sem política de foco própria: vale a padrão da janela, em ordem de leitura");
        List<Component> focaveis = Visual.naEdt(() -> {
            List<Component> r = new ArrayList<>();
            for (Component c : todos(d.raiz())) {
                if (c.isVisible() && c.isFocusable() && c.isEnabled() && (c instanceof javax.swing.AbstractButton || c instanceof javax.swing.text.JTextComponent)) {
                    r.add(c);
                }
            }
            r.sort(java.util.Comparator.<Component>comparingInt(c -> javax.swing.SwingUtilities.convertPoint(c, 0, 0, d.raiz()).y)
                .thenComparingInt(c -> javax.swing.SwingUtilities.convertPoint(c, 0, 0, d.raiz()).x));
            return r;
        });
        int caixa = focaveis.indexOf(d.caixaNaoPerguntarDeNovo());
        assertTrue(caixa >= 0, "a caixa entra na ordem do teclado");
        assertEquals(List.of(d.caixaNaoPerguntarDeNovo(), d.botaoCancelar(), d.botaoConfirmar()), focaveis.subList(caixa, focaveis.size()),
            "Tab: a caixa, depois Cancelar, depois Sair do projeto, e mais nada no meio (nem a dica)");
        assertTrue(focaveis.subList(0, caixa).stream().allMatch(c -> c instanceof br.com.labdesktop.ui.componentes.TextoCorrido t && t.getText().startsWith("Este projeto")),
            "antes da caixa só o texto da pergunta (que se pode selecionar e copiar, como em todo diálogo do app)");
    }

    @Test
    void aCaixaFicaAbaixoDoTextoEAcimaDosBotoes() {
        DialogoConfirmar d = novo();
        Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560) + 20);
        int yCaixa = Visual.naEdt(() -> javax.swing.SwingUtilities.convertPoint(d.caixaNaoPerguntarDeNovo(), 0, 0, d.raiz()).y);
        int yBotoes = Visual.naEdt(() -> javax.swing.SwingUtilities.convertPoint(d.botaoCancelar(), 0, 0, d.raiz()).y);
        int yTexto = Visual.naEdt(() -> {
            for (Component c : todos(d.raiz())) {
                if (c instanceof br.com.labdesktop.ui.componentes.TextoCorrido t && t.getText().startsWith("Este projeto")) {
                    return javax.swing.SwingUtilities.convertPoint(t, 0, 0, d.raiz()).y;
                }
            }
            throw new AssertionError("sem o texto da pergunta");
        });
        assertTrue(yTexto < yCaixa, "caixa abaixo do texto: " + yTexto + " < " + yCaixa);
        assertTrue(yCaixa < yBotoes, "caixa acima dos botões: " + yCaixa + " < " + yBotoes);
    }

    @Test
    void oEspacoMarcaEDesmarcaACaixaSemMouse() {
        DialogoConfirmar d = novo();
        JCheckBox caixa = d.caixaNaoPerguntarDeNovo();
        apertarEspaco(caixa);
        assertTrue(Visual.naEdt(caixa::isSelected), "Espaço marca");
        apertarEspaco(caixa);
        assertFalse(Visual.naEdt(caixa::isSelected), "Espaço de novo desmarca");
        apertarEspaco(caixa);
        Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
        assertEquals(List.of(new Decisao(true, true)), decisoes, "o que o teclado marcou vale ao confirmar");
    }

    @Test
    void aCaixaTemNomeEDescricaoParaLeitoresDeTela() {
        DialogoConfirmar d = novo();
        var acessivel = Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().getAccessibleContext());
        assertEquals(ROTULO, acessivel.getAccessibleName());
        assertEquals(DICA, acessivel.getAccessibleDescription(), "diz onde desfazer");
        assertEquals(AccessibleRole.CHECK_BOX, acessivel.getAccessibleRole());
    }

    @Test
    void aFonteDaCaixaENaDicaNaoFicaAbaixoDoMinimoDoApp() {
        DialogoConfirmar d = novo();
        Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560) + 20);
        float minimo = br.com.labdesktop.ui.tema.Fontes.MINIMO;
        assertTrue(Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().getFont().getSize2D()) >= minimo);
        Visual.naEdt(() -> {
            for (Component c : todos(d.raiz())) {
                if (c instanceof br.com.labdesktop.ui.componentes.TextoCorrido t && !t.getText().isEmpty()) {
                    assertTrue(t.getFont().getSize2D() >= minimo, "'" + t.getText() + "' com " + t.getFont().getSize2D());
                }
            }
        });
    }

    @Test
    void semDicaACaixaSaiSozinhaESemTextoNaoExiste() {
        Visual.tema(Tema.CLARO);
        DialogoConfirmar d = Visual.naEdt(() -> DialogoConfirmar.comNaoPerguntarDeNovo("t", "alerta", "x", "Ok", false, ROTULO, null, decisoes::add));
        assertNull(Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().getAccessibleContext().getAccessibleDescription()));
        assertThrows(IllegalArgumentException.class, () -> DialogoConfirmar.comNaoPerguntarDeNovo("t", "alerta", "x", "Ok", false, " ", null, decisoes::add));
    }

    @Test
    void osPngsDaCaixaDesmarcadaEMarcadaNosDoisTemas() {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoConfirmar d = Visual.naEdt(() -> DialogoConfirmar.comNaoPerguntarDeNovo("Sair de app-loja?", "alerta",
                "Este projeto tem um terminal aberto. Se sair da página dele agora, o terminal fecha e o que estiver rodando nele para. A conversa do Claude continua no servidor.",
                "Sair do projeto", true, ROTULO, DICA, decisoes::add));
            int w = Math.max(560, Visual.naEdt(() -> d.raiz().getPreferredSize().width));
            int h = Visual.alturaPreferida(d.raiz(), w) + 20;
            Visual.salvar(Visual.renderizar(d.raiz(), w, h), "pacote-t-sair-do-projeto-desmarcada-" + tema.name().toLowerCase());
            marcar(d);
            Visual.salvar(Visual.renderizar(d.raiz(), w, h), "pacote-t-sair-do-projeto-marcada-" + tema.name().toLowerCase());
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Aperta e solta o Espaço pelo que o Swing registrou para a caixa em foco (o mapa de teclas dela): entregar o evento de teclado de verdade pede foco, que o Swing sem janela
     * não tem. Também prova que Espaço É a tecla ligada à caixa.
     */
    private static void apertarEspaco(JCheckBox caixa) {
        Visual.naEdt(() -> {
            long t = System.currentTimeMillis();
            for (boolean solta : new boolean[] {false, true}) {
                KeyStroke tecla = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, solta);
                Object nome = caixa.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(tecla);
                assertNotNull(nome, "Espaço " + (solta ? "solto" : "apertado") + " está ligado à caixa");
                javax.swing.Action acao = caixa.getActionMap().get(nome);
                assertTrue(javax.swing.SwingUtilities.notifyAction(acao, tecla,
                    new KeyEvent(caixa, solta ? KeyEvent.KEY_RELEASED : KeyEvent.KEY_PRESSED, t, 0, KeyEvent.VK_SPACE, ' '), caixa, 0));
            }
        });
    }

    private static List<Component> todos(Component raiz) {
        List<Component> r = new ArrayList<>();
        r.add(raiz);
        if (raiz instanceof Container k) {
            for (Component f : k.getComponents()) {
                r.addAll(todos(f));
            }
        }
        return r;
    }

    private static int contar(Component raiz, Class<?> tipo) {
        return (int) Visual.naEdt(() -> todos(raiz).stream().filter(tipo::isInstance).count()).longValue();
    }
}
