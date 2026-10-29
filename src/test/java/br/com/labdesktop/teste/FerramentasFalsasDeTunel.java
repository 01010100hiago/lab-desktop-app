package br.com.labdesktop.teste;

import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.transporte.AbridorDeTuneis;
import br.com.labdesktop.transporte.ConfigDoTunel;
import br.com.labdesktop.transporte.LancadorDeProcesso;
import br.com.labdesktop.transporte.LocalizadorDeFerramenta;
import br.com.labdesktop.transporte.PortasLocais;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * O {@link AbridorDeTuneis} de teste: lançador e localizador de FERRAMENTAS FALSAS (nunca a aws nem o cloudflared de verdade), portas de verdade só em 127.0.0.1 e um
 * registro SEM o gancho de desligamento da JVM (o teste encerra tudo por conta própria).
 */
public final class FerramentasFalsasDeTunel {

    private FerramentasFalsasDeTunel() { }

    /** Todas as ferramentas "instaladas" (caminhos de mentira, que só o lançador falso interpreta). */
    public static LocalizadorDeFerramenta tudoInstalado() {
        return f -> Optional.of(switch (f) {
            case AWS -> "/ferramentas/aws";
            case PLUGIN_SSM -> "/ferramentas/plugin/session-manager-plugin";
            case CLOUDFLARED -> "/ferramentas/cloudflared";
        });
    }

    public static AbridorDeTuneis abridor(LancadorDeProcesso lancador, RegistroDeTuneis registro) {
        return abridor(lancador, tudoInstalado(), registro);
    }

    public static AbridorDeTuneis abridor(LancadorDeProcesso lancador, LocalizadorDeFerramenta localizador, RegistroDeTuneis registro) {
        return new AbridorDeTuneis(lancador, localizador, PortasLocais.doSistema(), registro, ConfigDoTunel.padrao().comPrazo(Duration.ofSeconds(25)),
            AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of("PATH", "/usr/bin:/bin")));
    }
}
