package br.com.labdesktop.instalador;

import java.util.List;
import java.util.regex.Pattern;

/**
 * TODAS as linhas de comando que o instalador do agente roda no servidor, num lugar só. Cada uma é FIXA: o que varia (o nome do
 * container, a porta, o nome da pasta, a versão do pacote, o nome da distribuição) já passou por uma regex curta antes de entrar
 * ({@link ParametrosDaInstalacao}, {@link PacoteDoAgente}, {@link #distribuicaoSuportada}), e nada que o usuário digite livremente nem
 * nada que o servidor devolva entra numa linha de comando. As linhas funcionam em qualquer shell de login (sh, bash, zsh, fish): as
 * que têm lógica vão em {@code sh -c '...'} sem aspas simples por dentro.
 *
 * <p>Há dois grupos. As LEITURAS (verificações prévias, diagnóstico do agente, conferências) não mudam nada no servidor; é só
 * elas que rodam quando o app conecta, abre ou diagnostica (a regra: NADA se instala sozinho). As ESCRITAS (instalar o
 * Docker, enviar e extrair o pacote, construir a imagem, subir ou remover o container) só rodam depois do clique do usuário na tela de
 * confirmação. {@link #ehLeitura(String)} é a fronteira, e um teste a confere contra o que cada construtor devolve.
 *
 * <p>{@code docker} em vários métodos é o comando do Docker já escolhido pela verificação: {@code "docker"} (root, ou usuário do
 * grupo docker) ou {@code "sudo -n docker"} (sudo sem senha; o app nunca digita senha de sudo).
 */
public final class ComandosRemotos {

    /** O rótulo que marca um container como criado por este instalador (e a versão do pacote que o criou). */
    public static final String ROTULO_DO_AGENTE = "br.com.labdesktop.agente";

    /** O comando do Docker quando o usuário já pode usá-lo. */
    public static final String DOCKER_DIRETO = "docker";
    /** O comando do Docker quando só o {@code sudo -n} chega nele. */
    public static final String DOCKER_COM_SUDO = "sudo -n docker";

    private static final Pattern DOCKER_VALIDO = Pattern.compile("^(sudo -n )?docker$");
    private static final Pattern DISTRIBUICAO = Pattern.compile("^(debian|ubuntu)$");
    private static final String REGISTRO_DE_IMAGENS = "https://registry-1.docker.io/v2/";

    private ComandosRemotos() { }

    // =====================================================================================================
    // LEITURAS (nada muda no servidor)
    // =====================================================================================================

    public static String lerOsRelease() {
        return "cat /etc/os-release";
    }

    public static String lerArquitetura() {
        return "uname -m";
    }

    /** Usuário, grupos, se tem sudo sem senha, o shell e se o tmux existe: uma saída de linhas {@code chave=valor}. */
    public static String lerPermissoes() {
        return "sh -c 'echo \"uid=$(id -u)\"; echo \"usuario=$(id -un)\"; echo \"grupos=$(id -nG)\"; "
            + "if sudo -n true >/dev/null 2>&1; then echo sudo=sim; else echo sudo=nao; fi; "
            + "echo \"shell=${SHELL:-}\"; if command -v tmux >/dev/null 2>&1; then echo tmux=sim; else echo tmux=nao; fi'";
    }

    /**
     * O Docker: {@code docker=ausente|ok:<versão>|so-com-sudo|sem-permissao|parado|erro} e {@code compose=<versão>|ausente}.
     * Diferencia "sem permissão" de "parado" olhando a mensagem do próprio Docker.
     */
    public static String lerDocker() {
        return "sh -c 'if ! command -v docker >/dev/null 2>&1; then echo docker=ausente; exit 0; fi; "
            + "o=$(docker version --format \"{{.Server.Version}}\" 2>&1); r=$?; "
            + "if [ $r -eq 0 ]; then echo \"docker=ok:$o\"; "
            + "elif echo \"$o\" | grep -qi \"permission denied\"; then "
            + "if sudo -n docker version --format \"{{.Server.Version}}\" >/dev/null 2>&1; then echo docker=so-com-sudo; else echo docker=sem-permissao; fi; "
            + "elif echo \"$o\" | grep -qiE \"cannot connect|daemon running\"; then echo docker=parado; "
            + "else echo docker=erro; fi; "
            + "c=$(docker compose version --short 2>/dev/null || sudo -n docker compose version --short 2>/dev/null); "
            + "echo \"compose=${c:-ausente}\"'";
    }

    /**
     * Os containers cujo nome contém o pedido, uma linha {@code nome|versão do pacote que o criou (rótulo, vazio se não foi o
     * instalador)|estado} por container (o filtro do Docker casa por trecho: quem lê confere o nome inteiro).
     */
    public static String lerContainer(String docker, ParametrosDaInstalacao p) {
        return exigirDocker(docker) + " ps -a --filter name=" + p.container()
            + " --format '{{.Names}}|{{.Label \"" + ROTULO_DO_AGENTE + "\"}}|{{.State}}'";
    }

    /** Os volumes com o nome pedido (uma linha por volume; o filtro casa por trecho). */
    public static String lerVolume(String docker, ParametrosDaInstalacao p) {
        return exigirDocker(docker) + " volume ls -q --filter name=" + p.volume();
    }

    /** Sai com 0 se a imagem existe. */
    public static String lerImagem(String docker, String imagem) {
        return exigirDocker(docker) + " image inspect -f '{{.Id}}' " + exigirImagem(imagem);
    }

    /** Uma linha por quem escuta nesta porta (em qualquer endereço); vazio = livre. */
    public static String lerPorta(int porta) {
        if (porta < 1 || porta > 65_535) {
            throw new IllegalArgumentException("porta");
        }
        return "ss -H -ltn 'sport = :" + porta + "'";
    }

    /** O espaço livre da pasta pessoal e, se existir, da pasta do Docker (formato POSIX, em KiB). */
    public static String lerDisco() {
        return "df -Pk \"$HOME\" /var/lib/docker";
    }

    /** O código HTTP do registro de imagens do Docker Hub ({@code 000}: sem saída para a internet; {@code sem-ferramenta}). */
    public static String lerInternet() {
        return "sh -c 'if command -v curl >/dev/null 2>&1; then curl -sS -m 8 -o /dev/null -w \"%{http_code}\" "
            + REGISTRO_DE_IMAGENS + " 2>/dev/null; "
            + "elif command -v wget >/dev/null 2>&1; then wget -q -T 8 --spider " + REGISTRO_DE_IMAGENS + " >/dev/null 2>&1; s=$?; "
            + "if [ $s -eq 0 ] || [ $s -eq 6 ]; then echo 200; else echo 000; fi; "
            + "else echo sem-ferramenta; fi'";
    }

    /** A pasta do instalador já existe? Responde {@code existe} ou {@code nao}. */
    public static String lerSeAPastaExiste(ParametrosDaInstalacao p) {
        return "sh -c 'if [ -d \"$HOME/" + p.pasta() + "\" ]; then echo existe; else echo nao; fi'";
    }

    /** A versão do pacote que está extraída no servidor (arquivo {@code VERSAO}); sai com status diferente de 0 se não há. */
    public static String lerVersaoExtraida(ParametrosDaInstalacao p) {
        return "cat \"$HOME/" + p.pasta() + "/src/VERSAO\"";
    }

    /** O SHA-256 do pacote enviado: {@code <hash>  <arquivo>}. */
    public static String lerSha256DoPacote(ParametrosDaInstalacao p, PacoteDoAgente pacote) {
        String arquivo = "\"$HOME/" + p.pasta() + "/" + pacote.nomeDoArquivo() + "\"";
        return "sh -c 'if command -v sha256sum >/dev/null 2>&1; then sha256sum " + arquivo + "; else shasum -a 256 " + arquivo + "; fi'";
    }

    /** O fim dos logs do container (para mostrar o erro REAL se o agente não sobe). */
    public static String lerLogsDoContainer(String docker, ParametrosDaInstalacao p) {
        return exigirDocker(docker) + " logs --tail 40 " + p.container() + " 2>&1";
    }

    /**
     * O diagnóstico de "o agente está instalado?", feito quando o SSH está de pé e o agente não responde: uma palavra em stdout,
     * {@code sem-docker}, {@code ausente}, {@code sem-permissao}, {@code existe:<estado>} ou {@code erro}.
     */
    public static String lerSituacaoDoAgente(String container) {
        new ParametrosDaInstalacao(container, ParametrosDaInstalacao.PORTA_PADRAO, ParametrosDaInstalacao.PASTA_PADRAO); // a mesma regra de nome
        return "sh -c 'command -v docker >/dev/null 2>&1 || { echo sem-docker; exit 0; }; "
            + "o=$(docker inspect -f \"{{.State.Status}}\" " + container + " 2>&1); r=$?; "
            + "if [ $r -eq 0 ]; then echo \"existe:$o\"; "
            + "elif echo \"$o\" | grep -qi \"permission denied\"; then echo sem-permissao; "
            + "elif echo \"$o\" | grep -qi \"no such\"; then echo ausente; "
            + "else echo erro; fi'";
    }

    // =====================================================================================================
    // ESCRITAS (só depois do clique do usuário)
    // =====================================================================================================

    /** O sistema suportado para instalar o Docker pelo repositório oficial: {@code debian} ou {@code ubuntu}. */
    public static boolean distribuicaoSuportada(String id) {
        return id != null && DISTRIBUICAO.matcher(id).matches();
    }

    /**
     * Instalar o Docker Engine pelo repositório apt OFICIAL da Docker (Debian e Ubuntu), na ordem da documentação deles. Cada item é
     * um comando; {@code root} diz se o usuário já é administrador (sem {@code sudo}) ou se vale {@code sudo -n}.
     */
    public static List<String> instalarODocker(boolean root, String distribuicao) {
        if (!distribuicaoSuportada(distribuicao)) {
            throw new IllegalArgumentException("O app só instala o Docker em Debian e Ubuntu.");
        }
        String adm = root ? "" : "sudo -n ";
        String url = "https://download.docker.com/linux/" + distribuicao;
        return List.of(
            adm + "env DEBIAN_FRONTEND=noninteractive apt-get update",
            adm + "env DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl",
            adm + "install -m 0755 -d /etc/apt/keyrings",
            adm + "curl -fsSL " + url + "/gpg -o /etc/apt/keyrings/docker.asc",
            adm + "chmod a+r /etc/apt/keyrings/docker.asc",
            "sh -c 'echo \"deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] " + url
                + " $(. /etc/os-release && echo $VERSION_CODENAME) stable\" | " + adm + "tee /etc/apt/sources.list.d/docker.list >/dev/null'",
            adm + "env DEBIAN_FRONTEND=noninteractive apt-get update",
            adm + "env DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io "
                + "docker-buildx-plugin docker-compose-plugin");
    }

    /** Cria a pasta do instalador (e a da fonte do agente) dentro da pasta pessoal. */
    public static String criarPasta(ParametrosDaInstalacao p) {
        return "sh -c '[ -n \"$HOME\" ] && mkdir -p \"$HOME/" + p.pasta() + "/src\"'";
    }

    /** Extrai o pacote enviado na pasta da fonte (esvaziando-a antes: é a fonte do instalador, nunca dados do usuário). */
    public static String extrairPacote(ParametrosDaInstalacao p, PacoteDoAgente pacote) {
        String base = "\"$HOME/" + p.pasta() + "\"";
        return "sh -c '[ -n \"$HOME\" ] && rm -rf " + base + "/src && mkdir -p " + base + "/src && tar -xzf "
            + base + "/" + pacote.nomeDoArquivo() + " -C " + base + "/src'";
    }

    /** O caminho do pacote no servidor, relativo à pasta pessoal (para o envio por SFTP). */
    public static String caminhoDoPacote(ParametrosDaInstalacao p, PacoteDoAgente pacote) {
        return p.pasta() + "/" + pacote.nomeDoArquivo();
    }

    /**
     * Constrói a imagem NO servidor (evita problema de arquitetura entre o Mac e a VPS). Guarda só o FIM da saída do build, que é
     * onde está o erro, e devolve o status do build (não o do {@code tail}).
     */
    public static String construirImagem(String docker, ParametrosDaInstalacao p, PacoteDoAgente pacote) {
        return "sh -c 'o=$(" + exigirDocker(docker) + " build -f Dockerfile.monitor -t " + p.imagem(pacote.versao())
            + " \"$HOME/" + p.pasta() + "/src\" 2>&1); r=$?; echo \"$o\" | tail -n 40; exit $r'";
    }

    /** Remove o container (o instalador só chama isto para um container que tem o rótulo dele). */
    public static String removerContainer(String docker, ParametrosDaInstalacao p) {
        return exigirDocker(docker) + " rm -f " + p.container();
    }

    /**
     * Sobe o container do agente: reinício automático, SÓ no loopback (o agente escuta em 127.0.0.1 e a rede é a do servidor, então
     * nada entra por fora; o app chega pelo túnel SSH), pasta de dados num volume nomeado e logs com teto. O
     * agente já nasce exigindo a credencial do app; o app a registra logo depois de subir e tolera o 401 até lá.
     */
    public static String subirContainer(String docker, ParametrosDaInstalacao p, PacoteDoAgente pacote) {
        return exigirDocker(docker) + " run -d --name " + p.container()
            + " --restart unless-stopped --network host --pid host"
            + " --label " + ROTULO_DO_AGENTE + "=" + pacote.versao()
            + " --log-opt max-size=10m --log-opt max-file=3"
            + " -e PORT=" + p.porta() + " -e PAINEL_PERFIL=monitor -e PAINEL_EXIGE_TOKEN=1"
            + " -v /var/run/docker.sock:/var/run/docker.sock -v /:/host:ro -v " + p.volume() + ":" + br.com.labdesktop.instalador.PastasNoContainer.DADOS + " "
            + p.imagem(pacote.versao());
    }

    /**
     * Renomeia um container (a atualização guarda o agente antigo como {@code <nome>-anterior} até o novo responder). Os dois nomes passam
     * pela regex de nome de container.
     */
    public static String renomearContainer(String docker, String de, String para) {
        return exigirDocker(docker) + " rename " + exigirNomeDeContainer(de) + " " + exigirNomeDeContainer(para);
    }

    /** Para um container (sem removê-lo). */
    public static String pararContainer(String docker, String nome) {
        return exigirDocker(docker) + " stop " + exigirNomeDeContainer(nome);
    }

    /** Liga de novo um container parado. */
    public static String iniciarContainer(String docker, String nome) {
        return exigirDocker(docker) + " start " + exigirNomeDeContainer(nome);
    }

    /** Remove um container pelo nome (o instalador só chama para um que tem o rótulo dele ou que ele mesmo acabou de criar). */
    public static String removerContainerPeloNome(String docker, String nome) {
        return exigirDocker(docker) + " rm -f " + exigirNomeDeContainer(nome);
    }

    public static String removerVolume(String docker, ParametrosDaInstalacao p) {
        return exigirDocker(docker) + " volume rm " + p.volume();
    }

    public static String removerImagem(String docker, String imagem) {
        return exigirDocker(docker) + " rmi " + exigirImagem(imagem);
    }

    /** Apaga a pasta do instalador (só ela: o nome é validado e o caminho começa na pasta pessoal). */
    public static String removerPasta(ParametrosDaInstalacao p) {
        return "sh -c '[ -n \"$HOME\" ] && rm -rf \"$HOME/" + p.pasta() + "\"'";
    }

    // =====================================================================================================
    // A fronteira
    // =====================================================================================================

    private static final List<String> PREFIXOS_DE_LEITURA = List.of(
        "cat /etc/os-release", "uname -m", "cat \"$HOME/", "df -Pk ", "ss -H -ltn ",
        "sh -c 'echo \"uid=", "sh -c 'if ! command -v docker", "sh -c 'command -v docker",
        "sh -c 'if command -v curl", "sh -c 'if [ -d \"$HOME/", "sh -c 'if command -v sha256sum");
    private static final List<String> LEITURAS_DO_DOCKER = List.of(
        " ps -a --filter name=", " volume ls -q ", " image inspect ", " logs --tail ");

    /**
     * O comando só LÊ? É a fronteira da regra: ao conectar, abrir o app ou diagnosticar, só comandos para os quais isto é
     * verdadeiro podem rodar.
     */
    public static boolean ehLeitura(String comando) {
        if (comando == null) {
            return false;
        }
        for (String prefixo : PREFIXOS_DE_LEITURA) {
            if (comando.startsWith(prefixo)) {
                return true;
            }
        }
        for (String docker : new String[] {DOCKER_DIRETO, DOCKER_COM_SUDO}) {
            if (comando.startsWith(docker)) {
                for (String leitura : LEITURAS_DO_DOCKER) {
                    if (comando.startsWith(docker + leitura)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String exigirDocker(String docker) {
        if (docker == null || !DOCKER_VALIDO.matcher(docker).matches()) {
            throw new IllegalArgumentException("Comando do Docker inválido.");
        }
        return docker;
    }

    private static String exigirNomeDeContainer(String nome) {
        if (nome == null || !nome.matches("^[a-z0-9][a-z0-9_.-]{0,60}$")) {
            throw new IllegalArgumentException("Nome de container inválido.");
        }
        return nome;
    }

    private static String exigirImagem(String imagem) {
        if (imagem == null || !imagem.matches("^[a-z0-9][a-z0-9_.-]{0,40}:[A-Za-z0-9][A-Za-z0-9_.-]{0,40}$")) {
            throw new IllegalArgumentException("Nome de imagem inválido.");
        }
        return imagem;
    }
}
