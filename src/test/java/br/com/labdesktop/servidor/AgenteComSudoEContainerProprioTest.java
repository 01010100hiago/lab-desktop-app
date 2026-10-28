package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.AgenteInstalado;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.InstaladorDeAgente;
import br.com.labdesktop.instalador.ModoDaInstalacao;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
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
 * Um usuário que só chega ao Docker por {@code sudo -n} e um agente instalado com outro nome de container ({@code painel-teste}, para testar
 * ao lado do agente do lab): depois da instalação, token, 2FA, rotação e diagnóstico têm de falar com ESSE container e COM sudo (antes só a
 * instalação o fazia, e o resto rodava {@code docker exec -i painel-lab} sem sudo).
 */
class AgenteComSudoEContainerProprioTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {5, 4, 3, 2, 1, 0, 1, 2, 3});
    private static final String PREFIXO = "sudo -n docker exec -i painel-teste node acesso.js ";

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
        agente = new AgenteDoLab();
        agente.fora = true;
        parametros = new ParametrosDaInstalacao("painel-teste", agente.porta(), "lab-agente");
        servidor = new ServidorSimulado(parametros, PACOTE, pessoal);
        servidor.noGrupoDocker = false; // só o sudo -n chega ao Docker
        servidor.sudoSemSenha = true;
        ssh = ServidorSshDeTeste.ed25519().comSftp(pessoal);
        ssh.comandoRemoto(servidor.comoManipulador(agente.comandosPorSsh()));
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
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() != SituacaoDoAgente.DESCONHECIDA);
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        ssh.close();
    }

    private void instalar() throws Exception {
        InstaladorDeAgente inst = lab.instaladorDoAgente(parametros, PACOTE).comSonda(Duration.ofSeconds(10), Duration.ofMillis(50));
        inst.verificar(ModoDaInstalacao.INSTALAR);
        assertTrue(inst.instalar(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA).concluida());
        lab.aoMudarOAgenteNoServidor();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
    }

    @Test
    void antesDeInstalarODiagnosticoJaFalaPorSudoNoContainerPadraoEDizNaoInstalado() {
        // o usuário só tem sudo: o diagnóstico usa o comando certo (sem sudo daria "sem permissão", que oferece Instalar do mesmo jeito)
        assertTrue(lab.estado().situacaoDoAgente().oferecerInstalar(), lab.estado().toString());
    }

    @Test
    void depoisDeInstalarOTokenOs2faEARevogacaoUsamOContainerInstaladoEOSudo() throws Exception {
        instalar();
        assertEquals(new AgenteInstalado("painel-teste", true), cofre.agenteInstalado(lab.id()).orElseThrow(), "o cofre lembra");
        assertTrue(agente.comandosCli.stream().allMatch(l -> l.startsWith(PREFIXO)), "o registro do token já foi assim: " + agente.comandosCli);
        agente.comandosCli.clear();

        lab.iniciarConfiguracao2fa();           // configurar o 2FA
        lab.reprovisionarToken("meu-mac");       // revogar e registrar o acesso
        lab.revogarAcessoDoApp(lab.lerAcessoDoApp().orElseThrow(), false); // revogar o acesso

        assertFalse(agente.comandosCli.isEmpty());
        for (String l : agente.comandosCli) {
            assertTrue(l.startsWith(PREFIXO), "toda chamada ao agente usa o container instalado e o sudo: " + l);
        }
        assertTrue(agente.comandosCli.stream().anyMatch(l -> l.endsWith("totp-iniciar")));
        assertTrue(agente.comandosCli.stream().anyMatch(l -> l.endsWith("token-registrar")));
    }

    @Test
    void oRegistroSobreviveAReiniciarOAppEDepoisDeRemoverOAgenteEEsquecido() throws Exception {
        instalar();
        gerenciador.fechar().join();
        GerenciadorServidores novo = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        try {
            ServidorLab outro = novo.servidor(lab.id()).orElseThrow();
            outro.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + outro.estado(), () -> outro.estado().conexao() == EstadoConexao.CONECTADO);
            agente.comandosCli.clear();
            outro.iniciarConfiguracao2fa();
            assertEquals(PREFIXO + "totp-iniciar", agente.comandosCli.get(0), "um app novo ainda fala com painel-teste por sudo");

            // o container sai: o agente deixa de responder e o diagnóstico (agora com o container e o sudo certos) diz "ausente"
            agente.fora = true;
            servidor.containerNome = null;
            servidor.rotuloDoContainer = "";
            outro.conexao().verificarAgora();
            Espera.ate(PRAZO, () -> "estado=" + outro.estado(), () -> outro.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
            Espera.ate(PRAZO, () -> "registro=" + cofre.agenteInstalado(lab.id()), () -> cofre.agenteInstalado(lab.id()).isEmpty());
        } finally {
            novo.fechar().join();
        }
    }
}
