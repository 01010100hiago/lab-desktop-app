package br.com.labdesktop.plataforma;

import java.util.Map;
import java.util.Objects;

/**
 * Tudo o que o app precisa saber do computador onde roda, num valor só e SEM ler nada na hora de usar: o sistema, a pasta pessoal, o nome do
 * usuário e as variáveis de ambiente que importam. Quem decide algo por sistema (pastas, comandos, atalhos, tema) recebe um destes, e os
 * testes montam um falso ({@link #de}) para provar o Windows e o Linux num Mac (ou o contrário), sem depender do sistema onde rodam.
 *
 * <p>Os caminhos aqui são TEXTO do sistema que o ambiente representa ({@code C:\Users\ana} ou {@code /Users/ana}), nunca {@code Path}: um
 * {@code Path} do Mac não sabe o que é uma letra de disco.
 *
 * @param casa      a pasta pessoal ({@code user.home})
 * @param usuario   o nome do usuário ({@code user.name}); vazio se desconhecido
 * @param variaveis as variáveis de ambiente (só as que importam são lidas: {@code APPDATA}, {@code LOCALAPPDATA}, {@code SystemRoot},
 *                  {@code XDG_*}...)
 */
public record AmbienteDoSistema(SistemaOperacional sistema, String casa, String usuario, Map<String, String> variaveis) {

    public AmbienteDoSistema {
        Objects.requireNonNull(sistema, "sistema");
        casa = casa == null ? "" : casa;
        usuario = usuario == null ? "" : usuario;
        variaveis = variaveis == null ? Map.of() : Map.copyOf(variaveis);
    }

    /** O deste computador, lido agora. */
    public static AmbienteDoSistema detectar() {
        return new AmbienteDoSistema(SistemaOperacional.detectar(), System.getProperty("user.home"), System.getProperty("user.name"),
            System.getenv());
    }

    /** Um ambiente à mão (testes e quem já tem os valores). */
    public static AmbienteDoSistema de(SistemaOperacional sistema, String casa, String usuario, Map<String, String> variaveis) {
        return new AmbienteDoSistema(sistema, casa, usuario, variaveis);
    }

    /** Um Windows de mentira com a pasta pessoal {@code C:\Users\<usuario>} e as variáveis que o Windows define de verdade. */
    public static AmbienteDoSistema windowsPadrao(String usuario) {
        String casa = "C:\\Users\\" + usuario;
        return new AmbienteDoSistema(SistemaOperacional.WINDOWS, casa, usuario, Map.of(
            "APPDATA", casa + "\\AppData\\Roaming",
            "LOCALAPPDATA", casa + "\\AppData\\Local",
            "SystemRoot", "C:\\Windows",
            "ProgramFiles", "C:\\Program Files",
            "USERNAME", usuario,
            "COMPUTERNAME", "PC-" + usuario.toUpperCase(java.util.Locale.ROOT)));
    }

    /** O valor da variável, sem espaços nas pontas; {@code null} se não existe ou está vazia. */
    public String variavel(String nome) {
        String v = variaveis.get(nome);
        if (v == null || v.isBlank()) {
            return null;
        }
        return v.strip();
    }

    /** {@code \} no Windows, {@code /} nos outros. */
    public String separador() {
        return sistema == SistemaOperacional.WINDOWS ? "\\" : "/";
    }

    /** Junta pedaços com o separador do sistema do ambiente (texto puro; não olha o sistema onde o código roda). */
    public String juntar(String base, String... partes) {
        StringBuilder sb = new StringBuilder(base);
        String sep = separador();
        for (String p : partes) {
            if (sb.length() > 0 && !sb.substring(sb.length() - 1).equals(sep)) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** O caminho é absoluto NO sistema do ambiente (letra de disco ou UNC no Windows; barra inicial nos outros). */
    public boolean ehAbsoluto(String caminho) {
        if (caminho == null || caminho.isBlank()) {
            return false;
        }
        if (sistema == SistemaOperacional.WINDOWS) {
            return caminho.matches("^[A-Za-z]:[\\\\/].*") || caminho.startsWith("\\\\");
        }
        return caminho.startsWith("/");
    }
}
