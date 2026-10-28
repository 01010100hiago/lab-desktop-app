package br.com.labdesktop.instalador;

import java.util.ArrayList;
import java.util.List;

/** Os ganchos do instalador de mentira: a sonda responde o que o teste mandar (uma resposta por chamada, a última se repete) e o token só anota. */
final class GanchosFalsos implements GanchosDaInstalacao {

    final List<String> chamadas = new ArrayList<>();
    private final List<Sonda> respostas = new ArrayList<>(List.of(Sonda.RESPONDE));
    private int proxima;
    boolean tokenJaValia;
    Exception falhaAoRegistrar;
    String dockerVisto;
    ParametrosDaInstalacao parametrosVistos;

    GanchosFalsos sonda(Sonda... r) {
        respostas.clear();
        respostas.addAll(List.of(r));
        proxima = 0;
        return this;
    }

    @Override
    public Sonda sondar() {
        chamadas.add("sondar");
        Sonda s = respostas.get(Math.min(proxima, respostas.size() - 1));
        proxima++;
        return s;
    }

    @Override
    public boolean registrarToken(ParametrosDaInstalacao parametros, String docker) throws Exception {
        chamadas.add("registrarToken");
        parametrosVistos = parametros;
        dockerVisto = docker;
        if (falhaAoRegistrar != null) {
            throw falhaAoRegistrar;
        }
        return !tokenJaValia;
    }
}
