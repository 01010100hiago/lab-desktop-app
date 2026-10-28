package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cloudflare.ComposeComEdge;
import br.com.labdesktop.cloudflare.Etapa;
import br.com.labdesktop.cloudflare.LabSimulado;
import br.com.labdesktop.cloudflare.ObservadorDaEtapa;
import br.com.labdesktop.cloudflare.PublicadorNaInternet;
import br.com.labdesktop.cloudflare.PublicadorPorArquivoTestApoio;
import br.com.labdesktop.cloudflare.TestadorDeUrl;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Publicar na internet": a prévia (só leitura, com o que muda), os bloqueios, o clique que escreve, o resultado, a reversão quando algo falha no meio, o Parar que nunca prende e o clique
 * duplo. Tudo contra a Cloudflare FALSA e o servidor simulado (SSH de verdade, Docker de mentira); PNGs nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class DialogoPublicarTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    CenarioDeCloudflare c;
    final AvisosGravados avisos = new AvisosGravados();
    final Clipboard area = new Clipboard("teste");
    final List<String> recriados = new CopyOnWriteArrayList<>();
    final List<String> abertos = new CopyOnWriteArrayList<>();
    final AtomicInteger depois = new AtomicInteger();
    final AtomicInteger encerrados = new AtomicInteger();
    final AtomicInteger execucoes = new AtomicInteger();
    volatile Exception falhaAoRecriar;
    volatile boolean podeRecriar = true;
    /** Se não for nulo, a execução espera por ele (para o teste clicar no meio dela). */
    volatile CountDownLatch segurar;
    volatile CountDownLatch comecou = new CountDownLatch(1);

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        c = new CenarioDeCloudflare(tmp);
        c.prepararOServidorDoDono(PublicadorPorArquivoTestApoio.COMPOSE, PublicadorPorArquivoTestApoio.CONFIG);
        c.conectarPorArquivo();
    }

    @AfterEach
    void desmontar() {
        if (segurar != null) {
            segurar.countDown();
        }
        c.close();
        Visual.tema(Tema.CLARO);
    }

    private DialogoPublicar.Motor motor() throws Exception {
        PublicadorNaInternet pub = c.servico.publicador(p -> {
            recriados.add(p);
            if (falhaAoRecriar != null) {
                throw falhaAoRecriar;
            }
        });
        return new DialogoPublicar.Motor() {
            @Override
            public String projeto() {
                return "demo";
            }

            @Override
            public String zona() {
                return "example.com";
            }

            @Override
            public String prefixo() {
                return "lab";
            }

            @Override
            public boolean podeRecriar() {
                return podeRecriar;
            }

            @Override
            public List<ComposeComEdge.Servico> servicos() throws FalhaDoProjeto {
                return c.servico.servicosDoProjeto("demo");
            }

            @Override
            public PublicadorNaInternet.Previa preparar(PublicadorNaInternet.Pedido pedido) throws FalhaDoProjeto {
                return pub.preparar(pedido);
            }

            @Override
            public PublicadorNaInternet.Resultado executar(PublicadorNaInternet.Previa previa, ObservadorDaEtapa obs, Cancelamento cancelamento) {
                execucoes.incrementAndGet();
                CountDownLatch espera = segurar;
                ObservadorDaEtapa o = obs;
                if (espera != null) {
                    // segura logo depois do primeiro passo, até o teste soltar (ou cancelar)
                    o = (e, s, f) -> {
                        obs.etapa(e, s, f);
                        if (e == Etapa.COMPOSE && s == ObservadorDaEtapa.Estado.FEITO) {
                            comecou.countDown();
                            try {
                                espera.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    };
                }
                return pub.executar(previa, o, cancelamento);
            }

            @Override
            public TestadorDeUrl.Resultado testarDeNovo(String hostname, Cancelamento cancelamento) {
                return c.servico.testar(hostname, cancelamento);
            }

            @Override
            public void abrirEndereco(String hostname) {
                abertos.add(hostname);
            }

            @Override
            public void encerrar() {
                encerrados.incrementAndGet();
                pub.close();
            }
        };
    }

    private DialogoPublicar abrir() throws Exception {
        DialogoPublicar.Motor m = motor();
        return Visual.naEdt(() -> new DialogoPublicar(m, avisos, new CopiadorDeTexto(() -> area), depois::incrementAndGet));
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    /** Abre e espera a prévia chegar (o clique em "Conferir de novo" não espera os 350 ms do digitar). */
    private DialogoPublicar abrirComPrevia() throws Exception {
        DialogoPublicar d = abrir();
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 1);
        Visual.naEdt(() -> d.botaoConferir().doClick());
        Espera.ate(PRAZO, () -> "prévia: " + ed(d::textoDaSituacao) + " " + ed(d::textoDoBloqueio), () -> ed(() -> d.previa() != null));
        return d;
    }

    private void clicar(DialogoPublicar d) {
        Visual.naEdt(() -> d.botaoPublicar().doClick());
    }

    private void esperarOFim(DialogoPublicar d) {
        Espera.ate(PRAZO, () -> "fim: " + ed(d::seloDoResultado) + " ocupado=" + ed(d::ocupado), () -> !ed(d::ocupado) && !ed(d::seloDoResultado).isEmpty());
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void aPreviaMostraOQueMudaEOBotaoSoLiberaDepoisDela() throws Exception {
        DialogoPublicar d = abrir();
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 1);
        assertFalse(ed(() -> d.botaoPublicar().isEnabled()), "sem prévia, não publica");
        assertEquals("app", ed(() -> d.caixaDeServicos().getSelectedItem()));
        assertEquals("3000", ed(() -> d.campoPorta().getText()), "a porta vem do mapeamento do compose");
        assertEquals("demo-app", ed(() -> d.campoContainer().getText()));
        assertEquals("lab-demo", ed(() -> d.campoNome().getText()));
        assertEquals("Endereço público: https://lab-demo.example.com/", ed(d::enderecoMostrado));
        Visual.naEdt(() -> d.botaoConferir().doClick());
        Espera.ate(PRAZO, () -> "prévia: " + ed(d::textoDoBloqueio), () -> ed(() -> d.previa() != null));
        assertTrue(ed(() -> d.botaoPublicar().isEnabled()));
        assertTrue(ed(d::textoDoCompose).contains("+     container_name: demo-app"), ed(d::textoDoCompose));
        assertTrue(ed(d::textoDoTunel).contains("+   - hostname: lab-demo.example.com"), ed(d::textoDoTunel));
        assertTrue(ed(d::textoDosPassos).contains("reiniciar SÓ o container cloudflared"), ed(d::textoDosPassos));
        assertTrue(ed(d::textoDosAvisos).contains("O cloudflared será reiniciado"), ed(d::textoDosAvisos));
        // a prévia só LEU: nada foi escrito em lugar nenhum
        assertEquals(PublicadorPorArquivoTestApoio.COMPOSE, c.lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml"));
        assertEquals(PublicadorPorArquivoTestApoio.CONFIG, c.lab.ler(LabSimulado.CONFIG));
        assertEquals(List.of(), c.cf.escritas());
        assertEquals(0, c.lab.reinicios.get());
        assertEquals(0, execucoes.get());
        assertEquals(0, depois.get());
        CenarioDeCloudflare.fotografar(d, "dialogo-publicar-1-previa");
    }

    @Test
    void mudarUmCampoDerrubaAPreviaEExigeConferirDeNovo() throws Exception {
        DialogoPublicar d = abrirComPrevia();
        assertTrue(ed(() -> d.botaoPublicar().isEnabled()));
        Visual.naEdt(() -> d.campoNome().setText("lab-outro"));
        assertFalse(ed(() -> d.botaoPublicar().isEnabled()), "a prévia era de outro nome");
        assertNull(ed(d::previa));
        Espera.ate(PRAZO, "a prévia nova (depois do tempo de digitar)", () -> ed(() -> d.previa() != null));
        assertEquals("lab-outro.example.com", ed(() -> d.previa().hostname()));
        assertTrue(ed(() -> d.botaoPublicar().isEnabled()));
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }

    @Test
    void nomeOuPortaInvalidosMostramAFraseENaoCalculamPrevia() throws Exception {
        DialogoPublicar d = abrir();
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 1);
        Visual.naEdt(() -> d.campoNome().setText("Lab_Demo!"));
        assertTrue(ed(d::textoDosProblemas).contains("Nome do endereço"), ed(d::textoDosProblemas));
        Visual.naEdt(() -> d.campoPorta().setText("99999"));
        assertTrue(ed(d::textoDosProblemas).contains("A porta é um número de 1 a 65535"), ed(d::textoDosProblemas));
        Visual.naEdt(() -> d.campoContainer().setText("Nome Ruim"));
        assertTrue(ed(d::textoDosProblemas).contains("Nome do container"), ed(d::textoDosProblemas));
        Visual.naEdt(() -> d.botaoConferir().doClick());
        assertFalse(ed(() -> d.botaoPublicar().isEnabled()));
        assertNull(ed(d::previa));
        assertEquals("", ed(d::enderecoMostrado), "endereço inválido não é mostrado como se valesse");
        CenarioDeCloudflare.fotografar(d, "dialogo-publicar-2-campos-invalidos");
    }

    @Test
    void comNetworkModeNaoHaBlocoParaCopiarSoOMotivo() throws Exception {
        c.lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", PublicadorPorArquivoTestApoio.COMPOSE.replace("    networks:\n      - default\n", "    network_mode: host\n"));
        DialogoPublicar d = abrir();
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 1);
        Visual.naEdt(() -> d.botaoConferir().doClick());
        Espera.ate(PRAZO, () -> "bloqueio: " + ed(d::textoDoBloqueio), () -> ed(() -> d.previa() != null));
        assertFalse(ed(() -> d.botaoPublicar().isEnabled()));
        assertTrue(ed(d::textoDoBloqueio).contains("network_mode"), ed(d::textoDoBloqueio));
        assertFalse(ed(() -> d.botaoCopiarBloco().isVisible()), "o bloco (networks default e edge) contradiria o network_mode: não é oferecido");
        assertEquals("", ed(d::textoDoBloco));
    }

    @Test
    void composeDoDonoForaDoFormatoBloqueiaEMostraOBlocoParaCopiar() throws Exception {
        c.lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", PublicadorPorArquivoTestApoio.COMPOSE.replace("    networks:\n      - default\n", "    networks: [default, interna]\n"));
        DialogoPublicar d = abrir();
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 1);
        Visual.naEdt(() -> d.botaoConferir().doClick());
        Espera.ate(PRAZO, () -> "bloqueio: " + ed(d::textoDoBloqueio), () -> ed(() -> d.previa() != null));
        assertFalse(ed(() -> d.botaoPublicar().isEnabled()), "bloqueado: o botão não vale");
        assertTrue(ed(d::textoDoBloqueio).contains("copie o bloco"), ed(d::textoDoBloqueio));
        assertTrue(ed(d::textoDoBloqueio).contains("uma linha só"), "diz por quê: " + ed(d::textoDoBloqueio));
        assertTrue(ed(d::textoDoBloco).contains("container_name: demo-app"), ed(d::textoDoBloco));
        assertTrue(ed(() -> d.botaoCopiarBloco().isVisible()));
        Visual.naEdt(() -> d.botaoCopiarBloco().doClick());
        assertEquals(ed(d::textoDoBloco), copiado());
        clicar(d);
        assertEquals(0, execucoes.get(), "clicar num pedido bloqueado não faz nada");
        CenarioDeCloudflare.fotografar(d, "dialogo-publicar-3-bloqueio-bloco-para-copiar");
    }

    private String copiado() {
        try {
            return (String) area.getContents(null).getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void semServicosNoComposeDizOQueFazer() throws Exception {
        c.lab.arquivo(LabSimulado.PROJETOS + "/demo/docker-compose.yml").toFile().delete();
        DialogoPublicar d = abrir();
        Espera.ate(PRAZO, () -> "erro: " + ed(d::textoDosProblemas), () -> ed(d::textoDosProblemas).contains("Não achei serviços"));
        assertFalse(ed(() -> d.botaoPublicar().isEnabled()));
    }

    @Test
    void publicarFazTudoMostraAsEtapasEOResultadoNoArEFechaComOSucesso() throws Exception {
        DialogoPublicar d = abrirComPrevia();
        clicar(d);
        esperarOFim(d);
        assertEquals("NO AR", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("https://lab-demo.example.com/"), ed(d::textoDoResultado));
        assertEquals(1, depois.get(), "a tela de trás se atualiza");
        assertEquals(List.of("demo"), recriados);
        assertTrue(c.lab.ler(LabSimulado.CONFIG).contains("hostname: lab-demo.example.com"));
        assertEquals(1, c.cf.registros.size());
        for (Etapa e : List.of(Etapa.COMPOSE, Etapa.REGRA, Etapa.CONFERIR_E_REINICIAR, Etapa.DNS, Etapa.PROJETO, Etapa.TESTE)) {
            assertNotNull(ed(d::linhasDosPassos).get(e), e.toString());
        }
        assertTrue(ed(d::linhasDosPassos).get(Etapa.DNS).contains("DNS"), ed(d::linhasDosPassos).toString());
        assertTrue(ed(() -> d.botaoAbrirEndereco().isVisible()));
        Visual.naEdt(() -> d.botaoAbrirEndereco().doClick());
        assertEquals(List.of("lab-demo.example.com"), abertos);
        assertEquals("Fechar", ed(() -> d.botaoCancelar().getText()));
        assertFalse(ed(() -> d.botaoPublicar().isVisible()));
        CenarioDeCloudflare.fotografar(d, "dialogo-publicar-4-no-ar");
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(ed(d::fechou));
        assertEquals(1, encerrados.get(), "fechar solta o motor (o token do cliente é zerado)");
    }

    @Test
    void enderecoPublicadoMasSemRespostaMostraEmDuvidaComTestarDeNovo() throws Exception {
        c.respostaDoTeste = new TestadorDeUrl.Resultado(TestadorDeUrl.Estado.FORA_DO_AR, 0, "Sem resposta ainda: o DNS pode levar alguns minutos.");
        DialogoPublicar d = abrirComPrevia();
        clicar(d);
        esperarOFim(d);
        assertEquals("PUBLICADO, AINDA SEM RESPOSTA", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("Nada foi desfeito"), ed(d::textoDoResultado));
        assertTrue(ed(() -> d.botaoTestarDeNovo().isVisible()));
        CenarioDeCloudflare.fotografar(d, "dialogo-publicar-5-sem-resposta");
        c.respostaDoTeste = new TestadorDeUrl.Resultado(TestadorDeUrl.Estado.NO_AR, 200, "No ar: o endereço respondeu (código 200).");
        Visual.naEdt(() -> d.botaoTestarDeNovo().doClick());
        Espera.ate(PRAZO, () -> "selo: " + ed(d::seloDoResultado), () -> ed(d::seloDoResultado).equals("NO AR"));
        assertFalse(ed(() -> d.botaoTestarDeNovo().isVisible()));
    }

    @Test
    void falhaNoMeioDesfazSoOQueFezEMostraODesfeitoNaTela() throws Exception {
        c.lab.validarSempreFalha = true;
        DialogoPublicar d = abrirComPrevia();
        clicar(d);
        esperarOFim(d);
        assertEquals("NÃO PUBLICOU", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("O cloudflared não aceitou as regras"), ed(d::textoDoResultado));
        assertEquals("Desfeito: o docker-compose.yml.", ed(d::textoDoDesfeito), "o nome do arquivo não pode ser mexido pela pontuação");
        assertEquals(PublicadorPorArquivoTestApoio.COMPOSE, c.lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml"), "o compose voltou como estava");
        assertEquals(PublicadorPorArquivoTestApoio.CONFIG, c.lab.ler(LabSimulado.CONFIG));
        assertEquals(0, c.cf.registros.size());
        assertEquals(0, depois.get(), "não publicou: a tela de trás não precisa mudar");
        assertEquals("Fechar", ed(() -> d.botaoCancelar().getText()));
        CenarioDeCloudflare.fotografar(d, "dialogo-publicar-6-falha-desfeita");
    }

    @Test
    void falhaAoRecriarODesfazTudoInclusiveODns() throws Exception {
        falhaAoRecriar = new IllegalStateException("compose up falhou: porta 3000 em uso");
        DialogoPublicar d = abrirComPrevia();
        clicar(d);
        esperarOFim(d);
        assertEquals("NÃO PUBLICOU", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("compose up falhou"), ed(d::textoDoResultado));
        assertTrue(ed(d::textoDoDesfeito).contains("DNS"), ed(d::textoDoDesfeito));
        assertEquals(0, c.cf.registros.size(), "o DNS que esta execução criou foi apagado");
        assertEquals(PublicadorPorArquivoTestApoio.CONFIG, c.lab.ler(LabSimulado.CONFIG));
    }

    @Test
    void cliqueDuploNaoPublicaDuasVezes() throws Exception {
        DialogoPublicar d = abrirComPrevia();
        Visual.naEdt(() -> {
            d.botaoPublicar().doClick();
            d.botaoPublicar().doClick();
            d.botaoPublicar().doClick();
        });
        esperarOFim(d);
        assertEquals(1, execucoes.get(), "uma execução só");
        assertEquals(1, c.cf.registros.size());
        assertEquals(1, c.cf.escritas().stream().filter(w -> w.metodo().equals("POST")).count());
        assertEquals(List.of("demo"), recriados);
    }

    @Test
    void pararDuranteAExecucaoDesfazEFecharMesmoAssimNuncaPrende() throws Exception {
        segurar = new CountDownLatch(1);
        DialogoPublicar d = abrirComPrevia();
        clicar(d);
        assertTrue(comecou.await(20, TimeUnit.SECONDS), "a execução chegou ao primeiro passo");
        assertTrue(ed(d::ocupado));
        assertEquals("Parar", ed(() -> d.botaoCancelar().getText()));
        assertFalse(ed(() -> d.botaoPublicar().isEnabled()) && !ed(d::ocupado));
        assertFalse(ed(() -> d.campoNome().isEnabled()), "os campos travam durante a execução");
        CenarioDeCloudflare.fotografar(d, "dialogo-publicar-7-executando");
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertEquals("Fechar mesmo assim", ed(() -> d.botaoCancelar().getText()));
        assertFalse(ed(d::fechou), "o primeiro clique só pede para parar");
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(ed(d::fechou), "o segundo clique fecha mesmo com a execução em andamento: nunca preso");
        assertEquals(0, encerrados.get(), "o motor fica aberto até a execução terminar");
        segurar.countDown();
        Espera.ate(PRAZO, "o motor foi solto no fim", () -> encerrados.get() == 1);
        Espera.ate(PRAZO, "o resultado chegou pelos avisos", () -> avisos.disse("info", "foi parado"));
        assertEquals(PublicadorPorArquivoTestApoio.COMPOSE, c.lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml"), "parou e desfez o que já tinha feito");
        assertEquals(0, c.cf.registros.size());
    }

    @Test
    void cancelarAntesDePublicarSoFechaESoltaOMotor() throws Exception {
        DialogoPublicar d = abrirComPrevia();
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(ed(d::fechou));
        assertEquals(1, encerrados.get());
        assertEquals(0, execucoes.get());
        assertEquals(List.of(), c.cf.escritas());
    }

    @Test
    void semAcoesDeProjetoNaoOferecePracriarOProjeto() throws Exception {
        podeRecriar = false;
        DialogoPublicar d = abrir();
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 1);
        assertFalse(ed(() -> d.caixaDeRecriar().isVisible()));
        Visual.naEdt(() -> d.botaoConferir().doClick());
        Espera.ate(PRAZO, "prévia", () -> ed(() -> d.previa() != null));
        assertTrue(ed(() -> d.previa().passos().stream().noneMatch(p -> p.startsWith("Projeto: recriar"))), ed(() -> d.previa().passos().toString()));
    }

    @Test
    void oTokenNuncaApareceNaTela() throws Exception {
        DialogoPublicar d = abrirComPrevia();
        clicar(d);
        esperarOFim(d);
        String t = CenarioDeCloudflare.textos(d.raiz());
        assertFalse(t.contains(CloudflareFalsa.TOKEN_CERTO), "o token não aparece em nenhum texto do diálogo");
        for (String texto : avisos.textos()) {
            assertFalse(texto.contains(CloudflareFalsa.TOKEN_CERTO));
        }
    }
}
