package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.AmbienteDeProcessos;
import br.com.labdesktop.processos.AvaliadorDeProcessos;
import br.com.labdesktop.processos.Desfecho;
import br.com.labdesktop.processos.FalhaDeProcesso;
import br.com.labdesktop.processos.IdentidadeDoProcesso;
import br.com.labdesktop.processos.ObservadorDeProcesso;
import br.com.labdesktop.processos.Oferta;
import br.com.labdesktop.processos.Previa;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.processos.Resultado;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A confirmação de Encerrar e Reiniciar: o que ela mostra ANTES de qualquer envio, o progresso, o "Forçar" que só aparece depois e por outro clique, o sem permissão com o comando, o container, o
 * Parar que nunca prende e o clique duplo. PNGs nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class DialogoProcessoTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final AmbienteDeProcessos AMBIENTE = AmbienteDeProcessos.servidor("labuser", Set.of());

    final AvisosGravados avisos = new AvisosGravados();
    final AtomicInteger terminou = new AtomicInteger();
    final List<String> chamadas = new ArrayList<>();
    volatile CountDownLatch segurar;
    volatile Resultado respostaDeEncerrar = Resultado.de(Desfecho.ENCERRADO, "O processo 3000 (node) fechou.");
    volatile Resultado respostaDeForcar = Resultado.de(Desfecho.ENCERRADO, "O processo 3000 (node) fechou (à força).");
    volatile Resultado respostaDeReiniciar = Resultado.de(Desfecho.REINICIADO, "O container app-loja-web foi reiniciado.");
    volatile FalhaDeProcesso falhaAoPreparar;
    volatile String unidade;

    @AfterEach
    void soltar() {
        if (segurar != null) {
            segurar.countDown();
        }
        Visual.tema(Tema.CLARO);
    }

    private DialogoProcesso.Motor motor(ProcessoAlvo alvo, Oferta oferta) {
        return new DialogoProcesso.Motor() {
            @Override
            public Previa preparar() throws FalhaDeProcesso {
                chamadas.add("preparar");
                if (falhaAoPreparar != null) {
                    throw falhaAoPreparar;
                }
                IdentidadeDoProcesso id = oferta.via() == null || oferta.via() == br.com.labdesktop.processos.Via.CONTAINER ? null
                    : new IdentidadeDoProcesso(alvo.pid(), "1000", "Tue Oct 28 12:00:00 2026", alvo.nome());
                return new Previa(alvo, oferta, id, unidade);
            }

            @Override
            public Resultado encerrar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
                chamadas.add("encerrar");
                obs.etapa("Enviando o pedido para o programa fechar (SIGTERM)...");
                obs.aguardando(2, 8);
                esperar();
                return respostaDeEncerrar;
            }

            @Override
            public Resultado forcar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
                chamadas.add("forcar");
                esperar();
                return respostaDeForcar;
            }

            @Override
            public Resultado reiniciar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
                chamadas.add("reiniciar");
                esperar();
                return respostaDeReiniciar;
            }

            private void esperar() throws InterruptedException {
                CountDownLatch l = segurar;
                if (l != null) {
                    l.await(15, TimeUnit.SECONDS);
                }
            }
        };
    }

    private DialogoProcesso abrir(ProcessoAlvo alvo, Acao acao) {
        Visual.tema(Tema.CLARO);
        Oferta oferta = AvaliadorDeProcessos.avaliar(alvo, AMBIENTE);
        String origem = alvo.container() == null ? "o próprio servidor (fora de container)" : alvo.projeto() + " (" + alvo.container() + ")";
        DialogoProcesso d = Visual.naEdt(() -> new DialogoProcesso(alvo, oferta, acao, origem, "Forçar (SIGKILL)", motor(alvo, oferta), new CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("t")),
            avisos, terminou::incrementAndGet));
        Espera.ate(PRAZO, () -> "preparar: " + chamadas, () -> chamadas.contains("preparar"));
        Espera.ate(PRAZO, () -> "aparecer: fatos=" + Visual.naEdt(d::fatos), () -> !Visual.naEdt(d::fatos).isEmpty() || !Visual.naEdt(d::textoDoBloqueio).isEmpty());
        return d;
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private static final ProcessoAlvo NODE = new ProcessoAlvo(3000, "node", "labuser", null, null);

    @Test
    void mostraNomeNumeroDonoProjetoHoraEOQueVaiAcontecerAntesDeQualquerEnvio() {
        DialogoProcesso d = abrir(NODE, Acao.ENCERRAR);
        List<String> fatos = ed(d::fatos);
        assertTrue(fatos.contains("Programa: node"), fatos.toString());
        assertTrue(fatos.contains("Número do processo (PID): 3000"));
        assertTrue(fatos.contains("Dono (conferido agora): labuser (usuário nº 1000)"), fatos.toString());
        assertTrue(fatos.contains("Projeto / container: o próprio servidor (fora de container)"));
        assertTrue(fatos.contains("Começou em: 28/10/2026 às 12:00:00"), fatos.toString());
        String e = ed(d::textoDaExplicacao);
        assertTrue(e.contains("SIGTERM") && e.contains("SIGKILL") && e.contains("você decide"), e);
        assertEquals(List.of("preparar"), chamadas, "abrir só lê");
        assertTrue(ed(() -> d.botaoEncerrar().isVisible()));
        assertFalse(ed(() -> d.botaoReiniciar().isVisible()), "processo comum não tem Reiniciar");
        assertFalse(ed(() -> d.botaoForcar().isVisible()), "Forçar nem aparece antes");
        assertSame(d.botaoFechar(), d.botaoDoEnter(), "o Enter aciona o seguro (Cancelar), nunca o que mata");
        assertEquals("Cancelar", ed(() -> d.botaoFechar().getText()));
        CenarioDeCloudflare.fotografar(d, "dialogo-processo-1-confirmar");
    }

    @Test
    void encerrarMostraOProgressoEOResultadoEAListaSeAtualizaAoFechar() throws Exception {
        segurar = new CountDownLatch(1);
        DialogoProcesso d = abrir(NODE, Acao.ENCERRAR);
        Visual.naEdt(() -> d.botaoEncerrar().doClick(0));
        Espera.ate(PRAZO, () -> "andando", () -> ed(d::textoDoProgresso).contains("Esperando o programa fechar: 2 de 8 s"));
        assertTrue(ed(d::ocupado));
        assertEquals("Parar de esperar", ed(() -> d.botaoFechar().getText()));
        assertFalse(ed(() -> d.botaoEncerrar().isEnabled()), "clique duplo não envia duas vezes");
        Visual.naEdt(() -> d.botaoEncerrar().doClick(0));
        CenarioDeCloudflare.fotografar(d, "dialogo-processo-2-progresso");
        segurar.countDown();
        Espera.ate(PRAZO, () -> "resultado", () -> !ed(d::seloDoResultado).isEmpty());
        assertEquals("ENCERRADO", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("fechou"));
        assertEquals(1, chamadas.stream().filter("encerrar"::equals).count(), "enviou UMA vez");
        assertEquals("Fechar", ed(() -> d.botaoFechar().getText()));
        assertFalse(ed(() -> d.botaoForcar().isVisible()));
        CenarioDeCloudflare.fotografar(d, "dialogo-processo-3-encerrado");
        assertEquals(0, terminou.get(), "ainda aberto");
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertTrue(ed(d::fechou));
        assertEquals(1, terminou.get(), "ao fechar, a lista se atualiza");
    }

    @Test
    void sigtermQueNaoBastaSoOfereceForcarEEleSoAgePorOutroClique() {
        respostaDeEncerrar = Resultado.de(Desfecho.AINDA_RODANDO, "O processo 3000 (node) recebeu o pedido e continua rodando depois de 8 s. Você pode esperar mais ou forçar (SIGKILL).");
        DialogoProcesso d = abrir(NODE, Acao.ENCERRAR);
        Visual.naEdt(() -> d.botaoEncerrar().doClick(0));
        Espera.ate(PRAZO, () -> "resultado", () -> !ed(d::seloDoResultado).isEmpty());
        assertEquals("AINDA RODANDO", ed(d::seloDoResultado));
        assertTrue(ed(() -> d.botaoForcar().isVisible()), "agora sim");
        assertEquals("Forçar (SIGKILL)", ed(() -> d.botaoForcar().getText()));
        assertEquals(List.of("preparar", "encerrar"), chamadas, "o app NÃO forçou sozinho");
        assertFalse(ed(() -> d.botaoEncerrar().isVisible()));
        CenarioDeCloudflare.fotografar(d, "dialogo-processo-4-ainda-rodando");
        Visual.naEdt(() -> d.botaoForcar().doClick(0));
        Espera.ate(PRAZO, () -> "forçado", () -> chamadas.contains("forcar") && ed(d::seloDoResultado).equals("ENCERRADO"));
        assertEquals(List.of("preparar", "encerrar", "forcar"), chamadas);
        assertFalse(ed(() -> d.botaoForcar().isVisible()));
    }

    @Test
    void semPermissaoMostraOComandoParaODonoENaoOfereceEncerrar() {
        ProcessoAlvo outro = new ProcessoAlvo(3200, "postgres", "postgres", null, null);
        DialogoProcesso d = abrir(outro, Acao.ENCERRAR);
        assertEquals("ps -o pid,user,lstart,comm -p 3200\nps -o comm= -p 3200 | grep -Eq '(^|/)postgres$' && sudo kill -TERM 3200", ed(d::textoDoComando));
        assertFalse(ed(() -> d.botaoEncerrar().isVisible()), "o app não age sem permissão");
        assertTrue(ed(d::textoDaExplicacao).contains("não usa sudo nem digita senha"));
        assertEquals("Fechar", ed(() -> d.botaoFechar().getText()));
        assertEquals(List.of("preparar"), chamadas);
        CenarioDeCloudflare.fotografar(d, "dialogo-processo-5-sem-permissao");
    }

    @Test
    void containerOfereceParaESubirEOExplicaOcontainerInteiro() {
        ProcessoAlvo app = new ProcessoAlvo(3100, "node", "node", "app-loja-web", "app-loja");
        DialogoProcesso d = abrir(app, Acao.ENCERRAR);
        assertEquals("Parar o container", ed(() -> d.botaoEncerrar().getText()));
        assertEquals("Reiniciar o container", ed(() -> d.botaoReiniciar().getText()));
        assertTrue(ed(() -> d.botaoReiniciar().isVisible()));
        String e = ed(d::textoDaExplicacao);
        assertTrue(e.contains("docker stop") && e.contains("docker restart") && e.contains("app-loja-web"), e);
        List<String> fatos = ed(d::fatos);
        assertTrue(fatos.contains("Container: app-loja-web") && fatos.contains("Projeto: app-loja"), fatos.toString());
        assertTrue(fatos.stream().noneMatch(f -> f.contains("PID") || f.contains("Dono") || f.contains("Começou")), "a ação é no container: nada de número de processo nem dono: " + fatos);
        assertEquals("Parar o container", d.titulo(), "o diálogo de container não se chama Encerrar processo");
        assertEquals("Reiniciar o container", abrirReiniciarContainer().titulo());
        CenarioDeCloudflare.fotografar(d, "dialogo-processo-6-container");
        Visual.naEdt(() -> d.botaoReiniciar().doClick(0));
        Espera.ate(PRAZO, () -> "reiniciado", () -> !ed(d::seloDoResultado).isEmpty());
        assertEquals("REINICIADO", ed(d::seloDoResultado));
        assertEquals(List.of("preparar", "reiniciar"), chamadas);
    }

    private DialogoProcesso abrirReiniciarContainer() {
        chamadas.clear();
        return abrir(new ProcessoAlvo(3100, "node", "node", "app-loja-web", "app-loja"), Acao.REINICIAR);
    }

    private DialogoProcesso abrirComoAdministrador(ProcessoAlvo alvo) {
        Visual.tema(Tema.CLARO);
        AmbienteDeProcessos root = AmbienteDeProcessos.servidor("root", Set.of());
        Oferta oferta = AvaliadorDeProcessos.avaliar(alvo, root);
        DialogoProcesso d = Visual.naEdt(() -> new DialogoProcesso(alvo, oferta, Acao.ENCERRAR, "o próprio servidor", "Forçar (SIGKILL)", true, motor(alvo, oferta),
            new CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("t")), avisos, terminou::incrementAndGet));
        Espera.ate(PRAZO, () -> "preparar: " + chamadas, () -> chamadas.contains("preparar"));
        Espera.ate(PRAZO, () -> "aparecer", () -> !Visual.naEdt(d::fatos).isEmpty());
        return d;
    }

    @Test
    void comoAdministradorOsBotoesSoLigamDepoisDeDigitarONomeEOAvisoFicaEmDestaque() {
        ProcessoAlvo nginx = new ProcessoAlvo(3300, "nginx", "root", null, null);
        DialogoProcesso d = abrirComoAdministrador(nginx);
        assertTrue(ed(d::textoDoAvisoDeAdministrador).contains("administrador (root)"), ed(d::textoDoAvisoDeAdministrador));
        assertTrue(ed(() -> d.campoDaConfirmacao().isVisible()));
        assertFalse(ed(() -> d.botaoEncerrar().isEnabled()), "sem digitar, o botão está desligado");
        Visual.naEdt(() -> d.botaoEncerrar().doClick(0));
        assertEquals(List.of("preparar"), chamadas, "o clique no botão desligado não envia nada");
        Visual.naEdt(() -> d.campoDaConfirmacao().setText("ngin"));
        assertFalse(ed(() -> d.botaoEncerrar().isEnabled()), "nome pela metade não vale");
        Visual.naEdt(() -> d.campoDaConfirmacao().setText("NGINX"));
        assertFalse(ed(() -> d.botaoEncerrar().isEnabled()), "maiúsculas diferentes não valem: é o nome do programa, não uma frase");
        Visual.naEdt(() -> d.campoDaConfirmacao().setText(" nginx "));
        assertTrue(ed(() -> d.botaoEncerrar().isEnabled()), "o nome certo (sem os espaços das pontas) liga");
        CenarioDeCloudflare.fotografar(d, "dialogo-processo-8-administrador");
        Visual.naEdt(() -> d.botaoEncerrar().doClick(0));
        Espera.ate(PRAZO, () -> "encerrou", () -> chamadas.contains("encerrar"));
    }

    @Test
    void comoAdministradorOContainerPedeODigitarONomeDoContainer() {
        DialogoProcesso d = abrirComoAdministrador(new ProcessoAlvo(3100, "node", "node", "app-loja-web", "app-loja"));
        assertFalse(ed(() -> d.botaoEncerrar().isEnabled()));
        assertFalse(ed(() -> d.botaoReiniciar().isEnabled()));
        Visual.naEdt(() -> d.campoDaConfirmacao().setText("node"));
        assertFalse(ed(() -> d.botaoEncerrar().isEnabled()), "no container se digita o nome do container, não do programa");
        Visual.naEdt(() -> d.campoDaConfirmacao().setText("app-loja-web"));
        assertTrue(ed(() -> d.botaoEncerrar().isEnabled()) && ed(() -> d.botaoReiniciar().isEnabled()));
    }

    @Test
    void comUsuarioComumNaoHaCampoNemAviso() {
        DialogoProcesso d = abrir(NODE, Acao.ENCERRAR);
        assertFalse(ed(() -> d.campoDaConfirmacao().isVisible()));
        assertEquals("", ed(d::textoDoAvisoDeAdministrador));
        assertTrue(ed(() -> d.botaoEncerrar().isEnabled()));
    }

    @Test
    void protegerOContainerMarcaEFechaSoParaContainerLiberado() {
        ProcessoAlvo app = new ProcessoAlvo(3100, "node", "node", "app-loja-web", "app-loja");
        DialogoProcesso d = abrir(app, Acao.ENCERRAR);
        assertFalse(ed(() -> d.botaoProteger().isVisible()), "sem quem grave a marca, o botão nem aparece");
        List<String> marcados = new ArrayList<>();
        Visual.naEdt(() -> d.permitirProteger(() -> marcados.add("app-loja-web")));
        assertTrue(ed(() -> d.botaoProteger().isVisible()));
        Visual.naEdt(() -> d.botaoProteger().doClick(0));
        assertEquals(List.of("app-loja-web"), marcados);
        assertTrue(ed(d::fechou));
        // processo comum: nada a proteger
        chamadas.clear();
        DialogoProcesso comum = abrir(NODE, Acao.ENCERRAR);
        Visual.naEdt(() -> comum.permitirProteger(() -> { }));
        assertFalse(ed(() -> comum.botaoProteger().isVisible()));
    }

    @Test
    void servicoDoSystemdDoUsuarioGanhaOBotaoReiniciarOServico() {
        unidade = "meu-app.service";
        DialogoProcesso d = abrir(NODE, Acao.ENCERRAR);
        assertTrue(ed(() -> d.botaoReiniciar().isVisible()));
        assertEquals("Reiniciar o serviço", ed(() -> d.botaoReiniciar().getText()));
        assertTrue(ed(d::textoDoServico).contains("meu-app.service"));
    }

    @Test
    void processoQueMudouOuJaSaiuMostraAFraseEMandaAtualizarALista() {
        falhaAoPreparar = new FalhaDeProcesso(Desfecho.MUDOU, "O processo 3000 agora é outro programa (python3, e na lista era node): o número foi reaproveitado. Nada foi enviado; a lista vai atualizar.");
        DialogoProcesso d = abrir(NODE, Acao.ENCERRAR);
        assertTrue(ed(d::textoDoBloqueio).contains("o número foi reaproveitado"));
        assertFalse(ed(() -> d.botaoEncerrar().isVisible()));
        assertEquals("Fechar", ed(() -> d.botaoFechar().getText()));
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertEquals(1, terminou.get(), "a lista estava velha: ao fechar, ela é lida de novo");
        CenarioDeCloudflare.fotografar(abrirDeNovoComFalha(), "dialogo-processo-7-mudou");
    }

    private DialogoProcesso abrirDeNovoComFalha() {
        chamadas.clear();
        return abrir(NODE, Acao.ENCERRAR);
    }

    @Test
    void pararDeEsperarNuncaPrendeEOSegundoCliqueFechaComOResultadoNosAvisos() throws Exception {
        segurar = new CountDownLatch(1);
        DialogoProcesso d = abrir(NODE, Acao.ENCERRAR);
        Visual.naEdt(() -> d.botaoEncerrar().doClick(0));
        Espera.ate(PRAZO, () -> "andando", () -> ed(d::ocupado));
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertEquals("Fechar mesmo assim", ed(() -> d.botaoFechar().getText()));
        assertFalse(ed(d::fechou));
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertTrue(ed(d::fechou), "o segundo clique fecha de verdade");
        segurar.countDown();
        Espera.ate(PRAZO, () -> "aviso: " + avisos.textos(), () -> avisos.textos().stream().anyMatch(t -> t.contains("fechou")));
        assertTrue(terminou.get() >= 1);
    }

    @Test
    void escFechaQuandoNaoHaNadaEmAndamento() {
        DialogoProcesso d = abrir(NODE, Acao.ENCERRAR);
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertTrue(ed(d::fechou));
        assertEquals(0, terminou.get(), "não aconteceu nada: não precisa reler a lista");
        assertEquals(List.of("preparar"), chamadas);
    }
}
