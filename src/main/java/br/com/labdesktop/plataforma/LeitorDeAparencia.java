package br.com.labdesktop.plataforma;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Se o sistema está no modo ESCURO, para o "Seguir o sistema" do tema (ARQUITETURA 8.2). Um leitor por sistema, todos com UM comando FIXO (lista de argumentos, nada digitado) e
 * tolerantes: sem resposta, o tema é o claro.
 *
 * <ul>
 *   <li><b>macOS:</b> {@code defaults read -g AppleInterfaceStyle}: {@code Dark} com status 0 no escuro; no claro a chave não existe e o status é 1.</li>
 *   <li><b>Windows:</b> {@code reg.exe query HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize /v AppsUseLightTheme}: o valor {@code 0x0} é o tema escuro dos aplicativos,
 *       {@code 0x1} o claro; sem a chave (Windows antigo) é claro. Escolhido no lugar do FlatLaf (3.7 não detecta o tema do sistema) e de uma biblioteca nativa: um processo de
 *       sistema, testável com resposta falsa, sem dependência nova. O caminho é o {@code reg.exe} da pasta do sistema ({@code SystemRoot}), não o do {@code PATH}.</li>
 *   <li><b>Linux:</b> {@code gsettings get org.gnome.desktop.interface color-scheme} ({@code 'prefer-dark'}); sem ele, o nome do tema GTK ({@code gtk-theme}) com "dark". Sem GNOME, claro.</li>
 * </ul>
 */
public final class LeitorDeAparencia {

    static final Duration PRAZO = Duration.ofSeconds(2);
    static final String CHAVE_DO_WINDOWS = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final Pattern VALOR_DO_WINDOWS = Pattern.compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)");

    private LeitorDeAparencia() { }

    /** {@code true} se o sistema está no escuro; {@code false} no claro ou se não foi possível saber. */
    public static boolean escuro(AmbienteDoSistema ambiente, ConsultaDeSistema consulta) {
        return switch (ambiente.sistema()) {
            case MAC -> doMac(consulta);
            case WINDOWS -> doWindows(ambiente, consulta);
            case LINUX -> doLinux(consulta);
        };
    }

    // ---- comandos (fixos; visíveis para o teste conferir) ----

    public static List<String> comandoDoMac() {
        return List.of("defaults", "read", "-g", "AppleInterfaceStyle");
    }

    /** O {@code reg.exe} da pasta do sistema. {@code SystemRoot} vem do ambiente do Windows; sem ele vale {@code C:\Windows}. */
    public static List<String> comandoDoWindows(AmbienteDoSistema ambiente) {
        String raiz = ambiente.variavel("SystemRoot");
        String pasta = raiz != null && ambiente.ehAbsoluto(raiz) ? raiz : "C:\\Windows";
        return List.of(ambiente.juntar(pasta, "System32", "reg.exe"), "query", CHAVE_DO_WINDOWS, "/v", "AppsUseLightTheme");
    }

    public static List<String> comandoDoLinuxEsquema() {
        return List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
    }

    public static List<String> comandoDoLinuxTema() {
        return List.of("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
    }

    // ---- regras ----

    private static boolean doMac(ConsultaDeSistema consulta) {
        ConsultaDeSistema.Resposta r = consulta.perguntar(comandoDoMac(), PRAZO);
        return interpretarMac(r.status(), r.saida());
    }

    /** Escuro só com status 0 e a palavra {@code Dark}. */
    public static boolean interpretarMac(int status, String saida) {
        return status == 0 && saida != null && saida.strip().equalsIgnoreCase("Dark");
    }

    private static boolean doWindows(AmbienteDoSistema ambiente, ConsultaDeSistema consulta) {
        ConsultaDeSistema.Resposta r = consulta.perguntar(comandoDoWindows(ambiente), PRAZO);
        return interpretarWindows(r.status(), r.saida());
    }

    /** {@code AppsUseLightTheme REG_DWORD 0x0} é escuro; qualquer outra coisa (0x1, sem a chave, erro, lixo) é claro. */
    public static boolean interpretarWindows(int status, String saida) {
        if (status != 0 || saida == null) {
            return false;
        }
        Matcher m = VALOR_DO_WINDOWS.matcher(saida);
        if (!m.find()) {
            return false;
        }
        try {
            return Long.parseLong(m.group(1), 16) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean doLinux(ConsultaDeSistema consulta) {
        ConsultaDeSistema.Resposta esquema = consulta.perguntar(comandoDoLinuxEsquema(), PRAZO);
        if (esquema.ok()) {
            String v = esquema.saida().strip().toLowerCase(Locale.ROOT);
            if (v.contains("prefer-dark")) {
                return true;
            }
            if (v.contains("prefer-light") || v.contains("default")) {
                return false; // o GNOME respondeu: vale a resposta dele
            }
        }
        ConsultaDeSistema.Resposta tema = consulta.perguntar(comandoDoLinuxTema(), PRAZO);
        return tema.ok() && tema.saida().toLowerCase(Locale.ROOT).contains("dark");
    }
}
