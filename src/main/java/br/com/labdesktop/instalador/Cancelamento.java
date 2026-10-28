package br.com.labdesktop.instalador;

/** O botão Cancelar da tela: o instalador o consulta entre os comandos (e a tela também interrompe a thread, para soltar o comando em curso). */
@FunctionalInterface
public interface Cancelamento {

    boolean cancelado();

    Cancelamento NUNCA = () -> false;
}
