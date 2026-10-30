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
    /** O arquivo que o instalador deixa na pasta dele: é como o app sabe que a pasta é sua (e só então a limpa ou a apaga). */
    public static final String MARCADOR_DA_PASTA = ".lab-desktop";

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

    /**
     * De quem é a pasta do instalador: {@code app} (tem o marcador {@code .lab-desktop}, ou a versão extraída de um instalador de antes do
     * marcador), {@code alheia} (existe e não é do app: nunca é tocada) ou {@code nao} (não existe).
     */
    public static String lerDonoDaPasta(ParametrosDaInstalacao p) {
        return "sh -c 'if [ -f \"$HOME/" + p.pasta() + "/" + MARCADOR_DA_PASTA + "\" ] || [ -f \"$HOME/" + p.pasta() + "/src/VERSAO\" ]; "
            + "then echo app; elif [ -e \"$HOME/" + p.pasta() + "\" ]; then echo alheia; else echo nao; fi'";
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
        return lerSituacaoDoAgente(container, DOCKER_DIRETO);
    }

    /** Como {@link #lerSituacaoDoAgente(String)}, com o comando do Docker que vale neste servidor ({@code docker} ou {@code sudo -n docker}). */
    public static String lerSituacaoDoAgente(String container, String docker) {
        new ParametrosDaInstalacao(container, ParametrosDaInstalacao.PORTA_PADRAO, ParametrosDaInstalacao.PASTA_PADRAO); // a mesma regra de nome
        return "sh -c 'command -v docker >/dev/null 2>&1 || { echo sem-docker; exit 0; }; "
            + "o=$(" + exigirDocker(docker) + " inspect -f \"{{.State.Status}}\" " + container + " 2>&1); r=$?; "
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
        return "sh -c '[ -n \"$HOME\" ] && mkdir -p \"$HOME/" + p.pasta() + "/src\" && : > \"$HOME/" + p.pasta() + "/" + MARCADOR_DA_PASTA + "\"'";
    }

    /** Extrai o pacote enviado na pasta da fonte (esvaziando-a antes: é a fonte do instalador, nunca dados do usuário). */
    public static String extrairPacote(ParametrosDaInstalacao p, PacoteDoAgente pacote) {
        String base = "\"$HOME/" + p.pasta() + "\"";
        return "sh -c '[ -n \"$HOME\" ] && [ -f " + base + "/" + MARCADOR_DA_PASTA + " ] && rm -rf " + base + "/src && mkdir -p " + base + "/src && tar -xzf "
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
     * Os TRÊS arquivos da máquina que o agente lê (e só eles), com o caminho que o container os enxerga: o nome da máquina, o sistema e os
     * nomes de usuário dos processos. Antes o container recebia a raiz do disco inteira (somente leitura); o agente de monitoramento só abre
     * estes três, então é só o que entra.
     */
    public static final List<String> ARQUIVOS_DA_MAQUINA = List.of("/etc/hostname", "/etc/os-release", "/etc/passwd");

    /**
     * As opções {@code --mount} dos {@link #ARQUIVOS_DA_MAQUINA}, só leitura. {@code --mount} e não {@code -v} DE PROPÓSITO: com {@code -v}, um
     * arquivo que não existe no servidor vira uma PASTA criada pelo Docker na raiz do sistema dele (um {@code /etc/hostname} que é pasta quebra
     * a máquina); com {@code --mount type=bind} o Docker recusa e o container não sobe.
     */
    public static List<String> montagemDosArquivosDaMaquina() {
        List<String> l = new java.util.ArrayList<>();
        for (String arquivo : ARQUIVOS_DA_MAQUINA) {
            l.add("--mount");
            l.add("type=bind,source=" + arquivo + ",target=/host" + arquivo + ",readonly");
        }
        return List.copyOf(l);
    }

    /** O usuário (uid:gid) do agente DENTRO da imagem: alto e sem dono na máquina (o 1000 costuma ser o do administrador). É o dono da pasta de dados da imagem. */
    public static final String USUARIO_DO_AGENTE = "10001:10001";

    /** O caminho do soquete do Docker, dentro do container e na máquina. */
    public static final String SOQUETE_DO_DOCKER = "/var/run/docker.sock";

    /** Os grupos do soquete são números ({@code stat -c %g}). */
    private static final Pattern GID = Pattern.compile("^[0-9]{1,10}$");

    /**
     * O grupo dono do soquete do Docker, como o container o enxerga: sobe um container descartável com o soquete montado e pergunta ao
     * {@code stat}. É ESCRITA (cria e apaga um container), só roda depois do clique, e serve ao {@code --group-add} do agente (que roda sem ser
     * root e por isso precisa estar no grupo do soquete). No Docker Desktop o grupo é 0 (root); num Linux comum é o do grupo {@code docker}.
     * Imprime só o número.
     */
    public static String lerGidDoSoquete(String docker, String imagem) {
        return exigirDocker(docker) + " run --rm --network none -v " + SOQUETE_DO_DOCKER + ":" + SOQUETE_DO_DOCKER + " " + exigirImagem(imagem)
            + " stat -c %g " + SOQUETE_DO_DOCKER;
    }

    /** A saída de {@link #lerGidDoSoquete} é um número? Devolve-o limpo, ou {@code null}. */
    public static String gidValido(String saida) {
        String g = saida == null ? "" : saida.strip();
        return GID.matcher(g).matches() ? g : null;
    }

    /**
     * Entrega a pasta de dados ao usuário do agente ({@link #USUARIO_DO_AGENTE}). Só para um volume que JÁ existia (de uma versão anterior, cujos arquivos
     * são de root ou de outro uid): o agente já não começa como root e por isso não acerta o dono sozinho. Container descartável, sem rede, como root
     * só para esta troca; um volume novo nasce com o usuário certo (a imagem o cria assim) e não passa por aqui.
     */
    public static String acertarDonoDosDados(String docker, ParametrosDaInstalacao p, PacoteDoAgente pacote) {
        return exigirDocker(docker) + " run --rm --network none --user 0:0 -v " + p.volume() + ":/srv/painel/dados " + p.imagem(pacote.versao())
            + " chown -R " + USUARIO_DO_AGENTE + " /srv/painel/dados";
    }

    /**
     * As opções que fecham o container: o agente roda como {@link #USUARIO_DO_AGENTE} desde o primeiro instante (nunca como root), entra no grupo do
     * soquete, sem NENHUMA capacidade, sem poder ganhar privilégio, com o sistema de arquivos só leitura (a única escrita é o volume de dados e o
     * {@code /tmp} em memória). Provado no Docker de verdade: sem {@code --user}, o {@code --cap-drop ALL} impede a troca de usuário e o agente não
     * sobe; com {@code --user}, ele sobe e o {@code docker exec ... node acesso.js} (que o app usa para o token) também roda como o mesmo usuário.
     */
    public static List<String> opcoesDeEndurecimento(String gidDoSoquete) {
        if (gidValido(gidDoSoquete) == null) {
            throw new IllegalArgumentException("Grupo do soquete inválido.");
        }
        return List.of("--user", USUARIO_DO_AGENTE, "--group-add", gidDoSoquete.strip(), "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", "--read-only", "--tmpfs", "/tmp");
    }

    /**
     * Sobe o container do agente: reinício automático, SÓ no loopback (o agente escuta em 127.0.0.1 e a rede é a do servidor, então
     * nada entra por fora; o app chega pelo túnel SSH), pasta de dados num volume nomeado e logs com teto. {@code PAINEL_EXIGE_TOKEN=1}: o
     * agente (que exige o token sempre) o aceita e ignora; o app registra o token logo depois de subir e tolera o 401 até lá.
     *
     * <p>O que o container recebe: o soquete do Docker (o agente só faz {@code GET} em {@code /info}, {@code /system/df} e
     * {@code /containers/json}, pelo filtro {@code docker-proxy.js} dele; o {@code :ro} num soquete NÃO impede escrita, quem impede é o
     * filtro, então o soquete é o poder grande do container e o risco que resta), {@code --pid host} (a visão dos processos), os três arquivos de
     * {@link #montagemDosArquivosDaMaquina()} e as {@link #opcoesDeEndurecimento}. A opção mais fechada do README do agente (um segundo container, sem
     * rede, com o único acesso ao soquete) ainda não é feita pelo app.
     *
     * @param gidDoSoquete o grupo do soquete ({@link #lerGidDoSoquete})
     */
    public static String subirContainer(String docker, ParametrosDaInstalacao p, PacoteDoAgente pacote, String gidDoSoquete) {
        return exigirDocker(docker) + " run -d --name " + p.container()
            + " --restart unless-stopped --network host --pid host"
            + " " + String.join(" ", opcoesDeEndurecimento(gidDoSoquete))
            + " --label " + ROTULO_DO_AGENTE + "=" + pacote.versao()
            + " --log-opt max-size=10m --log-opt max-file=3"
            + " -e PORT=" + p.porta() + " -e PAINEL_PERFIL=monitor -e PAINEL_EXIGE_TOKEN=1"
            + " -v " + SOQUETE_DO_DOCKER + ":" + SOQUETE_DO_DOCKER + " " + String.join(" ", montagemDosArquivosDaMaquina())
            + " -v " + p.volume() + ":" + br.com.labdesktop.instalador.PastasNoContainer.DADOS + " "
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

    /**
     * Apaga a pasta do instalador, e só se ela é do app (tem o marcador, ou a versão extraída de um instalador de antes dele): o nome é
     * validado, o caminho começa na pasta pessoal e uma pasta alheia com o mesmo nome nunca é apagada.
     */
    public static String removerPasta(ParametrosDaInstalacao p) {
        String base = "\"$HOME/" + p.pasta();
        return "sh -c '[ -n \"$HOME\" ] && { [ -f " + base + "/" + MARCADOR_DA_PASTA + "\" ] || [ -f " + base + "/src/VERSAO\" ]; } && rm -rf " + base + "\"'";
    }

    // =====================================================================================================
    // A fronteira
    // =====================================================================================================

    /**
     * O comando só LÊ? É a fronteira da regra: ao conectar, abrir o app ou diagnosticar, só comandos para os quais isto é
     * verdadeiro podem rodar. A comparação é EXATA: o comando tem de ter a forma inteira de uma das leituras abaixo (com o nome do container,
     * da pasta, a porta e a versão no lugar certo, cada um dentro da regex dele), e não só começar como uma. Um prefixo igual a uma leitura
     * seguido de qualquer outra coisa ({@code cat "$HOME/x"; rm ...}) NÃO passa.
     */
    public static boolean ehLeitura(String comando) {
        if (comando == null) {
            return false;
        }
        for (Pattern leitura : LEITURAS) {
            if (leitura.matcher(comando).matches()) {
                return true;
            }
        }
        return false;
    }

    private static final String RE_NOME = "[a-z0-9][a-z0-9_.-]{0,40}";
    private static final String RE_VERSAO = "[A-Za-z0-9][A-Za-z0-9_.-]{0,40}";
    private static final List<Pattern> LEITURAS = montarLeituras();

    /**
     * Cada leitura é construída uma vez com valores-sentinela e vira uma regex inteira: os sentinelas viram a regex do valor real, o resto é
     * literal. Assim a lista nunca se afasta dos construtores acima.
     */
    private static List<Pattern> montarLeituras() {
        ParametrosDaInstalacao p = new ParametrosDaInstalacao("zzcontainer", 54321, "zzpasta");
        PacoteDoAgente pacote = PacoteDoAgente.de("zzversao", "abcdef0", new byte[] {0});
        List<String> modelos = new java.util.ArrayList<>(List.of(lerOsRelease(), lerArquitetura(), lerPermissoes(), lerDocker(), lerPorta(54321),
            lerDisco(), lerInternet(), lerSeAPastaExiste(p), lerDonoDaPasta(p), lerVersaoExtraida(p), lerSha256DoPacote(p, pacote),
            lerSituacaoDoAgente("zzcontainer"), lerSituacaoDoAgente("zzcontainer", DOCKER_COM_SUDO)));
        for (String docker : new String[] {DOCKER_DIRETO, DOCKER_COM_SUDO}) {
            modelos.add(lerContainer(docker, p));
            modelos.add(lerVolume(docker, p));
            modelos.add(lerImagem(docker, p.imagem("zzversao")));
            modelos.add(lerLogsDoContainer(docker, p));
        }
        List<Pattern> padroes = new java.util.ArrayList<>();
        for (String modelo : modelos) {
            padroes.add(Pattern.compile(comoRegex(modelo)));
        }
        return List.copyOf(padroes);
    }

    private static String comoRegex(String modelo) {
        String[][] sentinelas = {{"zzcontainer", RE_NOME}, {"zzpasta", RE_NOME}, {"zzversao", RE_VERSAO}, {"54321", "[0-9]{1,5}"}};
        StringBuilder re = new StringBuilder();
        int i = 0;
        outer:
        while (i < modelo.length()) {
            for (String[] s : sentinelas) {
                if (modelo.startsWith(s[0], i)) {
                    re.append(s[1]);
                    i += s[0].length();
                    continue outer;
                }
            }
            char c = modelo.charAt(i++);
            if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) {
                re.append('\\');
            }
            re.append(c);
        }
        return re.toString();
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
