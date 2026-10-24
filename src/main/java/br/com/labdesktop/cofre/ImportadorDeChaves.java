package br.com.labdesktop.cofre;

import br.com.labdesktop.cofre.ImportacaoDeChaveException.Motivo;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.Zeragem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.security.auth.login.FailedLoginException;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;

/**
 * Importa uma chave Ed25519 existente (por exemplo {@code ~/.ssh/id_ed25519}) para o cofre (ARQUITETURA 5.8).
 * É opcional: a recomendação do projeto é gerar chave nova. O app só lê o arquivo que o USUÁRIO escolheu na
 * tela; nunca varre {@code ~/.ssh}.
 *
 * <p>A chave é lida pelo MINA (com a passphrase, se tiver), e RE-SERIALIZADA sem passphrase para dentro do
 * cofre, que já é cifrado. O arquivo original não é alterado.
 */
public final class ImportadorDeChaves {

    /** Chave privada OpenSSH de verdade tem poucos KiB; mais que isto não é chave. */
    static final long TAMANHO_MAXIMO = 64 * 1024;

    /**
     * Acima disto o conteúdo não é uma impressão digital nem uma linha de chave pública: nem se tenta reconhecer. Um
     * arquivo de chave privada Ed25519 tem uns 400 bytes, mas ele nunca passa por este reconhecimento (tem "PRIVATE KEY").
     */
    private static final int TAMANHO_MAXIMO_DE_TEXTO_CURTO = 2048;
    /** {@code SHA256:...} (o que o {@code ssh-keygen -lf} e o diálogo "Confiar neste servidor" mostram) ou {@code MD5:...}. */
    private static final Pattern IMPRESSAO = Pattern.compile("\\b(SHA256|MD5):[A-Za-z0-9+/=:]{16,}");
    /** O começo de uma linha de chave pública do OpenSSH: o tipo e o texto em base64. */
    private static final Pattern CHAVE_PUBLICA = Pattern.compile(
        "^(ssh-(ed25519|rsa|dss)|ecdsa-sha2-\\S+|sk-\\S+)\\s+[A-Za-z0-9+/=]{20,}");

    /** O texto que o usuário vê quando o arquivo é uma chave cifrada com uma senha própria (não é a senha-mestra do app). */
    public static final String MENSAGEM_PEDE_SENHA =
        "Este arquivo está protegido por uma senha própria, que não é a senha-mestra. Digite-a abaixo.";
    public static final String MENSAGEM_NAO_E_CHAVE = "Esse arquivo não é uma chave SSH.";
    public static final String MENSAGEM_IMPRESSAO =
        "Isso é a impressão digital de um servidor, não uma chave. Você não precisa dela aqui.";
    public static final String MENSAGEM_CHAVE_PUBLICA =
        "Isso é uma chave pública. O app precisa da chave privada: o arquivo com o mesmo nome, sem o .pub no final.";

    private ImportadorDeChaves() { }

    /**
     * Importa de um arquivo.
     *
     * @param passphrase passphrase da chave, ou {@code null} se não tiver; não é zerada aqui (é do chamador)
     */
    public static ChaveSsh importarDeArquivo(Path arquivo, char[] passphrase, String nome, String nomeDoMac)
        throws CofreException {
        byte[] conteudo;
        try {
            if (Files.size(arquivo) > TAMANHO_MAXIMO) {
                throw new ImportacaoDeChaveException(Motivo.ARQUIVO_ILEGIVEL, "O arquivo é grande demais para ser uma chave.");
            }
            conteudo = Files.readAllBytes(arquivo);
        } catch (IOException e) {
            throw new ImportacaoDeChaveException(Motivo.ARQUIVO_ILEGIVEL, "Não consegui ler o arquivo da chave.", e);
        }
        try {
            return importar(conteudo, passphrase, nome, nomeDoMac);
        } finally {
            Zeragem.zerar(conteudo);
        }
    }

    /** Importa do texto {@code BEGIN OPENSSH PRIVATE KEY}. Não zera {@code conteudo} nem {@code passphrase}. */
    public static ChaveSsh importar(byte[] conteudo, char[] passphrase, String nome, String nomeDoMac)
        throws CofreException {
        String nomeLimpo = TextoSeguro.limparLinha(nome);
        if (nomeLimpo.isEmpty() || nomeLimpo.length() > 64) {
            throw new IllegalArgumentException("O nome da chave deve ter de 1 a 64 caracteres.");
        }
        reconhecerOQueNaoEChave(conteudo);
        // A API do MINA devolve a passphrase como String (não zerável): limite aceito, vida curta.
        // Sem passphrase informada o provedor é nulo: o MINA então acusa FailedLoginException numa chave cifrada.
        FilePasswordProvider provedor = passphrase == null
            ? null
            : (sessao, recurso, tentativa) -> tentativa == 0 ? new String(passphrase) : null;
        List<KeyPair> pares = new ArrayList<>(1);
        try {
            Iterable<KeyPair> lidas = SecurityUtils.loadKeyPairIdentities(
                null, NamedResource.ofName(nomeLimpo), new ByteArrayInputStream(conteudo), provedor);
            if (lidas != null) {
                lidas.forEach(pares::add);
            }
        } catch (FailedLoginException e) {
            throw new ImportacaoDeChaveException(Motivo.PEDE_SENHA, MENSAGEM_PEDE_SENHA, e);
        } catch (StreamCorruptedException e) {
            // O OpenSSH confere um par de números depois de decifrar; é assim que o MINA acusa passphrase errada.
            throw new ImportacaoDeChaveException(Motivo.SENHA_INCORRETA,
                passphrase == null ? "Não consegui ler a chave: o arquivo parece estar danificado."
                    : "A senha do arquivo não confere. Confira e tente de novo.", e);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new ImportacaoDeChaveException(Motivo.FORMATO_INVALIDO, MENSAGEM_NAO_E_CHAVE, e);
        }
        if (pares.isEmpty()) {
            throw new ImportacaoDeChaveException(Motivo.FORMATO_INVALIDO, MENSAGEM_NAO_E_CHAVE);
        }
        if (pares.size() > 1) {
            throw new ImportacaoDeChaveException(Motivo.FORMATO_INVALIDO,
                "Esse arquivo tem mais de uma chave. Use um arquivo com uma chave só.");
        }
        KeyPair par = pares.get(0);
        String tipo = KeyUtils.getKeyType(par);
        if (!ChaveSsh.ALGORITMO_ED25519.equals(tipo)) {
            throw new ImportacaoDeChaveException(Motivo.ALGORITMO_NAO_SUPORTADO,
                "Só chaves do tipo Ed25519 (o mesmo tipo que o app cria sozinho) são aceitas aqui; esta é do tipo "
                    + TextoSeguro.limparLinha(String.valueOf(tipo)) + ".");
        }
        try {
            return GeradorDeChaves.empacotar(GeradorDeChaves.novoId(), nomeLimpo, par,
                GeradorDeChaves.comentarioDo(nomeDoMac), Instant.now(), InfoChave.Origem.IMPORTADA);
        } catch (IOException | GeneralSecurityException e) {
            throw new CofreException("Não consegui guardar a chave importada.", e);
        }
    }

    /**
     * O usuário já tentou entregar ao app a impressão digital de um servidor (o {@code SHA256:...} do diálogo "Confiar neste
     * servidor") onde achou que era a chave, e a chave pública no lugar da privada. Dizer isso em português claro vale mais
     * que "não consegui ler": só conteúdo CURTO e sem "PRIVATE KEY" é examinado, então uma chave de verdade nunca cai aqui.
     */
    static void reconhecerOQueNaoEChave(byte[] conteudo) throws ImportacaoDeChaveException {
        if (conteudo.length == 0 || conteudo.length > TAMANHO_MAXIMO_DE_TEXTO_CURTO) {
            return;
        }
        String texto = new String(conteudo, StandardCharsets.UTF_8).strip();
        if (texto.contains("PRIVATE KEY")) {
            return;
        }
        if (IMPRESSAO.matcher(texto).find()) {
            throw new ImportacaoDeChaveException(Motivo.IMPRESSAO_DE_SERVIDOR, MENSAGEM_IMPRESSAO);
        }
        if (CHAVE_PUBLICA.matcher(texto).find()) {
            throw new ImportacaoDeChaveException(Motivo.CHAVE_PUBLICA, MENSAGEM_CHAVE_PUBLICA);
        }
    }
}
