package br.com.labdesktop.git;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.EntradaRemota;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.git.ConferenciaDoRepositorio.ArquivoGrande;
import br.com.labdesktop.git.ConferenciaDoRepositorio.Situacao;
import br.com.labdesktop.git.ExecutorDeGit.Saida;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Criar o repositório Git de um projeto que ainda não tem um (a Fase 1 de {@link OperacoesDeGit}), em duas partes:
 *
 * <ol>
 *   <li>{@link #conferirCriacao}: SÓ LÊ o projeto. Descobre se o Git existe e é novo o bastante, se a pasta já é repositório, quem assina os commits, quais arquivos entrariam (o
 *       {@code .gitignore} do projeto vale, calculado pelo próprio git numa área temporária FORA do projeto), quais passam de 5 MB, quais subpastas já são repositórios e se algum segredo
 *       entraria. Nada é criado no projeto.</li>
 *   <li>{@link #criarRepositorio}: só no clique. {@code git init -b main}, identidade só com {@code --local}, ajuste do {@code .gitignore}, conferência de que os segredos estão ignorados
 *       ({@code git check-ignore}), {@code git add} SÓ da lista conferida (pela entrada, nunca {@code add -f}, nunca {@code add .}), nova conferência do que entrou na área de preparo e o primeiro commit.
 *       Sem {@code --force}, sem {@code push}, sem remoto, sem {@code --global}. Se algo falha no meio, diz o que fez e o que não fez e mostra o comando para desfazer: NUNCA apaga o {@code .git} sozinho.</li>
 * </ol>
 */
public final class CriadorDeRepositorio implements OperacoesDeGit {

    /** Arquivo acima disto (5 MB) fica de fora, a menos que o usuário marque a caixa. */
    public static final long LIMITE_DE_TAMANHO = 5L * 1024 * 1024;

    /** O que a janela diz quando o {@code .gitignore} é um link. */
    static final String FRASE_DO_LINK = "O .gitignore deste projeto é um link para outro arquivo. O app não grava através de link nem troca um link por um arquivo comum. "
        + "Troque o link por um .gitignore de verdade (ou apague o link) e confira de novo.";

    /** A mensagem do primeiro commit. */
    public static final String MENSAGEM_DO_PRIMEIRO_COMMIT = "Primeiro commit";

    private static final Duration PRAZO = Duration.ofSeconds(60);
    private static final Duration PRAZO_DA_ESCRITA = Duration.ofSeconds(180);
    private static final int PROFUNDIDADE_MAXIMA = 4;
    private static final int TETO_DO_GITIGNORE = 1024 * 1024;

    private final AmbienteDeProjetos ambiente;
    private final String pastaNoApp;
    private final String pasta;
    private final ExecutorDeGit paraConferir;
    private final ExecutorDeGit completo;
    private final RepositoriosAbaixo.Buscador buscador;

    /**
     * @param ambiente   onde o projeto mora (o servidor por SSH ou este computador)
     * @param pastaNoApp a pasta do projeto como o app a escreve (sempre com barra)
     */
    public CriadorDeRepositorio(AmbienteDeProjetos ambiente, String pastaNoApp) {
        this.ambiente = ambiente;
        this.pastaNoApp = pastaNoApp;
        this.pasta = ValidadorDeGit.exigirPasta(ambiente.paraOSistema().apply(pastaNoApp));
        this.paraConferir = ExecutorDeGit.paraConferir(ambiente.executor());
        this.completo = ExecutorDeGit.completo(ambiente.executor());
        this.buscador = ambiente.local() ? RepositoriosAbaixo.local() : RepositoriosAbaixo.porFind(this.paraConferir);
    }

    /** Como a conferência e o motor falam de repositórios em subpastas: {@code app/} (e outros). */
    public static String frasesDosRepositoriosAbaixo(List<String> caminhos) {
        String primeiro = caminhos.get(0).startsWith(RepositoriosAbaixo.TEXTO_DO_ILEGIVEL) ? caminhos.get(0) : caminhos.get(0) + "/";
        return caminhos.size() == 1 ? primeiro : primeiro + " (e " + (caminhos.size() - 1) + (caminhos.size() == 2 ? " outro)" : " outros)");
    }

    /** A recusa de criar na pasta principal com repositório abaixo, sem a confirmação explícita. Nada foi feito. */
    static String recusaPorRepositorioAbaixo(List<String> caminhos) {
        return "Já existe repositório Git dentro deste projeto, em " + frasesDosRepositoriosAbaixo(caminhos) + ". Criar um repositório na pasta principal esconderia o histórico dele: o Git trataria "
            + "essa pasta como um repositório dentro de outro. O app só cria assim com a sua confirmação explícita. Nada foi feito.";
    }

    // =====================================================================================================
    // A conferência (só lê)
    // =====================================================================================================

    @Override
    public ConferenciaDoRepositorio conferirCriacao() throws InterruptedException {
        String versao = "";
        try {
            Saida v = paraConferir.rodar(ComandosDeGit.versao(), PRAZO);
            if (!v.ok()) {
                FalhaDoGit f = ClassificadorDeErrosDoGit.classificar(v.status(), v.stderr(), pasta);
                return ConferenciaDoRepositorio.parou(f.tipo() == FalhaDoGit.Tipo.GIT_NAO_INSTALADO ? Situacao.GIT_AUSENTE : Situacao.NAO_CONFERI, f, "");
            }
            versao = v.texto().strip();
            if (ClassificadorDeErrosDoGit.antigoDemais(versao)) {
                return ConferenciaDoRepositorio.parou(Situacao.GIT_ANTIGO, ClassificadorDeErrosDoGit.antigo(versao), versao);
            }
            Saida repo = paraConferir.rodar(ComandosDeGit.ehRepositorio(pasta), PRAZO);
            if (repo.ok()) {
                boolean raiz = repo.texto().strip().isEmpty();
                return ConferenciaDoRepositorio.parou(raiz ? Situacao.JA_E_REPOSITORIO : Situacao.DENTRO_DE_OUTRO,
                    new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, raiz ? "Esta pasta já é um repositório Git." : "Esta pasta está dentro de outro repositório Git (de uma pasta de cima)."), versao);
            }
            FalhaDoGit naoRepo = ClassificadorDeErrosDoGit.classificar(repo.status(), repo.stderr(), pasta);
            if (naoRepo.tipo() != FalhaDoGit.Tipo.NAO_E_REPOSITORIO) {
                return ConferenciaDoRepositorio.parou(Situacao.NAO_CONFERI, naoRepo, versao);
            }
            RepositoriosAbaixo.Achados abaixo;
            try {
                abaixo = buscador.buscar(pasta);
            } catch (ErroDoGit e) {
                return ConferenciaDoRepositorio.parou(Situacao.NAO_CONFERI, new FalhaDoGit(e.falha().tipo(),
                    "Não consegui procurar repositórios nas subpastas, e criar sem saber se já há um esconderia o histórico dele. " + e.falha().frase()), versao);
            }
            if (abaixo.soIlegiveis()) {
                return ConferenciaDoRepositorio.parou(Situacao.NAO_CONFERI, new FalhaDoGit(FalhaDoGit.Tipo.OUTRO,
                    "Há repositório Git em subpasta com nome que o app não mostra com segurança (" + abaixo.ilegiveis() + "). Criar um repositório na pasta principal esconderia o histórico dele, "
                        + "e o app não consegue pedir a sua confirmação sem mostrar o nome. Renomeie a pasta no Terminal e confira de novo."), versao);
            }
            String nome = lerConfiguracao("user.name");
            String email = lerConfiguracao("user.email");
            String gitignore = lerGitignore();
            Lista lista = new Lista();
            ConferenciaDoRepositorio conferencia;
            try (AreaTemporaria area = AreaTemporaria.abrir(ambiente, paraConferir)) {
                area.escreverExclusoes(SegredosDoProjeto.todasAsLinhas());
                listar(area, null, 0, lista);
                boolean protecao = confirmarProtecao(area);
                List<String> arquivos = List.copyOf(lista.arquivos);
                List<ArquivoGrande> grandes = medirOsGrandes(arquivos);
                List<String> segredos = arquivos.stream().filter(SegredosDoProjeto::pareceSegredo).toList();
                conferencia = new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, versao, nome, email, arquivos, grandes, List.copyOf(lista.aninhados), gitignore, segredos,
                    protecao, lista.incompleta, lista.ignoradosPorNome, abaixo.paraAvisos());
            }
            return conferencia;
        } catch (ErroDoGit e) {
            return ConferenciaDoRepositorio.parou(e.falha().tipo() == FalhaDoGit.Tipo.GIT_NAO_INSTALADO ? Situacao.GIT_AUSENTE : Situacao.NAO_CONFERI, e.falha(), versao);
        } catch (ErroSftp e) {
            String frase = e.getMessage().equals(FRASE_DO_LINK) ? FRASE_DO_LINK : "Não consegui ler a pasta do projeto: " + e.getMessage();
            return ConferenciaDoRepositorio.parou(Situacao.NAO_CONFERI, new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, frase), versao);
        }
    }

    /** O valor do git para {@code user.name} ou {@code user.email} nesta pasta (global ou local); vazio se não há. */
    private String lerConfiguracao(String chave) throws ErroDoGit, InterruptedException {
        Saida s = paraConferir.rodar(ComandosDeGit.lerConfiguracao(pasta, chave), PRAZO);
        return s.ok() ? br.com.labdesktop.seguranca.TextoSeguro.limparLinha(s.texto()) : "";
    }

    /** O texto do {@code .gitignore} de hoje ({@code null} se não existe); um {@code .gitignore} que é LINK é recusado: o app não grava através de um link nem troca um link por arquivo. */
    private String lerGitignore() throws ErroSftp {
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String caminho = pastaNoApp + "/.gitignore";
            if (!c.existe(caminho)) {
                return null;
            }
            if (c.estatisticas(caminho).link()) {
                throw new ErroSftp(ErroSftp.Tipo.NOME_INVALIDO, FRASE_DO_LINK);
            }
            return DecodificadorDeTexto.decodificar(c.lerArquivo(caminho, TETO_DO_GITIGNORE)).texto();
        }
    }

    /** O que a listagem achou. */
    private static final class Lista {
        final Set<String> arquivos = new LinkedHashSet<>();
        final Set<String> aninhados = new LinkedHashSet<>();
        boolean incompleta;
        int ignoradosPorNome;
    }

    /**
     * Lista o que um {@code git add} levaria dentro de {@code dentro} (o projeto inteiro se {@code null}). A saída do executor tem teto: se uma pasta tem arquivos demais, pede só as subpastas (uma linha
     * por pasta) e olha cada uma, até {@link #PROFUNDIDADE_MAXIMA} níveis; o que não couber deixa a lista marcada como incompleta (e a criação desligada).
     */
    private void listar(AreaTemporaria area, String dentro, int nivel, Lista l) throws ErroDoGit, InterruptedException {
        Saida s = paraConferir.rodar(ComandosDeGit.listarNaoRastreados(pasta, area == null ? null : area.caminho(), dentro, false), PRAZO);
        if (!s.ok()) {
            throw new ErroDoGit(ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), pasta));
        }
        if (!s.truncado()) {
            guardar(s.texto(), l);
            return;
        }
        if (nivel >= PROFUNDIDADE_MAXIMA) {
            l.incompleta = true;
            return;
        }
        Saida c = paraConferir.rodar(ComandosDeGit.listarNaoRastreados(pasta, area == null ? null : area.caminho(), dentro, true), PRAZO);
        if (!c.ok() || c.truncado()) {
            l.incompleta = true;
            return;
        }
        for (String entrada : c.texto().split("\0")) {
            if (entrada.isEmpty()) {
                continue;
            }
            if (entrada.endsWith("/")) {
                listar(area, entrada, nivel + 1, l);
            } else {
                guardar(entrada + "\0", l);
            }
        }
    }

    private static void guardar(String saida, Lista l) {
        for (String t : saida.split("\0")) {
            if (t.isEmpty()) {
                continue;
            }
            if (t.endsWith("/")) {
                String pasta = t.substring(0, t.length() - 1);
                if (ValidadorDeGit.caminhoRelativoValido(pasta)) {
                    l.aninhados.add(pasta);
                } else {
                    l.ignoradosPorNome++;
                }
            } else if (ValidadorDeGit.caminhoRelativoValido(t)) {
                l.arquivos.add(t);
            } else {
                l.ignoradosPorNome++;
            }
        }
    }

    /** As linhas de segredo funcionam? Todas as amostras que devem ser ignoradas voltam ignoradas, e a que deve ser liberada não. */
    private boolean confirmarProtecao(AreaTemporaria area) throws ErroDoGit, InterruptedException {
        return amostrasDeSegredoObedecem(paraConferir, area.caminho());
    }

    private boolean amostrasDeSegredoObedecem(ExecutorDeGit executor, String area) throws ErroDoGit, InterruptedException {
        List<String> todas = new ArrayList<>(SegredosDoProjeto.amostrasIgnoradas());
        todas.addAll(SegredosDoProjeto.amostrasLiberadas());
        Saida s = executor.rodar(ComandosDeGit.checarIgnorados(pasta, area, String.join("\0", todas).concat("\0").getBytes(StandardCharsets.UTF_8)), PRAZO);
        if (s.status() > 1) { // 0: algum ignorado; 1: nenhum; o resto é erro
            throw new ErroDoGit(ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), pasta));
        }
        Set<String> ignoradas = java.util.Arrays.stream(s.texto().split("\0")).filter(x -> !x.isEmpty()).collect(Collectors.toSet());
        return ignoradas.containsAll(SegredosDoProjeto.amostrasIgnoradas()) && SegredosDoProjeto.amostrasLiberadas().stream().noneMatch(ignoradas::contains);
    }

    /** Os arquivos acima do limite: pede ao cliente de arquivos (SFTP ou disco) o tamanho dos que importam, uma pasta por vez. */
    private List<ArquivoGrande> medirOsGrandes(List<String> arquivos) throws ErroSftp {
        Map<String, Set<String>> porPasta = new LinkedHashMap<>();
        for (String a : arquivos) {
            int i = a.lastIndexOf('/');
            porPasta.computeIfAbsent(i < 0 ? "" : a.substring(0, i), k -> new LinkedHashSet<>()).add(i < 0 ? a : a.substring(i + 1));
        }
        List<ArquivoGrande> grandes = new ArrayList<>();
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            for (Map.Entry<String, Set<String>> e : porPasta.entrySet()) {
                String dir = e.getKey().isEmpty() ? pastaNoApp : pastaNoApp + "/" + e.getKey();
                for (EntradaRemota x : c.listar(dir, 100_000, e.getValue()::contains).entradas()) {
                    if (x.ehArquivo() && x.tamanho() > LIMITE_DE_TAMANHO) {
                        grandes.add(new ArquivoGrande(e.getKey().isEmpty() ? x.nome() : e.getKey() + "/" + x.nome(), x.tamanho()));
                    }
                }
            }
        }
        grandes.sort((x, y) -> Long.compare(y.tamanho(), x.tamanho()));
        return grandes;
    }

    // =====================================================================================================
    // A criação (escreve: só depois do clique)
    // =====================================================================================================

    private static final List<String> PASSOS = List.of("Criar o repositório (git init, branch main)", "Gravar quem assina os commits (só neste repositório)",
        "Ajustar o .gitignore", "Conferir que os segredos estão ignorados", "Escolher os arquivos do primeiro commit", "Preparar os arquivos (git add)",
        "Conferir o que foi preparado", "Fazer o primeiro commit");

    @Override
    public ResultadoDaCriacao criarRepositorio(PedidoDeCriacao pedido, Observador observador, Cancelamento cancelamento) throws InterruptedException {
        ConferenciaDoRepositorio conf = pedido.conferencia();
        if (conf == null || !conf.liberaACriacao()) {
            return recusa("A conferência não libera criar o repositório agora. Nada foi feito.");
        }
        boolean precisaDeIdentidade = !conf.temIdentidade();
        if (precisaDeIdentidade && (!ValidadorDeGit.nomeDeAutorValido(pedido.nome()) || !ValidadorDeGit.emailValido(pedido.email()))) {
            return recusa("Falta o nome e o e-mail de quem assina os commits (ou algum não vale). Nada foi feito.");
        }
        List<String> feito = new ArrayList<>();
        boolean iniciou = false;
        int passo = 0;
        try {
            // 0. A pasta não pode ter virado repositório depois da conferência (criar duas vezes).
            Saida jaTem = completo.rodar(ComandosDeGit.ehRepositorio(pasta), PRAZO);
            if (jaTem.ok()) {
                return recusa("Esta pasta já é um repositório Git (ou está dentro de um). O app não cria por cima. Nada foi feito.");
            }
            FalhaDoGit antes = ClassificadorDeErrosDoGit.classificar(jaTem.status(), jaTem.stderr(), pasta);
            if (antes.tipo() != FalhaDoGit.Tipo.NAO_E_REPOSITORIO) {
                return new ResultadoDaCriacao(false, false, antes.frase() + " Nada foi feito.", List.of(), PASSOS, null, 0, "", antes);
            }

            // 0b. Repositório em subpasta: criar na pasta principal o esconderia. Conferido DE NOVO aqui (a conferência pode ter ficado velha) e só passa com a confirmação explícita.
            RepositoriosAbaixo.Achados abaixoAgora = buscador.buscar(pasta);
            if (!abaixoAgora.vazio() && !pedido.confirmouRepositoriosAbaixo()) {
                return recusa(recusaPorRepositorioAbaixo(abaixoAgora.paraAvisos()));
            }

            try {
                lerGitignore(); // um .gitignore que é link barra a criação ANTES do git init
            } catch (ErroSftp e) {
                return new ResultadoDaCriacao(false, false, e.getMessage() + " Nada foi feito.", List.of(), PASSOS, null, 0, "", new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, e.getMessage()));
            }

            // 1. git init -b main
            observador.passo("Criando o repositório (git init)…");
            Saida init = completo.rodar(ComandosDeGit.iniciar(pasta), PRAZO_DA_ESCRITA);
            if (!init.ok()) {
                return parou(feito, false, passo, ClassificadorDeErrosDoGit.classificar(init.status(), init.stderr(), pasta), null);
            }
            iniciou = true;
            feito.add("Criei o repositório com o branch main (git init).");
            if (cancelou(cancelamento)) {
                return cancelado(feito, passo + 1, iniciou);
            }

            // 2. Identidade, SÓ neste repositório
            passo = 1;
            if (precisaDeIdentidade) {
                observador.passo("Gravando quem assina os commits (só neste repositório)…");
                Saida n = completo.rodar(ComandosDeGit.gravarIdentidadeLocal(pasta, "user.name", pedido.nome()), PRAZO);
                Saida e = n.ok() ? completo.rodar(ComandosDeGit.gravarIdentidadeLocal(pasta, "user.email", pedido.email()), PRAZO) : n;
                if (!e.ok()) {
                    return parou(feito, iniciou, passo, ClassificadorDeErrosDoGit.classificar(e.status(), e.stderr(), pasta), null);
                }
                feito.add("Gravei o nome e o e-mail de quem assina só neste repositório (git config --local).");
            }
            if (cancelou(cancelamento)) {
                return cancelado(feito, passo + 1, iniciou);
            }

            // 3. .gitignore
            passo = 2;
            observador.passo("Ajustando o .gitignore…");
            PlanejadorDoGitignore.Plano plano;
            try (ClienteSftp c = ambiente.abrirArquivos()) {
                String caminho = pastaNoApp + "/.gitignore";
                boolean existe = c.existe(caminho);
                if (existe && c.estatisticas(caminho).link()) {
                    throw new ErroSftp(ErroSftp.Tipo.NOME_INVALIDO, FRASE_DO_LINK);
                }
                String atual = existe ? DecodificadorDeTexto.decodificar(c.lerArquivo(caminho, TETO_DO_GITIGNORE)).texto() : null;
                plano = PlanejadorDoGitignore.planejar(atual, conf.extrasDoGitignore(pedido.incluirGrandes()));
                if (plano.mudaAlgo()) {
                    int permissoes = 0644;
                    if (atual != null) {
                        int p = c.estatisticas(caminho).permissoes();
                        permissoes = p < 0 ? 0644 : p & 07777;
                    }
                    c.gravarAtomico(caminho, plano.textoFinal().getBytes(StandardCharsets.UTF_8), permissoes);
                }
            } catch (ErroSftp e) {
                return parou(feito, iniciou, passo, new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "Não consegui ajustar o .gitignore: " + e.getMessage()), null);
            }
            feito.add(plano.mudaAlgo() ? "Acrescentei " + plano.linhasNovas().size() + " linha(s) ao .gitignore." : "O .gitignore já tinha tudo o que precisava.");
            if (cancelou(cancelamento)) {
                return cancelado(feito, passo + 1, iniciou);
            }

            // 4. Os segredos estão mesmo ignorados? (o git responde, não o app)
            passo = 3;
            observador.passo("Conferindo que os segredos estão ignorados (git check-ignore)…");
            if (!amostrasDeSegredoObedecem(completo, null)) {
                return parou(feito, iniciou, passo, new FalhaDoGit(FalhaDoGit.Tipo.OUTRO,
                    "O Git não confirmou que os arquivos de segredo (como o .env) ficam de fora com o .gitignore deste projeto. Por segurança o app parou antes de adicionar qualquer arquivo."), null);
            }
            feito.add("Conferi com o git que os segredos (.env, chaves, credenciais) estão ignorados.");

            // 5. A lista final: o que o git vê agora, só o que já tinha sido conferido
            passo = 4;
            observador.passo("Escolhendo os arquivos do primeiro commit…");
            Lista fresca = new Lista();
            listar(null, null, 0, fresca);
            Set<String> conferidos = new LinkedHashSet<>(conf.arquivos());
            conferidos.add(".gitignore"); // o arquivo que o app acabou de ajustar também vai no primeiro commit
            List<String> lista = fresca.arquivos.stream().filter(conferidos::contains).toList();
            int novosDepois = (int) fresca.arquivos.stream().filter(a -> !conferidos.contains(a)).count();
            List<String> segredos = lista.stream().filter(SegredosDoProjeto::pareceSegredo).toList();
            if (!segredos.isEmpty()) {
                return parou(feito, iniciou, passo, new FalhaDoGit(FalhaDoGit.Tipo.OUTRO,
                    "Um arquivo com cara de segredo ainda entraria no primeiro commit (" + segredos.get(0) + "). O app parou antes de adicionar qualquer arquivo."), null);
            }
            if (cancelou(cancelamento)) {
                return cancelado(feito, passo + 1, iniciou);
            }
            if (lista.isEmpty()) {
                feito.add("Não havia arquivos para o primeiro commit.");
                return new ResultadoDaCriacao(true, true, "O repositório foi criado, mas a pasta não tinha arquivos para o primeiro commit. Ele está vazio.", feito, List.of(), null, 0, "", null);
            }

            // 6. git add só da lista
            passo = 5;
            observador.passo("Preparando " + lista.size() + " arquivo(s) (git add)…");
            byte[] entrada = (String.join("\0", lista) + "\0").getBytes(StandardCharsets.UTF_8);
            Saida add = completo.rodar(ComandosDeGit.adicionarDaLista(pasta, entrada), PRAZO_DA_ESCRITA);
            if (!add.ok()) {
                String desfazer = desfazerOPreparo();
                return parou(feito, iniciou, passo, ClassificadorDeErrosDoGit.classificar(add.status(), add.stderr(), pasta), desfazer);
            }
            feito.add("Preparei " + lista.size() + " arquivo(s) (git add).");

            // 7. O que foi preparado tem segredo?
            passo = 6;
            observador.passo("Conferindo o que foi preparado…");
            Saida rastreados = completo.rodar(ComandosDeGit.rastreadosQueParecemSegredo(pasta), PRAZO);
            List<String> dentro = rastreados.ok() ? java.util.Arrays.stream(rastreados.texto().split("\0")).filter(x -> !x.isEmpty()).filter(SegredosDoProjeto::pareceSegredo).toList() : List.of();
            if (!rastreados.ok() || !dentro.isEmpty()) {
                String desfazer = desfazerOPreparo();
                return parou(feito, iniciou, passo, new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, rastreados.ok()
                    ? "Um arquivo com cara de segredo foi preparado (" + dentro.get(0) + "). O app não fez o commit."
                    : "Não consegui conferir o que foi preparado. O app não fez o commit."), desfazer);
            }

            // 8. Primeiro commit
            passo = 7;
            observador.passo("Fazendo o primeiro commit…");
            Saida commit = completo.rodar(ComandosDeGit.commitar(pasta, MENSAGEM_DO_PRIMEIRO_COMMIT), PRAZO_DA_ESCRITA);
            if (!commit.ok()) {
                return parou(feito, iniciou, passo, ClassificadorDeErrosDoGit.classificar(commit.status(), commit.stderr(), pasta), null);
            }
            Saida h = completo.rodar(ComandosDeGit.hashCurtoDaCabeca(pasta), PRAZO);
            String curto = h.ok() ? h.texto().strip() : "";
            feito.add("Fiz o primeiro commit" + (curto.isEmpty() ? "" : " (" + curto + ")") + " com " + lista.size() + " arquivo(s).");
            String extra = novosDepois > 0 ? " " + novosDepois + " arquivo(s) apareceram depois da conferência e ficaram de fora." : "";
            return new ResultadoDaCriacao(true, true, "Repositório criado com o primeiro commit: " + lista.size() + " arquivo(s), branch main." + extra, feito, List.of(), null, lista.size(), curto, null);
        } catch (ErroDoGit e) {
            return parou(feito, iniciou, passo, e.falha(), null);
        }
    }

    /**
     * Tira tudo da área de preparo (git reset) e DIZ a verdade sobre o resultado: se o git respondeu que desfez, a frase conta; se não, a frase diz que a preparação continua e dá o comando para o usuário rodar.
     */
    private String desfazerOPreparo() throws InterruptedException {
        try {
            Saida r = completo.rodar(ComandosDeGit.limparPreparo(pasta), PRAZO);
            if (r.ok()) {
                return "O app desfez a preparação dos arquivos.";
            }
        } catch (ErroDoGit e) {
            // cai na frase de baixo
        }
        return "O app NÃO conseguiu desfazer a preparação dos arquivos: eles continuam na área de preparo. Para desfazer, rode no Terminal: git -C " + ClassificadorDeErrosDoGit.paraOShell(pasta) + " reset";
    }

    private static boolean cancelou(Cancelamento c) {
        return c != null && c.cancelado();
    }

    private ResultadoDaCriacao recusa(String mensagem) {
        return new ResultadoDaCriacao(false, false, mensagem, List.of(), PASSOS, null, 0, "", null);
    }

    private ResultadoDaCriacao cancelado(List<String> feito, int proximo, boolean iniciou) {
        return new ResultadoDaCriacao(false, iniciou, "Você cancelou. O repositório ficou pela metade: veja o que foi feito e o que não foi.", feito,
            PASSOS.subList(Math.min(proximo, PASSOS.size()), PASSOS.size()), iniciou ? comandoParaDesfazer() : null, 0, "", null);
    }

    /** Parou no passo {@code passo} (índice de {@link #PASSOS}): diz o que foi feito, o que não foi e como desfazer. */
    private ResultadoDaCriacao parou(List<String> feito, boolean iniciou, int passo, FalhaDoGit falha, String extra) {
        String msg = "Não terminei de criar o repositório. " + falha.frase() + (extra == null ? "" : " " + extra);
        if (iniciou) {
            msg += " O app NÃO apaga nada sozinho: a pasta .git que ele criou continua lá. Se quiser voltar a como estava, rode o comando abaixo.";
        } else {
            msg += " Nada foi criado.";
        }
        return new ResultadoDaCriacao(false, iniciou, msg, feito, PASSOS.subList(Math.min(passo, PASSOS.size()), PASSOS.size()), iniciou ? comandoParaDesfazer() : null, 0, "", falha);
    }

    /** O comando que o USUÁRIO roda para tirar o {@code .git} que o app criou. O app nunca o executa. */
    String comandoParaDesfazer() {
        boolean windows = pasta.length() > 2 && pasta.charAt(1) == ':';
        if (windows) {
            return "rmdir /s /q \"" + pasta + "\\.git\"";
        }
        return "rm -rf " + ClassificadorDeErrosDoGit.paraOShell(pasta + "/.git");
    }
}
