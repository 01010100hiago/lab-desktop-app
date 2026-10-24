package br.com.labdesktop.ssh;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.io.PathUtils;

/**
 * Monta um HOME falso com {@code ~/.ssh/id_ed25519} e {@code ~/.ssh/config} populados e roda código com o HOME
 * do MINA apontando para ele. É o que prova que o app nunca oferece nada de {@code ~/.ssh}: o diretório do usuário
 * de verdade não é lido por teste nenhum.
 */
final class HomeFalso {

    @FunctionalInterface
    interface Bloco {
        void rodar() throws Exception;
    }

    private HomeFalso() { }

    static Path popular(Path tmp, KeyPair chaveDoDono) throws Exception {
        Path casa = tmp.resolve("home-falso");
        Path ssh = casa.resolve(".ssh");
        Files.createDirectories(ssh);
        Path privada = ssh.resolve("id_ed25519");
        try (OutputStream out = Files.newOutputStream(privada)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(chaveDoDono, "usuario@mac", null, out);
        }
        Files.setPosixFilePermissions(privada, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(ssh.resolve("config"), "Host *\n  IdentityFile ~/.ssh/id_ed25519\n");
        return casa;
    }

    /**
     * O MINA resolve o HOME por um resolvedor próprio ({@code PathUtils}), que não relê {@code user.home};
     * por isso os dois são trocados e depois restaurados.
     */
    static void comHome(Path casa, Bloco bloco) throws Exception {
        String antes = System.getProperty("user.home");
        Path homeReal = PathUtils.getUserHomeFolder();
        System.setProperty("user.home", casa.toString());
        PathUtils.setUserHomeFolderResolver(() -> casa);
        try {
            bloco.rodar();
        } finally {
            PathUtils.setUserHomeFolderResolver(() -> homeReal);
            System.setProperty("user.home", antes);
        }
    }
}
