package br.com.labdesktop.teste;

import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;

/**
 * O "servidor" dos testes do primeiro acesso por senha: roda de VERDADE, no {@code sh} do computador de quem testa, a linha de comando que o app mandaria, com
 * {@code HOME} apontando para uma pasta de teste. Assim a instalação da chave é provada com o shell de verdade (permissões 700 e 600, sem duplicar,
 * preservando o que já existia), e não com uma imitação. Ligado a um {@link ServidorSshDeTeste}, as chaves do {@code authorized_keys} passam a valer
 * nele depois de cada comando: a prova "entro numa conexão nova só com a chave" é real de ponta a ponta.
 */
public final class ShellDeTeste implements ProvisionadorRemoto.Executor {

    private final Path home;
    private final ServidorSshDeTeste servidor;
    /** Todas as linhas de comando que chegaram, na ordem. */
    public final List<String> recebidos = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean honrarAuthorizedKeys = true;
    private volatile ServidorSshDeTeste.Manipulador outros;

    /** Sem servidor SSH: só o shell (para testar o comando sozinho). */
    public ShellDeTeste(Path home) {
        this.home = home;
        this.servidor = null;
    }

    /** Ligado ao servidor SSH de teste: ele passa a executar as linhas que chegam e a aceitar as chaves do {@code authorized_keys}. */
    public ShellDeTeste(Path home, ServidorSshDeTeste servidor) {
        this.home = home;
        this.servidor = servidor;
        servidor.comandoRemoto(this::executarParaOServidor);
    }

    /** Quem atende as linhas que NÃO são a instalação da chave (por exemplo os comandos do agente): sem isto, o shell roda tudo. */
    public ShellDeTeste outrosComandos(ServidorSshDeTeste.Manipulador m) {
        this.outros = m;
        return this;
    }

    /** {@code false}: as chaves escritas no arquivo NÃO passam a valer no servidor (o servidor "não permite login por chave"). */
    public void honrarAuthorizedKeys(boolean sim) {
        this.honrarAuthorizedKeys = sim;
    }

    public Path authorizedKeys() {
        return home.resolve(".ssh").resolve("authorized_keys");
    }

    /** O {@code sh} existe neste computador? (O CI do Windows não tem.) */
    public static boolean temShell() {
        return Files.isExecutable(Path.of("/bin/sh"));
    }

    @Override
    public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        recebidos.add(comando);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", comando);
        pb.environment().put("HOME", home.toString());
        Process p = pb.start();
        try (OutputStream entrada = p.getOutputStream()) {
            entrada.write(stdin);
        }
        byte[] out = lerTudo(p.getInputStream());
        byte[] err = lerTudo(p.getErrorStream());
        if (!p.waitFor(prazo.toSeconds(), TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new ResultadoExec(124, out, err, false);
        }
        if (honrarAuthorizedKeys && servidor != null) {
            atualizarChavesAceitas();
        }
        return new ResultadoExec(p.exitValue(), out, err, false);
    }

    private int executarParaOServidor(String linha, String stdin, StringBuilder saida) {
        if (outros != null && !linha.equals(br.com.labdesktop.acesso.ComandosDoAcesso.instalarChave())
            && !linha.equals(br.com.labdesktop.ssh.ProvaPorChave.COMANDO_DA_PROVA)) {
            recebidos.add(linha);
            return outros.executar(linha, stdin, saida);
        }
        try {
            ResultadoExec r = executar(linha, stdin.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(20));
            saida.append(r.stdout());
            for (String l : r.stderr().split("(?<=\n)")) {
                if (!l.isEmpty()) {
                    saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append(l);
                }
            }
            return r.status();
        } catch (IOException e) {
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    private static byte[] lerTudo(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    /** O que está no {@code authorized_keys} agora é o que o servidor aceita. */
    private void atualizarChavesAceitas() throws IOException {
        Path arquivo = authorizedKeys();
        if (!Files.isRegularFile(arquivo)) {
            return;
        }
        List<PublicKey> chaves = new ArrayList<>();
        try {
            for (AuthorizedKeyEntry e : AuthorizedKeyEntry.readAuthorizedKeys(arquivo)) {
                chaves.add(e.resolvePublicKey(null, PublicKeyEntryResolver.IGNORING));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        servidor.aceitar(chaves.toArray(PublicKey[]::new));
    }
}
