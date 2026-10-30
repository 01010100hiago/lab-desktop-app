package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A rede que muda (outra rede Wi-Fi, a VPN) vira um aviso para quem espera reconectar: o retrato de endereços é falso, nenhuma espera de verdade. */
class VigiaDeRedeTest {

    private final AtomicReference<Set<String>> rede = new AtomicReference<>(Set.of("en0=192.0.2.10"));
    private final List<String> eventos = new ArrayList<>();
    private final MonitorDeSono monitor = new MonitorDeSono();
    private final AtomicReference<RuntimeException> falha = new AtomicReference<>();
    private final VigiaDeRede vigia = new VigiaDeRede(monitor, () -> {
        if (falha.get() != null) {
            throw falha.get();
        }
        return rede.get();
    });

    VigiaDeRedeTest() {
        monitor.aoMudarARede(() -> eventos.add("rede"));
    }

    @Test
    void semMudancaNaoAvisaENuncaAvisaNoPrimeiroRetrato() {
        for (int i = 0; i < 20; i++) {
            vigia.conferir();
        }
        assertEquals(List.of(), eventos);
    }

    @Test
    void enderecoNovoOuQueSumiuAvisaUmaVezSo() {
        rede.set(Set.of("en0=198.51.100.7"));
        vigia.conferir();
        vigia.conferir();
        assertEquals(List.of("rede"), eventos, "mudou uma vez: um aviso");
        rede.set(Set.of("en0=198.51.100.7", "utun3=203.0.113.9")); // a VPN subiu
        vigia.conferir();
        rede.set(Set.of()); // caiu tudo
        vigia.conferir();
        rede.set(Set.of("en0=192.0.2.10")); // voltou
        vigia.conferir();
        assertEquals(List.of("rede", "rede", "rede", "rede"), eventos);
    }

    @Test
    void umaLeituraQueFalhaNaoAvisaNemQuebra() {
        falha.set(new IllegalStateException("sem leitura agora"));
        vigia.conferir();
        assertEquals(List.of(), eventos);
        falha.set(null);
        vigia.conferir();
        assertEquals(List.of(), eventos, "a leitura que falhou não mexeu na referência");
    }

    @Test
    void quemDesfazORegistroNaoRecebeMais() {
        List<String> outros = new ArrayList<>();
        Runnable desfazer = monitor.aoMudarARede(() -> outros.add("x"));
        rede.set(Set.of());
        vigia.conferir();
        assertEquals(List.of("x"), outros);
        desfazer.run();
        rede.set(Set.of("en0=192.0.2.11"));
        vigia.conferir();
        assertEquals(List.of("x"), outros);
        assertEquals(List.of("rede", "rede"), eventos);
    }

    @Test
    void oRetratoDeVerdadeSoTemEnderecosDeInterfacesLigadasSemLoopbackNemLinkLocal() {
        Set<String> real = VigiaDeRede.enderecosDasInterfaces();
        for (String e : real) {
            assertTrue(e.contains("="), e);
            assertFalse(e.contains("127.0.0.1") || e.contains("=::1") || e.contains("=fe80"), "sem loopback nem link-local: " + e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Nenhuma exceção ao olhar a rede
    // ---------------------------------------------------------------------------------------------

    @Test
    void listaNulaOuErroAoListarAsInterfacesValemSemEnderecosENuncaLancam() {
        assertEquals(Set.of(), VigiaDeRede.enderecos(() -> null), "alguns JDK devolvem null quando não há interface nenhuma (era um NPE em Aplicacao.montar, na EDT)");
        assertEquals(Set.of(), VigiaDeRede.enderecos(() -> {
            throw new java.net.SocketException("sem interfaces");
        }));
        assertEquals(Set.of(), VigiaDeRede.enderecos(() -> {
            throw new IllegalStateException("o JDK falhou");
        }));
        assertEquals(Set.of(), VigiaDeRede.enderecos(() -> java.util.Collections.enumeration(java.util.Arrays.asList((java.net.NetworkInterface) null))), "uma interface nula não conta");
        assertEquals(Set.of(), VigiaDeRede.enderecos(() -> java.util.Collections.emptyEnumeration()));
        // e a de verdade, de novo, sem lançar
        VigiaDeRede.enderecosDasInterfaces();
    }

    @Test
    void oConstrutorNuncaLancaEUmRetratoQueFalhouNaoViraAvisoDeMudanca() {
        List<String> avisos = new ArrayList<>();
        MonitorDeSono m = new MonitorDeSono();
        m.aoMudarARede(() -> avisos.add("rede"));
        AtomicReference<Object> modo = new AtomicReference<>("lança");
        AtomicReference<Set<String>> atual = new AtomicReference<>(Set.of("en0=192.0.2.10"));
        VigiaDeRede v = new VigiaDeRede(m, () -> {
            if (modo.get().equals("lança")) {
                throw new IllegalStateException("sem leitura");
            }
            return modo.get().equals("nulo") ? null : atual.get();
        });
        v.conferir();
        assertEquals(List.of(), avisos, "ainda sem leitura");
        modo.set("ok");
        v.conferir();
        assertEquals(List.of(), avisos, "a primeira leitura que deu certo é só a referência: não é mudança de rede");
        atual.set(Set.of("en0=198.51.100.7"));
        v.conferir();
        assertEquals(List.of("rede"), avisos);
        modo.set("nulo");
        v.conferir(); // um retrato nulo vale "sem endereços" (e não quebra)
        assertEquals(List.of("rede", "rede"), avisos);
    }
}
