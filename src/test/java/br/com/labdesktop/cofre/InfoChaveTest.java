package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A linha do authorized_keys e o comando de colar (ARQUITETURA 5.8): conferidos rodando o comando de verdade. */
class InfoChaveTest {

    private static final String PUBLICA = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBase64DaChave lab-desktop@mac-de-teste";

    private static InfoChave chave(String publica) {
        return new InfoChave("k1", "lab", ChaveSsh.ALGORITMO_ED25519, publica, "SHA256:abc", Instant.EPOCH);
    }

    @Test
    void aLinhaRestringeAChaveAoAgente() {
        assertEquals("no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:7000\" " + PUBLICA,
            chave(PUBLICA).linhaDeAuthorizedKeys(7000));
        assertTrue(chave(PUBLICA).linhaDeAuthorizedKeys(7123).contains("permitopen=\"127.0.0.1:7123\""));
        assertThrows(IllegalArgumentException.class, () -> chave(PUBLICA).linhaDeAuthorizedKeys(0));
        assertThrows(IllegalArgumentException.class, () -> chave(PUBLICA).linhaDeAuthorizedKeys(65_536));
    }

    /**
     * O texto da tela dizia "restrita ao agente (sem terminal...)", e a linha só tem três opções: a chave ainda abre shell.
     * Se a linha ganhar {@code no-pty}, {@code restrict} ou {@code command=}, este teste falha e o texto tem de mudar.
     */
    @Test
    void oTextoDasRestricoesDizOQueALinhaFazDeVerdadeESemPrometerSemTerminal() {
        String linha = chave(PUBLICA).linhaDeAuthorizedKeys(7000);
        for (String ausente : new String[] {"no-pty", "restrict", "command=", "no-port-forwarding"}) {
            assertFalse(linha.contains(ausente), "a linha agora tem " + ausente + ": revise InfoChave.RESTRICOES_DA_LINHA");
        }
        assertTrue(linha.contains("no-agent-forwarding") && linha.contains("no-X11-forwarding") && linha.contains("permitopen="));

        String texto = InfoChave.RESTRICOES_DA_LINHA;
        assertFalse(texto.contains("sem terminal"), texto);
        assertFalse(texto.contains("restrita ao agente"), texto);
        assertTrue(texto.contains("agente SSH") && texto.contains("X11") && texto.contains("túnel"), "diz o que a linha impede: " + texto);
        assertTrue(texto.contains("NÃO tira o shell"), "diz o que a linha NÃO impede: " + texto);
        assertTrue(texto.contains("executa comandos"), texto);
        assertTrue(texto.contains("token") && texto.contains("2FA") && texto.contains("terminal"), "e por que o app precisa do shell: " + texto);
    }

    @Test
    void oComandoColadoNoShellAcrescentaExatamenteALinhaComPermissoesFechadas(@TempDir Path casa) throws Exception {
        String comando = chave(PUBLICA).comandoDeInstalacao(7000);
        rodar(comando, casa);
        rodar(comando, casa); // colar duas vezes acrescenta duas linhas (o usuário vê a repetição)

        Path arquivo = casa.resolve(".ssh").resolve("authorized_keys");
        assertEquals(java.util.List.of(chave(PUBLICA).linhaDeAuthorizedKeys(7000), chave(PUBLICA).linhaDeAuthorizedKeys(7000)),
            Files.readAllLines(arquivo));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(arquivo));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(casa.resolve(".ssh")));
    }

    @Test
    void aspaSimplesOuCifraoNoComentarioNaoEscapamDoComandoNemExecutamNada(@TempDir Path casa) throws Exception {
        String hostil = "ssh-ed25519 AAAAC3Nza lab-desktop@mac'; touch " + casa.resolve("invadido") + "; echo '$(touch " + casa.resolve("invadido2") + ")";
        rodar(chave(hostil).comandoDeInstalacao(7000), casa);

        assertTrue(Files.notExists(casa.resolve("invadido")), "o comentário virou comando");
        assertTrue(Files.notExists(casa.resolve("invadido2")), "a substituição de comando foi executada");
        String gravada = Files.readString(casa.resolve(".ssh").resolve("authorized_keys")).strip();
        assertEquals(chave(hostil).linhaDeAuthorizedKeys(7000), gravada, "o texto chegou inteiro, como texto");
    }

    private static void rodar(String comando, Path casa) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", comando).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("HOME", casa.toString());
        Process p = pb.start();
        String saida = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "o comando não terminou");
        assertEquals(0, p.exitValue(), "o comando falhou: " + saida);
    }
}
