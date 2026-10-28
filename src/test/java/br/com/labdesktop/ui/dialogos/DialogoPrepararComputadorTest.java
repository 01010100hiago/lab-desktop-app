package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.EstadoDoPasso;
import br.com.labdesktop.instalador.GanchosFalsos;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.local.ComandosLocais;
import br.com.labdesktop.local.InstaladorDeRequisitos;
import br.com.labdesktop.local.LaboratorioLocal;
import br.com.labdesktop.local.ParametrosDoLaboratorio;
import br.com.labdesktop.local.PassoDaRemocaoLocal;
import br.com.labdesktop.local.PassoDoLaboratorio;
import br.com.labdesktop.local.PlanoDeInstalacao;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.local.RelatorioDoComputador;
import br.com.labdesktop.local.SistemaOperacional;
import br.com.labdesktop.local.VerificacoesDoComputador;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.SondaFalsa;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * A tela "Preparar este computador" contra o computador simulado: conferir só lê, cada instalação mostra os comandos exatos e só roda no clique,
 * "Abrir o Docker", criar o laboratório, falha com desfazer e "Tentar de novo", cancelar, e "Remover laboratório local" com confirmação pelo
 * nome. Grava os PNGs de cada momento nos dois temas em {@code build/telas/}.
 */
@ExtendWith(VerificaEdt.class)
class DialogoPrepararComputadorTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[2048]);

    @TempDir
    Path tmp;

    private final ComputadorSimulado pc = new ComputadorSimulado();
    private final SondaFalsa sonda = new SondaFalsa();
    private final GanchosFalsos ganchos = new GanchosFalsos();
    private final AvisosGravados avisos = new AvisosGravados();
    private final AtomicInteger mudancas = new AtomicInteger();
    private final List<String> paginasAbertas = new CopyOnWriteArrayList<>();
    private final List<String> escolhas = new CopyOnWriteArrayList<>();
    private PlataformaLocal plataforma;
    private String pasta;

    private final DialogoPrepararComputador.Motor motor = new DialogoPrepararComputador.Motor() {
        @Override
        public RelatorioDoComputador verificar(String pastaDosProjetos, int porta) throws Exception {
            return new VerificacoesDoComputador(pc, sonda, plataforma).executar(pastaDosProjetos, porta);
        }

        @Override
        public InstaladorDeRequisitos.Resultado instalar(PlanoDeInstalacao plano, ObservadorDaInstalacao observador, Cancelamento cancelamento) {
            return new InstaladorDeRequisitos(pc).instalar(plano, observador, cancelamento);
        }

        @Override
        public boolean abrirODocker(ObservadorDaInstalacao observador, Cancelamento cancelamento) throws InterruptedException {
            return new InstaladorDeRequisitos(pc).abrirEEsperar(plataforma.comandoParaAbrirODocker(true, false), Duration.ofSeconds(10),
                Duration.ofMillis(10), observador, cancelamento);
        }

        @Override
        public LaboratorioLocal laboratorio(ParametrosDoLaboratorio p) {
            return new LaboratorioLocal(pc, p, plataforma, PACOTE, ganchos).comSonda(Duration.ofMillis(300), Duration.ofMillis(10));
        }

        @Override
        public void abrirPagina(String url) {
            paginasAbertas.add(url);
        }

        @Override
        public void guardarEscolhas(int porta, String pastaDosProjetos) {
            escolhas.add(porta + "|" + pastaDosProjetos);
        }
    };

    @BeforeEach
    void preparar() {
        Visual.tema(Tema.CLARO);
        plataforma = new PlataformaLocal(SistemaOperacional.MAC, tmp.toString(), Map.of());
        pasta = plataforma.pastaDeProjetosPadrao();
        sonda.programas.add("brew");
    }

    @AfterEach
    void restaurar() {
        Visual.tema(Tema.CLARO);
    }

    private DialogoPrepararComputador abrir(DialogoPrepararComputador.Acao acao) {
        DialogoPrepararComputador.Alvo alvo = new DialogoPrepararComputador.Alvo("Este computador", 7070, pasta, "painel-lab", PACOTE.versao(), false);
        DialogoPrepararComputador d = Visual.naEdt(() -> new DialogoPrepararComputador(acao, alvo, motor, mudancas::incrementAndGet, avisos,
            new CopiadorDeTexto(() -> new Clipboard("teste"))));
        if (acao == DialogoPrepararComputador.Acao.PREPARAR) {
            Visual.naEdt(d::iniciarConferencia);
        }
        return d;
    }

    private static void esperarPagina(DialogoPrepararComputador d, String pagina) {
        Espera.ate(PRAZO, () -> "página=" + Visual.naEdt(d::paginaAtual), () -> pagina.equals(Visual.naEdt(d::paginaAtual)));
    }

    private static void esperarFim(DialogoPrepararComputador d) {
        Espera.ate(PRAZO, () -> "executando=" + Visual.naEdt(d::executando) + " situacao=" + Visual.naEdt(d::situacaoFinal),
            () -> !Visual.naEdt(d::executando) && !Visual.naEdt(d::situacaoFinal).isEmpty());
    }

    private static void fotografar(DialogoPrepararComputador d, String nome) {
        for (Tema t : Tema.values()) {
            if (t == Tema.SISTEMA) {
                continue;
            }
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
            Visual.naEdt(() -> {
                if (d.mioloRolavel() != null) {
                    d.mioloRolavel().rolarParaOTopo();
                }
            });
            int altura = Math.min(900, Visual.alturaPreferida(d.raiz(), 560));
            Visual.salvar(Visual.renderizar(d.raiz(), 560, altura), nome + (t == Tema.CLARO ? "-claro" : "-escuro"));
        }
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
    }

    private static Botao botaoOuNulo(DialogoPrepararComputador d, String nome) {
        try {
            return Visual.naEdt(() -> d.botaoNomeado(nome));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Botao botao(DialogoPrepararComputador d, String nome) {
        return Visual.naEdt(() -> d.botaoNomeado(nome));
    }

    // ---------------------------------------------------------------------------------------------
    // Conferir e resultado
    // ---------------------------------------------------------------------------------------------

    @Test
    void conferirSoLeEMostraCadaVerificacaoComMarcaEAFraseSimples() {
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");

        assertTrue(pc.escritas().isEmpty(), "só leitura até o clique: " + pc.escritas());
        List<String> v = Visual.naEdt(d::frasesDasVerificacoes);
        assertEquals(8, v.size(), v.toString());
        assertTrue(v.get(2).startsWith("Docker (ok): Docker 29.6.2"), v.get(2));
        assertTrue(Visual.naEdt(() -> d.botaoCriar().isEnabled()), "tudo certo: dá para criar");
        assertEquals("Criar meu laboratório local", Visual.naEdt(() -> d.botaoCriar().getText()));
        String tudo = String.join("\n", Visual.naEdt(d::frasesDoQueVaiAcontecer));
        assertTrue(tudo.contains("127.0.0.1:7070"), tudo);
        assertTrue(tudo.contains(pasta), tudo);
        assertTrue(tudo.contains("rede edge"), tudo);
        assertTrue(tudo.contains("só no loopback"), tudo);
        assertTrue(tudo.contains("desfaz só o que ele criou"), tudo);
        fotografar(d, "dialogo-preparar-computador-pronto");
    }

    @Test
    void dockerAusenteMostraOsPlanosEOCriarFicaDesligado() {
        pc.dockerInstalado = false;
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");

        assertFalse(Visual.naEdt(() -> d.botaoCriar().isEnabled()));
        assertNotNull(botao(d, "plano-docker-brew"));
        assertNotNull(botao(d, "plano-docker-colima"));
        assertTrue(Visual.naEdt(d::textoDoBloqueio).contains("impede"), Visual.naEdt(d::textoDoBloqueio));
        assertEquals("Instalar o Docker Desktop", Visual.naEdt(() -> botao(d, "plano-docker-brew").getText()));
        fotografar(d, "dialogo-preparar-computador-docker-ausente");
    }

    // ---------------------------------------------------------------------------------------------
    // Instalar só com o clique e a confirmação
    // ---------------------------------------------------------------------------------------------

    @Test
    void escolherUmPlanoSoMostraOsComandosExatosENadaRodaAteOBotaoDaConfirmacao() {
        pc.dockerInstalado = false;
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        pc.limparRegistro();

        Visual.naEdt(() -> botao(d, "plano-docker-brew").doClick());

        assertEquals("plano", Visual.naEdt(d::paginaAtual));
        assertEquals(List.of("brew install --cask docker-desktop"), Visual.naEdt(d::comandosDoPlano));
        String tela = String.join("\n", Visual.naEdt(d::textosDoPlano));
        assertTrue(tela.contains("O app vai rodar exatamente isto"), tela);
        assertTrue(tela.contains("senha de administrador"), tela);
        assertTrue(tela.contains("o app nunca vê"), tela);
        assertTrue(pc.comandos.isEmpty(), "escolher o plano e ler a confirmação NÃO roda nada: " + pc.comandos);
        assertEquals("Instalar o Docker Desktop", Visual.naEdt(() -> d.botaoInstalarPlano().getText()));
        fotografar(d, "dialogo-preparar-computador-confirmar-instalacao");

        Visual.naEdt(() -> d.botaoVoltarDoPlano().doClick());
        assertEquals("resultado", Visual.naEdt(d::paginaAtual));
        assertTrue(pc.comandos.isEmpty(), "voltar também não roda nada");
        assertFalse(pc.dockerInstalado);
    }

    @Test
    void oCliqueRodaOPlanoMostraOProgressoEDepoisConferirDeNovoVeOTrocadoPorParado() {
        pc.dockerInstalado = false;
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        Visual.naEdt(() -> botao(d, "plano-docker-brew").doClick());
        pc.limparRegistro();

        Visual.naEdt(() -> d.botaoInstalarPlano().doClick());
        esperarFim(d);

        assertEquals(List.of(List.of("brew", "install", "--cask", "docker-desktop")), pc.comandos);
        assertTrue(Visual.naEdt(d::situacaoFinal).contains("pronto"), Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoDoLog).contains("$ brew install --cask docker-desktop"));
        assertTrue(Visual.naEdt(d::terminouComSucesso));
        fotografar(d, "dialogo-preparar-computador-instalou");

        sonda.caminhos.add("/Applications/Docker.app"); // o que o Homebrew deixou no disco
        Visual.naEdt(() -> d.botaoVoltarAoResultado().doClick());
        esperarPagina(d, "resultado");
        assertTrue(pc.dockerInstalado);
        assertNotNull(botao(d, "abrir-o-docker"), "instalado e parado: oferece abrir o Docker (e abrir não é instalar)");
    }

    @Test
    void planoSemComandosAbreSoAPaginaOficialPeloNavegadorENadaRoda() {
        pc.dockerInstalado = false;
        sonda.programas.clear();
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        pc.limparRegistro();
        Visual.naEdt(() -> botao(d, "plano-docker-pagina").doClick());

        assertEquals("Abrir a página oficial", Visual.naEdt(() -> d.botaoInstalarPlano().getText()));
        String tela = String.join("\n", Visual.naEdt(d::textosDoPlano));
        assertTrue(tela.contains("https://www.docker.com/products/docker-desktop/"), tela);
        assertTrue(tela.contains("senha de administrador"), tela);
        Visual.naEdt(() -> d.botaoInstalarPlano().doClick());
        assertEquals(List.of("https://www.docker.com/products/docker-desktop/"), paginasAbertas);
        assertTrue(pc.comandos.isEmpty());
        fotografar(d, "dialogo-preparar-computador-pagina-oficial");
    }

    @Test
    void falhaNaInstalacaoMostraOErroRealETentarDeNovoRepete() {
        pc.dockerInstalado = false;
        pc.falharEm(ComputadorSimulado.contem("install --cask"), 1, "Error: Cask 'docker-desktop' requires macOS >= 14");
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        Visual.naEdt(() -> botao(d, "plano-docker-brew").doClick());
        Visual.naEdt(() -> d.botaoInstalarPlano().doClick());
        esperarFim(d);

        assertEquals("Não deu certo", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoFinal).contains("requires macOS >= 14"), Visual.naEdt(d::textoFinal));
        assertFalse(Visual.naEdt(d::terminouComSucesso));
        assertTrue(Visual.naEdt(() -> d.botaoTentarDeNovo().isVisible()));
        fotografar(d, "dialogo-preparar-computador-falha-instalacao");

        pc.consertar();
        Visual.naEdt(() -> d.botaoTentarDeNovo().doClick());
        esperarFim(d);
        Espera.ate(PRAZO, "instalou na segunda", () -> pc.dockerInstalado);
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir o Docker
    // ---------------------------------------------------------------------------------------------

    @Test
    void dockerParadoOfereceAbrirEEsperaOMotorSubir() {
        pc.dockerParado = true;
        pc.consultasAteSubir = 2;
        sonda.caminhos.add("/Applications/Docker.app");
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        assertFalse(Visual.naEdt(() -> d.botaoCriar().isEnabled()));
        Botao abrir = botao(d, "abrir-o-docker");
        assertNotNull(abrir);
        pc.limparRegistro();

        Visual.naEdt(() -> abrir.doClick());
        esperarFim(d);

        assertEquals(List.of("open", "-a", "Docker"), pc.comandos.get(0));
        assertEquals("O Docker está rodando", Visual.naEdt(d::situacaoFinal));
        assertTrue(pc.escritas().stream().allMatch(c -> c.equals(List.of("open", "-a", "Docker"))), "abrir não instala nada: " + pc.escritas());

        Visual.naEdt(() -> d.botaoVoltarAoResultado().doClick());
        esperarPagina(d, "resultado");
        assertTrue(Visual.naEdt(() -> d.botaoCriar().isEnabled()), "com o Docker no ar, dá para criar");
    }

    // ---------------------------------------------------------------------------------------------
    // Porta
    // ---------------------------------------------------------------------------------------------

    @Test
    void portaOcupadaOfereceUsarAPortaLivreEConferirDeNovo() {
        sonda.portasOcupadas.add(7070);
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        assertFalse(Visual.naEdt(() -> d.botaoCriar().isEnabled()));
        Botao usar = botao(d, "usar-porta-sugerida");
        assertEquals("Usar a porta 7071", Visual.naEdt(usar::getText));

        Visual.naEdt(() -> usar.doClick());
        esperarPagina(d, "resultado");
        Espera.ate(PRAZO, "re-verificou com a porta nova", () -> Visual.naEdt(() -> d.botaoCriar().isEnabled()));
        assertEquals("7071", Visual.naEdt(() -> d.campoDaPorta().getText()));
    }

    @Test
    void comOLaboratorioJaRodandoODialogoDizIssoETrataAPortaComoDele() {
        ComputadorSimulado.Cont c = new ComputadorSimulado.Cont();
        c.rotulo = PACOTE.versao();
        c.rotuloLocal = "1";
        c.portaPublicada = 7070;
        pc.containers.put("painel-lab", c);
        sonda.portasOcupadas.add(7070); // quem atende é o container do app
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");

        String nota = Visual.naEdt(d::textoDoLaboratorioQueJaRoda);
        assertTrue(nota.contains("Seu laboratório local já está rodando"), nota);
        assertTrue(nota.contains("porta 7070"), nota);
        assertTrue(Visual.naEdt(() -> d.botaoCriar().isEnabled()), "a porta é do laboratório do app: dá para criar de novo, sem 'porta em uso'");
        assertFalse(Visual.naEdt(d::frasesDasVerificacoes).stream().anyMatch(f -> f.contains("em uso por outro programa")), Visual.naEdt(d::frasesDasVerificacoes).toString());
        assertEquals(null, botaoOuNulo(d, "usar-porta-sugerida"));
        fotografar(d, "dialogo-preparar-computador-ja-roda");
    }

    @Test
    void semOLaboratorioNaoHaNotaDeQueJaRoda() {
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        assertEquals("", Visual.naEdt(d::textoDoLaboratorioQueJaRoda));
    }

    @Test
    void falhaAoCriarNaoDeixaAPortaNemAPastaGravadas() {
        pc.falharEm(ComputadorSimulado.contem(" run -d"), 125, "docker: Error response from daemon: port is already allocated");
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        Visual.naEdt(() -> d.botaoCriar().doClick());
        esperarFim(d);
        assertEquals(List.of(), escolhas, "o agente nunca respondeu: nada foi gravado");
    }

    // ---------------------------------------------------------------------------------------------
    // Criar o laboratório
    // ---------------------------------------------------------------------------------------------

    @Test
    void criarMostraOsPassosAoVivoEConcluiAvisandoOApp() throws Exception {
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        pc.limparRegistro();

        Visual.naEdt(() -> d.botaoCriar().doClick());
        esperarFim(d);

        assertEquals("Laboratório local criado", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::terminouComSucesso));
        assertEquals(1, mudancas.get());
        assertEquals(List.of("7070|" + pasta), escolhas);
        assertTrue(Files.isDirectory(Path.of(pasta)));
        assertEquals(EstadoDoPasso.FEITO, Visual.naEdt(() -> d.estadoDoPasso(PassoDoLaboratorio.CONTAINER)));
        assertTrue(Visual.naEdt(d::textoDoLog).contains("$ " + PlanoDeInstalacao.comoTexto(ComandosLocais.versaoDoDocker())));
        assertTrue(pc.containers.containsKey("painel-lab"));
        assertTrue(Visual.naEdt(() -> d.botaoConcluir().isVisible()));
        fotografar(d, "dialogo-preparar-computador-criado");
    }

    @Test
    void falhaAoCriarMostraOErroRealODesfeitoETentarDeNovoConclui() {
        pc.falharEm(ComputadorSimulado.contem(" run -d"), 125, "docker: Error response from daemon: port is already allocated");
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        Visual.naEdt(() -> d.botaoCriar().doClick());
        esperarFim(d);

        assertEquals("Parou em um passo", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoFinal).contains("port is already allocated"), Visual.naEdt(d::textoFinal));
        assertEquals(EstadoDoPasso.FALHOU, Visual.naEdt(() -> d.estadoDoPasso(PassoDoLaboratorio.CONTAINER)));
        assertEquals(EstadoDoPasso.DESFEITO, Visual.naEdt(() -> d.estadoDoPasso(PassoDoLaboratorio.IMAGEM)));
        assertTrue(Visual.naEdt(d::linhasDoDesfeito).stream().anyMatch(l -> l.contains("Removi a imagem")), Visual.naEdt(d::linhasDoDesfeito).toString());
        assertEquals(0, mudancas.get());
        fotografar(d, "dialogo-preparar-computador-falha");

        pc.consertar();
        Visual.naEdt(() -> d.botaoTentarDeNovo().doClick());
        Espera.ate(PRAZO, () -> "situação=" + Visual.naEdt(d::situacaoFinal), () -> "Laboratório local criado".equals(Visual.naEdt(d::situacaoFinal)));
        assertEquals(1, mudancas.get());
    }

    @Test
    void cancelarNoMeioInterrompeDesfazEMostraCancelado() throws Exception {
        CountDownLatch noBuild = new CountDownLatch(1);
        pc.antesDe(ComputadorSimulado.contem(" build "), () -> {
            noBuild.countDown();
            try {
                Thread.sleep(PRAZO.toMillis()); // "o build demora": só sai quando o Cancelar interrompe a thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
        esperarPagina(d, "resultado");
        Visual.naEdt(() -> d.botaoCriar().doClick());
        assertTrue(noBuild.await(PRAZO.toSeconds(), TimeUnit.SECONDS));

        Visual.naEdt(() -> d.botaoCancelarExecucao().doClick());
        assertEquals("Cancelando…", Visual.naEdt(() -> d.botaoCancelarExecucao().getText()));
        esperarFim(d);

        assertEquals("Cancelado", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoFinal).contains("foi desfeito"));
        assertFalse(pc.redes.containsKey("edge"));
        assertEquals(0, mudancas.get());
    }

    @Test
    void cancelarComComandoQueNaoSoltaOferecePassadoOPrazoFecharMesmoAssim() throws Exception {
        DialogoPrepararComputador.definirPrazoParaFecharMesmoAssimParaTeste(300);
        CountDownLatch noBuild = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        try {
            pc.antesDe(ComputadorSimulado.contem(" build "), () -> {
                noBuild.countDown();
                while (true) {
                    try {
                        soltar.await(10, TimeUnit.SECONDS);
                        break;
                    } catch (InterruptedException e) {
                        // ignora de propósito, como um canal que não solta
                    }
                }
            });
            DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.PREPARAR);
            esperarPagina(d, "resultado");
            Visual.naEdt(() -> d.botaoCriar().doClick());
            assertTrue(noBuild.await(PRAZO.toSeconds(), TimeUnit.SECONDS));
            Visual.naEdt(() -> d.botaoCancelarExecucao().doClick());
            assertEquals("Cancelando…", Visual.naEdt(() -> d.botaoCancelarExecucao().getText()));
            Espera.ate(PRAZO, "o botão deixa de ficar preso", () -> "Fechar mesmo assim".equals(Visual.naEdt(() -> d.botaoCancelarExecucao().getText())));
            Visual.naEdt(() -> d.botaoCancelarExecucao().doClick());
            assertTrue(Visual.naEdt(d::fechada));
        } finally {
            soltar.countDown();
            DialogoPrepararComputador.definirPrazoParaFecharMesmoAssimParaTeste(8000);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Remover
    // ---------------------------------------------------------------------------------------------

    private void criarLaboratorioSemTela() throws Exception {
        LaboratorioLocal l = new LaboratorioLocal(pc, new ParametrosDoLaboratorio("painel-lab", 7070, "edge", "labdesktop-agente", pasta), plataforma, PACOTE, ganchos)
            .comSonda(Duration.ofMillis(300), Duration.ofMillis(10));
        assertTrue(l.criar(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA).concluida());
        pc.limparRegistro();
    }

    @Test
    void removerPedeOnomeDoAgenteEOBotaoLevaONome() throws Exception {
        criarLaboratorioSemTela();
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.REMOVER);
        assertEquals("remover", Visual.naEdt(d::paginaAtual));
        assertEquals("Remover painel-lab", Visual.naEdt(() -> d.botaoRemover().getText()));
        assertFalse(Visual.naEdt(() -> d.botaoRemover().isEnabled()), "desligado até digitar o nome");
        assertEquals("Cancelar", Visual.naEdt(() -> d.botaoCancelarRemocao().getText()));

        Visual.naEdt(() -> d.campoDeConfirmacao().setText("outro"));
        assertFalse(Visual.naEdt(() -> d.botaoRemover().isEnabled()));
        Visual.naEdt(() -> d.botaoRemover().doClick());
        assertTrue(pc.comandos.isEmpty(), "sem o nome certo nada roda");
        fotografar(d, "dialogo-remover-laboratorio-local");

        Visual.naEdt(() -> d.campoDeConfirmacao().setText("painel-lab"));
        assertTrue(Visual.naEdt(() -> d.botaoRemover().isEnabled()));
    }

    @Test
    void removerTiraOAgenteMasNuncaOsProjetosEDizIsso() throws Exception {
        criarLaboratorioSemTela();
        Files.writeString(Path.of(pasta).resolve("meu-projeto.txt"), "do usuário");
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.REMOVER);
        Visual.naEdt(() -> d.campoDeConfirmacao().setText("painel-lab"));
        Visual.naEdt(() -> d.botaoRemover().doClick());
        esperarFim(d);

        assertEquals("Laboratório local removido", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoFinal).contains("continuam em " + pasta), Visual.naEdt(d::textoFinal));
        assertTrue(pc.containers.isEmpty());
        assertFalse(pc.redes.containsKey("edge"));
        assertTrue(Files.exists(Path.of(pasta).resolve("meu-projeto.txt")), "os projetos do usuário nunca são apagados");
        assertEquals(1, mudancas.get());
        assertEquals(EstadoDoPasso.FEITO, Visual.naEdt(() -> d.estadoDoPasso(PassoDaRemocaoLocal.CONTAINER)));
        assertTrue(Visual.naEdt(d::linhasDoDesfeito).stream().anyMatch(l -> l.contains("continuam onde estavam")), Visual.naEdt(d::linhasDoDesfeito).toString());
        fotografar(d, "dialogo-remover-laboratorio-local-feito");
    }

    @Test
    void cancelarNaRemocaoNaoRodaNada() throws Exception {
        criarLaboratorioSemTela();
        DialogoPrepararComputador d = abrir(DialogoPrepararComputador.Acao.REMOVER);
        Visual.naEdt(() -> d.botaoCancelarRemocao().doClick());
        assertTrue(Visual.naEdt(d::fechada));
        assertTrue(pc.comandos.isEmpty());
        assertTrue(pc.containers.containsKey("painel-lab"));
    }

    @Test
    void conferirQueFalhaMostraOErroEBotaoTentarDeNovo() {
        DialogoPrepararComputador.Motor quebrado = new DialogoPrepararComputador.Motor() {
            @Override
            public RelatorioDoComputador verificar(String p, int porta) throws Exception {
                throw new java.io.IOException("sem permissão para ler o disco");
            }

            @Override
            public InstaladorDeRequisitos.Resultado instalar(PlanoDeInstalacao plano, ObservadorDaInstalacao o, Cancelamento c) {
                throw new AssertionError("não devia instalar");
            }

            @Override
            public boolean abrirODocker(ObservadorDaInstalacao o, Cancelamento c) {
                throw new AssertionError("não devia abrir");
            }

            @Override
            public LaboratorioLocal laboratorio(ParametrosDoLaboratorio p) {
                throw new AssertionError("não devia criar");
            }

            @Override
            public void abrirPagina(String url) { }

            @Override
            public void guardarEscolhas(int porta, String p) { }
        };
        DialogoPrepararComputador d = Visual.naEdt(() -> new DialogoPrepararComputador(DialogoPrepararComputador.Acao.PREPARAR,
            new DialogoPrepararComputador.Alvo("Este computador", 7070, pasta, "painel-lab", PACOTE.versao(), false), quebrado, () -> { }, avisos,
            new CopiadorDeTexto(() -> new Clipboard("teste"))));
        Visual.naEdt(d::iniciarConferencia);
        Espera.ate(PRAZO, () -> "erro=" + Visual.naEdt(d::textoDaConferencia), () -> !Visual.naEdt(d::textoDaConferencia).isEmpty());
        assertTrue(Visual.naEdt(d::textoDaConferencia).contains("sem permissão para ler o disco"), Visual.naEdt(d::textoDaConferencia));
        assertEquals("conferindo", Visual.naEdt(d::paginaAtual));
    }
}
