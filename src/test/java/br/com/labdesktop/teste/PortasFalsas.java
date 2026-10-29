package br.com.labdesktop.teste;

import br.com.labdesktop.transporte.PortasLocais;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** As portas de mentira: entrega portas escolhidas pelo teste e diz quem "escuta" em cada uma. */
public final class PortasFalsas implements PortasLocais {

    private final Deque<Integer> fila = new ArrayDeque<>();
    private final Set<Integer> escutando = ConcurrentHashMap.newKeySet();
    public final List<Integer> reservadas = new CopyOnWriteArrayList<>();

    public PortasFalsas(Integer... portas) {
        fila.addAll(List.of(portas));
    }

    @Override
    public synchronized int reservar() throws IOException {
        Integer p = fila.pollFirst();
        if (p == null) {
            throw new IOException("sem porta livre (teste)");
        }
        reservadas.add(p);
        return p;
    }

    @Override
    public boolean alguemEscuta(int porta) {
        return escutando.contains(porta);
    }

    public void escutar(int porta) {
        escutando.add(porta);
    }

    public void parar(int porta) {
        escutando.remove(porta);
    }

    public boolean escutaAlguem(int porta) {
        return escutando.contains(porta);
    }
}
