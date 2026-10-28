package br.com.labdesktop.arquivos;

import br.com.labdesktop.util.Fundo;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fila de envios e downloads: cada pedido (um ou vários arquivos e pastas, recursivo) vira UMA transferência com barra de
 * progresso, velocidade, tempo restante e Cancelar. No máximo {@code simultaneas} rodam ao mesmo tempo; as outras esperam na fila.
 *
 * <p>Regras:
 * <ul>
 *   <li><b>Conflito</b>: destino que já existe pergunta ao {@link Decisor} (Substituir, Pular, Manter os dois, Cancelar) e a
 *       resposta pode valer para o resto da transferência ("aplicar a todos"). Pasta que já existe é só reaproveitada.</li>
 *   <li><b>Um erro num arquivo não derruba o resto</b> (sem permissão para um arquivo); sem conexão derruba tudo, porque
 *       não adianta tentar o próximo. Retomar uma transferência interrompida não existe nesta versão: envia-se de novo.</li>
 *   <li><b>Links não são seguidos</b> dentro das pastas (nem do Mac nem do servidor): um link dentro de uma pasta levaria
 *       arquivos de fora do que o usuário escolheu.</li>
 *   <li>Os retratos ({@link Retrato}) são imutáveis e saem pelo executor de entrega (a EDT), no máximo a cada 150 ms por
 *       andamento; mudança de estado sai na hora.</li>
 * </ul>
 */
public final class FilaDeTransferencias implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FilaDeTransferencias.class);

    /** Acima disto o usuário é avisado antes de começar. */
    public static final long LIMITE_DE_AVISO = 500L * 1024 * 1024;
    private static final long INTERVALO_DE_ANDAMENTO_MS = 150;
    private static final Duration JANELA_DA_VELOCIDADE = Duration.ofSeconds(4);

    public enum Tipo { ENVIO, DOWNLOAD }

    public enum Estado {
        NA_FILA, EM_ANDAMENTO, ESPERANDO_VOCE, CONCLUIDA, COM_PROBLEMAS, FALHOU, CANCELADA;

        public boolean terminou() {
            return this == CONCLUIDA || this == COM_PROBLEMAS || this == FALHOU || this == CANCELADA;
        }
    }

    public enum Escolha { SUBSTITUIR, PULAR, MANTER_OS_DOIS, CANCELAR }

    /** A resposta do usuário ao conflito; {@code aplicarATodos} vale para os próximos conflitos DESTA transferência. */
    public record Decisao(Escolha escolha, boolean aplicarATodos) { }

    /** O que a tela mostra no aviso de "já existe". */
    public record Conflito(Tipo tipo, String nome, String destino, long tamanhoDaOrigem, Instant modificadoDaOrigem,
                           long tamanhoDoDestino, Instant modificadoDoDestino) { }

    /** Quem decide um conflito (a tela). Bloqueia a thread da transferência até o usuário responder. */
    @FunctionalInterface
    public interface Decisor {
        Decisao decidir(Conflito conflito);
    }

    /**
     * O estado de uma transferência, para a tela.
     *
     * @param titulo        "3 arquivos para orbita", "relatorio.md"
     * @param bytesTotal    soma do que há para transferir (0 até o plano ficar pronto)
     * @param bytesPorSegundo velocidade dos últimos segundos; 0 se ainda não dá para dizer
     * @param restante      estimativa do que falta; {@code null} se não dá para dizer
     * @param atual         o arquivo que está andando agora ({@code ""} fora disso)
     * @param mensagem      a frase de resultado ({@code ""} enquanto anda)
     */
    public record Retrato(long id, Tipo tipo, String titulo, Estado estado, long bytesFeitos, long bytesTotal, long bytesPorSegundo,
                          Duration restante, int itensFeitos, int itensTotal, int pulados, int erros, String atual, String mensagem) {

        /** 0 a 1; {@code -1} se o total ainda não se sabe. */
        public double fracao() {
            if (estado == Estado.CONCLUIDA || estado == Estado.COM_PROBLEMAS) {
                return 1.0;
            }
            return bytesTotal <= 0 ? -1 : Math.min(1.0, (double) bytesFeitos / bytesTotal);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Planos
    // ---------------------------------------------------------------------------------------------

    private record ItemDeEnvio(Path local, String remoto, boolean pasta, long tamanho) { }

    private record ItemDeDownload(String remoto, Path local, boolean pasta, long tamanho, Instant modificadoEm) { }

    private static final class Plano<T> {
        final List<T> itens = new ArrayList<>();
        long bytes;
        int ignorados;
        /** Itens que o plano RECUSOU (um link para fora da pasta, por exemplo), cada um com a sua frase: o resto do lote segue. */
        final List<String> recusados = new ArrayList<>();
    }

    // ---------------------------------------------------------------------------------------------
    // Uma transferência em andamento
    // ---------------------------------------------------------------------------------------------

    private final class Trabalho {
        final long id;
        final Tipo tipo;
        final String titulo;
        final Cancelamento cancelamento = new Cancelamento();
        private Estado estado = Estado.NA_FILA;
        private long basePronta; // bytes dos arquivos já concluídos
        private long doArquivoAtual;
        private long total;
        private int itensFeitos;
        private int itensTotal;
        private int pulados;
        private int erros;
        private String atual = "";
        private String mensagem = "";
        private Escolha decisaoParaTodos;
        private final Deque<long[]> amostras = new ArrayDeque<>(); // {nanoTime, bytes}
        private long ultimaPublicacao;

        Trabalho(long id, Tipo tipo, String titulo) {
            this.id = id;
            this.tipo = tipo;
            this.titulo = titulo;
        }

        synchronized Retrato retrato() {
            long feitos = basePronta + doArquivoAtual;
            long vel = velocidade();
            Duration restante = null;
            if (estado == Estado.EM_ANDAMENTO && vel > 0 && total > feitos) {
                restante = Duration.ofSeconds(Math.max(1, (total - feitos) / vel));
            }
            return new Retrato(id, tipo, titulo, estado, feitos, total, estado == Estado.EM_ANDAMENTO ? vel : 0, restante, itensFeitos,
                itensTotal, pulados, erros, estado.terminou() ? "" : atual, mensagem);
        }

        private long velocidade() {
            if (amostras.size() < 2) {
                return 0;
            }
            long[] a = amostras.peekFirst();
            long[] b = amostras.peekLast();
            double s = (b[0] - a[0]) / 1e9;
            return s < 0.3 ? 0 : Math.max(0, (long) ((b[1] - a[1]) / s));
        }

        synchronized void mudar(Estado novo) {
            this.estado = novo;
        }

        synchronized Estado estado() {
            return estado;
        }

        synchronized void definirPlano(long bytes, int itens) {
            this.total = bytes;
            this.itensTotal = itens;
        }

        synchronized void comecouArquivo(String nome) {
            this.atual = nome;
            this.doArquivoAtual = 0;
        }

        synchronized void andamento(long feitosDoArquivo) {
            this.doArquivoAtual = feitosDoArquivo;
            long agora = System.nanoTime();
            amostras.addLast(new long[] {agora, basePronta + feitosDoArquivo});
            while (amostras.size() > 2 && agora - amostras.peekFirst()[0] > JANELA_DA_VELOCIDADE.toNanos()) {
                amostras.removeFirst();
            }
        }

        synchronized void terminouArquivo(long tamanho) {
            this.basePronta += tamanho;
            this.doArquivoAtual = 0;
            this.itensFeitos++;
        }

        synchronized void pulou(long tamanho) {
            this.pulados++;
            this.total = Math.max(0, total - tamanho);
            this.itensFeitos++;
        }

        synchronized void falhou() {
            this.erros++;
            this.itensFeitos++;
        }

        synchronized void contarItem() {
            this.itensFeitos++;
        }

        synchronized boolean devePublicar() {
            long agora = System.currentTimeMillis();
            if (agora - ultimaPublicacao >= INTERVALO_DE_ANDAMENTO_MS) {
                ultimaPublicacao = agora;
                return true;
            }
            return false;
        }

        synchronized void finalizar(Estado novo, String texto) {
            this.estado = novo;
            this.mensagem = texto;
            this.atual = "";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A fila
    // ---------------------------------------------------------------------------------------------

    private final Supplier<ServicoDeArquivos> fornecedorDoServico;
    private final Decisor decisor;
    private final Executor entrega;
    private final Semaphore vagas;
    private final AtomicLong proximoId = new AtomicLong(1);
    private final List<Trabalho> trabalhos = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<Retrato>>> observadores = new CopyOnWriteArrayList<>();
    private volatile boolean fechada;

    /**
     * @param decisor     quem responde aos conflitos (a tela; nos testes, uma resposta fixa)
     * @param entrega     por onde os retratos saem (a EDT); {@code Fundo::naEdt} em produção
     * @param simultaneas quantas transferências andam juntas
     */
    public FilaDeTransferencias(ServicoDeArquivos servico, Decisor decisor, Executor entrega, int simultaneas) {
        this(() -> servico, decisor, entrega, simultaneas);
    }

    /**
     * Com o serviço (e, por ele, a raiz do modo) escolhido NA HORA do pedido: o gerenciador troca de raiz sem perder a fila, e cada
     * transferência segue valendo no modo em que nasceu.
     */
    public FilaDeTransferencias(Supplier<ServicoDeArquivos> fornecedorDoServico, Decisor decisor, Executor entrega, int simultaneas) {
        this.fornecedorDoServico = fornecedorDoServico;
        this.decisor = decisor;
        this.entrega = entrega;
        this.vagas = new Semaphore(Math.max(1, simultaneas));
    }

    public FilaDeTransferencias(ServicoDeArquivos servico, Decisor decisor) {
        this(servico, decisor, Fundo::naEdt, 2);
    }

    /** Passa a receber a lista (na EDT) a cada mudança; o primeiro retrato chega logo. Devolve o que desfaz. */
    public Runnable observar(Consumer<List<Retrato>> ouvinte) {
        observadores.add(ouvinte);
        List<Retrato> agora = retratos();
        entrega.execute(() -> ouvinte.accept(agora));
        return () -> observadores.remove(ouvinte);
    }

    public List<Retrato> retratos() {
        List<Retrato> l = new ArrayList<>(trabalhos.size());
        for (Trabalho t : trabalhos) {
            l.add(t.retrato());
        }
        return List.copyOf(l);
    }

    /** Quantas ainda não terminaram. */
    public int emAberto() {
        return (int) trabalhos.stream().filter(t -> !t.estado().terminou()).count();
    }

    public void cancelar(long id) {
        for (Trabalho t : trabalhos) {
            if (t.id == id) {
                t.cancelamento.cancelar();
            }
        }
    }

    /** Tira da lista as que já terminaram. */
    public void limparConcluidas() {
        trabalhos.removeIf(t -> t.estado().terminou());
        publicar(null, true);
    }

    @Override
    public void close() {
        fechada = true;
        for (Trabalho t : trabalhos) {
            t.cancelamento.cancelar();
        }
    }

    private void publicar(Trabalho quem, boolean forcar) {
        if (!forcar && quem != null && !quem.devePublicar()) {
            return;
        }
        List<Retrato> r = retratos();
        for (Consumer<List<Retrato>> o : observadores) {
            entrega.execute(() -> o.accept(r));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pedidos
    // ---------------------------------------------------------------------------------------------

    /** Soma o tamanho dos arquivos de um conjunto de caminhos do Mac (pastas inclusive), sem seguir links. Para o aviso de 500 MB. */
    public static long somarLocal(List<Path> locais) throws IOException {
        long[] soma = {0};
        for (Path p : locais) {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path arquivo, BasicFileAttributes a) {
                    if (a.isRegularFile()) {
                        soma[0] += a.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path arquivo, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return soma[0];
    }

    /**
     * Enfileira o envio de arquivos e pastas do Mac para uma pasta do servidor. Volta na hora; o resto roda em segundo plano.
     *
     * @return o id da transferência
     */
    public long enviar(List<Path> locais, String pastaRemota) {
        return enviar(locais, pastaRemota, null, null);
    }

    /**
     * Como {@link #enviar(List, String)}, com duas coisas a mais para importar um projeto: um filtro por NOME (o que não passa nem entra no plano;
     * vale para tudo DENTRO das pastas escolhidas, nunca para a pasta escolhida em si) e um nome no destino para a PRIMEIRA pasta ou arquivo
     * (a pasta {@code meu-codigo} do Mac vira {@code meu-projeto} no servidor).
     *
     * @param aceita         recebe o nome (de pasta ou de arquivo) e se é pasta; {@code false} deixa o item de fora (conta como ignorado); {@code null} aceita tudo
     * @param nomeNoDestino  o nome que o primeiro item leva no destino; {@code null} mantém o do Mac
     */
    public long enviar(List<Path> locais, String pastaRemota, java.util.function.BiPredicate<String, Boolean> aceita, String nomeNoDestino) {
        String titulo = tituloDe(locais.size(), locais.isEmpty() ? "" : locais.get(0).getFileName().toString(),
            CaminhoRemoto.nome(pastaRemota).isEmpty() ? "o servidor" : CaminhoRemoto.nome(pastaRemota), "para");
        Trabalho t = novo(Tipo.ENVIO, titulo);
        ServicoDeArquivos servico = fornecedorDoServico.get();
        executar(t, () -> rodarEnvio(t, servico, List.copyOf(locais), pastaRemota, aceita, nomeNoDestino));
        return t.id;
    }

    /** Enfileira o download de itens do servidor (pastas inclusive) para uma pasta do Mac. */
    public long baixar(List<EntradaRemota> remotos, Path pastaLocal) {
        String titulo = tituloDe(remotos.size(), remotos.isEmpty() ? "" : remotos.get(0).nome(), pastaLocal.getFileName() == null
            ? "o Mac" : pastaLocal.getFileName().toString(), "para");
        Trabalho t = novo(Tipo.DOWNLOAD, titulo);
        ServicoDeArquivos servico = fornecedorDoServico.get();
        executar(t, () -> rodarDownload(t, servico, List.copyOf(remotos), pastaLocal));
        return t.id;
    }

    private static String tituloDe(int quantos, String primeiro, String destino, String ligacao) {
        String o = quantos == 1 ? primeiro : quantos + " itens";
        return o + " " + ligacao + " " + destino;
    }

    private Trabalho novo(Tipo tipo, String titulo) {
        Trabalho t = new Trabalho(proximoId.getAndIncrement(), tipo, titulo);
        trabalhos.add(t);
        publicar(t, true);
        return t;
    }

    @FunctionalInterface
    private interface Corpo {
        void rodar() throws ErroSftp, IOException;
    }

    private void executar(Trabalho t, Corpo corpo) {
        Fundo.ioVirtual().execute(() -> {
            try {
                vagas.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                t.finalizar(Estado.CANCELADA, "Cancelada.");
                publicar(t, true);
                return;
            }
            try {
                if (fechada || t.cancelamento.cancelado()) {
                    t.finalizar(Estado.CANCELADA, "Cancelada.");
                    return;
                }
                t.mudar(Estado.EM_ANDAMENTO);
                publicar(t, true);
                corpo.rodar();
            } catch (ErroSftp e) {
                if (e.tipo() == ErroSftp.Tipo.CANCELADO) {
                    t.finalizar(Estado.CANCELADA, "Cancelada.");
                } else {
                    t.finalizar(Estado.FALHOU, e.tipo() == ErroSftp.Tipo.SEM_CONEXAO
                        ? "O servidor desconectou no meio. Envie ou baixe de novo (a retomada ainda não existe)." : e.mensagemSimples());
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Transferência {} falhou: {}", t.id, e.toString());
                t.finalizar(Estado.FALHOU, ErroSftp.de(e, "transferir").mensagemSimples());
            } finally {
                vagas.release();
                publicar(t, true);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Envio
    // ---------------------------------------------------------------------------------------------

    private void rodarEnvio(Trabalho t, ServicoDeArquivos servico, List<Path> locais, String pastaRemota,
                            java.util.function.BiPredicate<String, Boolean> aceita, String nomeNoDestino) throws ErroSftp {
        String destinoReal = servico.resolverDentroDaRaiz(pastaRemota);
        Plano<ItemDeEnvio> plano = planejarEnvio(locais, destinoReal, aceita, nomeNoDestino);
        t.definirPlano(plano.bytes, plano.itens.size());
        publicar(t, true);
        List<String> problemas = new ArrayList<>();
        int enviados = 0;
        for (ItemDeEnvio item : plano.itens) {
            t.cancelamento.conferir();
            try {
                if (item.pasta()) {
                    garantirPastaRemota(servico, item.remoto());
                    t.contarItem();
                } else if (enviarArquivo(t, servico, item)) {
                    enviados++;
                }
            } catch (ErroSftp e) {
                if (e.tipo() == ErroSftp.Tipo.CANCELADO || e.tipo() == ErroSftp.Tipo.SEM_CONEXAO || e.tipo() == ErroSftp.Tipo.SFTP_INDISPONIVEL) {
                    throw e;
                }
                t.falhou();
                problemas.add(CaminhoRemoto.nome(item.remoto()) + ": " + e.mensagemSimples());
            }
            publicar(t, false);
        }
        terminar(t, "enviado", "enviados", enviados, plano.ignorados, problemas);
    }

    private Plano<ItemDeEnvio> planejarEnvio(List<Path> locais, String destinoReal, java.util.function.BiPredicate<String, Boolean> aceita,
                                             String nomeNoDestino) throws ErroSftp {
        Plano<ItemDeEnvio> plano = new Plano<>();
        boolean primeiro = true;
        for (Path raiz : locais) {
            Path nomeLocal = raiz.getFileName();
            if (nomeLocal == null) {
                plano.ignorados++;
                continue;
            }
            String nome;
            try {
                nome = CaminhoRemoto.validarNome(primeiro && nomeNoDestino != null ? nomeNoDestino : nomeLocal.toString());
            } catch (ErroSftp e) {
                plano.ignorados++;
                continue;
            } finally {
                primeiro = false;
            }
            String base = CaminhoRemoto.juntar(destinoReal, nome);
            try {
                if (Files.isDirectory(raiz, LinkOption.NOFOLLOW_LINKS)) {
                    Files.walkFileTree(raiz, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                            if (aceita != null && !dir.equals(raiz) && !aceita.test(dir.getFileName().toString(), true)) {
                                plano.ignorados++;
                                return FileVisitResult.SKIP_SUBTREE; // por exemplo node_modules, ou .git que o usuário não quis levar
                            }
                            String r = remotoDe(base, raiz, dir);
                            if (r == null) {
                                plano.ignorados++;
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            plano.itens.add(new ItemDeEnvio(dir, r, true, 0));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path arquivo, BasicFileAttributes a) {
                            if (aceita != null && !aceita.test(arquivo.getFileName().toString(), false)) {
                                plano.ignorados++; // por exemplo .env, que o usuário não quis levar
                                return FileVisitResult.CONTINUE;
                            }
                            String r = a.isRegularFile() ? remotoDe(base, raiz, arquivo) : null;
                            if (r == null) {
                                plano.ignorados++; // link, socket, nome que o servidor não aceita
                            } else {
                                plano.itens.add(new ItemDeEnvio(arquivo, r, false, a.size()));
                                plano.bytes += a.size();
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path arquivo, IOException e) {
                            plano.ignorados++;
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else if (Files.isRegularFile(raiz)) {
                    long tamanho = Files.size(raiz);
                    plano.itens.add(new ItemDeEnvio(raiz, base, false, tamanho));
                    plano.bytes += tamanho;
                } else {
                    plano.ignorados++;
                }
            } catch (IOException e) {
                plano.ignorados++;
            }
        }
        return plano;
    }

    /** O caminho remoto de um item local: a pasta base mais o caminho relativo, trecho a trecho validado. {@code null} se algum trecho não vale. */
    private static String remotoDe(String base, Path raiz, Path item) {
        String atual = base;
        try {
            for (Path trecho : raiz.relativize(item)) {
                String n = trecho.toString();
                if (n.isEmpty()) {
                    continue;
                }
                atual = CaminhoRemoto.juntar(atual, n);
            }
            return atual;
        } catch (ErroSftp e) {
            return null;
        }
    }

    private void garantirPastaRemota(ServicoDeArquivos servico, String caminho) throws ErroSftp {
        EntradaRemota e = tentarEstatisticas(servico, caminho);
        if (e == null) {
            servico.cliente().criarPasta(caminho);
        } else if (!e.ehPasta()) {
            throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe um arquivo com o nome da pasta \"" + CaminhoRemoto.nome(caminho) + "\".");
        }
    }

    private static EntradaRemota tentarEstatisticas(ServicoDeArquivos servico, String caminho) throws ErroSftp {
        try {
            return servico.cliente().estatisticas(caminho);
        } catch (ErroSftp e) {
            if (e.tipo() == ErroSftp.Tipo.NAO_EXISTE) {
                return null;
            }
            throw e;
        }
    }

    /** @return {@code true} se enviou (e não pulou) */
    private boolean enviarArquivo(Trabalho t, ServicoDeArquivos servico, ItemDeEnvio item) throws ErroSftp {
        String alvo = item.remoto();
        EntradaRemota existente = tentarEstatisticas(servico, alvo);
        if (existente != null) {
            if (existente.ehPasta() && !existente.link()) {
                throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe uma pasta com este nome no destino.");
            }
            Instant local = modificadoLocal(item.local());
            Escolha escolha = decidir(t, new Conflito(Tipo.ENVIO, CaminhoRemoto.nome(alvo), alvo, item.tamanho(), local,
                existente.tamanho(), existente.modificadoEm()));
            switch (escolha) {
                case CANCELAR -> throw ErroSftp.cancelado();
                case PULAR -> {
                    t.pulou(item.tamanho());
                    return false;
                }
                case MANTER_OS_DOIS -> alvo = nomeUnicoRemoto(servico, alvo);
                case SUBSTITUIR -> { }
            }
        }
        t.comecouArquivo(CaminhoRemoto.nome(item.remoto()));
        servico.cliente().enviar(item.local(), alvo, (feitos, total) -> {
            t.andamento(feitos);
            publicar(t, false);
        }, t.cancelamento);
        t.terminouArquivo(item.tamanho());
        return true;
    }

    private static String nomeUnicoRemoto(ServicoDeArquivos servico, String caminho) throws ErroSftp {
        String pasta = CaminhoRemoto.pai(caminho);
        String nome = CaminhoRemoto.nome(caminho);
        for (int i = 2; i < 1000; i++) {
            String candidato = CaminhoRemoto.juntar(pasta, comNumero(nome, i));
            if (tentarEstatisticas(servico, candidato) == null) {
                return candidato;
            }
        }
        throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Não achei um nome livre para manter os dois.");
    }

    /** {@code relatorio.md} e 2 dão {@code relatorio (2).md}; sem extensão, {@code LEIA (2)}. */
    static String comNumero(String nome, int n) {
        int ponto = nome.lastIndexOf('.');
        if (ponto <= 0) {
            return nome + " (" + n + ")";
        }
        return nome.substring(0, ponto) + " (" + n + ")" + nome.substring(ponto);
    }

    private static Instant modificadoLocal(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Download
    // ---------------------------------------------------------------------------------------------

    private void rodarDownload(Trabalho t, ServicoDeArquivos servico, List<EntradaRemota> remotos, Path pastaLocal) throws ErroSftp, IOException {
        Path base = pastaLocal.toAbsolutePath().normalize();
        Files.createDirectories(base);
        Plano<ItemDeDownload> plano = planejarDownload(servico, remotos, base, t.cancelamento);
        t.definirPlano(plano.bytes, plano.itens.size());
        publicar(t, true);
        List<String> problemas = new ArrayList<>(plano.recusados);
        for (int i = 0; i < plano.recusados.size(); i++) {
            t.falhou();
        }
        int baixados = 0;
        for (ItemDeDownload item : plano.itens) {
            t.cancelamento.conferir();
            try {
                if (item.pasta()) {
                    if (Files.exists(item.local(), LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(item.local(), LinkOption.NOFOLLOW_LINKS)) {
                        throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe um arquivo com o nome da pasta neste computador.");
                    }
                    Files.createDirectories(item.local());
                    t.contarItem();
                } else if (baixarArquivo(t, servico, item)) {
                    baixados++;
                }
            } catch (ErroSftp e) {
                if (e.tipo() == ErroSftp.Tipo.CANCELADO || e.tipo() == ErroSftp.Tipo.SEM_CONEXAO || e.tipo() == ErroSftp.Tipo.SFTP_INDISPONIVEL) {
                    throw e;
                }
                t.falhou();
                problemas.add(item.local().getFileName() + ": " + e.mensagemSimples());
            } catch (IOException e) {
                t.falhou();
                problemas.add(item.local().getFileName() + ": não consegui gravar neste computador.");
            }
            publicar(t, false);
        }
        terminar(t, "baixado", "baixados", baixados, plano.ignorados, plano.recusados.size(), problemas);
    }

    private Plano<ItemDeDownload> planejarDownload(ServicoDeArquivos servico, List<EntradaRemota> remotos, Path base, Cancelamento cancelamento) throws ErroSftp {
        Plano<ItemDeDownload> plano = new Plano<>();
        for (EntradaRemota e : remotos) {
            cancelamento.conferir();
            try {
                planejarUm(servico, e, base, plano, cancelamento);
            } catch (ErroSftp falha) {
                if (falha.tipo() == ErroSftp.Tipo.CANCELADO || falha.tipo() == ErroSftp.Tipo.SEM_CONEXAO || falha.tipo() == ErroSftp.Tipo.SFTP_INDISPONIVEL) {
                    throw falha; // o lote inteiro para: não é culpa deste item
                }
                // um item que a cerca recusa (link para fora, nome ruim) é só ELE recusado: o resto do lote segue e o resumo diz quantos
                plano.recusados.add(e.nome() + ": " + (falha.tipo() == ErroSftp.Tipo.FORA_DA_RAIZ
                    ? "é um link que leva para fora da pasta de onde o app baixa (" + servico.escopo().nome() + "). Por segurança, não foi baixado."
                    : falha.mensagemSimples()));
            }
        }
        return plano;
    }

    private void planejarUm(ServicoDeArquivos servico, EntradaRemota e, Path base, Plano<ItemDeDownload> plano, Cancelamento cancelamento) throws ErroSftp {
        Path destino = dentroDe(base, e.nome());
        if (destino == null) {
            plano.ignorados++;
            return;
        }
        if (e.ehPasta() && !e.link()) {
            String real = servico.resolverDentroDaRaiz(e.caminho());
            caminharRemoto(servico, real, destino, plano, cancelamento);
        } else if (e.tipo() == EntradaRemota.Tipo.ARQUIVO) {
            String real = servico.resolverDentroDaRaiz(e.caminho());
            plano.itens.add(new ItemDeDownload(real, destino, false, Math.max(0, e.tamanho()), e.modificadoEm()));
            plano.bytes += Math.max(0, e.tamanho());
        } else {
            plano.ignorados++; // link para pasta, link quebrado, arquivo especial
        }
    }

    private void caminharRemoto(ServicoDeArquivos servico, String pastaRemota, Path pastaLocal, Plano<ItemDeDownload> plano, Cancelamento cancelamento) throws ErroSftp {
        plano.itens.add(new ItemDeDownload(pastaRemota, pastaLocal, true, 0, null));
        ListagemDePasta l = servico.cliente().listar(pastaRemota, Integer.MAX_VALUE);
        for (EntradaRemota filho : l.entradas()) {
            cancelamento.conferir();
            Path destino = dentroDe(pastaLocal, filho.nome());
            if (destino == null) {
                plano.ignorados++;
            } else if (filho.link()) {
                plano.ignorados++; // nunca seguir link dentro de uma pasta
            } else if (filho.ehPasta()) {
                caminharRemoto(servico, filho.caminho(), destino, plano, cancelamento);
            } else if (filho.tipo() == EntradaRemota.Tipo.ARQUIVO) {
                plano.itens.add(new ItemDeDownload(filho.caminho(), destino, false, Math.max(0, filho.tamanho()), filho.modificadoEm()));
                plano.bytes += Math.max(0, filho.tamanho());
            } else {
                plano.ignorados++;
            }
        }
    }

    /** {@code pasta/nome} se o nome é um nome e o resultado fica dentro da pasta; senão {@code null}. */
    static Path dentroDe(Path pasta, String nome) {
        try {
            String n = CaminhoRemoto.validarNome(nome);
            Path p = pasta.resolve(n).normalize();
            return p.startsWith(pasta.normalize()) && p.getParent() != null && p.getParent().equals(pasta.normalize()) ? p : null;
        } catch (ErroSftp | java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    private boolean baixarArquivo(Trabalho t, ServicoDeArquivos servico, ItemDeDownload item) throws ErroSftp, IOException {
        Path alvo = item.local();
        if (Files.exists(alvo, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(alvo, LinkOption.NOFOLLOW_LINKS)) {
                throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe uma pasta com este nome neste computador.");
            }
            Escolha escolha = decidir(t, new Conflito(Tipo.DOWNLOAD, alvo.getFileName().toString(), alvo.toString(), item.tamanho(),
                item.modificadoEm(), Files.size(alvo), modificadoLocal(alvo)));
            switch (escolha) {
                case CANCELAR -> throw ErroSftp.cancelado();
                case PULAR -> {
                    t.pulou(item.tamanho());
                    return false;
                }
                case MANTER_OS_DOIS -> alvo = nomeUnicoLocal(alvo);
                case SUBSTITUIR -> { }
            }
        }
        t.comecouArquivo(alvo.getFileName().toString());
        servico.cliente().baixar(item.remoto(), alvo, (feitos, total) -> {
            t.andamento(feitos);
            publicar(t, false);
        }, t.cancelamento);
        t.terminouArquivo(item.tamanho());
        return true;
    }

    private static Path nomeUnicoLocal(Path alvo) throws ErroSftp {
        String nome = alvo.getFileName().toString();
        for (int i = 2; i < 1000; i++) {
            Path candidato = alvo.resolveSibling(comNumero(nome, i));
            if (!Files.exists(candidato, LinkOption.NOFOLLOW_LINKS)) {
                return candidato;
            }
        }
        throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Não achei um nome livre para manter os dois.");
    }

    // ---------------------------------------------------------------------------------------------
    // Comum
    // ---------------------------------------------------------------------------------------------

    /** A decisão do usuário (ou a que ele mandou valer para todos). Bloqueia até ele responder. */
    private Escolha decidir(Trabalho t, Conflito conflito) throws ErroSftp {
        synchronized (t) {
            if (t.decisaoParaTodos != null) {
                return t.decisaoParaTodos;
            }
        }
        t.mudar(Estado.ESPERANDO_VOCE);
        publicar(t, true);
        Decisao d;
        try {
            d = decisor.decidir(conflito);
        } finally {
            t.mudar(Estado.EM_ANDAMENTO);
            publicar(t, true);
        }
        Escolha e = d == null ? Escolha.CANCELAR : d.escolha();
        if (d != null && d.aplicarATodos() && e != Escolha.CANCELAR) {
            synchronized (t) {
                t.decisaoParaTodos = e;
            }
        }
        return e;
    }

    private void terminar(Trabalho t, String singular, String plural, int feitos, int ignorados, List<String> problemas) {
        terminar(t, singular, plural, feitos, ignorados, 0, problemas);
    }

    private void terminar(Trabalho t, String singular, String plural, int feitos, int ignorados, int recusados, List<String> problemas) {
        StringBuilder sb = new StringBuilder();
        sb.append(feitos).append(feitos == 1 ? " arquivo " + singular : " arquivos " + plural);
        if (recusados > 0) {
            sb.append(", ").append(recusados).append(recusados == 1 ? " recusado" : " recusados");
        }
        int pulados;
        synchronized (t) {
            pulados = t.pulados;
        }
        if (pulados > 0) {
            sb.append(", ").append(pulados).append(pulados == 1 ? " pulado" : " pulados");
        }
        if (ignorados > 0) {
            sb.append(", ").append(ignorados).append(ignorados == 1 ? " link ou item especial ignorado" : " links ou itens especiais ignorados");
        }
        if (!problemas.isEmpty()) {
            sb.append(". Com problema: ").append(String.join("; ", problemas.subList(0, Math.min(3, problemas.size()))));
            if (problemas.size() > 3) {
                sb.append(" e mais ").append(problemas.size() - 3);
            }
        }
        Estado fim = problemas.isEmpty() ? Estado.CONCLUIDA : feitos == 0 && pulados == 0 ? Estado.FALHOU : Estado.COM_PROBLEMAS;
        t.finalizar(fim, sb.toString());
    }
}
