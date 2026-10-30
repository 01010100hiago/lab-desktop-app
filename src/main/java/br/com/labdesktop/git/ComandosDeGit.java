package br.com.labdesktop.git;

import br.com.labdesktop.git.ComandoGit.Natureza;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TODAS as linhas de git que o app escreve, num lugar só. Cada uma é uma LISTA de argumentos e nasce com a sua {@link Natureza}: ler, escrever numa área temporária fora do projeto,
 * ou escrever no projeto. O que varia (pasta, hash, branch, caminho) passa antes por {@link ValidadorDeGit}; o único texto digitado pelo usuário (o nome de quem assina) entra como texto livre,
 * entre aspas simples por SSH. {@code GitDoProjetoSemEfeitoSozinhoTest} varre este código: nada de {@code add -f}, {@code --force}, {@code push}, {@code remote} nem {@code --global}.
 *
 * <p>A leitura leva sempre {@code --no-pager}, {@code -c core.quotepath=off} (nome com acento sai exato), {@code -c core.fsmonitor=false} (o fsmonitor de um repositório não roda),
 * {@code GIT_TERMINAL_PROMPT=0} (nunca espera senha), {@code GIT_OPTIONAL_LOCKS=0} (ler nunca escreve na pasta) e {@code LC_ALL=C} (as frases do git não mudam com o idioma).
 *
 * <p><b>Um repositório não faz o app rodar um programa dele</b> só com isso e mais as opções dos diffs ({@code --no-ext-diff}, {@code --no-textconv}) quando o Git é 2.40 ou mais novo, porque aí o
 * {@link ExecutorDeGit#comAtributosNeutros} põe {@code --attr-source} apontando para a árvore vazia em toda leitura: o {@code .gitattributes} do projeto deixa de valer e nenhum {@code filter=} (com o
 * {@code [filter "x"] clean = programa} do {@code .git/config}) roda no {@code status} nem no {@code diff}. Num Git mais antigo (2.28 a 2.39) essa opção não existe, então o leitor confere ANTES
 * ({@link #configuracaoQueExecuta}) se o {@code .git/config} tem filtro, fsmonitor, ganchos, comando de SSH, pager, comando de diff ou {@code include}, e, se tem, não roda {@code status} nem {@code diff}.
 */
public final class ComandosDeGit {

    /** Onde a área temporária mora no servidor por SSH (o nome inteiro é conferido por {@link #areaTemporariaValida}). */
    public static final String PREFIXO_DA_AREA = "/tmp/lab-desktop-git-";
    private static final Pattern AREA_REMOTA = Pattern.compile("^/tmp/lab-desktop-git-[0-9a-f]{16}$");
    /** A área temporária deste computador: a pasta temporária do sistema (qualquer uma) mais o nome fixo de 16 letras hexadecimais. */
    private static final Pattern AREA_LOCAL = Pattern.compile("^(?!.*\\.\\.)[^\\x00-\\x1f]+[/\\\\]lab-desktop-git-[0-9a-f]{16}$");

    /** O ambiente de toda execução do git pelo app. */
    private static final Map<String, String> AMBIENTE = Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_OPTIONAL_LOCKS", "0", "LC_ALL", "C");

    /** O hash da árvore vazia num repositório SHA-1 (o padrão); num SHA-256 o leitor pergunta ao git ({@link #hashDaArvoreVazia}). */
    public static final String ARVORE_VAZIA = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

    /** O que no {@code .git/config} faz o git rodar um programa (conferido antes de ler num git que não tem {@code --attr-source}). */
    private static final String REGEXP_DA_CONFIGURACAO_QUE_EXECUTA = "^(filter\\.|core\\.(fsmonitor|hookspath|sshcommand|pager)|diff\\..*\\.(command|textconv)|include)";

    private ComandosDeGit() { }

    // =====================================================================================================
    // Leitura
    // =====================================================================================================

    /** {@code git --version}. */
    public static ComandoGit versao() {
        return new ComandoGit(Natureza.LEITURA, List.of("git", "--version"), Set.of(), AMBIENTE, null);
    }

    /** A pasta está num repositório? {@code --show-prefix} dá o caminho DENTRO dele: vazio quando a pasta é a raiz. */
    public static ComandoGit ehRepositorio(String pasta) {
        return leitura(pasta, false, "rev-parse", "--show-prefix").pronto();
    }

    public static ComandoGit status(String pasta) {
        return leitura(pasta, false, "status", "--porcelain=v2", "--branch", "-z", "--untracked-files=normal").pronto();
    }

    /** O hash da árvore vazia NESTE repositório (SHA-1 ou SHA-256): {@code hash-object -t tree --stdin} com a entrada vazia. Não escreve nada (sem {@code -w}). */
    public static ComandoGit hashDaArvoreVazia(String pasta) {
        return leitura(pasta, false, "hash-object", "-t", "tree", "--stdin").pronto();
    }

    /**
     * O {@code .git/config} DESTE repositório tem algo que faz o git rodar um programa? Imprime os nomes das chaves (nunca os valores); sai com 1 quando não há nenhuma. Só o leitor de um git sem
     * {@code --attr-source} usa.
     */
    public static ComandoGit configuracaoQueExecuta(String pasta) {
        Montagem m = leitura(pasta, false, "config", "--local", "--name-only", "--get-regexp");
        return m.livre(REGEXP_DA_CONFIGURACAO_QUE_EXECUTA).pronto();
    }

    /** O último commit (para a linha "Código"). {@code nonce}: o de {@link InterpretadorDoLog#novoNonce()}, o mesmo que o parser vai receber. */
    public static ComandoGit ultimoCommit(String pasta, String nonce) {
        return leitura(pasta, false, "log", "--max-count=1", "--no-show-signature", "--format=" + InterpretadorDoLog.formato(nonce), "--shortstat").pronto();
    }

    /**
     * Uma fatia do log: {@code quantos} commits depois de pular {@code pular}, a partir de {@code ref} ({@code null}: a cabeça atual; um branch; ou o HASH da cabeça lida na primeira página, que ancora as
     * páginas seguintes: um commit novo no meio não as desloca). A ref vai como texto livre (um branch pode ter acento e símbolos).
     */
    public static ComandoGit commits(String pasta, String ref, int pular, int quantos, String nonce) {
        if (pular < 0 || quantos < 1 || quantos > 500) {
            throw new IllegalArgumentException("Fatia do log fora do razoável.");
        }
        Montagem m = leitura(pasta, false, "log", "--no-show-signature", "--format=" + InterpretadorDoLog.formato(nonce), "--shortstat", "--skip=" + pular, "--max-count=" + quantos);
        if (ref != null) {
            m.livre(ValidadorDeGit.exigirBranch(ref));
        }
        return m.fixo("--").pronto();
    }

    /** O cabeçalho completo de um commit (hash, autor, data, mensagem inteira). */
    public static ComandoGit cabecalhoDoCommit(String pasta, String hash) {
        return leitura(pasta, false, "show", "-s", "--no-show-signature", "--format=" + InterpretadorDoLog.FORMATO_DO_CABECALHO, ValidadorDeGit.exigirHash(hash), "--").pronto();
    }

    /** Os arquivos de um commit com as linhas acrescentadas e tiradas (contra o primeiro pai, se for um merge). */
    public static ComandoGit arquivosDoCommit(String pasta, String hash) {
        return leitura(pasta, false, "show", "--numstat", "-z", "--format=", "-M", "-m", "--first-parent", ValidadorDeGit.exigirHash(hash), "--").pronto();
    }

    /** O diff de UM arquivo de um commit ({@code antigo}: o nome de antes, se foi renomeado, para o git ver o renomear). */
    public static ComandoGit diffDoCommit(String pasta, String hash, String caminho, String antigo) {
        Montagem m = leitura(pasta, true, "show", "--format=", "-M", "-U3", "--no-color", "--no-ext-diff", "--no-textconv", "-m", "--first-parent", ValidadorDeGit.exigirHash(hash), "--");
        m.livre(ValidadorDeGit.exigirCaminhoRelativo(caminho));
        if (antigo != null) {
            m.livre(ValidadorDeGit.exigirCaminhoRelativo(antigo));
        }
        return m.pronto();
    }

    /** Os branches locais, o atual marcado. */
    public static ComandoGit branches(String pasta) {
        Montagem m = leitura(pasta, false, "for-each-ref");
        m.livre("--format=" + InterpretadorDeBranches.FORMATO);
        return m.fixo("--sort=-committerdate").fixo("refs/heads").pronto();
    }

    /** O diff de uma alteração ainda não commitada: da árvore de trabalho ({@code noIndice == false}) ou da área de preparo. */
    public static ComandoGit diffPendente(String pasta, String caminho, String antigo, boolean noIndice) {
        Montagem m = leitura(pasta, true, "diff");
        if (noIndice) {
            m.fixo("--cached");
        }
        m.fixo("--no-color").fixo("--no-ext-diff").fixo("--no-textconv").fixo("-M").fixo("-U3").fixo("--");
        m.livre(ValidadorDeGit.exigirCaminhoRelativo(caminho));
        if (antigo != null && noIndice) {
            m.livre(ValidadorDeGit.exigirCaminhoRelativo(antigo));
        }
        return m.pronto();
    }

    /** O diff de um arquivo novo (não rastreado): contra o vazio. Sai com código 1 quando há diferença. */
    public static ComandoGit diffDeNaoRastreado(String pasta, String caminho) {
        Montagem m = leitura(pasta, false, "diff", "--no-index", "--no-color", "--no-ext-diff", "--no-textconv", "-U3", "--");
        m.fixo("/dev/null");
        m.livre(ValidadorDeGit.exigirCaminhoRelativo(caminho));
        return m.pronto();
    }

    /** Os arquivos de uma PASTA nova (não rastreada), sem seguir links: {@code ls-files -o --exclude-standard}; um repositório de dentro sai como a própria pasta com barra. */
    public static ComandoGit arquivosDeUmaPastaNova(String pasta, String pastaNova) {
        return listarNaoRastreados(pasta, null, ValidadorDeGit.exigirCaminhoRelativo(pastaNova.endsWith("/") ? pastaNova.substring(0, pastaNova.length() - 1) : pastaNova) + "/", false);
    }

    /** Lê um valor da configuração que vale na pasta (global ou local): {@code user.name} ou {@code user.email}. */
    public static ComandoGit lerConfiguracao(String pasta, String chave) {
        if (!chave.equals("user.name") && !chave.equals("user.email")) {
            throw new IllegalArgumentException("Chave de configuração não prevista.");
        }
        return leitura(pasta, false, "config", "--get", chave).pronto();
    }

    // =====================================================================================================
    // A área temporária (escreve SÓ fora do projeto) e a conferência da lista de arquivos
    // =====================================================================================================

    /** Um nome de área temporária no servidor por SSH, de 16 letras hexadecimais, e só em {@code /tmp}: é o único nome que o {@code rm -rf} aceita. */
    public static boolean areaRemotaValida(String caminho) {
        return caminho != null && AREA_REMOTA.matcher(caminho).matches();
    }

    /** O nome de uma área temporária, deste computador ou do servidor (a forma que o app gera; nada além disso). */
    public static boolean areaTemporariaValida(String caminho) {
        return areaRemotaValida(caminho) || (caminho != null && AREA_LOCAL.matcher(caminho).matches());
    }

    /** Cria a pasta da área temporária só para o usuário (700): outro usuário do servidor não lê o que o git põe ali. Só no servidor por SSH (neste computador o app cria a pasta pelo Java). */
    public static ComandoGit criarPastaDaArea(String area) {
        if (!areaRemotaValida(area)) {
            throw new IllegalArgumentException("Área temporária inválida.");
        }
        return new ComandoGit(Natureza.TEMPORARIO, List.of("mkdir", "-m", "700", "--", area), Set.of(), Map.of(), null);
    }

    /** Cria o repositório descartável (sem pasta de trabalho) que só serve para o git aplicar o {@code .gitignore} sem tocar no projeto. */
    public static ComandoGit criarAreaTemporaria(String area) {
        return new ComandoGit(Natureza.TEMPORARIO, List.of("git", "init", "-q", "--bare", area), Set.of(), AMBIENTE, null);
    }

    /** Põe as regras do {@code info/exclude} da área temporária (só por SSH: neste computador o app escreve o arquivo direto). */
    public static ComandoGit escreverExclusoes(String area, byte[] linhas) {
        if (!areaTemporariaValida(area)) {
            throw new IllegalArgumentException("Área temporária inválida.");
        }
        return new ComandoGit(Natureza.TEMPORARIO, List.of("tee", area + "/info/exclude"), Set.of(), Map.of(), linhas);
    }

    /** Apaga a área temporária (só aceita o nome exato que o app gera). */
    public static ComandoGit apagarAreaTemporaria(String area) {
        if (!areaRemotaValida(area)) {
            throw new IllegalArgumentException("Área temporária inválida.");
        }
        return new ComandoGit(Natureza.TEMPORARIO, List.of("rm", "-rf", "--", area), Set.of(), Map.of(), null);
    }

    /**
     * Os arquivos do projeto que um {@code git add} levaria, respeitando todo {@code .gitignore}: {@code ls-files -o --exclude-standard}, com a área temporária no lugar do {@code .git}
     * que ainda não existe. Repositório DENTRO do projeto sai como "pasta/" (nunca descem para dentro dele).
     *
     * @param area a área temporária que faz o papel do {@code .git}; {@code null} quando o repositório já existe
     * @param dentro o prefixo (uma pasta, com barra no fim) para olhar só ali, ou {@code null} para o projeto inteiro
     * @param colapsado pasta inteira ainda não rastreada sai como uma linha só ({@code --directory}): a primeira passada, curta
     */
    public static ComandoGit listarNaoRastreados(String pasta, String area, String dentro, boolean colapsado) {
        ValidadorDeGit.exigirPasta(pasta);
        Montagem m = new Montagem(Natureza.LEITURA, true);
        m.fixo("git").fixo("--no-pager").fixo("-C").fixo(pasta);
        if (area != null) {
            m.fixo("--git-dir=" + area).fixo("--work-tree=.");
        }
        m.fixo("-c").fixo("core.quotepath=off").fixo("-c").fixo("core.fsmonitor=false");
        m.fixo("--literal-pathspecs");
        m.fixo("ls-files").fixo("-o").fixo("--exclude-standard").fixo("-z");
        if (colapsado) {
            m.fixo("--directory").fixo("--no-empty-directory");
        }
        if (dentro != null) {
            m.fixo("--").livre(dentro);
        }
        return m.pronto();
    }

    /** Dos caminhos recebidos na entrada ({@code NUL} entre eles), imprime os que o {@code .gitignore} ignora (a área temporária faz o papel do {@code .git}). */
    public static ComandoGit checarIgnorados(String pasta, String areaOuNula, byte[] caminhos) {
        ValidadorDeGit.exigirPasta(pasta);
        Montagem m = new Montagem(Natureza.LEITURA, false);
        m.fixo("git").fixo("--no-pager").fixo("-C").fixo(pasta);
        if (areaOuNula != null) {
            m.fixo("--git-dir=" + areaOuNula).fixo("--work-tree=.");
        }
        m.fixo("-c").fixo("core.quotepath=off").fixo("-c").fixo("core.fsmonitor=false").fixo("check-ignore").fixo("-z").fixo("--stdin");
        return m.comEntrada(caminhos).pronto();
    }

    /** Os arquivos que o repositório JÁ rastreia e que casam com os padrões de segredo (a conferência do que entrou na área de preparo). */
    public static ComandoGit rastreadosQueParecemSegredo(String pasta) {
        Montagem m = leitura(pasta, false, "ls-files", "--cached", "-z", "--");
        for (String g : SegredosDoProjeto.pathspecsParaOGit()) {
            m.livre(g);
        }
        return m.pronto();
    }

    // =====================================================================================================
    // Escrita (só pelo clique de "Criar repositório")
    // =====================================================================================================

    /**
     * {@code git init -b main}: cria o repositório (o branch inicial se chama main), SEM o modelo de pasta do usuário ({@code --template=} vazio): um {@code init.templateDir} global não copia ganchos para o
     * repositório novo.
     */
    public static ComandoGit iniciar(String pasta) {
        return escrita(pasta, "init", "-q", "-b", "main", "--template=").pronto();
    }

    /** Grava o nome ou o e-mail de quem assina SÓ neste repositório ({@code --local}); nunca na configuração global. */
    public static ComandoGit gravarIdentidadeLocal(String pasta, String chave, String valor) {
        if (!chave.equals("user.name") && !chave.equals("user.email")) {
            throw new IllegalArgumentException("Chave de configuração não prevista.");
        }
        if (chave.equals("user.name") ? !ValidadorDeGit.nomeDeAutorValido(valor) : !ValidadorDeGit.emailValido(valor)) {
            throw new IllegalArgumentException("Valor de identidade inválido.");
        }
        Montagem m = escrita(pasta, "config", "--local", chave);
        return m.livre(valor).pronto();
    }

    /** {@code git add} SÓ dos arquivos da lista (um caminho por entrada, separados por NUL, na entrada do programa). Nunca {@code -f}: o que o {@code .gitignore} ignora não entra. */
    public static ComandoGit adicionarDaLista(String pasta, byte[] caminhosSeparadosPorNul) {
        return escrita(pasta, "add", "--pathspec-from-file=-", "--pathspec-file-nul").comEntrada(caminhosSeparadosPorNul).pronto();
    }

    /** Tira tudo da área de preparo (só mexe no índice; os arquivos ficam como estão). */
    public static ComandoGit limparPreparo(String pasta) {
        return escrita(pasta, "reset", "-q").pronto();
    }

    /**
     * O primeiro commit: a mensagem vai pela entrada; sem ganchos nem assinatura (nada espera uma senha). {@code --no-verify} só pula o {@code pre-commit} e o {@code commit-msg}; o {@code post-commit} e os
     * outros ganchos só deixam de rodar porque {@code core.hooksPath} aponta para {@code /dev/null} neste comando (a pasta não existe, então nenhum gancho vale, nem o de um {@code core.hooksPath} global).
     * Não foi conferido num Windows de verdade.
     */
    public static ComandoGit commitar(String pasta, String mensagem) {
        return escrita(pasta, "-c", "core.hooksPath=/dev/null", "commit", "-q", "--no-verify", "--no-gpg-sign", "-F", "-").comEntrada(mensagem.getBytes(java.nio.charset.StandardCharsets.UTF_8)).pronto();
    }

    /** O hash curto da cabeça (para dizer o que foi criado). */
    public static ComandoGit hashCurtoDaCabeca(String pasta) {
        return leitura(pasta, false, "rev-parse", "--short", "HEAD").pronto();
    }

    // =====================================================================================================
    // A fronteira
    // =====================================================================================================

    private static final Set<String> SUBCOMANDOS_DE_LEITURA = Set.of("rev-parse", "status", "log", "show", "diff", "for-each-ref", "ls-files", "check-ignore", "config-get");

    /**
     * A natureza de um comando pela FORMA dele (o executor de leitura confere isto em vez de confiar no que o objeto diz): o que NÃO é um dos subcomandos de leitura é {@link Natureza#ESCRITA}
     * ({@code init}, {@code add}, {@code commit}, {@code reset}, {@code config --local}, {@code rm}, {@code tee}), com a exceção da área temporária.
     */
    public static Natureza naturezaPelaForma(List<String> argumentos) {
        if (argumentos.isEmpty() || !argumentos.get(0).equals("git")) {
            if (argumentos.size() >= 4 && argumentos.get(0).equals("rm") && argumentos.equals(List.of("rm", "-rf", "--", argumentos.get(argumentos.size() - 1))) && areaRemotaValida(argumentos.get(argumentos.size() - 1))) {
                return Natureza.TEMPORARIO;
            }
            if (argumentos.size() == 5 && argumentos.get(0).equals("mkdir") && argumentos.subList(1, 4).equals(List.of("-m", "700", "--")) && areaRemotaValida(argumentos.get(4))) {
                return Natureza.TEMPORARIO;
            }
            if (argumentos.size() == 2 && argumentos.get(0).equals("tee") && argumentos.get(1).endsWith("/info/exclude") && areaRemotaValida(argumentos.get(1).substring(0, argumentos.get(1).length() - "/info/exclude".length()))) {
                return Natureza.TEMPORARIO;
            }
            return Natureza.ESCRITA;
        }
        if (argumentos.size() == 2 && argumentos.get(1).equals("--version")) {
            return Natureza.LEITURA;
        }
        int i = 1;
        while (i < argumentos.size()) {
            String a = argumentos.get(i);
            if (a.equals("-C") || a.equals("-c")) {
                i += 2;
            } else if (a.equals("--no-pager") || a.equals("--literal-pathspecs") || a.startsWith("--git-dir=") || a.startsWith("--work-tree=") || a.startsWith("--attr-source=")) {
                i++;
            } else {
                break;
            }
        }
        if (i >= argumentos.size()) {
            return Natureza.ESCRITA;
        }
        String sub = argumentos.get(i);
        if (sub.equals("init") && argumentos.size() == i + 4 && argumentos.get(i + 1).equals("-q") && argumentos.get(i + 2).equals("--bare") && areaTemporariaValida(argumentos.get(i + 3))) {
            return Natureza.TEMPORARIO;
        }
        if (sub.equals("hash-object")) {
            return argumentos.subList(i, argumentos.size()).equals(List.of("hash-object", "-t", "tree", "--stdin")) ? Natureza.LEITURA : Natureza.ESCRITA;
        }
        if (sub.equals("config")) {
            if (argumentos.size() == i + 5 && argumentos.subList(i + 1, i + 4).equals(List.of("--local", "--name-only", "--get-regexp"))) {
                return Natureza.LEITURA; // só LISTA os nomes das chaves que fazem o git rodar programa
            }
            return argumentos.contains("--get") && !argumentos.contains("--local") && !argumentos.contains("--global") ? Natureza.LEITURA : Natureza.ESCRITA;
        }
        if (SUBCOMANDOS_DE_LEITURA.contains(sub)) {
            return Natureza.LEITURA;
        }
        return Natureza.ESCRITA;
    }

    // =====================================================================================================
    // Montagem
    // =====================================================================================================

    private static Montagem leitura(String pasta, boolean comCaminhos, String... resto) {
        ValidadorDeGit.exigirPasta(pasta);
        Montagem m = new Montagem(Natureza.LEITURA, comCaminhos);
        m.fixo("git").fixo("--no-pager").fixo("-C").fixo(pasta).fixo("-c").fixo("core.quotepath=off").fixo("-c").fixo("core.fsmonitor=false");
        if (comCaminhos) {
            m.fixo("--literal-pathspecs");
        }
        for (String r : resto) {
            m.fixo(r);
        }
        return m;
    }

    private static Montagem escrita(String pasta, String... resto) {
        ValidadorDeGit.exigirPasta(pasta);
        Montagem m = new Montagem(Natureza.ESCRITA, false);
        // --literal-pathspecs em TODA escrita: um arquivo chamado "*" ou ":!ok.txt" é só um nome, nunca um padrão que pegue (ou tire) outros arquivos
        m.fixo("git").fixo("--no-pager").fixo("--literal-pathspecs").fixo("-C").fixo(pasta).fixo("-c").fixo("core.quotepath=off").fixo("-c").fixo("core.fsmonitor=false");
        for (String r : resto) {
            m.fixo(r);
        }
        return m;
    }

    /** Monta a lista e anota quais posições são texto livre. */
    private static final class Montagem {
        private final Natureza natureza;
        private final List<String> argumentos = new ArrayList<>();
        private final Set<Integer> livres = new LinkedHashSet<>();
        private byte[] entrada = new byte[0];

        Montagem(Natureza natureza, boolean comCaminhos) {
            this.natureza = natureza;
        }

        Montagem fixo(String a) {
            argumentos.add(a);
            return this;
        }

        Montagem livre(String a) {
            livres.add(argumentos.size());
            argumentos.add(a);
            return this;
        }

        Montagem comEntrada(byte[] e) {
            this.entrada = e;
            return this;
        }

        ComandoGit pronto() {
            return new ComandoGit(natureza, argumentos, livres, AMBIENTE, entrada);
        }
    }
}
