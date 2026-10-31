package br.com.labdesktop.arquivos;

import br.com.labdesktop.arquivos.EntradaRemota.Tipo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * O {@link ClienteSftp} de "Este computador": o sistema de arquivos do próprio computador, por {@code java.nio}, atrás da MESMA interface do SFTP.
 * A cerca ({@code ServicoDeArquivos}: raízes, links, intocáveis), a edição com cópia de segurança e a fila de transferências valem iguais.
 *
 * <p>Mesmas regras do cliente do MINA: nome de entrada com barra, NUL, {@code .} ou {@code ..} é descartado; nunca segue link ao apagar; gravar
 * é atômico (temporário na mesma pasta e troca por cima); arquivo de outro dono não é trocado (o novo seria do usuário do app).
 */
public final class ClienteSftpLocal implements ClienteSftp {

    static final int TETO_DA_CONTAGEM = 100_000;
    private static final int BLOCO = 64 * 1024;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final CaminhosLocais caminhos;
    private final String casa;
    private volatile boolean fechado;

    /** @param casa a pasta pessoal do usuário (o que {@code caminhoCanonico(".")} devolve), no texto do app */
    public ClienteSftpLocal(CaminhosLocais caminhos, String casa) {
        this.caminhos = caminhos;
        this.casa = casa;
    }

    /** O de produção: a pasta pessoal do usuário. */
    public static ClienteSftpLocal doSistema() {
        CaminhosLocais c = CaminhosLocais.doSistema();
        return new ClienteSftpLocal(c, c.doTextoDoSistema(System.getProperty("user.home")));
    }

    // ---------------------------------------------------------------------------------------------

    private Path p(String caminho) throws ErroSftp {
        if (fechado) {
            throw new ErroSftp(ErroSftp.Tipo.SEM_CONEXAO, "A tela de arquivos foi fechada.");
        }
        if (caminho == null || caminho.isEmpty() || !CaminhoRemoto.semControle(caminho) || caminho.charAt(0) != '/') {
            throw ErroSftp.nomeInvalido("O caminho tem caracteres que não posso usar.");
        }
        if (caminhos.windows() && caminho.indexOf('\\') >= 0) {
            // no Windows a barra invertida separa pastas: "a\..\..\fora" seria UM nome para a cerca do gerenciador (que só conhece a barra comum) e saltaria para fora dela
            throw ErroSftp.nomeInvalido("O caminho tem caracteres que não posso usar.");
        }
        try {
            Path r = caminhos.path(caminho);
            if (r == null) {
                throw ErroSftp.nomeInvalido("Escolha um disco.");
            }
            return r;
        } catch (IllegalArgumentException e) {
            throw ErroSftp.nomeInvalido("O caminho não vale neste sistema.");
        }
    }

    private String texto(Path p) {
        return caminhos.texto(p);
    }

    /** O alvo de um link no texto do app (no Windows, {@code ..\\x} vira {@code ../x} e {@code D:\\x} vira {@code /D:/x}); {@code null} se não dá para dizer. */
    private String alvoDoLink(Path link, String destinoDoSistema) {
        Path pasta = link.getParent();
        return caminhos.alvoDoLink(pasta == null ? null : texto(pasta), destinoDoSistema);
    }

    // ---------------------------------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------------------------------

    @Override
    public ListagemDePasta listar(String caminho, int limite, Predicate<String> nomeAceito) throws ErroSftp {
        if (fechado) {
            throw new ErroSftp(ErroSftp.Tipo.SEM_CONEXAO, "A tela de arquivos foi fechada.");
        }
        int teto = Math.max(1, limite);
        List<EntradaRemota> guardadas = new ArrayList<>(Math.min(teto, 512));
        int total = 0;
        boolean exato = true;
        try {
            if (caminhos.windows() && "/".equals(caminho)) {
                for (Path raiz : FileSystems.getDefault().getRootDirectories()) {
                    String nome = raiz.toString().replace("\\", "");
                    if (nomeAceito.test(nome)) {
                        total++;
                        guardadas.add(new EntradaRemota(nome, "/" + nome, Tipo.PASTA, false, -1, null, -1, "", "", null));
                    }
                }
                return new ListagemDePasta(caminho, guardadas, total, false, true, Instant.now());
            }
            Path pasta = p(caminho);
            try (DirectoryStream<Path> fluxo = Files.newDirectoryStream(pasta)) {
                for (Path filho : fluxo) {
                    String nome = filho.getFileName().toString();
                    if (nome.isEmpty() || nome.equals(".") || nome.equals("..") || nome.indexOf('/') >= 0 || nome.indexOf('\0') >= 0
                        || !nomeAceito.test(nome)) {
                        continue;
                    }
                    total++;
                    if (guardadas.size() < teto) {
                        guardadas.add(entradaDe(filho, true));
                    } else if (total >= TETO_DA_CONTAGEM) {
                        exato = false;
                        break;
                    }
                }
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
            return new ListagemDePasta(caminho, guardadas, total, total > guardadas.size(), exato, Instant.now());
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "abrir esta pasta");
        }
    }

    /** Um item como a tela o vê: sem seguir o link (um link é um link), com o tipo do destino se for link. */
    private EntradaRemota entradaDe(Path caminho, boolean resolverLink) throws IOException {
        BasicFileAttributes a = Files.readAttributes(caminho, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        boolean link = a.isSymbolicLink();
        Tipo tipo = a.isDirectory() ? Tipo.PASTA : a.isRegularFile() ? Tipo.ARQUIVO : link ? Tipo.ARQUIVO : Tipo.OUTRO;
        long tamanho = a.isRegularFile() ? a.size() : -1;
        String destino = null;
        if (link && resolverLink) {
            try {
                destino = alvoDoLink(caminho, Files.readSymbolicLink(caminho).toString());
            } catch (IOException | RuntimeException e) {
                destino = null;
            }
            try {
                BasicFileAttributes alvo = Files.readAttributes(caminho, BasicFileAttributes.class);
                tipo = alvo.isDirectory() ? Tipo.PASTA : alvo.isRegularFile() ? Tipo.ARQUIVO : Tipo.OUTRO;
                tamanho = alvo.isRegularFile() ? alvo.size() : tamanho;
            } catch (NoSuchFileException | java.nio.file.FileSystemLoopException e) {
                tipo = Tipo.LINK_QUEBRADO;
            } catch (IOException e) {
                tipo = Tipo.OUTRO;
            }
        }
        int permissoes = -1;
        String dono = "";
        String grupo = "";
        PosixFileAttributeView posix = Files.getFileAttributeView(caminho, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            try {
                PosixFileAttributes pa = posix.readAttributes();
                permissoes = bitsDe(pa.permissions());
                dono = pa.owner().getName();
                grupo = pa.group().getName();
            } catch (IOException | RuntimeException e) {
                // sem dono nem permissões: a tela mostra vazio
            }
        }
        Path nomeFinal = caminho.getFileName();
        return new EntradaRemota(nomeFinal == null ? caminho.toString() : nomeFinal.toString(), texto(caminho), tipo, link, tamanho,
            a.lastModifiedTime().toInstant(), permissoes, dono, grupo, destino);
    }

    static int bitsDe(Set<PosixFilePermission> permissoes) {
        int bits = 0;
        for (PosixFilePermission p : permissoes) {
            bits |= switch (p) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 040;
                case GROUP_WRITE -> 020;
                case GROUP_EXECUTE -> 010;
                case OTHERS_READ -> 04;
                case OTHERS_WRITE -> 02;
                case OTHERS_EXECUTE -> 01;
            };
        }
        return bits;
    }

    static Set<PosixFilePermission> permissoesDe(int bits) {
        Set<PosixFilePermission> s = java.util.EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] ordem = {PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE};
        for (int i = 0; i < 9; i++) {
            if ((bits & (0400 >> i)) != 0) {
                s.add(ordem[i]);
            }
        }
        return s;
    }

    @Override
    public EntradaRemota estatisticas(String caminho) throws ErroSftp {
        try {
            return entradaDe(p(caminho), false);
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "ver este item");
        }
    }

    @Override
    public boolean existe(String caminho) throws ErroSftp {
        if (caminhos.windows() && "/".equals(caminho)) {
            return true;
        }
        return Files.exists(p(caminho), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public byte[] lerArquivo(String caminho, int limite) throws ErroSftp {
        Path arq = p(caminho);
        try (InputStream in = Files.newInputStream(arq)) {
            return in.readNBytes(Math.max(0, limite));
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "abrir este arquivo");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Escrita
    // ---------------------------------------------------------------------------------------------

    @Override
    public void gravarAtomico(String caminho, byte[] conteudo, int permissoes) throws ErroSftp {
        Path alvo = p(caminho);
        Path pasta = alvo.getParent();
        Path tmp = temporarioAo(alvo, "grava");
        try {
            int bits = permissoes;
            if (Files.exists(alvo, LinkOption.NOFOLLOW_LINKS)) {
                if (donoDiferente(alvo)) {
                    throw new ErroSftp(ErroSftp.Tipo.OUTRO_DONO, "Este arquivo é de outro usuário do computador: salvar por cima trocaria o dono do arquivo. "
                        + "Salve uma cópia, ou mude o dono do arquivo.");
                }
                if (bits < 0) {
                    bits = bitsExistentes(alvo);
                }
            }
            if (pasta != null && !Files.isWritable(pasta)) {
                throw ErroSftp.semPermissao("gravar nesta pasta");
            }
            criarPrivado(tmp); // já nasce 0600: o conteúdo nunca fica legível por outros durante a gravação
            Files.write(tmp, conteudo, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            if (bits >= 0 && Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(tmp, permissoesDe(bits));
            }
            trocar(tmp, alvo);
        } catch (IOException | RuntimeException e) {
            apagarQuieto(tmp);
            throw ErroSftp.de(e, "gravar este arquivo");
        }
    }

    @Override
    public void gravarNoLugar(String caminho, byte[] conteudo) throws ErroSftp {
        Path alvo = p(caminho);
        try {
            if (!Files.exists(alvo)) {
                throw ErroSftp.naoExiste(alvo.getFileName() == null ? caminho : alvo.getFileName().toString());
            }
            if (Files.isDirectory(alvo)) {
                throw new ErroSftp(ErroSftp.Tipo.OUTRO, "\"" + alvo.getFileName() + "\" é uma pasta, não um arquivo.");
            }
            // sem CREATE: só escreve no que já existe; TRUNCATE_EXISTING zera o conteúdo mantendo o arquivo (dono, permissões, i-node)
            Files.write(alvo, conteudo, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "gravar este arquivo");
        }
    }

    private static void trocar(Path de, Path para) throws IOException {
        try {
            Files.move(de, para, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(de, para, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean donoDiferente(Path alvo) {
        try {
            if (Files.getFileAttributeView(alvo, PosixFileAttributeView.class) == null) {
                return false;
            }
            String dono = Files.getOwner(alvo, LinkOption.NOFOLLOW_LINKS).getName();
            return !dono.equals(System.getProperty("user.name"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static int bitsExistentes(Path alvo) {
        try {
            return bitsDe(Files.getPosixFilePermissions(alvo));
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    @Override
    public void enviar(Path local, String remoto, Progresso progresso, Cancelamento cancelamento) throws ErroSftp {
        copiar(local, p(remoto), progresso, cancelamento, "gravar este arquivo");
    }

    @Override
    public void baixar(String remoto, Path local, Progresso progresso, Cancelamento cancelamento) throws ErroSftp {
        copiar(p(remoto), local, progresso, cancelamento, "ler este arquivo");
    }

    /** Copia por um temporário ao lado do destino, confere o tamanho e só então o põe no lugar; cancelar apaga o temporário. */
    private void copiar(Path origem, Path destino, Progresso progresso, Cancelamento cancelamento, String oQue) throws ErroSftp {
        Path tmp = temporarioAo(destino, "copia");
        try {
            long total = Files.size(origem);
            AtomicLong feitos = new AtomicLong();
            criarPrivado(tmp);
            try (InputStream in = Files.newInputStream(origem); OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] b = new byte[BLOCO];
                int n;
                while ((n = in.read(b)) != -1) {
                    cancelamento.conferir();
                    out.write(b, 0, n);
                    progresso.andamento(feitos.addAndGet(n), total);
                }
            }
            if (Files.size(tmp) != total) {
                throw new ErroSftp(ErroSftp.Tipo.TAMANHO_NAO_CONFERE, "O tamanho do arquivo copiado não confere com o original.");
            }
            trocar(tmp, destino);
        } catch (IOException | RuntimeException e) {
            apagarQuieto(tmp);
            throw ErroSftp.de(e, oQue);
        }
    }

    @Override
    public void criarPasta(String caminho) throws ErroSftp {
        try {
            Files.createDirectory(p(caminho));
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "criar esta pasta");
        }
    }

    @Override
    public void criarArquivoVazio(String caminho) throws ErroSftp {
        try {
            Files.createFile(p(caminho));
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "criar este arquivo");
        }
    }

    @Override
    public void renomear(String de, String para) throws ErroSftp {
        Path origem = p(de);
        Path destino = p(para);
        try {
            if (Files.exists(destino, LinkOption.NOFOLLOW_LINKS)) {
                throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe um item com este nome.");
            }
            Files.move(origem, destino);
        } catch (FileAlreadyExistsException e) {
            throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe um item com este nome.", e);
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "renomear este item");
        }
    }

    @Override
    public void apagar(String caminho, boolean recursivo) throws ErroSftp {
        Path alvo = p(caminho);
        try {
            BasicFileAttributes a = Files.readAttributes(alvo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isDirectory() || ehLigacao(a)) {
                Files.delete(alvo); // arquivo, link ou junção: apaga o item, nunca o destino
                return;
            }
            if (!recursivo) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(alvo)) {
                    if (s.iterator().hasNext()) {
                        throw new ErroSftp(ErroSftp.Tipo.PASTA_NAO_VAZIA, "A pasta não está vazia.");
                    }
                }
                Files.delete(alvo);
                return;
            }
            Files.walkFileTree(alvo, visitanteDeApagar(alvo));
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "apagar este item");
        }
    }

    /**
     * Um link ou uma JUNÇÃO do Windows (ponto de reparse): o Java a vê como pasta comum ({@code isDirectory}) mas {@code isOther}, e um
     * {@code walkFileTree} desceria por ela e apagaria o conteúdo do DESTINO. Quem apaga trata a junção como um item só.
     */
    static boolean ehLigacao(BasicFileAttributes a) {
        return a.isSymbolicLink() || a.isOther();
    }

    /** O visitante do "apagar tudo": apaga a pasta e o que ela tem, mas uma junção ou link é apagado SEM descer por ela. Visível para teste. */
    static SimpleFileVisitor<Path> visitanteDeApagar(Path raiz) {
        return new SimpleFileVisitor<>() { // por padrão NÃO segue links
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes at) throws IOException {
                if (!d.equals(raiz) && ehLigacao(at)) {
                    Files.delete(d); // só a junção; o conteúdo do destino fica
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes at) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        };
    }

    @Override
    public ResumoDeTamanho resumir(String caminho, Cancelamento cancelamento) throws ErroSftp {
        Path raiz = p(caminho);
        long[] c = {0, 0, 0};
        boolean[] completo = {true};
        try {
            Files.walkFileTree(raiz, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                    cancelamentoConferir(cancelamento);
                    if (!d.equals(raiz)) {
                        c[1]++;
                    }
                    return conta(c, completo);
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    cancelamentoConferir(cancelamento);
                    c[0]++;
                    c[2] += a.isRegularFile() ? a.size() : 0;
                    return conta(c, completo);
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (ErroSftpNaVisita e) {
            throw e.erro;
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "ver o conteúdo desta pasta");
        }
        return new ResumoDeTamanho(c[0], c[1], c[2], completo[0]);
    }

    private static FileVisitResult conta(long[] c, boolean[] completo) {
        if (c[0] + c[1] >= TETO_DA_CONTAGEM) {
            completo[0] = false;
            return FileVisitResult.TERMINATE;
        }
        return FileVisitResult.CONTINUE;
    }

    private static final class ErroSftpNaVisita extends IOException {
        private static final long serialVersionUID = 1L;
        final transient ErroSftp erro;

        ErroSftpNaVisita(ErroSftp erro) {
            super(erro);
            this.erro = erro;
        }
    }

    private static void cancelamentoConferir(Cancelamento c) throws IOException {
        try {
            c.conferir();
        } catch (ErroSftp e) {
            throw new ErroSftpNaVisita(e);
        }
    }

    @Override
    public String lerLink(String caminho) throws ErroSftp {
        try {
            Path link = p(caminho);
            String alvo = alvoDoLink(link, Files.readSymbolicLink(link).toString());
            if (alvo == null) {
                throw new ErroSftp(ErroSftp.Tipo.OUTRO, "Não consegui dizer para onde este link aponta.");
            }
            return alvo;
        } catch (ErroSftp e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw ErroSftp.de(e, "ler este link");
        }
    }

    @Override
    public String caminhoCanonico(String caminho) throws ErroSftp {
        if (".".equals(caminho)) {
            return casa;
        }
        return CaminhoRemoto.normalizar(caminho);
    }

    @Override
    public void close() {
        fechado = true;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Cria o arquivo temporário JÁ com permissão 0600 (onde há POSIX), em vez de criá-lo com a permissão padrão e trocar depois: não há janela em
     * que outro usuário do computador o leria. No Windows (sem POSIX) é um arquivo comum. Falha se já existe.
     */
    static void criarPrivado(Path tmp) throws IOException {
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tmp);
        }
    }

    private static Path temporarioAo(Path alvo, String marca) {
        byte[] b = new byte[5];
        ALEATORIO.nextBytes(b);
        String nomeFinal = alvo.getFileName() == null ? "raiz" : alvo.getFileName().toString();
        String base = nomeFinal.length() > 60 ? nomeFinal.substring(0, 60) : nomeFinal;
        String nome = "." + base + ".labdesktop-" + marca + "-" + HexFormat.of().formatHex(b) + ".tmp";
        Path pasta = alvo.getParent();
        return pasta == null ? Path.of(nome) : pasta.resolve(nome);
    }

    private static void apagarQuieto(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            // o temporário fica; é só um arquivo de ponto na pasta
        }
    }
}
