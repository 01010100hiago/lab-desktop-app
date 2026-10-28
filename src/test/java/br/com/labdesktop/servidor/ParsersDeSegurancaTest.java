package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.servidor.ResumoDeSeguranca.ConfiguracaoDoSsh;
import br.com.labdesktop.servidor.ResumoDeSeguranca.EstadoFail2ban;
import br.com.labdesktop.servidor.ResumoDeSeguranca.PortaAberta;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ResumoDoFirewall;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Tentativas;
import br.com.labdesktop.servidor.ResumoDeSeguranca.TipoDeTentativa;
import br.com.labdesktop.teste.SaidasDeSeguranca;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Os parsers de segurança com saídas SINTÉTICAS: contagem, tipos, origens, cortes de 24 h e 7 dias, vazio e truncado. */
class ParsersDeSegurancaTest {

    private static final Instant AGORA = Instant.parse("2026-10-24T20:00:00Z");

    private static Tentativas ataque() {
        return ParsersDeSeguranca.lerRegistroDoSsh(SaidasDeSeguranca.registroComAtaque(AGORA), AGORA, false);
    }

    @Test
    void contaTentativasEm24HorasESeteDiasPorConexaoSemContarDuasVezes() {
        Tentativas t = ataque();
        // A (3 linhas, 1 conexão), B, C, D (3 linhas + PAM, 1 conexão), E (2), H (PAM sozinha) = 7; F entra só nos 7 dias; G (8 dias) fora
        assertEquals(7, t.em24h());
        assertEquals(8, t.em7d());
        assertFalse(t.parcial());
    }

    @Test
    void classificaPorTipoDeAtaque() {
        Tentativas t = ataque();
        assertEquals(4, t.porTipo().get(TipoDeTentativa.USUARIO_INVENTADO), "admin e oracle: A, B, C e F");
        assertEquals(2, t.porTipo().get(TipoDeTentativa.ADIVINHAR_SENHA), "D (usuário que existe) e H (só o PAM)");
        assertEquals(2, t.porTipo().get(TipoDeTentativa.VARREDURA), "negociação impossível e conexão fechada antes de autenticar");
        assertEquals(0, t.porTipo().get(TipoDeTentativa.OUTROS));
        assertEquals(8, t.porTipo().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void listaAsOrigensQueMaisTentaramSemGeolocalizacao() {
        Tentativas t = ataque();
        assertEquals(4, t.totalDeOrigens());
        assertEquals("203.0.113.5", t.origens().get(0).ip());
        assertEquals(4, t.origens().get(0).tentativas());
        assertEquals("198.51.100.9", t.origens().get(1).ip());
        assertEquals(2, t.origens().get(1).tentativas());
        assertEquals(List.of("198.51.100.20", "198.51.100.7"), t.origens().subList(2, 4).stream().map(o -> o.ip()).toList(),
            "empate: ordem do endereço");
        assertTrue(t.origens().stream().noneMatch(o -> o.redeLocal()));
    }

    @Test
    void devolveAListaDeEventosDoMaisRecenteAoMaisAntigoComTipoIpEUsuario() {
        Tentativas t = ataque();
        assertEquals(8, t.eventos().size());
        assertFalse(t.maisQueOLimite());
        for (int i = 1; i < t.eventos().size(); i++) {
            assertFalse(t.eventos().get(i).quando().isAfter(t.eventos().get(i - 1).quando()), "do mais recente ao mais antigo");
        }
        var maisRecente = t.eventos().get(0);
        assertEquals("198.51.100.9", maisRecente.ip(), "a varredura de há 1 h é a mais recente");
        assertEquals(TipoDeTentativa.VARREDURA, maisRecente.tipo());
        assertNull(maisRecente.usuario(), "varredura não tem usuário");
        var admin = t.eventos().stream().filter(e -> e.ip().equals("203.0.113.5") && "admin".equals(e.usuario())).toList();
        assertEquals(2, admin.size(), "A (as três linhas viram um evento) e F");
        assertTrue(t.eventos().stream().anyMatch(e -> "oracle".equals(e.usuario()) && e.tipo() == TipoDeTentativa.USUARIO_INVENTADO));
        assertTrue(t.eventos().stream().anyMatch(e -> "labuser".equals(e.usuario()) && e.tipo() == TipoDeTentativa.ADIVINHAR_SENHA));
        assertEquals(t.em7d(), t.eventos().size());
    }

    @Test
    void guardaNoMaximo500EventosOsMaisRecentesEDizQueHaMais() {
        Tentativas t = ParsersDeSeguranca.lerRegistroDoSsh(SaidasDeSeguranca.registroGrande(AGORA, 620, 0), AGORA, false);
        assertEquals(620, t.em7d());
        assertEquals(ParsersDeSeguranca.MAX_EVENTOS, t.eventos().size());
        assertEquals(500, t.eventos().size());
        assertTrue(t.maisQueOLimite());
        Instant maisAntigoGuardado = t.eventos().get(499).quando();
        Instant maisRecente = t.eventos().get(0).quando();
        assertTrue(maisAntigoGuardado.isBefore(maisRecente));
        assertEquals(AGORA.minusSeconds(60), maisRecente, "o mais recente é o de 1 minuto atrás");
        assertEquals(AGORA.minusSeconds(60 + 499 * 600L), maisAntigoGuardado, "os 500 mais recentes, nenhum a menos");
        ResumoDeSeguranca resumo = null;
        assertNull(resumo);
        Tentativas exato = ParsersDeSeguranca.lerRegistroDoSsh(SaidasDeSeguranca.registroGrande(AGORA, 500, 0), AGORA, false);
        assertEquals(500, exato.eventos().size());
        assertFalse(exato.maisQueOLimite(), "500 exatos cabem");
    }

    @Test
    void asOrigensTrazemTipoPredominanteEUltimaTentativaEsaoAteDez() {
        Tentativas t = ParsersDeSeguranca.lerRegistroDoSsh(SaidasDeSeguranca.registroGrande(AGORA, 120, 0), AGORA, false);
        assertEquals(10, ParsersDeSeguranca.MAX_ORIGENS);
        assertEquals(10, t.origens().size(), "18 endereços, só os 10 que mais tentaram");
        assertEquals(18, t.totalDeOrigens());
        var o = t.origens().get(0);
        assertNotNull(o.tipoPredominante());
        assertNotNull(o.ultima());
        assertTrue(o.tentativas() >= t.origens().get(9).tentativas());
        var ataque = ataque().origens().get(0);
        assertEquals("203.0.113.5", ataque.ip());
        assertEquals(TipoDeTentativa.USUARIO_INVENTADO, ataque.tipoPredominante(), "3 de 4 foram usuário inventado");
        assertEquals(AGORA.minusSeconds(2 * 3600 - 5), ataque.ultima(), "a mais recente dele é a conexão B, 5 s depois da A");
    }

    @Test
    void guardaOsLoginsAceitosAte200() {
        Tentativas t = ParsersDeSeguranca.lerRegistroDoSsh(SaidasDeSeguranca.registroGrande(AGORA, 0, 230), AGORA, false);
        assertEquals(ParsersDeSeguranca.MAX_LOGINS, t.logins().size());
        assertTrue(t.logins().get(0).quando().isAfter(t.logins().get(199).quando()));
    }

    @Test
    void noMaximoDezOrigens() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 14; i++) {
            sb.append(SaidasDeSeguranca.ts(AGORA.minusSeconds(60)))
                .append(" h sshd[1]: Failed password for invalid user x from 203.0.113.").append(i).append(" port 4000 ssh2\n");
        }
        Tentativas t = ParsersDeSeguranca.lerRegistroDoSsh(sb.toString(), AGORA, false);
        assertEquals(10, t.origens().size());
        assertEquals(14, t.totalDeOrigens());
        assertEquals(14, t.em24h());
    }

    @Test
    void loginsAceitosVemDoMaisNovoParaOMaisAntigoEDestacamOQueEhDeForaDaRedeLocal() {
        Tentativas t = ataque();
        assertEquals(2, t.logins().size());
        var fora = t.logins().get(0);
        assertEquals("deploy", fora.usuario());
        assertEquals("203.0.113.77", fora.ip());
        assertEquals(61000, fora.porta());
        assertEquals("senha", fora.metodo());
        assertTrue(fora.foraDaRedeLocal());
        var local = t.logins().get(1);
        assertEquals("labuser", local.usuario());
        assertEquals("10.0.0.10", local.ip());
        assertEquals("chave", local.metodo());
        assertEquals("ED25519", local.algoritmo());
        assertTrue(local.impressao().startsWith("SHA256:AbCdEf"));
        assertFalse(local.foraDaRedeLocal());
        assertEquals(1, t.foraDaRedeEm24h());
    }

    @Test
    void oCorteDe24HorasUsaORelogioInjetado() {
        String saida = SaidasDeSeguranca.registroComAtaque(AGORA);
        Tentativas depois = ParsersDeSeguranca.lerRegistroDoSsh(saida, AGORA.plusSeconds(3600 * 24), false);
        // um dia depois, quase tudo saiu das 24 h (E, a mais recente, estava a 1 h: 25 h agora) e o F cai para 4 dias
        assertEquals(0, depois.em24h());
        assertEquals(8, depois.em7d());
        Tentativas seis = ParsersDeSeguranca.lerRegistroDoSsh(saida, AGORA.plusSeconds(3600 * 24 * 6), false);
        assertEquals(7, seis.em7d(), "seis dias depois o F (3 dias antes, agora a 9) saiu da janela; os outros 7 ficam");
        Tentativas muito = ParsersDeSeguranca.lerRegistroDoSsh(saida, AGORA.plusSeconds(3600 * 24 * 8), false);
        assertEquals(0, muito.em24h());
        assertEquals(0, muito.em7d(), "oito dias depois tudo está fora da janela");
        assertTrue(muito.logins().isEmpty());
    }

    @Test
    void saidaVaziaOuSoDeRuidoDaZeroTentativas() {
        for (String s : new String[] {"", "-- No entries --\n", null, "\n\n", "lixo sem data\n"}) {
            Tentativas t = ParsersDeSeguranca.lerRegistroDoSsh(s, AGORA, false);
            assertEquals(0, t.em24h());
            assertEquals(0, t.em7d());
            assertTrue(t.origens().isEmpty());
            assertTrue(t.logins().isEmpty());
        }
    }

    @Test
    void registroCalmoTemLoginLegitimoEZeroTentativas() {
        Tentativas t = ParsersDeSeguranca.lerRegistroDoSsh(SaidasDeSeguranca.registroCalmo(AGORA), AGORA, false);
        assertEquals(0, t.em24h());
        assertEquals(1, t.logins().size());
        assertEquals(0, t.foraDaRedeEm24h());
    }

    @Test
    void saidaTruncadaNoMeioDaLinhaNaoQuebraEMarcaParcial() {
        String cheia = SaidasDeSeguranca.registroComAtaque(AGORA);
        String cortada = cheia.substring(0, cheia.length() / 2 + 7); // corta no meio de uma linha
        Tentativas t = ParsersDeSeguranca.lerRegistroDoSsh(cortada, AGORA, true);
        assertTrue(t.parcial());
        assertTrue(t.em7d() > 0 && t.em7d() <= 8);
    }

    @Test
    void aceitaHoraComEDoisPontosNoFuso() {
        String linha = "2026-10-24T19:00:00+00:00 h sshd[1]: Failed password for invalid user admin from 203.0.113.9 port 1 ssh2\n";
        assertEquals(1, ParsersDeSeguranca.lerRegistroDoSsh(linha, AGORA, false).em24h());
        String comOffset = "2026-10-24T16:00:00-0300 h sshd[1]: Failed password for invalid user admin from 203.0.113.9 port 1 ssh2\n";
        assertEquals(1, ParsersDeSeguranca.lerRegistroDoSsh(comOffset, AGORA, false).em24h(), "16:00 em -03:00 é 19:00 UTC");
    }

    @Test
    void redeLocalReconheceOsBlocosPrivadosELoopback() {
        for (String ip : new String[] {"10.0.0.10", "10.1.2.3", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1", "::1",
            "fe80::1", "fd12:3456::1", "::ffff:10.0.1.1"}) {
            assertTrue(ParsersDeSeguranca.redeLocal(ip), ip);
        }
        for (String ip : new String[] {"203.0.113.5", "198.51.100.1", "172.32.0.1", "198.51.100.8", "2001:db8::1", null}) {
            assertFalse(ParsersDeSeguranca.redeLocal(ip), String.valueOf(ip));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void portasAgrupadasPorPortaSemOLoopback() {
        List<PortaAberta> p = ParsersDeSeguranca.lerPortas(SaidasDeSeguranca.SS);
        assertEquals(List.of(80, 2222, 8080), p.stream().map(PortaAberta::porta).toList(), "7000, 631 e 53 são do loopback");
        assertEquals("sshd", p.get(1).processo());
        assertEquals("todas as interfaces", p.get(1).escopo());
        assertEquals("docker-proxy", p.get(2).processo());
        assertEquals("só em 10.0.0.10", p.get(2).escopo());
        assertEquals("", p.get(0).processo());
    }

    @Test
    void portasComSaidaVaziaOuLixo() {
        assertTrue(ParsersDeSeguranca.lerPortas("").isEmpty());
        assertTrue(ParsersDeSeguranca.lerPortas(null).isEmpty());
        assertTrue(ParsersDeSeguranca.lerPortas("lixo\nmais lixo sem porta\n").isEmpty());
    }

    @Test
    void firewallNftablesEmFrasesSimples() {
        ResumoDoFirewall f = ParsersDeSeguranca.lerNft(SaidasDeSeguranca.NFT, 2222);
        assertTrue(f.identificado());
        assertEquals("nftables", f.motor());
        assertEquals("bloqueia", f.politica());
        assertEquals(List.of(
            "Respostas a conexões que a própria máquina abriu: permitido",
            "Tráfego interno da máquina (loopback): permitido",
            "Porta 2222 (SSH): permitido só de 10.0.0.0/24",
            "Portas 80 (HTTP), 443 (HTTPS): permitido",
            "Ping e avisos de rede (ICMP): permitido",
            "Todo o resto: bloqueado"), f.regras());
    }

    @Test
    void firewallIptablesEmFrasesSimples() {
        ResumoDoFirewall f = ParsersDeSeguranca.lerIptables(SaidasDeSeguranca.IPTABLES, 2222);
        assertTrue(f.identificado());
        assertEquals("iptables", f.motor());
        assertEquals("bloqueia", f.politica());
        assertTrue(f.regras().contains("Porta 2222 (SSH): permitido só de 10.0.0.0/24"), f.regras().toString());
        assertTrue(f.regras().contains("Porta 443 (HTTPS): permitido"));
        assertTrue(f.regras().contains("Origem 203.0.113.0/24: bloqueado"));
        assertTrue(f.regras().contains("Tráfego interno da máquina (loopback): permitido"));
    }

    @Test
    void firewallSemSaidaNaoEIdentificado() {
        assertFalse(ParsersDeSeguranca.lerNft("", 22).identificado());
        assertFalse(ParsersDeSeguranca.lerNft(null, 22).identificado());
        assertFalse(ParsersDeSeguranca.lerIptables("", 22).identificado());
    }

    @Test
    void politicaAceitaTudoEDizAceita() {
        String nft = "table inet filter {\n chain input {\n  type filter hook input priority filter; policy accept;\n }\n}\n";
        ResumoDoFirewall f = ParsersDeSeguranca.lerNft(nft, 22);
        assertTrue(f.identificado());
        assertEquals("aceita", f.politica());
        assertTrue(f.regras().isEmpty());
    }

    @Test
    void sshdExtraiAsDiretivasQueImportam() {
        ConfiguracaoDoSsh c = ParsersDeSeguranca.lerSshd(SaidasDeSeguranca.SSHD_SEGURO);
        assertEquals(2222, c.porta());
        assertEquals(Boolean.FALSE, c.senhaLigada());
        assertEquals("no", c.loginDoRoot());
        assertEquals(Boolean.TRUE, c.chaveLigada());
        assertEquals(3, c.maxTentativas());
        assertTrue(c.soChave());
        assertTrue(c.rootDesligado());
        ConfiguracaoDoSsh com = ParsersDeSeguranca.lerSshd(SaidasDeSeguranca.SSHD_COM_SENHA);
        assertEquals(Boolean.TRUE, com.senhaLigada());
        assertFalse(com.soChave());
    }

    @Test
    void sshdSemNenhumaDiretivaDevolveNulo() {
        assertNull(ParsersDeSeguranca.lerSshd(""));
        assertNull(ParsersDeSeguranca.lerSshd("sudo: a password is required"));
        assertNull(ParsersDeSeguranca.lerSshd(null));
    }

    @Test
    void fail2banAtivoComIpsEInativo() {
        EstadoFail2ban f = ParsersDeSeguranca.lerFail2ban(true, SaidasDeSeguranca.FAIL2BAN);
        assertTrue(f.ativo());
        assertEquals(2, f.banidosAgora());
        assertEquals(40, f.totalBanidos());
        assertEquals(List.of("203.0.113.7", "198.51.100.4"), f.ips());
        EstadoFail2ban parado = ParsersDeSeguranca.lerFail2ban(false, null);
        assertFalse(parado.ativo());
        assertNotNull(parado.ips());
        EstadoFail2ban semStatus = ParsersDeSeguranca.lerFail2ban(true, null);
        assertTrue(semStatus.ativo());
        assertNull(semStatus.banidosAgora());
    }

    @Test
    void sshdLeTecladoInterativoOuOSeuNomeAntigoEUsePamEMarcaParcial() {
        var c = ParsersDeSeguranca.lerSshd("passwordauthentication no\nkbdinteractiveauthentication yes\nusepam yes\npubkeyauthentication yes\n");
        assertEquals(Boolean.TRUE, c.tecladoLigado());
        assertEquals(Boolean.TRUE, c.usaPam());
        assertTrue(c.parcial());
        assertFalse(c.soChave(), "com o teclado interativo ligado não é só chave");
        var antigo = ParsersDeSeguranca.lerSshd("passwordauthentication no\nchallengeresponseauthentication yes\n");
        assertTrue(antigo.parcial(), "o nome antigo vale igual");
        var seguro = ParsersDeSeguranca.lerSshd("passwordauthentication no\nkbdinteractiveauthentication no\nusepam yes\n");
        assertFalse(seguro.parcial());
        assertTrue(seguro.soChave());
    }
}
