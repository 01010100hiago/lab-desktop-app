package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.MonitorDeSono;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ProxyTcp;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O coordenador: um ServidorLab por servidor do cofre, bloqueio do cofre e sono do Mac. */
class GerenciadorServidoresTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private ServidorSshDeTeste ssh;
    private ProxyTcp proxy;
    private AgenteDoLab agente;
    private CofreFalso cofre;
    private GerenciadorServidores gerenciador;

    @BeforeEach
    void montar() throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        proxy = new ProxyTcp(ssh.porta());
        agente = new AgenteDoLab();
        TokenAgente token = TokenAgente.gerar();
        cofre = new CofreFalso("127.0.0.1", proxy.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta())
            .comToken(token, "mac-de-teste");
        ssh.aceitar(cofre.par().getPublic());
        agente.tokens.put(token.hashSha256Hex(), "mac-de-teste");
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        proxy.close();
        agente.close();
        ssh.close();
    }

    @Test
    void criaUmServidorLabPorServidorDoCofreEConectaTodos() {
        assertEquals(1, gerenciador.servidores().size());
        ServidorLab lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        assertEquals(CofreFalso.ID, lab.id());
        assertEquals("lab", lab.config().nome());
        assertEquals(EstadoConexao.DESCONECTADO, lab.estado().conexao(), "só cria; quem inicia é a casca");

        gerenciador.iniciarTodos();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
    }

    @Test
    void adicionarEIdempotenteEServidorDesconhecidoNaoExiste() {
        ServidorLab lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        assertSame(lab, gerenciador.adicionar(CofreFalso.ID));
        assertTrue(gerenciador.servidor("outro").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> gerenciador.adicionar("outro"));
    }

    @Test
    void bloquearOCofreFechaAsConexoesDeTodosENaoReconecta() {
        gerenciador.iniciarTodos();
        ServidorLab lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);

        cofre.bloquear(); // o gancho aoBloquear do cofre

        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.BLOQUEADO_COFRE);
        assertTrue(lab.tunel().isEmpty());
        int conexoes = proxy.conexoesAceitas.get();
        Espera.naoAcontece(Duration.ofMillis(1000), "reconexão com o cofre bloqueado", () -> proxy.conexoesAceitas.get() > conexoes);
        // Nenhuma leitura do cofre derruba nada: a consulta periódica de uma tela esquecida só encontra o cofre fechado.
        lab.definirJanela(EstadoJanela.ATIVA);
    }

    @Test
    void sonoDoMacSuspendeTodosEAcordarReconecta() {
        MonitorDeSono sono = new MonitorDeSono();
        Runnable desfazer = gerenciador.acompanhar(sono);
        gerenciador.iniciarTodos();
        ServidorLab lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);

        sono.systemAboutToSleep(null);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.SUSPENSO);

        sono.systemAwoke(null);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        assertEquals(2, proxy.conexoesAceitas.get(), "ao acordar abre uma sessão nova");

        desfazer.run();
        sono.systemAboutToSleep(null);
        Espera.naoAcontece(Duration.ofMillis(500), "reagir depois de desfeita a ligação",
            () -> lab.estado().conexao() == EstadoConexao.SUSPENSO);
    }

    @Test
    void removerFechaOServidorETiraDaLista() throws Exception {
        gerenciador.iniciarTodos();
        ServidorLab lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);

        gerenciador.remover(CofreFalso.ID).join();

        assertTrue(gerenciador.servidores().isEmpty());
        assertTrue(gerenciador.servidor(CofreFalso.ID).isEmpty());
        gerenciador.remover("nao-existe").join(); // sem efeito
    }

    @Test
    void quemObservaAListaRecebeAAtualEDepoisCadaMudanca() {
        java.util.List<java.util.List<String>> recebidas = new java.util.concurrent.CopyOnWriteArrayList<>();
        Inscricao inscricao = gerenciador.observarLista(l -> recebidas.add(l.stream().map(ServidorLab::id).toList()));
        assertEquals(List.of(List.of(CofreFalso.ID)), recebidas, "a lista atual chega logo na inscrição");

        gerenciador.adicionar(CofreFalso.ID); // já existe: não é mudança
        assertEquals(1, recebidas.size());

        gerenciador.remover(CofreFalso.ID).join();
        assertEquals(List.of(List.of(CofreFalso.ID), List.of()), recebidas);

        inscricao.close();
        gerenciador.remover("nao-existe").join();
        assertEquals(2, recebidas.size(), "depois de soltar a inscrição nada mais chega");
    }

    @Test
    void fecharEIdempotenteERecusaNovosServidores() {
        gerenciador.fechar().join();
        gerenciador.fechar().join();
        assertTrue(gerenciador.servidores().isEmpty());
        assertThrows(IllegalStateException.class, () -> gerenciador.adicionar(CofreFalso.ID));
    }
}
