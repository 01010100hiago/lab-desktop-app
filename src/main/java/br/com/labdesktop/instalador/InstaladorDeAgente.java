package br.com.labdesktop.instalador;

import br.com.labdesktop.instalador.ResultadoDaInstalacao.Desfecho;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A camada de passos que instala, atualiza e remove o agente do perfil monitor num servidor. Sem Swing e sem SSH: fala só com o
 * {@link ExecutorDeComandos} (o servidor local a reaproveita com um executor local) e com os {@link GanchosDaInstalacao}.
 *
 * <p><b>Nada se instala sozinho.</b> Esta classe nunca é chamada por conexão, abertura do app ou segundo plano: só a tela de
 * confirmação, depois do clique do usuário, a aciona. {@link #verificar} só LÊ; {@link #instalar} e {@link #remover} escrevem.
 *
 * <p><b>Passos idempotentes.</b> Cada passo confere por leitura se já está feito e não repete ({@link EstadoDoPasso#JA_ESTAVA_FEITO}):
 * rodar duas vezes não muda nada na segunda, e "Tentar de novo" depois de uma falha retoma do passo que falhou (os anteriores que
 * continuam de pé são pulados).
 *
 * <p><b>Se falhar, ou cancelar:</b> desfaz SÓ o que ESTA execução criou (o container, o volume de dados novo, a imagem e a pasta
 * do instalador) e nunca o que já existia (dados de uma instalação anterior, o Docker). O erro volta como texto, com o que o
 * servidor disse.
 *
 * <p>Uma instância serve a UMA tela: guarda o último relatório de verificação e o que já foi feito. Não é thread-safe: uma thread só.
 */
public final class InstaladorDeAgente {

    private static final Logger LOG = LoggerFactory.getLogger(InstaladorDeAgente.class);

    private static final Duration LEITURA = Duration.ofSeconds(30);
    private static final Duration ESCRITA_CURTA = Duration.ofMinutes(2);
    private static final Duration APT = Duration.ofMinutes(15);
    private static final Duration BUILD = Duration.ofMinutes(20);
    /** Quanto de cada saída vai para o log da tela (o servidor pode imprimir muito). */
    private static final int TETO_DA_SAIDA_NO_LOG = 3000;

    private final ExecutorDeComandos executor;
    private final ParametrosDaInstalacao parametros;
    private final PacoteDoAgente pacote;
    private final GanchosDaInstalacao ganchos;
    private final PreVerificacoes verificacoes;

    private Duration prazoDaSonda = Duration.ofSeconds(60);
    private Duration intervaloDaSonda = Duration.ofSeconds(1);

    private ModoDaInstalacao modo = ModoDaInstalacao.INSTALAR;
    private RelatorioDeVerificacao relatorio;
    private String docker;
    private final Set<PassoDeInstalacao> concluidos = EnumSet.noneOf(PassoDeInstalacao.class);
    private boolean criouContainer;
    private boolean criouVolume;
    private boolean criouImagem;
    private boolean criouPasta;

    private ObservadorDaInstalacao obs = ObservadorDaInstalacao.NENHUM;
    private Cancelamento cancelamento = Cancelamento.NUNCA;

    public InstaladorDeAgente(ExecutorDeComandos executor, ParametrosDaInstalacao parametros, PacoteDoAgente pacote,
                              GanchosDaInstalacao ganchos) {
        this.executor = executor;
        this.parametros = parametros;
        this.pacote = pacote;
        this.ganchos = ganchos;
        this.verificacoes = new PreVerificacoes(executor, parametros, pacote);
    }

    /** Encurta a espera pelo agente subir (os testes). */
    public InstaladorDeAgente comSonda(Duration prazo, Duration intervalo) {
        this.prazoDaSonda = prazo;
        this.intervaloDaSonda = intervalo;
        return this;
    }

    public ParametrosDaInstalacao parametros() {
        return parametros;
    }

    public PacoteDoAgente pacote() {
        return pacote;
    }

    /** O último relatório das verificações, ou {@code null} se ainda não rodou. */
    public RelatorioDeVerificacao relatorio() {
        return relatorio;
    }

    // =====================================================================================================
    // Verificar (só leitura)
    // =====================================================================================================

    /**
     * Roda as verificações prévias (SÓ leitura) e guarda o relatório para {@link #instalar}.
     *
     * @throws IOException          a conexão caiu
     * @throws InterruptedException cancelado
     */
    public RelatorioDeVerificacao verificar(ModoDaInstalacao como) throws IOException, InterruptedException {
        this.modo = como;
        this.relatorio = verificacoes.executar(como);
        this.docker = relatorio.docker();
        return relatorio;
    }

    // =====================================================================================================
    // Instalar ou atualizar
    // =====================================================================================================

    /**
     * Instala (ou atualiza, conforme o modo da última verificação) o agente, passo a passo. Exige que {@link #verificar} tenha rodado
     * e não tenha problema; senão devolve {@link Desfecho#RECUSADA} sem tocar em nada.
     *
     * @param observador recebe o estado ao vivo de cada passo e o log (na thread do instalador)
     * @param cancelamento consultado entre os comandos
     */
    public ResultadoDaInstalacao instalar(ObservadorDaInstalacao observador, Cancelamento cancelamento) {
        this.obs = observador == null ? ObservadorDaInstalacao.NENHUM : observador;
        this.cancelamento = cancelamento == null ? Cancelamento.NUNCA : cancelamento;
        if (relatorio == null || relatorio.bloqueado()) {
            return new ResultadoDaInstalacao(Desfecho.RECUSADA, null, "As verificações ainda têm problema: resolva antes de instalar.", List.of());
        }
        criouContainer = false;
        criouVolume = false;
        criouImagem = false;
        criouPasta = false;
        PassoDeInstalacao atual = null;
        try {
            for (PassoDeInstalacao p : PassoDeInstalacao.values()) {
                atual = p;
                conferirCancelamento();
                obs.aoMudarPasso(p, EstadoDoPasso.EM_ANDAMENTO, "");
                boolean fez = executarPasso(p);
                concluidos.add(p);
                obs.aoMudarPasso(p, fez ? EstadoDoPasso.FEITO : EstadoDoPasso.JA_ESTAVA_FEITO, "");
            }
            return new ResultadoDaInstalacao(Desfecho.CONCLUIDA, null, "", List.of());
        } catch (InterruptedException e) {
            Thread.interrupted(); // soltou o comando em andamento; o desfazer precisa rodar
            if (atual != null) {
                obs.aoMudarPasso(atual, EstadoDoPasso.FALHOU, "Cancelado.");
            }
            List<String> desfeito = desfazer();
            return new ResultadoDaInstalacao(Desfecho.CANCELADA, atual, "Cancelado por você.", desfeito);
        } catch (FalhaDePasso e) {
            obs.aoMudarPasso(atual, EstadoDoPasso.FALHOU, e.getMessage());
            List<String> desfeito = desfazer();
            return new ResultadoDaInstalacao(Desfecho.FALHOU, atual, e.getMessage(), desfeito);
        } catch (IOException e) {
            String msg = "A conexão com o servidor caiu: " + Redator.redigir(e);
            obs.aoMudarPasso(atual, EstadoDoPasso.FALHOU, msg);
            List<String> desfeito = desfazer();
            return new ResultadoDaInstalacao(Desfecho.FALHOU, atual, msg, desfeito);
        }
    }

    private boolean executarPasso(PassoDeInstalacao p) throws IOException, InterruptedException, FalhaDePasso {
        return switch (p) {
            case DOCKER -> passoDocker();
            case ENVIAR_PACOTE -> passoEnviarPacote();
            case CONSTRUIR_IMAGEM -> passoConstruirImagem();
            case SUBIR_CONTAINER -> passoSubirContainer();
            case SONDAR -> passoSondar();
            case REGISTRAR_TOKEN -> passoRegistrarToken();
        };
    }

    // ---- Docker ----

    private boolean passoDocker() throws IOException, InterruptedException, FalhaDePasso {
        if (relatorio.dockerPresente()) {
            return false;
        }
        // O Docker pode ter aparecido depois da verificação ("Tentar de novo" depois de uma falha mais adiante, ou o usuário o instalou
        // à mão): uma leitura confirma antes de reinstalar.
        String atual = PreVerificacoes.chaveValor(rodar(ComandosRemotos.lerDocker(), LEITURA).stdout(), '=').getOrDefault("docker", "erro");
        if (atual.startsWith("ok:") || "so-com-sudo".equals(atual)) {
            docker = atual.startsWith("ok:") ? ComandosRemotos.DOCKER_DIRETO : ComandosRemotos.DOCKER_COM_SUDO;
            return false;
        }
        if (!relatorio.vaiInstalarODocker()) {
            throw new FalhaDePasso("O app não consegue instalar o Docker neste servidor (precisa de Debian ou Ubuntu e de administrador).");
        }
        for (String comando : ComandosRemotos.instalarODocker(relatorio.root(), relatorio.distribuicao())) {
            exigirSucesso(comando, APT, "A instalação do Docker falhou");
        }
        // O grupo docker do usuário só vale em sessões NOVAS: nesta, o Docker responde por root ou por sudo sem senha.
        ResultadoExec x = rodar(ComandosRemotos.lerDocker(), LEITURA);
        String estado = PreVerificacoes.chaveValor(x.stdout(), '=').getOrDefault("docker", "erro");
        if (estado.startsWith("ok:")) {
            docker = ComandosRemotos.DOCKER_DIRETO;
        } else if ("so-com-sudo".equals(estado)) {
            docker = ComandosRemotos.DOCKER_COM_SUDO;
        } else {
            throw new FalhaDePasso("Instalei o Docker, mas não consegui usá-lo logo em seguida (estado: " + limpar(estado)
                + "). Confira o servidor e tente de novo.");
        }
        return true;
    }

    // ---- pacote ----

    private boolean passoEnviarPacote() throws IOException, InterruptedException, FalhaDePasso {
        ResultadoExec ja = rodar(ComandosRemotos.lerVersaoExtraida(parametros), LEITURA);
        if (ja.status() == 0 && ja.stdout().strip().equals(pacote.versao())) {
            return false;
        }
        boolean pastaJaExistia = "existe".equals(rodar(ComandosRemotos.lerSeAPastaExiste(parametros), LEITURA).stdout().strip());
        exigirSucesso(ComandosRemotos.criarPasta(parametros), ESCRITA_CURTA, "Não consegui criar a pasta do agente no servidor");
        if (!pastaJaExistia) {
            criouPasta = true;
        }
        String caminho = ComandosRemotos.caminhoDoPacote(parametros, pacote);
        registrar("[enviando " + caminho + " (" + pacote.tamanho() + " bytes)]");
        try {
            executor.enviarArquivo(caminho, pacote.conteudo());
        } catch (IOException e) {
            throw new FalhaDePasso("Não consegui enviar o pacote do agente: " + limpar(Redator.redigir(e)));
        }
        ResultadoExec sha = rodar(ComandosRemotos.lerSha256DoPacote(parametros, pacote), LEITURA);
        String chegou = sha.stdout().strip().split("\\s+")[0];
        if (sha.status() != 0 || !chegou.equalsIgnoreCase(pacote.sha256())) {
            throw new FalhaDePasso("O pacote que chegou ao servidor não confere com o que o app enviou (SHA-256 diferente). "
                + "A transferência foi corrompida: tente de novo.");
        }
        exigirSucesso(ComandosRemotos.extrairPacote(parametros, pacote), ESCRITA_CURTA, "Não consegui extrair o pacote no servidor");
        return true;
    }

    // ---- imagem ----

    private boolean passoConstruirImagem() throws IOException, InterruptedException, FalhaDePasso {
        String imagem = parametros.imagem(pacote.versao());
        if (rodar(ComandosRemotos.lerImagem(docker, imagem), LEITURA).status() == 0) {
            return false;
        }
        ResultadoExec x = rodar(ComandosRemotos.construirImagem(docker, parametros, pacote), BUILD);
        if (x.status() != 0) {
            throw new FalhaDePasso("O Docker não conseguiu construir a imagem do agente:\n" + fimDaSaida(x));
        }
        criouImagem = true;
        return true;
    }

    // ---- container ----

    private boolean passoSubirContainer() throws IOException, InterruptedException, FalhaDePasso {
        String existenteDoApp = null;
        boolean existeOutro = false;
        boolean rodando = false;
        ResultadoExec ps = rodar(ComandosRemotos.lerContainer(docker, parametros), LEITURA);
        for (String linha : ps.stdout().split("\\R")) {
            String[] c = linha.strip().split("\\|", -1);
            if (c.length >= 3 && c[0].equals(parametros.container())) {
                if (c[1].isBlank()) {
                    existeOutro = true;
                } else {
                    existenteDoApp = c[1].strip();
                    rodando = "running".equals(c[2].strip());
                }
            }
        }
        if (existeOutro) {
            throw new FalhaDePasso("Já existe um container chamado " + parametros.container() + " que não foi criado por este app: não mexi nele.");
        }
        if (existenteDoApp != null && existenteDoApp.equals(pacote.versao()) && rodando) {
            return false;
        }
        boolean volumeExistia = false;
        for (String v : rodar(ComandosRemotos.lerVolume(docker, parametros), LEITURA).stdout().split("\\R")) {
            if (v.strip().equals(parametros.volume())) {
                volumeExistia = true;
            }
        }
        if (existenteDoApp != null) {
            exigirSucesso(ComandosRemotos.removerContainer(docker, parametros), ESCRITA_CURTA, "Não consegui remover o agente anterior");
        }
        ResultadoExec x = rodar(ComandosRemotos.subirContainer(docker, parametros, pacote), ESCRITA_CURTA);
        if (x.status() != 0) {
            throw new FalhaDePasso("O Docker não conseguiu subir o container do agente: " + saidaDeErro(x));
        }
        criouContainer = true;
        criouVolume = !volumeExistia;
        return true;
    }

    // ---- sonda e token ----

    private boolean passoSondar() throws IOException, InterruptedException, FalhaDePasso {
        long limite = System.nanoTime() + prazoDaSonda.toNanos();
        while (true) {
            conferirCancelamento();
            GanchosDaInstalacao.Sonda s = ganchos.sondar();
            if (s != GanchosDaInstalacao.Sonda.NAO_RESPONDE) {
                return true;
            }
            if (System.nanoTime() > limite) {
                String logs = "";
                try {
                    ResultadoExec x = rodar(ComandosRemotos.lerLogsDoContainer(docker, parametros), LEITURA);
                    logs = fimDaSaida(x);
                } catch (IOException e) {
                    logs = "(não consegui ler os logs do container)";
                }
                throw new FalhaDePasso("O container do agente subiu, mas não responde em 127.0.0.1:" + parametros.porta()
                    + " do servidor depois de " + prazoDaSonda.toSeconds() + " s. O que o container disse:\n" + logs);
            }
            Thread.sleep(intervaloDaSonda.toMillis());
        }
    }

    private boolean passoRegistrarToken() throws FalhaDePasso, InterruptedException {
        try {
            return ganchos.registrarToken(parametros, docker);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new FalhaDePasso("Não consegui registrar o token deste Mac no agente: " + limpar(Redator.redigir(e)));
        }
    }

    // =====================================================================================================
    // Desfazer (só o que ESTA execução criou)
    // =====================================================================================================

    private List<String> desfazer() {
        List<String> feito = new ArrayList<>();
        if (docker == null) {
            return feito;
        }
        Set<PassoDeInstalacao> desfeitos = EnumSet.noneOf(PassoDeInstalacao.class);
        if (criouContainer) {
            if (tentar(ComandosRemotos.removerContainer(docker, parametros))) {
                feito.add("Removi o container " + parametros.container() + ".");
            }
            criouContainer = false;
            desfeitos.addAll(List.of(PassoDeInstalacao.SUBIR_CONTAINER, PassoDeInstalacao.SONDAR, PassoDeInstalacao.REGISTRAR_TOKEN));
        }
        if (criouVolume) {
            if (tentar(ComandosRemotos.removerVolume(docker, parametros))) {
                feito.add("Removi os dados novos do agente (volume " + parametros.volume() + ").");
            }
            criouVolume = false;
        }
        if (criouImagem) {
            if (tentar(ComandosRemotos.removerImagem(docker, parametros.imagem(pacote.versao())))) {
                feito.add("Removi a imagem que o app construiu.");
            }
            criouImagem = false;
            desfeitos.add(PassoDeInstalacao.CONSTRUIR_IMAGEM);
        }
        if (criouPasta) {
            if (tentar(ComandosRemotos.removerPasta(parametros))) {
                feito.add("Apaguei a pasta " + parametros.pasta() + " do instalador.");
            }
            criouPasta = false;
            desfeitos.add(PassoDeInstalacao.ENVIAR_PACOTE);
        }
        for (PassoDeInstalacao p : desfeitos) {
            if (concluidos.remove(p)) {
                obs.aoMudarPasso(p, EstadoDoPasso.DESFEITO, "");
            }
        }
        if (!feito.isEmpty()) {
            registrar("[desfeito] " + String.join(" ", feito));
        }
        return feito;
    }

    /** Roda um comando de limpeza sem deixar um erro dele esconder o erro original. */
    private boolean tentar(String comando) {
        try {
            return rodar(comando, ESCRITA_CURTA).status() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Não consegui desfazer ({}): {}", comando.length() > 60 ? comando.substring(0, 60) : comando, Redator.redigir(e));
            return false;
        }
    }

    // =====================================================================================================
    // Remover
    // =====================================================================================================

    /**
     * "Remover agente": para e remove o container do agente (só se foi o app que o criou), os dados dele, a imagem e a pasta do
     * instalador. NÃO desinstala o Docker. O que falhar vira aviso e os outros passos seguem.
     */
    public ResultadoDaRemocao remover(ObservadorDaInstalacao observador, Cancelamento cancelamento) throws IOException, InterruptedException {
        this.obs = observador == null ? ObservadorDaInstalacao.NENHUM : observador;
        this.cancelamento = cancelamento == null ? Cancelamento.NUNCA : cancelamento;
        List<String> feito = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        String dk = descobrirODocker();
        if (dk == null) {
            avisos.add("Não consegui usar o Docker deste servidor (sem permissão ou parado): nada foi removido.");
            return new ResultadoDaRemocao(false, feito, avisos, false);
        }
        docker = dk;
        String doApp = null;
        boolean existe = false;
        for (String linha : rodar(ComandosRemotos.lerContainer(docker, parametros), LEITURA).stdout().split("\\R")) {
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
        obs.aoMudarPasso(PassoDeRemocao.CONTAINER, EstadoDoPasso.EM_ANDAMENTO, "");
        if (existe) {
            ResultadoExec x = rodar(ComandosRemotos.removerContainer(docker, parametros), ESCRITA_CURTA);
            removido = x.status() == 0;
            if (removido) {
                feito.add("Removi o container " + parametros.container() + ".");
            } else {
                avisos.add("Não consegui remover o container: " + saidaDeErro(x));
            }
        }
        obs.aoMudarPasso(PassoDeRemocao.CONTAINER, removido ? (existe ? EstadoDoPasso.FEITO : EstadoDoPasso.JA_ESTAVA_FEITO) : EstadoDoPasso.FALHOU, "");

        removerComAviso(PassoDeRemocao.DADOS, ComandosRemotos.removerVolume(docker, parametros), "os dados do agente",
            "Apaguei os dados do agente (token e 2FA).", feito, avisos, volumeExiste());
        // A imagem só sai se nenhum outro agente do app a usa: o próprio Docker recusa ("está em uso") e isso vira só um aviso.
        removerComAviso(PassoDeRemocao.IMAGEM, ComandosRemotos.removerImagem(docker, parametros.imagem(pacote.versao())), "a imagem do agente",
            "Apaguei a imagem do agente.", feito, avisos, true);
        removerComAviso(PassoDeRemocao.PASTA, ComandosRemotos.removerPasta(parametros), "a pasta do instalador",
            "Apaguei a pasta " + parametros.pasta() + " do instalador.", feito, avisos, true);
        concluidos.clear();
        return new ResultadoDaRemocao(removido, feito, avisos, false);
    }

    private boolean volumeExiste() throws IOException, InterruptedException {
        for (String v : rodar(ComandosRemotos.lerVolume(docker, parametros), LEITURA).stdout().split("\\R")) {
            if (v.strip().equals(parametros.volume())) {
                return true;
            }
        }
        return false;
    }

    private void removerComAviso(Passo passo, String comando, String oQue, String frase, List<String> feito, List<String> avisos,
                                 boolean tentar) throws IOException, InterruptedException {
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

    private String descobrirODocker() throws IOException, InterruptedException {
        if (docker != null) {
            return docker;
        }
        String estado = PreVerificacoes.chaveValor(rodar(ComandosRemotos.lerDocker(), LEITURA).stdout(), '=').getOrDefault("docker", "erro");
        if (estado.startsWith("ok:")) {
            return ComandosRemotos.DOCKER_DIRETO;
        }
        return "so-com-sudo".equals(estado) ? ComandosRemotos.DOCKER_COM_SUDO : null;
    }

    // =====================================================================================================
    // Apoio
    // =====================================================================================================

    /** Roda um comando, registra no log e devolve o resultado (qualquer status). */
    private ResultadoExec rodar(String comando, Duration prazo) throws IOException, InterruptedException {
        conferirCancelamentoSeInterrompido();
        registrar("$ " + comando);
        ResultadoExec x = executor.executar(comando, new byte[0], prazo);
        String saida = (x.stdout() + (x.stderr().isBlank() ? "" : "\n" + x.stderr())).strip();
        if (!saida.isEmpty()) {
            registrar(TextoSeguro.truncar(saida, TETO_DA_SAIDA_NO_LOG));
        }
        if (x.status() != 0) {
            registrar("[status " + x.status() + "]");
        }
        return x;
    }

    private void exigirSucesso(String comando, Duration prazo, String oQueFalhou) throws IOException, InterruptedException, FalhaDePasso {
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

    private void conferirCancelamentoSeInterrompido() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Interrompido.");
        }
    }

    /** O fim da saída (onde está o erro), limpo, para a frase. */
    private static String fimDaSaida(ResultadoExec x) {
        String texto = (x.stdout() + (x.stderr().isBlank() ? "" : "\n" + x.stderr())).strip();
        String[] linhas = texto.split("\\R");
        int de = Math.max(0, linhas.length - 15);
        StringBuilder sb = new StringBuilder();
        for (int i = de; i < linhas.length; i++) {
            sb.append(limpar(linhas[i])).append('\n');
        }
        return TextoSeguro.truncar(sb.toString().strip(), 1500);
    }

    /** A mensagem de erro de um comando: o que o servidor disse, em uma frase. */
    private static String saidaDeErro(ResultadoExec x) {
        String texto = x.stderr().isBlank() ? x.stdout() : x.stderr();
        String limpo = TextoSeguro.truncar(limpar(Redator.redigir(texto.strip())), 400);
        return (limpo.isEmpty() ? "sem mensagem" : limpo) + " (status " + x.status() + ")";
    }

    private static String limpar(String texto) {
        return TextoSeguro.limparLinha(texto == null ? "" : texto);
    }

    /** Falha de um passo: a mensagem já é a frase para a tela. */
    private static final class FalhaDePasso extends Exception {
        private static final long serialVersionUID = 1L;

        FalhaDePasso(String mensagem) {
            super(mensagem);
        }
    }
}
