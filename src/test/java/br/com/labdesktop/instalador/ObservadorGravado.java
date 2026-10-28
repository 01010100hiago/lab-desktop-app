package br.com.labdesktop.instalador;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Anota o que o instalador mostrou: o último estado de cada passo, a ordem das mudanças e o log. */
public final class ObservadorGravado implements ObservadorDaInstalacao {

    public record Mudanca(Passo passo, EstadoDoPasso estado, String detalhe) { }

    public final List<Mudanca> mudancas = new ArrayList<>();
    public final List<String> log = new ArrayList<>();
    public final Map<PassoDeInstalacao, EstadoDoPasso> ultimo = new EnumMap<>(PassoDeInstalacao.class);

    @Override
    public void aoMudarPasso(Passo passo, EstadoDoPasso estado, String detalhe) {
        mudancas.add(new Mudanca(passo, estado, detalhe));
        if (passo instanceof PassoDeInstalacao p) {
            ultimo.put(p, estado);
        }
    }

    @Override
    public void aoRegistrar(String linha) {
        log.add(linha);
    }

    public String detalheDoFalhou() {
        return mudancas.stream().filter(m -> m.estado() == EstadoDoPasso.FALHOU).map(Mudanca::detalhe).findFirst().orElse("");
    }
}
