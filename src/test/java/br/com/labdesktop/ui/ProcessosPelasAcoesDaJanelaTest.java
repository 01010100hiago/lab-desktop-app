package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.AmbienteDeProcessos;
import br.com.labdesktop.processos.ControleDeProcessos;
import br.com.labdesktop.processos.Desfecho;
import br.com.labdesktop.processos.IdentidadeDoProcesso;
import br.com.labdesktop.processos.ObservadorDeProcesso;
import br.com.labdesktop.processos.Previa;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.processos.Resultado;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.dialogos.DialogoProcesso;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Encerrar e reiniciar pelas ações da janela: a mesma trava das outras ações de escrita, os protegidos nunca abrem, e o clique até o fim com um controle de mentira. */
@ExtendWith(VerificaEdt.class)
class ProcessosPelasAcoesDaJanelaTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    @TempDir
    Path tmp;

    CenarioDeCloudflare c;
    ServidorLab lab;
    final AvisosGravados avisos = new AvisosGravados();
    final DialogosGravados dialogos = new DialogosGravados();
    final AtomicInteger depois = new AtomicInteger();
    final List<String> chamadas = new ArrayList<>();
    AcoesDaInterface acoes;

    /** Um controle que anota o que lhe pedem (a regra é a de verdade: o avaliador). */
    private final ControleDeProcessos controle = new ControleDeProcessos() {
        final AmbienteDeProcessos ambiente = AmbienteDeProcessos.servidor("labuser", Set.of());

        @Override
        public AmbienteDeProcessos ambiente() {
            return ambiente;
        }

        @Override
        public Previa preparar(ProcessoAlvo alvo) {
            chamadas.add("preparar:" + alvo.pid());
            return new Previa(alvo, avaliar(alvo), new IdentidadeDoProcesso(alvo.pid(), "1000", "Tue Oct 28 12:00:00 2026", alvo.nome()), null);
        }

        @Override
        public Resultado encerrar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
            chamadas.add("encerrar:" + p.alvo().pid());
            return Resultado.de(Desfecho.ENCERRADO, "O processo " + p.alvo().pid() + " fechou.");
        }

        @Override
        public Resultado forcar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
            chamadas.add("forcar:" + p.alvo().pid());
            return Resultado.de(Desfecho.ENCERRADO, "à força");
        }

        @Override
        public Resultado reiniciar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
            chamadas.add("reiniciar:" + p.alvo().pid());
            return Resultado.de(Desfecho.REINICIADO, "reiniciado");
        }
    };

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        c = new CenarioDeCloudflare(tmp);
        lab = c.servidorLab();
        lab.definirControleDeProcessos(controle);
        acoes = new AcoesDaInterface(null, null, avisos, dialogos, s -> { }, new NavegadorGravado(avisos), ServicosDeArquivos.descartaveis());
        acoes.usarTrava(() -> true);
    }

    @AfterEach
    void desmontar() {
        c.close();
        Visual.tema(Tema.CLARO);
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    @Test
    void semAsAcoesLigadasNadaAbreENadaERodado() {
        acoes.usarTrava(() -> false);
        ProcessoAlvo alvo = new ProcessoAlvo(3000, "node", "labuser", null, null);
        Visual.naEdt(() -> acoes.encerrarOuReiniciarProcesso(lab, alvo, "o próprio servidor", Acao.ENCERRAR, depois::incrementAndGet));
        assertTrue(dialogos.mostrados.isEmpty());
        assertEquals(List.of(), chamadas);
        assertTrue(avisos.ultimo().texto().contains("desligadas nesta versão"), avisos.ultimo().texto());
    }

    @Test
    void protegidoNuncaAbreAConfirmacaoENemLeNada() {
        for (ProcessoAlvo p : List.of(new ProcessoAlvo(1, "systemd", "labuser", null, null), new ProcessoAlvo(700, "sshd", "labuser", null, null),
            new ProcessoAlvo(900, "node", "root", "painel-lab", null), new ProcessoAlvo(800, "dockerd", "root", null, null))) {
            Visual.naEdt(() -> acoes.encerrarOuReiniciarProcesso(lab, p, "x", Acao.ENCERRAR, null));
            assertTrue(dialogos.mostrados.isEmpty(), p.nome());
        }
        assertEquals(List.of(), chamadas);
        assertEquals(4, avisos.todos.size());
    }

    @Test
    void reiniciarProcessoComumDizPorQueNaoExisteENaoAbre() {
        ProcessoAlvo alvo = new ProcessoAlvo(3000, "node", "labuser", null, null);
        Visual.naEdt(() -> acoes.encerrarOuReiniciarProcesso(lab, alvo, "x", Acao.REINICIAR, null));
        assertTrue(dialogos.mostrados.isEmpty());
        assertTrue(avisos.ultimo().texto().contains("não sabe como religá-lo"));
    }

    @Test
    void oCliqueAteOFimEncerraEAAtualizacaoDaListaAcontece() {
        ProcessoAlvo alvo = new ProcessoAlvo(3000, "node", "labuser", null, null);
        Visual.naEdt(() -> acoes.encerrarOuReiniciarProcesso(lab, alvo, "o próprio servidor", Acao.ENCERRAR, depois::incrementAndGet));
        DialogoProcesso d = dialogos.ultimoComo(DialogoProcesso.class);
        Espera.ate(PRAZO, "aberto", () -> !ed(d::fatos).isEmpty());
        assertEquals(List.of("preparar:3000"), chamadas, "abrir só lê");
        Visual.naEdt(() -> d.botaoEncerrar().doClick(0));
        Espera.ate(PRAZO, "resultado", () -> !ed(d::seloDoResultado).isEmpty());
        assertEquals(List.of("preparar:3000", "encerrar:3000"), chamadas);
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertEquals(1, depois.get(), "a lista se atualiza ao fechar");
    }

    @Test
    void containerReiniciaPeloBotaoDoDialogo() {
        ProcessoAlvo alvo = new ProcessoAlvo(3100, "node", "node", "app-loja-web", "app-loja");
        Visual.naEdt(() -> acoes.encerrarOuReiniciarProcesso(lab, alvo, "app-loja (app-loja-web)", Acao.REINICIAR, null));
        DialogoProcesso d = dialogos.ultimoComo(DialogoProcesso.class);
        Espera.ate(PRAZO, "aberto", () -> ed(() -> d.botaoReiniciar().isVisible()));
        Visual.naEdt(() -> d.botaoReiniciar().doClick(0));
        Espera.ate(PRAZO, "reiniciou", () -> chamadas.contains("reiniciar:3100"));
    }

    @Test
    void oRotuloDeForcarAcompanhaOSistema() {
        ProcessoAlvo alvo = new ProcessoAlvo(3000, "node", "labuser", null, null);
        Visual.naEdt(() -> acoes.encerrarOuReiniciarProcesso(lab, alvo, "x", Acao.ENCERRAR, null));
        assertEquals("Forçar (SIGKILL)", dialogos.ultimoComo(DialogoProcesso.class).rotuloDoForcar());
    }
}
