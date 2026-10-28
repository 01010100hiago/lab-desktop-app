package br.com.labdesktop.processos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Os processos DESTE computador, vistos pelo Java ({@link ProcessHandle}): funciona igual no Mac, no Linux e no Windows, sem rodar programa nenhum
 * para LER. É a "tomada" do {@link ControleLocal} e do {@link LeitorDeProcessosLocais}; os testes entregam uma versão falsa ou usam processos de
 * verdade (um {@code sleep} aberto pelo próprio teste).
 */
public interface ProcessosDoSistema {

    /**
     * O que o sistema diz de um processo.
     *
     * @param comando  o caminho do programa
     * @param usuario  o usuário, como o sistema o escreve (no Windows, {@code PC\ana}); vazio se o sistema não disse
     * @param inicio   quando começou
     * @param cpuTotal o tempo de processador gasto desde que começou ({@code null} se o sistema não disse)
     */
    record Info(long pid, String comando, String usuario, Instant inicio, Duration cpuTotal) {

        public Info {
            comando = comando == null ? "" : comando;
            usuario = usuario == null ? "" : usuario;
        }
    }

    Optional<Info> ler(long pid);

    List<Info> todos();

    /** Pede ao programa para fechar (SIGTERM no Mac e no Linux). {@code false} se o sistema não aceitou. */
    boolean pedirParaFechar(long pid);

    /** Mata à força (SIGKILL no Mac e no Linux). */
    boolean matar(long pid);

    boolean estaVivo(long pid);

    /** O processo do app e os que o abriram (nunca se oferece nenhum deles). */
    Set<Integer> pidsDoApp();

    /** O de verdade, sobre o {@link ProcessHandle}. */
    static ProcessosDoSistema real() {
        return new ProcessosDoSistema() {
            @Override
            public Optional<Info> ler(long pid) {
                return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(ProcessosDoSistema::deHandle);
            }

            @Override
            public List<Info> todos() {
                List<Info> l = new ArrayList<>();
                ProcessHandle.allProcesses().forEach(h -> {
                    if (h.isAlive()) {
                        l.add(deHandle(h));
                    }
                });
                return l;
            }

            @Override
            public boolean pedirParaFechar(long pid) {
                return ProcessHandle.of(pid).map(ProcessHandle::destroy).orElse(false);
            }

            @Override
            public boolean matar(long pid) {
                return ProcessHandle.of(pid).map(ProcessHandle::destroyForcibly).orElse(false);
            }

            @Override
            public boolean estaVivo(long pid) {
                return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            }

            @Override
            public Set<Integer> pidsDoApp() {
                Set<Integer> s = new LinkedHashSet<>();
                Optional<ProcessHandle> h = Optional.of(ProcessHandle.current());
                while (h.isPresent()) {
                    long p = h.get().pid();
                    if (p <= Integer.MAX_VALUE) {
                        s.add((int) p);
                    }
                    h = h.get().parent();
                }
                return s;
            }
        };
    }

    private static Info deHandle(ProcessHandle h) {
        ProcessHandle.Info i = h.info();
        return new Info(h.pid(), i.command().orElse(""), i.user().orElse(""), i.startInstant().orElse(null), i.totalCpuDuration().orElse(null));
    }
}
