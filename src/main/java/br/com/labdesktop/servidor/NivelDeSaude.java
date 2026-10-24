package br.com.labdesktop.servidor;

/**
 * O estado de um indicador de saúde do servidor (primeira linha da Visão geral). A ordem importa: do menos ao mais
 * grave, para {@link #pior} escolher o pior entre vários. {@code SEM_DADO} é o indicador que não pôde ser medido
 * (nunca conta como problema).
 */
public enum NivelDeSaude {
    SEM_DADO, OK, ATENCAO, CRITICO;

    /** O mais grave dos dois (em empate, este). */
    public NivelDeSaude pior(NivelDeSaude outro) {
        return outro.ordinal() > ordinal() ? outro : this;
    }
}
