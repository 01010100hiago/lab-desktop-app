package br.com.labdesktop.ui;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import java.time.Duration;
import java.util.List;

/**
 * O mundo de um teste de tela com servidor VIVO: {@code SshServer} embutido, agente de mentira (as rotas do mock do
 * front), cofre falso com o token já registrado e um {@link ServidorLab} conectado de verdade, pelo túnel. É o mesmo
 * caminho que a janela percorre, sem a janela.
 */
public final class CenarioDeUi implements AutoCloseable {

    public final ServidorSshDeTeste ssh;
    public final AgenteDoLab agente;
    public final CofreFalso cofre;
    public final GerenciadorServidores gerenciador;
    public final ServidorLab lab;

    public CenarioDeUi() throws Exception {
        this(PedidoDeCodigo2fa.NUNCA, true);
    }

    public CenarioDeUi(PedidoDeCodigo2fa pedido, boolean conectar) throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        ssh.comandoRemoto(agente.comandosPorSsh());
        TokenAgente token = TokenAgente.gerar();
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta())
            .comToken(token, "mac-de-teste");
        ssh.aceitar(cofre.par().getPublic());
        agente.tokens.put(token.hashSha256Hex(), "mac-de-teste");
        gerenciador = new GerenciadorServidores(cofre, pedido, ConfigSsh.rapida());
        lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        if (conectar) {
            lab.iniciar();
            Espera.ate(Duration.ofSeconds(20), () -> "estado=" + lab.estado(),
                () -> lab.estado().conexao() == EstadoConexao.CONECTADO && lab.estado().limites() != null);
        }
    }

    /** Pedidos que o agente recebeu com este método e este caminho. */
    public List<AgenteFalso.Pedido> pedidos(String metodo, String caminho) {
        return agente.http.pedidos.stream().filter(p -> p.metodo().equals(metodo) && p.caminho().equals(caminho)).toList();
    }

    public boolean recebeu(String metodo, String caminho) {
        return !pedidos(metodo, caminho).isEmpty();
    }

    @Override
    public void close() throws java.io.IOException {
        gerenciador.fechar().join();
        agente.close();
        ssh.close();
    }
}
