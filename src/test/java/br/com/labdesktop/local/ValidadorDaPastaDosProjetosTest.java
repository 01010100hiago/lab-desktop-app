package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A pasta dos projetos que o container recebe com escrita: nunca a raiz, a pasta pessoal nem as do sistema; a regra vale nos três sistemas. */
class ValidadorDaPastaDosProjetosTest {

    private static Optional<String> mac(String pasta) {
        return ValidadorDaPastaDosProjetos.problema(SistemaOperacional.MAC, "/Users/ana", pasta);
    }

    private static Optional<String> linux(String pasta) {
        return ValidadorDaPastaDosProjetos.problema(SistemaOperacional.LINUX, "/home/ana", pasta);
    }

    private static Optional<String> win(String pasta) {
        return ValidadorDaPastaDosProjetos.problema(SistemaOperacional.WINDOWS, "C:\\Users\\ana", pasta);
    }

    @Test
    void asPastasBoasPassamNosTresSistemas() {
        assertEquals(Optional.empty(), mac("/Users/ana/LabLocal/projects"));
        assertEquals(Optional.empty(), mac("/Users/ana/Meus Projetos/órbita"));
        assertEquals(Optional.empty(), mac("/Volumes/Disco Externo/projetos"));
        assertEquals(Optional.empty(), mac("/var/folders/ab/xyz/T/junit123/LabLocal/projects"), "o que está dentro de /var é do usuário");
        assertEquals(Optional.empty(), linux("/home/ana/LabLocal/projects"));
        assertEquals(Optional.empty(), linux("/srv/lab/projetos"));
        assertEquals(Optional.empty(), win("C:\\Users\\ana\\LabLocal\\projects"));
        assertEquals(Optional.empty(), win("D:/Projetos/lab"));
        assertEquals(Optional.empty(), win("C:\\Users\\João Silva\\LabLocal\\projects"), "outro usuário/acento: o caminho em si serve");
    }

    @Test
    void relativaVaziaEComPontoPontoSaoRecusadas() {
        for (String ruim : new String[] {"", "  ", "projetos", "./projetos", "../projetos", "LabLocal/projects"}) {
            assertTrue(mac(ruim).isPresent(), "aceitou: '" + ruim + "'");
            assertTrue(linux(ruim).isPresent());
        }
        assertTrue(mac("/Users/ana/../x").get().contains(".."));
        assertTrue(mac("/Users/ana/./x").isPresent());
        assertTrue(win("projetos").isPresent());
        assertTrue(win("\\Users\\ana").isPresent(), "sem a letra do disco");
        assertTrue(win("C:\\Users\\ana\\..\\x").isPresent());
        assertTrue(mac("/Users/ana/x\u0000y").isPresent(), "caractere de controle");
        assertTrue(mac(null).isPresent());
    }

    @Test
    void aRaizDoDiscoNaoServe() {
        assertTrue(mac("/").get().contains("raiz do disco"));
        assertTrue(mac("///").isPresent());
        assertTrue(linux("/").isPresent());
        assertTrue(win("C:\\").get().contains("raiz do disco"));
        assertTrue(win("c:/").isPresent());
    }

    @Test
    void aPastaPessoalInteiraEAsQueAContemNaoServem() {
        assertTrue(mac("/Users/ana").get().contains("pasta pessoal"));
        assertTrue(mac("/Users/ana/").isPresent());
        assertTrue(mac("/users/ANA").isPresent(), "o disco do Mac não distingue maiúsculas");
        assertTrue(mac("/Users").isPresent(), "uma pasta que contém a pessoal");
        assertTrue(linux("/home/ana").isPresent());
        assertTrue(linux("/home").isPresent());
        assertTrue(win("C:\\Users\\ana").get().contains("pasta pessoal"));
        assertTrue(win("c:/users/ANA").isPresent());
        assertTrue(win("C:\\Users").isPresent());
    }

    @Test
    void asPastasDoSistemaNaoServem() {
        for (String p : new String[] {"/etc", "/etc/lab", "/usr/local", "/bin", "/System/Library", "/Library/Foo", "/Applications", "/dev", "/boot", "/var",
            "/private", "/opt", "/tmp"}) {
            assertTrue(mac(p).isPresent(), "aceitou: " + p);
        }
        for (String p : new String[] {"C:\\Windows", "C:\\Windows\\System32\\x", "C:\\Program Files\\Lab", "C:\\Program Files (x86)", "C:\\ProgramData\\x"}) {
            assertTrue(win(p).isPresent(), "aceitou: " + p);
        }
    }
}
