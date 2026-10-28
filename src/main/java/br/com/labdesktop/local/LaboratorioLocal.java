package br.com.labdesktop.local;

import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.instalador.EstadoDoPasso;
import br.com.labdesktop.instalador.GanchosDaInstalacao;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.Passo;
import br.com.labdesktop.instalador.ResultadoDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaInstalacao.Desfecho;
import br.com.labdesktop.instalador.ResultadoDaRemocao;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Criar meu laboratório local" e "Remover laboratório local": a camada de passos sobre o {@link ExecutorDeProcesso}, sem Swing e sem SSH. É a
 * versão local do {@code InstaladorDeAgente} (mesma ideia, mesmos tipos de observador, cancelamento e resultado), com comandos em lista e o
 * agente publicado em {@code 127.0.0.1} em vez de rede do host.
 *
 * <p><b>Nada se instala sozinho.</b> Só a tela de confirmação, depois do clique, chama {@link #criar} e {@link #remover}. <b>Idempotente</b>:
 * cada passo confere por leitura se já está feito; rodar de novo não estraga nada. <b>Se falhar ou cancelar</b>, desfaz SÓ o que esta execução
 * criou e devolve o agente que já funcionava, se havia. <b>Remover</b> tira o agente, os dados dele, a imagem e a rede (só a que é do app e está
 * vazia) e NUNCA apaga a pasta dos projetos do usuário.
 *
 * <p>Uma instância serve a UMA tela: não é thread-safe (uma thread só).
 */
public final class LaboratorioLocal {

    private static final Logger LOG = LoggerFactory.getLogger(LaboratorioLocal.class);

    private static final Duration LEITURA = Duration.ofSeconds(30);
    private static final Duration ESCRITA_CURTA = Duration.ofMinutes(2);
    private static final Duration BUILD = Duration.ofMinutes(20);
    private static final int TETO_DA_SAIDA_NO_LOG = 3000;

    private final ExecutorDeProcesso executor;
    private final ParametrosDoLaboratorio parametros;
    private final PlataformaLocal plataforma;
    private final PacoteDoAgente pacote;
    private final GanchosDaInstalacao ganchos;

    private Duration prazoDaSonda = Duration.ofSeconds(60);
    private Duration intervaloDaSonda = Duration.ofSeconds(1);

    private ObservadorDaInstalacao obs = ObservadorDaInstalacao.NENHUM;
    private Cancelamento cancelamento = Cancelamento.NUNCA;
    private final Set<PassoDoLaboratorio> concluidos = EnumSet.noneOf(PassoDoLaboratorio.class);
    // o que ESTA execução criou (só isso é desfeito)
    private final List<Path> pastasCriadas = new ArrayList<>();
    private boolean criouRede;
    private boolean criouImagem;
    private boolean criouContainer;
    private boolean criouVolume;
    private boolean guardouOAnterior;
    private boolean anteriorRodava;
    private boolean interrompidoNoDesfazer;

    public LaboratorioLocal(ExecutorDeProcesso executor, ParametrosDoLaboratorio parametros, PlataformaLocal plataforma, PacoteDoAgente pacote,
                            GanchosDaInstalacao ganchos) {
        this.executor = executor;
        this.parametros = parametros;
        this.plataforma = plataforma;
        this.pacote = pacote;
        this.ganchos = ganchos;
    }

    /** Encurta a espera pelo agente subir (os testes). */
    public LaboratorioLocal comSonda(Duration prazo, Duration intervalo) {
        this.prazoDaSonda = prazo;
        this.intervaloDaSonda = intervalo;
        return this;
    }

    public ParametrosDoLaboratorio parametros() {
        return parametros;
    }

    // =====================================================================================================
    // Criar
    // =====================================================================================================

    public ResultadoDaInstalacao criar(ObservadorDaInstalacao observador, Cancelamento cancela) {
        this.obs = observador == null ? ObservadorDaInstalacao.NENHUM : observador;
        this.cancelamento = cancela == null ? Cancelamento.NUNCA : cancela;
        pastasCriadas.clear();
        criouRede = false;
        criouImagem = false;
        criouContainer = false;
        criouVolume = false;
        guardouOAnterior = false;
        anteriorRodava = false;
        PassoDoLaboratorio atual = null;
        try {
            if (rodar(ComandosLocais.versaoDoDocker(), LEITURA).status() != 0) {
                return new ResultadoDaInstalacao(Desfecho.RECUSADA, null, "O Docker não está respondendo. Abra o Docker, espere ele ligar e tente de novo.",
                    List.of());
            }
            for (PassoDoLaboratorio p : PassoDoLaboratorio.values()) {
                atual = p;
                conferirCancelamento();
                obs.aoMudarPasso(p, EstadoDoPasso.EM_ANDAMENTO, "");
                boolean fez = executarPasso(p);
                concluidos.add(p);
                obs.aoMudarPasso(p, fez ? EstadoDoPasso.FEITO : EstadoDoPasso.JA_ESTAVA_FEITO, "");
            }
            descartarOAnterior();
            return new ResultadoDaInstalacao(Desfecho.CONCLUIDA, null, "", List.of());
        } catch (InterruptedException e) {
            Thread.interrupted();
            if (atual != null) {
                obs.aoMudarPasso(atual, EstadoDoPasso.FALHOU, "Cancelado.");
            }
            return new ResultadoDaInstalacao(Desfecho.CANCELADA, atual, "Cancelado por você.", desfazer());
        } catch (FalhaDePasso e) {
            obs.aoMudarPasso(atual, EstadoDoPasso.FALHOU, e.getMessage());
            return new ResultadoDaInstalacao(Desfecho.FALHOU, atual, e.getMessage(), desfazer());
        } catch (IOException e) {
            String msg = e instanceof ProgramaNaoEncontradoException ? "O Docker não foi encontrado neste computador."
                : e instanceof ComandoDemorouException ? "O comando demorou demais e foi interrompido: " + Redator.redigir(e)
                : "Não consegui rodar o comando: " + Redator.redigir(e);
            obs.aoMudarPasso(atual, EstadoDoPasso.FALHOU, msg);
            return new ResultadoDaInstalacao(Desfecho.FALHOU, atual, msg, desfazer());
        } catch (RuntimeException e) {
            LOG.warn("Erro inesperado ao criar o laboratório local: {}", Redator.redigir(e));
            String msg = "Erro inesperado: " + TextoSeguro.limparLinha(Redator.redigir(e));
            if (atual != null) {
                obs.aoMudarPasso(atual, EstadoDoPasso.FALHOU, msg);
            }
            return new ResultadoDaInstalacao(Desfecho.FALHOU, atual, msg, desfazer());
        }
    }

    private boolean executarPasso(PassoDoLaboratorio p) throws IOException, InterruptedException, FalhaDePasso {
        return switch (p) {
            case PASTA -> passoPasta();
            case REDE -> passoRede();
            case IMAGEM -> passoImagem();
            case CONTAINER -> passoContainer();
            case SONDAR -> passoSondar();
            case TOKEN -> passoToken();
        };
    }

    private boolean passoPasta() throws FalhaDePasso {
        Path raiz;
        try {
            raiz = Path.of(parametros.pastaDosProjetos());
        } catch (RuntimeException e) {
            throw new FalhaDePasso("A pasta dos projetos não é um caminho válido: " + TextoSeguro.limparLinha(parametros.pastaDosProjetos()));
        }
        if (Files.isDirectory(raiz)) {
            return false;
        }
        List<Path> faltam = new ArrayList<>();
        for (Path p = raiz; p != null && !Files.exists(p); p = p.getParent()) {
            faltam.add(p);
        }
        try {
            registrar("[criando a pasta " + raiz + "]");
            Files.createDirectories(raiz);
        } catch (IOException | RuntimeException e) {
            throw new FalhaDePasso("Não consegui criar a pasta " + raiz + ": " + TextoSeguro.limparLinha(Redator.redigir(e)));
        }
        pastasCriadas.addAll(faltam); // da mais funda para a mais rasa: é a ordem de apagar
        return true;
    }

    private boolean passoRede() throws IOException, InterruptedException, FalhaDePasso {
        for (String linha : rodar(ComandosLocais.lerRede(parametros), LEITURA).stdout().split("\\R")) {
            if (linha.strip().split("\\|", -1)[0].equals(parametros.rede())) {
                return false;
            }
        }
        exigirSucesso(ComandosLocais.criarRede(parametros), ESCRITA_CURTA, "Não consegui criar a rede " + parametros.rede());
        criouRede = true;
        return true;
    }

    private boolean passoImagem() throws IOException, InterruptedException, FalhaDePasso {
        if (rodar(ComandosLocais.lerImagem(parametros, pacote.versao()), LEITURA).status() == 0) {
            return false;
        }
        ResultadoExec x = rodar(ComandosLocais.construirImagem(parametros, pacote), pacote.conteudo(), BUILD);
        if (x.status() != 0) {
            throw new FalhaDePasso("O Docker não conseguiu construir a imagem do agente:\n" + fimDaSaida(x));
        }
        criouImagem = true;
        return true;
    }

    private record DoDocker(String rotulo, String estado) {
        boolean doApp() {
            return !rotulo.isBlank();
        }

        boolean rodando() {
            return "running".equals(estado);
        }
    }

    private Map<String, DoDocker> containersDoApp() throws IOException, InterruptedException {
        Map<String, DoDocker> m = new HashMap<>();
        for (List<String> consulta : List.of(ComandosLocais.lerContainer(parametros), ComandosLocais.lerContainerPeloNome(parametros.containerAnterior()))) {
            for (String linha : rodar(consulta, LEITURA).stdout().split("\\R")) {
                String[] c = linha.strip().split("\\|", -1);
                if (c.length >= 3) {
                    m.put(c[0], new DoDocker(c[1].strip(), c[2].strip()));
                }
            }
        }
        return m;
    }

    private boolean passoContainer() throws IOException, InterruptedException, FalhaDePasso {
        Map<String, DoDocker> existentes = containersDoApp();
        DoDocker atual = existentes.get(parametros.container());
        DoDocker anterior = existentes.get(parametros.containerAnterior());
        if (atual != null && !atual.doApp()) {
            throw new FalhaDePasso("Já existe um container chamado " + parametros.container() + " que não foi criado por este app: não mexi nele. "
                + "Escolha outro nome nas opções avançadas.");
        }
        if (atual != null && atual.rotulo().equals(pacote.versao()) && atual.rodando()) {
            return false;
        }
        if (anterior != null) {
            if (!anterior.doApp()) {
                throw new FalhaDePasso("Já existe um container chamado " + parametros.containerAnterior() + " que não foi criado por este app: "
                    + "não mexi nele.");
            }
            if (atual == null) {
                exigirSucesso(ComandosLocais.renomearContainer(parametros.containerAnterior(), parametros.container()), ESCRITA_CURTA,
                    "Não consegui devolver o agente antigo ao nome dele");
                atual = anterior;
            } else {
                exigirSucesso(ComandosLocais.removerContainer(parametros.containerAnterior()), ESCRITA_CURTA,
                    "Não consegui remover o agente antigo que sobrou");
            }
        }
        boolean volumeExistia = rodar(ComandosLocais.lerVolume(parametros), LEITURA).stdout().lines().anyMatch(v -> v.strip().equals(parametros.volume()));
        if (atual != null) {
            exigirSucesso(ComandosLocais.renomearContainer(parametros.container(), parametros.containerAnterior()), ESCRITA_CURTA, "Não consegui guardar o agente anterior");
            guardouOAnterior = true;
            anteriorRodava = atual.rodando();
            if (anteriorRodava) {
                exigirSucesso(ComandosLocais.pararContainer(parametros.containerAnterior()), ESCRITA_CURTA, "Não consegui parar o agente anterior");
            }
        }
        String pastaNoContainer;
        try {
            pastaNoContainer = plataforma.noContainer(parametros.pastaDosProjetos());
        } catch (IllegalArgumentException e) {
            throw new FalhaDePasso(e.getMessage());
        }
        criouContainer = true;
        ResultadoExec x = rodar(ComandosLocais.subirContainer(parametros, pacote, plataforma.montagemDaMaquina(), pastaNoContainer), ESCRITA_CURTA);
        if (x.status() != 0) {
            throw new FalhaDePasso("O Docker não conseguiu subir o agente: " + saidaDeErro(x));
        }
        criouVolume = !volumeExistia;
        return true;
    }

    private boolean passoSondar() throws IOException, InterruptedException, FalhaDePasso {
        long limite = System.nanoTime() + prazoDaSonda.toNanos();
        while (true) {
            conferirCancelamento();
            if (ganchos.sondar() != GanchosDaInstalacao.Sonda.NAO_RESPONDE) {
                return true;
            }
            if (System.nanoTime() > limite) {
                String logs;
                try {
                    logs = fimDaSaida(rodar(ComandosLocais.lerLogs(parametros), LEITURA));
                } catch (IOException e) {
                    logs = "(não consegui ler os logs do container)";
                }
                throw new FalhaDePasso("O agente subiu, mas não responde em 127.0.0.1:" + parametros.porta() + " depois de " + prazoDaSonda.toSeconds()
                    + " s. O que o container disse:\n" + logs);
            }
            Thread.sleep(intervaloDaSonda.toMillis());
        }
    }

    private boolean passoToken() throws FalhaDePasso, InterruptedException {
        try {
            return ganchos.registrarToken(parametros.comoDoInstalador(), ComandosRemotos.DOCKER_DIRETO);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new FalhaDePasso("Não consegui registrar o token deste app no agente: " + TextoSeguro.limparLinha(Redator.redigir(e)));
        }
    }

    private void descartarOAnterior() {
        if (guardouOAnterior) {
            if (tentar(ComandosLocais.removerContainer(parametros.containerAnterior()))) {
                registrar("[o agente anterior foi removido]");
            } else {
                registrar("[não consegui remover o agente anterior (" + parametros.containerAnterior() + "); pode apagá-lo pelo Docker]");
            }
            guardouOAnterior = false;
        }
    }

    // =====================================================================================================
    // Desfazer (só o que ESTA execução criou; um segundo Cancelar não o aborta)
    // =====================================================================================================

    private List<String> desfazer() {
        boolean interrompidoAntes = Thread.interrupted();
        try {
            return desfazerSemInterrupcao();
        } finally {
            if (interrompidoAntes || interrompidoNoDesfazer) {
                Thread.currentThread().interrupt();
            }
            interrompidoNoDesfazer = false;
        }
    }

    private List<String> desfazerSemInterrupcao() {
        List<String> feito = new ArrayList<>();
        Set<PassoDoLaboratorio> desfeitos = EnumSet.noneOf(PassoDoLaboratorio.class);
        if (criouContainer) {
            if (tentar(ComandosLocais.removerContainer(parametros.container()))) {
                feito.add("Removi o agente " + parametros.container() + ".");
            }
            criouContainer = false;
            desfeitos.addAll(List.of(PassoDoLaboratorio.CONTAINER, PassoDoLaboratorio.SONDAR, PassoDoLaboratorio.TOKEN));
        }
        if (guardouOAnterior) {
            boolean voltou = tentar(ComandosLocais.renomearContainer(parametros.containerAnterior(), parametros.container()));
            if (voltou && anteriorRodava) {
                voltou = tentar(ComandosLocais.iniciarContainer(parametros.container()));
            }
            feito.add(voltou ? "Devolvi o agente anterior ao ar (" + parametros.container() + "), como estava."
                : "Não consegui devolver o agente anterior: ele está guardado como " + parametros.containerAnterior() + " (docker rename e docker start).");
            guardouOAnterior = false;
        }
        if (criouVolume) {
            if (tentar(ComandosLocais.removerVolume(parametros))) {
                feito.add("Removi os dados novos do agente (volume " + parametros.volume() + ").");
            }
            criouVolume = false;
        }
        if (criouImagem) {
            if (tentar(ComandosLocais.removerImagem(parametros, pacote.versao()))) {
                feito.add("Removi a imagem que o app construiu.");
            }
            criouImagem = false;
            desfeitos.add(PassoDoLaboratorio.IMAGEM);
        }
        if (criouRede) {
            if (tentar(ComandosLocais.removerRede(parametros))) {
                feito.add("Removi a rede " + parametros.rede() + " que o app criou.");
            }
            criouRede = false;
            desfeitos.add(PassoDoLaboratorio.REDE);
        }
        if (!pastasCriadas.isEmpty()) {
            for (Path p : pastasCriadas) { // só as que esta execução criou, e só se estão vazias
                if (pastaVazia(p)) {
                    try {
                        Files.delete(p);
                        feito.add("Apaguei a pasta " + p + " que o app criou.");
                    } catch (IOException e) {
                        break;
                    }
                } else {
                    break;
                }
            }
            pastasCriadas.clear();
            desfeitos.add(PassoDoLaboratorio.PASTA);
        }
        for (PassoDoLaboratorio p : desfeitos) {
            if (concluidos.remove(p)) {
                obs.aoMudarPasso(p, EstadoDoPasso.DESFEITO, "");
            }
        }
        if (!feito.isEmpty()) {
            registrar("[desfeito] " + String.join(" ", feito));
        }
        return feito;
    }

    private static boolean pastaVazia(Path p) {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(p)) {
            return !s.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean tentar(List<String> comando) {
        for (int vez = 0; vez < 3; vez++) {
            try {
                return rodar(comando, ESCRITA_CURTA).status() == 0;
            } catch (InterruptedException e) {
                Thread.interrupted();
                interrompidoNoDesfazer = true;
            } catch (IOException e) {
                LOG.warn("Não consegui desfazer ({}): {}", PlanoDeInstalacao.comoTexto(comando), Redator.redigir(e));
                return false;
            }
        }
        return false;
    }

    // =====================================================================================================
    // Remover
    // =====================================================================================================

    /**
     * "Remover laboratório local": o agente (só se foi o app que o criou), os dados dele, a imagem e a rede {@code edge} (só se é do app e está
     * vazia). NÃO apaga a pasta dos projetos nem o Docker. O que falhar vira aviso e os outros passos seguem.
     */
    public ResultadoDaRemocao remover(ObservadorDaInstalacao observador, Cancelamento cancela) throws IOException, InterruptedException {
        this.obs = observador == null ? ObservadorDaInstalacao.NENHUM : observador;
        this.cancelamento = cancela == null ? Cancelamento.NUNCA : cancela;
        List<String> feito = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        if (rodar(ComandosLocais.versaoDoDocker(), LEITURA).status() != 0) {
            avisos.add("O Docker não está respondendo, então nada foi removido. Abra o Docker e tente de novo.");
            return new ResultadoDaRemocao(false, feito, avisos, false);
        }
        String doApp = null;
        boolean existe = false;
        for (String linha : rodar(ComandosLocais.lerContainer(parametros), LEITURA).stdout().split("\\R")) {
            String[] c = linha.strip().split("\\|", -1);
            if (c.length >= 3 && c[0].equals(parametros.container())) {
                existe = true;
                doApp = c[1].isBlank() ? null : c[1];
            }
        }
        if (existe && doApp == null) {
            avisos.add("O container " + parametros.container() + " não foi criado por este app: não mexi nele.");
            return new ResultadoDaRemocao(false, feito, avisos, true);
        }
        boolean removido = !existe;
        conferirCancelamento();
        obs.aoMudarPasso(PassoDaRemocaoLocal.CONTAINER, EstadoDoPasso.EM_ANDAMENTO, "");
        if (existe) {
            ResultadoExec x = rodar(ComandosLocais.removerContainer(parametros.container()), ESCRITA_CURTA);
            removido = x.status() == 0;
            if (removido) {
                feito.add("Removi o agente " + parametros.container() + ".");
            } else {
                avisos.add("Não consegui remover o agente: " + saidaDeErro(x));
            }
        }
        obs.aoMudarPasso(PassoDaRemocaoLocal.CONTAINER, removido ? (existe ? EstadoDoPasso.FEITO : EstadoDoPasso.JA_ESTAVA_FEITO) : EstadoDoPasso.FALHOU, "");

        boolean eraDoApp = existe && doApp != null;
        String versao = eraDoApp && doApp.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,40}$") ? doApp : pacote.versao();
        if (eraDoApp) {
            boolean temVolume = rodar(ComandosLocais.lerVolume(parametros), LEITURA).stdout().lines().anyMatch(v -> v.strip().equals(parametros.volume()));
            removerComAviso(PassoDaRemocaoLocal.DADOS, ComandosLocais.removerVolume(parametros), "os dados do agente",
                "Apaguei os dados do agente (token e 2FA).", feito, avisos, temVolume);
            removerComAviso(PassoDaRemocaoLocal.IMAGEM, ComandosLocais.removerImagem(parametros, versao), "a imagem do agente",
                "Apaguei a imagem do agente.", feito, avisos, true);
        } else {
            obs.aoMudarPasso(PassoDaRemocaoLocal.DADOS, EstadoDoPasso.JA_ESTAVA_FEITO, "");
            obs.aoMudarPasso(PassoDaRemocaoLocal.IMAGEM, EstadoDoPasso.JA_ESTAVA_FEITO, "");
            avisos.add("Não havia um agente deste app chamado " + parametros.container() + ", então deixei os dados e a imagem como estavam.");
        }
        removerAReSeForDoApp(feito, avisos);
        feito.add("Seus projetos em " + parametros.pastaDosProjetos() + " continuam onde estavam: o app nunca os apaga.");
        return new ResultadoDaRemocao(removido, feito, avisos, false);
    }

    private void removerAReSeForDoApp(List<String> feito, List<String> avisos) throws IOException, InterruptedException {
        conferirCancelamento();
        boolean doApp = false;
        boolean existe = false;
        for (String linha : rodar(ComandosLocais.lerRede(parametros), LEITURA).stdout().split("\\R")) {
            String[] c = linha.strip().split("\\|", -1);
            if (c[0].equals(parametros.rede())) {
                existe = true;
                doApp = c.length > 1 && !c[1].isBlank();
            }
        }
        if (!existe) {
            obs.aoMudarPasso(PassoDaRemocaoLocal.REDE, EstadoDoPasso.JA_ESTAVA_FEITO, "");
            return;
        }
        if (!doApp) {
            obs.aoMudarPasso(PassoDaRemocaoLocal.REDE, EstadoDoPasso.JA_ESTAVA_FEITO, "");
            avisos.add("A rede " + parametros.rede() + " não foi criada por este app: deixei como estava.");
            return;
        }
        String dentro = rodar(ComandosLocais.lerContainersNaRede(parametros), LEITURA).stdout().strip();
        if (!dentro.equals("0")) {
            obs.aoMudarPasso(PassoDaRemocaoLocal.REDE, EstadoDoPasso.JA_ESTAVA_FEITO, "");
            avisos.add("A rede " + parametros.rede() + " ainda tem containers ligados (projetos): deixei como estava.");
            return;
        }
        removerComAviso(PassoDaRemocaoLocal.REDE, ComandosLocais.removerRede(parametros), "a rede " + parametros.rede(),
            "Removi a rede " + parametros.rede() + ".", feito, avisos, true);
    }

    private void removerComAviso(Passo passo, List<String> comando, String oQue, String frase, List<String> feito, List<String> avisos, boolean tentar)
        throws IOException, InterruptedException {
        conferirCancelamento();
        if (!tentar) {
            obs.aoMudarPasso(passo, EstadoDoPasso.JA_ESTAVA_FEITO, "");
            return;
        }
        obs.aoMudarPasso(passo, EstadoDoPasso.EM_ANDAMENTO, "");
        ResultadoExec x = rodar(comando, ESCRITA_CURTA);
        if (x.status() == 0) {
            feito.add(frase);
            obs.aoMudarPasso(passo, EstadoDoPasso.FEITO, "");
        } else {
            avisos.add("Não consegui apagar " + oQue + ": " + saidaDeErro(x));
            obs.aoMudarPasso(passo, EstadoDoPasso.FALHOU, saidaDeErro(x));
        }
    }

    // =====================================================================================================
    // Apoio
    // =====================================================================================================

    private ResultadoExec rodar(List<String> comando, Duration prazo) throws IOException, InterruptedException {
        return rodar(comando, new byte[0], prazo);
    }

    private ResultadoExec rodar(List<String> comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Interrompido.");
        }
        registrar("$ " + PlanoDeInstalacao.comoTexto(comando) + (stdin.length > 0 ? "   [pacote do agente pela entrada, " + stdin.length + " bytes]" : ""));
        ResultadoExec x = executor.executar(comando, stdin, prazo);
        String saida = (x.stdout() + (x.stderr().isBlank() ? "" : "\n" + x.stderr())).strip();
        if (!saida.isEmpty()) {
            registrar(TextoSeguro.truncar(saida, TETO_DA_SAIDA_NO_LOG));
        }
        if (x.status() != 0) {
            registrar("[status " + x.status() + "]");
        }
        return x;
    }

    private void exigirSucesso(List<String> comando, Duration prazo, String oQueFalhou) throws IOException, InterruptedException, FalhaDePasso {
        conferirCancelamento();
        ResultadoExec x = rodar(comando, prazo);
        if (x.status() != 0) {
            throw new FalhaDePasso(oQueFalhou + ": " + saidaDeErro(x));
        }
    }

    private void registrar(String linha) {
        obs.aoRegistrar(linha);
    }

    private void conferirCancelamento() throws InterruptedException {
        if (cancelamento.cancelado()) {
            throw new InterruptedException("Cancelado.");
        }
    }

    private static String fimDaSaida(ResultadoExec x) {
        String texto = (x.stdout() + (x.stderr().isBlank() ? "" : "\n" + x.stderr())).strip();
        String[] linhas = texto.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, linhas.length - 15); i < linhas.length; i++) {
            sb.append(TextoSeguro.limparLinha(linhas[i])).append('\n');
        }
        return TextoSeguro.truncar(sb.toString().strip(), 1500);
    }

    private static String saidaDeErro(ResultadoExec x) {
        String texto = x.stderr().isBlank() ? x.stdout() : x.stderr();
        String limpo = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(texto.strip())), 400);
        return (limpo.isEmpty() ? "sem mensagem" : limpo) + " (status " + x.status() + ")";
    }

    private static final class FalhaDePasso extends Exception {
        private static final long serialVersionUID = 1L;

        FalhaDePasso(String mensagem) {
            super(mensagem);
        }
    }
}
