package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.ImportacaoDeChaveException.Motivo;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportadorDeChavesTest {

    private static byte[] escrever(KeyPair par, String senha) throws Exception {
        OpenSSHKeyEncryptionContext contexto = null;
        if (senha != null) {
            contexto = new OpenSSHKeyEncryptionContext();
            contexto.setPassword(senha);
            contexto.setCipherName("AES");
            contexto.setCipherType("256");
            contexto.setCipherMode("CTR"); // aes256-ctr + bcrypt, o que o ssh-keygen usa
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(par, "original@algum-lugar", contexto, out);
        return out.toByteArray();
    }

    private static KeyPair par(String tipo, int bits) throws Exception {
        return KeyUtils.generateKeyPair(tipo, bits);
    }

    private static Motivo motivoDe(Throwable t) {
        return ((ImportacaoDeChaveException) t).motivo();
    }

    @Test
    void importaChaveSemSenhaEGuardaNoFormatoDoCofre() throws Exception {
        KeyPair original = par("ssh-ed25519", 256);
        ChaveSsh chave = ImportadorDeChaves.importar(escrever(original, null), null, "minha chave", "mac-de-teste");
        assertEquals("minha chave", chave.nome());
        assertEquals(KeyUtils.getFingerPrint(original.getPublic()), chave.impressao());
        assertTrue(chave.publica().endsWith(" lab-desktop@mac-de-teste"), chave.publica());
        assertEquals(KeyUtils.getFingerPrint(original.getPublic()), KeyUtils.getFingerPrint(chave.paraKeyPair().getPublic()));
        // Re-serializada sem senha: o cofre inteiro já é cifrado.
        assertTrue(new String(chave.privadaInterna(), StandardCharsets.US_ASCII).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"));
    }

    @Test
    void importaChaveComPassphraseEAchaveGuardadaNaoTemMais() throws Exception {
        KeyPair original = par("ssh-ed25519", 256);
        byte[] cifrada = escrever(original, "frase-secreta-da-chave");
        ChaveSsh chave = ImportadorDeChaves.importar(cifrada, "frase-secreta-da-chave".toCharArray(), "k", "mac");
        assertEquals(KeyUtils.getFingerPrint(original.getPublic()), chave.impressao());
        // O que vai para o cofre abre SEM passphrase (parser sem provedor de senha).
        assertEquals(chave.impressao(), KeyUtils.getFingerPrint(chave.paraKeyPair().getPublic()));
    }

    @Test
    void chaveComSenhaSemSenhaInformadaPedeSenha() throws Exception {
        byte[] cifrada = escrever(par("ssh-ed25519", 256), "frase");
        ImportacaoDeChaveException e = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar(cifrada, null, "k", "mac"));
        assertEquals(Motivo.PEDE_SENHA, e.motivo());
    }

    @Test
    void senhaErradaDaSenhaIncorretaESemMentira() throws Exception {
        byte[] cifrada = escrever(par("ssh-ed25519", 256), "frase");
        ImportacaoDeChaveException e = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar(cifrada, "errada".toCharArray(), "k", "mac"));
        assertEquals(Motivo.SENHA_INCORRETA, e.motivo());
        assertFalse(e.getMessage().contains("errada"));
    }

    @Test
    void naoZeraNemAlteraOsArgumentosDoChamador() throws Exception {
        byte[] conteudo = escrever(par("ssh-ed25519", 256), "frase");
        byte[] copia = conteudo.clone();
        char[] frase = "frase".toCharArray();
        ImportadorDeChaves.importar(conteudo, frase, "k", "mac");
        assertTrue(Arrays.equals(copia, conteudo));
        assertEquals("frase", new String(frase));
    }

    @Test
    void chaveQueNaoEEd25519EhRecusada() throws Exception {
        byte[] ecdsa = escrever(par("ecdsa-sha2-nistp256", 256), null);
        ImportacaoDeChaveException e = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar(ecdsa, null, "k", "mac"));
        assertEquals(Motivo.ALGORITMO_NAO_SUPORTADO, e.motivo());
        assertTrue(e.getMessage().contains("Ed25519"), e.getMessage());
    }

    @Test
    void lixoEFormatoInvalido() {
        ImportacaoDeChaveException e = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar("isto não é uma chave".getBytes(StandardCharsets.UTF_8), null, "k", "mac"));
        assertEquals(Motivo.FORMATO_INVALIDO, e.motivo());
        e = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar(new byte[0], null, "k", "mac"));
        assertEquals(Motivo.FORMATO_INVALIDO, e.motivo());
        e = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar(
                "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n".getBytes(StandardCharsets.UTF_8),
                null, "k", "mac"));
        assertEquals(Motivo.FORMATO_INVALIDO, e.motivo());
    }

    @Test
    void asMensagensDeErroSaoClarasParaQuemNaoEhTecnico() throws Exception {
        // Arquivo que não é chave.
        ImportacaoDeChaveException naoEChave = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar("isto não é uma chave".getBytes(StandardCharsets.UTF_8), null, "k", "mac"));
        assertEquals("Esse arquivo não é uma chave SSH.", naoEChave.getMessage());
        assertEquals(Motivo.FORMATO_INVALIDO, naoEChave.motivo());

        // Chave protegida por senha, sem a senha: não é a senha-mestra.
        byte[] cifrada = escrever(par("ssh-ed25519", 256), "frase");
        ImportacaoDeChaveException pedeSenha = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar(cifrada, null, "k", "mac"));
        assertEquals("Este arquivo está protegido por uma senha própria, que não é a senha-mestra. Digite-a abaixo.",
            pedeSenha.getMessage());
        assertEquals(Motivo.PEDE_SENHA, pedeSenha.motivo());

        ImportacaoDeChaveException errada = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar(cifrada, "errada".toCharArray(), "k", "mac"));
        assertEquals("A senha do arquivo não confere. Confira e tente de novo.", errada.getMessage());
        assertEquals(Motivo.SENHA_INCORRETA, errada.motivo());
    }

    @Test
    void aImpressaoDigitalDeUmServidorNaoEChaveEODonoLeIssoEmPortugues() {
        String sha = "SHA256:" + "A".repeat(43);
        for (String texto : new String[] {sha, sha + "\n", "256 " + sha + " host (ED25519)", "  " + sha + "  ",
            "MD5:" + "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"}) {
            ImportacaoDeChaveException e = assertThrows(ImportacaoDeChaveException.class,
                () -> ImportadorDeChaves.importar(texto.getBytes(StandardCharsets.UTF_8), null, "k", "mac"), texto);
            assertEquals(Motivo.IMPRESSAO_DE_SERVIDOR, e.motivo(), texto);
            assertEquals("Isso é a impressão digital de um servidor, não uma chave. Você não precisa dela aqui.", e.getMessage());
        }
    }

    @Test
    void aChavePublicaNoLugarDaPrivadaTemMensagemPropria() throws Exception {
        String publica = org.apache.sshd.common.config.keys.PublicKeyEntry.toString(par("ssh-ed25519", 256).getPublic())
            + " alguem@mac\n";
        ImportacaoDeChaveException e = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importar(publica.getBytes(StandardCharsets.UTF_8), null, "k", "mac"));
        assertEquals(Motivo.CHAVE_PUBLICA, e.motivo());
        assertTrue(e.getMessage().startsWith("Isso é uma chave pública."), e.getMessage());
    }

    @Test
    void umaChaveDeVerdadeNuncaEConfundidaComImpressaoNemComPublica() throws Exception {
        // A privada tem texto comprido em base64 e um comentário que cita SHA256: não pode cair no reconhecimento.
        KeyPair original = par("ssh-ed25519", 256);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(original, "SHA256:" + "B".repeat(43) + " ssh-ed25519 AAAA", null, out);
        ChaveSsh chave = ImportadorDeChaves.importar(out.toByteArray(), null, "k", "mac");
        assertEquals(KeyUtils.getFingerPrint(original.getPublic()), chave.impressao());
    }

    @Test
    void importaDeArquivoEnaoMexeNele(@TempDir Path tmp) throws Exception {
        KeyPair original = par("ssh-ed25519", 256);
        Path arquivo = tmp.resolve("id_teste");
        Files.write(arquivo, escrever(original, null));
        byte[] antes = Files.readAllBytes(arquivo);
        var tempoAntes = Files.getLastModifiedTime(arquivo);

        ChaveSsh chave = ImportadorDeChaves.importarDeArquivo(arquivo, null, "do arquivo", "mac");
        assertEquals(KeyUtils.getFingerPrint(original.getPublic()), chave.impressao());
        assertTrue(Arrays.equals(antes, Files.readAllBytes(arquivo)));
        assertEquals(tempoAntes, Files.getLastModifiedTime(arquivo));
    }

    @Test
    void arquivoInexistenteOuGrandeDemais(@TempDir Path tmp) throws Exception {
        ImportacaoDeChaveException e = assertThrows(ImportacaoDeChaveException.class,
            () -> ImportadorDeChaves.importarDeArquivo(tmp.resolve("nao-existe"), null, "k", "mac"));
        assertEquals(Motivo.ARQUIVO_ILEGIVEL, e.motivo());
    }
}
