package br.com.labdesktop.util;

import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.ConsultaDeSistema;
import br.com.labdesktop.plataforma.ConsultaPorProcesso;
import br.com.labdesktop.plataforma.LeitorDeAparencia;

/**
 * Se o sistema está no modo escuro (ARQUITETURA 8.2: "Sistema" lê o claro/escuro na abertura e ao ganhar foco). Cada sistema tem o seu jeito, num comando fixo
 * ({@link LeitorDeAparencia}): {@code defaults read -g AppleInterfaceStyle} no macOS, {@code reg query ... AppsUseLightTheme} no Windows, {@code gsettings} no Linux.
 *
 * <p>Roda um processo: chame de thread de fundo, nunca da EDT. Este pacote não conhece Swing; quem aplica o resultado ao tema é o {@code GerenciadorTema}, na EDT.
 */
public final class AparenciaDoSistema {

    private AparenciaDoSistema() { }

    /** {@code true} se o sistema está no modo escuro; {@code false} no claro ou se não foi possível saber. */
    public static boolean escuro() {
        return escuro(AmbienteDoSistema.detectar(), new ConsultaPorProcesso());
    }

    /** O mesmo, com o sistema e as respostas dadas (os testes provam os três sistemas sem rodar nada). */
    public static boolean escuro(AmbienteDoSistema ambiente, ConsultaDeSistema consulta) {
        return LeitorDeAparencia.escuro(ambiente, consulta);
    }
}
