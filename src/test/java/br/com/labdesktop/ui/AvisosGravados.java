package br.com.labdesktop.ui;

import br.com.labdesktop.ui.componentes.Notificacoes;
import java.util.List;

/** Um {@link Notificacoes} que só anota o que foi dito, para os testes conferirem as mensagens. */
public final class AvisosGravados implements Notificacoes {

    /** Uma mensagem: o tipo ("ok", "info", "atencao", "erro", "acao") e o texto. */
    public record Aviso(String tipo, String texto) { }

    public final List<Aviso> todos = new java.util.concurrent.CopyOnWriteArrayList<>(); // a EDT escreve e o teste lê esperando
    public Runnable ultimaAcao;

    @Override
    public void ok(String texto) {
        todos.add(new Aviso("ok", texto));
    }

    @Override
    public void info(String texto) {
        todos.add(new Aviso("info", texto));
    }

    @Override
    public void atencao(String texto) {
        todos.add(new Aviso("atencao", texto));
    }

    @Override
    public void erro(String texto) {
        todos.add(new Aviso("erro", texto));
    }

    @Override
    public void comAcao(String texto, String rotuloDaAcao, Runnable acao) {
        todos.add(new Aviso("acao", texto));
        ultimaAcao = acao;
    }

    /** Só os textos, na ordem. */
    public List<String> textos() {
        return todos.stream().map(Aviso::texto).toList();
    }

    public Aviso ultimo() {
        return todos.isEmpty() ? null : todos.get(todos.size() - 1);
    }

    public boolean disse(String tipo, String trecho) {
        return todos.stream().anyMatch(a -> a.tipo().equals(tipo) && a.texto().contains(trecho));
    }
}
