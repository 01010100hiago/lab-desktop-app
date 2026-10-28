package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.BackupsLocais.Backup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** As cópias de segurança locais: onde ficam, quem lê, quantas sobram. */
class BackupsLocaisTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void guardaEmServidorDataHoraECaminhoDoArquivo(@TempDir Path tmp) throws IOException {
        BackupsLocais backups = new BackupsLocais(tmp, "lab", () -> Instant.parse("2026-10-24T21:05:09.123Z"), SP);
        Backup bk = backups.guardar("/srv/lab/projects/orbita/.env", b("SEGREDO=1"));
        assertEquals(tmp.resolve("lab/2026-10-24_18-05-09-123/srv/lab/projects/orbita/.env"), bk.arquivo());
        assertEquals("SEGREDO=1", Files.readString(bk.arquivo()));
        assertEquals(Instant.parse("2026-10-24T21:05:09.123Z"), bk.quando());
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void cadaCopiaSoPodeSerLidaPeloDono(@TempDir Path tmp) throws IOException {
        BackupsLocais backups = new BackupsLocais(tmp, "lab");
        Backup bk = backups.guardar("/srv/x/.env", b("segredo"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(bk.arquivo())));
        Path p = bk.arquivo().getParent();
        while (!p.equals(tmp)) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p)), p.toString());
            p = p.getParent();
        }
    }

    @Test
    void maisRecenteVemPrimeiroEUltimoEhOMaisNovo(@TempDir Path tmp) throws IOException {
        AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-10-24T10:00:00Z"));
        BackupsLocais backups = new BackupsLocais(tmp, "lab", agora::get, SP);
        for (int i = 1; i <= 3; i++) {
            backups.guardar("/a/b.txt", b("v" + i));
            agora.set(agora.get().plusSeconds(60));
        }
        backups.guardar("/a/outro.txt", b("outro"));
        List<Backup> todos = backups.todos("/a/b.txt");
        assertEquals(3, todos.size());
        assertEquals("v3", new String(backups.ler(todos.get(0)), StandardCharsets.UTF_8));
        assertEquals("v1", new String(backups.ler(todos.get(2)), StandardCharsets.UTF_8));
        assertEquals("v3", new String(backups.ler(backups.ultimo("/a/b.txt").orElseThrow()), StandardCharsets.UTF_8));
        assertEquals(1, backups.todos("/a/outro.txt").size());
        assertTrue(backups.ultimo("/nao/guardado").isEmpty());
    }

    @Test
    void ficamAsVinteMaisRecentesPorArquivoESoDele(@TempDir Path tmp) throws IOException {
        AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-10-24T10:00:00Z"));
        BackupsLocais backups = new BackupsLocais(tmp, "lab", agora::get, SP);
        backups.guardar("/a/vizinho.txt", b("vizinho"));
        for (int i = 1; i <= 25; i++) {
            agora.set(agora.get().plusSeconds(1));
            backups.guardar("/a/b.txt", b("v" + i));
        }
        List<Backup> todos = backups.todos("/a/b.txt");
        assertEquals(BackupsLocais.MANTER, todos.size());
        assertEquals(20, todos.size());
        assertEquals("v25", new String(backups.ler(todos.get(0)), StandardCharsets.UTF_8));
        assertEquals("v6", new String(backups.ler(todos.get(19)), StandardCharsets.UTF_8), "as 5 mais antigas sumiram");
        assertEquals(1, backups.todos("/a/vizinho.txt").size(), "a poda de um arquivo não toca nos outros");
        try (var s = Files.list(backups.pastaDoServidor())) {
            assertEquals(21, s.count(), "as pastas de data que ficaram vazias foram embora");
        }
    }

    @Test
    void doisBackupsNoMesmoInstanteNaoSeSobrescrevem(@TempDir Path tmp) throws IOException {
        BackupsLocais backups = new BackupsLocais(tmp, "lab", () -> Instant.parse("2026-10-24T10:00:00Z"), SP);
        backups.guardar("/a/b.txt", b("um"));
        assertThrows(IOException.class, () -> backups.guardar("/a/b.txt", b("dois")), "falha em vez de sobrescrever em silêncio");
        assertEquals("um", new String(backups.ler(backups.ultimo("/a/b.txt").orElseThrow()), StandardCharsets.UTF_8));
    }

    @Test
    void nomeDeServidorEstranhoViraNomeDePastaSeguro(@TempDir Path tmp) throws IOException {
        BackupsLocais backups = new BackupsLocais(tmp, "../../etc/lab prod");
        Backup bk = backups.guardar("/a.txt", b("x"));
        assertTrue(bk.arquivo().startsWith(tmp), bk.arquivo().toString());
        assertEquals("servidor", backups.pastaDoServidor().getFileName().toString(), "nome que começa com ponto vira o padrão");
        assertEquals("lab_prod", new BackupsLocais(tmp, "lab prod").pastaDoServidor().getFileName().toString());
        assertEquals("servidor", new BackupsLocais(tmp, "").pastaDoServidor().getFileName().toString());
    }

    @Test
    void caminhoInvalidoNaoVaiParaDisco(@TempDir Path tmp) {
        BackupsLocais backups = new BackupsLocais(tmp, "lab");
        assertThrows(IOException.class, () -> backups.guardar("relativo/x", b("x")));
        assertThrows(IOException.class, () -> backups.guardar("/", b("x")));
        assertThrows(IOException.class, () -> backups.guardar("/a\nb", b("x")));
        assertFalse(Files.exists(tmp.resolve("lab")));
    }

    @Test
    void doisPontosNoCaminhoNaoEscapamDaPastaDeBackups(@TempDir Path tmp) throws IOException {
        BackupsLocais backups = new BackupsLocais(tmp.resolve("bk"), "lab");
        Backup bk = backups.guardar("/a/../../../fora.txt", b("x")); // normaliza para /fora.txt
        assertTrue(bk.arquivo().startsWith(tmp.resolve("bk")), bk.arquivo().toString());
        assertArrayEquals(b("x"), backups.ler(bk));
        assertThrows(IOException.class, () -> backups.ler(new Backup(tmp.resolve("fora-da-pasta.txt"), Instant.now())));
    }
}
