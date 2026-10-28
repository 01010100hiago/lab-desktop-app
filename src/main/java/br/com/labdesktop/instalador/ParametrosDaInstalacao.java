package br.com.labdesktop.instalador;

import java.util.regex.Pattern;

/**
 * O que se pode escolher numa instalação do agente: o nome do container, a porta (a mesma em que o app entra pelo túnel) e o nome
 * da pasta do instalador. Os padrões ({@code painel-lab}, 7000 e {@code lab-agente}) são o que o resto do app espera; mudá-los existe
 * só para TESTAR num servidor que já tem o agente do lab (por exemplo {@code painel-teste} na porta 7100) sem tocar nele.
 *
 * <p>Todo valor passa por uma regex curta ANTES de entrar numa linha de comando: nome de container e de pasta só com minúsculas,
 * dígitos, ponto, sublinhado e hífen, e porta numérica.
 *
 * @param container nome do container do agente
 * @param porta     porta do agente em 127.0.0.1 do servidor
 * @param pasta     nome da pasta (dentro da pasta pessoal) onde o instalador guarda o pacote e a fonte do agente
 */
public record ParametrosDaInstalacao(String container, int porta, String pasta) {

    public static final String CONTAINER_PADRAO = "painel-lab";
    public static final int PORTA_PADRAO = 7000;
    public static final String PASTA_PADRAO = "lab-agente";

    private static final Pattern NOME = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,40}$");

    public ParametrosDaInstalacao {
        if (container == null || !NOME.matcher(container).matches()) {
            throw new IllegalArgumentException("O nome do container do agente só pode ter letras minúsculas, números, ponto, "
                + "sublinhado e hífen (até 41 caracteres).");
        }
        if (pasta == null || !NOME.matcher(pasta).matches()) {
            throw new IllegalArgumentException("O nome da pasta do agente só pode ter letras minúsculas, números, ponto, "
                + "sublinhado e hífen (até 41 caracteres).");
        }
        if (porta < 1 || porta > 65_535) {
            throw new IllegalArgumentException("A porta do agente vai de 1 a 65535.");
        }
    }

    /** Os padrões, na porta pedida (a do servidor no cofre). */
    public static ParametrosDaInstalacao padrao(int porta) {
        return new ParametrosDaInstalacao(CONTAINER_PADRAO, porta, PASTA_PADRAO);
    }

    /** O volume do Docker onde o agente guarda os dados (token e 2FA). Sobrevive a "Atualizar agente". */
    public String volume() {
        return container + "-dados";
    }

    /** A imagem construída no servidor para esta versão do pacote. */
    public String imagem(String versao) {
        return "labdesktop-agente:" + versao;
    }

    /** O agente é o do lab de produção? Só para o app avisar: o instalador nunca mexe num container que não é dele. */
    public boolean ehOPadrao() {
        return CONTAINER_PADRAO.equals(container);
    }
}
