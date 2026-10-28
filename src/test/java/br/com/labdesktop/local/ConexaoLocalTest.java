package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ssh.InfoConexao;
import br.com.labdesktop.ssh.SondaDoAgente;
import br.com.labdesktop.teste.CofreFalso;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A conexão de "Este computador": sem SSH e sem túnel, só o teste do agente em 127.0.0.1. */
class ConexaoLocalTest {

    private static final Duration PRAZO = Duration.ofSeconds(10);

    private CofreFalso cofre;
    private ConexaoLocal conexao;
    private final AtomicReference<SondaDoAgente.Resultado> resposta = new AtomicReference<>(SondaDoAgente.Resultado.OK);
    private final AtomicInteger sondagens = new AtomicInteger();
    private final List<InfoConexao> historico = new CopyOnWriteArrayList<>();
    private final List<Integer> portasSondadas = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() {
        cofre = new CofreFalso("127.0.0.1", 22, List.of()).comoLocal(7070);
        conexao = new ConexaoLocal(CofreFalso.ID, cofre, ConfigLocal.rapida(), p -> {
            sondagens.incrementAndGet();
            portasSondadas.add(p.portaLocal());
            return resposta.get();
        }, br.com.labdesktop.util.Agendador.doFundo(), (c, in, prazo) -> {
            throw new java.io.IOException("não roda nada neste teste");
        });
        conexao.observar(historico::add);
    }

    @AfterEach
    void desmontar() {
        conexao.fechar().join();
    }

    private EstadoConexao estado() {
        return conexao.info().estado();
    }

    @Test
    void comOAgenteRespondendoVaiDeDesconectadoAConectadoSemNenhumEstadoDeSshNemDeRede() {
        assertEquals(EstadoConexao.DESCONECTADO, estado());
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);

        assertEquals(7070, conexao.info().portaLocal(), "a ponta é a porta do Docker em 127.0.0.1: sem túnel");
        assertEquals(7070, conexao.pontaDoAgente().orElseThrow().portaLocal());
        assertEquals(List.of(7070), portasSondadas.subList(0, 1));
        for (InfoConexao i : historico) {
            assertFalse(List.of(EstadoConexao.RECONECTANDO, EstadoConexao.ERRO_AUTENTICACAO, EstadoConexao.ERRO_CHAVE_HOST).contains(i.estado()),
                "não há rede nem chave: " + i.estado());
        }
    }

    @Test
    void agenteForaFicaEmSemAgenteEInsisteSozinhoAteElePassarAResponder() {
        resposta.set(SondaDoAgente.Resultado.AGENTE_FORA);
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.SSH_OK_SEM_AGENTE);
        assertEquals(Falha.Tipo.LOCAL_AGENTE_FORA, conexao.info().falha().tipo());
        int antes = sondagens.get();
        Espera.ate(PRAZO, "insiste no teste do agente sem derrubar nada", () -> sondagens.get() >= antes + 2);

        resposta.set(SondaDoAgente.Resultado.OK);
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
        assertEquals(null, conexao.info().falha());
    }

    @Test
    void tokenRecusadoEDoisFatoresSaoOsMesmosEstadosDoRemoto() {
        resposta.set(SondaDoAgente.Resultado.TOKEN_RECUSADO);
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.TOKEN_RECUSADO);
        resposta.set(SondaDoAgente.Resultado.REQUER_2FA);
        conexao.tentarAgora();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.AGUARDANDO_2FA);
        resposta.set(SondaDoAgente.Resultado.OK);
        conexao.tentarAgora();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
    }

    @Test
    void verificarAgoraPercebeOAgenteQueMorreuEInformarTokenMudaOEstado() {
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
        resposta.set(SondaDoAgente.Resultado.AGENTE_FORA);
        conexao.verificarAgora();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.SSH_OK_SEM_AGENTE);
        resposta.set(SondaDoAgente.Resultado.OK);
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
        conexao.informarTokenRecusado();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.TOKEN_RECUSADO);
    }

    @Test
    void dormirNaoDerrubaENadaMudaAoAcordarComOAgenteBem() {
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
        conexao.aoDormir();
        assertEquals(EstadoConexao.CONECTADO, estado(), "o agente é um container local: dormir não o derruba");
        int antes = sondagens.get();
        conexao.aoAcordar();
        Espera.ate(PRAZO, "ao acordar só se confere o agente", () -> sondagens.get() > antes);
        assertEquals(EstadoConexao.CONECTADO, estado());
    }

    @Test
    void bloquearOCofrePara_eDesbloquearERecomecaDoZero() {
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
        conexao.bloquear();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.BLOQUEADO_COFRE);
        assertTrue(conexao.pontaDoAgente().isEmpty());
        assertEquals(null, conexao.info().portaLocal());
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
    }

    @Test
    void reconectarLeAPortaNovaDoCofreEAGeracaoCresce() {
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
        long g1 = conexao.info().geracao();
        cofre.comoLocal(7080);
        conexao.reconectar();
        Espera.ate(PRAZO, () -> "porta=" + conexao.info().portaLocal(), () -> Integer.valueOf(7080).equals(conexao.info().portaLocal()));
        assertTrue(conexao.info().geracao() > g1, "o ClienteApi nasce de novo");
    }

    @Test
    void fecharEIdempotenteEPoeODesconectado() {
        conexao.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> estado() == EstadoConexao.CONECTADO);
        conexao.fechar().join();
        conexao.fechar().join();
        assertEquals(EstadoConexao.DESCONECTADO, estado());
        assertTrue(conexao.pontaDoAgente().isEmpty());
        assertEquals(7070, ConfigServidor.PORTA_AGENTE_LOCAL_PADRAO, "a porta padrão do local NÃO é a 7000 (o AirPlay do macOS a ocupa)");
    }

    @Test
    void executarSoAceitaAsLinhasDoAgenteESemShell() throws Exception {
        List<List<String>> rodou = new CopyOnWriteArrayList<>();
        ConexaoLocal c = new ConexaoLocal(CofreFalso.ID, cofre, ConfigLocal.rapida(), p -> SondaDoAgente.Resultado.OK,
            br.com.labdesktop.util.Agendador.doFundo(), new ExecutorDeLinhaFixa((a, in, prazo) -> {
                rodou.add(a);
                return new br.com.labdesktop.ssh.ResultadoExec(0, "{\"ok\":true}\n".getBytes(), new byte[0], false);
            }));
        c.executar("docker exec -i painel-lab node acesso.js token-registrar", "x\n".getBytes(), Duration.ofSeconds(5));
        assertEquals(List.of(List.of("docker", "exec", "-i", "painel-lab", "node", "acesso.js", "token-registrar")), rodou);
        for (String ruim : List.of("sh -c 'rm -rf /'", "docker exec -i painel-lab node acesso.js token-registrar; rm x", "sudo -n docker exec -i x node acesso.js totp-iniciar",
            "docker ps", "docker exec -i painel-lab node acesso.js outro", "docker exec -i Pai\"nel node acesso.js token-revogar", "", "docker exec -i a  node acesso.js totp-iniciar")) {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> c.executar(ruim, new byte[0], Duration.ofSeconds(1)), ruim);
        }
        assertEquals(1, rodou.size(), "nada além da linha do agente chegou ao processo");
        c.fechar().join();
    }
}
