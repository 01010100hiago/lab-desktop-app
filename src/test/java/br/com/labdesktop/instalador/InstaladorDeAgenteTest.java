package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.GanchosDaInstalacao.Sonda;
import br.com.labdesktop.instalador.ResultadoDaInstalacao.Desfecho;
import br.com.labdesktop.teste.ServidorSimulado;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * A camada de passos do instalador contra um servidor SIMULADO (sem SSH): a sequência exata de comandos, a idempotência, a falha em
 * cada passo com o desfazer parcial (só o que ESTA execução criou), o cancelamento e a retomada. Nada de rede.
 */
class InstaladorDeAgenteTest {

    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1});
    private static final String DK = "docker";

    private final ParametrosDaInstalacao p = ParametrosDaInstalacao.padrao(7000);
    private final ServidorSimulado servidor = new ServidorSimulado(p, PACOTE);
    private final GanchosFalsos ganchos = new GanchosFalsos();
    private final ObservadorGravado obs = new ObservadorGravado();
    private final InstaladorDeAgente instalador = new InstaladorDeAgente(servidor, p, PACOTE, ganchos).comSonda(Duration.ofMillis(150), Duration.ofMillis(10));

    private RelatorioDeVerificacao verificar(ModoDaInstalacao modo) throws Exception {
        servidor.limparRegistro();
        return instalador.verificar(modo);
    }

    private ResultadoDaInstalacao instalar() {
        return instalador.instalar(obs, Cancelamento.NUNCA);
    }

    private static Predicate<String> contem(String trecho) {
        return c -> c.contains(trecho);
    }

    private List<String> escritas() {
        return servidor.escritas();
    }

    // ---------------------------------------------------------------------------------------------
    // A sequência exata
    // ---------------------------------------------------------------------------------------------

    @Test
    void caminhoFelizComDockerPresenteRodaExatamenteEstaSequenciaDeEscritas() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertEquals(List.of(
            ComandosRemotos.criarPasta(p),
            ComandosRemotos.extrairPacote(p, PACOTE),
            ComandosRemotos.construirImagem(DK, p, PACOTE),
            ComandosRemotos.subirContainer(DK, p, PACOTE)), escritas());
        assertEquals(List.of("lab-agente/agente-0.1.0-abc1234.tar.gz"), servidor.envios);
        assertTrue(servidor.containerNome != null && servidor.rotuloDoContainer.equals("0.1.0-abc1234"));
        assertEquals(List.of("sondar", "registrarToken"), ganchos.chamadas);
        assertEquals("docker", ganchos.dockerVisto);
        assertEquals("painel-lab", ganchos.parametrosVistos.container());
        for (PassoDeInstalacao passo : PassoDeInstalacao.values()) {
            assertTrue(obs.ultimo.get(passo) == EstadoDoPasso.FEITO || obs.ultimo.get(passo) == EstadoDoPasso.JA_ESTAVA_FEITO, passo + " " + obs.ultimo.get(passo));
        }
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, obs.ultimo.get(PassoDeInstalacao.DOCKER), "o Docker já estava lá: nada a instalar");
        assertEquals(EstadoDoPasso.FEITO, obs.ultimo.get(PassoDeInstalacao.SUBIR_CONTAINER));
    }

    @Test
    void oComandoQueSobeOContainerEExatamenteEsteEOAgenteSoEscutaNoLoopback() {
        // Texto literal: se alguém "simplificar" isto (porta publicada em 0.0.0.0, sem reinício, sem volume), o teste acusa.
        assertEquals("docker run -d --name painel-lab --restart unless-stopped --network host --pid host"
            + " --label br.com.labdesktop.agente=0.1.0-abc1234 --log-opt max-size=10m --log-opt max-file=3"
            + " -e PORT=7000 -e PAINEL_PERFIL=monitor -e PAINEL_EXIGE_TOKEN=1 -v /var/run/docker.sock:/var/run/docker.sock -v /:/host:ro"
            + " -v painel-lab-dados:" + br.com.labdesktop.instalador.PastasNoContainer.DADOS + " labdesktop-agente:0.1.0-abc1234",
            ComandosRemotos.subirContainer(DK, p, PACOTE));
        assertFalse(ComandosRemotos.subirContainer(DK, p, PACOTE).contains("0.0.0.0"));
        assertFalse(ComandosRemotos.subirContainer(DK, p, PACOTE).contains(" -p "), "nenhuma porta publicada: o agente escuta no loopback do servidor");
        assertEquals("sudo -n docker run -d --name painel-teste --restart unless-stopped --network host --pid host"
            + " --label br.com.labdesktop.agente=0.1.0-abc1234 --log-opt max-size=10m --log-opt max-file=3"
            + " -e PORT=7100 -e PAINEL_PERFIL=monitor -e PAINEL_EXIGE_TOKEN=1 -v /var/run/docker.sock:/var/run/docker.sock -v /:/host:ro"
            + " -v painel-teste-dados:" + br.com.labdesktop.instalador.PastasNoContainer.DADOS + " labdesktop-agente:0.1.0-abc1234",
            ComandosRemotos.subirContainer("sudo -n docker", new ParametrosDaInstalacao("painel-teste", 7100, "lab-agente"), PACOTE));
    }

    @Test
    void semDockerInstalaPeloRepositorioOficialComSudoEDepoisUsaSudoNosOutrosComandos() throws Exception {
        servidor.ubuntu().semDocker(); // tem sudo sem senha e não está no grupo docker (que só vale numa sessão nova)
        RelatorioDeVerificacao rel = verificar(ModoDaInstalacao.INSTALAR);
        assertTrue(rel.vaiInstalarODocker());
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        List<String> esperadoDoDocker = ComandosRemotos.instalarODocker(false, "ubuntu");
        assertEquals(8, esperadoDoDocker.size());
        assertEquals(esperadoDoDocker, escritas().subList(0, 8), "os comandos oficiais da Docker, na ordem, com sudo -n");
        assertTrue(esperadoDoDocker.stream().allMatch(c -> c.startsWith("sudo -n ") || c.startsWith("sh -c 'echo \"deb [arch=")));
        assertTrue(esperadoDoDocker.get(3).contains("https://download.docker.com/linux/ubuntu/gpg"));
        assertTrue(esperadoDoDocker.get(7).contains("docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin"));
        assertEquals(ComandosRemotos.construirImagem("sudo -n docker", p, PACOTE), escritas().stream().filter(c -> c.contains(" build ")).findFirst().orElseThrow(),
            "depois de instalar, o grupo docker ainda não vale nesta sessão: o app usa sudo -n docker");
        assertEquals("sudo -n docker", ganchos.dockerVisto);
        assertEquals(EstadoDoPasso.FEITO, obs.ultimo.get(PassoDeInstalacao.DOCKER));
    }

    @Test
    void rootInstalaODockerSemSudoNemPrefixo() throws Exception {
        servidor.comoRoot().semDocker();
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();
        assertEquals(Desfecho.CONCLUIDA, instalar().desfecho());
        List<String> doDocker = ComandosRemotos.instalarODocker(true, "debian");
        assertEquals(doDocker, escritas().subList(0, 8));
        assertTrue(doDocker.stream().noneMatch(c -> c.contains("sudo")), doDocker.toString());
        assertEquals("docker", ganchos.dockerVisto);
    }

    @Test
    void distribuicaoSemSuporteNaoGeraComandoDeInstalacao() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.instalarODocker(true, "fedora"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.instalarODocker(true, "debian; rm -rf /"));
        assertTrue(ComandosRemotos.distribuicaoSuportada("debian"));
        assertTrue(ComandosRemotos.distribuicaoSuportada("ubuntu"));
        assertFalse(ComandosRemotos.distribuicaoSuportada("linuxmint"));
    }

    // ---------------------------------------------------------------------------------------------
    // Recusa
    // ---------------------------------------------------------------------------------------------

    @Test
    void semVerificarOuComProblemaARecusaNaoToCaEmNada() throws Exception {
        ResultadoDaInstalacao sem = instalar();
        assertEquals(Desfecho.RECUSADA, sem.desfecho());
        assertTrue(servidor.comandos.isEmpty());

        servidor.noGrupoDocker = true;
        servidor.discoLivreKib = 100;
        assertTrue(verificar(ModoDaInstalacao.INSTALAR).bloqueado());
        servidor.limparRegistro();
        ResultadoDaInstalacao com = instalar();
        assertEquals(Desfecho.RECUSADA, com.desfecho());
        assertTrue(servidor.comandos.isEmpty(), "recusada: nenhum comando, nem de leitura: " + servidor.comandos);
        assertTrue(ganchos.chamadas.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Idempotência
    // ---------------------------------------------------------------------------------------------

    @Test
    void rodarDuasVezesNaSegundaNadaMudaESoLe() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Desfecho.CONCLUIDA, instalar().desfecho());

        servidor.limparRegistro();
        servidor.containerNome = "painel-lab";
        ObservadorGravado segunda = new ObservadorGravado();
        ganchos.tokenJaValia = true;
        verificar(ModoDaInstalacao.INSTALAR); // o relatório novo (agora o container é do app: aviso, não problema)
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(segunda, Cancelamento.NUNCA);

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertTrue(servidor.escritas().isEmpty(), "a segunda vez só lê: " + servidor.escritas());
        assertTrue(servidor.envios.isEmpty(), "o pacote não é enviado de novo");
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, segunda.ultimo.get(PassoDeInstalacao.ENVIAR_PACOTE));
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, segunda.ultimo.get(PassoDeInstalacao.CONSTRUIR_IMAGEM));
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, segunda.ultimo.get(PassoDeInstalacao.SUBIR_CONTAINER));
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, segunda.ultimo.get(PassoDeInstalacao.REGISTRAR_TOKEN), "o token do app já valia");
    }

    @Test
    void containerEmOutraVersaoOuParadoESubstituidoMantendoOsDados() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        servidor.volumeExiste = true;
        servidor.pastaExiste = true;
        verificar(ModoDaInstalacao.ATUALIZAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        List<String> w = escritas();
        String antes = ComandosRemotos.renomearContainer(DK, p.container(), p.containerAnterior());
        assertTrue(w.contains(antes), "o agente antigo é GUARDADO (renomeado), não apagado: " + w);
        assertTrue(w.indexOf(antes) < w.indexOf(ComandosRemotos.pararContainer(DK, p.containerAnterior())));
        assertTrue(w.indexOf(ComandosRemotos.pararContainer(DK, p.containerAnterior())) < w.indexOf(ComandosRemotos.subirContainer(DK, p, PACOTE)));
        assertFalse(w.contains(ComandosRemotos.removerContainer(DK, p)), "o agente antigo só sai no FIM, e pelo nome de reserva: " + w);
        String descarte = ComandosRemotos.removerContainerPeloNome(DK, p.containerAnterior());
        assertTrue(w.indexOf(ComandosRemotos.subirContainer(DK, p, PACOTE)) < w.indexOf(descarte), "depois de o novo estar de pé: " + w);
        assertNull(servidor.anteriorNome, "não sobra o agente antigo");
        assertFalse(w.contains(ComandosRemotos.removerVolume(DK, p)), "os dados (e o token) ficam");
        assertEquals("0.1.0-abc1234", servidor.rotuloDoContainer);
        assertTrue(servidor.volumeExiste);
    }

    // ---------------------------------------------------------------------------------------------
    // Falha em cada passo, com o desfazer parcial
    // ---------------------------------------------------------------------------------------------

    @Test
    void falhaAoInstalarODockerNaoCriouNadaENaoTentaDesinstalar() throws Exception {
        servidor.ubuntu().semDocker();
        servidor.falharEm(contem("apt-get install -y docker-ce"), 100, "E: Unable to locate package docker-ce");
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDeInstalacao.DOCKER, r.passo());
        assertTrue(r.erro().contains("Unable to locate package docker-ce"), "o erro REAL do servidor: " + r.erro());
        assertTrue(r.desfeito().isEmpty());
        assertTrue(escritas().stream().noneMatch(c -> c.contains("remove") || c.contains("purge") || c.contains("rm ")), "não desinstala o Docker: " + escritas());
        assertEquals(EstadoDoPasso.FALHOU, obs.ultimo.get(PassoDeInstalacao.DOCKER));
        assertTrue(ganchos.chamadas.isEmpty());
    }

    @Test
    void falhaNoEnvioDoPacoteApagaAPastaQueOAppCriou() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.falharEm(c -> c.startsWith("envio:"), 1, "Connection lost while writing");
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDeInstalacao.ENVIAR_PACOTE, r.passo());
        assertTrue(r.erro().contains("Connection lost while writing"), r.erro());
        assertEquals(List.of("Apaguei a pasta lab-agente do instalador."), r.desfeito());
        assertFalse(servidor.pastaExiste);
        assertNull(servidor.containerNome);
    }

    @Test
    void pacoteQueChegaDiferenteFalhaPeloSha256() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.antesDe(contem("sha256sum"), () -> servidor.pacoteRecebido = new byte[] {0, 0, 0}); // chegou corrompido
        ResultadoDaInstalacao r = instalar();
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDeInstalacao.ENVIAR_PACOTE, r.passo());
        assertTrue(r.erro().contains("SHA-256"), r.erro());
        assertFalse(servidor.pastaExiste, "a pasta criada por esta execução foi desfeita");
        assertNull(servidor.versaoExtraida, "nada foi extraído");
    }

    @Test
    void falhaNoBuildMostraOErroDoDockerEDesfazAPasta() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.falharEm(contem(" build "), 1, "ERROR: failed to solve: node:22-alpine: failed to resolve source metadata");
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(PassoDeInstalacao.CONSTRUIR_IMAGEM, r.passo());
        assertTrue(r.erro().contains("failed to resolve source metadata"), r.erro());
        assertTrue(r.erro().startsWith("O Docker não conseguiu construir a imagem do agente"), r.erro());
        assertEquals(List.of("Apaguei a pasta lab-agente do instalador."), r.desfeito());
        assertFalse(servidor.imagemExiste);
        assertEquals(EstadoDoPasso.DESFEITO, obs.ultimo.get(PassoDeInstalacao.ENVIAR_PACOTE));
    }

    @Test
    void falhaAoSubirOContainerDesfazImagemEPastaEOContainerSoSeExistir() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.falharEm(contem(" run -d "), 125, "docker: Error response from daemon: port is already allocated");
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(PassoDeInstalacao.SUBIR_CONTAINER, r.passo());
        assertTrue(r.erro().contains("port is already allocated"), r.erro());
        assertEquals(List.of("Removi a imagem que o app construiu.", "Apaguei a pasta lab-agente do instalador."), r.desfeito());
        // `docker run` que falha pode deixar um container "Created" (porta ocupada, por exemplo): o desfazer tenta removê-lo, e como aqui ele
        // nem existe, nada é dito ao usuário (a lista acima não tem "Removi o container").
        assertFalse(servidor.imagemExiste);
        assertFalse(servidor.pastaExiste);
    }

    /** Um segundo Cancelar (ou fechar a janela) interrompe a thread no meio do desfazer: a limpeza termina mesmo assim, nunca fica pela metade. */
    @Test
    void interromperADuranteODesfazerNaoAbortaALimpeza() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.falharEm(contem(" run -d "), 125, "docker: port is already allocated");
        servidor.antesDe(c -> c.contains(" rmi "), () -> Thread.currentThread().interrupt()); // o Cancelar de novo, no meio da limpeza
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertFalse(servidor.imagemExiste, "a imagem saiu");
        assertFalse(servidor.pastaExiste, "e a pasta também, mesmo com a interrupção no meio: " + r.desfeito());
        assertTrue(r.desfeito().contains("Apaguei a pasta lab-agente do instalador."), r.desfeito().toString());
        assertTrue(Thread.interrupted(), "a flag de interrupção volta quando a limpeza termina");
    }

    @Test
    void agenteQueNaoRespondeDesfazTudoQueACriouEMostraOsLogsDoContainer() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.logsDoContainer = "Error: listen EADDRINUSE: address already in use 127.0.0.1:7000";
        ganchos.sonda(Sonda.NAO_RESPONDE);
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDeInstalacao.SONDAR, r.passo());
        assertTrue(r.erro().contains("EADDRINUSE"), "o texto do container: " + r.erro());
        assertEquals(List.of("Removi o container painel-lab.", "Removi os dados novos do agente (volume painel-lab-dados).",
            "Removi a imagem que o app construiu.", "Apaguei a pasta lab-agente do instalador."), r.desfeito());
        assertNull(servidor.containerNome);
        assertFalse(servidor.volumeExiste);
        assertFalse(servidor.imagemExiste);
        assertFalse(servidor.pastaExiste);
        assertEquals(EstadoDoPasso.DESFEITO, obs.ultimo.get(PassoDeInstalacao.SUBIR_CONTAINER));
    }

    @Test
    void falhaAoRegistrarOTokenTambemDesfazTudo() throws Exception {
        servidor.noGrupoDocker = true;
        ganchos.falhaAoRegistrar = new IllegalStateException("token_conflito: Já existe um token com esse nome.");
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(PassoDeInstalacao.REGISTRAR_TOKEN, r.passo());
        assertTrue(r.erro().contains("Já existe um token com esse nome"), r.erro());
        assertEquals(4, r.desfeito().size(), r.desfeito().toString());
        assertNull(servidor.containerNome);
    }

    @Test
    void atualizacaoQueFalhaDevolveOAgenteAntigoAoNomeEAoAr() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        servidor.estadoDoContainer = "running";
        servidor.volumeExiste = true;
        servidor.pastaExiste = true;
        ganchos.sonda(Sonda.NAO_RESPONDE); // o novo sobe e não responde
        verificar(ModoDaInstalacao.ATUALIZAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals("painel-lab", servidor.containerNome, "o agente antigo voltou ao nome");
        assertEquals("0.0.9-velha", servidor.rotuloDoContainer, "e é o ANTIGO, não o novo");
        assertEquals("running", servidor.estadoDoContainer, "e voltou a rodar");
        assertNull(servidor.anteriorNome);
        assertTrue(r.desfeito().stream().anyMatch(l -> l.contains("Devolvi o agente anterior")), r.desfeito().toString());
        assertTrue(servidor.volumeExiste);
    }

    @Test
    void atualizacaoCujoNovoNaoSobeDevolveOAntigo() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        servidor.estadoDoContainer = "running";
        servidor.volumeExiste = true;
        servidor.pastaExiste = true;
        servidor.falharEm(contem(" run -d "), 125, "docker: port is already allocated");
        verificar(ModoDaInstalacao.ATUALIZAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDeInstalacao.SUBIR_CONTAINER, r.passo());
        assertEquals("0.0.9-velha", servidor.rotuloDoContainer);
        assertEquals("running", servidor.estadoDoContainer);
    }

    @Test
    void atualizacaoInterrompidaAntesQueDeixouOAntigoGuardadoEOutraTentativaOUsa() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.anteriorNome = "painel-lab-anterior";
        servidor.anteriorRotulo = "0.0.9-velha";
        servidor.anteriorEstado = "exited";
        servidor.volumeExiste = true;
        servidor.pastaExiste = true;
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertEquals("0.1.0-abc1234", servidor.rotuloDoContainer);
        assertNull(servidor.anteriorNome);
    }

    @Test
    void containerDeReservaQueNaoEDoAppNuncaESobrescrito() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        servidor.anteriorNome = "painel-lab-anterior";
        servidor.anteriorRotulo = ""; // sem o rótulo do app: é de outra pessoa
        servidor.pastaExiste = true;
        verificar(ModoDaInstalacao.ATUALIZAR);

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertTrue(r.erro().contains("painel-lab-anterior"), r.erro());
        assertEquals("painel-lab-anterior", servidor.anteriorNome);
        assertEquals("0.0.9-velha", servidor.rotuloDoContainer);
    }

    @Test
    void naAtualizacaoUmaFalhaNaoApagaOsDadosQueJaExistiam() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        servidor.volumeExiste = true;
        servidor.pastaExiste = true;
        ganchos.sonda(Sonda.NAO_RESPONDE);
        verificar(ModoDaInstalacao.ATUALIZAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertTrue(servidor.volumeExiste, "o volume já existia antes desta execução: nunca é apagado pelo desfazer");
        assertFalse(escritas().contains(ComandosRemotos.removerVolume(DK, p)));
        assertTrue(servidor.pastaExiste, "a pasta também já existia");
        assertFalse(escritas().contains(ComandosRemotos.removerPasta(p)));
    }

    // ---------------------------------------------------------------------------------------------
    // Retomada
    // ---------------------------------------------------------------------------------------------

    @Test
    void tentarDeNovoRetomaOQueFicouDeEscritaEPulaOQueContinuaDePe() throws Exception {
        servidor.ubuntu().semDocker();
        servidor.falharUmaVezEm(contem(" run -d "), 125, "docker: daemon hiccup");
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();

        ResultadoDaInstalacao primeira = instalar();
        assertEquals(PassoDeInstalacao.SUBIR_CONTAINER, primeira.passo());
        assertTrue(servidor.dockerInstalado, "o Docker instalado FICA (não se desinstala)");

        servidor.limparRegistro();
        ObservadorGravado segunda = new ObservadorGravado();
        ResultadoDaInstalacao r = instalador.instalar(segunda, Cancelamento.NUNCA);

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertTrue(escritas().stream().noneMatch(c -> c.contains("apt-get") || c.contains("tee /etc/apt")), "o Docker não é instalado de novo: " + escritas());
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, segunda.ultimo.get(PassoDeInstalacao.DOCKER));
        assertEquals(EstadoDoPasso.FEITO, segunda.ultimo.get(PassoDeInstalacao.ENVIAR_PACOTE), "a pasta tinha sido desfeita: reenvia");
        assertEquals(EstadoDoPasso.FEITO, segunda.ultimo.get(PassoDeInstalacao.SUBIR_CONTAINER));
        assertNotNull(servidor.containerNome);
    }

    // ---------------------------------------------------------------------------------------------
    // Cancelamento
    // ---------------------------------------------------------------------------------------------

    @Test
    void cancelarNoMeioParaDesfazOQueFoiCriadoENaoSegueParaOsProximosPassos() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();
        AtomicBoolean cancelou = new AtomicBoolean();
        servidor.antesDe(contem(" build "), () -> cancelou.set(true)); // o usuário aperta Cancelar enquanto a imagem constrói

        ResultadoDaInstalacao r = instalador.instalar(obs, cancelou::get);

        assertEquals(Desfecho.CANCELADA, r.desfecho());
        assertEquals(PassoDeInstalacao.SUBIR_CONTAINER, r.passo(), "o cancelamento vale antes do próximo passo");
        assertEquals(List.of("Removi a imagem que o app construiu.", "Apaguei a pasta lab-agente do instalador."), r.desfeito());
        assertNull(servidor.containerNome);
        assertTrue(escritas().stream().noneMatch(c -> c.contains(" run -d ")), "o container nem chegou a subir");
        assertTrue(ganchos.chamadas.isEmpty());
        assertFalse(servidor.pastaExiste);
    }

    @Test
    void interromperAThreadSoltaOComandoEmAndamentoEDesfaz() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.limparRegistro();
        // o comando de build "demora": quem cancela interrompe a thread, e o executor real (ExecRemoto) lança InterruptedException
        servidor.antesDe(contem(" build "), () -> Thread.currentThread().interrupt());

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.CANCELADA, r.desfecho());
        assertFalse(Thread.currentThread().isInterrupted(), "o instalador limpa a interrupção para poder desfazer");
        assertFalse(servidor.pastaExiste);
        assertFalse(servidor.imagemExiste);
    }

    @Test
    void queSeCancelaEFalhaNoDesfazerOErroOriginalNaoSePerde() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.falharEm(contem(" rmi "), 1, "image is being used");
        servidor.falharEm(contem(" run -d "), 125, "boom");
        verificar(ModoDaInstalacao.INSTALAR);
        ResultadoDaInstalacao r = instalar();
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertTrue(r.erro().contains("boom"), r.erro());
        assertEquals(List.of("Apaguei a pasta lab-agente do instalador."), r.desfeito(), "o que não deu para desfazer não aparece como feito");
    }

    // ---------------------------------------------------------------------------------------------
    // Container que não é do app
    // ---------------------------------------------------------------------------------------------

    @Test
    void containerDeOutroDonoNuncaETocadoNemSeAVerificacaoFoiBurlada() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.containerNome = "painel-lab"; // apareceu DEPOIS da verificação (sem o rótulo do instalador)
        servidor.rotuloDoContainer = "";
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDeInstalacao.SUBIR_CONTAINER, r.passo());
        assertTrue(r.erro().contains("não foi criado por este app"), r.erro());
        assertFalse(escritas().contains(ComandosRemotos.removerContainer(DK, p)), "o container que não é do agente nunca é removido");
        assertEquals("painel-lab", servidor.containerNome);
    }

    // ---------------------------------------------------------------------------------------------
    // Remover
    // ---------------------------------------------------------------------------------------------

    @Test
    void removerApagaSoOQueOInstaladorCriouEMantemODocker() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Desfecho.CONCLUIDA, instalar().desfecho());
        servidor.limparRegistro();

        ResultadoDaRemocao r = instalador.remover(obs, Cancelamento.NUNCA);

        assertTrue(r.removido());
        assertFalse(r.recusada());
        assertEquals(List.of(
            ComandosRemotos.removerContainer(DK, p),
            ComandosRemotos.removerVolume(DK, p),
            ComandosRemotos.removerImagem(DK, p.imagem(PACOTE.versao())),
            ComandosRemotos.removerPasta(p)), escritas());
        assertTrue(servidor.dockerInstalado, "o Docker fica");
        assertNull(servidor.containerNome);
        assertFalse(servidor.volumeExiste);
        assertFalse(servidor.pastaExiste);
        assertEquals(4, r.feito().size());
        assertTrue(r.avisos().isEmpty());
    }

    @Test
    void removerRecusaOContainerQueNaoEDoApp() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "";
        ResultadoDaRemocao r = instalador.remover(obs, Cancelamento.NUNCA);
        assertTrue(r.recusada());
        assertFalse(r.removido());
        assertTrue(servidor.escritas().isEmpty(), "nada foi tocado: " + servidor.escritas());
        assertEquals("painel-lab", servidor.containerNome);
    }

    /** Sem o container do app não se sabe de quem são o volume e a imagem: ficam. A pasta com o marcador é do app e sai. */
    @Test
    void removerSemContainerDoAppNaoApagaVolumeNemImagem() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.volumeExiste = true;   // o volume "painel-lab-dados" pode ser de outra instalação
        servidor.imagemExiste = true;
        servidor.pastaExiste = true;
        ResultadoDaRemocao r = instalador.remover(obs, Cancelamento.NUNCA);

        assertFalse(servidor.escritas().contains(ComandosRemotos.removerVolume(DK, p)), servidor.escritas().toString());
        assertFalse(servidor.escritas().contains(ComandosRemotos.removerImagem(DK, p.imagem(PACOTE.versao()))));
        assertTrue(servidor.volumeExiste);
        assertTrue(servidor.imagemExiste);
        assertFalse(servidor.pastaExiste, "a pasta é do app (marcador): sai");
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("deixei os dados e a imagem")), r.avisos().toString());
    }

    @Test
    void removerNuncaApagaUmaPastaQueNaoELDoApp() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.1.0-abc1234";
        servidor.volumeExiste = true;
        servidor.imagemExiste = true;
        servidor.pastaExiste = true;
        servidor.pastaAlheia = true;
        ResultadoDaRemocao r = instalador.remover(obs, Cancelamento.NUNCA);

        assertTrue(r.removido());
        assertTrue(servidor.pastaExiste, "a pasta alheia continua");
        assertFalse(servidor.escritas().contains(ComandosRemotos.removerPasta(p)), "nem o comando de apagar foi enviado");
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("não foi criada por este app")), r.avisos().toString());
    }

    @Test
    void remocaoApagaAImagemDaVersaoQueEstavaInstaladaENaoADoPacote() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        servidor.volumeExiste = true;
        servidor.imagemExiste = true;
        servidor.pastaExiste = true;
        instalador.remover(obs, Cancelamento.NUNCA);
        assertTrue(servidor.comandos.contains(ComandosRemotos.removerImagem(DK, p.imagem("0.0.9-velha"))), servidor.comandos.toString());
        assertFalse(servidor.comandos.contains(ComandosRemotos.removerImagem(DK, p.imagem(PACOTE.versao()))));
    }

    @Test
    void pastaAlheiaImpedeAInstalacaoNaVerificacaoENoPasso() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.pastaExiste = true;
        servidor.pastaAlheia = true;
        RelatorioDeVerificacao rel = verificar(ModoDaInstalacao.INSTALAR);

        assertTrue(rel.bloqueado());
        assertEquals(ResultadoDaVerificacao.Estado.PROBLEMA, rel.de(ResultadoDaVerificacao.Item.PASTA).estado());
        assertTrue(rel.de(ResultadoDaVerificacao.Item.PASTA).frase().contains("NÃO foi criada por este app"));
        assertEquals(Desfecho.RECUSADA, instalar().desfecho());
        assertTrue(servidor.escritas().isEmpty(), servidor.escritas().toString());
    }

    @Test
    void passoDeEnviarOPacoteRecusaPastaAlheiaMesmoSemAVerificacao() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        servidor.pastaExiste = true;   // apareceu depois da conferência
        servidor.pastaAlheia = true;
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalar();

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDeInstalacao.ENVIAR_PACOTE, r.passo());
        assertTrue(r.erro().contains("NÃO foi criada por este app"), r.erro());
        assertFalse(servidor.escritas().contains(ComandosRemotos.extrairPacote(p, PACOTE)), "o rm -rf do src nunca rodou");
        assertTrue(servidor.pastaExiste);
    }

    @Test
    void removerSemPermissaoNoDockerNaoFazNada() throws Exception {
        servidor.semPermissaoNenhuma();
        ResultadoDaRemocao r = instalador.remover(obs, Cancelamento.NUNCA);
        assertFalse(r.removido());
        assertTrue(servidor.escritas().isEmpty());
        assertFalse(r.avisos().isEmpty());
    }

    @Test
    void removerContinuaDepoisDeUmPassoQueFalhaEContaOAviso() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.1.0-abc1234";
        servidor.volumeExiste = true;
        servidor.imagemExiste = true;
        servidor.pastaExiste = true;
        servidor.falharEm(contem(" rmi "), 1, "conflict: image is being used by container x");
        ResultadoDaRemocao r = instalador.remover(obs, Cancelamento.NUNCA);
        assertTrue(r.removido());
        assertEquals(1, r.avisos().size(), r.avisos().toString());
        assertTrue(r.avisos().get(0).contains("image is being used"), r.avisos().get(0));
        assertFalse(servidor.pastaExiste, "o passo seguinte rodou mesmo assim");
    }

    // ---------------------------------------------------------------------------------------------
    // O log
    // ---------------------------------------------------------------------------------------------

    @Test
    void oLogCompletoTemOsComandosEAFimDaSaida() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        instalar();
        List<String> log = new ArrayList<>(obs.log);
        assertTrue(log.contains("$ " + ComandosRemotos.subirContainer(DK, p, PACOTE)), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("Successfully built")), "a saída do build entra no log");
        assertTrue(log.stream().anyMatch(l -> l.startsWith("[enviando lab-agente/agente-0.1.0-abc1234.tar.gz")));
    }
}
