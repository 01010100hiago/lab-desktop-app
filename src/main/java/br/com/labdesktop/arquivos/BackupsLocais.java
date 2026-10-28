package br.com.labdesktop.arquivos;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * As cópias de segurança LOCAIS dos arquivos editados: antes de cada gravação o conteúdo que estava no servidor vai para
 * {@code <pasta>/<servidor>/<data-hora>/<caminho do arquivo>} (no Mac, em {@code ~/Library/Application Support/LabDesktop/
 * backups-arquivos}). Local de propósito: um {@code .bak} no servidor sujaria o git dos projetos.
 *
 * <p>Guarda as 20 mais recentes POR ARQUIVO; as mais antigas somem. As pastas e os arquivos nascem só com o usuário (0700 e 0600):
 * a cópia de um {@code .env} não pode ficar legível para outros usuários do Mac. O caminho de dentro vem de um caminho remoto já
 * normalizado, e mesmo assim confere-se que o destino fica sob a pasta de backups.
 */
public final class BackupsLocais {

    /** Quantas versões se guardam de cada arquivo. */
    public static final int MANTER = 20;

    private static final DateTimeFormatter PASTA_DA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    /** Uma versão guardada. */
    public record Backup(Path arquivo, Instant quando) { }

    private final Path pasta;
    private final String servidor;
    private final Supplier<Instant> relogio;
    private final ZoneId fuso;

    public BackupsLocais(Path pasta, String nomeDoServidor) {
        this(pasta, nomeDoServidor, Instant::now, ZoneId.systemDefault());
    }

    /** Com relógio e fuso trocáveis (testes). */
    public BackupsLocais(Path pasta, String nomeDoServidor, Supplier<Instant> relogio, ZoneId fuso) {
        this.pasta = pasta.toAbsolutePath().normalize();
        String limpo = nomeDoServidor == null ? "" : nomeDoServidor.replaceAll("[^A-Za-z0-9._-]", "_");
        this.servidor = limpo.isBlank() || limpo.startsWith(".") ? "servidor" : limpo;
        this.relogio = relogio;
        this.fuso = fuso;
    }

    /** A pasta deste servidor ({@code .../backups-arquivos/<servidor>}). */
    public Path pastaDoServidor() {
        return pasta.resolve(servidor);
    }

    /** Guarda o conteúdo e apaga as versões que passam das {@link #MANTER}. */
    public Backup guardar(String caminhoRemoto, byte[] conteudo) throws IOException {
        Instant agora = relogio.get();
        String hora = PASTA_DA_HORA.format(LocalDateTime.ofInstant(agora, fuso));
        Path pastaDaHora = pastaDoServidor().resolve(hora);
        Path alvo = resolverDentro(pastaDaHora, caminhoRemoto);
        Files.createDirectories(alvo.getParent(), br.com.labdesktop.util.Permissoes.atributosDePastaPrivada());
        // CREATE_NEW: duas gravações no mesmo milissegundo não se sobrescrevem (a segunda falha e quem chama vê)
        br.com.labdesktop.util.Permissoes.criarArquivoPrivado(alvo);
        Files.write(alvo, conteudo);
        podar(caminhoRemoto);
        return new Backup(alvo, agora);
    }

    /** Todas as versões deste arquivo, da mais nova para a mais antiga. */
    public List<Backup> todos(String caminhoRemoto) throws IOException {
        Path servidorDir = pastaDoServidor();
        if (!Files.isDirectory(servidorDir)) {
            return List.of();
        }
        List<Backup> achados = new ArrayList<>();
        try (DirectoryStream<Path> horas = Files.newDirectoryStream(servidorDir)) {
            for (Path h : horas) {
                if (!Files.isDirectory(h)) {
                    continue;
                }
                Path candidato = resolverDentro(h, caminhoRemoto);
                if (Files.isRegularFile(candidato)) {
                    achados.add(new Backup(candidato, instanteDaPasta(h)));
                }
            }
        }
        achados.sort(Comparator.comparing(Backup::quando).reversed().thenComparing(b -> b.arquivo().toString(), Comparator.reverseOrder()));
        return List.copyOf(achados);
    }

    /** A versão guardada mais recente deste arquivo, se houver. */
    public Optional<Backup> ultimo(String caminhoRemoto) throws IOException {
        return todos(caminhoRemoto).stream().findFirst();
    }

    public byte[] ler(Backup backup) throws IOException {
        Path alvo = backup.arquivo().toAbsolutePath().normalize();
        if (!alvo.startsWith(pasta)) {
            throw new IOException("Backup fora da pasta de backups.");
        }
        return Files.readAllBytes(alvo);
    }

    private void podar(String caminhoRemoto) throws IOException {
        List<Backup> todos = todos(caminhoRemoto);
        for (int i = MANTER; i < todos.size(); i++) {
            Path arquivo = todos.get(i).arquivo();
            Files.deleteIfExists(arquivo);
            // sobe apagando as pastas que ficaram vazias, até a pasta do servidor (que fica)
            Path p = arquivo.getParent();
            while (p != null && !p.equals(pastaDoServidor()) && p.startsWith(pastaDoServidor()) && vazia(p)) {
                Files.deleteIfExists(p);
                p = p.getParent();
            }
        }
    }

    private static boolean vazia(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    /** {@code base/<caminho sem a barra do começo>}, conferindo que não escapou de {@code base}. */
    private Path resolverDentro(Path base, String caminhoRemoto) throws IOException {
        String relativo;
        try {
            relativo = CaminhoRemoto.normalizar(caminhoRemoto).substring(1);
        } catch (ErroSftp e) {
            throw new IOException("Caminho inválido para backup.", e);
        }
        if (relativo.isEmpty()) {
            throw new IOException("A raiz não tem backup.");
        }
        Path alvo = base.resolve(relativo).normalize();
        if (!alvo.startsWith(pasta)) {
            throw new IOException("Backup fora da pasta de backups.");
        }
        return alvo;
    }

    private Instant instanteDaPasta(Path pastaDaHora) {
        try {
            return LocalDateTime.parse(pastaDaHora.getFileName().toString(), PASTA_DA_HORA).atZone(fuso).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
