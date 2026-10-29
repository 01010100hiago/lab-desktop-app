package br.com.labdesktop.transporte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.SistemaOperacional;
import org.junit.jupiter.api.Test;

/** As mensagens de erro do túnel: o que o aws e o cloudflared dizem, traduzido em palavras simples, sempre como TEXTO. */
class InterpretadorDaSaidaTest {

    private static final Transporte SSM_COM_PERFIL = Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22);
    private static final Transporte SSM_SEM_PERFIL = Transporte.ssm("i-0123456789abcdef0", null, null, 22);
    private static final Transporte CLOUD = Transporte.cloudflared("ssh.example.com");

    private static FalhaDoTunel ler(Transporte t, String saida, Integer codigo) {
        return InterpretadorDaSaida.interpretar(t, saida, codigo, SistemaOperacional.MAC, 30);
    }

    @Test
    void loginDaAwsExpiradoDizOComandoComOPerfil() {
        for (String saida : new String[] {
            "The SSO session associated with this profile has expired or is otherwise invalid. To refresh this SSO session run aws sso login with the corresponding profile.",
            "An error occurred (ExpiredToken) when calling the StartSession operation: The security token included in the request is expired",
            "Error when retrieving token from sso: Token has expired and refresh failed",
            "SSO session expired"}) {
            FalhaDoTunel f = ler(SSM_COM_PERFIL, saida, 255);
            assertEquals(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, f.tipo(), saida);
            assertEquals("Seu login da AWS expirou. No terminal rode: aws sso login --profile meu-perfil e tente de novo.", f.mensagem(), saida);
            assertEquals("aws sso login --profile meu-perfil", f.textoCopiavel());
        }
    }

    @Test
    void loginDaAwsExpiradoSemPerfilNaoInventaUmPerfil() {
        FalhaDoTunel f = ler(SSM_SEM_PERFIL, "ExpiredToken", 255);
        assertEquals("Seu login da AWS expirou. No terminal rode: aws sso login e tente de novo.", f.mensagem());
        assertEquals("aws sso login", f.textoCopiavel());
        assertFalse(f.mensagem().contains("--profile"));
    }

    @Test
    void semCredenciaisDaAwsTambemMandaFazerOLogin() {
        FalhaDoTunel f = ler(SSM_COM_PERFIL, "Unable to locate credentials. You can configure credentials by running \"aws configure\".", 255);
        assertEquals(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, f.tipo());
        assertTrue(f.mensagem().contains("aws sso login --profile meu-perfil"));
        assertTrue(f.mensagem().startsWith("O aws não achou um login válido"));
    }

    @Test
    void instanciaSemSsmFalaDoTargetNotConnected() {
        FalhaDoTunel f = ler(SSM_COM_PERFIL, "An error occurred (TargetNotConnected) when calling the StartSession operation: i-0123456789abcdef0 is not connected.", 254);
        assertEquals(FalhaDoTunel.Tipo.INSTANCIA_SEM_SSM, f.tipo());
        assertTrue(f.mensagem().contains("TargetNotConnected"));
        assertTrue(f.mensagem().contains("agente SSM"));
        assertNull(f.textoCopiavel());
    }

    @Test
    void pluginAusenteVaiParaOGuiaDeInstalacaoDoSistema() {
        FalhaDoTunel mac = ler(SSM_COM_PERFIL, "SessionManagerPlugin is not found. Please refer to SessionManager Documentation here: http://docs.aws.amazon.com/console/systems-manager/session-manager-plugin-not-found", 255);
        assertEquals(FalhaDoTunel.Tipo.FERRAMENTA_AUSENTE, mac.tipo());
        assertEquals("brew install --cask session-manager-plugin", mac.textoCopiavel());
        FalhaDoTunel win = InterpretadorDaSaida.interpretar(SSM_COM_PERFIL, "SessionManagerPlugin is not found", 255, SistemaOperacional.WINDOWS, 30);
        assertTrue(win.textoCopiavel().contains("instalador oficial da AWS"));
    }

    @Test
    void cloudflarePedindoLoginMostraOEnderecoComoTextoCopiavelSemAbrirNada() {
        String saida = "2026-10-29T10:00:00Z INF Start Websocket listener host=127.0.0.1:53211\n"
            + "Please open the following URL and log in with your Cloudflare account:\n\nhttps://dash.cloudflare.com/argotunnel?aud=abc&callback=https%3A%2F%2Fssh.example.com%2Fcdn-cgi%2Faccess%2Fcli\n\nWaiting for login...";
        FalhaDoTunel f = ler(CLOUD, saida, null);
        assertEquals(FalhaDoTunel.Tipo.CLOUDFLARE_PEDE_LOGIN, f.tipo());
        assertTrue(f.textoCopiavel().startsWith("https://dash.cloudflare.com/argotunnel?aud=abc"), f.textoCopiavel());
        assertTrue(f.mensagem().contains("navegador"));
        assertTrue(f.mensagem().contains("não abre nada sozinho"));
        assertFalse(f.mensagem().contains("https://"), "o endereço vai só no texto copiável");
    }

    @Test
    void urlSemPalavraDeLoginNaoViraPedidoDeLogin() {
        FalhaDoTunel f = ler(CLOUD, "INF Requesting from https://api.cloudflare.com/client/v4/x\nERR failed to connect to origin", 1);
        assertEquals(FalhaDoTunel.Tipo.SAIU, f.tipo());
        assertNull(f.textoCopiavel());
    }

    @Test
    void loginDaCloudflareSoVaiParaOModoCloudflareEOEnderecoNuncaDeOutroEsquema() {
        assertEquals(FalhaDoTunel.Tipo.SAIU, ler(SSM_COM_PERFIL, "please log in at https://x.example.com/login", 1).tipo());
        FalhaDoTunel f = ler(CLOUD, "Please log in: javascript:alert(1) and file:///etc/passwd and http://inseguro.example.com", null);
        assertEquals(FalhaDoTunel.Tipo.PRAZO_ESTOURADO, f.tipo(), "só https conta");
    }

    @Test
    void prazoEstouradoDizOPrazoEOQueOProgramaDisse() {
        FalhaDoTunel f = InterpretadorDaSaida.interpretar(SSM_COM_PERFIL, "Starting session with SessionId: x", null, SistemaOperacional.MAC, 30);
        assertEquals(FalhaDoTunel.Tipo.PRAZO_ESTOURADO, f.tipo());
        assertTrue(f.mensagem().startsWith("O túnel não ficou pronto em 30 s."), f.mensagem());
        assertTrue(f.mensagem().contains("Starting session with SessionId"));
    }

    @Test
    void saiuSozinhoDizOCodigoEOTrecho() {
        FalhaDoTunel f = ler(CLOUD, "ERR boom", 3);
        assertEquals(FalhaDoTunel.Tipo.SAIU, f.tipo());
        assertEquals("O programa cloudflared terminou sozinho (código 3). O que ele disse: ERR boom", f.mensagem());
        assertEquals("O programa aws terminou sozinho (código 1).", ler(SSM_COM_PERFIL, "", 1).mensagem());
    }

    @Test
    void portaOcupadaEReconhecida() {
        assertEquals(FalhaDoTunel.Tipo.PORTA_OCUPADA, ler(CLOUD, "ERR listen tcp 127.0.0.1:53211: bind: address already in use", 1).tipo());
        assertEquals(FalhaDoTunel.Tipo.PORTA_OCUPADA, ler(SSM_COM_PERFIL, "listen tcp: bind: Only one usage of each socket address is normally permitted", 1).tipo());
    }

    @Test
    void asaidaPassaPeloRedatorEhTruncadaETextoSemControleNemHtml() {
        String token = "lab_" + "A".repeat(43);
        String lixo = "<html><b>oi</b></html>\u001b[31m vermelho \u0007\u0000 " + token + " Authorization: Bearer abcdef123456 " + "x".repeat(2000) + " FIM";
        FalhaDoTunel f = ler(CLOUD, lixo, 1);
        assertFalse(f.mensagem().contains(token), "o token foi mascarado");
        assertFalse(f.mensagem().contains("abcdef123456"));
        assertFalse(f.mensagem().chars().anyMatch(c -> Character.isISOControl(c)), "sem controles");
        assertTrue(f.mensagem().length() < 500, "truncada: " + f.mensagem().length());
        assertTrue(f.mensagem().endsWith("FIM"), "o FIM da saída é o que importa");
        assertFalse(f.mensagem().startsWith("<html"), "nunca começa como HTML");
    }

    @Test
    void asMensagensTemTextoSimplesSemExcecaoDeJava() {
        for (Transporte t : new Transporte[] {SSM_COM_PERFIL, CLOUD}) {
            for (String saida : new String[] {"", "java.lang.NullPointerException at x.y", "ERR boom"}) {
                FalhaDoTunel f = ler(t, saida, 1);
                assertFalse(f.mensagem().isBlank());
            }
        }
    }
}
