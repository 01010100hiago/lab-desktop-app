package br.com.labdesktop.local;

/**
 * A porta e a pasta dos projetos que o usuário escolheu, e o que as guarda (o cofre e a memória de pastas). {@link LaboratorioLocal} só pede para
 * GRAVAR depois que o agente respondeu na porta nova, e para RESTAURAR se o que veio depois falhar ou for cancelado: uma criação que não deu
 * certo não deixa a porta nem a pasta errada guardadas.
 */
public interface EscolhasDoLaboratorio {

    /** Sem nada a guardar (os testes do domínio). */
    EscolhasDoLaboratorio NENHUMAS = new EscolhasDoLaboratorio() {
        @Override
        public void gravar() { }

        @Override
        public void restaurar() { }
    };

    /** Guarda a porta e a pasta escolhidas. */
    void gravar() throws Exception;

    /** Devolve o que estava guardado antes de {@link #gravar}. */
    void restaurar() throws Exception;
}
