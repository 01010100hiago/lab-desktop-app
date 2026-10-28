package br.com.labdesktop.local;

import java.util.regex.Pattern;

/**
 * O que se pode escolher no laboratório local: o nome do container do agente, a porta publicada em 127.0.0.1, o nome da rede do Docker, o nome
 * da imagem e a pasta dos projetos. Os padrões são os que o resto do app espera ({@code painel-lab}, {@code edge}, {@code labdesktop-agente});
 * mudá-los existe para TESTAR ao lado de outro laboratório sem tocar nele ({@code labteste-agente}). Todo valor passa por uma regex curta antes
 * de entrar num comando.
 *
 * @param container       nome do container do agente
 * @param porta           a porta do agente em 127.0.0.1 (o Docker a publica só no loopback do computador)
 * @param rede            a rede do Docker dos projetos (criada vazia: os projetos entram nela depois)
 * @param imagemBase      o nome (sem versão) da imagem do agente
 * @param pastaDosProjetos a pasta dos projetos locais no computador (caminho absoluto do sistema)
 */
public record ParametrosDoLaboratorio(String container, int porta, String rede, String imagemBase, String pastaDosProjetos) {

    public static final String CONTAINER_PADRAO = "painel-lab";
    public static final String REDE_PADRAO = "edge";
    public static final String IMAGEM_PADRAO = "labdesktop-agente";

    private static final Pattern NOME = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,40}$");

    public ParametrosDoLaboratorio {
        exigirNome(container, "do container do agente");
        exigirNome(rede, "da rede do Docker");
        exigirNome(imagemBase, "da imagem do agente");
        if (porta < 1024 || porta > 65_535) {
            throw new IllegalArgumentException("A porta do agente vai de 1024 a 65535.");
        }
        if (pastaDosProjetos == null || pastaDosProjetos.isBlank()) {
            throw new IllegalArgumentException("Falta a pasta dos projetos.");
        }
    }

    private static void exigirNome(String valor, String de) {
        if (valor == null || !NOME.matcher(valor).matches()) {
            throw new IllegalArgumentException("O nome " + de + " só pode ter letras minúsculas, números, ponto, sublinhado e hífen (até 41 caracteres).");
        }
    }

    public static ParametrosDoLaboratorio padrao(int porta, String pastaDosProjetos) {
        return new ParametrosDoLaboratorio(CONTAINER_PADRAO, porta, REDE_PADRAO, IMAGEM_PADRAO, pastaDosProjetos);
    }

    /** O volume do Docker onde o agente guarda os dados (token e 2FA). Sobrevive a "Atualizar". */
    public String volume() {
        return container + "-dados";
    }

    /** O nome que o agente antigo leva durante uma atualização (até o novo responder). */
    public String containerAnterior() {
        return container + "-anterior";
    }

    /** A imagem construída para esta versão do pacote. */
    public String imagem(String versao) {
        return imagemBase + ":" + versao;
    }

    /** O mesmo laboratório numa porta (ou pasta) nova. */
    public ParametrosDoLaboratorio comPorta(int nova) {
        return new ParametrosDoLaboratorio(container, nova, rede, imagemBase, pastaDosProjetos);
    }

    public ParametrosDoLaboratorio comPasta(String nova) {
        return new ParametrosDoLaboratorio(container, porta, rede, imagemBase, nova);
    }

    /** Os mesmos dados no formato que o gancho de token do instalador entende (container e porta; a pasta é a do instalador remoto, sem uso aqui). */
    public br.com.labdesktop.instalador.ParametrosDaInstalacao comoDoInstalador() {
        return new br.com.labdesktop.instalador.ParametrosDaInstalacao(container, porta,
            br.com.labdesktop.instalador.ParametrosDaInstalacao.PASTA_PADRAO);
    }
}
