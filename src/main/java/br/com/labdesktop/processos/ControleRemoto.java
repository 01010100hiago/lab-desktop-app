package br.com.labdesktop.processos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Encerrar e reiniciar num servidor Linux por SSH, pela conexão que o app já tem (usuário comum, SEM sudo).
 *
 * <ul>
 *   <li><b>Antes de enviar o sinal</b> o app lê a identidade do processo (nome, dono, hora de início) e a mostra; o sinal sai num comando que
 *       confere a identidade DE NOVO e só então faz o {@code kill} ({@link ComandosDeProcessos#enviarSinal}). Se o número foi reaproveitado, nada
 *       é enviado.</li>
 *   <li><b>Primeiro SIGTERM</b>; o app espera ({@link #ESPERA_DO_TERM_EM_SEGUNDOS} s) lendo de segundo em segundo, e só então o usuário pode pedir
 *       {@link #forcar} (SIGKILL).</li>
 *   <li><b>Container</b>: {@code docker stop} e {@code docker restart}; o agente do painel nunca (a lista de proteção).</li>
 *   <li><b>Sem permissão</b> (outro usuário, ou o usuário não alcança o Docker): a frase e o comando exato para o usuário rodar; nunca sudo.</li>
 * </ul>
 */
public final class ControleRemoto implements ControleDeProcessos {

    /** Quanto o app espera o programa fechar depois do SIGTERM. */
    public static final int ESPERA_DO_TERM_EM_SEGUNDOS = 8;
    /** Quanto espera depois do SIGKILL (que o sistema cumpre quase na hora). */
    public static final int ESPERA_DO_KILL_EM_SEGUNDOS = 3;

    private static final Duration PRAZO_CURTO = Duration.ofSeconds(15);
    private static final Duration PRAZO_DO_DOCKER = Duration.ofSeconds(60);

    /** Dorme em milissegundos (os testes não esperam de verdade). */
    @FunctionalInterface
    public interface Dorminhoco {
        void dormir(long milissegundos) throws InterruptedException;
    }

    private final ProvisionadorRemoto.Executor linha;
    private final ExecutorDeArgumentos argumentos;
    private final AmbienteDeProcessos ambiente;
    private final Dorminhoco dorminhoco;
    private final int esperaDoTerm;
    private final int esperaDoKill;
    private volatile long avisoDoDockerMs = AVISO_DO_DOCKER_EM_MS;

    /** Depois de quanto tempo sem resposta a tela diz que o container está demorando. */
    public static final long AVISO_DO_DOCKER_EM_MS = 10_000;

    public ControleRemoto(ProvisionadorRemoto.Executor linha, AmbienteDeProcessos ambiente) {
        this(linha, ExecutorDeArgumentos.porSsh(linha), ambiente, Thread::sleep, ESPERA_DO_TERM_EM_SEGUNDOS, ESPERA_DO_KILL_EM_SEGUNDOS);
    }

    public ControleRemoto(ProvisionadorRemoto.Executor linha, ExecutorDeArgumentos argumentos, AmbienteDeProcessos ambiente, Dorminhoco dorminhoco,
                          int esperaDoTerm, int esperaDoKill) {
        this.linha = linha;
        this.argumentos = argumentos;
        this.ambiente = ambiente;
        this.dorminhoco = dorminhoco;
        this.esperaDoTerm = esperaDoTerm;
        this.esperaDoKill = esperaDoKill;
    }

    /** Só os testes: encurta o tempo até o aviso de demora do Docker. */
    ControleRemoto comAvisoDoDockerEm(long milissegundos) {
        this.avisoDoDockerMs = milissegundos;
        return this;
    }

    @Override
    public AmbienteDeProcessos ambiente() {
        return ambiente;
    }

    // =====================================================================================================
    // Preparar (só lê)
    // =====================================================================================================

    @Override
    public Previa preparar(ProcessoAlvo alvo) throws FalhaDeProcesso, InterruptedException {
        Oferta oferta = avaliar(alvo);
        if (oferta.protegido()) {
            throw new FalhaDeProcesso(Desfecho.PROTEGIDO, oferta.protecao());
        }
        if (oferta.via() == Via.CONTAINER) {
            return new Previa(alvo, oferta, null, null);
        }
        int pid = ValidadorDePid.exigir(alvo.pid());
        IdentidadeDoProcesso id = lerIdentidade(pid, alvo);
        String unidade = null;
        if (oferta.situacao() == Oferta.Situacao.LIBERADO) {
            unidade = detectarServico(pid, id.uid());
            if (unidade != null && ListaDeProtecao.motivoDaUnidade(unidade).isPresent()) {
                unidade = null; // filho de um serviço protegido (docker.service, sshd.service...): nunca "Reiniciar o serviço"
            }
        }
        return new Previa(alvo, oferta, id, unidade);
    }

    private IdentidadeDoProcesso lerIdentidade(int pid, ProcessoAlvo alvo) throws FalhaDeProcesso, InterruptedException {
        ResultadoExec r;
        try {
            r = argumentos.executar(ComandosDeProcessos.lerIdentidade(pid), ComandosDeProcessos.ambienteDaLeitura(), new byte[0], PRAZO_CURTO);
        } catch (IOException e) {
            throw new FalhaDeProcesso(Desfecho.FALHOU, textoDaFalhaDeConexao(e), "", e);
        }
        Optional<IdentidadeDoProcesso> id = IdentidadeDoProcesso.deSaidaDoPs(r.stdout(), pid);
        if (id.isEmpty()) {
            boolean numeroNaoExiste = r.stderr().isBlank() && (r.status() == 1 || r.stdout().isBlank());
            if (numeroNaoExiste) {
                throw new FalhaDeProcesso(Desfecho.JA_SAIU, "O processo " + pid + " já saiu. A lista vai atualizar.");
            }
            if (r.stdout().isBlank() && !r.stderr().isBlank()) {
                throw new FalhaDeProcesso(Desfecho.SEM_CONFERENCIA, "Este servidor não sabe dizer a hora em que o processo " + pid
                    + " começou (o ps dele não tem essa opção), e sem isso o app não consegue ter certeza de que é o mesmo processo. Por segurança, não envia nada. "
                    + "Se você tem certeza, rode o comando no Terminal.", paraODono(pid, alvo.nome(), Sinal.TERM).texto(), null);
            }
            throw new FalhaDeProcesso(Desfecho.SEM_CONFERENCIA, "O servidor respondeu de um jeito que o app não entende; por segurança, nada foi enviado.");
        }
        if (!IdentidadeDoProcesso.nomeConfere(alvo.nome(), id.get().nome())) {
            throw new FalhaDeProcesso(Desfecho.MUDOU, "O processo " + pid + " agora é outro programa (" + limpo(id.get().nome()) + ", e na lista era "
                + limpo(alvo.nomeCurto()) + "): o número foi reaproveitado. Nada foi enviado; a lista vai atualizar.");
        }
        return id.get();
    }

    private String detectarServico(int pid, String uid) throws InterruptedException {
        try {
            ResultadoExec r = argumentos.executar(ComandosDeProcessos.lerGrupoDeControle(pid), PRAZO_CURTO);
            if (r.status() != 0) {
                return null;
            }
            return ComandosDeProcessos.unidadeDoUsuario(r.stdout(), uid).orElse(null);
        } catch (IOException | IllegalArgumentException e) {
            return null; // detectar o serviço é um extra: sem ele, só Encerrar
        }
    }

    // =====================================================================================================
    // Encerrar, forçar, reiniciar
    // =====================================================================================================

    @Override
    public Resultado encerrar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
        if (previa.oferta().protegido()) {
            return Resultado.de(Desfecho.PROTEGIDO, previa.oferta().protecao());
        }
        if (previa.oferta().via() == Via.CONTAINER) {
            String c = previa.alvo().container();
            obs.etapa("Parando o container " + c + "... (o Docker espera até 10 s antes de forçar; o app espera até 1 minuto)");
            return docker(obs, ComandosDeProcessos.pararContainer(c), Acao.ENCERRAR, c, Desfecho.ENCERRADO,
                "O container " + c + " parou. Os programas que rodavam nele pararam junto.");
        }
        return sinal(previa, Sinal.TERM, esperaDoTerm, obs, cancelamento);
    }

    @Override
    public Resultado forcar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
        if (previa.oferta().protegido()) {
            return Resultado.de(Desfecho.PROTEGIDO, previa.oferta().protecao());
        }
        if (previa.oferta().via() != Via.PROCESSO || previa.identidade() == null) {
            return Resultado.de(Desfecho.FALHOU, "Forçar só existe para um processo comum. Um container é parado à força pelo próprio Docker se não fechar a tempo.");
        }
        if (!previa.pedidoEducadoEnviado()) {
            return Resultado.de(Desfecho.FALHOU, AvaliadorDeProcessos.FORCAR_SO_DEPOIS);
        }
        return sinal(previa, Sinal.KILL, esperaDoKill, obs, cancelamento);
    }

    @Override
    public Resultado reiniciar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
        if (previa.oferta().protegido()) {
            return Resultado.de(Desfecho.PROTEGIDO, previa.oferta().protecao());
        }
        if (previa.oferta().via() == Via.CONTAINER) {
            String c = previa.alvo().container();
            obs.etapa("Reiniciando o container " + c + "... (o app espera até 1 minuto)");
            return docker(obs, ComandosDeProcessos.reiniciarContainer(c), Acao.REINICIAR, c, Desfecho.REINICIADO,
                "O container " + c + " foi reiniciado. As conexões abertas com ele caíram por alguns segundos.");
        }
        if (previa.temServico() && previa.identidade() != null) {
            String unidade = previa.unidade();
            obs.etapa("Reiniciando o serviço " + unidade + "...");
            ResultadoExec r;
            try {
                r = argumentos.executar(ComandosDeProcessos.reiniciarServicoDoUsuario(unidade),
                    ComandosDeProcessos.ambienteDoServicoDoUsuario(previa.identidade().uid()), new byte[0], PRAZO_DO_DOCKER);
            } catch (IOException e) {
                return new Resultado(Desfecho.FALHOU, textoDaFalhaDeConexao(e), "");
            }
            if (r.status() == 0) {
                return Resultado.de(Desfecho.REINICIADO, "O serviço " + unidade + " foi reiniciado pelo systemd do seu usuário.");
            }
            return new Resultado(Desfecho.FALHOU, "O systemd não conseguiu reiniciar " + unidade + ": " + limpo(r.stderr()) + " Você pode tentar no Terminal: systemctl --user restart "
                + unidade, "systemctl --user restart " + unidade);
        }
        return Resultado.de(Desfecho.FALHOU, AvaliadorDeProcessos.SEM_REINICIAR);
    }

    private Resultado docker(ObservadorDeProcesso obs, java.util.List<String> comando, Acao acao, String container, Desfecho bom, String frase) throws InterruptedException {
        ResultadoExec r;
        Thread aviso = avisoDeDemora(obs, container);
        try {
            r = argumentos.executar(comando, PRAZO_DO_DOCKER);
        } catch (ComandoDemorouException e) {
            return Resultado.de(Desfecho.FALHOU, "O Docker não terminou em 1 minuto. Veja o estado do container na aba Containers antes de tentar de novo.");
        } catch (IOException e) {
            return new Resultado(Desfecho.FALHOU, textoDaFalhaDeConexao(e), "");
        } finally {
            aviso.interrupt();
        }
        if (r.status() == 0) {
            return Resultado.de(bom, frase);
        }
        String erro = r.stderr().toLowerCase(java.util.Locale.ROOT);
        if (erro.contains("permission denied") || erro.contains("got permission denied")) {
            ComandoParaODono paraUsuario = ComandosDeProcessos.paraODonoDoContainer(acao, container);
            return new Resultado(Desfecho.SEM_PERMISSAO, "O seu usuário não alcança o Docker deste servidor (não está no grupo docker). O app não usa sudo nem digita senha: "
                + "rode o comando abaixo no Terminal, ou peça ao administrador para pôr o usuário no grupo docker." + (paraUsuario.temComando() ? "" : " " + paraUsuario.motivoSem()), paraUsuario.texto());
        }
        if (erro.contains("no such container")) {
            return Resultado.de(Desfecho.JA_SAIU, "O container " + container + " não existe mais. A lista vai atualizar.");
        }
        return Resultado.de(Desfecho.FALHOU, "O Docker não conseguiu: " + limpo(r.stderr()));
    }

    /** Depois de {@code avisoDoDockerMs} sem resposta, diz na tela que o container está demorando (o Docker espera 10 s antes de forçar). */
    private Thread avisoDeDemora(ObservadorDeProcesso obs, String container) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(avisoDoDockerMs);
                obs.etapa("Já passaram 10 s e o container " + container + " ainda não fechou: o Docker vai forçar em seguida. O app espera até 1 minuto pela resposta.");
            } catch (InterruptedException e) {
                // a resposta chegou antes do aviso: nada a dizer
            }
        }, "aviso-do-docker");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private ComandoParaODono paraODono(Integer pid, String nome, Sinal sinal) {
        return ComandosDeProcessos.paraODono(pid, nome, sinal, ambiente.tipo());
    }

    private Resultado sinal(Previa previa, Sinal sinal, int espera, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
        IdentidadeDoProcesso id = previa.identidade();
        int pid = id.pid();
        obs.etapa("Enviando o pedido " + (sinal == Sinal.TERM ? "para o programa fechar (" : "à força (") + sinal.rotulo() + ")...");
        ResultadoExec r;
        try {
            r = linha.executar(ComandosDeProcessos.enviarSinal(pid, sinal), (id.linha() + "\n").getBytes(StandardCharsets.UTF_8), PRAZO_CURTO);
        } catch (IOException e) {
            return new Resultado(Desfecho.FALHOU, textoDaFalhaDeConexao(e), "");
        }
        String saida = ComandosDeProcessos.respostaDoSinal(r.stdout()).orElse("");
        switch (saida) {
            case ComandosDeProcessos.ENVIADO -> {
                if (sinal == Sinal.TERM) {
                    previa.registrarPedidoEducado();
                }
                return esperarSair(id, sinal, espera, obs, cancelamento);
            }
            case ComandosDeProcessos.MUDOU -> {
                return Resultado.de(Desfecho.MUDOU, "O número " + pid + " agora é de outro processo (o que você escolheu já saiu e o número foi reaproveitado). "
                    + "Nada foi enviado. A lista vai atualizar.");
            }
            case ComandosDeProcessos.JA_SAIU -> {
                return Resultado.de(Desfecho.JA_SAIU, "O processo " + pid + " já tinha saído. Nada foi enviado.");
            }
            case ComandosDeProcessos.SEM_PERMISSAO -> {
                ComandoParaODono paraUsuario = paraODono(pid, id.nome(), sinal);
                return new Resultado(Desfecho.SEM_PERMISSAO, "O sistema não deixou: este processo não é do usuário " + ambiente.usuarioDoApp() + ". O app não usa sudo nem digita senha. "
                    + (paraUsuario.temComando() ? "Se você tem permissão, rode o comando abaixo no Terminal." : paraUsuario.motivoSem()), paraUsuario.texto());
            }
            case ComandosDeProcessos.SEM_CONFERENCIA -> {
                ComandoParaODono paraUsuario = paraODono(pid, id.nome(), sinal);
                return new Resultado(Desfecho.SEM_CONFERENCIA, "O app não conseguiu conferir este processo (o servidor não deixa ler a identidade dele, ou é de outro usuário); "
                    + "por segurança, nada foi enviado e não dá para dizer se ele saiu." + (paraUsuario.temComando() ? "" : " " + paraUsuario.motivoSem()), paraUsuario.texto());
            }
            default -> {
                return Resultado.de(Desfecho.FALHOU, "O servidor respondeu de um jeito inesperado e o app não sabe se o pedido saiu. Confira a lista.");
            }
        }
    }

    private Resultado esperarSair(IdentidadeDoProcesso id, Sinal sinal, int espera, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
        Leitura ultima = Leitura.RODANDO;
        for (int s = 1; s <= espera; s++) {
            dorminhoco.dormir(1000);
            if (cancelamento.cancelado()) {
                return Resultado.de(Desfecho.CANCELADO, "Você parou de esperar. O pedido (" + sinal.rotulo() + ") já tinha sido enviado; o programa pode ter fechado ou não: veja a lista.");
            }
            obs.aguardando(s, espera);
            ultima = ler(id);
            if (ultima == Leitura.SAIU) {
                return Resultado.de(Desfecho.ENCERRADO, "O processo " + id.pid() + " (" + limpo(id.nome()) + ") fechou" + (sinal == Sinal.KILL ? " (à força)." : "."));
            }
        }
        if (ultima == Leitura.ILEGIVEL) {
            return Resultado.de(Desfecho.SEM_CONFERENCIA, "O pedido (" + sinal.rotulo() + ") foi enviado ao processo " + id.pid() + " (" + limpo(id.nome()) + "), mas o app não conseguiu "
                + "ler a resposta do servidor para saber se ele fechou. Confira a lista antes de pedir de novo.");
        }
        return Resultado.de(Desfecho.AINDA_RODANDO, "O processo " + id.pid() + " (" + limpo(id.nome()) + ") recebeu o pedido e continua rodando depois de " + espera + " s. "
            + (sinal == Sinal.TERM ? "Ele pode estar terminando o que fazia. Você pode esperar mais ou forçar (" + Sinal.KILL.rotulo() + ")."
                : "Isso é raro: ele pode estar preso no sistema. Confira de novo daqui a pouco."));
    }

    private enum Leitura { RODANDO, SAIU, ILEGIVEL }

    /**
     * O processo continua o mesmo? SÓ conta como "fechou" a confirmação de verdade: o {@code ps} disse que o número não existe (status 1 e nenhuma
     * reclamação) ou mostrou OUTRA identidade (virou zumbi, ou o número foi reaproveitado). Uma saída que o app não entende, um erro do {@code ps} ou a conexão
     * que falha NÃO valem como "fechou": o app segue esperando e, no fim, diz que não conseguiu conferir.
     */
    private Leitura ler(IdentidadeDoProcesso id) throws InterruptedException {
        try {
            ResultadoExec r = argumentos.executar(ComandosDeProcessos.lerIdentidade(id.pid()), ComandosDeProcessos.ambienteDaLeitura(), new byte[0], PRAZO_CURTO);
            Optional<IdentidadeDoProcesso> agora = IdentidadeDoProcesso.deSaidaDoPs(r.stdout(), id.pid());
            if (agora.isEmpty()) {
                // nenhuma linha do ps que o app entenda: só vale como "fechou" se o ps disse que o número não existe (status 1, sem reclamar); banner do servidor em volta não conta
                return r.status() == 1 && r.stderr().isBlank() ? Leitura.SAIU : Leitura.ILEGIVEL;
            }
            return agora.get().linha().equals(id.linha()) ? Leitura.RODANDO : Leitura.SAIU;
        } catch (IOException e) {
            return Leitura.ILEGIVEL;
        }
    }

    private static String textoDaFalhaDeConexao(IOException e) {
        if (e instanceof ComandoDemorouException) {
            return "O servidor demorou para responder. Confira a lista antes de tentar de novo.";
        }
        return "Não consegui falar com o servidor (a conexão caiu?). Nada garante que o pedido tenha saído: confira a lista.";
    }

    private static String limpo(String t) {
        return TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(t == null ? "" : t.strip())), 200);
    }
}
