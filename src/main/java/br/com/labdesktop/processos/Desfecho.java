package br.com.labdesktop.processos;

/** Como terminou uma ação sobre um processo, em palavras que a tela mostra. */
public enum Desfecho {
    /** O processo (ou o container) parou. */
    ENCERRADO("Encerrado"),
    /** O container ou o serviço foi reiniciado. */
    REINICIADO("Reiniciado"),
    /** O pedido foi enviado, o tempo de espera acabou e o processo continua rodando. */
    AINDA_RODANDO("Ainda rodando"),
    /** Quando o app foi agir, o processo já tinha saído. */
    JA_SAIU("Já tinha saído"),
    /** O número agora é de OUTRO processo (o número foi reaproveitado): nada foi enviado. */
    MUDOU("Não é mais o mesmo processo"),
    /** O usuário do app não é o usuário. */
    SEM_PERMISSAO("Sem permissão"),
    /** O servidor não deixa o app conferir a identidade do processo: por segurança, nada foi enviado. */
    SEM_CONFERENCIA("Não deu para conferir"),
    /** Nunca se oferece para este processo. */
    PROTEGIDO("Protegido"),
    /** O comando não rodou ou falhou. */
    FALHOU("Não deu certo"),
    /** O usuário cancelou (e nada mais foi enviado). */
    CANCELADO("Cancelado");

    private final String rotulo;

    Desfecho(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    /** O usuário pode considerar a ação feita (parou, reiniciou, ou já nem existia). */
    public boolean deuCerto() {
        return this == ENCERRADO || this == REINICIADO || this == JA_SAIU;
    }
}
