package br.com.labdesktop.cofre;

import br.com.labdesktop.cofre.ImportacaoDeChaveException.Motivo;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.Zeragem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
            throw new ImportacaoDeChaveException(Motivo.PEDE_SENHA, "A chave tem passphrase: informe-a.", e);
        } catch (StreamCorruptedException e) {
            // O OpenSSH confere um par de números depois de decifrar; é assim que o MINA acusa passphrase errada.
            throw new ImportacaoDeChaveException(Motivo.SENHA_INCORRETA,
                passphrase == null ? "Não consegui ler a chave." : "Passphrase incorreta.", e);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new ImportacaoDeChaveException(Motivo.FORMATO_INVALIDO,
                "O arquivo não é uma chave privada OpenSSH legível.", e);
        }
        if (pares.size() != 1) {
            throw new ImportacaoDeChaveException(Motivo.FORMATO_INVALIDO, "O arquivo deve ter exatamente uma chave.");
        }
        KeyPair par = pares.get(0);
        String tipo = KeyUtils.getKeyType(par);
        if (!ChaveSsh.ALGORITMO_ED25519.equals(tipo)) {
            throw new ImportacaoDeChaveException(Motivo.ALGORITMO_NAO_SUPORTADO,
                "Só chaves Ed25519 são aceitas (esta é " + TextoSeguro.limparLinha(String.valueOf(tipo)) + ").");
        }
        try {
            return GeradorDeChaves.empacotar(GeradorDeChaves.novoId(), nomeLimpo, par,
                GeradorDeChaves.comentarioDo(nomeDoMac), Instant.now());
        } catch (IOException | GeneralSecurityException e) {
            throw new CofreException("Não consegui guardar a chave importada.", e);
        }
    }
}
