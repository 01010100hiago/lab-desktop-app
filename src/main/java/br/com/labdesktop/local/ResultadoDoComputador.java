package br.com.labdesktop.local;

import br.com.labdesktop.instalador.ResultadoDaVerificacao.Estado;
import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.List;

/**
 * O resultado de UMA verificação do computador: a marca, a frase simples e, quando falta algo, o que o app propõe (planos de instalação, que só
 * rodam depois do clique e da confirmação) ou o comando que o usuário roda por conta própria.
 *
 * @param comandoSugerido    um comando exato para o usuário (ou o administrador) rodar, ou {@code null}
 * @param planos             o que o app propõe para resolver (o primeiro é o recomendado); vazio se nada a instalar
 * @param oferecerAbrirDocker o botão "Abrir o Docker" faz sentido (instalado e parado)
 */
public record ResultadoDoComputador(ItemDoComputador item, Estado estado, String frase, String comandoSugerido, List<PlanoDeInstalacao> planos,
                                    boolean oferecerAbrirDocker) {

    public ResultadoDoComputador {
        frase = TextoSeguro.limparLinha(frase == null ? "" : frase);
        planos = List.copyOf(planos);
    }

    public static ResultadoDoComputador ok(ItemDoComputador item, String frase) {
        return new ResultadoDoComputador(item, Estado.OK, frase, null, List.of(), false);
    }

    public static ResultadoDoComputador aviso(ItemDoComputador item, String frase, String comando, List<PlanoDeInstalacao> planos) {
        return new ResultadoDoComputador(item, Estado.AVISO, frase, comando, planos, false);
    }

    public static ResultadoDoComputador problema(ItemDoComputador item, String frase, String comando, List<PlanoDeInstalacao> planos) {
        return new ResultadoDoComputador(item, Estado.PROBLEMA, frase, comando, planos, false);
    }

    public boolean bloqueia() {
        return estado == Estado.PROBLEMA;
    }
}
