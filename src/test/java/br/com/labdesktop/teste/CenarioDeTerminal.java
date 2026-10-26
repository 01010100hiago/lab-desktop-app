package br.com.labdesktop.teste;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ssh.ConexaoSsh;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondaDoAgente;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;

/**
 * O mundo de um teste do terminal: servidor SSH embutido com um terminal de mentira, cofre falso e uma {@link ConexaoSsh} de verdade
 * (com a sonda do agente sempre verde: o terminal nem precisa do agente). Fecha tudo no {@code close()}.
 */
public final class CenarioDeTerminal implements AutoCloseable {

    public final ServidorSshDeTeste ssh;
    public final CofreFalso cofre;
    public final ConexaoSsh conexao;
    public final ServidorDeTerminalFalso terminal;

    private CenarioDeTerminal(ConfigSsh cfg) throws IOException, GeneralSecurityException {
        this.ssh = ServidorSshDeTeste.ed25519();
        this.cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin()));
        ssh.aceitar(cofre.par().getPublic());
        this.terminal = new ServidorDeTerminalFalso(ssh);
        this.conexao = new ConexaoSsh(CofreFalso.ID, cofre, cfg, tunel -> SondaDoAgente.Resultado.OK);
    }

    /** Conectado e pronto para abrir canais. */
    public static CenarioDeTerminal conectado() throws IOException, GeneralSecurityException {
        return conectado(ConfigSsh.rapida());
    }

    public static CenarioDeTerminal conectado(ConfigSsh cfg) throws IOException, GeneralSecurityException {
        CenarioDeTerminal c = new CenarioDeTerminal(cfg);
        c.conexao.iniciar();
        Espera.ate(Duration.ofSeconds(20), () -> "estado=" + c.conexao.info(), () -> c.conexao.info().estado() == EstadoConexao.CONECTADO);
        return c;
    }

    @Override
    public void close() {
        conexao.fechar().join();
        try {
            ssh.close();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
