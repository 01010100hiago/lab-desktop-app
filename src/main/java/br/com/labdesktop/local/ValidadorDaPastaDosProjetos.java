package br.com.labdesktop.local;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * Confere a pasta dos projetos ANTES de criá-la ou de montá-la no container: o container do agente recebe essa pasta com permissão de escrita,
 * então ela nunca pode ser a raiz do disco, a pasta pessoal inteira nem uma pasta do sistema. Regra neutra de sistema, só sobre o texto (nada de
 * disco), testada para Mac, Linux e Windows num Mac.
 */
public final class ValidadorDaPastaDosProjetos {

    /** No Mac e no Linux: nem a pasta nem o que está dentro dela. */
    private static final List<String> SISTEMA_UNIX = List.of("/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/boot", "/dev", "/proc", "/sys",
        "/system", "/library", "/applications", "/private/etc");
    /** No Mac e no Linux: só a própria pasta (o que está dentro é do usuário: {@code /var/folders}, {@code /opt/meus}). */
    private static final Set<String> SO_ELA_UNIX = Set.of("/var", "/private", "/private/var", "/opt", "/tmp", "/volumes", "/mnt", "/media", "/srv", "/home",
        "/users", "/root", "/run", "/snap");
    private static final List<String> SISTEMA_WINDOWS = List.of("windows", "program files", "program files (x86)", "programdata");
    private static final Set<String> SO_ELA_WINDOWS = Set.of("users", "usuários", "usuarios");

    private ValidadorDaPastaDosProjetos() { }

    /**
     * @param sistema o sistema deste computador
     * @param casa    a pasta pessoal ({@code /Users/ana} ou {@code C:\Users\ana})
     * @param pasta   a pasta escolhida
     * @return a frase do problema, ou vazio se serve
     */
    public static Optional<String> problema(SistemaOperacional sistema, String casa, String pasta) {
        if (pasta == null || pasta.isBlank()) {
            return Optional.of("Falta a pasta dos projetos.");
        }
        if (pasta.chars().anyMatch(Character::isISOControl)) {
            return Optional.of("A pasta dos projetos tem um caractere inválido.");
        }
        return sistema == SistemaOperacional.WINDOWS ? windows(casa, pasta.strip()) : unix(casa, pasta.strip());
    }

    private static Optional<String> unix(String casa, String pasta) {
        if (!pasta.startsWith("/")) {
            return Optional.of("A pasta dos projetos deve ser um caminho completo (começando por /), não relativo.");
        }
        List<String> partes = partes(pasta, '/');
        if (partes.contains("..") || partes.contains(".")) {
            return Optional.of("A pasta dos projetos não pode ter . nem .. no caminho: escreva o caminho completo.");
        }
        String normal = "/" + String.join("/", partes);
        String baixo = normal.toLowerCase(Locale.ROOT);
        if (partes.isEmpty()) {
            return Optional.of("A pasta dos projetos não pode ser a raiz do disco (/): o container do agente escreveria nela. Use uma pasta sua, como LabLocal/projects.");
        }
        if (eIgualOuAncestralDaCasa(normal, casa == null ? "" : casa.replace('\\', '/'))) {
            return Optional.of("A pasta dos projetos não pode ser a sua pasta pessoal inteira (nem uma acima dela): use uma pasta dentro dela, como LabLocal/projects.");
        }
        for (String s : SISTEMA_UNIX) {
            if (baixo.equals(s) || baixo.startsWith(s + "/")) {
                return Optional.of("A pasta dos projetos não pode ser uma pasta do sistema (" + normal + "). Use uma pasta sua, como LabLocal/projects.");
            }
        }
        if (SO_ELA_UNIX.contains(baixo)) {
            return Optional.of("A pasta dos projetos não pode ser " + normal + " (é uma pasta do sistema que guarda tudo). Use uma pasta dentro da sua, como LabLocal/projects.");
        }
        return Optional.empty();
    }

    private static Optional<String> windows(String casa, String pasta) {
        String p = pasta.replace('\\', '/');
        if (p.length() < 3 || !Character.isLetter(p.charAt(0)) || p.charAt(1) != ':' || p.charAt(2) != '/') {
            return Optional.of("A pasta dos projetos deve ser um caminho completo com a letra do disco (por exemplo C:\\Users\\voce\\LabLocal\\projects).");
        }
        List<String> partes = partes(p.substring(3), '/');
        if (partes.contains("..") || partes.contains(".")) {
            return Optional.of("A pasta dos projetos não pode ter . nem .. no caminho: escreva o caminho completo.");
        }
        if (partes.isEmpty()) {
            return Optional.of("A pasta dos projetos não pode ser a raiz do disco (" + Character.toUpperCase(p.charAt(0)) + ":\\): o container do agente escreveria nela.");
        }
        String normal = Character.toUpperCase(p.charAt(0)) + ":/" + String.join("/", partes);
        String c = casa == null ? "" : casa.replace('\\', '/');
        if (!c.isEmpty() && eIgualOuAncestralDaCasa(normal.toLowerCase(Locale.ROOT), c.toLowerCase(Locale.ROOT).replaceAll("/+$", ""))) {
            return Optional.of("A pasta dos projetos não pode ser a sua pasta pessoal inteira (nem uma acima dela): use uma pasta dentro dela, como LabLocal\\projects.");
        }
        String primeira = partes.get(0).toLowerCase(Locale.ROOT);
        if (SISTEMA_WINDOWS.contains(primeira)) {
            return Optional.of("A pasta dos projetos não pode ficar numa pasta do sistema (" + partes.get(0) + "). Use uma pasta sua, como LabLocal\\projects.");
        }
        if (partes.size() == 1 && SO_ELA_WINDOWS.contains(primeira)) {
            return Optional.of("A pasta dos projetos não pode ser a pasta Users (guarda a de todos os usuários). Use uma pasta dentro da sua.");
        }
        return Optional.empty();
    }

    private static List<String> partes(String caminho, char sep) {
        return java.util.Arrays.stream(caminho.split(String.valueOf(sep))).filter(s -> !s.isEmpty()).toList();
    }

    /** {@code pasta} é a pasta pessoal ou uma pasta que a contém (por exemplo {@code /Users}). */
    private static boolean eIgualOuAncestralDaCasa(String pasta, String casa) {
        String c = casa.replaceAll("/+$", "");
        if (c.isEmpty()) {
            return false;
        }
        String p = pasta.replaceAll("/+$", "");
        // o Mac e o Windows não distinguem maiúsculas de minúsculas no disco; no Linux a comparação exata também vale e a outra só endurece
        String cb = c.toLowerCase(Locale.ROOT);
        String pb = p.toLowerCase(Locale.ROOT);
        return cb.equals(pb) || cb.startsWith(pb + "/");
    }
}
