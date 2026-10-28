package br.com.labdesktop.processos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Encerrar processos DESTE computador, com as mesmas regras do servidor: protegidos nunca, só do mesmo usuário, identidade conferida de novo antes de
 * agir (nome, dono e hora de início), primeiro o pedido educado e SÓ DEPOIS, por outro clique, o à força.
 *
 * <ul>
 *   <li><b>Mac e Linux</b>: o pedido é o SIGTERM e o à força é o SIGKILL, pelo {@link ProcessHandle} (o Java manda o sinal ao número, sem shell).</li>
 *   <li><b>Windows</b>: {@code taskkill /PID <n> /T} e, para forçar, {@code taskkill /PID <n> /T /F}, com a lista de argumentos fixa; só programas
 *       {@code .exe}.</li>
 * </ul>
 * Não há Reiniciar para processo comum (o app não sabe como religá-lo).
 */
public final class ControleLocal implements ControleDeProcessos {

    private static final Duration PRAZO = Duration.ofSeconds(15);

    private final ProcessosDoSistema sistema;
    private final ExecutorDeProcesso executor;
    private final AmbienteDeProcessos ambiente;
    private final ControleRemoto.Dorminhoco dorminhoco;
    private final int esperaDoPedido;
    private final int esperaDoForcar;

    public ControleLocal(ProcessosDoSistema sistema, ExecutorDeProcesso executor, AmbienteDeProcessos ambiente) {
        this(sistema, executor, ambiente, Thread::sleep, ControleRemoto.ESPERA_DO_TERM_EM_SEGUNDOS, ControleRemoto.ESPERA_DO_KILL_EM_SEGUNDOS);
    }

    public ControleLocal(ProcessosDoSistema sistema, ExecutorDeProcesso executor, AmbienteDeProcessos ambiente, ControleRemoto.Dorminhoco dorminhoco,
                         int esperaDoPedido, int esperaDoForcar) {
        this.sistema = sistema;
        this.executor = executor;
        this.ambiente = ambiente;
        this.dorminhoco = dorminhoco;
        this.esperaDoPedido = esperaDoPedido;
        this.esperaDoForcar = esperaDoForcar;
    }

    private boolean windows() {
        return ambiente.tipo() == AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS;
    }

    @Override
    public AmbienteDeProcessos ambiente() {
        return ambiente;
    }

    @Override
    public Previa preparar(ProcessoAlvo alvo) throws FalhaDeProcesso {
        Oferta oferta = avaliar(alvo);
        if (oferta.protegido()) {
            throw new FalhaDeProcesso(Desfecho.PROTEGIDO, oferta.protecao());
        }
        if (oferta.via() == Via.CONTAINER) {
            throw new FalhaDeProcesso(Desfecho.FALHOU, "Os processos de container de Este computador se mexem pela lista de containers do projeto.");
        }
        int pid = ValidadorDePid.exigir(alvo.pid());
        if (windows() && !alvo.nomeCurto().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            throw new FalhaDeProcesso(Desfecho.FALHOU, "No Windows o app só encerra programas .exe (este é " + limpo(alvo.nomeCurto()) + ").");
        }
        Optional<ProcessosDoSistema.Info> info = sistema.ler(pid);
        if (info.isEmpty()) {
            throw new FalhaDeProcesso(Desfecho.JA_SAIU, "O processo " + pid + " já saiu. A lista vai atualizar.");
        }
        if (!IdentidadeDoProcesso.nomeConfere(alvo.nome(), info.get().comando())) {
            throw new FalhaDeProcesso(Desfecho.MUDOU, "O processo " + pid + " agora é outro programa (" + limpo(ProcessoAlvo.nomeCurto(info.get().comando())) + "): o número foi reaproveitado. "
                + "Nada foi enviado; a lista vai atualizar.");
        }
        return new Previa(alvo, oferta, identidadeDe(info.get()), null);
    }

    private static IdentidadeDoProcesso identidadeDe(ProcessosDoSistema.Info i) {
        String inicio = i.inicio() == null ? "(sem hora de início)" : i.inicio().toString();
        return new IdentidadeDoProcesso((int) i.pid(), i.usuario().isBlank() ? "?" : i.usuario(), inicio, i.comando().isBlank() ? "?" : i.comando());
    }

    @Override
    public Resultado encerrar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
        return agir(previa, false, esperaDoPedido, obs, cancelamento);
    }

    @Override
    public Resultado forcar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
        return agir(previa, true, esperaDoForcar, obs, cancelamento);
    }

    @Override
    public Resultado reiniciar(Previa previa, ObservadorDeProcesso obs, Cancelamento cancelamento) {
        return Resultado.de(Desfecho.FALHOU, AvaliadorDeProcessos.SEM_REINICIAR);
    }

    private Resultado agir(Previa previa, boolean forcar, int espera, ObservadorDeProcesso obs, Cancelamento cancelamento) throws InterruptedException {
        if (previa.oferta().protegido()) {
            return Resultado.de(Desfecho.PROTEGIDO, previa.oferta().protecao());
        }
        IdentidadeDoProcesso id = previa.identidade();
        if (id == null) {
            return Resultado.de(Desfecho.FALHOU, "O app não leu o processo antes; nada foi enviado.");
        }
        int pid = id.pid();
        Optional<ProcessosDoSistema.Info> agora = sistema.ler(pid);
        if (agora.isEmpty()) {
            return Resultado.de(Desfecho.JA_SAIU, "O processo " + pid + " já tinha saído. Nada foi enviado.");
        }
        if (!identidadeDe(agora.get()).linha().equals(id.linha())) {
            return Resultado.de(Desfecho.MUDOU, "O número " + pid + " agora é de outro processo (o que você escolheu já saiu e o número foi reaproveitado). Nada foi enviado. A lista vai atualizar.");
        }
        Sinal sinal = forcar ? Sinal.KILL : Sinal.TERM;
        obs.etapa("Enviando o pedido " + (forcar ? "à força (" : "para o programa fechar (") + (windows() ? "taskkill" : sinal.rotulo()) + ")...");
        Resultado recusa = windows() ? taskkill(pid, forcar) : sinalDoSistema(pid, forcar);
        if (recusa != null) {
            return recusa;
        }
        for (int s = 1; s <= espera; s++) {
            dorminhoco.dormir(1000);
            if (cancelamento.cancelado()) {
                return Resultado.de(Desfecho.CANCELADO, "Você parou de esperar. O pedido já tinha sido enviado; o programa pode ter fechado ou não: veja a lista.");
            }
            obs.aguardando(s, espera);
            if (saiu(pid, id)) {
                return Resultado.de(Desfecho.ENCERRADO, "O processo " + pid + " (" + limpo(id.nome()) + ") fechou" + (forcar ? " (à força)." : "."));
            }
        }
        return Resultado.de(Desfecho.AINDA_RODANDO, "O processo " + pid + " (" + limpo(ProcessoAlvo.nomeCurto(id.nome())) + ") recebeu o pedido e continua rodando depois de " + espera + " s. "
            + (forcar ? "Isso é raro: ele pode estar preso no sistema. Confira de novo daqui a pouco." : "Você pode esperar mais ou forçar (" + (windows() ? "taskkill /F" : Sinal.KILL.rotulo()) + ")."));
    }

    /** Mac e Linux: o sinal pelo Java. {@code null} = o pedido saiu e é hora de esperar. */
    private Resultado sinalDoSistema(int pid, boolean forcar) {
        boolean aceito = forcar ? sistema.matar(pid) : sistema.pedirParaFechar(pid);
        if (aceito) {
            return null;
        }
        if (!sistema.estaVivo(pid)) {
            return Resultado.de(Desfecho.JA_SAIU, "O processo " + pid + " já tinha saído.");
        }
        return new Resultado(Desfecho.SEM_PERMISSAO, "O sistema não deixou: este processo não é do usuário " + ambiente.usuarioDoApp() + ". O app não usa sudo nem digita senha. "
            + "Se você tem permissão, rode o comando abaixo no Terminal.", ComandosDeProcessos.comandoParaODono(pid, forcar ? Sinal.KILL : Sinal.TERM, ambiente.tipo()));
    }

    /** Windows: taskkill com a lista de argumentos fixa. */
    private Resultado taskkill(int pid, boolean forcar) throws InterruptedException {
        ResultadoExec r;
        try {
            r = executor.executar(ComandosDeProcessos.taskkill(pid, forcar), PRAZO);
        } catch (IOException e) {
            return Resultado.de(Desfecho.FALHOU, "Não consegui rodar o taskkill neste computador.");
        }
        if (r.status() == 0) {
            return null;
        }
        String t = (r.stdout() + " " + r.stderr()).toLowerCase(Locale.ROOT);
        if (t.contains("access is denied") || t.contains("acesso negado")) {
            return new Resultado(Desfecho.SEM_PERMISSAO, "O Windows não deixou: este programa pede mais permissão do que o app tem. O app não eleva privilégio. "
                + "Se você tem permissão, rode o comando abaixo num terminal aberto como administrador.", ComandosDeProcessos.comandoParaODono(pid, forcar ? Sinal.KILL : Sinal.TERM, ambiente.tipo()));
        }
        if (t.contains("not found") || t.contains("não foi encontrado") || t.contains("nao foi encontrado") || t.contains("no running instance")) {
            return Resultado.de(Desfecho.JA_SAIU, "O processo " + pid + " já tinha saído.");
        }
        if (!forcar && (t.contains("can only be terminated forcefully") || t.contains("/f"))) {
            return null; // o pedido educado não vale para este programa: a espera termina em AINDA_RODANDO e o usuário decide forçar
        }
        return Resultado.de(Desfecho.FALHOU, "O taskkill não conseguiu: " + limpo(r.stderr().isBlank() ? r.stdout() : r.stderr()));
    }

    private boolean saiu(int pid, IdentidadeDoProcesso id) {
        Optional<ProcessosDoSistema.Info> i = sistema.ler(pid);
        return i.isEmpty() || !identidadeDe(i.get()).linha().equals(id.linha());
    }

    private static String limpo(String t) {
        return TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(t == null ? "" : t.strip())), 200);
    }
}
