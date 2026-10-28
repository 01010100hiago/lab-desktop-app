package br.com.labdesktop.acesso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** A ordem de precedência do sshd (o primeiro valor vence), os {@code Include}, o {@code Match} e a leitura do {@code sshd -T}. */
class AnaliseDoSshdTest {

    private static final String REGRA = "PasswordAuthentication no\nKbdInteractiveAuthentication no\n";
    private static final String DEBIAN = "Include /etc/ssh/sshd_config.d/*.conf\n\nPort 22\nUsePAM yes\nPasswordAuthentication yes\nKbdInteractiveAuthentication no\n";

    private static SortedMap<String, String> pasta(String... paresNomeConteudo) {
        SortedMap<String, String> m = new TreeMap<>();
        for (int i = 0; i < paresNomeConteudo.length; i += 2) {
            m.put(paresNomeConteudo[i], paresNomeConteudo[i + 1]);
        }
        return m;
    }

    @Test
    void oIncludeNoTopoColocaOsArquivosDaPastaAntesDoPrincipalEONossoVence() {
        // Debian: o Include está no topo, então a pasta é lida ANTES do "PasswordAuthentication yes" do principal: o nosso, em 10-, ganha dele
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(DEBIAN, pasta(), REGRA);
        assertTrue(p.incluiAPasta());
        assertEquals(new AnaliseDoSshd.Decisor("no", "10-lab-desktop.conf"), p.senha());
        assertTrue(p.nossaVale(p.senha()));
        assertTrue(p.nossaVale(p.teclado()));
    }

    @Test
    void arquivoQueVemAntesDoNossoNaOrdemAlfabeticaVence() {
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(DEBIAN, pasta("01-ajuste.conf", "PasswordAuthentication yes\n"), REGRA);
        assertEquals(new AnaliseDoSshd.Decisor("yes", "01-ajuste.conf"), p.senha());
        assertFalse(p.nossaVale(p.senha()));
        assertTrue(p.nossaVale(p.teclado()), "a outra opção segue sendo do app");
    }

    @Test
    void arquivoQueVemDepoisDoNossoComoOCloudInitNaoVence() {
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(DEBIAN, pasta("50-cloud-init.conf", "PasswordAuthentication yes\n"), REGRA);
        assertTrue(p.nossaVale(p.senha()), "o 10- vem antes do 50-: o primeiro valor é o nosso");
    }

    @Test
    void valorNoPrincipalAntesDoIncludeVenceOArquivoDoApp() {
        String incluiNoFim = "Port 22\nPasswordAuthentication yes\nInclude /etc/ssh/sshd_config.d/*.conf\n";
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(incluiNoFim, pasta(), REGRA);
        assertEquals(new AnaliseDoSshd.Decisor("yes", "sshd_config"), p.senha());
    }

    @Test
    void semIncludeDaPastaOArquivoDoAppNemELido() {
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever("Port 22\nPasswordAuthentication yes\n", pasta(), REGRA);
        assertFalse(p.incluiAPasta());
        assertEquals(new AnaliseDoSshd.Decisor("yes", "sshd_config"), p.senha());
    }

    @Test
    void outroIncludeNaoContaEMatchNaoEARegraGeral() {
        String texto = "Include /etc/ssh/outra-pasta/*.conf\nInclude sshd_config.d/*.conf\nMatch User fulano\n  PasswordAuthentication yes\n";
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(texto, pasta(), REGRA);
        assertTrue(p.incluiAPasta(), "o caminho relativo sshd_config.d/*.conf também vale");
        assertTrue(p.temMatch());
        assertTrue(p.nossaVale(p.senha()), "o que está dentro do Match não é a regra geral");
    }

    @Test
    void matchNumArquivoDaPastaSoValeAteOFimDaqueleArquivo() {
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(DEBIAN,
            pasta("05-grupo.conf", "Match Group ti\n  PasswordAuthentication yes\n", "07-outro.conf", "KbdInteractiveAuthentication yes\n"), REGRA);
        assertTrue(p.nossaVale(p.senha()), "o Match do 05- fica no 05-");
        assertEquals("07-outro.conf", p.teclado().arquivo(), "o 07- é um arquivo novo e vale a regra geral");
    }

    @Test
    void aceitaIgualEMaiusculasEComentarioAoFinal() {
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(DEBIAN, pasta("01-x.conf", "  passwordauthentication=yes   # antigo\n"), REGRA);
        assertEquals(new AnaliseDoSshd.Decisor("yes", "01-x.conf"), p.senha());
    }

    @Test
    void oNomeAntigoChallengeResponseValeComoTecladoInterativo() {
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(DEBIAN, pasta("01-x.conf", "ChallengeResponseAuthentication yes\n"), REGRA);
        assertEquals("01-x.conf", p.teclado().arquivo());
        assertEquals("yes", p.teclado().valor());
    }

    @Test
    void arquivoDoAppQueJaExistiaEhSubstituidoNaSimulacao() {
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever(DEBIAN, pasta("10-lab-desktop.conf", "PasswordAuthentication yes\n"), REGRA);
        assertTrue(p.nossaVale(p.senha()), "vale o conteúdo NOVO do arquivo do app");
    }

    @Test
    void quemMexeEmSenhaListaOsArquivosMenosOProprio() {
        List<String> quem = AnaliseDoSshd.quemMexeEmSenha(DEBIAN, pasta("50-cloud-init.conf", "PasswordAuthentication yes\n", "60-nada.conf", "Port 22\n",
            "10-lab-desktop.conf", REGRA));
        assertEquals(List.of("sshd_config", "50-cloud-init.conf"), quem);
    }

    @Test
    void temMatchEnxergaOPrincipalEAPasta() {
        assertFalse(AnaliseDoSshd.temMatch(DEBIAN, pasta()));
        assertTrue(AnaliseDoSshd.temMatch(DEBIAN + "Match Address 10.0.0.0/8\n", pasta()));
        assertTrue(AnaliseDoSshd.temMatch(DEBIAN, pasta("20-x.conf", "Match User a\n")));
    }

    @Test
    void lerEfetivaPegaSenhaTecladoPamChaveEMetodos() {
        AnaliseDoSshd.Efetiva e = AnaliseDoSshd.lerEfetiva("port 22\npasswordauthentication yes\nkbdinteractiveauthentication no\nusepam yes\n"
            + "pubkeyauthentication yes\nauthenticationmethods any\n");
        assertEquals(Boolean.TRUE, e.senha());
        assertEquals(Boolean.FALSE, e.teclado());
        assertEquals(Boolean.TRUE, e.usaPam());
        assertEquals(Boolean.TRUE, e.chave());
        assertEquals("any", e.metodos());
        AnaliseDoSshd.Efetiva antigo = AnaliseDoSshd.lerEfetiva("challengeresponseauthentication yes\n");
        assertEquals(Boolean.TRUE, antigo.teclado());
        assertNull(antigo.senha());
    }

    @Test
    void oIncludeComAspasEVariosGlobsEEntendidoEUmArquivoComQuatroCercasNoTextoNaoQuebraOSeparador() {
        AnaliseDoSshd.Previsao p = AnaliseDoSshd.prever("Include \"/etc/ssh/x/*.conf\" \"/etc/ssh/sshd_config.d/*.conf\"\nPasswordAuthentication yes\n", pasta(), REGRA);
        assertTrue(p.incluiAPasta());
        assertTrue(p.nossaVale(p.senha()));
        SortedMap<String, String> m = AnaliseDoSshd.arquivosDaPasta(ComandosDoAcesso.MARCADOR_DA_PASTA + " sim\n" + ComandosDoAcesso.MARCADOR_DE_ARQUIVO
            + " /etc/ssh/sshd_config.d/10-a.conf\n#### comentario com quatro cercas\nPort 22\n");
        assertEquals(List.of("10-a.conf"), List.copyOf(m.keySet()), "'####' num comentário não vira arquivo novo");
    }

    @Test
    void arquivosDaPastaSeparaOsArquivosPeloMarcador() {
        SortedMap<String, String> m = AnaliseDoSshd.arquivosDaPasta(ComandosDoAcesso.MARCADOR_DA_PASTA + " sim\n" + ComandosDoAcesso.MARCADOR_DE_ARQUIVO + " /etc/ssh/sshd_config.d/50-b.conf\nB\n"
            + ComandosDoAcesso.MARCADOR_DE_ARQUIVO + " /etc/ssh/sshd_config.d/10-a.conf\nA\n");
        assertEquals(List.of("10-a.conf", "50-b.conf"), List.copyOf(m.keySet()));
        assertEquals("A\n", m.get("10-a.conf"));
        assertTrue(AnaliseDoSshd.pastaExiste(ComandosDoAcesso.MARCADOR_DA_PASTA + " sim\n"));
        assertFalse(AnaliseDoSshd.pastaExiste(ComandosDoAcesso.MARCADOR_DA_PASTA + " nao\n"));
    }
}
