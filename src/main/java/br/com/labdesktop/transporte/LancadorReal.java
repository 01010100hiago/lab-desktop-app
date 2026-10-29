package br.com.labdesktop.transporte;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O lançador de verdade: {@link ProcessBuilder} com a LISTA de argumentos (sem shell: nunca {@code sh -c}, {@code cmd /c} nem {@code powershell}). O que o programa escreve
 * (saída e erro juntos) é lido numa thread à parte e só o FIM fica guardado (poucos KiB), para o app poder mostrar a causa de uma queda sem crescer na memória. A entrada do
 * programa é fechada na hora: ele nunca fica esperando o teclado.
 *
 * <p>Ao encerrar, mata o processo e todos os descendentes dele ({@link ProcessHandle#descendants()}): o {@code aws} lança o plugin do Session Manager como filho, e matar só o
 * {@code aws} deixaria o plugin órfão, com a porta local ocupada.
 */
public final class LancadorReal implements LancadorDeProcesso {

    private static final Logger LOG = LoggerFactory.getLogger(LancadorReal.class);
    /** Quanto da saída do programa se guarda (o FIM dela). */
    static final int BYTES_DA_SAIDA = 16 * 1024;
    /** Quanto esperar depois do pedido gentil de encerramento, e depois do forçado. */
    private static final long ESPERA_EM_MILISSEGUNDOS = 2_000;

    @Override
    public ProcessoExterno lancar(List<String> argumentos, Map<String, String> ambienteExtra) throws IOException {
        if (argumentos == null || argumentos.isEmpty() || argumentos.get(0) == null || argumentos.get(0).isBlank()) {
            throw new IOException("Faltou o programa a lançar.");
        }
        ProcessBuilder pb = new ProcessBuilder(argumentos);
        pb.redirectErrorStream(true);
        pb.environment().putAll(ambienteExtra);
        Process p = pb.start();
        try {
            p.getOutputStream().close(); // o programa não lê o teclado
        } catch (IOException e) {
            LOG.debug("Não consegui fechar a entrada do programa lançado: {}", e.toString());
        }
        return new ProcessoReal(p);
    }

    /** O processo de verdade (público para os testes lerem o {@link #pid()}). */
    public static final class ProcessoReal implements ProcessoExterno {

        private final Process processo;
        private final Object trava = new Object();
        private final byte[] fim = new byte[BYTES_DA_SAIDA];
        private final Thread leitor;
        private final CompletableFuture<Integer> saiu;
        private int usados;

        ProcessoReal(Process processo) {
            this.processo = processo;
            this.leitor = new Thread(this::ler, "saida-do-tunel-" + processo.pid());
            leitor.setDaemon(true);
            leitor.start();
            // só completa depois de a saída ter sido lida até o fim (ou de um segundo: um filho que herdou o cano a mantém aberta), para quem lê a causa da queda não ver o texto pela metade
            this.saiu = processo.onExit().thenApplyAsync(p -> {
                try {
                    leitor.join(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return p.exitValue();
            });
        }

        public long pid() {
            return processo.pid();
        }

        private void ler() {
            byte[] bloco = new byte[2048];
            try (InputStream in = processo.getInputStream()) {
                int n;
                while ((n = in.read(bloco)) > 0) {
                    guardar(bloco, n);
                }
            } catch (IOException e) {
                LOG.debug("Fim da leitura da saída do programa: {}", e.toString());
            }
        }

        private void guardar(byte[] bloco, int n) {
            synchronized (trava) {
                if (n >= fim.length) {
                    System.arraycopy(bloco, n - fim.length, fim, 0, fim.length);
                    usados = fim.length;
                    return;
                }
                int excesso = usados + n - fim.length;
                if (excesso > 0) {
                    System.arraycopy(fim, excesso, fim, 0, usados - excesso);
                    usados -= excesso;
                }
                System.arraycopy(bloco, 0, fim, usados, n);
                usados += n;
            }
        }

        @Override
        public boolean vivo() {
            return processo.isAlive();
        }

        @Override
        public CompletableFuture<Integer> aoSair() {
            return saiu;
        }

        @Override
        public String saida() {
            synchronized (trava) {
                return new String(fim, 0, usados, StandardCharsets.UTF_8);
            }
        }

        @Override
        public void encerrar() {
            // A lista de descendentes se refaz: um filho pode nascer enquanto o pai morre, e depois de o pai morrer os filhos viram órfãos e somem da árvore dele.
            // Por isso: lista e mata os filhos (até três rodadas, para os que nasceram no meio), mata o pai, lista DE NOVO a partir de cada um que já se viu e força o que sobrou.
            Set<ProcessHandle> vistos = new LinkedHashSet<>();
            for (int rodada = 0; rodada < 3; rodada++) {
                List<ProcessHandle> novos = descendentesNovos(vistos);
                if (novos.isEmpty()) {
                    break;
                }
                vistos.addAll(novos);
                novos.forEach(ProcessHandle::destroy);
            }
            processo.destroy();
            if (!esperar()) {
                vistos.forEach(ProcessHandle::destroyForcibly);
                processo.destroyForcibly();
                esperar();
            }
            // a segunda listagem, depois de o pai morrer: o que ainda está de pé (e o que esses ainda geraram) é morto de uma vez
            List<ProcessHandle> sobraram = new ArrayList<>(vistos);
            for (ProcessHandle h : List.copyOf(vistos)) {
                sobraram.addAll(descendentesDe(h));
            }
            sobraram.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }

        private List<ProcessHandle> descendentesNovos(Set<ProcessHandle> vistos) {
            List<ProcessHandle> novos = new ArrayList<>();
            try {
                processo.descendants().filter(h -> !vistos.contains(h)).forEach(novos::add);
            } catch (RuntimeException e) {
                LOG.debug("Não consegui listar os filhos do programa: {}", e.toString());
            }
            for (ProcessHandle h : List.copyOf(vistos)) {
                for (ProcessHandle d : descendentesDe(h)) {
                    if (!vistos.contains(d) && !novos.contains(d)) {
                        novos.add(d);
                    }
                }
            }
            return novos;
        }

        private static List<ProcessHandle> descendentesDe(ProcessHandle h) {
            try {
                return h.descendants().toList();
            } catch (RuntimeException e) {
                return List.of();
            }
        }

        private boolean esperar() {
            try {
                return processo.waitFor(ESPERA_EM_MILISSEGUNDOS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !processo.isAlive();
            }
        }
    }
}
