package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.ui.AjustesDoApp;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.swing.JCheckBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A caixa "Não perguntar de novo" da pergunta "Sair do projeto?" com servidor VIVO e terminal de verdade: a primeira saída pergunta; confirmar com a caixa marcada grava
 * e a segunda saída vai direto (com o aviso "o terminal foi fechado"); sem marcar continua perguntando; cancelar nunca grava; com a preferência já desligada nem se abre o
 * diálogo, por nenhum caminho de saída (atalhos Cmd+1 a 9, link da Ajuda, "‹ Projetos", Esc, outro projeto); e o item de Ajustes, Terminal, desfaz.
 */
@ExtendWith(VerificaEdt.class)
class NaoPerguntarDeNovoAoSairDoProjetoTest {

    private static final String AVISO_DO_ITEM = "Perguntar antes de sair de um projeto com terminal aberto";

    @TempDir
    Path pasta;

    private CenarioDeUi cenario;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private PainelPrincipal painel;
    private PreferenciasAtuais preferencias;
    private CaminhosApp caminhos;
    private TelaAjustes ajustes;
    private String acoesAntes;
    private final Clipboard area = new Clipboard("teste");

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        acoesAntes = System.getProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true");
        cenario = new CenarioDeUi();
        cenario.agente.detalhes.put("app-loja", MocksDoFront.ler("projetos/app-loja.json"));
        cenario.agente.detalhes.put("fit-app", MocksDoFront.ler("projetos/fit-app.json"));
        caminhos = CaminhosApp.comPasta(pasta);
        preferencias = new PreferenciasAtuais(caminhos, Preferencias.padrao());
        GerenciadorTema tema = new GerenciadorTema(Tema.CLARO, false);
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, dialogos, new NavegadorGravado(avisos), ServicosDeArquivos.descartaveis(),
            preferencias::get, preferencias::atualizar));
        Visual.naEdt(() -> painel.instalarAjustes(() -> {
            ajustes = new TelaAjustes(new AjustesDoApp(tema, preferencias, painel, new CopiadorDeTexto(() -> area), avisos));
            return ajustes;
        }));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(painel::encerrar);
        cenario.close();
        if (acoesAntes == null) {
            System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        } else {
            System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, acoesAntes);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------------------------------

    private TelaServidor servidor() {
        return Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
    }

    private TelaProjeto projeto() {
        return Visual.naEdt(() -> servidor().projetoAberto());
    }

    /** Abre a página do projeto e a aba Terminal e espera o terminal de verdade subir (é isso que torna a saída "com terminal aberto"). */
    private TelaProjeto abrirComTerminalVivo(String nome) {
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, nome));
        TelaProjeto t = projeto();
        Visual.naEdt(t::assinar);
        Visual.naEdt(() -> t.abas().botao("terminal").doClick());
        Visual.naEdt(() -> t.terminal().assinar());
        Visual.renderizar(servidor().projetoAberto(), 1100, 700);
        Espera.ate(Duration.ofSeconds(20), "o terminal da aba abrir", () -> Visual.naEdt(t::temTerminalVivo));
        return t;
    }

    private DialogoConfirmar perguntou() {
        return dialogos.ultimoComo(DialogoConfirmar.class);
    }

    private void esperarGravado(java.util.function.Predicate<Preferencias> condicao) {
        Espera.ate(Duration.ofSeconds(10), "preferências no disco", () -> condicao.test(Preferencias.carregar(caminhos)));
    }

    private JCheckBox itemDeAjustes() {
        Visual.naEdt(() -> painel.mostrarAjustes(Secao.TERMINAL));
        return Visual.naEdt(() -> achar(ajustes, AVISO_DO_ITEM));
    }

    private static JCheckBox achar(Component raiz, String texto) {
        if (raiz instanceof JCheckBox c && c.getText().equals(texto)) {
            return c;
        }
        if (raiz instanceof Container k) {
            for (Component f : k.getComponents()) {
                JCheckBox r = achar(f, texto);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private boolean avisouDoTerminalFechado(String projeto) {
        return avisos.disse("info", "O terminal do projeto " + projeto + " foi fechado");
    }

    // ---------------------------------------------------------------------------------------------
    // A caixa, ponta a ponta
    // ---------------------------------------------------------------------------------------------

    @Test
    void confirmarComACaixaMarcadaGravaEASegundaSaidaVaiDiretoComAviso() {
        assertTrue(preferencias.get().perguntarAoSairDoProjetoComTerminal(), "de fábrica o app pergunta");
        TelaProjeto t = abrirComTerminalVivo("app-loja");

        Visual.naEdt(() -> painel.mostrarAbaNumero(1)); // Cmd+1: sai do projeto
        assertEquals(1, dialogos.mostrados.size(), "a primeira saída pergunta");
        DialogoConfirmar d = perguntou();
        assertFalse(Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().isSelected()), "a caixa vem desmarcada");
        assertSame(t, projeto(), "enquanto não responde, tudo continua");
        Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().doClick(0));
        Visual.naEdt(() -> d.botaoConfirmar().doClick(0));

        assertNull(projeto(), "confirmou: saiu");
        assertFalse(Visual.naEdt(t::temTerminalVivo), "e o terminal fechou");
        assertFalse(preferencias.get().perguntarAoSairDoProjetoComTerminal(), "a escolha ficou guardada na memória");
        esperarGravado(p -> !p.perguntarAoSairDoProjetoComTerminal());
        assertFalse(avisouDoTerminalFechado("app-loja"), "quem acabou de confirmar a pergunta não precisa do aviso de novo");

        TelaProjeto t2 = abrirComTerminalVivo("app-loja");
        Visual.naEdt(() -> painel.irParaAba(TelaServidor.Aba.RESUMO)); // o link da Ajuda
        assertEquals(1, dialogos.mostrados.size(), "a segunda saída NÃO pergunta");
        assertNull(projeto(), "e saiu mesmo");
        assertFalse(Visual.naEdt(t2::temTerminalVivo), "o terminal fechou");
        assertTrue(avisouDoTerminalFechado("app-loja"), "o app continua honesto: avisa que o terminal foi fechado " + avisos.textos());
        assertEquals(1, avisos.todos.stream().filter(a -> a.texto().contains("foi fechado")).count(), "um aviso só");
    }

    @Test
    void confirmarSemMarcarContinuaPerguntandoNasProximasSaidas() {
        abrirComTerminalVivo("app-loja");
        Visual.naEdt(() -> painel.mostrarAbaNumero(1));
        Visual.naEdt(() -> perguntou().botaoConfirmar().doClick(0));
        assertNull(projeto());
        assertTrue(preferencias.get().perguntarAoSairDoProjetoComTerminal());
        assertFalse(Files.exists(caminhos.arquivoPreferencias()), "nada foi gravado");

        abrirComTerminalVivo("app-loja");
        Visual.naEdt(() -> painel.mostrarAbaNumero(1));
        assertEquals(2, dialogos.mostrados.size(), "perguntou de novo");
        assertFalse(avisouDoTerminalFechado("app-loja"));
    }

    @Test
    void cancelarComACaixaMarcadaNuncaGravaNada() {
        TelaProjeto t = abrirComTerminalVivo("app-loja");
        Visual.naEdt(() -> painel.mostrarAbaNumero(1));
        DialogoConfirmar a = perguntou();
        Visual.naEdt(() -> a.caixaNaoPerguntarDeNovo().doClick(0));
        Visual.naEdt(() -> a.botaoCancelar().doClick(0));
        Visual.naEdt(() -> painel.irParaAba(TelaServidor.Aba.RESUMO)); // outro caminho de saída (o link da Ajuda), marcando de novo
        DialogoConfirmar b = perguntou();
        Visual.naEdt(() -> b.caixaNaoPerguntarDeNovo().doClick(0));
        Visual.naEdt(() -> b.botaoCancelar().doClick(0));
        // (Esc e o botão de fechar da janela passam pelo mesmo caminho do Cancelar: provados em DialogoConfirmarNaoPerguntarTest)

        assertEquals(2, dialogos.mostrados.size());
        assertSame(t, projeto(), "cancelar mantém a página");
        assertTrue(Visual.naEdt(t::temTerminalVivo), "e o terminal vivo");
        assertTrue(preferencias.get().perguntarAoSairDoProjetoComTerminal(), "a marca de quem cancelou é ignorada");
        // dá tempo de uma gravação em segundo plano (que não deveria existir) aparecer
        Espera.dormir(400);
        assertFalse(Files.exists(caminhos.arquivoPreferencias()), "cancelar nunca grava preferência");
        Visual.naEdt(() -> painel.mostrarAbaNumero(1));
        assertEquals(3, dialogos.mostrados.size(), "e a próxima saída pergunta");
    }

    @Test
    void comAPerguntaJaDesligadaNenhumCaminhoDeSaidaAbreODialogo() {
        preferencias.atualizar(p -> p.comPerguntarAoSairDoProjetoComTerminal(false));

        // Cmd+1 a 9 (mostrarAbaNumero)
        TelaProjeto t = abrirComTerminalVivo("app-loja");
        Visual.naEdt(() -> painel.mostrarAbaNumero(2));
        assertNull(projeto(), "Cmd+2 saiu direto");
        assertFalse(Visual.naEdt(t::temTerminalVivo));
        assertEquals(Visual.naEdt(() -> servidor().abasVisiveis().get(1)), Visual.naEdt(servidor()::abaAtual), "foi para a aba pedida");

        // "‹ Projetos"
        TelaProjeto t2 = abrirComTerminalVivo("app-loja");
        Visual.naEdt(painel::mostrarProjetos);
        assertNull(projeto(), "‹ Projetos saiu direto");
        assertEquals(TelaServidor.Aba.PROJETOS, Visual.naEdt(servidor()::abaAtual));
        assertFalse(Visual.naEdt(t2::temTerminalVivo));

        // o botão ‹ Projetos da própria página e o Esc dela
        abrirComTerminalVivo("app-loja");
        Visual.naEdt(() -> servidor().voltarParaALista());
        assertNull(projeto(), "voltar para a lista saiu direto");

        // abrir OUTRO projeto (o "Último projeto aberto")
        TelaProjeto t3 = abrirComTerminalVivo("app-loja");
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "fit-app"));
        assertEquals("fit-app", Visual.naEdt(projeto()::projeto), "trocou de projeto direto");
        assertFalse(Visual.naEdt(t3::temTerminalVivo), "o terminal do app-loja fechou");

        assertTrue(dialogos.mostrados.isEmpty(), "nem um diálogo aberto: " + dialogos.mostrados.size());
        assertEquals(4, avisos.todos.stream().filter(a -> a.texto().equals("O terminal do projeto app-loja foi fechado.")).count(),
            "um aviso por saída, cada um com o nome do projeto que fechou: " + avisos.textos());
    }

    @Test
    void oItemDeAjustesMostraAPreferenciaELigaDeNovoAPergunta() {
        JCheckBox item = itemDeAjustes();
        assertNotNull(item, "Ajustes, Terminal tem o item para desfazer");
        assertTrue(Visual.naEdt(item::isSelected), "ligado de fábrica");

        // a caixa do diálogo desliga...
        TelaProjeto t = abrirComTerminalVivo("app-loja");
        Visual.naEdt(() -> painel.mostrarAbaNumero(1));
        DialogoConfirmar d = perguntou();
        Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().doClick(0));
        Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
        assertNull(projeto());
        esperarGravado(p -> !p.perguntarAoSairDoProjetoComTerminal());

        // ... Ajustes mostra desligado e (o usuário muda de ideia) liga de novo
        JCheckBox item2 = itemDeAjustes();
        assertFalse(Visual.naEdt(item2::isSelected), "o item reflete o que a caixa gravou");
        Visual.naEdt(() -> item2.doClick());
        assertTrue(preferencias.get().perguntarAoSairDoProjetoComTerminal());
        esperarGravado(Preferencias::perguntarAoSairDoProjetoComTerminal);

        TelaProjeto t2 = abrirComTerminalVivo("app-loja");
        int antes = dialogos.mostrados.size();
        Visual.naEdt(() -> painel.mostrarAbaNumero(1));
        assertEquals(antes + 1, dialogos.mostrados.size(), "voltou a perguntar");
        assertSame(t2, projeto());
        Visual.naEdt(() -> perguntou().botaoCancelar().doClick(0));
        assertNotNull(t);

        // e o item também desliga (sem passar pelo diálogo)
        Visual.naEdt(() -> itemDeAjustes().doClick());
        assertFalse(preferencias.get().perguntarAoSairDoProjetoComTerminal());
        esperarGravado(p -> !p.perguntarAoSairDoProjetoComTerminal());
        Visual.naEdt(() -> painel.irParaAba(TelaServidor.Aba.RESUMO));
        assertEquals(antes + 1, dialogos.mostrados.size(), "desligado pelo item: sai direto");
        assertNull(projeto());
    }

    @Test
    void oItemTemNomeAcessivelEFicaNaOrdemDoTeclado() {
        JCheckBox item = itemDeAjustes();
        assertTrue(Visual.naEdt(item::isFocusable) && Visual.naEdt(item::isEnabled));
        assertEquals(AVISO_DO_ITEM, Visual.naEdt(() -> item.getAccessibleContext().getAccessibleName() == null
            ? item.getText() : item.getAccessibleContext().getAccessibleName()));
    }

    @Test
    void osPngsDoItemDeAjustesNosDoisTemas() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
            for (boolean ligado : new boolean[] {true, false}) {
                preferencias.atualizar(p -> p.comPerguntarAoSairDoProjetoComTerminal(ligado));
                JCheckBox item = itemDeAjustes();
                assertEquals(ligado, Visual.naEdt(item::isSelected));
                Visual.salvar(Visual.renderizar(painel, 1100, 760), "pacote-t-ajustes-terminal-" + (ligado ? "pergunta-ligada-" : "pergunta-desligada-") + t.name().toLowerCase());
            }
        }
    }
}
