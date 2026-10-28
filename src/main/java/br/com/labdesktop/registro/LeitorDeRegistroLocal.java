package br.com.labdesktop.registro;

import br.com.labdesktop.local.ComandosLocais;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * O registro de "Este computador". No Mac e no Windows NÃO existe SSH e o registro do SISTEMA não é lido (cada sistema tem o seu jeito, e o app não mexe no do usuário): o que a caixa
 * mostra é o registro do PRÓPRIO LABORATÓRIO, o que o container do agente escreve ({@code docker logs --tail 200 --timestamps <container>}). Um processo, com os argumentos em LISTA
 * ({@link ComandosLocais#lerRegistroDoAgente}), por um executor que só aceita leituras. A tela diz, em texto simples, que é isso e não o registro do sistema.
 */
public final class LeitorDeRegistroLocal implements LeitorDeRegistro {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    /** O que a caixa diz sobre a origem destas linhas. */
    public static final String FONTE = "do laboratório local: o que o container do agente escreve (o registro do sistema deste computador não é lido)";

    private final ExecutorDeProcesso executor;
    private final Supplier<String> container;

    /** @param executor SÓ de leitura ({@code ExecutorSoDeLeitura}); @param container o nome do container do agente, lido a cada leitura (o usuário pode ter mudado) */
    public LeitorDeRegistroLocal(ExecutorDeProcesso executor, Supplier<String> container) {
        this.executor = executor;
        this.container = container;
    }

    @Override
    public ResultadoDoRegistro ler() throws IOException, InterruptedException {
        List<String> comando;
        String nome = container.get();
        try {
            comando = ComandosLocais.lerRegistroDoAgente(nome);
        } catch (IllegalArgumentException e) {
            return new ResultadoDoRegistro.Indisponivel("O nome do container do agente é inválido: não vou pedir o registro dele.");
        }
        ResultadoExec r;
        try {
            r = executor.executar(comando, PRAZO);
        } catch (ProgramaNaoEncontradoException e) {
            return new ResultadoDoRegistro.Indisponivel("O Docker não está instalado, ou o app não o achou neste computador: sem ele não há laboratório local nem registro dele.");
        }
        if (r.status() != 0) {
            return new ResultadoDoRegistro.Indisponivel(explicar(r.stderr()));
        }
        // o Docker manda o que o container escreveu na saída de ERRO para o erro, e o resto para a saída: junta as duas e ordena pela hora de cada linha
        InterpretadorDeRegistro.Leitura a = InterpretadorDeRegistro.doDocker(r.stdout() + "\n" + r.stderr(), nome);
        return new ResultadoDoRegistro.Lido(InterpretadorDeRegistro.maisNovasEmCima(new ArrayList<>(a.linhas())), FONTE, a.cortada() || r.truncado());
    }

    private static String explicar(String erro) {
        String e = erro == null ? "" : erro.toLowerCase(Locale.ROOT);
        if (e.contains("no such container") || e.contains("no such object")) {
            return "O laboratório local ainda não foi criado neste computador. Use Preparar este computador e o registro dele aparece aqui.";
        }
        if (e.contains("cannot connect") || e.contains("is the docker daemon running") || e.contains("error during connect")) {
            return "O Docker não está aberto. Abra o Docker e o registro do laboratório volta a aparecer aqui.";
        }
        if (e.contains("permission denied")) {
            return "Seu usuário não alcança o Docker deste computador (no Linux, precisa estar no grupo docker). Sem isso o app não lê o registro do laboratório.";
        }
        return "Não consegui ler o registro do laboratório local agora.";
    }
}
