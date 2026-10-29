package br.com.labdesktop.transporte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.Transporte;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Os argumentos exatos de cada modo: listas, nunca uma linha de comando; nada do usuário começa com hífen. */
class ArgumentosDoTunelTest {

    private static final String INSTANCIA = "i-0123456789abcdef0";

    @Test
    void ssmCompletoEmListaExata() {
        assertEquals(List.of("/opt/homebrew/bin/aws", "ssm", "start-session", "--target", INSTANCIA, "--document-name", "AWS-StartPortForwardingSession",
                "--parameters", "portNumber=22,localPortNumber=54321", "--region", "us-east-1", "--profile", "meu-perfil"),
            ArgumentosDoTunel.ssm("/opt/homebrew/bin/aws", Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 22), 54321));
    }

    @Test
    void ssmSemPerfilSemRegiaoEComPortaSshDiferente() {
        assertEquals(List.of("aws", "ssm", "start-session", "--target", "mi-0123456789abcdef0", "--document-name", "AWS-StartPortForwardingSession",
                "--parameters", "portNumber=2222,localPortNumber=40000"),
            ArgumentosDoTunel.ssm("aws", Transporte.ssm("mi-0123456789abcdef0", null, null, 2222), 40000));
        assertEquals(List.of("aws", "ssm", "start-session", "--target", INSTANCIA, "--document-name", "AWS-StartPortForwardingSession",
                "--parameters", "portNumber=22,localPortNumber=40001", "--region", "sa-east-1"),
            ArgumentosDoTunel.ssm("aws", Transporte.ssm(INSTANCIA, "sa-east-1", null, 22), 40001));
        assertEquals(List.of("aws", "ssm", "start-session", "--target", INSTANCIA, "--document-name", "AWS-StartPortForwardingSession",
                "--parameters", "portNumber=22,localPortNumber=40002", "--profile", "p"),
            ArgumentosDoTunel.ssm("aws", Transporte.ssm(INSTANCIA, null, "p", 22), 40002));
    }

    @Test
    void cloudflareEmListaExata() {
        assertEquals(List.of("C:\\Program Files (x86)\\cloudflared\\cloudflared.exe", "access", "tcp", "--hostname", "ssh.example.com", "--url", "127.0.0.1:50123"),
            ArgumentosDoTunel.cloudflared("C:\\Program Files (x86)\\cloudflared\\cloudflared.exe", Transporte.cloudflared("ssh.example.com"), 50123));
    }

    @Test
    void paraEscolhePeloModoERecusaDiretoELocal() {
        assertEquals(ArgumentosDoTunel.cloudflared("cf", Transporte.cloudflared("a.b"), 40000), ArgumentosDoTunel.para("cf", Transporte.cloudflared("a.b"), 40000));
        assertEquals(ArgumentosDoTunel.ssm("aws", Transporte.ssm(INSTANCIA, null, null, 22), 40000), ArgumentosDoTunel.para("aws", Transporte.ssm(INSTANCIA, null, null, 22), 40000));
        assertThrows(IllegalArgumentException.class, () -> ArgumentosDoTunel.para("x", Transporte.DIRETO, 40000));
        assertThrows(IllegalArgumentException.class, () -> ArgumentosDoTunel.para("x", Transporte.LOCAL, 40000));
    }

    @Test
    void nadaDoUsuarioComecaComHifenNemTemEspacoENenhumArgumentoEUmaLinhaDeComando() {
        List<List<String>> todas = List.of(
            ArgumentosDoTunel.ssm("aws", Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 22), 40000),
            ArgumentosDoTunel.ssm("aws", Transporte.ssm("mi-abcdef12", null, null, 65535), 65535),
            ArgumentosDoTunel.cloudflared("cloudflared", Transporte.cloudflared("a-b.example.com"), 1024));
        for (List<String> a : todas) {
            for (int i = 1; i < a.size(); i++) {
                String atual = a.get(i);
                String anterior = a.get(i - 1);
                boolean ehOpcaoFixa = List.of("--target", "--document-name", "--parameters", "--region", "--profile", "--hostname", "--url").contains(atual);
                if (!ehOpcaoFixa) {
                    assertFalse(atual.startsWith("-"), "um valor começa com hífen: " + atual);
                }
                assertTrue(atual.isEmpty() || !atual.contains(" "), "argumento com espaço (parece linha de comando): " + atual + " depois de " + anterior);
                assertFalse(atual.contains(";") || atual.contains("|") || atual.contains("$") || atual.contains("`") || atual.contains("&"), atual);
            }
            assertTrue(a.stream().noneMatch(s -> s.equals("sh") || s.equals("-c") || s.equals("cmd") || s.equals("/c") || s.equals("powershell")), "sem shell: " + a);
        }
    }

    @Test
    void cadaValorDoUsuarioAparecePorInteiroNoSeuProprioItem() {
        Transporte t = Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 22);
        List<String> a = ArgumentosDoTunel.ssm("aws", t, 41000);
        assertEquals(INSTANCIA, a.get(a.indexOf("--target") + 1));
        assertEquals("us-east-1", a.get(a.indexOf("--region") + 1));
        assertEquals("meu-perfil", a.get(a.indexOf("--profile") + 1));
        List<String> c = ArgumentosDoTunel.cloudflared("cloudflared", Transporte.cloudflared("ssh.example.com"), 41000);
        assertEquals("ssh.example.com", c.get(c.indexOf("--hostname") + 1));
    }

    @Test
    void portaLocalForaDaFaixaERecusada() {
        assertThrows(IllegalArgumentException.class, () -> ArgumentosDoTunel.cloudflared("x", Transporte.cloudflared("a.b"), 0));
        assertThrows(IllegalArgumentException.class, () -> ArgumentosDoTunel.ssm("x", Transporte.ssm(INSTANCIA, null, null, 22), 70000));
    }

    @Test
    void oArgumentoDaVersaoEOProgramaMaisVersion() {
        assertEquals(List.of("/usr/bin/aws", "--version"), ArgumentosDoTunel.versao("/usr/bin/aws"));
    }
}
