package br.com.labdesktop.acesso;

import java.util.List;
import java.util.regex.Pattern;

/**
 * TODAS as linhas de comando do primeiro acesso por senha (primeira conexão com senha e desligar o login por senha), num lugar só. Cada uma é FIXA: o único
 * texto que varia é o prefixo de permissão ({@code ""} ou {@code "sudo -n "}) e o carimbo da cópia ({@code AAAAMMDD-HHMMSS}), os dois
 * conferidos por regex antes de entrar; nada que o usuário digite e nada que o servidor devolva vai para uma linha de comando. A chave pública
 * NÃO está aqui: vai por stdin. As linhas funcionam em qualquer shell de login (sh, bash, zsh, fish): as que têm lógica vão em
 * {@code sh -c '...'} sem aspas simples por dentro.
 *
 * <p>Dois grupos. As LEITURAS e a conferência ({@code sshd -t} só lê) não mudam nada no servidor. As ESCRITAS só rodam depois do clique do usuário:
 * instalar a chave (sem {@code sudo}, só na pasta pessoal do usuário) e, no desligar a senha, copiar o arquivo, gravar a regra, recarregar e
 * desfazer. {@code sudo -n} nunca pergunta senha: sem sudo sem senha o comando falha na hora e o app mostra o comando exato para o usuário rodar
 * sozinho ({@link #comandoParaOsDonoRodar()}).
 */
public final class ComandosDoAcesso {

    /** A pasta de regras do sshd que o OpenSSH moderno lê. */
    public static final String PASTA_DA_CONFIG = "/etc/ssh/sshd_config.d";
    /** O arquivo principal do sshd. */
    public static final String CONFIG_PRINCIPAL = "/etc/ssh/sshd_config";
    /** O nome do arquivo que o app cria (e só ele): o {@code 10-} o põe antes dos arquivos de nuvem ({@code 50-cloud-init.conf}). */
    public static final String NOME_DO_ARQUIVO_PROPRIO = "10-lab-desktop.conf";
    /** O caminho do arquivo que o app cria. */
    public static final String ARQUIVO_PROPRIO = PASTA_DA_CONFIG + "/" + NOME_DO_ARQUIVO_PROPRIO;
    /** O que a instalação da chave imprime quando acrescentou a linha. */
    public static final String SAIDA_ADICIONADA = "adicionada";
    /** O que ela imprime quando a chave já estava no arquivo (nada foi acrescentado). */
    public static final String SAIDA_JA_EXISTIA = "ja-existia";

    /** Sem permissão de administrador (o usuário é root, ou não precisa de nada). */
    public static final String SEM_PREFIXO = "";
    /** Com o sudo que nunca pergunta senha. */
    public static final String COM_SUDO = "sudo -n ";

    private static final Pattern PREFIXO_VALIDO = Pattern.compile("^(sudo -n )?$");
    private static final Pattern CARIMBO_VALIDO = Pattern.compile("^[0-9]{8}-[0-9]{6}$");
    private static final String PATH_DO_SSHD = "PATH=\"$PATH:/usr/sbin:/sbin\"; ";

    private ComandosDoAcesso() { }

    // =====================================================================================================
    // A chave (sem sudo; só a pasta pessoal do usuário)
    // =====================================================================================================

    /**
     * Instala a chave no {@code ~/.ssh/authorized_keys} do usuário que está logado. Lê DUAS linhas do stdin: a primeira é só o conteúdo da
     * chave (base64), para achar duplicata; a segunda é a linha completa a acrescentar. Cria a pasta {@code .ssh} (700) e o arquivo (600) se
     * faltarem, ajusta as permissões, não duplica e preserva o que já existe (inclusive um arquivo sem a quebra de linha final). Imprime
     * {@value #SAIDA_ADICIONADA} ou {@value #SAIDA_JA_EXISTIA}. Códigos de saída: 2 sem pasta pessoal, 3 permissões, 4 entrada inválida, 5 gravação.
     * É ESCRITA, mas só na pasta pessoal do usuário e sem {@code sudo}.
     */
    public static String instalarChave() {
        return "sh -c '"
            + "umask 077; "
            + "[ -n \"$HOME\" ] || exit 2; "
            + "mkdir -p \"$HOME/.ssh\" && chmod 700 \"$HOME/.ssh\" || exit 3; "
            + "IFS= read -r blob; IFS= read -r linha; "
            + "case \"$blob\" in \"\"|*[!A-Za-z0-9+/=]*) exit 4;; esac; "
            + "[ -n \"$linha\" ] || exit 4; "
            + "arq=\"$HOME/.ssh/authorized_keys\"; "
            + "[ -e \"$arq\" ] || : > \"$arq\" || exit 5; "
            + "if grep -qF -e \"$blob\" \"$arq\"; then echo " + SAIDA_JA_EXISTIA + "; "
            + "else if [ -s \"$arq\" ] && [ -n \"$(tail -c1 \"$arq\")\" ]; then echo >> \"$arq\"; fi; "
            + "printf \"%s\\n\" \"$linha\" >> \"$arq\" || exit 5; echo " + SAIDA_ADICIONADA + "; fi; "
            + "chmod 600 \"$arq\" || exit 3'";
    }

    // =====================================================================================================
    // LEITURAS do desligar a senha (nada muda no servidor)
    // =====================================================================================================

    /** {@code uid=<n>} e {@code sudo=sim|nao}: o que o usuário pode fazer sem digitar senha. */
    public static String lerPermissoes() {
        return "sh -c 'echo \"uid=$(id -u)\"; if sudo -n true >/dev/null 2>&1; then echo sudo=sim; else echo sudo=nao; fi'";
    }

    /** A configuração EFETIVA do sshd (enxerga {@code sshd_config.d} e os {@code Include}). */
    public static String lerConfiguracaoEfetiva(String prefixo) {
        return "sh -c '" + PATH_DO_SSHD + exigirPrefixo(prefixo) + "sshd -T'";
    }

    /** O arquivo principal do sshd, para saber a ordem dos {@code Include}. */
    public static String lerConfigPrincipal(String prefixo) {
        return "sh -c '" + exigirPrefixo(prefixo) + "cat " + CONFIG_PRINCIPAL + "'";
    }

    /** {@code pasta=sim|nao} e, para cada arquivo {@code *.conf}, uma linha {@code #### <caminho>} e o conteúdo. */
    public static String lerPastaDaConfig(String prefixo) {
        String p = exigirPrefixo(prefixo);
        return "sh -c 'if [ -d " + PASTA_DA_CONFIG + " ]; then echo pasta=sim; else echo pasta=nao; fi; "
            + "for f in " + PASTA_DA_CONFIG + "/*.conf; do [ -f \"$f\" ] && { echo \"#### $f\"; " + p + "cat \"$f\"; }; done; true'";
    }

    /** Confere a sintaxe da configuração do sshd ({@code sshd -t}); não muda nada. */
    public static String validarConfiguracao(String prefixo) {
        return "sh -c '" + PATH_DO_SSHD + exigirPrefixo(prefixo) + "sshd -t'";
    }

    // =====================================================================================================
    // ESCRITAS do desligar a senha (só depois do clique e do nome digitado)
    // =====================================================================================================

    /** Cópia datada do arquivo principal do sshd, ao lado dele. */
    public static String copiaDoArquivoPrincipal(String prefixo, String carimbo) {
        return "sh -c '" + exigirPrefixo(prefixo) + "cp -p " + CONFIG_PRINCIPAL + " " + nomeDaCopiaDoPrincipal(carimbo) + "'";
    }

    /** Cópia datada do arquivo PRÓPRIO do app, se ele já existe (uma segunda vez que se desliga a senha). */
    public static String copiaDoArquivoProprio(String prefixo, String carimbo) {
        String copia = nomeDaCopiaDoProprio(carimbo);
        return "sh -c 'if [ -f " + ARQUIVO_PROPRIO + " ]; then " + exigirPrefixo(prefixo) + "cp -p " + ARQUIVO_PROPRIO + " " + copia + "; fi'";
    }

    /** Grava a regra no arquivo próprio; o conteúdo vem pelo stdin ({@link DesligadorDeSenha#conteudoDaRegra}). */
    public static String gravarArquivoProprio(String prefixo) {
        return "sh -c '" + exigirPrefixo(prefixo) + "tee " + ARQUIVO_PROPRIO + " >/dev/null'";
    }

    /** Recarrega o sshd (NUNCA reinicia): as sessões abertas continuam. Serve o {@code ssh} do Debian e o {@code sshd} dos outros. */
    public static String recarregarSshd(String prefixo) {
        String p = exigirPrefixo(prefixo);
        return "sh -c '" + p + "systemctl reload ssh || " + p + "systemctl reload sshd'";
    }

    /** Devolve o arquivo próprio ao que era antes (a cópia datada dele). */
    public static String restaurarArquivoProprio(String prefixo, String carimbo) {
        return "sh -c '" + exigirPrefixo(prefixo) + "cp -p " + nomeDaCopiaDoProprio(carimbo) + " " + ARQUIVO_PROPRIO + "'";
    }

    /** Apaga o arquivo próprio do app (desfazer, ou "Como reverter"). */
    public static String apagarArquivoProprio(String prefixo) {
        return "sh -c '" + exigirPrefixo(prefixo) + "rm -f " + ARQUIVO_PROPRIO + "'";
    }

    public static String nomeDaCopiaDoPrincipal(String carimbo) {
        return CONFIG_PRINCIPAL + ".lab-desktop-" + exigirCarimbo(carimbo) + ".bak";
    }

    public static String nomeDaCopiaDoProprio(String carimbo) {
        return ARQUIVO_PROPRIO + ".lab-desktop-" + exigirCarimbo(carimbo) + ".bak";
    }

    // =====================================================================================================
    // O que o USUÁRIO roda sozinho (o app nunca digita senha de sudo)
    // =====================================================================================================

    /**
     * Uma linha para o usuário colar NO servidor, como administrador, quando o usuário não tem {@code sudo} sem senha: copia o arquivo, grava a
     * regra, confere, recarrega e, se algo falhar, apaga o que acabou de criar. É a mesma regra que o app grava.
     */
    public static String comandoParaOsDonoRodar() {
        return "sudo sh -c 'cp -p " + CONFIG_PRINCIPAL + " " + CONFIG_PRINCIPAL + ".lab-desktop-$(date +%Y%m%d-%H%M%S).bak && "
            + "printf \"PasswordAuthentication no\\nKbdInteractiveAuthentication no\\n\" > " + ARQUIVO_PROPRIO + " && "
            + "sshd -t && (systemctl reload ssh || systemctl reload sshd) || { rm -f " + ARQUIVO_PROPRIO + "; echo desfeito; }'";
    }

    /** O comando para voltar ao login por senha: apaga o arquivo do app e recarrega o sshd. O app NUNCA religa a senha sozinho. */
    public static String comandoParaReverter() {
        return "sudo rm " + ARQUIVO_PROPRIO + " && (sudo systemctl reload ssh || sudo systemctl reload sshd)";
    }

    /** As escritas e leituras montadas aqui: os testes confrontam esta lista com a fronteira. */
    static List<String> todasAsEscritasDeExemplo() {
        return List.of(instalarChave(), copiaDoArquivoPrincipal(COM_SUDO, "20260101-000000"), copiaDoArquivoProprio(COM_SUDO, "20260101-000000"),
            gravarArquivoProprio(COM_SUDO), recarregarSshd(COM_SUDO), restaurarArquivoProprio(COM_SUDO, "20260101-000000"),
            apagarArquivoProprio(COM_SUDO));
    }

    private static String exigirPrefixo(String prefixo) {
        if (prefixo == null || !PREFIXO_VALIDO.matcher(prefixo).matches()) {
            throw new IllegalArgumentException("Prefixo de permissão inválido.");
        }
        return prefixo;
    }

    private static String exigirCarimbo(String carimbo) {
        if (carimbo == null || !CARIMBO_VALIDO.matcher(carimbo).matches()) {
            throw new IllegalArgumentException("Carimbo de data inválido.");
        }
        return carimbo;
    }
}
