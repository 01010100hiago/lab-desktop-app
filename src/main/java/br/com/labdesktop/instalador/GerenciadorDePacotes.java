package br.com.labdesktop.instalador;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * O gerenciador de pacotes de um servidor Linux e o jeito de instalar o git com ele. Modelo puro: dado o {@code /etc/os-release} (lido em MODO TEXTO, nunca executado) e quais
 * gerenciadores existem no servidor, escolhe um. A ordem de preferência segue a distribuição ({@code ID} e {@code ID_LIKE}); se a preferida não existe, vale o primeiro que existe, na
 * ordem apt-get, dnf, yum, apk, pacman, zypper.
 */
public enum GerenciadorDePacotes {

    APT_GET("apt-get", "Debian, Ubuntu e derivados"),
    DNF("dnf", "Fedora, RHEL, Rocky e AlmaLinux"),
    YUM("yum", "CentOS e RHEL antigos"),
    APK("apk", "Alpine"),
    PACMAN("pacman", "Arch e Manjaro"),
    ZYPPER("zypper", "openSUSE e SUSE");

    private final String programa;
    private final String familia;

    GerenciadorDePacotes(String programa, String familia) {
        this.programa = programa;
        this.familia = familia;
    }

    /** O nome do programa ({@code apt-get}). */
    public String programa() {
        return programa;
    }

    /** As distribuições em que ele é o comum, para a tela dizer. */
    public String familia() {
        return familia;
    }

    /** O gerenciador com este nome de programa, ou vazio. */
    public static Optional<GerenciadorDePacotes> dePrograma(String nome) {
        for (GerenciadorDePacotes g : values()) {
            if (g.programa.equals(nome)) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }

    /** As linhas de comando que instalam o git, SEM o prefixo de permissão (cada uma é uma lista de palavras seguras, sem aspas). */
    public List<List<String>> comandosDeInstalar() {
        return switch (this) {
            case APT_GET -> List.of(List.of("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "update"),
                List.of("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", "git"));
            case DNF -> List.of(List.of("dnf", "install", "-y", "git"));
            case YUM -> List.of(List.of("yum", "install", "-y", "git"));
            case APK -> List.of(List.of("apk", "add", "git"));
            case PACMAN -> List.of(List.of("pacman", "-S", "--noconfirm", "git"));
            case ZYPPER -> List.of(List.of("zypper", "--non-interactive", "install", "git"));
        };
    }

    private static final Set<String> DEBIAN = Set.of("debian", "ubuntu", "linuxmint", "raspbian", "pop", "kali", "devuan", "elementary", "zorin");
    private static final Set<String> RED_HAT = Set.of("fedora", "rhel", "centos", "rocky", "almalinux", "ol", "amzn", "scientific");
    private static final Set<String> ARCH = Set.of("arch", "manjaro", "endeavouros", "garuda");
    private static final Set<String> SUSE = Set.of("opensuse", "opensuse-leap", "opensuse-tumbleweed", "sles", "sled", "suse");

    /** A distribuição que o {@code /etc/os-release} diz ({@code ID} e as palavras do {@code ID_LIKE}), em minúsculas. */
    public static List<String> distribuicoesDe(String osRelease) {
        List<String> l = new java.util.ArrayList<>();
        String id = valor(osRelease, "ID");
        if (!id.isEmpty()) {
            l.add(id);
        }
        for (String p : valor(osRelease, "ID_LIKE").split("\\s+")) {
            if (!p.isEmpty()) {
                l.add(p);
            }
        }
        return l;
    }

    /** O nome bonito do sistema ({@code PRETTY_NAME}), limpo, ou {@code ""}. */
    public static String nomeDoSistema(String osRelease) {
        String v = valor(osRelease, "PRETTY_NAME");
        String limpo = br.com.labdesktop.seguranca.TextoSeguro.truncar(br.com.labdesktop.seguranca.TextoSeguro.limparLinha(v), 80);
        return limpo == null ? "" : limpo;
    }

    /** O valor de uma chave do {@code os-release} (texto; aspas tiradas; nunca executa nada). */
    static String valor(String osRelease, String chave) {
        if (osRelease == null) {
            return "";
        }
        for (String linha : osRelease.split("\n")) {
            String t = linha.strip();
            if (t.startsWith(chave + "=")) {
                String v = t.substring(chave.length() + 1).strip();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                return chave.equals("ID") || chave.equals("ID_LIKE") ? v.toLowerCase(Locale.ROOT) : v;
            }
        }
        return "";
    }

    /**
     * O gerenciador a usar.
     *
     * @param osRelease   o texto do {@code /etc/os-release}
     * @param disponiveis os programas que existem no servidor ({@code apt-get}, {@code dnf}...)
     * @return vazio se nenhum dos seis existe
     */
    public static Optional<GerenciadorDePacotes> escolher(String osRelease, Set<String> disponiveis) {
        for (String d : distribuicoesDe(osRelease)) {
            List<GerenciadorDePacotes> preferidos = DEBIAN.contains(d) ? List.of(APT_GET)
                : RED_HAT.contains(d) ? List.of(DNF, YUM)
                : d.equals("alpine") ? List.of(APK)
                : ARCH.contains(d) ? List.of(PACMAN)
                : SUSE.contains(d) || d.startsWith("opensuse") ? List.of(ZYPPER)
                : List.of();
            for (GerenciadorDePacotes g : preferidos) {
                if (disponiveis.contains(g.programa)) {
                    return Optional.of(g);
                }
            }
        }
        for (GerenciadorDePacotes g : values()) {
            if (disponiveis.contains(g.programa)) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }
}
