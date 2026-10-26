package br.com.labdesktop.ui.terminal;

import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.terminal.TransporteDeTerminal;
import br.com.labdesktop.terminal.TransporteSsh;
import br.com.labdesktop.ui.Navegador;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Preferencias;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * O que um {@link PainelTerminal} recebe de fora, num lugar só: por onde abrir canais, quantos terminais o servidor ainda comporta,
 * as preferências do usuário (lidas a cada anexação, então mudar uma vale no próximo terminal), quem abre um link e o relógio.
 * Um contexto por servidor: as abas do shell e os terminais das sessões do Claude dos projetos dividem o mesmo {@link LimiteDeAbas}.
 *
 * @param transporte   por onde os canais abrem (SSH hoje)
 * @param limite       as vagas de terminal do servidor (8)
 * @param preferencias as preferências em uso
 * @param abrirLink    abre um link do terminal no navegador (Cmd+clique; só http e https)
 * @param agendador    relógio e timers (o do sistema, ou o de teste)
 */
public record ContextoDeTerminal(
    TransporteDeTerminal transporte,
    LimiteDeAbas limite,
    Supplier<Preferencias> preferencias,
    Consumer<String> abrirLink,
    Agendador agendador) {

    /** O contexto de produção de um servidor vivo. */
    public static ContextoDeTerminal de(ServidorLab lab, Supplier<Preferencias> preferencias, Navegador navegador) {
        Supplier<Preferencias> prefs = preferencias == null ? Preferencias::padrao : preferencias;
        return new ContextoDeTerminal(new TransporteSsh(lab.conexao(), () -> prefs.get().donoExclusivo()), new LimiteDeAbas(), prefs,
            navegador == null ? url -> { } : navegador::abrirLinkDoTerminal, Agendador.doFundo());
    }
}
