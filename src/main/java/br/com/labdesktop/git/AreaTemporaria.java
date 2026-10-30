package br.com.labdesktop.git;

import br.com.labdesktop.projetos.AmbienteDeProjetos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Um repositório DESCARTÁVEL, sem pasta de trabalho, numa pasta temporária do SISTEMA (nunca dentro do projeto), que existe só para o git aplicar o {@code .gitignore} do projeto e dizer quais arquivos
 * entrariam num {@code git add}, SEM criar nada no projeto: o {@code git ls-files} precisa de um repositório e o projeto ainda não tem o dele. Quem o abre o FECHA (apaga) no fim, em qualquer caso.
 * No computador local o app escreve o arquivo {@code info/exclude} direto; no servidor por SSH usa os comandos fixos {@code tee} e {@code rm -rf}, que só aceitam o nome exato que o app gera
 * ({@code /tmp/lab-desktop-git-} mais 16 letras hexadecimais).
 */
public final class AreaTemporaria implements AutoCloseable {

    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final SecureRandom AZAR = new SecureRandom();

    private final boolean local;
    private final String caminho;
    private final ExecutorDeGit executor;

    private AreaTemporaria(boolean local, String caminho, ExecutorDeGit executor) {
        this.local = local;
        this.caminho = caminho;
        this.executor = executor;
    }

    /** Abre a área: pasta temporária e {@code git init --bare} nela. */
    public static AreaTemporaria abrir(AmbienteDeProjetos ambiente, ExecutorDeGit executor) throws ErroDoGit, InterruptedException {
        String nome = "lab-desktop-git-" + HexFormat.of().formatHex(randomico());
        String caminho;
        if (ambiente.local()) {
            try {
                Path alvo = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve(nome);
                Path pasta;
                try {
                    // só o proprietário lê (700) onde o sistema tem permissões POSIX; no Windows a pasta temporária já é do usuário
                    pasta = Files.createDirectory(alvo, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
                } catch (UnsupportedOperationException e) {
                    pasta = Files.createDirectory(alvo);
                }
                caminho = pasta.toString();
            } catch (IOException e) {
                throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.SEM_PERMISSAO, "Não consegui criar a pasta temporária deste computador para conferir a lista de arquivos."));
            }
        } else {
            caminho = ComandosDeGit.PREFIXO_DA_AREA + nome.substring("lab-desktop-git-".length());
        }
        AreaTemporaria area = new AreaTemporaria(ambiente.local(), caminho, executor);
        try {
            if (!ambiente.local()) {
                ExecutorDeGit.Saida pasta = executor.rodar(ComandosDeGit.criarPastaDaArea(caminho), PRAZO); // mkdir -m 700: outro usuário do servidor não lê o que o git põe ali
                if (!pasta.ok()) {
                    throw new ErroDoGit(ClassificadorDeErrosDoGit.classificar(pasta.status(), pasta.stderr(), null));
                }
            }
            ExecutorDeGit.Saida s = executor.rodar(ComandosDeGit.criarAreaTemporaria(caminho), PRAZO);
            if (!s.ok()) {
                throw new ErroDoGit(ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), null));
            }
            return area;
        } catch (ErroDoGit | InterruptedException | RuntimeException e) {
            area.close();
            throw e;
        }
    }

    private static byte[] randomico() {
        byte[] b = new byte[8];
        AZAR.nextBytes(b);
        return b;
    }

    /** O caminho da área (para o {@code --git-dir}). */
    public String caminho() {
        return caminho;
    }

    /** Escreve as regras do {@code info/exclude}: o que o app vai acrescentar ao {@code .gitignore}, para a lista de arquivos já refletir o arquivo como ficará. */
    public void escreverExclusoes(List<String> linhas) throws ErroDoGit, InterruptedException {
        String texto = String.join("\n", linhas) + "\n";
        if (local) {
            try {
                Path arquivo = Path.of(caminho, "info", "exclude");
                Files.createDirectories(arquivo.getParent());
                Files.writeString(arquivo, texto, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.SEM_PERMISSAO, "Não consegui escrever na pasta temporária deste computador."));
            }
            return;
        }
        ExecutorDeGit.Saida s = executor.rodar(ComandosDeGit.escreverExclusoes(caminho, texto.getBytes(StandardCharsets.UTF_8)), PRAZO);
        if (!s.ok()) {
            throw new ErroDoGit(ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), null));
        }
    }

    /** Apaga a área. Nunca lança (é o que roda no {@code finally}); se não deu, o resto fica na pasta temporária do sistema, que se limpa sozinha. */
    @Override
    public void close() {
        // com a thread já interrompida (o Cancelar) o executor desistiria na hora e a pasta ficaria: limpa o aviso, apaga e devolve o aviso
        boolean interrompida = Thread.interrupted();
        try {
            if (local) {
                apagarLocal(Path.of(caminho));
            } else {
                executor.rodar(ComandosDeGit.apagarAreaTemporaria(caminho), PRAZO);
            }
        } catch (ErroDoGit | IOException | RuntimeException e) {
            // melhor esforço: a pasta é pequena e fica no temporário do sistema
        } catch (InterruptedException e) {
            interrompida = true;
        } finally {
            if (interrompida) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Só apaga se o nome é exatamente o que o app gera e fica dentro da pasta temporária do sistema. */
    private static void apagarLocal(Path pasta) throws IOException {
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path alvo = pasta.toAbsolutePath().normalize();
        if (!ComandosDeGit.areaTemporariaValida(alvo.toString()) || !alvo.startsWith(temp) || !Files.isDirectory(alvo)) {
            return;
        }
        Files.walkFileTree(alvo, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                f.toFile().setWritable(true);
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
