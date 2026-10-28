package br.com.labdesktop.processos;

import java.util.Locale;
import java.util.Set;

/**
 * Onde os processos moram, para as regras de proteção e de permissão.
 *
 * @param tipo                    um servidor Linux por SSH, este computador (Mac ou Linux) ou este computador (Windows)
 * @param usuarioDoApp            o usuário com que o app age ({@code usuario} do SSH, ou o usuário do computador); sem ele NINGUÉM é "do mesmo usuário"
 * @param containersProtegidos    containers que o app nunca para nem reinicia por esta tela: o agente do painel (por ele o app fala com o servidor)
 * @param pidsDoApp               só em "Este computador": o processo do próprio app e os que o abriram
 */
public record AmbienteDeProcessos(Tipo tipo, String usuarioDoApp, Set<String> containersProtegidos, Set<Integer> pidsDoApp) {

    /** Os containers do agente que o app instala ou que o lab usa: o nome padrão do agente. */
    public static final String CONTAINER_DO_AGENTE = "painel-lab";

    public enum Tipo {
        SERVIDOR_LINUX,
        COMPUTADOR_MAC_OU_LINUX,
        COMPUTADOR_WINDOWS
    }

    public AmbienteDeProcessos {
        usuarioDoApp = usuarioDoApp == null ? "" : usuarioDoApp.strip();
        Set<String> c = new java.util.LinkedHashSet<>();
        c.add(CONTAINER_DO_AGENTE);
        if (containersProtegidos != null) {
            for (String n : containersProtegidos) {
                if (n != null && !n.isBlank()) {
                    c.add(n.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        containersProtegidos = Set.copyOf(c);
        pidsDoApp = pidsDoApp == null ? Set.of() : Set.copyOf(pidsDoApp);
    }

    public static AmbienteDeProcessos servidor(String usuarioSsh, Set<String> containersProtegidos) {
        return new AmbienteDeProcessos(Tipo.SERVIDOR_LINUX, usuarioSsh, containersProtegidos, Set.of());
    }

    /** O usuário da lista é o do app? Compara só o nome, sem o domínio do Windows ({@code PC\ana} vale {@code ana}), sem diferenciar maiúsculas no Windows. */
    public boolean ehDoMesmoUsuario(String usuarioDaLista) {
        if (usuarioDoApp.isBlank() || usuarioDaLista == null || usuarioDaLista.isBlank()) {
            return false;
        }
        String a = semDominio(usuarioDoApp);
        String b = semDominio(usuarioDaLista);
        return tipo == Tipo.COMPUTADOR_WINDOWS ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    public boolean usuarioDoAppEhAdministrador() {
        String u = semDominio(usuarioDoApp);
        return "root".equals(u) || tipo == Tipo.COMPUTADOR_WINDOWS && "system".equalsIgnoreCase(u);
    }

    private static String semDominio(String u) {
        String t = u.strip();
        int i = t.lastIndexOf('\\');
        return i >= 0 ? t.substring(i + 1) : t;
    }
}
