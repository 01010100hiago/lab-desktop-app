package br.com.labdesktop.processos;

/** Quem acompanha a ação (a tela): um passo em palavras e, na espera depois do pedido, os segundos que já passaram. Chamado de thread de fundo. */
public interface ObservadorDeProcesso {

    /** Um passo ("Enviando o pedido de encerramento..."). */
    void etapa(String texto);

    /** Esperando o programa fechar: {@code passados} de {@code total} segundos. */
    void aguardando(int passados, int total);

    ObservadorDeProcesso NENHUM = new ObservadorDeProcesso() {
        @Override
        public void etapa(String texto) { }

        @Override
        public void aguardando(int passados, int total) { }
    };
}
