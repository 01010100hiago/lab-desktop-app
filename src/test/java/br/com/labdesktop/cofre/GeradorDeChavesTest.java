package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.Signature;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class GeradorDeChavesTest {

    @Test
    void chaveGeradaEAceitaPeloParserDoMinaEBate() throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac-de-teste");
        assertTrue(ValidadorDeNomes.idLocalValido(chave.id()), chave.id());
        assertEquals("ssh-ed25519", chave.algoritmo());
        assertEquals("lab", chave.nome());

        // O texto guardado é o formato OpenSSH de verdade...
        String pem = new String(chave.privadaInterna(), StandardCharsets.US_ASCII);
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"), pem);
        assertTrue(pem.stripTrailing().endsWith("-----END OPENSSH PRIVATE KEY-----"));

        // ...que o parser do MINA lê sem arquivo nenhum, e é o mesmo par que foi mostrado ao usuário.
        KeyPair par = chave.paraKeyPair();
        assertEquals("ssh-ed25519", KeyUtils.getKeyType(par));
        assertEquals(chave.impressao(), KeyUtils.getFingerPrint(par.getPublic()));
        assertTrue(chave.impressao().startsWith("SHA256:"));
        assertEquals(chave.publica().substring(0, chave.publica().lastIndexOf(' ')), PublicKeyEntry.toString(par.getPublic()));

        // Cada chamada devolve um KeyPair novo.
        assertTrue(chave.paraKeyPair() != par);
    }

    @Test
    void privadaReconstruidaAssinaEAPublicaMostradaVerifica() throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac");
        KeyPair par = chave.paraKeyPair();
        byte[] mensagem = "mensagem de teste".getBytes(StandardCharsets.UTF_8);
        Signature assinar = Signature.getInstance("Ed25519");
        assinar.initSign(par.getPrivate());
        assinar.update(mensagem);
        byte[] assinatura = assinar.sign();

        // A pública que o app MOSTRA (linha do authorized_keys), relida do texto, verifica a assinatura da privada.
        PublicKeyEntry entrada = PublicKeyEntry.parsePublicKeyEntry(chave.publica());
        java.security.PublicKey publicaMostrada = entrada.resolvePublicKey(null, null, null);
        Signature verificar = Signature.getInstance("Ed25519");
        verificar.initVerify(publicaMostrada);
        verificar.update(mensagem);
        assertTrue(verificar.verify(assinatura));
    }

    @Test
    void publicaTemOComentarioLabDesktopArrobaMac() throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac-de-teste");
        String[] partes = chave.publica().split(" ");
        assertEquals(3, partes.length, chave.publica());
        assertEquals("ssh-ed25519", partes[0]);
        assertEquals("lab-desktop@mac-de-teste", partes[2]);
        // Nome do Mac com espaço ou controle não quebra a linha de authorized_keys.
        ChaveSsh outra = GeradorDeChaves.gerar("lab", "MacBook Pro\nde Maria");
        assertEquals(3, outra.publica().split(" ").length, outra.publica());
        assertFalse(outra.publica().contains("\n"));
    }

    @Test
    void linhaDeAuthorizedKeysTemAsRestricoesDoDocumento() throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac");
        String linha = chave.linhaDeAuthorizedKeys(7000);
        assertEquals("no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:7000\" " + chave.publica(), linha);
        assertFalse(linha.contains("command="), "o terminal usa exec: sem command=");
        assertTrue(chave.linhaDeAuthorizedKeys(7100).contains("permitopen=\"127.0.0.1:7100\""));
    }

    @Test
    void chavesGeradasSaoDistintas() throws Exception {
        ChaveSsh a = GeradorDeChaves.gerar("a", "mac");
        ChaveSsh b = GeradorDeChaves.gerar("b", "mac");
        assertNotEquals(a.id(), b.id());
        assertNotEquals(a.impressao(), b.impressao());
        assertFalse(java.util.Arrays.equals(a.privadaInterna(), b.privadaInterna()));
    }

    @Test
    void nomeInvalidoEhRecusado() {
        assertThrows(IllegalArgumentException.class, () -> GeradorDeChaves.gerar("   ", "mac"));
        assertThrows(IllegalArgumentException.class, () -> GeradorDeChaves.gerar("x".repeat(65), "mac"));
    }

    @Test
    void toStringENuncaMostraAChavePrivada() throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac");
        String corpo = new String(chave.privadaInterna(), StandardCharsets.US_ASCII).lines().skip(1).findFirst().orElseThrow();
        assertFalse(chave.toString().contains(corpo.substring(0, 20)));
        assertFalse(chave.toString().contains("BEGIN"));
        assertTrue(chave.toString().contains("redigida"));
        assertFalse(chave.info().toString().contains("BEGIN"));
    }

    @Test
    void zerarImpedeUsoEZeraOsBytes() throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac");
        byte[] interno = chave.privadaInterna();
        assertFalse(chave.estaZerado());
        chave.zerar();
        assertTrue(chave.estaZerado());
        assertArrayEquals(new byte[interno.length], interno, "o MESMO array foi preenchido com zeros");
        assertThrows(SegredoZeradoException.class, chave::paraKeyPair);
        assertThrows(SegredoZeradoException.class, chave::privadaInterna);
        // A parte pública continua legível: não é segredo.
        assertTrue(chave.publica().startsWith("ssh-ed25519 "));
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void ssh_keygenDoSistemaAceitaAChaveEConfereAImpressao(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/ssh-keygen")), "ssh-keygen ausente");
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac");
        Path arquivo = tmp.resolve("chave-de-teste");
        Files.write(arquivo, chave.privadaInterna());
        Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-------"));

        // ssh-keygen -y: deriva a pública a partir da privada. É a prova de que o PEM escrito é OpenSSH válido.
        String derivada = executar(tmp, "/usr/bin/ssh-keygen", "-y", "-f", arquivo.toString()).strip();
        assertEquals(chave.publica().substring(0, chave.publica().lastIndexOf(' ')),
            derivada.substring(0, derivada.lastIndexOf(' ') < 0 ? derivada.length() : derivada.lastIndexOf(' ')));

        // ssh-keygen -lf: a impressão é a mesma que o app mostra (formato SHA256:...).
        Path pub = tmp.resolve("chave-de-teste.pub");
        Files.writeString(pub, chave.publica() + "\n");
        String lf = executar(tmp, "/usr/bin/ssh-keygen", "-lf", pub.toString());
        assertTrue(lf.contains(chave.impressao()), lf + " / " + chave.impressao());
    }

    private static String executar(Path pasta, String... comando) throws Exception {
        Process p = new ProcessBuilder(comando).directory(pasta.toFile()).redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), saida);
        return saida;
    }

    @Test
    void oParserDoMinaTambemLeOTextoDireto() throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac");
        int n = 0;
        for (KeyPair par : SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("t"),
            new ByteArrayInputStream(chave.privadaInterna()), null)) {
            assertEquals(chave.impressao(), KeyUtils.getFingerPrint(par.getPublic()));
            n++;
        }
        assertEquals(1, n);
    }
}
