package br.com.labdesktop.cofre;

import br.com.labdesktop.cofre.ArquivoCofre.Cabecalho;
import br.com.labdesktop.cofre.ArquivoCofre.ModoDeBackup;
import br.com.labdesktop.cofre.ConteudoEmMemoria.ServidorGuardado;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Json;
import br.com.labdesktop.util.Permissoes;
import br.com.labdesktop.seguranca.Redator;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import javax.crypto.AEADBadTagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O cofre local cifrado por senha-mestra (ARQUITETURA 5). Não conhece rede nem UI.
 *
 * <h2>Ciclo de vida</h2>
 * {@link #criar} (primeira vez) ou {@link #abrir}: derivam a chave com Argon2id (cerca de 1 s, 256 MiB):
 * chame de thread de fundo, ou use {@link #criarEmThreadPropria} e {@link #abrirEmThreadPropria}, que rodam
 * na thread {@code cofre-kdf}. Com o cofre aberto, as leituras são imediatas e as escritas regravam o arquivo
 * de forma atômica antes de retornar. {@link #bloquear()} zera TODOS os segredos em memória (chaves SSH,
 * tokens, chave derivada) e o cofre fica inutilizável.
 *
 * <h2>Garantias</h2>
 * <ul>
 *   <li>Uma escrita que falha no disco não muda nada na memória (o novo conteúdo só passa a valer depois de gravado).</li>
 *   <li>Queda no meio da gravação nunca deixa o cofre ilegível: a abertura tenta {@code cofre.labcofre},
 *       {@code .bak} e {@code .tmp}, nessa ordem, e cada um precisa autenticar no GCM.</li>
 *   <li>O arquivo nunca contém chave nem token em claro; a senha nunca vai a disco.</li>
 * </ul>
 *
 * <p>Seguro entre threads: leituras sem trava (cópia na escrita), escritas serializadas.
 */
public final class CofreDeChaves implements AcessoAoCofre, AutoCloseable {

    /** Tamanho mínimo da senha-mestra na criação (em caracteres). */
    public static final int SENHA_MINIMA = 12;

    private static final Logger LOG = LoggerFactory.getLogger(CofreDeChaves.class);
    private static final SecureRandom ALEATORIO = new SecureRandom();

    /** De qual arquivo o cofre foi aberto. {@link #PRINCIPAL} é o caso normal. */
    public enum ArquivoUsado {
        PRINCIPAL("cofre.labcofre"),
        BACKUP("cofre.labcofre.bak"),
        TEMPORARIO("cofre.labcofre.tmp");

        private final String nome;

        ArquivoUsado(String nome) {
            this.nome = nome;
        }

        public String nomeDoArquivo() {
            return nome;
        }

        Path caminho(CaminhosApp c) {
            return switch (this) {
                case PRINCIPAL -> c.arquivoCofre();
                case BACKUP -> c.arquivoCofreBak();
                case TEMPORARIO -> c.arquivoCofreTmp();
            };
        }
    }

    /** Chave derivada, sal e parâmetros do arquivo atual. A chave é zerada ao bloquear. */
    static final class Chaveiro {
        final byte[] chave;
        final byte[] sal;
        final ParametrosKdf parametros;

        Chaveiro(byte[] chave, byte[] sal, ParametrosKdf parametros) {
            this.chave = chave;
            this.sal = sal;
            this.parametros = parametros;
        }

        void zerar() {
            Zeragem.zerar(chave);
        }

        @Override
        public String toString() {
            return "Chaveiro[redigido]";
        }
    }

    private final CaminhosApp caminhos;
    private final ParametrosKdf alvo;
    private final ArquivoUsado arquivoUsado;
    private final List<String> avisos;
    private final Object trava = new Object();

    /** {@code null} = bloqueado. Troca inteira a cada escrita (cópia na escrita). */
    private volatile ConteudoEmMemoria conteudo;
    private Chaveiro chaveiro; // protegido por trava
    private boolean principalValido; // protegido por trava: o arquivo principal é um cofre autêntico que posso copiar para .bak
    private ExecutorService executorIo; // protegido por trava
    private final List<Runnable> ouvintesDeBloqueio = new ArrayList<>(); // protegido por trava

    private CofreDeChaves(CaminhosApp caminhos, ParametrosKdf alvo, Chaveiro chaveiro, ConteudoEmMemoria conteudo,
                          ArquivoUsado arquivoUsado, List<String> avisos) {
        this.caminhos = caminhos;
        this.alvo = alvo;
        this.chaveiro = chaveiro;
        this.conteudo = conteudo;
        this.arquivoUsado = arquivoUsado;
        this.avisos = avisos;
    }

    // ------------------------------------------------------------------------------------------
    // Criar e abrir
    // ------------------------------------------------------------------------------------------

    /** {@code true} se há algum arquivo de cofre na pasta (principal, {@code .bak} ou {@code .tmp}). */
    public static boolean existe(CaminhosApp caminhos) {
        for (ArquivoUsado a : ArquivoUsado.values()) {
            if (Files.isRegularFile(a.caminho(caminhos))) {
                return true;
            }
        }
        return false;
    }

    /** Cria o cofre com os parâmetros de produção ({@link ParametrosKdf#PADRAO}). */
    public static CofreDeChaves criar(CaminhosApp caminhos, char[] senha) throws CofreException {
        return criar(caminhos, senha, ParametrosKdf.PADRAO);
    }

    /**
     * Cria um cofre vazio e já o grava. Bloqueia ~1 s com os parâmetros de produção: fora da EDT.
     *
     * @throws IllegalArgumentException se a senha tiver menos de {@value #SENHA_MINIMA} caracteres
     * @throws CofreException           se já existir cofre na pasta, ou se o disco falhar
     */
    public static CofreDeChaves criar(CaminhosApp caminhos, char[] senha, ParametrosKdf parametros)
        throws CofreException {
        Objects.requireNonNull(caminhos, "caminhos");
        Objects.requireNonNull(senha, "senha");
        Objects.requireNonNull(parametros, "parametros");
        if (senha.length < SENHA_MINIMA) {
            throw new IllegalArgumentException("A senha-mestra deve ter ao menos " + SENHA_MINIMA + " caracteres.");
        }
        try {
            caminhos.prepararPastas();
        } catch (IOException e) {
            throw new CofreException("Não consegui preparar a pasta do cofre.", e);
        }
        if (existe(caminhos)) {
            throw new CofreException("Já existe um cofre nesta pasta.");
        }
        byte[] sal = new byte[ArquivoCofre.TAMANHO_SAL];
        ALEATORIO.nextBytes(sal);
        byte[] chave = DerivadorArgon2id.derivar(senha, sal, parametros);
        Chaveiro novo = new Chaveiro(chave, sal, parametros);
        CofreDeChaves cofre = new CofreDeChaves(caminhos, parametros, novo, ConteudoEmMemoria.vazio(),
            ArquivoUsado.PRINCIPAL, new ArrayList<>());
        try {
            synchronized (cofre.trava) {
                cofre.persistir(cofre.conteudo, novo, ModoDeBackup.MANTER);
                cofre.principalValido = true;
            }
        } catch (CofreException | RuntimeException e) {
            cofre.bloquear();
            throw e;
        }
        return cofre;
    }

    /** Abre com os parâmetros de produção como alvo de reforço. */
    public static CofreDeChaves abrir(CaminhosApp caminhos, char[] senha) throws CofreException {
        return abrir(caminhos, senha, ParametrosKdf.PADRAO);
    }

    /**
     * Abre o cofre. Tenta {@code cofre.labcofre}, {@code .bak} e {@code .tmp}, nessa ordem; o primeiro que
     * autentica vale, e {@link #arquivoUsado()} e {@link #avisos()} dizem qual foi. Bloqueia ~1 s: fora da EDT.
     * A {@code senha} NÃO é zerada aqui (é do chamador).
     *
     * @param alvo parâmetros que o cofre deve ter; se o arquivo tiver parâmetros mais fracos, é regravado com
     *             estes (sal novo) logo após o desbloqueio, com a senha ainda em memória (ARQUITETURA 5.2)
     * @throws CofreInexistenteException se não houver arquivo
     * @throws SenhaIncorretaException   se nenhum arquivo autenticar (senha errada, ou arquivo adulterado)
     * @throws CofreCorrompidoException  se os arquivos existirem mas não tiverem a forma esperada
     */
    public static CofreDeChaves abrir(CaminhosApp caminhos, char[] senha, ParametrosKdf alvo) throws CofreException {
        Objects.requireNonNull(caminhos, "caminhos");
        Objects.requireNonNull(senha, "senha");
        Objects.requireNonNull(alvo, "alvo");
        List<String> avisos = new ArrayList<>();
        corrigirPermissoes(caminhos, avisos);

        Derivacoes derivacoes = new Derivacoes(senha);
        boolean algum = false;
        boolean autenticacaoFalhou = false;
        CofreException primeiroErro = null;
        try {
            for (ArquivoUsado quem : ArquivoUsado.values()) {
                Path caminho = quem.caminho(caminhos);
                if (!Files.isRegularFile(caminho)) {
                    continue;
                }
                algum = true;
                Aberto aberto;
                try {
                    aberto = tentar(caminho, derivacoes);
                } catch (SenhaIncorretaException e) {
                    autenticacaoFalhou = true;
                    continue;
                } catch (CofreCorrompidoException e) {
                    if (primeiroErro == null) {
                        primeiroErro = e;
                    }
                    continue;
                } catch (IOException e) {
                    if (primeiroErro == null) {
                        primeiroErro = new CofreCorrompidoException("Não consegui ler " + quem.nomeDoArquivo() + ".", e);
                    }
                    continue;
                }
                derivacoes.zerarExceto(aberto.chave);
                Chaveiro chaveiro = new Chaveiro(aberto.chave, aberto.cabecalho.sal(), aberto.cabecalho.parametros());
                CofreDeChaves cofre = new CofreDeChaves(caminhos, alvo, chaveiro, aberto.conteudo, quem, avisos);
                cofre.concluirAbertura(aberto.arquivo, senha);
                return cofre;
            }
        } finally {
            derivacoes.zerarTudo();
        }
        if (!algum) {
            throw new CofreInexistenteException();
        }
        if (autenticacaoFalhou) {
            throw new SenhaIncorretaException();
        }
        throw primeiroErro != null ? primeiroErro : new CofreCorrompidoException("O cofre não pôde ser lido.");
    }

    /**
     * Como {@link #criar(CaminhosApp, char[])}, na thread {@code cofre-kdf}. Copia a senha e zera a cópia ao
     * terminar: o chamador pode zerar o {@code char[]} dele logo em seguida.
     */
    public static CompletableFuture<CofreDeChaves> criarEmThreadPropria(CaminhosApp caminhos, char[] senha) {
        char[] copia = senha.clone();
        return emThreadDoKdf(() -> {
            try {
                return criar(caminhos, copia);
            } finally {
                Zeragem.zerar(copia);
            }
        });
    }

    /** Como {@link #abrir(CaminhosApp, char[])}, na thread {@code cofre-kdf}; copia e zera a senha como acima. */
    public static CompletableFuture<CofreDeChaves> abrirEmThreadPropria(CaminhosApp caminhos, char[] senha) {
        char[] copia = senha.clone();
        return emThreadDoKdf(() -> {
            try {
                return abrir(caminhos, copia);
            } finally {
                Zeragem.zerar(copia);
            }
        });
    }

    /** Roda a tarefa numa thread nova chamada {@code cofre-kdf} (ARQUITETURA 6.6): o Argon2id fica fora de qualquer pool. */
    public static <T> CompletableFuture<T> emThreadDoKdf(Callable<T> tarefa) {
        CompletableFuture<T> futuro = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                futuro.complete(tarefa.call());
            } catch (Throwable t) {
                futuro.completeExceptionally(t);
            }
        }, "cofre-kdf");
        thread.setDaemon(true);
        thread.start();
        return futuro;
    }

    private static void corrigirPermissoes(CaminhosApp caminhos, List<String> avisos) {
        try {
            if (Files.isDirectory(caminhos.pastaDados())
                && Permissoes.corrigirSeAberta(caminhos.pastaDados(), Permissoes.PASTA_PRIVADA)) {
                avisos.add("As permissões da pasta do cofre estavam abertas demais e foram corrigidas (0700).");
            }
            for (ArquivoUsado a : ArquivoUsado.values()) {
                Path p = a.caminho(caminhos);
                if (Files.isRegularFile(p) && Permissoes.corrigirSeAberta(p, Permissoes.ARQUIVO_PRIVADO)) {
                    avisos.add("As permissões de " + a.nomeDoArquivo() + " estavam abertas demais e foram corrigidas (0600).");
                }
            }
        } catch (IOException e) {
            avisos.add("Não consegui conferir as permissões da pasta do cofre.");
        }
    }

    /** Resultado de uma tentativa bem sucedida com um dos arquivos. */
    private static final class Aberto {
        final Cabecalho cabecalho;
        final byte[] chave;
        final ConteudoEmMemoria conteudo;
        final byte[] arquivo;

        Aberto(Cabecalho cabecalho, byte[] chave, ConteudoEmMemoria conteudo, byte[] arquivo) {
            this.cabecalho = cabecalho;
            this.chave = chave;
            this.conteudo = conteudo;
            this.arquivo = arquivo;
        }
    }

    private static Aberto tentar(Path caminho, Derivacoes derivacoes)
        throws IOException, CofreCorrompidoException, SenhaIncorretaException {
        byte[] arquivo = ArquivoCofre.ler(caminho);
        Cabecalho cabecalho = ArquivoCofre.lerCabecalho(arquivo);
        byte[] chave = derivacoes.chavePara(cabecalho);
        byte[] claro;
        try {
            claro = CifraAesGcm.decifrar(chave, cabecalho.nonce(), ArquivoCofre.aad(arquivo), arquivo,
                ArquivoCofre.TAMANHO_CABECALHO, arquivo.length - ArquivoCofre.TAMANHO_CABECALHO);
        } catch (AEADBadTagException e) {
            throw new SenhaIncorretaException();
        } catch (GeneralSecurityException e) {
            throw new CofreCorrompidoException("Falha ao decifrar o cofre.", e);
        }
        try {
            ConteudoCofre json = Json.mapper().readValue(claro, ConteudoCofre.class);
            if (json == null) {
                throw new CofreCorrompidoException("O conteúdo do cofre está vazio.");
            }
            return new Aberto(cabecalho, chave, json.paraMemoria(), arquivo);
        } catch (JsonProcessingException e) {
            throw new CofreCorrompidoException("O conteúdo do cofre não é um JSON válido.", e);
        } finally {
            Zeragem.zerar(claro);
        }
    }

    /**
     * Depois de autenticar: se veio do {@code .bak} ou do {@code .tmp}, regrava o principal; se os parâmetros do
     * KDF são mais fracos que o alvo, reforça.
     */
    private void concluirAbertura(byte[] arquivoLido, char[] senha) {
        synchronized (trava) {
            principalValido = arquivoUsado == ArquivoUsado.PRINCIPAL;
            if (arquivoUsado != ArquivoUsado.PRINCIPAL) {
                try {
                    // MANTER: o principal que está no disco é justamente o que estava ruim, não pode virar o .bak.
                    ArquivoCofre.gravar(caminhos, arquivoLido, ModoDeBackup.MANTER);
                    principalValido = true;
                    avisos.add("O cofre foi recuperado de " + arquivoUsado.nomeDoArquivo() + ".");
                    LOG.warn("Cofre aberto a partir de {}; o arquivo principal foi regravado.", arquivoUsado.nomeDoArquivo());
                } catch (IOException e) {
                    LOG.warn("Cofre aberto a partir de {}, mas o principal não pôde ser regravado: {}",
                        arquivoUsado.nomeDoArquivo(), Redator.redigir(e));
                    avisos.add("O cofre foi recuperado de " + arquivoUsado.nomeDoArquivo()
                        + ", mas não consegui regravar o arquivo principal.");
                }
            }
            if (chaveiro.parametros.maisFracoQue(alvo)) {
                reforcarKdf(senha);
            }
        }
    }

    /** Regrava com sal novo e os parâmetros do alvo. Falhar aqui não impede o uso do cofre: só avisa. */
    private void reforcarKdf(char[] senha) {
        byte[] sal = new byte[ArquivoCofre.TAMANHO_SAL];
        ALEATORIO.nextBytes(sal);
        Chaveiro novo = new Chaveiro(DerivadorArgon2id.derivar(senha, sal, alvo), sal, alvo);
        try {
            // REPLICAR_NOVO: o .bak antigo tem o KDF fraco e não pode ficar para trás.
            persistir(conteudo, novo, ModoDeBackup.REPLICAR_NOVO);
        } catch (CofreException e) {
            novo.zerar();
            avisos.add("Não consegui reforçar os parâmetros de segurança do cofre.");
            LOG.warn("Não consegui reforçar o KDF do cofre: {}", Redator.redigir(e));
            return;
        }
        chaveiro.zerar();
        chaveiro = novo;
        principalValido = true;
        avisos.add("Os parâmetros de segurança do cofre foram reforçados.");
        LOG.info("KDF do cofre reforçado para m={} KiB, t={}, p={}.", alvo.memoriaKib(), alvo.iteracoes(), alvo.paralelismo());
    }

    /** Cache das derivações de uma tentativa de abertura: os três arquivos costumam dividir o mesmo sal. */
    private static final class Derivacoes {
        private final char[] senha;
        private final Map<String, byte[]> cache = new HashMap<>();

        Derivacoes(char[] senha) {
            this.senha = senha;
        }

        byte[] chavePara(Cabecalho c) {
            ParametrosKdf p = c.parametros();
            String id = p.memoriaKib() + "/" + p.iteracoes() + "/" + p.paralelismo() + "/" + HexFormat.of().formatHex(c.sal());
            return cache.computeIfAbsent(id, k -> DerivadorArgon2id.derivar(senha, c.sal(), p));
        }

        void zerarExceto(byte[] manter) {
            for (byte[] k : cache.values()) {
                if (k != manter) {
                    Zeragem.zerar(k);
                }
            }
            cache.clear();
        }

        void zerarTudo() {
            for (byte[] k : cache.values()) {
                Zeragem.zerar(k);
            }
            cache.clear();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------------------------------

    /** {@code true} até {@link #bloquear()}. */
    public boolean aberto() {
        return conteudo != null;
    }

    /** De qual arquivo este cofre foi aberto (ou {@link ArquivoUsado#PRINCIPAL} se acabou de ser criado). */
    public ArquivoUsado arquivoUsado() {
        return arquivoUsado;
    }

    /** Avisos da abertura para o usuário ver: permissões corrigidas, recuperação do backup, KDF reforçado. */
    public List<String> avisos() {
        synchronized (trava) {
            return List.copyOf(avisos);
        }
    }

    private ConteudoEmMemoria exigirAberto() {
        ConteudoEmMemoria c = conteudo;
        if (c == null) {
            throw new CofreBloqueadoException();
        }
        return c;
    }

    /**
     * Bloqueia: zera as chaves SSH, os tokens e a chave derivada, e solta o conteúdo. Idempotente. Depois
     * disso qualquer uso lança {@link CofreBloqueadoException}. Quem segura {@link ChaveSsh} ou
     * {@link TokenAgente} do cofre passa a ter objetos zerados.
     */
    public void bloquear() {
        ExecutorService io;
        List<Runnable> ouvintes;
        synchronized (trava) {
            ConteudoEmMemoria c = conteudo;
            conteudo = null;
            if (c != null) {
                c.zerar();
            }
            if (chaveiro != null) {
                chaveiro.zerar();
            }
            io = executorIo;
            executorIo = null;
            ouvintes = new ArrayList<>(ouvintesDeBloqueio);
            ouvintesDeBloqueio.clear();
        }
        if (io != null) {
            io.shutdown();
        }
        // Fora da trava e depois de zerar: um ouvinte pode chamar de volta o cofre sem travar.
        ouvintes.forEach(CofreDeChaves::executarOuvinte);
    }

    @Override
    public void aoBloquear(Runnable ouvinte) {
        Objects.requireNonNull(ouvinte, "ouvinte");
        boolean jaBloqueado;
        synchronized (trava) {
            jaBloqueado = conteudo == null;
            if (!jaBloqueado) {
                ouvintesDeBloqueio.add(ouvinte);
            }
        }
        if (jaBloqueado) {
            executarOuvinte(ouvinte);
        }
    }

    private static void executarOuvinte(Runnable ouvinte) {
        try {
            ouvinte.run();
        } catch (RuntimeException e) {
            LOG.warn("Ouvinte de bloqueio do cofre falhou: {}", Redator.redigir(e));
        }
    }

    /** Igual a {@link #bloquear()}. */
    @Override
    public void close() {
        bloquear();
    }

    // ------------------------------------------------------------------------------------------
    // Leitura
    // ------------------------------------------------------------------------------------------

    @Override
    public List<ConfigServidor> servidores() {
        return exigirAberto().servidores.stream().map(s -> s.config).toList();
    }

    @Override
    public Optional<ConfigServidor> servidor(String servidorId) {
        return exigirAberto().servidor(servidorId).map(s -> s.config);
    }

    /** As chaves SEM a parte privada. */
    public List<InfoChave> chaves() {
        return exigirAberto().chaves.stream().map(ChaveSsh::info).toList();
    }

    public Optional<InfoChave> chave(String chaveId) {
        return exigirAberto().chave(chaveId).map(ChaveSsh::info);
    }

    /**
     * A chave do cofre com esta impressão digital, se houver. É a chave de verdade que importa, não o id: o mesmo arquivo
     * importado duas vezes cria dois ids com a mesma chave pública, e para o servidor (o {@code authorized_keys}) é UMA
     * chave só.
     */
    public Optional<InfoChave> chaveComAImpressao(String impressao) {
        return exigirAberto().chaves.stream().filter(c -> c.impressao().equals(impressao)).findFirst().map(ChaveSsh::info);
    }

    /** A chave com a parte privada. Pertence ao cofre: não zere. Para entregar ao MINA use {@link #keyPairDoServidor}. */
    public Optional<ChaveSsh> chaveSsh(String chaveId) {
        return exigirAberto().chave(chaveId);
    }

    @Override
    public KeyPair keyPairDoServidor(String servidorId) throws CofreException {
        ConteudoEmMemoria c = exigirAberto();
        ServidorGuardado servidor = c.servidor(servidorId)
            .orElseThrow(() -> new IllegalArgumentException("Servidor desconhecido."));
        ChaveSsh chave = c.chave(servidor.config.chaveId())
            .orElseThrow(() -> new CofreException("O servidor aponta para uma chave que não está no cofre."));
        return chave.paraKeyPair();
    }

    @Override
    public Optional<TokenAgente> tokenDoServidor(String servidorId) {
        return exigirAberto().servidor(servidorId).map(s -> s.token);
    }

    // ------------------------------------------------------------------------------------------
    // Escrita
    // ------------------------------------------------------------------------------------------

    /** Aplica uma mudança ao conteúdo e a grava; só passa a valer na memória se a gravação der certo. */
    private interface Mutacao {
        ConteudoEmMemoria aplicar(ConteudoEmMemoria atual) throws CofreException;
    }

    private void mutar(Mutacao mutacao) throws CofreException {
        synchronized (trava) {
            ConteudoEmMemoria atual = exigirAberto();
            ConteudoEmMemoria novo = mutacao.aplicar(atual);
            if (novo == atual) {
                return; // nada mudou de verdade: não regrava nem gira o .bak
            }
            persistir(novo, chaveiro, principalValido ? ModoDeBackup.ROTACIONAR : ModoDeBackup.MANTER);
            principalValido = true;
            conteudo = novo;
            // Segredos que saíram do conteúdo (chave removida, token trocado) não têm mais responsável: zera.
            for (ChaveSsh c : atual.chaves) {
                if (!novo.temChaveInstancia(c)) {
                    c.zerar();
                }
            }
            for (ServidorGuardado s : atual.servidores) {
                if (s.token != null && !novo.temTokenInstancia(s.token)) {
                    s.token.zerar();
                }
            }
        }
    }

    /**
     * Regrava o arquivo com o estado atual (chave derivada e sal iguais, nonce novo). As escritas já gravam
     * sozinhas; isto existe para forçar um ciclo de gravação completo.
     */
    public void salvar() throws CofreException {
        synchronized (trava) {
            ConteudoEmMemoria atual = exigirAberto();
            persistir(atual, chaveiro, principalValido ? ModoDeBackup.ROTACIONAR : ModoDeBackup.MANTER);
            principalValido = true;
        }
    }

    /** Guarda uma chave nova. O cofre passa a ser o responsável por ela (zera ao remover ou bloquear). */
    public void adicionarChave(ChaveSsh chave) throws CofreException {
        Objects.requireNonNull(chave, "chave");
        if (chave.estaZerado()) {
            throw new IllegalArgumentException("A chave já foi zerada.");
        }
        mutar(atual -> {
            if (atual.chave(chave.id()).isPresent()) {
                throw new IllegalArgumentException("Já existe uma chave com este id.");
            }
            return atual.comChave(chave);
        });
    }

    /** Remove e zera a chave. Recusa se algum servidor ainda a usa. */
    public void removerChave(String chaveId) throws CofreException {
        mutar(atual -> {
            if (atual.chave(chaveId).isEmpty()) {
                throw new IllegalArgumentException("Chave desconhecida.");
            }
            for (ServidorGuardado s : atual.servidores) {
                if (s.config.chaveId().equals(chaveId)) {
                    throw new CofreException("A chave ainda é usada pelo servidor \"" + s.config.nome() + "\".");
                }
            }
            return atual.semChave(chaveId);
        });
    }

    /**
     * Cadastra um servidor. A chave dele ({@code config.chaveId()}) já tem de estar no cofre.
     *
     * @param token token do cliente nativo já registrado no agente, ou {@code null} se ainda não houver; o
     *              cofre passa a ser o responsável por ele
     */
    public void adicionarServidor(ConfigServidor config, TokenAgente token) throws CofreException {
        Objects.requireNonNull(config, "config");
        mutar(atual -> {
            if (atual.servidor(config.id()).isPresent()) {
                throw new IllegalArgumentException("Já existe um servidor com este id.");
            }
            if (atual.chave(config.chaveId()).isEmpty()) {
                throw new IllegalArgumentException("A chave do servidor não está no cofre.");
            }
            return atual.comServidor(new ServidorGuardado(config, token));
        });
    }

    /** Troca a configuração (nome, host, porta, usuário, chave, pins...) mantendo o token. */
    public void atualizarServidor(ConfigServidor config) throws CofreException {
        Objects.requireNonNull(config, "config");
        mutar(atual -> {
            ServidorGuardado antigo = atual.servidor(config.id())
                .orElseThrow(() -> new IllegalArgumentException("Servidor desconhecido."));
            if (atual.chave(config.chaveId()).isEmpty()) {
                throw new IllegalArgumentException("A chave do servidor não está no cofre.");
            }
            if (antigo.config.equals(config)) {
                return atual;
            }
            return atual.substituindoServidor(antigo.comConfig(config));
        });
    }

    /** Remove o servidor e zera o token dele. A chave SSH fica no cofre (remova com {@link #removerChave}). */
    public void removerServidor(String servidorId) throws CofreException {
        removerServidor(servidorId, false);
    }

    /**
     * Remove o servidor e, se pedido e se ninguém mais a usa, a chave dele, TUDO numa gravação só: ou sai tudo ou nada
     * muda, nunca sobra uma chave órfã por uma falha entre duas gravações. Token e chave removidos são zerados na memória.
     * "Ninguém mais a usa" compara a chave pública (a impressão digital), não só o id: dois ids com a mesma chave pública são
     * a mesma chave para o servidor, e a apagada deixaria o outro servidor sem a que o usuário vê no cofre.
     */
    @Override
    public boolean removerServidor(String servidorId, boolean apagarAChaveSeLivre) throws CofreException {
        boolean[] chaveApagada = {false};
        mutar(atual -> {
            ServidorGuardado servidor = atual.servidor(servidorId)
                .orElseThrow(() -> new IllegalArgumentException("Servidor desconhecido."));
            ConteudoEmMemoria restante = atual.semServidor(servidorId);
            ConteudoEmMemoria novo = restante;
            String chaveId = servidor.config.chaveId();
            String impressao = atual.chave(chaveId).map(ChaveSsh::impressao).orElse(null);
            boolean outroUsa = restante.servidores.stream().anyMatch(s -> s.config.chaveId().equals(chaveId)
                || (impressao != null && restante.chave(s.config.chaveId()).map(c -> impressao.equals(c.impressao())).orElse(false)));
            if (apagarAChaveSeLivre && !outroUsa && novo.chave(chaveId).isPresent()) {
                novo = novo.semChave(chaveId);
                chaveApagada[0] = true;
            }
            return novo;
        });
        return chaveApagada[0];
    }

    @Override
    public void alterarServidor(String servidorId, UnaryOperator<ConfigServidor> mudanca) throws CofreException {
        Objects.requireNonNull(mudanca, "mudanca");
        alterarConfig(servidorId, cfg -> {
            ConfigServidor nova = mudanca.apply(cfg);
            if (!nova.id().equals(servidorId)) {
                throw new IllegalArgumentException("A edição não pode mudar o id do servidor.");
            }
            if (chave(nova.chaveId()).isEmpty()) {
                throw new IllegalArgumentException("A chave do servidor não está no cofre.");
            }
            return nova;
        });
    }

    @Override
    public Optional<InfoChave> infoDaChave(String chaveId) {
        return chave(chaveId);
    }

    @Override
    public void gravarPin(String servidorId, Pin pin) throws CofreException {
        Objects.requireNonNull(pin, "pin");
        alterarConfig(servidorId, cfg -> cfg.comPin(pin));
    }

    @Override
    public void limparPins(String servidorId) throws CofreException {
        alterarConfig(servidorId, ConfigServidor::semPins);
    }

    @Override
    public void gravarEstado2fa(String servidorId, Estado2fa estado) throws CofreException {
        Objects.requireNonNull(estado, "estado");
        alterarConfig(servidorId, cfg -> cfg.comEstado2fa(estado));
    }

    @Override
    public void gravarToken(String servidorId, TokenAgente token, String nomeDoDispositivo) throws CofreException {
        Objects.requireNonNull(token, "token");
        if (!ValidadorDeNomes.dispositivoValido(nomeDoDispositivo)) {
            throw new IllegalArgumentException("Nome do dispositivo inválido (a-z, 0-9 e hífen, até 40).");
        }
        mutar(atual -> {
            ServidorGuardado antigo = atual.servidor(servidorId)
                .orElseThrow(() -> new IllegalArgumentException("Servidor desconhecido."));
            ServidorGuardado novo = new ServidorGuardado(antigo.config.comNomeDoDispositivo(nomeDoDispositivo), token);
            return atual.substituindoServidor(novo);
        });
    }

    private void alterarConfig(String servidorId, UnaryOperator<ConfigServidor> mudanca) throws CofreException {
        mutar(atual -> {
            ServidorGuardado antigo = atual.servidor(servidorId)
                .orElseThrow(() -> new IllegalArgumentException("Servidor desconhecido."));
            ConfigServidor nova = mudanca.apply(antigo.config);
            return nova.equals(antigo.config) ? atual : atual.substituindoServidor(antigo.comConfig(nova));
        });
    }

    // ------------------------------------------------------------------------------------------
    // Gravação
    // ------------------------------------------------------------------------------------------

    /** Serializa, cifra com nonce novo e grava na thread {@code cofre-io}. Chamar com {@code trava} tomada. */
    private void persistir(ConteudoEmMemoria c, Chaveiro com, ModoDeBackup modo) throws CofreException {
        byte[] json = null;
        try {
            json = Json.mapper().writeValueAsBytes(ConteudoCofre.de(c));
            byte[] nonce = new byte[ArquivoCofre.TAMANHO_NONCE];
            ALEATORIO.nextBytes(nonce);
            Cabecalho cabecalho = new Cabecalho(com.parametros, com.sal, nonce);
            byte[] aad = ArquivoCofre.aad(ArquivoCofre.montar(cabecalho, new byte[CifraAesGcm.TAMANHO_TAG]));
            byte[] cifrado = CifraAesGcm.cifrar(com.chave, nonce, aad, json);
            byte[] arquivo = ArquivoCofre.montar(cabecalho, cifrado);
            gravarNaThreadDeIo(arquivo, modo);
        } catch (JsonProcessingException | GeneralSecurityException e) {
            throw new CofreException("Não consegui montar o arquivo do cofre.", e);
        } finally {
            Zeragem.zerar(json);
        }
    }

    private void gravarNaThreadDeIo(byte[] arquivo, ModoDeBackup modo) throws CofreException {
        if (executorIo == null) {
            executorIo = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cofre-io");
                t.setDaemon(true);
                return t;
            });
        }
        Future<Void> futuro = executorIo.submit(() -> {
            ArquivoCofre.gravar(caminhos, arquivo, modo);
            return null;
        });
        try {
            futuro.get();
        } catch (ExecutionException e) {
            Throwable causa = e.getCause() != null ? e.getCause() : e;
            throw new CofreException("Não consegui gravar o cofre no disco: " + causa.getMessage(), causa);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CofreException("A gravação do cofre foi interrompida.", e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Para os testes do mesmo pacote
    // ------------------------------------------------------------------------------------------

    /** O chaveiro atual, mesmo depois de bloquear (o teste confere que a chave derivada foi zerada). */
    Chaveiro chaveiroParaTeste() {
        synchronized (trava) {
            return chaveiro;
        }
    }

    @Override
    public String toString() {
        return "CofreDeChaves[aberto=" + aberto() + ", arquivoUsado=" + arquivoUsado + "]";
    }
}
