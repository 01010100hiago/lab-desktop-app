package br.com.labdesktop.instalador;

/**
 * Quem acompanha a instalação ao vivo (a tela). Chamado NA THREAD do instalador (nunca na EDT): quem desenha despacha para a EDT.
 */
public interface ObservadorDaInstalacao {

    /** Um passo mudou de estado; {@code detalhe} é uma frase curta (o erro real do servidor, em texto, se falhou). */
    void aoMudarPasso(Passo passo, EstadoDoPasso estado, String detalhe);

    /** Uma linha do log completo (o comando que rodou e o fim da saída dele), para o painel recolhível. */
    void aoRegistrar(String linha);

    /** Não observa nada. */
    ObservadorDaInstalacao NENHUM = new ObservadorDaInstalacao() {
        @Override
        public void aoMudarPasso(Passo passo, EstadoDoPasso estado, String detalhe) { }

        @Override
        public void aoRegistrar(String linha) { }
    };
}
