package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A escolha do gerenciador de pacotes pelo os-release (lido como texto) e pelo que existe no servidor. */
class GerenciadorDePacotesTest {

    private static String os(String id, String idLike) {
        return "PRETTY_NAME=\"Sistema de Teste\"\nNAME=\"Teste\"\nID=" + id + (idLike == null ? "" : "\nID_LIKE=\"" + idLike + "\"") + "\n";
    }

    @Test
    void cadaDistribuicaoEscolheOSeuGerenciador() {
        Set<String> todos = Set.of("apt-get", "dnf", "yum", "apk", "pacman", "zypper");
        assertEquals(Optional.of(GerenciadorDePacotes.APT_GET), GerenciadorDePacotes.escolher(os("debian", null), todos));
        assertEquals(Optional.of(GerenciadorDePacotes.APT_GET), GerenciadorDePacotes.escolher(os("ubuntu", "debian"), todos));
        assertEquals(Optional.of(GerenciadorDePacotes.APT_GET), GerenciadorDePacotes.escolher(os("linuxmint", "ubuntu debian"), todos));
        assertEquals(Optional.of(GerenciadorDePacotes.DNF), GerenciadorDePacotes.escolher(os("fedora", null), todos));
        assertEquals(Optional.of(GerenciadorDePacotes.DNF), GerenciadorDePacotes.escolher(os("rocky", "rhel centos fedora"), todos));
        assertEquals(Optional.of(GerenciadorDePacotes.APK), GerenciadorDePacotes.escolher(os("alpine", null), todos));
        assertEquals(Optional.of(GerenciadorDePacotes.PACMAN), GerenciadorDePacotes.escolher(os("arch", null), todos));
        assertEquals(Optional.of(GerenciadorDePacotes.PACMAN), GerenciadorDePacotes.escolher(os("manjaro", "arch"), todos));
        assertEquals(Optional.of(GerenciadorDePacotes.ZYPPER), GerenciadorDePacotes.escolher(os("opensuse-leap", "suse opensuse"), todos));
    }

    @Test
    void centosAntigoSoComYumUsaYum() {
        assertEquals(Optional.of(GerenciadorDePacotes.YUM), GerenciadorDePacotes.escolher(os("centos", "rhel fedora"), Set.of("yum")));
    }

    @Test
    void seAPreferidaNaoExisteUsaAPrimeiraQueExiste() {
        assertEquals(Optional.of(GerenciadorDePacotes.APK), GerenciadorDePacotes.escolher(os("debian", null), Set.of("apk")), "debian sem apt-get: o que houver");
        assertEquals(Optional.of(GerenciadorDePacotes.DNF), GerenciadorDePacotes.escolher(os("desconhecida", null), Set.of("zypper", "dnf")), "ordem fixa: apt-get, dnf, yum, apk, pacman, zypper");
    }

    @Test
    void nenhumGerenciadorNaoEscolheNada() {
        assertEquals(Optional.empty(), GerenciadorDePacotes.escolher(os("debian", null), Set.of()));
        assertEquals(Optional.empty(), GerenciadorDePacotes.escolher("", Set.of("brew")), "um programa fora dos seis nunca entra");
        assertEquals(Optional.empty(), GerenciadorDePacotes.escolher(null, Set.of()));
    }

    @Test
    void oOsReleaseSoEscolheEntreSeisEMaisNadaVaiParaAComando() {
        // um os-release hostil não consegue pôr texto num comando: só escolhe entre as seis opções fixas
        String hostil = "ID=\"debian; rm -rf /\"\nID_LIKE=\"$(id)\"\nPRETTY_NAME=\"x\u0007y\"\n";
        Optional<GerenciadorDePacotes> g = GerenciadorDePacotes.escolher(hostil, Set.of("apt-get"));
        assertEquals(Optional.of(GerenciadorDePacotes.APT_GET), g);
        for (List<String> c : g.orElseThrow().comandosDeInstalar()) {
            c.forEach(p -> assertTrue(p.matches("^[A-Za-z0-9_@%+=:.,/~-]+$"), p));
        }
        assertEquals("xy", GerenciadorDePacotes.nomeDoSistema(hostil), "o caractere de controle sai do nome mostrado");
    }

    @Test
    void osComandosDeInstalarSaoOsEsperados() {
        assertEquals(List.of(List.of("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "update"), List.of("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", "git")),
            GerenciadorDePacotes.APT_GET.comandosDeInstalar());
        assertEquals(List.of(List.of("dnf", "install", "-y", "git")), GerenciadorDePacotes.DNF.comandosDeInstalar());
        assertEquals(List.of(List.of("yum", "install", "-y", "git")), GerenciadorDePacotes.YUM.comandosDeInstalar());
        assertEquals(List.of(List.of("apk", "add", "git")), GerenciadorDePacotes.APK.comandosDeInstalar());
        assertEquals(List.of(List.of("pacman", "-S", "--noconfirm", "git")), GerenciadorDePacotes.PACMAN.comandosDeInstalar());
        assertEquals(List.of(List.of("zypper", "--non-interactive", "install", "git")), GerenciadorDePacotes.ZYPPER.comandosDeInstalar());
    }

    @Test
    void ProgramaPorNome() {
        assertEquals(Optional.of(GerenciadorDePacotes.APT_GET), GerenciadorDePacotes.dePrograma("apt-get"));
        assertEquals(Optional.empty(), GerenciadorDePacotes.dePrograma("brew"));
    }
}
