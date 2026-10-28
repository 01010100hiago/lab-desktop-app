package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cloudflare.LabSimulado;
import br.com.labdesktop.cloudflare.ObservadorDaEtapa;
import br.com.labdesktop.cloudflare.PublicadorNaInternet;
import br.com.labdesktop.cloudflare.PublicadorPorArquivoTestApoio;
import br.com.labdesktop.cloudflare.RemovedorDeEndereco;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** "Remover endereço": só o que o app criou, só depois de digitar o endereço, com a reversão quando falha, o Parar que nunca prende e o clique duplo. PNGs nos dois temas. */
@ExtendWith(VerificaEdt.class)
class DialogoRemoverEnderecoTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final String HOST = "lab-demo.example.com";

    @TempDir
    Path tmp;

    CenarioDeCloudflare c;
    final AvisosGravados avisos = new AvisosGravados();
    final AtomicInteger removidos = new AtomicInteger();
    final AtomicInteger encerrados = new AtomicInteger();
    final AtomicInteger execucoes = new AtomicInteger();
    volatile CountDownLatch segurar;
    volatile CountDownLatch comecou = new CountDownLatch(1);

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        c = new CenarioDeCloudflare(tmp);
        c.prepararOServidorDoDono(PublicadorPorArquivoTestApoio.COMPOSE, PublicadorPorArquivoTestApoio.CONFIG);
        c.conectarPorArquivo();
        try (PublicadorNaInternet p = c.servico.publicador(x -> { })) {
            PublicadorNaInternet.Resultado r = p.executar(p.preparar(new PublicadorNaInternet.Pedido("demo", "app", "demo-app", 3000, "lab-demo", false)), ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        }
        c.lab.comandos.clear();
        c.cf.pedidos.clear();
    }

    @AfterEach
    void desmontar() {
        if (segurar != null) {
            segurar.countDown();
        }
        c.close();
        Visual.tema(Tema.CLARO);
    }

    private DialogoRemoverEndereco abrir(String host) throws Exception {
        RemovedorDeEndereco rem = c.servico.removedor();
        DialogoRemoverEndereco.Motor m = new DialogoRemoverEndereco.Motor() {
            @Override
            public RemovedorDeEndereco.Previa preparar(String h) throws FalhaDoProjeto {
                return rem.preparar(h);
            }

            @Override
            public RemovedorDeEndereco.Resultado executar(RemovedorDeEndereco.Previa previa, ObservadorDaEtapa obs, Cancelamento cancelamento) {
                execucoes.incrementAndGet();
                CountDownLatch espera = segurar;
                ObservadorDaEtapa o = obs;
                if (espera != null) {
                    o = (e, s, f) -> {
                        obs.etapa(e, s, f);
                        if (s == ObservadorDaEtapa.Estado.FEITO) {
                            comecou.countDown();
                            try {
                                espera.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    };
                }
                return rem.executar(previa, o, cancelamento);
            }

            @Override
            public void encerrar() {
                encerrados.incrementAndGet();
                rem.close();
            }
        };
        return Visual.naEdt(() -> new DialogoRemoverEndereco(host, m, avisos, removidos::incrementAndGet));
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private DialogoRemoverEndereco abrirComPrevia(String host) throws Exception {
        DialogoRemoverEndereco d = abrir(host);
        Espera.ate(PRAZO, () -> "prévia: " + ed(d::textoDoBloqueio), () -> ed(() -> d.previa() != null) || !ed(d::textoDoBloqueio).isEmpty());
        return d;
    }

    private static void digitar(DialogoRemoverEndereco d, String texto) {
        Visual.naEdt(() -> d.campoDaConfirmacao().setText(texto));
    }

    @Test
    void soHabilitaDepoisDeDigitarOEnderecoCertoENaoEscreveNadaAntes() throws Exception {
        DialogoRemoverEndereco d = abrirComPrevia(HOST);
        assertEquals("Remover " + HOST, ed(() -> d.botaoRemover().getText()));
        assertFalse(ed(() -> d.botaoRemover().isEnabled()), "sem digitar, o botão não vale");
        assertTrue(ed(d::textoDoTunel).contains("-   - hostname: " + HOST), "mostra o que SAI do túnel: " + ed(d::textoDoTunel));
        assertTrue(ed(d::textoDosPassos).contains("DNS"), ed(d::textoDosPassos));
        digitar(d, "lab-outro.example.com");
        assertFalse(ed(() -> d.botaoRemover().isEnabled()), "outro endereço não vale");
        digitar(d, HOST.substring(0, HOST.length() - 1));
        assertFalse(ed(() -> d.botaoRemover().isEnabled()), "quase certo também não");
        CenarioDeCloudflare.fotografar(d, "dialogo-remover-1-previa");
        digitar(d, "  " + HOST.toUpperCase() + " ");
        assertTrue(ed(() -> d.botaoRemover().isEnabled()), "o endereço certo (sem ligar para maiúsculas nem espaços nas pontas) libera");
        assertEquals(0, execucoes.get());
        assertEquals(List.of(), c.cf.escritas(), "a prévia e o digitar só leram");
        assertTrue(c.lab.ler(LabSimulado.CONFIG).contains("hostname: " + HOST), "nada saiu do túnel ainda");
        assertEquals(1, c.lab.reinicios.get(), "só o reinício da publicação de antes");
        assertEquals(0, removidos.get());
    }

    @Test
    void removerTiraARegraEODnsMostraOResultadoEAvisaATelaDeTras() throws Exception {
        DialogoRemoverEndereco d = abrirComPrevia(HOST);
        digitar(d, HOST);
        Visual.naEdt(() -> d.botaoRemover().doClick());
        Espera.ate(PRAZO, () -> "selo: " + ed(d::seloDoResultado), () -> !ed(d::ocupado) && !ed(d::seloDoResultado).isEmpty());
        assertEquals("REMOVIDO", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("saiu do túnel e do DNS"), ed(d::textoDoResultado));
        assertEquals(1, removidos.get());
        assertFalse(c.lab.ler(LabSimulado.CONFIG).contains("lab-demo"), "a regra saiu");
        assertEquals(0, c.cf.registros.size(), "o DNS saiu");
        assertTrue(c.lab.ler(LabSimulado.CONFIG).contains("meu-painel.example.com"), "o que era do usuário ficou");
        assertEquals("Fechar", ed(() -> d.botaoCancelar().getText()));
        CenarioDeCloudflare.fotografar(d, "dialogo-remover-2-removido");
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(ed(d::fechou));
        assertEquals(1, encerrados.get());
    }

    @Test
    void enderecoQueNaoEDoAppFicaBloqueadoMesmoDigitandoCerto() throws Exception {
        DialogoRemoverEndereco d = abrirComPrevia("meu-painel.example.com");
        assertTrue(ed(d::textoDoBloqueio).contains("não foi criada por este app"), ed(d::textoDoBloqueio));
        digitar(d, "meu-painel.example.com");
        assertFalse(ed(() -> d.botaoRemover().isEnabled()), "a regra do usuário nunca é removida pelo app");
        Visual.naEdt(() -> d.botaoRemover().doClick());
        assertEquals(0, execucoes.get());
        assertTrue(c.lab.ler(LabSimulado.CONFIG).contains("meu-painel.example.com"));
        CenarioDeCloudflare.fotografar(d, "dialogo-remover-3-nao-e-do-app");
    }

    @Test
    void falhaDevolveOArquivoEMostraNaoRemoveu() throws Exception {
        c.lab.validarSempreFalha = true;
        String antes = c.lab.ler(LabSimulado.CONFIG);
        DialogoRemoverEndereco d = abrirComPrevia(HOST);
        digitar(d, HOST);
        Visual.naEdt(() -> d.botaoRemover().doClick());
        Espera.ate(PRAZO, () -> "selo: " + ed(d::seloDoResultado), () -> !ed(d::ocupado) && !ed(d::seloDoResultado).isEmpty());
        assertEquals("NÃO REMOVEU", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("O cloudflared não aceitou"), ed(d::textoDoResultado));
        assertEquals(antes, c.lab.ler(LabSimulado.CONFIG), "o config.yml voltou como estava");
        assertEquals(1, c.cf.registros.size(), "o DNS continua: nada saiu pela metade");
        assertEquals(0, removidos.get());
        CenarioDeCloudflare.fotografar(d, "dialogo-remover-4-falha");
    }

    @Test
    void cliqueDuploRemoveUmaVezSo() throws Exception {
        DialogoRemoverEndereco d = abrirComPrevia(HOST);
        digitar(d, HOST);
        Visual.naEdt(() -> {
            d.botaoRemover().doClick();
            d.botaoRemover().doClick();
        });
        Espera.ate(PRAZO, () -> "selo", () -> !ed(d::ocupado) && !ed(d::seloDoResultado).isEmpty());
        assertEquals(1, execucoes.get());
        assertEquals(1, removidos.get());
    }

    @Test
    void pararEFecharMesmoAssimNuncaPrendemEOMotorSoLogoDepois() throws Exception {
        segurar = new CountDownLatch(1);
        DialogoRemoverEndereco d = abrirComPrevia(HOST);
        digitar(d, HOST);
        Visual.naEdt(() -> d.botaoRemover().doClick());
        assertTrue(comecou.await(20, TimeUnit.SECONDS));
        assertEquals("Parar", ed(() -> d.botaoCancelar().getText()));
        assertFalse(ed(() -> d.campoDaConfirmacao().isEnabled()));
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertEquals("Fechar mesmo assim", ed(() -> d.botaoCancelar().getText()));
        assertFalse(ed(d::fechou));
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(ed(d::fechou), "nunca preso");
        segurar.countDown();
        Espera.ate(PRAZO, "o motor foi solto no fim", () -> encerrados.get() == 1);
        Espera.ate(PRAZO, "o resultado chegou pelos avisos", () -> !avisos.todos.isEmpty());
        String texto = avisos.todos.get(avisos.todos.size() - 1).texto();
        assertTrue(texto.contains(HOST), texto);
    }

    @Test
    void cancelarAntesDeRemoverSoFechaESoltaOMotor() throws Exception {
        DialogoRemoverEndereco d = abrirComPrevia(HOST);
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(ed(d::fechou));
        assertEquals(1, encerrados.get());
        assertEquals(0, execucoes.get());
        assertEquals(List.of(), c.cf.escritas());
    }

    @Test
    void oTokenNuncaApareceNaTela() throws Exception {
        DialogoRemoverEndereco d = abrirComPrevia(HOST);
        digitar(d, HOST);
        Visual.naEdt(() -> d.botaoRemover().doClick());
        Espera.ate(PRAZO, "fim", () -> !ed(d::ocupado) && !ed(d::seloDoResultado).isEmpty());
        assertFalse(CenarioDeCloudflare.textos(d.raiz()).contains(CloudflareFalsa.TOKEN_CERTO));
    }

}
