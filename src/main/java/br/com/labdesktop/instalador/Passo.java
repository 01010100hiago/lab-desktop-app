package br.com.labdesktop.instalador;

/** Um passo que a tela mostra com o estado ao vivo (instalar, remover): o nome estável e a frase para o usuário. */
public interface Passo {

    /** O identificador estável (o nome da constante). */
    String name();

    /** A frase do passo, em português simples. */
    String rotulo();
}
