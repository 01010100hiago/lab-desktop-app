package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.NivelDeSaude;
import br.com.labdesktop.ui.Gravidade;

/**
 * Como um {@link NivelDeSaude} aparece: SEMPRE o texto (OK, ATENÇÃO, CRÍTICO) junto de um ícone do conjunto do app,
 * nunca só a cor. O terracota do crítico é o {@code PERIGO} da paleta.
 */
final class ApresentacaoDeSaude {

    private ApresentacaoDeSaude() { }

    static String texto(NivelDeSaude n) {
        return switch (n) {
            case OK -> "OK";
            case ATENCAO -> "Atenção";
            case CRITICO -> "Crítico";
            case SEM_DADO -> "Sem dado";
        };
    }

    static String icone(NivelDeSaude n) {
        return switch (n) {
            case OK -> "ok-circulo";
            case ATENCAO -> "alerta";
            case CRITICO -> "erro";
            case SEM_DADO -> "info";
        };
    }

    static Gravidade gravidade(NivelDeSaude n) {
        return switch (n) {
            case OK -> Gravidade.OK;
            case ATENCAO -> Gravidade.ATENCAO;
            case CRITICO -> Gravidade.PERIGO;
            case SEM_DADO -> Gravidade.NEUTRO;
        };
    }
}
