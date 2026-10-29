package br.com.labdesktop.teste;

import br.com.labdesktop.transporte.LancadorDeProcesso;
import br.com.labdesktop.transporte.ProcessoExterno;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Um lançador de programas de MENTIRA: não lança nada de verdade. Guarda cada lançamento (a lista de argumentos e o ambiente extra) e deixa o teste decidir, por um
 * roteiro, o que o "programa" faz: sobe (passa a escutar na porta pedida), cai, demora, não sobe, solta lixo na saída.
 */
public final class LancadorFalso implements LancadorDeProcesso {

    /** O que o teste faz quando o app lança o programa. */
    @FunctionalInterface
    public interface Roteiro {
        void aoLancar(Lancamento lancamento) throws IOException;
    }

    /** Um lançamento e o processo de mentira dele. */
    public static final class Lancamento {
        public final List<String> argumentos;
        public final Map<String, String> ambiente;
        public final ProcessoFalso processo;
        private final PortasFalsas portas;

        Lancamento(List<String> argumentos, Map<String, String> ambiente, ProcessoFalso processo, PortasFalsas portas) {
            this.argumentos = argumentos;
            this.ambiente = ambiente;
            this.processo = processo;
            this.portas = portas;
        }

        /** A porta local que o app pediu (no {@code --url} do cloudflared ou no {@code localPortNumber} do aws). */
        public int portaPedida() {
            for (String a : argumentos) {
                Matcher m = Pattern.compile("(?:localPortNumber=|127\\.0\\.0\\.1:)(\\d+)").matcher(a);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
            throw new IllegalStateException("sem porta nos argumentos: " + argumentos);
        }

        /** O programa "sobe": passa a escutar na porta que o app pediu. */
        public void subir() {
            portas.escutar(portaPedida());
            processo.aoMorrer = () -> portas.parar(portaPedida());
        }

        /** O programa "cai sozinho" (não foi o app que o encerrou). */
        public void cair(int codigo) {
            processo.sair(codigo);
        }
    }

    /** O processo de mentira. */
    public static final class ProcessoFalso implements ProcessoExterno {
        private final AtomicBoolean vivo = new AtomicBoolean(true);
        private final CompletableFuture<Integer> saiu = new CompletableFuture<>();
        private final StringBuilder saida = new StringBuilder();
        public final AtomicInteger pedidosDeEncerrar = new AtomicInteger();
        volatile Runnable aoMorrer = () -> { };

        public void escrever(String texto) {
            synchronized (saida) {
                saida.append(texto);
            }
        }

        public void sair(int codigo) {
            if (vivo.compareAndSet(true, false)) {
                aoMorrer.run();
                saiu.complete(codigo);
            }
        }

        @Override
        public boolean vivo() {
            return vivo.get();
        }

        @Override
        public CompletableFuture<Integer> aoSair() {
            return saiu;
        }

        @Override
        public String saida() {
            synchronized (saida) {
                return saida.toString();
            }
        }

        @Override
        public void encerrar() {
            pedidosDeEncerrar.incrementAndGet();
            sair(143);
        }
    }

    public final List<Lancamento> lancamentos = new CopyOnWriteArrayList<>();
    private final PortasFalsas portas;
    private volatile Roteiro roteiro;
    private volatile IOException falhaAoLancar;

    public LancadorFalso(PortasFalsas portas, Roteiro roteiro) {
        this.portas = portas;
        this.roteiro = roteiro;
    }

    /** O programa que sobe na hora, na porta pedida. */
    public static LancadorFalso queSobe(PortasFalsas portas) {
        return new LancadorFalso(portas, Lancamento::subir);
    }

    public void roteiro(Roteiro novo) {
        this.roteiro = novo;
    }

    public void falharAoLancar(IOException e) {
        this.falhaAoLancar = e;
    }

    @Override
    public ProcessoExterno lancar(List<String> argumentos, Map<String, String> ambienteExtra) throws IOException {
        if (falhaAoLancar != null) {
            throw falhaAoLancar;
        }
        ProcessoFalso p = new ProcessoFalso();
        Lancamento l = new Lancamento(List.copyOf(argumentos), Map.copyOf(ambienteExtra), p, portas);
        lancamentos.add(l);
        roteiro.aoLancar(l);
        return p;
    }

    /** Quantos processos de mentira ainda estão de pé. */
    public long vivos() {
        return lancamentos.stream().filter(l -> l.processo.vivo()).count();
    }
}
