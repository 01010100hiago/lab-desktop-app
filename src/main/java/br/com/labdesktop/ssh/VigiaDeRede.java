package br.com.labdesktop.ssh;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Descobre que a REDE do computador mudou (outra rede Wi-Fi, a VPN subiu ou caiu, o cabo voltou), para um servidor que está esperando no ritmo lento (uma tentativa a cada 5 minutos) tentar
 * logo em vez de esperar o fim da espera. O Java não avisa mudança de rede: a vigia olha, a cada {@link #INTERVALO}, o conjunto de endereços das interfaces ligadas (sem loopback nem endereço
 * de uso local) e, se ele mudou desde a última olhada, avisa o {@link MonitorDeSono}. O primeiro retrato só vira referência (não avisa). Nada de comando externo nem de rede: só lê o que o sistema
 * já sabe. O retrato é injetado ({@code Supplier}) e {@link #conferir()} é público para o teste andar sem esperar. Quem recebe o aviso só age se está em {@code RECONECTANDO}.
 */
public final class VigiaDeRede implements AutoCloseable {

    public static final Duration INTERVALO = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(VigiaDeRede.class);

    private final MonitorDeSono monitor;
    private final Supplier<Set<String>> retrato;
    /** {@code null}: ainda não há retrato de referência (a primeira leitura só vira referência e não avisa). */
    private Set<String> ultimo;
    private ScheduledExecutorService executor;

    /** De onde vêm as interfaces de rede do sistema: pode devolver {@code null} (alguns JDK devolvem quando não há interface nenhuma) ou lançar. */
    @FunctionalInterface
    interface Interfaces {
        Enumeration<NetworkInterface> listar() throws SocketException;
    }

    public VigiaDeRede(MonitorDeSono monitor, Supplier<Set<String>> retrato) {
        this.monitor = monitor;
        this.retrato = retrato;
        this.ultimo = lerSemFalhar();
    }

    /** Uma leitura que nunca lança: {@code null} se não deu para ler agora. */
    private Set<String> lerSemFalhar() {
        try {
            Set<String> r = retrato.get();
            return r == null ? Set.of() : r;
        } catch (RuntimeException e) {
            LOG.debug("Não consegui olhar a rede: {}", e.toString());
            return null;
        }
    }

    /** O de produção: as interfaces de verdade, a cada 10 s, numa thread de fundo que não segura o app aberto. */
    public static VigiaDeRede iniciar(MonitorDeSono monitor) {
        VigiaDeRede v = new VigiaDeRede(monitor, VigiaDeRede::enderecosDasInterfaces);
        v.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vigia-de-rede");
            t.setDaemon(true);
            return t;
        });
        v.executor.scheduleWithFixedDelay(v::conferir, INTERVALO.toMillis(), INTERVALO.toMillis(), TimeUnit.MILLISECONDS);
        return v;
    }

    /** Uma olhada: se o conjunto de endereços mudou desde a última, avisa. */
    public synchronized void conferir() {
        Set<String> agora = lerSemFalhar();
        if (agora == null) {
            return;
        }
        if (ultimo == null) {
            ultimo = agora; // a primeira leitura que deu certo é só a referência
            return;
        }
        if (agora.equals(ultimo)) {
            return;
        }
        ultimo = agora;
        LOG.info("A rede do computador mudou ({} endereço(s) agora).", agora.size());
        monitor.redeMudou();
    }

    /** Os endereços das interfaces ligadas e que não são loopback nem de uso local (link-local); texto simples, em ordem. */
    static Set<String> enderecosDasInterfaces() {
        return enderecos(NetworkInterface::getNetworkInterfaces);
    }

    /** Como {@link #enderecosDasInterfaces()}, de onde se queira. Lista {@code null} (sem interface), {@link SocketException} e qualquer erro ao ler uma interface valem "sem endereços": nunca lançam. */
    static Set<String> enderecos(Interfaces fonte) {
        Set<String> r = new TreeSet<>();
        try {
            Enumeration<NetworkInterface> todas = fonte.listar();
            if (todas == null) {
                return r;
            }
            for (NetworkInterface i : Collections.list(todas)) {
                try {
                    if (i == null || !i.isUp() || i.isLoopback()) {
                        continue;
                    }
                    for (InetAddress a : Collections.list(i.getInetAddresses())) {
                        if (!a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                            r.add(i.getName() + "=" + a.getHostAddress());
                        }
                    }
                } catch (SocketException | RuntimeException e) {
                    LOG.debug("Não consegui ler uma interface de rede: {}", e.toString()); // esta interface não conta; as outras valem
                }
            }
        } catch (SocketException | RuntimeException e) {
            return Set.of(); // sem leitura agora: o mesmo que "sem rede"
        }
        return r;
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
