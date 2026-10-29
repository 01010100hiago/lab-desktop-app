package br.com.labdesktop.instalador;

import java.util.ArrayList;
import java.util.List;

/** Os ganchos do instalador de mentira: a sonda responde o que o teste mandar (uma resposta por chamada, a última se repete) e o token só anota. */
public final class GanchosFalsos implements GanchosDaInstalacao {

    public final List<String> chamadas = new ArrayList<>();
    private final List<Sonda> respostas = new ArrayList<>(List.of(Sonda.RESPONDE));
    private int proxima;
    public boolean tokenJaValia;
    public Exception falhaAoRegistrar;
    public String dockerVisto;
    public ParametrosDaInstalacao parametrosVistos;
    /** A frase que o servidor dá para recusar qualquer escrita (protegido, ou agente do lab de fora); vazio: pode seguir. */
    public volatile java.util.Optional<String> recusa = java.util.Optional.empty();
    public int consultasDeRecusa;

    public GanchosFalsos sonda(Sonda... r) {
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
    public java.util.Optional<String> recusaDeEscrita() {
        consultasDeRecusa++;
        return recusa;
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
