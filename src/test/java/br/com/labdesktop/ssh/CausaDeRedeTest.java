package br.com.labdesktop.ssh;

import static br.com.labdesktop.ssh.CausaDeRede.NOME_NAO_ENCONTRADO;
import static br.com.labdesktop.ssh.CausaDeRede.OUTRA;
import static br.com.labdesktop.ssh.CausaDeRede.RECUSOU;
import static br.com.labdesktop.ssh.CausaDeRede.SEM_ROTA;
import static br.com.labdesktop.ssh.CausaDeRede.TEMPO_ESGOTADO;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;
import org.apache.sshd.common.SshException;
import org.junit.jupiter.api.Test;

/**
 * Valores conhecidos: a causa vem do TIPO da exceção (e das causas encadeadas), não do texto do sistema. As mensagens abaixo são as de cada sistema e idioma de propósito: a resposta
 * tem de ser a mesma.
 */
class CausaDeRedeTest {

    private static Throwable embrulhada(Throwable raiz) {
        return new SshException("Failed to connect", new IOException("Falha ao executar", raiz));
    }

    @Test
    void recusaPeloTipoDaExcecaoEmQualquerIdiomaEPorQualquerEmbrulho() {
        assertEquals(RECUSOU, CausaDeRede.de(new ConnectException("Connection refused"), true));
        assertEquals(RECUSOU, CausaDeRede.de(new ConnectException("Conexão recusada"), true));
        assertEquals(RECUSOU, CausaDeRede.de(new ConnectException("Verbindungsaufbau abgelehnt"), false));
        assertEquals(RECUSOU, CausaDeRede.de(embrulhada(new ConnectException("Connection refused: no further information")), true));
    }

    @Test
    void noWindowsARecusaChegaComoIoExceptionComumComOTextoDoSistemaEIssoValeRecusouAoConectar() {
        // o Java no Windows entrega a recusa como IOException comum, com a frase do próprio Windows (em qualquer idioma)
        assertEquals(RECUSOU, CausaDeRede.de(new IOException("The remote computer refused the network connection."), true));
        assertEquals(RECUSOU, CausaDeRede.de(new IOException("O computador remoto recusou a conexão de rede."), true));
        assertEquals(RECUSOU, CausaDeRede.de(new IOException("El equipo remoto rechazó la conexión de red."), true));
        assertEquals(RECUSOU, CausaDeRede.de(embrulhada(new IOException("The remote computer refused the network connection.")), true));
    }

    @Test
    void aRegraDaIoExceptionComumSoValeAoConectarENuncaEngoleOutrasCausas() {
        // depois de a sessão existir, uma IOException comum não é "recusou"
        assertEquals(OUTRA, CausaDeRede.de(new IOException("The remote computer refused the network connection."), false));
        // subclasses que dizem outra coisa não viram "recusou"
        assertEquals(OUTRA, CausaDeRede.de(new EOFException("fim"), true));
        assertEquals(OUTRA, CausaDeRede.de(new SshException("Connection closed"), true));
        assertEquals(OUTRA, CausaDeRede.de(new SocketException("Connection reset"), true));
        // e uma causa conhecida na cadeia vence a regra
        assertEquals(TEMPO_ESGOTADO, CausaDeRede.de(new IOException("x", new SocketTimeoutException("Read timed out")), true));
        assertEquals(NOME_NAO_ENCONTRADO, CausaDeRede.de(new IOException("x", new UnknownHostException("nao-existe")), true));
        assertEquals(OUTRA, CausaDeRede.de(null, true));
    }

    @Test
    void tempoEsgotadoNomeDesconhecidoESemRotaPeloTipo() {
        assertEquals(TEMPO_ESGOTADO, CausaDeRede.de(new SocketTimeoutException("Connect timed out"), true));
        assertEquals(TEMPO_ESGOTADO, CausaDeRede.de(new SocketTimeoutException("Tempo limite de conexão esgotado"), true));
        assertEquals(TEMPO_ESGOTADO, CausaDeRede.de(new TimeoutException(), true));
        assertEquals(TEMPO_ESGOTADO, CausaDeRede.de(embrulhada(new ConnectException("Connection timed out: connect")), true));
        assertEquals(NOME_NAO_ENCONTRADO, CausaDeRede.de(new UnknownHostException("host.invalido"), true));
        assertEquals(NOME_NAO_ENCONTRADO, CausaDeRede.de(new UnresolvedAddressException(), true));
        assertEquals(NOME_NAO_ENCONTRADO, CausaDeRede.de(embrulhada(new UnknownHostException("Nome desconhecido")), true));
        assertEquals(SEM_ROTA, CausaDeRede.de(new NoRouteToHostException("No route to host"), true));
        assertEquals(SEM_ROTA, CausaDeRede.de(new NoRouteToHostException("Sem rota para o host"), true));
        assertEquals(SEM_ROTA, CausaDeRede.de(embrulhada(new NoRouteToHostException("Network is unreachable")), true));
    }

    @Test
    void osTextosDeSistemasSemTipoProprioAindaSaoReconhecidosComoReforco() {
        assertEquals(SEM_ROTA, CausaDeRede.de(new SocketException("Network is unreachable"), true));
        assertEquals(RECUSOU, CausaDeRede.de(new SshException("java.net.SocketException: Connection refused"), false));
    }

    @Test
    void aFraseDaTelaVemDaCausaEMesmoNoWindowsDizConexaoRecusada() {
        var windows = new IOException("The remote computer refused the network connection.");
        assertEquals("Não consegui conectar: conexão recusada.", ConexaoSsh.MensagensDeFalha.rede(embrulhada(windows), null, true).mensagem());
        assertEquals("Não consegui conectar: conexão recusada.", ConexaoSsh.MensagensDeFalha.rede(new ConnectException("Connection refused"), null, true).mensagem());
        assertEquals("Não consegui conectar: tempo esgotado.", ConexaoSsh.MensagensDeFalha.rede(new SocketTimeoutException("Connect timed out"), null, true).mensagem());
        assertEquals("Não consegui conectar: sem rota até o servidor.", ConexaoSsh.MensagensDeFalha.rede(new NoRouteToHostException("x"), null, true).mensagem());
        assertEquals("Não consegui conectar: servidor desconhecido.", ConexaoSsh.MensagensDeFalha.rede(new UnknownHostException("x"), null, true).mensagem());
        assertEquals("A conexão SSH caiu (The remote computer refused the network connection.).", ConexaoSsh.MensagensDeFalha.sessaoPerdida(windows).mensagem(),
            "depois de a sessão existir, a mesma exceção não é 'recusou'");
    }
}
