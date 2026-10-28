package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A lista deste computador: números do próprio sistema, CPU pela diferença entre duas leituras e a mais ocupada primeiro. */
class LeitorDeProcessosLocaisTest {

    private static ProcessosDoSistema.Info info(long pid, String comando, long cpuMs) {
        return new ProcessosDoSistema.Info(pid, comando, "ana", Instant.parse("2026-10-28T12:00:00Z"), Duration.ofMillis(cpuMs));
    }

    private static ProcessosDoSistema sistema(List<ProcessosDoSistema.Info> lista) {
        return new ProcessosDoSistema() {
            @Override
            public Optional<Info> ler(long pid) {
                return lista.stream().filter(i -> i.pid() == pid).findFirst();
            }

            @Override
            public List<Info> todos() {
                return lista;
            }

            @Override
            public boolean pedirParaFechar(long pid) {
                return false;
            }

            @Override
            public boolean matar(long pid) {
                return false;
            }

            @Override
            public boolean estaVivo(long pid) {
                return true;
            }

            @Override
            public Set<Integer> pidsDoApp() {
                return Set.of();
            }
        };
    }

    @Test
    void primeiraLeituraNaoTemCpuEDepoisAFracaoEntreAsLeituras() {
        List<ProcessosDoSistema.Info> lista = new ArrayList<>();
        lista.add(info(100, "/usr/bin/quieto", 1000));
        lista.add(info(200, "/usr/bin/ocupado", 1000));
        AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-10-28T12:00:10Z"));
        LeitorDeProcessosLocais l = new LeitorDeProcessosLocais(sistema(lista), agora::get);
        List<LeitorDeProcessosLocais.Linha> primeira = l.ler();
        assertEquals(2, primeira.size());
        assertNull(primeira.get(0).cpu());

        lista.set(0, info(100, "/usr/bin/quieto", 1000));
        lista.set(1, info(200, "/usr/bin/ocupado", 6000)); // 5 s de processador em 10 s = 50 %
        agora.set(Instant.parse("2026-10-28T12:00:20Z"));
        List<LeitorDeProcessosLocais.Linha> segunda = l.ler();
        assertEquals("ocupado", segunda.get(0).alvo().nome(), "o mais ocupado vem primeiro");
        assertEquals(50.0, segunda.get(0).cpu(), 0.5);
        assertEquals(0.0, segunda.get(1).cpu(), 0.01);
        assertEquals(200, segunda.get(0).alvo().pid());
    }

    @Test
    void semComandoNaoEntraEAListaTemTeto() {
        List<ProcessosDoSistema.Info> lista = new ArrayList<>();
        lista.add(info(5, "", 10));
        for (int i = 0; i < 450; i++) {
            lista.add(info(1000 + i, "/bin/p" + i, i));
        }
        List<LeitorDeProcessosLocais.Linha> l = new LeitorDeProcessosLocais(sistema(lista), Instant::now).ler();
        assertEquals(LeitorDeProcessosLocais.MAXIMO, l.size());
        assertTrue(l.stream().noneMatch(x -> x.alvo().pid() == 5));
    }
}
