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
    private static final Pattern COMPOSE_NA_RAIZ = Pattern.compile("^docker-compose.*\\.ya?ml$");
    private static final Pattern COMPOSE_MODERNO = Pattern.compile("^compose(\\..+)?\\.ya?ml$");
    private static final int PERMISSAO_DO_ARQUIVO = 0644;

    private final AmbienteDeProjetos ambiente;

    public ImportadorDeProjeto(AmbienteDeProjetos ambiente) {
        this.ambiente = ambiente;
    }

    // ---------------------------------------------------------------------------------------------
    // Repositório Git
    // ---------------------------------------------------------------------------------------------

    /** {@code git clone} do repositório na raiz dos projetos, com o nome dado. */
    public Resultado clonar(UrlDeGit url, String nome, Observador obs) throws FalhaDoProjeto, InterruptedException {
        String pasta;
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            pasta = prepararDestino(c, nome);
        } catch (ErroSftp e) {
            throw Falhas.de(e, "preparar a pasta do projeto");
        }
        obs.andamento("Clonando " + url.texto() + "…", -1);
        ResultadoExec r;
        try {
            r = ambiente.executor().executar(ComandosDeProjeto.clonar(url, ambiente.paraOSistema().apply(pasta)), ComandosDeProjeto.ambienteDoGit(),
                new byte[0], PRAZO_DO_CLONE);
        } catch (ComandoDemorouException e) {
            limparQuieto(pasta);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O clone demorou mais de " + PRAZO_DO_CLONE.toMinutes()
                + " minutos e foi parado (a pasta incompleta foi apagada). O repositório é muito grande ou a conexão está lenta.", null, e);
        } catch (InterruptedException e) {
            limparQuieto(pasta);
            throw e;
        } catch (IOException e) {
            limparQuieto(pasta);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "Não consegui rodar o git: " + ExplicadorDeErrosDeGit.explicar(e.getMessage()), null, e);
        } catch (IllegalArgumentException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O endereço ou o caminho tem algo que o app não passa ao servidor.", null, e);
        }
        if (r.status() != 0) {
            limparQuieto(pasta);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, ExplicadorDeErrosDeGit.explicar(r.stderr()));
        }
        return finalizar(nome, pasta, obs);
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
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String raiz = ambiente.raizDosProjetos();
            String destino = prepararDestino(c, nome);
            String provisoria = CaminhoRemoto.juntar(raiz, "." + nome + ".enviando");
            if (c.existe(provisoria)) {
                c.apagar(provisoria, true); // sobra de um envio que foi interrompido: tem o nome que só o app usa
            }
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
                    throw Falhas.de(e, "terminar a cópia do projeto");
                }
            }
            return finalizar(nome, destino, obs);
        } catch (ErroSftp e) {
            throw Falhas.de(e, "copiar a pasta para o servidor");
        }
    }

    private static FilaDeTransferencias.Retrato esperar(FilaDeTransferencias fila, long id, Observador obs, Cancelamento cancelamento) {
        boolean pediuParar = false;
        while (true) {
            FilaDeTransferencias.Retrato r = fila.retratos().stream().filter(x -> x.id() == id).findFirst().orElse(null);
            if (r != null && r.estado().terminou()) {
                return r;
            }
            if (r != null) {
                obs.andamento(r.atual().isEmpty() ? "Copiando…" : "Copiando " + r.atual(), r.fracao());
            }
            if (!pediuParar && (Thread.currentThread().isInterrupted() || cancelamento.cancelado())) {
                pediuParar = true;
                fila.cancelar(id); // a transferência para no próximo bloco e se declara cancelada
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                if (!pediuParar) {
                    pediuParar = true;
                    fila.cancelar(id);
                }
                Thread.interrupted(); // continua esperando o fim para limpar a pasta provisória; o resultado é "cancelado"
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
     * pessoal, uma pasta que contém a raiz dos projetos e uma que já está dentro dela. Sem permissão, a falha leva o comando exato.
     */
    public Resultado adotar(String origem, String nome, Modo modo, Observador obs) throws FalhaDoProjeto, InterruptedException {
        if (ambiente.local()) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Neste computador, use \"Pasta do meu computador\": ela copia a pasta para o laboratório.");
        }
        String pasta;
        String src;
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            src = CaminhoRemoto.normalizar(origem);
            String casa = c.caminhoCanonico(".");
            String raiz = ambiente.raizDosProjetos();
            if (src.equals(CaminhoRemoto.RAIZ) || CaminhoRemoto.dentroDe(src, casa) || CaminhoRemoto.dentroDe(src, raiz) || CaminhoRemoto.dentroDe(raiz, src)) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Essa pasta não pode virar projeto (é a raiz, a sua pasta pessoal, a dos projetos ou contém a dos projetos).");
            }
            EntradaRemota e = c.estatisticas(src);
            if (e.link() || !e.ehPasta()) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Escolha uma pasta de verdade (não um arquivo nem um atalho).");
            }
            pasta = prepararDestino(c, nome);
        } catch (ErroSftp e) {
            throw Falhas.de(e, "conferir a pasta no servidor");
        }
        List<String> comando = modo == Modo.MOVER ? ComandosDeProjeto.mover(src, pasta) : ComandosDeProjeto.copiar(src, pasta);
        obs.andamento((modo == Modo.MOVER ? "Movendo " : "Copiando ") + src + "…", -1);
        ResultadoExec r;
        try {
            r = ambiente.executor().executar(comando, Map.of(), new byte[0], modo == Modo.MOVER ? PRAZO_CURTO : PRAZO_DE_COPIA);
        } catch (IllegalArgumentException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O caminho dessa pasta tem espaço ou símbolo que o app não passa ao servidor. Rode você "
                + "mesmo, no servidor, o comando abaixo.", comandoParaCopiar(comando), e);
        } catch (IOException e) {
            limparSeCopia(modo, pasta);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "Não consegui rodar o comando no servidor (a conexão caiu ou demorou demais).", comandoParaCopiar(comando), e);
        } catch (InterruptedException e) {
            limparSeCopia(modo, pasta);
            throw e;
        }
        if (r.status() != 0) {
            limparSeCopia(modo, pasta);
            String m = r.stderr().toLowerCase(Locale.ROOT);
            if (m.contains("permission denied") || m.contains("operation not permitted")) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_PERMISSAO, "O servidor não deixou o seu usuário " + (modo == Modo.MOVER ? "mover" : "copiar")
                    + " essa pasta. Quem tem a permissão pode rodar, no servidor, o comando abaixo.", comandoParaCopiar(comando));
            }
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O servidor não conseguiu " + (modo == Modo.MOVER ? "mover" : "copiar") + " a pasta: "
                + br.com.labdesktop.seguranca.TextoSeguro.truncar(br.com.labdesktop.seguranca.TextoSeguro.limparLinha(
                    br.com.labdesktop.seguranca.Redator.redigir(r.stderr())), 200), comandoParaCopiar(comando));
        }
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            if (!c.existe(pasta)) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O comando terminou, mas a pasta do projeto não apareceu. Confira a lista.", comandoParaCopiar(comando));
            }
        } catch (ErroSftp e) {
            throw Falhas.de(e, "conferir o projeto");
        }
        return finalizar(nome, pasta, obs);
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

    private void limparSeCopia(Modo modo, String pasta) {
        if (modo == Modo.COPIAR) {
            limparQuieto(pasta); // a pasta de destino não existia antes: o que há lá é a cópia incompleta desta chamada
        }
    }

    private void limparQuieto(String pasta) {
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            apagarQuieto(c, pasta);
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
