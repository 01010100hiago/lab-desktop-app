package br.com.labdesktop.projetos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.EntradaRemota;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.arquivos.Escopo;
import br.com.labdesktop.arquivos.FilaDeTransferencias;
import br.com.labdesktop.arquivos.ServicoDeArquivos;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Traz para a raiz dos projetos um projeto que já existe, por três caminhos, todos com o nome já conferido por {@link NomeDoProjeto} e SEM nunca
 * sobrescrever nada (o destino não pode existir):
 * <ul>
 *   <li>{@link #clonar}: {@code git clone} do endereço ({@link UrlDeGit}) na raiz, por comando fixo, sem senha na linha e sem pergunta
 *       ({@code GIT_TERMINAL_PROMPT=0}); se falha, a frase explica o que fazer ({@link ExplicadorDeErrosDeGit});</li>
 *   <li>{@link #importarPasta}: copia uma pasta do computador do usuário pela fila de transferências, SEM {@code node_modules}, SEM {@code .git}
 *       (a menos que marcado) e SEM {@code .env} (a menos que marcado), para uma pasta provisória oculta que só vira o projeto no fim;</li>
 *   <li>{@link #adotar}: move ou copia uma pasta que já está no servidor (fora da raiz) para dentro dela, com o comando exato à mão se o servidor
 *       negar a permissão.</li>
 * </ul>
 * Depois de trazer, completa o que falta sem tocar no que veio: o {@code docker-compose.yml} esqueleto só se o projeto não tem compose, e a rede
 * {@code <nome>_default} (falha de rede vira aviso). Tudo bloqueia: thread de fundo, nunca a EDT; interromper a thread cancela.
 */
public final class ImportadorDeProjeto {

    /** O que deu certo. */
    public record Resultado(String nome, boolean composeCriado, List<String> avisos) {
        public Resultado {
            avisos = List.copyOf(avisos);
        }
    }

    /** Uma pasta do servidor que o usuário pode trazer para os projetos. */
    public record Candidata(String nome, String caminho) { }

    /** Como trazer uma pasta que já está no servidor. */
    public enum Modo { MOVER, COPIAR }

    /** Quem acompanha (a tela): uma frase do que está acontecendo e, no envio, a fração de 0 a 1 ({@code -1} se não se sabe). */
    public interface Observador {
        void andamento(String frase, double fracao);

        Observador NENHUM = (frase, fracao) -> { };
    }

    static final Duration PRAZO_DO_CLONE = Duration.ofMinutes(10);
    private static final Duration PRAZO_CURTO = Duration.ofMinutes(2);
    private static final Duration PRAZO_DE_COPIA = Duration.ofMinutes(10);
    /** Mover entre discos vira cópia mais remoção: pode levar bem mais que uma cópia. */
    private static final Duration PRAZO_DE_MOVER = Duration.ofMinutes(30);
    /** Folga entre o {@code timeout} do servidor e o prazo do app: o servidor desiste primeiro, e o app sabe por quê (status 124). */
    private static final Duration FOLGA = Duration.ofSeconds(30);
    /** O que {@code timeout} devolve quando matou o comando. */
    private static final int STATUS_DO_TIMEOUT = 124;
    /** Pastas de sistema que NUNCA viram projeto (nem as que as contêm). */
    private static final List<String> PASTAS_PROTEGIDAS = List.of("/etc", "/usr", "/var", "/bin", "/sbin",
        "/lib", "/boot", "/dev", "/proc", "/sys");
    private static final Pattern COMPOSE_NA_RAIZ = Pattern.compile("^docker-compose.*\\.ya?ml$");
    private static final Pattern COMPOSE_MODERNO = Pattern.compile("^compose(\\..+)?\\.ya?ml$");
    private static final int PERMISSAO_DO_ARQUIVO = 0644;
    /** Depois de pedir para parar, quanto o app espera a fila responder antes de desistir de esperar. */
    private static final Duration ESPERA_DEPOIS_DE_PARAR = Duration.ofSeconds(15);

    private final AmbienteDeProjetos ambiente;

    public ImportadorDeProjeto(AmbienteDeProjetos ambiente) {
        this.ambiente = ambiente;
    }

    // ---------------------------------------------------------------------------------------------
    // Repositório Git
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code git clone} do repositório na raiz dos projetos, com o nome dado. O clone vai primeiro para uma pasta provisória oculta
     * ({@code .<nome>.clonando}) e só vira o projeto no fim, por um renomear que NUNCA sobrescreve: se outro cliente criou a pasta do projeto no meio
     * do caminho, o app apaga só a provisória (que é dele) e nunca a pasta alheia.
     *
     * @param cancelamento o usuário pediu para parar (a thread também pode ser interrompida)
     */
    public Resultado clonar(UrlDeGit url, String nome, Observador obs, Cancelamento cancelamento) throws FalhaDoProjeto, InterruptedException {
        conferirCancelamento(cancelamento);
        String destino;
        String provisoria;
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            destino = prepararDestino(c, nome);
            provisoria = CaminhoRemoto.juntar(ambiente.raizDosProjetos(), "." + nome + ".clonando");
            apagarSobraDoApp(c, provisoria);
        } catch (ErroSftp e) {
            throw Falhas.de(e, "preparar a pasta do projeto");
        }
        conferirCancelamento(cancelamento);
        obs.andamento("Clonando " + url.texto() + "…", -1);
        ResultadoExec r;
        try {
            r = ambiente.executor().executar(limitado(ComandosDeProjeto.clonar(url, ambiente.paraOSistema().apply(provisoria)), PRAZO_DO_CLONE),
                ComandosDeProjeto.ambienteDoGit(), new byte[0], PRAZO_DO_CLONE.plus(FOLGA));
        } catch (ComandoDemorouException e) {
            limparQuieto(provisoria);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O clone demorou mais de " + PRAZO_DO_CLONE.toMinutes()
                + " minutos e foi parado (a pasta incompleta foi apagada). O repositório é muito grande ou a conexão está lenta.", null, e);
        } catch (InterruptedException e) {
            limparQuieto(provisoria);
            throw e;
        } catch (br.com.labdesktop.local.ProgramaNaoEncontradoException e) {
            limparQuieto(provisoria);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O git não está instalado neste computador. Veja \"Preparar este computador\" (o git aparece como aviso, "
                + "com o jeito de instalar) e tente de novo.", null, e);
        } catch (IOException e) {
            limparQuieto(provisoria);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "Não consegui rodar o git (a conexão caiu ou o servidor não respondeu).", null, e);
        } catch (IllegalArgumentException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O endereço ou o caminho tem algo que o app não passa ao servidor.", null, e);
        }
        if (r.status() != 0) {
            limparQuieto(provisoria);
            if (r.status() == STATUS_DO_TIMEOUT && !ambiente.local()) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O clone demorou mais de " + PRAZO_DO_CLONE.toMinutes()
                    + " minutos e foi parado (a pasta incompleta foi apagada). O repositório é muito grande ou a conexão está lenta.");
            }
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, ExplicadorDeErrosDeGit.explicar(r.stderr(), ambiente.local()));
        }
        conferirCancelamento(cancelamento, provisoria);
        virarProjeto(provisoria, destino);
        return finalizar(nome, destino, obs);
    }

    /** O destino: renomeia a provisória sem sobrescrever; se o nome apareceu no meio do caminho, apaga SÓ a provisória. */
    private void virarProjeto(String provisoria, String destino) throws FalhaDoProjeto {
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            virarProjetoDe(c, provisoria, destino);
        }
    }

    /** O comando com o limite de tempo do servidor ({@code timeout N}), para o processo não seguir sozinho depois que o app desistiu. No computador local não há. */
    private List<String> limitado(List<String> comando, Duration prazo) {
        return ambiente.local() ? comando : ComandosDeProjeto.comLimiteDeTempo(comando, prazo.toSeconds());
    }

    private void conferirCancelamento(Cancelamento cancelamento) throws FalhaDoProjeto {
        conferirCancelamento(cancelamento, null);
    }

    /** Se o usuário já pediu para parar, para antes de começar (e apaga a provisória que esta chamada tenha criado). */
    private void conferirCancelamento(Cancelamento cancelamento, String provisoria) throws FalhaDoProjeto {
        if (cancelamento != null && cancelamento.cancelado()) {
            if (provisoria != null) {
                limparQuieto(provisoria);
            }
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado." + (provisoria == null ? "" : " O que estava pela metade foi apagado."));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pasta do computador
    // ---------------------------------------------------------------------------------------------

    /**
     * Copia {@code pasta} (do computador do usuário) para {@code <raiz>/<nome>}. Vai para uma pasta provisória oculta e só vira o projeto no fim; se
     * falha ou o usuário cancela, a provisória é apagada e nada fica pela metade.
     *
     * @param cancelamento o usuário pediu para parar (a thread também pode ser interrompida)
     */
    public Resultado importarPasta(Path pasta, String nome, FiltroDeEnvio filtro, Observador obs, Cancelamento cancelamento)
        throws FalhaDoProjeto {
        if (pasta == null || !Files.isDirectory(pasta, LinkOption.NOFOLLOW_LINKS)) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Escolha uma pasta (não um arquivo nem um atalho).");
        }
        recusarPastaDosProprioProjetos(pasta);
        conferirCancelamento(cancelamento);
        List<String> avisosDoEnvio = itensQueFicamDeFora(pasta, filtro);
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String raiz = ambiente.raizDosProjetos();
            String destino = prepararDestino(c, nome);
            String provisoria = CaminhoRemoto.juntar(raiz, "." + nome + ".enviando");
            apagarSobraDoApp(c, provisoria); // sobra de um envio que foi interrompido: tem o nome que só o app usa
            String casa = c.caminhoCanonico(".");
            ServicoDeArquivos servico = new ServicoDeArquivos(c, Escopo.servidor("Projetos", raiz, raiz, casa));
            try (FilaDeTransferencias fila = new FilaDeTransferencias(servico, conflito -> new FilaDeTransferencias.Decisao(FilaDeTransferencias.Escolha.CANCELAR, true),
                Runnable::run, 1)) {
                obs.andamento("Copiando " + pasta.getFileName() + "…", 0);
                long id = fila.enviar(List.of(pasta), raiz, filtro::aceita, "." + nome + ".enviando");
                FilaDeTransferencias.Retrato fim = esperar(fila, id, obs, cancelamento);
                if (fim.estado() != FilaDeTransferencias.Estado.CONCLUIDA) {
                    apagarQuieto(c, provisoria);
                    throw falhaDoEnvio(fim, cancelamento);
                }
                obs.andamento("Terminando…", 1);
                try {
                    c.renomear(provisoria, destino);
                } catch (ErroSftp e) {
                    apagarQuieto(c, provisoria);
                    if (e.tipo() == ErroSftp.Tipo.JA_EXISTE) {
                        throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Já existe um projeto com esse nome (a pasta apareceu agora há pouco). "
                            + "Nada foi sobrescrito: escolha outro nome.");
                    }
                    throw Falhas.de(e, "terminar a cópia do projeto");
                }
            }
            Resultado r = finalizar(nome, destino, obs);
            List<String> todos = new ArrayList<>(avisosDoEnvio);
            todos.addAll(r.avisos());
            return new Resultado(r.nome(), r.composeCriado(), todos);
        } catch (ErroSftp e) {
            throw Falhas.de(e, "copiar a pasta para o servidor");
        }
    }

    /**
     * No computador local a pasta de projetos fica na pasta pessoal: escolher a pasta pessoal (ou a do laboratório, ou a dos projetos) copiaria
     * todos os OUTROS projetos para dentro de um. Recusa a pasta que contém a raiz dos projetos e a que está dentro dela.
     */
    private void recusarPastaDosProprioProjetos(Path pasta) throws FalhaDoProjeto {
        if (!ambiente.local()) {
            return; // a pasta é do computador do usuário, e a raiz dos projetos é a do servidor: não se confundem
        }
        try {
            Path escolhida = pasta.toRealPath();
            Path raiz = Path.of(ambiente.paraOSistema().apply(ambiente.raizDosProjetos())).toAbsolutePath().normalize();
            if (Files.exists(raiz)) {
                raiz = raiz.toRealPath();
            }
            if (escolhida.startsWith(raiz) || raiz.startsWith(escolhida)) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Essa pasta é (ou contém) a pasta dos projetos do laboratório: copiá-la levaria os outros "
                    + "projetos junto. Escolha a pasta de um projeto só.");
            }
        } catch (IOException | RuntimeException e) {
            // não deu para conferir (o caminho não existe ainda, nome estranho): segue; a cópia confere de novo cada nome
        }
    }

    /** Os itens que NÃO serão copiados por serem especiais (atalho, socket, arquivo que o servidor não aceita): o usuário é avisado, nunca em silêncio. */
    private static List<String> itensQueFicamDeFora(Path pasta, FiltroDeEnvio filtro) {
        List<String> nomes = new ArrayList<>();
        int[] total = {0};
        try {
            Files.walkFileTree(pasta, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                    if (!dir.equals(pasta) && !filtro.aceita(dir.getFileName().toString(), true)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path arquivo, java.nio.file.attribute.BasicFileAttributes a) {
                    if (filtro.aceita(arquivo.getFileName().toString(), false)) {
                        boolean nomeRuim = false;
                        try {
                            CaminhoRemoto.validarNome(arquivo.getFileName().toString());
                        } catch (ErroSftp e) {
                            nomeRuim = true;
                        }
                        if (!a.isRegularFile() || nomeRuim) {
                            total[0]++;
                            if (nomes.size() < 5) {
                                nomes.add(pasta.relativize(arquivo).toString());
                            }
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path arquivo, IOException e) {
                    total[0]++;
                    if (nomes.size() < 5) {
                        nomes.add(pasta.relativize(arquivo).toString());
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return List.of("Não consegui conferir tudo o que há na pasta; confira se o projeto veio completo.");
        }
        if (total[0] == 0) {
            return List.of();
        }
        return List.of(total[0] + (total[0] == 1 ? " item" : " itens") + " da pasta (atalhos, sockets ou arquivos que o servidor não aceita) NÃO "
            + (total[0] == 1 ? "foi copiado" : "foram copiados") + ": " + String.join(", ", nomes) + (total[0] > nomes.size() ? " e mais " + (total[0] - nomes.size()) : "")
            + ". Confira se o projeto precisa deles.");
    }

    /**
     * Espera o envio terminar. Se o usuário pede para parar (ou a thread é interrompida), manda a fila cancelar e espera o fim, mas SÓ por alguns
     * segundos: se a fila não responde, devolve "cancelada" sem esperar para sempre, e a interrupção continua marcada para quem chamou.
     */
    private static FilaDeTransferencias.Retrato esperar(FilaDeTransferencias fila, long id, Observador obs, Cancelamento cancelamento) {
        boolean pediuParar = false;
        boolean interrompida = false;
        long limiteDepoisDeParar = 0;
        FilaDeTransferencias.Retrato ultimo = null;
        while (true) {
            FilaDeTransferencias.Retrato r = fila.retratos().stream().filter(x -> x.id() == id).findFirst().orElse(null);
            if (r != null) {
                ultimo = r;
            }
            if (r != null && r.estado().terminou()) {
                if (interrompida) {
                    Thread.currentThread().interrupt();
                }
                return r;
            }
            if (r != null) {
                obs.andamento(r.atual().isEmpty() ? "Copiando…" : "Copiando " + r.atual(), r.fracao());
            }
            if (!pediuParar && (interrompida || Thread.currentThread().isInterrupted() || cancelamento.cancelado())) {
                pediuParar = true;
                limiteDepoisDeParar = System.nanoTime() + ESPERA_DEPOIS_DE_PARAR.toNanos();
                fila.cancelar(id); // a transferência para no próximo bloco e se declara cancelada
            }
            if (pediuParar && System.nanoTime() > limiteDepoisDeParar) {
                cancelamento.cancelar();
                if (interrompida || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
                return ultimo != null ? ultimo : new FilaDeTransferencias.Retrato(id, FilaDeTransferencias.Tipo.ENVIO, "", FilaDeTransferencias.Estado.CANCELADA, 0, 0, 0,
                    null, 0, 0, 0, 0, "", "");
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                interrompida = true; // guarda a interrupção: ela volta marcada no fim, e a fila é cancelada já
                cancelamento.cancelar();
            }
        }
    }

    private static FalhaDoProjeto falhaDoEnvio(FilaDeTransferencias.Retrato fim, Cancelamento cancelamento) {
        if (fim.estado() == FilaDeTransferencias.Estado.CANCELADA || cancelamento.cancelado()) {
            return new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado. A cópia incompleta foi apagada.");
        }
        String m = fim.mensagem() == null || fim.mensagem().isBlank() ? "A cópia não terminou." : fim.mensagem();
        return new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, m + " A cópia incompleta foi apagada; nada foi criado.");
    }

    // ---------------------------------------------------------------------------------------------
    // Pasta que já está no servidor
    // ---------------------------------------------------------------------------------------------

    /** As pastas da pasta pessoal do usuário (sem as ocultas e sem a raiz dos projetos) que podem virar projeto, em ordem alfabética. */
    public List<Candidata> candidatasParaAdotar() throws FalhaDoProjeto {
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String casa = c.caminhoCanonico(".");
            List<Candidata> l = new ArrayList<>();
            for (EntradaRemota e : c.listar(casa, 500, n -> !n.startsWith(".")).entradas()) {
                if (e.ehPasta() && !e.link() && !CaminhoRemoto.dentroDe(e.caminho(), ambiente.raizDosProjetos())) {
                    l.add(new Candidata(e.nome(), e.caminho()));
                }
            }
            l.sort(Comparator.comparing(x -> x.nome().toLowerCase(Locale.ROOT)));
            return l;
        } catch (ErroSftp e) {
            throw Falhas.de(e, "listar as pastas do servidor");
        }
    }

    /**
     * Traz a pasta {@code origem} (um caminho absoluto do servidor) para {@code <raiz>/<nome>}. Recusa o que não é pasta, um atalho, a raiz, a pasta
     * pessoal, uma pasta que contém a raiz dos projetos, uma que já está dentro dela e as pastas de sistema e de infraestrutura do lab. Sem permissão, a
     * falha leva o comando exato.
     *
     * <p><b>Copiar</b> vai para uma provisória oculta ({@code .<nome>.copiando}) e só vira o projeto no fim (renomear sem sobrescrever): um destino
     * que apareceu no meio do caminho nunca é apagado. <b>Mover</b> usa {@code mv -nT} direto no destino (um rename quando é o mesmo disco), com limite
     * de tempo no servidor; se o prazo estoura ou o usuário cancela, o resultado é INCERTO ("pode ter terminado"), e o app NÃO limpa nada às cegas.
     */
    public Resultado adotar(String origem, String nome, Modo modo, Observador obs, Cancelamento cancelamento) throws FalhaDoProjeto, InterruptedException {
        if (ambiente.local()) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Neste computador, use \"Pasta do meu computador\": ela copia a pasta para o laboratório.");
        }
        conferirCancelamento(cancelamento);
        String destino;
        String src;
        String provisoria = null;
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            src = CaminhoRemoto.normalizar(origem);
            String casa = c.caminhoCanonico(".");
            String raiz = ambiente.raizDosProjetos();
            if (src.equals(CaminhoRemoto.RAIZ) || CaminhoRemoto.dentroDe(src, casa) || CaminhoRemoto.dentroDe(src, raiz) || CaminhoRemoto.dentroDe(raiz, src)) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Essa pasta não pode virar projeto (é a raiz, a sua pasta pessoal, a dos projetos ou contém a dos projetos).");
            }
            for (String protegida : PASTAS_PROTEGIDAS) {
                if (CaminhoRemoto.dentroDe(protegida, src) || CaminhoRemoto.dentroDe(src, protegida)) {
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Essa pasta é do sistema (" + protegida
                        + "), ou contém uma: o app não a move nem a copia para os projetos.");
                }
            }
            EntradaRemota e = c.estatisticas(src);
            if (e.link() || !e.ehPasta()) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Escolha uma pasta de verdade (não um arquivo nem um atalho).");
            }
            destino = prepararDestino(c, nome);
            if (modo == Modo.COPIAR) {
                provisoria = CaminhoRemoto.juntar(raiz, "." + nome + ".copiando");
                apagarSobraDoApp(c, provisoria);
            }
        } catch (ErroSftp e) {
            throw Falhas.de(e, "conferir a pasta no servidor");
        }
        List<String> comando = modo == Modo.MOVER ? ComandosDeProjeto.mover(src, destino) : ComandosDeProjeto.copiar(src, provisoria);
        String paraCopiar = comandoParaCopiar(modo == Modo.MOVER ? ComandosDeProjeto.mover(src, destino) : ComandosDeProjeto.copiar(src, destino));
        conferirCancelamento(cancelamento, provisoria);
        obs.andamento((modo == Modo.MOVER ? "Movendo " : "Copiando ") + src + "…", -1);
        Duration limite = modo == Modo.MOVER ? PRAZO_DE_MOVER : PRAZO_DE_COPIA;
        ResultadoExec r;
        try {
            r = ambiente.executor().executar(limitado(comando, limite), Map.of(), new byte[0], limite.plus(FOLGA));
        } catch (IllegalArgumentException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O caminho dessa pasta tem espaço ou símbolo que o app não passa ao servidor. Rode você "
                + "mesmo, no servidor, o comando abaixo.", paraCopiar, e);
        } catch (IOException e) {
            if (modo == Modo.MOVER) {
                throw moverIncerto(paraCopiar, e);
            }
            limparQuieto(provisoria);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "Não consegui rodar o comando no servidor (a conexão caiu ou demorou demais).", paraCopiar, e);
        } catch (InterruptedException e) {
            if (modo == Modo.MOVER) {
                Thread.currentThread().interrupt();
                throw moverIncerto(paraCopiar, e);
            }
            limparQuieto(provisoria);
            throw e;
        }
        if (r.status() != 0) {
            if (modo == Modo.COPIAR) {
                limparQuieto(provisoria);
            }
            if (r.status() == STATUS_DO_TIMEOUT) {
                if (modo == Modo.MOVER) {
                    throw moverIncerto(paraCopiar, null);
                }
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "A cópia demorou mais de " + PRAZO_DE_COPIA.toMinutes() + " minutos e foi parada (a cópia incompleta foi apagada).",
                    paraCopiar);
            }
            String m = r.stderr().toLowerCase(Locale.ROOT);
            if (m.contains("permission denied") || m.contains("operation not permitted")) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_PERMISSAO, "O servidor não deixou o seu usuário " + (modo == Modo.MOVER ? "mover" : "copiar")
                    + " essa pasta. Quem tem a permissão pode rodar, no servidor, o comando abaixo.", paraCopiar);
            }
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O servidor não conseguiu " + (modo == Modo.MOVER ? "mover" : "copiar") + " a pasta: "
                + br.com.labdesktop.seguranca.TextoSeguro.truncar(br.com.labdesktop.seguranca.TextoSeguro.limparLinha(
                    br.com.labdesktop.seguranca.Redator.redigir(r.stderr())), 200), paraCopiar);
        }
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            if (modo == Modo.COPIAR) {
                conferirCancelamento(cancelamento, provisoria);
                virarProjetoDe(c, provisoria, destino);
            } else if (!c.existe(destino) || c.existe(src)) {
                // mv -n não move por cima do que existe (e nem sempre avisa): sem destino, ou com a origem ainda lá, nada foi movido
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O servidor terminou o comando, mas a pasta não foi movida (talvez já existisse algo com o nome do "
                    + "projeto). Nada foi apagado: confira a lista e a pasta de origem.", paraCopiar);
            }
        } catch (ErroSftp e) {
            throw Falhas.de(e, "conferir o projeto");
        }
        Resultado fim = finalizar(nome, destino, obs);
        if (modo == Modo.MOVER) {
            List<String> avisos = new ArrayList<>(fim.avisos());
            avisos.add("A pasta saiu de " + src + ". Se algum container usava essa pasta (volume ligado a ela), ele não a acha mais: recrie-o.");
            return new Resultado(fim.nome(), fim.composeCriado(), avisos);
        }
        return fim;
    }

    private static FalhaDoProjeto moverIncerto(String comando, Throwable causa) {
        return new FalhaDoProjeto(FalhaDoProjeto.Tipo.INCERTO, "Não sei se a pasta terminou de ser movida (o prazo acabou, você parou ou a conexão caiu, e o servidor "
            + "pode ter continuado). O app não apagou nada: confira a lista de projetos e a pasta de origem antes de tentar de novo.", comando, causa);
    }

    private void virarProjetoDe(ClienteSftp c, String provisoria, String destino) throws FalhaDoProjeto {
        try {
            c.renomear(provisoria, destino);
        } catch (ErroSftp e) {
            apagarQuieto(c, provisoria);
            if (e.tipo() == ErroSftp.Tipo.JA_EXISTE) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Já existe um projeto com esse nome (a pasta apareceu agora há pouco). "
                    + "Nada foi sobrescrito: escolha outro nome.");
            }
            throw Falhas.de(e, "terminar a cópia do projeto");
        }
    }

    /** O comando como o usuário o colaria num terminal: o que tem caractere fora do seguro vai entre aspas simples. */
    public static String comandoParaCopiar(List<String> argumentos) {
        StringBuilder sb = new StringBuilder();
        for (String a : argumentos) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            boolean limpo = ExecutorDeArgumentos.SEGURO.matcher(a).matches();
            sb.append(limpo ? a : "'" + a.replace("'", "'\\''") + "'");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // O que é comum
    // ---------------------------------------------------------------------------------------------

    /** Confere o nome e o destino (a raiz existe ou é criada; a pasta do projeto NÃO existe) e devolve o caminho do projeto. */
    private String prepararDestino(ClienteSftp c, String nome) throws FalhaDoProjeto, ErroSftp {
        if (!NomeDoProjeto.valido(nome)) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, NomeDoProjeto.fraseDeRecusa(nome));
        }
        if (NomeDoProjeto.reservado(nome)) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Esse nome é reservado pela infraestrutura do lab.");
        }
        String raiz = ambiente.raizDosProjetos();
        Pastas.garantir(c, raiz);
        String pasta = CaminhoRemoto.juntar(raiz, nome);
        if (c.existe(pasta)) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Já existe um projeto com esse nome.");
        }
        return pasta;
    }

    /** Completa o que falta num projeto que já chegou (sem tocar no que veio): o compose esqueleto, só se não tem compose, e a rede. */
    private Resultado finalizar(String nome, String pasta, Observador obs) throws FalhaDoProjeto {
        List<String> avisos = new ArrayList<>();
        boolean composeCriado = false;
        obs.andamento("Conferindo o docker-compose.yml…", -1);
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            boolean temCompose = false;
            boolean temComposeModerno = false;
            for (EntradaRemota e : c.listar(pasta, 2000).entradas()) {
                if (e.ehArquivo() && COMPOSE_NA_RAIZ.matcher(e.nome()).matches()) {
                    temCompose = true;
                } else if (e.ehArquivo() && COMPOSE_MODERNO.matcher(e.nome()).matches()) {
                    temComposeModerno = true;
                } else if (e.ehPasta() && !e.link() && !e.nome().startsWith(".") && c.existe(CaminhoRemoto.juntar(e.caminho(), GabaritoDoProjeto.ARQUIVO_COMPOSE))) {
                    temCompose = true; // o agente também reconhece <subpasta>/docker-compose.yml
                }
            }
            if (!temCompose && temComposeModerno) {
                avisos.add("O projeto usa compose.yaml (ou compose.yml). O Docker o entende, mas o agente do lab só reconhece docker-compose.yml: renomeie se quiser "
                    + "ver os containers dele na lista.");
            } else if (!temCompose) {
                c.gravarAtomico(CaminhoRemoto.juntar(pasta, GabaritoDoProjeto.ARQUIVO_COMPOSE),
                    GabaritoDoProjeto.composeVazio(nome).getBytes(StandardCharsets.UTF_8), PERMISSAO_DO_ARQUIVO);
                composeCriado = true;
                avisos.add("O projeto não tinha docker-compose.yml: criei um vazio (esqueleto), sem tocar em mais nada.");
            }
        } catch (ErroSftp e) {
            avisos.add("O projeto chegou, mas não consegui conferir nem criar o docker-compose.yml (" + e.mensagemSimples() + ").");
        }
        obs.andamento("Criando a rede do projeto…", -1);
        avisos.addAll(redeDoProjeto(nome));
        return new Resultado(nome, composeCriado, avisos);
    }

    private List<String> redeDoProjeto(String nome) {
        String falta = "A rede " + GabaritoDoProjeto.redeDoProjeto(nome) + " não foi criada";
        try {
            ResultadoExec r = ambiente.executor().executar(ComandosDeProjeto.criarRede(nome), Map.of(), new byte[0], PRAZO_CURTO);
            if (r.status() != 0 && !r.stderr().toLowerCase(Locale.ROOT).contains("already exists")) {
                return List.of(falta + " (o Docker recusou). Sem problema: o Docker a cria na primeira vez que o projeto subir.");
            }
            return List.of();
        } catch (IOException e) {
            return List.of(falta + " (o Docker não respondeu). Sem problema: o Docker a cria na primeira vez que o projeto subir.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of(falta + " (interrompido). O Docker a cria na primeira vez que o projeto subir.");
        } catch (IllegalArgumentException e) {
            return List.of(falta + ".");
        }
    }

    /** Apaga a provisória de uma tentativa anterior: o nome (começa com ponto e termina em .clonando, .copiando ou .enviando) é só do app. */
    private static void apagarSobraDoApp(ClienteSftp c, String provisoria) throws ErroSftp {
        if (c.existe(provisoria)) {
            c.apagar(provisoria, true);
        }
    }

    /** Apaga uma provisória DESTA chamada (nunca o destino final). */
    private void limparQuieto(String provisoria) {
        if (provisoria == null) {
            return;
        }
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            apagarQuieto(c, provisoria);
        }
    }

    private static void apagarQuieto(ClienteSftp c, String pasta) {
        try {
            if (c.existe(pasta)) {
                c.apagar(pasta, true);
            }
        } catch (ErroSftp e) {
            // fica lá; a falha principal já foi dita
        }
    }
}
