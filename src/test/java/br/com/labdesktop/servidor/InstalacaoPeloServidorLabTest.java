package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.instalador.InstaladorDeAgente;
import br.com.labdesktop.instalador.ModoDaInstalacao;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaRemocao;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSimulado;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O instalador do agente ponta a ponta com o que o app tem de verdade: a sessão SSH real (servidor SSH embutido, com SFTP para o
 * envio do pacote), o cofre, o túnel e os ganchos do {@code ServidorLab} (sondar o agente pelo túnel e registrar o token). O servidor
 * é o {@link ServidorSimulado}; o "agente" só passa a responder depois que o container sobe. Nada de rede externa.
 */
class InstalacaoPeloServidorLabTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {5, 4, 3, 2, 1, 0, 1, 2, 3});

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private ServidorLab lab;
    private ServidorSimulado servidor;
    private ParametrosDaInstalacao parametros;

    @BeforeEach
    void montar() throws Exception {
        Path pessoal = tmp.resolve("pessoal");
        Files.createDirectories(pessoal);
        parametros = ParametrosDaInstalacao.padrao(7000);
        servidor = new ServidorSimulado(parametros, PACOTE, pessoal);
        servidor.noGrupoDocker = true;
        agente = new AgenteDoLab();
        agente.fora = true;
        ssh = ServidorSshDeTeste.ed25519().comSftp(pessoal);
        ssh.comandoRemoto(servidor.comoManipulador(agente.comandosPorSsh()));
        // o agente "sobe" quando o container sobe: o monitor responde, sem Claude
        servidor.antesDe(c -> c.contains(" run -d "), () -> {
            agente.perfil = "monitor";
            agente.fora = false;
        });
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        cofre = CofreDeChaves.criar(caminhos, SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        InfoChave chave = assistente.gerarChave("lab");
        SondagemDeHost.Resultado sonda = assistente.sondar("127.0.0.1", ssh.porta());
        lab = assistente.cadastrar(new Dados("lab", "127.0.0.1", ssh.porta(), "labuser", agente.porta()), chave.id(), sonda.pin());
        ssh.aceitar(cofre.keyPairDoServidor(lab.id()).getPublic());
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        ssh.close();
    }

    @Test
    void instalarDeVerdadeEnviaOPacoteSobeOContainerRegistraOTokenEOAppPassaAConectar() throws Exception {
        servidor.limparRegistro();
        InstaladorDeAgente inst = lab.instaladorDoAgente(parametros, PACOTE).comSonda(Duration.ofSeconds(10), Duration.ofMillis(50));

        var relatorio = inst.verificar(ModoDaInstalacao.INSTALAR);
        assertFalse(relatorio.bloqueado(), relatorio.problemas().toString());
        ResultadoDaInstalacao r = inst.instalar(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA);

        assertTrue(r.concluida(), r.erro());
        // o pacote chegou ao servidor POR SFTP, byte a byte, e foi extraído
        assertEquals(java.util.List.of(5, 4, 3, 2, 1, 0, 1, 2, 3), bytesComoLista(Files.readAllBytes(
            tmp.resolve("pessoal").resolve(ComandosRemotos.caminhoDoPacote(parametros, PACOTE)))));
        assertEquals("0.1.0-abc1234", servidor.versaoExtraida);
        assertEquals("painel-lab", servidor.containerNome);
        assertEquals(1, agente.tokens.size(), "o token deste computador foi registrado (por docker exec, no agente que acabou de subir)");
        assertTrue(cofre.tokenDoServidor(lab.id()).isPresent(), "e guardado no cofre");

        lab.aoMudarOAgenteNoServidor();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        assertTrue(lab.estado().agenteDoMonitor());
        assertEquals(Boolean.FALSE, lab.estado().temClaude());
        assertEquals(SituacaoDoAgente.DESCONHECIDA, lab.estado().situacaoDoAgente());
    }

    @Test
    void naoInstalaNadaSemOClique_soODonoAcionaOInstalador() throws Exception {
        // Montar o instalador e verificar NÃO escreve nada: a escrita só começa em instalar(), que é do clique na tela de confirmação.
        servidor.limparRegistro();
        InstaladorDeAgente inst = lab.instaladorDoAgente(parametros, PACOTE);
        inst.verificar(ModoDaInstalacao.INSTALAR);
        assertTrue(servidor.escritas().isEmpty(), "verificar só lê: " + servidor.escritas());
        assertTrue(servidor.envios.isEmpty());
        assertEquals(0, agente.tokens.size());
    }

    @Test
    void removerDepoisDeInstaladoDeixaOServidorSemOAgenteEOAppVoltaAPerguntar() throws Exception {
        InstaladorDeAgente inst = lab.instaladorDoAgente(parametros, PACOTE).comSonda(Duration.ofSeconds(10), Duration.ofMillis(50));
        inst.verificar(ModoDaInstalacao.INSTALAR);
        assertTrue(inst.instalar(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA).concluida());
        lab.aoMudarOAgenteNoServidor();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);

        // o container sai: o agente deixa de responder e o diagnóstico passa a dizer "ausente"
        servidor.antesDe(c -> c.startsWith("docker rm -f painel-lab"), () -> {
            agente.fora = true;
            agente.diagnostico = "ausente";
        });
        ResultadoDaRemocao rem = inst.remover(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA);
        assertTrue(rem.removido(), rem.avisos().toString());
        assertTrue(servidor.dockerInstalado);
        assertEquals(null, servidor.containerNome);

        lab.aoMudarOAgenteNoServidor();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
        assertEquals(EstadoConexao.SSH_OK_SEM_AGENTE, lab.estado().conexao());
    }

    @Test
    void falhaNoMeioDesfazEMostraOErroRealETentarDeNovoConclui() throws Exception {
        servidor.falharUmaVezEm(c -> c.contains(" build "), 1, "ERROR: failed to solve: DeadlineExceeded");
        InstaladorDeAgente inst = lab.instaladorDoAgente(parametros, PACOTE).comSonda(Duration.ofSeconds(10), Duration.ofMillis(50));
        inst.verificar(ModoDaInstalacao.INSTALAR);

        ResultadoDaInstalacao falhou = inst.instalar(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA);
        assertEquals(ResultadoDaInstalacao.Desfecho.FALHOU, falhou.desfecho());
        assertTrue(falhou.erro().contains("DeadlineExceeded"), falhou.erro());
        assertFalse(servidor.pastaExiste, "a pasta que o instalador criou foi desfeita");
        assertFalse(Files.exists(tmp.resolve("pessoal").resolve("lab-agente")));

        ResultadoDaInstalacao de_novo = inst.instalar(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA);
        assertTrue(de_novo.concluida(), de_novo.erro());
    }

    private static java.util.List<Integer> bytesComoLista(byte[] b) {
        java.util.List<Integer> l = new java.util.ArrayList<>();
        for (byte x : b) {
            l.add((int) x);
        }
        return l;
    }
}
