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
    /** A linha que marca o começo do que a instalação da chave quer dizer (o que o shell do usuário imprimir antes, como num {@code .bashrc}, fica de fora). */
    public static final String MARCADOR_DE_INICIO = "@@lab-desktop-inicio@@";
    /** A linha que marca o fim. Sem ela o comando não terminou. */
    public static final String MARCADOR_DE_FIM = "@@lab-desktop-fim@@";
    /** O que a instalação da chave diz quando acrescentou a linha. */
    public static final String SAIDA_ADICIONADA = "adicionada";
    /** Acrescentou, mas a chave já aparecia no arquivo só COMENTADA ou com uma restrição ({@code command=}, {@code from=}, {@code restrict}), que não serve ao app. */
    public static final String SAIDA_ADICIONADA_APESAR = "adicionada-apesar";
    /** A chave já estava no arquivo numa linha que serve (nada foi acrescentado). */
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
     * faltarem, ajusta as permissões, não duplica e preserva o que já existe (inclusive um arquivo sem a quebra de linha final). A duplicata é
     * procurada só em linhas que NÃO são comentário e NÃO têm {@code command=}, {@code from=} ou {@code restrict}: uma chave só comentada ou restrita
     * ganha uma linha nossa ({@value #SAIDA_ADICIONADA_APESAR}). A resposta vai entre {@link #MARCADOR_DE_INICIO} e {@link #MARCADOR_DE_FIM}
     * ({@value #SAIDA_ADICIONADA}, {@value #SAIDA_ADICIONADA_APESAR} ou {@value #SAIDA_JA_EXISTIA}), então o que o shell do usuário imprimir por conta
     * própria não atrapalha. Códigos de saída: 2 sem pasta pessoal, 3 permissões da pasta .ssh, 4 entrada inválida, 5 não criou o arquivo,
     * 6 falhou ao acrescentar a linha (pode ter ficado pela metade), 7 acrescentou mas não conseguiu fechar a permissão do arquivo.
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
            + "echo " + MARCADOR_DE_INICIO + "; "
            + "if grep -v \"^[[:space:]]*#\" \"$arq\" | grep -F -e \"$blob\" | grep -qv -e \"command=\" -e \"from=\" -e \"restrict\"; then echo " + SAIDA_JA_EXISTIA + "; "
            + "else prior=" + SAIDA_ADICIONADA + "; grep -qF -e \"$blob\" \"$arq\" && prior=" + SAIDA_ADICIONADA_APESAR + "; "
            + "if [ -s \"$arq\" ] && [ -n \"$(tail -c1 \"$arq\")\" ]; then echo >> \"$arq\"; fi; "
            + "printf \"%s\\n\" \"$linha\" >> \"$arq\" || exit 6; echo \"$prior\"; fi; "
            + "chmod 600 \"$arq\" || exit 7; "
            + "echo " + MARCADOR_DE_FIM + "'";
    }

    /**
     * Lê (sem alterar nada) as permissões da pasta pessoal, da {@code .ssh} e do {@code authorized_keys}: o {@code sshd} com {@code StrictModes} recusa a chave se a
     * pasta pessoal aceita escrita por grupo ou outros. Uma linha {@code ls -l} por item.
     */
    public static String lerPermissoesDaPastaPessoal() {
        return "sh -c 'ls -ld \"$HOME\" \"$HOME/.ssh\" \"$HOME/.ssh/authorized_keys\"'";
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

    /** O marcador que abre a resposta de {@link #lerPastaDaConfig}: depois dele, {@code sim} ou {@code nao} (a pasta existe?). Improvável de aparecer num arquivo de configuração. */
    public static final String MARCADOR_DA_PASTA = "@@lab-desktop-pasta@@";
    /** O marcador que abre cada arquivo na resposta de {@link #lerPastaDaConfig}, seguido de um espaço e do caminho. */
    public static final String MARCADOR_DE_ARQUIVO = "@@lab-desktop-arquivo@@";

    /**
     * {@code @@lab-desktop-pasta@@ sim|nao} e, para cada arquivo {@code *.conf} (em ordem ASCII, a do shell no locale C; o sshd ordena como o {@code glob} do sistema),
     * uma linha {@code @@lab-desktop-arquivo@@ <caminho>} e o conteúdo.
     */
    public static String lerPastaDaConfig(String prefixo) {
        String p = exigirPrefixo(prefixo);
        return "sh -c 'if [ -d " + PASTA_DA_CONFIG + " ]; then echo \"" + MARCADOR_DA_PASTA + " sim\"; else echo \"" + MARCADOR_DA_PASTA + " nao\"; fi; "
            + "for f in " + PASTA_DA_CONFIG + "/*.conf; do [ -f \"$f\" ] && { echo \"" + MARCADOR_DE_ARQUIVO + " $f\"; " + p + "cat \"$f\"; }; done; true'";
    }

    /** O conteúdo do arquivo próprio do app (ou nada, se não existe): serve para reler o estado real depois de uma queda de conexão. */
    public static String lerArquivoProprio(String prefixo) {
        return "sh -c '" + exigirPrefixo(prefixo) + "cat " + ARQUIVO_PROPRIO + " 2>/dev/null; true'";
    }

    /** A configuração efetiva do sshd PARA este usuário e este endereço (enxerga os blocos {@code Match}); o endereço é o do próprio app, do {@code SSH_CLIENT}. */
    public static String lerConfiguracaoParaEsteUsuario(String prefixo, String usuario) {
        if (usuario == null || !br.com.labdesktop.seguranca.ValidadorDeNomes.usuarioSshValido(usuario)) {
            throw new IllegalArgumentException("Usuário do SSH inválido.");
        }
        return "sh -c '" + PATH_DO_SSHD + exigirPrefixo(prefixo) + "sshd -T -C user=" + usuario + ",addr=${SSH_CLIENT%% *},host=localhost,laddr=127.0.0.1,lport=22'";
    }

    /** O servidor tem {@code systemd-run}? Sem ele o app não arma o desfazer automático no servidor e não desliga a senha. */
    public static String verificarSystemdRun() {
        return "sh -c 'if command -v systemd-run >/dev/null 2>&1 && [ -d /run/systemd/system ]; then echo systemd-run=sim; else echo systemd-run=nao; fi'";
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
        String p = exigirPrefixo(prefixo);
        // Num servidor com SELinux o arquivo novo precisa do contexto certo para o sshd lê-lo; sem o restorecon (Debian, Ubuntu) nada acontece.
        return "sh -c '" + p + "tee " + ARQUIVO_PROPRIO + " >/dev/null && { [ -x /usr/sbin/restorecon ] && " + p + "/usr/sbin/restorecon " + ARQUIVO_PROPRIO + " || true; }'";
    }

    /** Quantos segundos o servidor espera antes de desfazer sozinho a regra do app, se o app não cancelar. */
    public static final int PRAZO_DO_DESFAZER_NO_SERVIDOR = 120;

    /** O nome da unidade do desfazer automático desta operação (um carimbo só: o de {@code carimbo}). */
    public static String nomeDoDesfazerNoServidor(String carimbo) {
        return "lab-desktop-desfazer-" + exigirCarimbo(carimbo);
    }

    /**
     * ARMA o desfazer no PRÓPRIO servidor, antes de gravar: um timer do systemd ({@code systemd-run --on-active=120}) que, se o app morrer, perder a conexão ou
     * ficar sem resposta, apaga o arquivo do app (ou devolve a versão anterior dele) e recarrega o sshd sozinho em 2 minutos. O app o cancela
     * ({@link #cancelarDesfazerNoServidor}) só DEPOIS da prova da chave e da conferência final. Comando fixo: só o prefixo e o carimbo (regex) variam.
     */
    public static String armarDesfazerNoServidor(String prefixo, String carimbo, boolean tinhaArquivoProprio) {
        String p = exigirPrefixo(prefixo);
        String desfazer = tinhaArquivoProprio ? "cp -p " + nomeDaCopiaDoProprio(carimbo) + " " + ARQUIVO_PROPRIO : "rm -f " + ARQUIVO_PROPRIO;
        return "sh -c '" + p + "systemd-run --quiet --on-active=" + PRAZO_DO_DESFAZER_NO_SERVIDOR + " --unit=" + nomeDoDesfazerNoServidor(carimbo)
            + " sh -c \"" + desfazer + "; systemctl reload ssh || systemctl reload sshd\"'";
    }

    /** Cancela o desfazer automático (o timer). */
    public static String cancelarDesfazerNoServidor(String prefixo, String carimbo) {
        return "sh -c '" + exigirPrefixo(prefixo) + "systemctl stop " + nomeDoDesfazerNoServidor(carimbo) + ".timer'";
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
     * Uma linha para o usuário colar NO servidor, como administrador, quando o usuário não tem {@code sudo} sem senha: guarda uma cópia datada (do arquivo do app, se já
     * existia, e do principal), grava a regra, confere, recarrega e, se algo falhar, devolve o que havia (ou apaga o arquivo que acabou de criar, se não havia nada). É a
     * mesma regra que o app grava. O usuário deve MANTER a sessão atual aberta e testar uma conexão nova antes de fechá-la ({@link #AVISO_DO_COMANDO_MANUAL}).
     */
    public static String comandoParaOsDonoRodar() {
        return "sudo sh -c 'f=" + ARQUIVO_PROPRIO + "; d=$(date +%Y%m%d-%H%M%S); [ -f $f ] && cp -p $f $f.lab-desktop-$d.bak; cp -p " + CONFIG_PRINCIPAL + " "
            + CONFIG_PRINCIPAL + ".lab-desktop-$d.bak; printf \"PasswordAuthentication no\\nKbdInteractiveAuthentication no\\nChallengeResponseAuthentication no\\n\" > $f && "
            + "sshd -t && (systemctl reload ssh || systemctl reload sshd) || { if [ -f $f.lab-desktop-$d.bak ]; then cp -p $f.lab-desktop-$d.bak $f; else rm -f $f; fi; echo desfeito; }'";
    }

    /** O aviso que vai junto do comando manual. */
    public static final String AVISO_DO_COMANDO_MANUAL = "Antes de rodar, deixe esta sessão do servidor aberta; depois de rodar, teste uma conexão NOVA (por exemplo, abra outro terminal "
        + "ou reconecte o app) antes de fechar a sessão antiga. Se a conexão nova não entrar, rode o comando de reverter na sessão que ficou aberta.";

    /** O comando para voltar ao login por senha: apaga o arquivo do app e recarrega o sshd. O app NUNCA religa a senha sozinho. */
    public static String comandoParaReverter() {
        return "sudo rm " + ARQUIVO_PROPRIO + " && (sudo systemctl reload ssh || sudo systemctl reload sshd)";
    }

    /** As escritas e leituras montadas aqui: os testes confrontam esta lista com a fronteira. */
    static List<String> todasAsEscritasDeExemplo() {
        return List.of(instalarChave(), copiaDoArquivoPrincipal(COM_SUDO, "20260101-000000"), copiaDoArquivoProprio(COM_SUDO, "20260101-000000"),
            gravarArquivoProprio(COM_SUDO), recarregarSshd(COM_SUDO), restaurarArquivoProprio(COM_SUDO, "20260101-000000"),
            apagarArquivoProprio(COM_SUDO), armarDesfazerNoServidor(COM_SUDO, "20260101-000000", true), armarDesfazerNoServidor(COM_SUDO, "20260101-000000", false),
            cancelarDesfazerNoServidor(COM_SUDO, "20260101-000000"), comandoParaOsDonoRodar());
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
