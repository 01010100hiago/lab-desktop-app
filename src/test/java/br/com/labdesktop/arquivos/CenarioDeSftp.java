package br.com.labdesktop.arquivos;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ssh.ConexaoSsh;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondaDoAgente;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;

/**
 * O mundo de um teste do gerenciador de arquivos: um {@code SshServer} embutido com o subsistema SFTP do MINA sobre uma pasta
 * temporária ({@code RootedFileSystem}: o cliente vê {@code /} como essa pasta), uma {@link ConexaoSsh} conectada de verdade
 * e um {@link ClienteSftpMina} aberto por ela. É o mesmo caminho que a tela percorre, sem tela.
 *
 * <p>A pasta tem a forma do lab: {@code /srv/lab/projects/orbita} (o projeto) e {@code /srv/lab/projects/outro}.
 */
public final class CenarioDeSftp implements AutoCloseable {

    public static final String PROJETOS = "/srv/lab/projects";
    public static final String ORBITA = PROJETOS + "/orbita";

    public final Path raiz;
    public final ServidorSshDeTeste ssh;
    public final CofreFalso cofre;
    public final ConexaoSsh conexao;
    public final ClienteSftpMina cliente;

    public CenarioDeSftp(Path pasta) throws IOException, GeneralSecurityException {
        this(pasta, true);
    }

    /** @param comSftp {@code false}: o servidor NÃO aceita o subsistema SFTP (o caso "este servidor não aceita transferência") */
    public CenarioDeSftp(Path pasta, boolean comSftp) throws IOException, GeneralSecurityException {
        this.raiz = pasta.toAbsolutePath().toRealPath().resolve("servidor");
        Files.createDirectories(raiz.resolve("srv/lab/projects/orbita"));
        Files.createDirectories(raiz.resolve("srv/lab/projects/outro"));
        ssh = ServidorSshDeTeste.ed25519();
        if (comSftp) {
            ssh.comSftp(raiz);
        }
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin()));
        ssh.aceitar(cofre.par().getPublic());
        conexao = new ConexaoSsh(CofreFalso.ID, cofre, ConfigSsh.rapida(), tunel -> SondaDoAgente.Resultado.OK);
        conexao.iniciar();
        Espera.ate(Duration.ofSeconds(20), () -> "estado=" + conexao.info(), () -> conexao.info().estado() == EstadoConexao.CONECTADO);
        cliente = new ClienteSftpMina(conexao::abrirSftp);
    }

    /** O caminho real, no disco do teste, de um caminho do servidor. */
    public Path local(String caminhoDoServidor) {
        return raiz.resolve(caminhoDoServidor.substring(1));
    }

    public Path orbita() {
        return local(ORBITA);
    }

    public Escopo escopoDoProjeto() {
        try {
            return Escopo.projeto(PROJETOS, "orbita", "/");
        } catch (ErroSftp e) {
            throw new IllegalStateException(e);
        }
    }

    public Escopo escopoDoServidor(RaizDoServidor r) {
        return switch (r) {
            case PROJETOS -> Escopo.servidor("Projetos", PROJETOS, PROJETOS, "/");
            case MINHA_PASTA -> Escopo.servidor("Minha pasta", "/", PROJETOS, "/");
            case TUDO -> Escopo.servidor("Tudo (/)", "/", PROJETOS, "/");
        };
    }

    public ServicoDeArquivos servicoDoProjeto() {
        return new ServicoDeArquivos(cliente, escopoDoProjeto());
    }

    public ServicoDeArquivos servicoDoServidor(RaizDoServidor r) {
        return new ServicoDeArquivos(cliente, escopoDoServidor(r));
    }

    /** Escreve um arquivo de texto na pasta do servidor (pelo disco, sem passar pelo SFTP). */
    public Path escrever(String caminhoDoServidor, String conteudo) throws IOException {
        Path p = local(caminhoDoServidor);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, conteudo);
    }

    @Override
    public void close() throws IOException {
        cliente.close();
        conexao.fechar().join();
        ssh.close();
    }
}
