package br.com.labdesktop.projetos;

import br.com.labdesktop.arquivos.CaminhosLocais;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ClienteSftpLocal;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.arquivos.FabricaDeClienteSftp;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Um ambiente de projetos de mentira para os testes: o sistema de arquivos é uma pasta temporária de verdade (pelo mesmo {@link ClienteSftpLocal}
 * que o laboratório local usa) e os programas são um roteiro que anota tudo o que foi rodado (nenhum comando de verdade, nenhum Docker, nenhuma rede).
 */
public final class AmbienteFalso {

    /** Um comando que o roteiro viu. */
    public record Chamada(List<String> argumentos, Map<String, String> ambiente, String entrada) { }

    /** O que o roteiro responde (e faz no disco, se for o caso) a um comando. */
    @FunctionalInterface
    public interface Roteiro {
        ResultadoExec responder(Chamada chamada) throws IOException, InterruptedException;
    }

    public final Path casa;
    public final Path raiz;
    public final List<Chamada> chamadas = new CopyOnWriteArrayList<>();
    /** Quantos clientes de arquivos foram abertos e ainda não fechados (a prova de que nada vaza). */
    public final AtomicInteger abertos = new AtomicInteger();
    public volatile Roteiro roteiro = c -> ok("");
    /** Se for maior que zero, a gravação de número N (contando de 1) falha com "sem permissão". */
    public volatile int falharNaGravacao;
    private final AtomicInteger gravacoes = new AtomicInteger();

    public AmbienteFalso(Path tmp) {
        this.casa = tmp.resolve("casa");
        this.raiz = casa.resolve("LabLocal").resolve("projects");
        try {
            Files.createDirectories(casa);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static ResultadoExec ok(String saida) {
        return new ResultadoExec(0, saida.getBytes(StandardCharsets.UTF_8), new byte[0], false);
    }

    public static ResultadoExec erro(int status, String stderr) {
        return new ResultadoExec(status, new byte[0], stderr.getBytes(StandardCharsets.UTF_8), false);
    }

    public String raizDoApp() {
        return raiz.toString();
    }

    private FabricaDeClienteSftp fabrica() {
        return () -> {
            ClienteSftp real = new ClienteSftpLocal(new CaminhosLocais(false), casa.toString());
            abertos.incrementAndGet();
            return (ClienteSftp) Proxy.newProxyInstance(ClienteSftp.class.getClassLoader(), new Class<?>[] {ClienteSftp.class}, (proxy, metodo, args) -> {
                if (metodo.getName().equals("close")) {
                    abertos.decrementAndGet();
                } else if (metodo.getName().equals("gravarAtomico") && falharNaGravacao > 0 && gravacoes.incrementAndGet() == falharNaGravacao) {
                    throw new ErroSftp(ErroSftp.Tipo.SEM_PERMISSAO, "Sem permissão (teste).");
                }
                try {
                    return metodo.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        };
    }

    private ExecutorDeArgumentos executor() {
        return (argumentos, ambiente, entrada, prazo) -> {
            Chamada c = new Chamada(List.copyOf(argumentos), Map.copyOf(ambiente), new String(entrada, StandardCharsets.UTF_8));
            chamadas.add(c);
            return roteiro.responder(c);
        };
    }

    /** Como o computador local: comandos de lista (sem a regra de caracteres seguros) e caminhos do sistema iguais aos do app. */
    public AmbienteDeProjetos local() {
        return new AmbienteDeProjetos(true, raizDoApp(), fabrica(), executor(), caminho -> caminho);
    }

    /** Como um servidor por SSH: o MESMO roteiro, mas cada comando passa antes pela linha remota (só argumentos seguros). */
    public AmbienteDeProjetos remoto() {
        ExecutorDeArgumentos porLista = executor();
        ExecutorDeArgumentos viaLinha = (argumentos, ambiente, entrada, prazo) -> {
            ExecutorDeArgumentos.linhaRemota(argumentos, ambiente); // recusa (IllegalArgumentException) o que o servidor não receberia
            return porLista.executar(argumentos, ambiente, entrada, prazo);
        };
        return new AmbienteDeProjetos(false, raizDoApp(), fabrica(), viaLinha, caminho -> caminho);
    }

    public AmbienteDeProjetos localComConversao(java.util.function.UnaryOperator<String> paraOSistema) {
        return new AmbienteDeProjetos(true, raizDoApp(), fabrica(), executor(), paraOSistema);
    }

    public List<List<String>> comandos() {
        return chamadas.stream().map(Chamada::argumentos).toList();
    }

    public static Duration qualquer() {
        return Duration.ofSeconds(1);
    }
}
