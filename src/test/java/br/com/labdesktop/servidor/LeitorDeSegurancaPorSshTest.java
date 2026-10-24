package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura.Situacao;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.teste.SaidasDeSeguranca;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** O leitor por SSH exec: comandos FIXOS, cada seção degrada sozinha e sem sudo não aparece erro feio. */
class LeitorDeSegurancaPorSshTest {

    private static final Instant AGORA = Instant.parse("2026-10-24T20:00:00Z");

    private static ResultadoExec r(int status, String out, String err, boolean truncado) {
        return new ResultadoExec(status, out.getBytes(StandardCharsets.UTF_8), err.getBytes(StandardCharsets.UTF_8), truncado);
    }

    /** Um servidor de mentira: cada comando devolve o que o mapa disser; o que não está no mapa falha com 127. */
    private static final class Falso implements br.com.labdesktop.ssh.ProvisionadorRemoto.Executor {
        final Map<String, ResultadoExec> respostas;
        final List<String> chamados = new ArrayList<>();
        final List<byte[]> entradas = new ArrayList<>();
        final java.util.Set<String> queLancam = new java.util.HashSet<>();

        Falso(Map<String, ResultadoExec> respostas) {
            this.respostas = respostas;
        }

        @Override
        public ResultadoExec executar(String comando, byte[] stdin, java.time.Duration prazo) throws IOException {
            chamados.add(comando);
            entradas.add(stdin);
            if (queLancam.contains(comando)) {
                throw new IOException("Não há conexão SSH aberta com o servidor.");
            }
            return respostas.getOrDefault(comando, r(127, "", "comando não encontrado", false));
        }
    }

    private static Map<String, ResultadoExec> servidorCompleto(boolean ataque) {
        return Map.of(
            LeitorDeSegurancaPorSsh.COMANDO_REGISTRO, r(0, ataque ? SaidasDeSeguranca.registroComAtaque(AGORA) : SaidasDeSeguranca.registroCalmo(AGORA), "", false),
            LeitorDeSegurancaPorSsh.COMANDO_PORTAS, r(0, SaidasDeSeguranca.SS, "", false),
            LeitorDeSegurancaPorSsh.COMANDO_NFT, r(0, SaidasDeSeguranca.NFT, "", false),
            LeitorDeSegurancaPorSsh.COMANDO_SSHD, r(0, SaidasDeSeguranca.SSHD_SEGURO, "", false),
            LeitorDeSegurancaPorSsh.COMANDO_FAIL2BAN_ATIVO, r(0, "active\n", "", false),
            LeitorDeSegurancaPorSsh.COMANDO_FAIL2BAN_STATUS, r(0, SaidasDeSeguranca.FAIL2BAN, "", false));
    }

    @Test
    void leTodasAsSecoesEUsaAPortaDoSshNoFirewall() {
        Falso f = new Falso(servidorCompleto(true));
        ResumoDeSeguranca s = new LeitorDeSegurancaPorSsh(f, () -> AGORA).ler();
        assertEquals(AGORA, s.lidoEm());
        assertEquals(7, s.tentativas().dados().em24h());
        assertEquals(3, s.portas().dados().size());
        assertTrue(s.firewall().dados().regras().contains("Porta 2222 (SSH): permitido só de 10.0.0.0/24"));
        assertTrue(s.ssh().dados().soChave());
        assertEquals(2, s.fail2ban().dados().banidosAgora());
    }

    @Test
    void osComandosSaoFixosESemEntrada() {
        Falso f = new Falso(servidorCompleto(false));
        new LeitorDeSegurancaPorSsh(f, () -> AGORA).ler();
        assertEquals(List.of(LeitorDeSegurancaPorSsh.COMANDO_SSHD, LeitorDeSegurancaPorSsh.COMANDO_REGISTRO,
            LeitorDeSegurancaPorSsh.COMANDO_PORTAS, LeitorDeSegurancaPorSsh.COMANDO_NFT,
            LeitorDeSegurancaPorSsh.COMANDO_FAIL2BAN_ATIVO, LeitorDeSegurancaPorSsh.COMANDO_FAIL2BAN_STATUS).stream().sorted().toList(),
            f.chamados.stream().sorted().toList());
        assertTrue(f.entradas.stream().allMatch(e -> e.length == 0), "nada vai por stdin");
        for (String c : f.chamados) {
            assertTrue(c.startsWith("sudo -n ") || c.startsWith("ss ") || c.startsWith("systemctl "), c);
            assertFalse(c.contains("rm ") || c.contains("tee ") || c.contains(" > ") || c.contains("iptables -A")
                || c.contains("nft add") || c.contains("ufw") || c.contains("fail2ban-client set"), "só leitura: " + c);
            assertTrue(c.chars().noneMatch(ch -> ch < 0x20), "sem caractere de controle: " + c);
        }
    }

    @Test
    void semSudoCadaSecaoDizSemPermissaoSemDerrubarAsOutras() {
        String semSudo = "sudo: a password is required";
        Falso f = new Falso(Map.of(
            LeitorDeSegurancaPorSsh.COMANDO_REGISTRO, r(1, "", semSudo, false),
            LeitorDeSegurancaPorSsh.COMANDO_PORTAS, r(0, SaidasDeSeguranca.SS, "", false),
            LeitorDeSegurancaPorSsh.COMANDO_NFT, r(1, "", semSudo, false),
            LeitorDeSegurancaPorSsh.COMANDO_IPTABLES, r(1, "", semSudo, false),
            LeitorDeSegurancaPorSsh.COMANDO_SSHD, r(1, "", semSudo, false),
            LeitorDeSegurancaPorSsh.COMANDO_FAIL2BAN_ATIVO, r(3, "inactive\n", "", false)));
        ResumoDeSeguranca s = new LeitorDeSegurancaPorSsh(f, () -> AGORA).ler();
        assertEquals(Situacao.SEM_PERMISSAO, s.tentativas().situacao());
        assertEquals(Situacao.SEM_PERMISSAO, s.firewall().situacao());
        assertEquals(Situacao.SEM_PERMISSAO, s.ssh().situacao());
        assertTrue(s.portas().lida(), "as portas não precisam de sudo");
        assertTrue(s.fail2ban().lida());
        assertFalse(s.fail2ban().dados().ativo());
    }

    @Test
    void permissaoNegadaDoJournalAindaComStatusZeroEFaltaDePermissao() {
        Falso f = new Falso(Map.of(LeitorDeSegurancaPorSsh.COMANDO_REGISTRO,
            r(1, "", "Hint: You are currently not seeing messages from other users and the system.\nNo journal files were opened due to insufficient permissions.", false)));
        assertEquals(Situacao.SEM_PERMISSAO, new LeitorDeSegurancaPorSsh(f, () -> AGORA).ler().tentativas().situacao());
    }

    @Test
    void grepSemNenhumaLinhaSaiComStatusUmEEZeroTentativas() {
        Map<String, ResultadoExec> m = new java.util.HashMap<>(servidorCompleto(false));
        m.put(LeitorDeSegurancaPorSsh.COMANDO_REGISTRO, r(1, "", "", false));
        ResumoDeSeguranca s = new LeitorDeSegurancaPorSsh(new Falso(m), () -> AGORA).ler();
        assertTrue(s.tentativas().lida());
        assertEquals(0, s.tentativas().dados().em24h());
    }

    @Test
    void saidaTruncadaMarcaParcial() {
        Map<String, ResultadoExec> m = new java.util.HashMap<>(servidorCompleto(true));
        m.put(LeitorDeSegurancaPorSsh.COMANDO_REGISTRO, r(0, SaidasDeSeguranca.registroComAtaque(AGORA), "", true));
        assertTrue(new LeitorDeSegurancaPorSsh(new Falso(m), () -> AGORA).ler().tentativas().dados().parcial());
    }

    @Test
    void comandoQueFalhaNaoDerrubaOsOutros() {
        Falso f = new Falso(servidorCompleto(true));
        f.queLancam.add(LeitorDeSegurancaPorSsh.COMANDO_REGISTRO);
        ResumoDeSeguranca s = new LeitorDeSegurancaPorSsh(f, () -> AGORA).ler();
        assertEquals(Situacao.INDISPONIVEL, s.tentativas().situacao());
        assertTrue(s.portas().lida());
        assertTrue(s.ssh().lida());
        assertTrue(s.firewall().lida());
    }

    @Test
    void semConexaoNenhumaSecaoLeEOLeitorNaoLanca() {
        Falso f = new Falso(servidorCompleto(true));
        f.queLancam.addAll(List.of(LeitorDeSegurancaPorSsh.COMANDO_REGISTRO, LeitorDeSegurancaPorSsh.COMANDO_PORTAS,
            LeitorDeSegurancaPorSsh.COMANDO_NFT, LeitorDeSegurancaPorSsh.COMANDO_IPTABLES, LeitorDeSegurancaPorSsh.COMANDO_SSHD,
            LeitorDeSegurancaPorSsh.COMANDO_FAIL2BAN_ATIVO));
        ResumoDeSeguranca s = new LeitorDeSegurancaPorSsh(f, () -> AGORA).ler();
        for (var l : List.of(s.tentativas(), s.portas(), s.firewall(), s.ssh(), s.fail2ban())) {
            assertEquals(Situacao.INDISPONIVEL, l.situacao());
        }
    }

    @Test
    void semNftTentaIptablesESemNenhumDiz() {
        Map<String, ResultadoExec> m = new java.util.HashMap<>(servidorCompleto(false));
        m.put(LeitorDeSegurancaPorSsh.COMANDO_NFT, r(127, "", "sudo: nft: command not found", false));
        m.put(LeitorDeSegurancaPorSsh.COMANDO_IPTABLES, r(0, SaidasDeSeguranca.IPTABLES, "", false));
        var s = new LeitorDeSegurancaPorSsh(new Falso(m), () -> AGORA).ler();
        assertEquals("iptables", s.firewall().dados().motor());

        m.put(LeitorDeSegurancaPorSsh.COMANDO_IPTABLES, r(127, "", "sudo: iptables: command not found", false));
        var nenhum = new LeitorDeSegurancaPorSsh(new Falso(m), () -> AGORA).ler();
        assertTrue(nenhum.firewall().lida());
        assertFalse(nenhum.firewall().dados().identificado(), "firewall não identificado");
    }

    @Test
    void fail2banAtivoSemPermissaoParaContarAindaDizQueRoda() {
        Map<String, ResultadoExec> m = new java.util.HashMap<>(servidorCompleto(false));
        m.put(LeitorDeSegurancaPorSsh.COMANDO_FAIL2BAN_STATUS, r(1, "", "sudo: a password is required", false));
        var s = new LeitorDeSegurancaPorSsh(new Falso(m), () -> AGORA).ler();
        assertTrue(s.fail2ban().dados().ativo());
        assertEquals(null, s.fail2ban().dados().banidosAgora());
    }
}
