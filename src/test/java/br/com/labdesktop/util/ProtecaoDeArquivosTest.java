package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "Só o dono" nos três tipos de sistema de arquivos: POSIX de verdade, ACL do NTFS com uma vista FALSA (nenhum Windows), e sem nada. */
class ProtecaoDeArquivosTest {

    private final ProtecaoDeArquivos original = Permissoes.atual();

    @AfterEach
    void volta() {
        Permissoes.trocar(original);
    }

    // ---- ACL (a vista é falsa: grava o que o app mandar) ----

    record Usuario(String nome) implements UserPrincipal {
        @Override
        public String getName() {
            return nome;
        }
    }

    static final class AclFalsa implements AclFileAttributeView {
        final UserPrincipal dono;
        final List<AclEntry> herdadas = new ArrayList<>();
        List<AclEntry> gravada;
        int gravacoes;

        AclFalsa(String dono) {
            this.dono = new Usuario(dono);
        }

        @Override
        public String name() {
            return "acl";
        }

        @Override
        public List<AclEntry> getAcl() {
            List<AclEntry> l = new ArrayList<>(gravada == null ? List.of() : gravada);
            l.addAll(herdadas);
            return l;
        }

        @Override
        public void setAcl(List<AclEntry> acl) {
            gravada = List.copyOf(acl);
            gravacoes++;
        }

        @Override
        public UserPrincipal getOwner() {
            return dono;
        }

        @Override
        public void setOwner(UserPrincipal owner) {
            throw new UnsupportedOperationException();
        }
    }

    private static AclEntry permitir(UserPrincipal quem) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(quem).setPermissions(EnumSet.of(AclEntryPermission.READ_DATA)).build();
    }

    @Test
    void naAclOArquivoFicaComUmaEntradaSoDoDonoComAcessoTotalSemHeranca() throws IOException {
        AclFalsa v = new AclFalsa("PC\\ana");
        new ProtecaoAcl(p -> v).restringirAoDono(Path.of("cofre.labcofre"), false);
        assertEquals(1, v.gravada.size());
        AclEntry e = v.gravada.get(0);
        assertEquals(AclEntryType.ALLOW, e.type());
        assertEquals(v.dono, e.principal());
        assertEquals(EnumSet.allOf(AclEntryPermission.class), e.permissions());
        assertEquals(Set.of(), e.flags(), "arquivo não herda nada para baixo");
    }

    @Test
    void aEntradaEDoUsuarioQueRodaOAppEnaoDoDonoDoArquivo() throws IOException {
        // pasta criada por um instalador/administrador: o dono do arquivo é o grupo, mas quem precisa de acesso é o usuário atual
        AclFalsa v = new AclFalsa("BUILTIN\\Administradores");
        Usuario ana = new Usuario("PC\\ana");
        new ProtecaoAcl(p -> v, p -> ana).restringirAoDono(Path.of("LabDesktop"), true);
        assertEquals(List.of(ana), v.gravada.stream().map(AclEntry::principal).toList());
    }

    @Test
    void usuarioQueRodaOAppVemDoNomeDoUsuarioDoSistema(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        UserPrincipal achado = ProtecaoAcl.usuarioQueRodaOApp(tmp);
        assertEquals(java.nio.file.Files.getOwner(tmp), achado, "numa pasta que o próprio usuário criou, o usuário atual é o dono");
    }

    @Test
    void naAclAPastaPassaOAcessoSoDoDonoParaOQueNascerDentro() throws IOException {
        AclFalsa v = new AclFalsa("PC\\ana");
        new ProtecaoAcl(p -> v).restringirAoDono(Path.of("LabDesktop"), true);
        assertEquals(EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT), v.gravada.get(0).flags());
    }

    @Test
    void aclSubstituiOQueJaEstavaEIdempotente() throws IOException {
        AclFalsa v = new AclFalsa("PC\\ana");
        v.gravada = List.of(permitir(new Usuario("Todos")), permitir(new Usuario("Usuários")));
        ProtecaoAcl acl = new ProtecaoAcl(p -> v);
        acl.restringirAoDono(Path.of("x"), false);
        acl.restringirAoDono(Path.of("x"), false);
        assertEquals(List.of(v.dono), v.gravada.stream().map(AclEntry::principal).toList(), "'Todos' e 'Usuários' saíram");
        assertEquals(2, v.gravacoes);
    }

    @Test
    void entradasHerdadasQueSobramSaoVistasMasNaoViramFalha() throws IOException {
        AclFalsa v = new AclFalsa("PC\\ana");
        v.herdadas.add(permitir(new Usuario("NT AUTHORITY\\SISTEMA")));
        v.herdadas.add(permitir(new Usuario("BUILTIN\\Administradores")));
        ProtecaoAcl acl = new ProtecaoAcl(p -> v);
        acl.restringirAoDono(Path.of("x"), true); // não lança
        assertEquals(List.of("NT AUTHORITY\\SISTEMA", "BUILTIN\\Administradores"), ProtecaoAcl.quemMaisTemAcesso(v.getAcl(), v.dono));
        assertFalse(acl.corrigirSeAberta(Path.of("x"), true), "no NTFS o app refaz a ACL e não alarma o usuário");
        assertEquals("dono e mais 2", acl.descrever(Path.of("x")));
    }

    @Test
    void semVistaDeAclDizComClarezaEmVezDeLancarNullPointer() {
        IOException e = assertThrows(IOException.class, () -> new ProtecaoAcl(p -> null).restringirAoDono(Path.of("x"), false));
        assertTrue(e.getMessage().contains("lista de acesso"), e.getMessage());
        assertEquals(0, new ProtecaoAcl(p -> null).atributosDeArquivo().length, "a criação no NTFS não leva atributo de permissão");
    }

    // ---- POSIX e a porta única (Permissoes) ----

    @Test
    void posixDeVerdadeDeixa0600E0700EConfereECorrige(@TempDir Path tmp) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "permissões POSIX");
        Permissoes.trocar(new ProtecaoPosix());
        Path pasta = tmp.resolve("a").resolve("b");
        Permissoes.criarPastaPrivada(pasta);
        assertEquals("rwx------", Permissoes.comoTexto(pasta));
        assertEquals("rwx------", Permissoes.comoTexto(pasta.getParent()), "as pastas que faltavam também");
        Path arq = pasta.resolve("c");
        Permissoes.criarArquivoPrivado(arq);
        assertEquals("rw-------", Permissoes.comoTexto(arq));
        Files.setPosixFilePermissions(arq, PosixFilePermissions.fromString("rw-r--r--"));
        assertTrue(Permissoes.corrigirArquivoSeAberto(arq), "estava aberto e foi corrigido");
        assertFalse(Permissoes.corrigirArquivoSeAberto(arq));
        Path canalado = pasta.resolve("d");
        try (FileChannel c = Permissoes.abrirCanalPrivado(canalado, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            assertTrue(c.isOpen());
        }
        assertEquals("rw-------", Permissoes.comoTexto(canalado));
    }

    @Test
    void naAclAPortaUnicaRestringeOArquivoNovoEATravaDeUmArquivoQueJaExisteNaoMexe(@TempDir Path tmp) throws IOException {
        // a vista falsa serve a qualquer caminho: a proteção de ACL age sobre um arquivo comum do disco do teste
        AclFalsa v = new AclFalsa("PC\\ana");
        Permissoes.trocar(new ProtecaoAcl(p -> v));
        Path novo = tmp.resolve("cofre.lock");
        try (FileChannel c = Permissoes.abrirCanalPrivado(novo, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE))) {
            assertTrue(c.isOpen());
        }
        assertEquals(1, v.gravacoes, "arquivo novo: a ACL do dono foi gravada logo depois de criar");
        try (FileChannel c = Permissoes.abrirCanalPrivado(novo, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE))) {
            assertTrue(c.isOpen());
        }
        assertEquals(1, v.gravacoes, "reabrir o que já existe não refaz a ACL");
        Path pasta = tmp.resolve("dados");
        Permissoes.criarPastaPrivada(pasta);
        assertTrue(Files.isDirectory(pasta));
        assertTrue(v.gravacoes >= 3, "a pasta recebeu a ACL (ao criar e ao conferir)");
    }

    @Test
    void aclQueFalhaNaCriacaoNaoDerrubaOApp(@TempDir Path tmp) throws IOException {
        Permissoes.trocar(new ProtecaoAcl(p -> {
            throw new UnsupportedOperationException("sem acl");
        }));
        Path arq = tmp.resolve("x");
        try (FileChannel c = Permissoes.abrirCanalPrivado(arq, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            assertTrue(c.isOpen(), "o arquivo foi criado mesmo assim (a ACL é uma segunda trava; o cofre é cifrado)");
        }
        assertTrue(Files.exists(arq));
    }

    @Test
    void semNadaNaoFalhaENaoDizPermissao(@TempDir Path tmp) throws IOException {
        Permissoes.trocar(new ProtecaoNenhuma());
        Path pasta = tmp.resolve("p");
        Permissoes.criarPastaPrivada(pasta);
        Permissoes.criarArquivoPrivado(pasta.resolve("a"));
        assertEquals("?", Permissoes.comoTexto(pasta));
        assertFalse(Permissoes.corrigirPastaSeAberta(pasta));
        assertArrayEquals(new Object[0], Permissoes.atributosDeArquivoPrivado());
    }

    @Test
    void aEscolhaDoSistemaSegueACapacidadeDoSistemaDeArquivos() {
        var vistas = FileSystems.getDefault().supportedFileAttributeViews();
        ProtecaoDeArquivos p = ProtecaoDeArquivos.doSistema();
        if (vistas.contains("posix")) {
            assertInstanceOf(ProtecaoPosix.class, p);
        } else if (vistas.contains("acl")) {
            assertInstanceOf(ProtecaoAcl.class, p);
        } else {
            assertInstanceOf(ProtecaoNenhuma.class, p);
        }
    }
}
