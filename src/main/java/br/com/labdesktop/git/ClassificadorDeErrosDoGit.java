package br.com.labdesktop.git;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforma o que o git (ou o shell que o chamou) disse numa {@link FalhaDoGit} com uma frase em português simples. Só LÊ texto: o comando que sugere (por exemplo o
 * {@code safe.directory}) é mostrado ao usuário como texto copiável e NUNCA é executado pelo app. A saída do git vem com {@code LC_ALL=C}, então as frases em inglês são sempre as mesmas.
 */
public final class ClassificadorDeErrosDoGit {

    /** A versão mínima do git que o app usa ({@code git init -b} existe desde a 2.28). */
    public static final int[] VERSAO_MINIMA = {2, 28};

    private static final Pattern NAO_ACHADO = Pattern.compile("(?i)(git: (command )?not found|git: no such file or directory|env: .?git.?: no such file or directory|'git' is not recognized|command not found: git)");
    private static final Pattern PROPRIEDADE = Pattern.compile("(?i)(dubious ownership|safe\\.directory)");
    private static final Pattern PASTA_DA_PROPRIEDADE = Pattern.compile("(?i)dubious ownership in repository at '([^'\\n]+)'");
    private static final Pattern NAO_E_REPO = Pattern.compile("(?i)not a git repository");
    private static final Pattern SEM_COMMITS = Pattern.compile("(?i)(does not have any commits yet|bad default revision 'HEAD'|ambiguous argument 'HEAD'|your current branch '[^']*' does not have any commits)");
    private static final Pattern SEM_IDENTIDADE = Pattern.compile("(?i)(please tell me who you are|unable to auto-detect email address|empty ident name|fatal: no email was given)");
    private static final Pattern PERMISSAO = Pattern.compile("(?i)(permission denied|operation not permitted|read-only file system|could not lock|unable to create '[^']*': permission)");
    private static final Pattern VERSAO = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private ClassificadorDeErrosDoGit() { }

    /**
     * @param status o código de saída
     * @param stderr o que o programa escreveu no erro
     * @param pasta  a pasta do projeto (para o comando do {@code safe.directory}); pode ser {@code null}
     */
    public static FalhaDoGit classificar(int status, String stderr, String pasta) {
        String e = stderr == null ? "" : stderr;
        if (NAO_ACHADO.matcher(e).find() || (status == 127 && e.toLowerCase(Locale.ROOT).contains("git"))) {
            return naoInstalado();
        }
        if (PROPRIEDADE.matcher(e).find()) {
            Matcher m = PASTA_DA_PROPRIEDADE.matcher(e);
            String p = m.find() && textoSeguroDePasta(m.group(1)) ? m.group(1) : pasta;
            return propriedadeDuvidosa(p);
        }
        if (SEM_IDENTIDADE.matcher(e).find()) {
            return new FalhaDoGit(FalhaDoGit.Tipo.SEM_IDENTIDADE, "O Git ainda não sabe quem assina os commits (falta nome e e-mail).");
        }
        if (SEM_COMMITS.matcher(e).find()) {
            return new FalhaDoGit(FalhaDoGit.Tipo.SEM_COMMITS, "Este repositório ainda não tem nenhum commit.");
        }
        if (NAO_E_REPO.matcher(e).find()) {
            return new FalhaDoGit(FalhaDoGit.Tipo.NAO_E_REPOSITORIO, "Esta pasta ainda não é um repositório Git.");
        }
        if (PERMISSAO.matcher(e).find()) {
            return new FalhaDoGit(FalhaDoGit.Tipo.SEM_PERMISSAO, "O usuário do app não tem permissão para ler ou escrever nesta pasta. O Git disse: " + resumo(e));
        }
        return new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "O Git respondeu com erro" + (status > 0 ? " (código " + status + ")" : "") + ": " + resumo(e));
    }

    /** O git não existe nesta máquina (o programa nem foi achado). */
    public static FalhaDoGit naoInstalado() {
        return new FalhaDoGit(FalhaDoGit.Tipo.GIT_NAO_INSTALADO, "O Git não está instalado nesta máquina.");
    }

    /** A pasta é de outro dono e o git recusa: a frase e o comando exato, para o USUÁRIO rodar (o app nunca o executa). */
    public static FalhaDoGit propriedadeDuvidosa(String pasta) {
        String comando = pasta == null || pasta.isBlank() ? null : "git config --global --add safe.directory " + paraOShell(pasta);
        return new FalhaDoGit(FalhaDoGit.Tipo.PROPRIEDADE_DUVIDOSA,
            "O Git recusou esta pasta porque ela é de outro usuário (uma proteção do próprio Git). Se você confia nela, rode o comando abaixo no Terminal do servidor, com o usuário que o app usa. O app não faz isso sozinho.",
            comando);
    }

    /** A versão do git ({@code git version 2.43.0}) como números {@code [maior, menor]}; vazio se não deu para ler. */
    public static int[] versao(String texto) {
        if (texto == null) {
            return new int[0];
        }
        Matcher m = VERSAO.matcher(texto);
        if (!m.find()) {
            return new int[0];
        }
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** {@code true} se a versão lida é menor que {@link #VERSAO_MINIMA}; versão ilegível não reprova (o git diz o erro). */
    public static boolean antigoDemais(String textoDaVersao) {
        int[] v = versao(textoDaVersao);
        if (v.length < 2) {
            return false;
        }
        return v[0] < VERSAO_MINIMA[0] || (v[0] == VERSAO_MINIMA[0] && v[1] < VERSAO_MINIMA[1]);
    }

    public static FalhaDoGit antigo(String textoDaVersao) {
        return new FalhaDoGit(FalhaDoGit.Tipo.GIT_ANTIGO, "O Git desta máquina é antigo (" + TextoSeguro.truncar(TextoSeguro.limparLinha(textoDaVersao), 60) + "). O app precisa do Git "
            + VERSAO_MINIMA[0] + "." + VERSAO_MINIMA[1] + " ou mais novo para criar o repositório. Atualize o Git e confira de novo.");
    }

    /** Uma pasta como o git a escreveu na mensagem: só vale se não tem controle nem aspas (para o comando copiável ficar sem surpresa). */
    private static boolean textoSeguroDePasta(String p) {
        return ValidadorDeGit.pastaValida(p) && p.indexOf('\'') < 0;
    }

    /** O caminho para um comando que o usuário vai colar: sem aspas se é só caracteres comuns, senão entre aspas simples. */
    static String paraOShell(String p) {
        if (p.matches("[A-Za-z0-9_@%+=:.,/~-]+")) {
            return p;
        }
        return "'" + p.replace("'", "'\\''") + "'";
    }

    private static String resumo(String stderr) {
        String limpo = TextoSeguro.limparLinha(Redator.redigir(stderr));
        return limpo.isEmpty() ? "sem mais detalhes" : TextoSeguro.truncar(limpo, 300);
    }
}
