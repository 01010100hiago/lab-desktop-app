package br.com.labdesktop.transporte;

import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import java.util.function.Consumer;

/**
 * O {@link AbridorDeDestino} de verdade: direto devolve o endereço do servidor; SSM e Cloudflare sobem um {@link TunelDeTransporte}. Tudo o que toca o computador (lançar programa,
 * achar programa, portas) entra por interface, e os testes trocam cada uma.
 */
public final class AbridorDeTuneis implements AbridorDeDestino {

    private final TunelDeTransporte.Dependencias dependencias;

    public AbridorDeTuneis(LancadorDeProcesso lancador, LocalizadorDeFerramenta localizador, PortasLocais portas, RegistroDeTuneis registro, ConfigDoTunel config,
                           AmbienteDoSistema ambiente) {
        String path = ambiente.variavel("PATH") != null ? ambiente.variavel("PATH") : ambiente.variavel("Path");
        this.dependencias = new TunelDeTransporte.Dependencias(lancador, localizador, portas, registro, config, ambiente.sistema(), path,
            ambiente.sistema() == SistemaOperacional.WINDOWS ? ";" : ":");
    }

    private static final class Unico {
        static final AbridorDeTuneis PADRAO = deProducao();
    }

    /** O de produção, um só por processo (o app todo compartilha o registro do gancho de desligamento). */
    public static AbridorDeTuneis padrao() {
        return Unico.PADRAO;
    }

    private static AbridorDeTuneis deProducao() {
        DescobertaDeFerramentas descoberta = DescobertaDeFerramentas.doSistema();
        return new AbridorDeTuneis(new LancadorReal(), descoberta, PortasLocais.doSistema(), RegistroDeTuneis.doProcesso(), ConfigDoTunel.padrao(), descoberta.ambiente());
    }

    @Override
    public DestinoSsh abrir(Transporte transporte, String host, int porta, Consumer<FalhaDoTunel> aoCair) throws FalhaDoTunelException, InterruptedException {
        if (transporte.ehTunel()) {
            return TunelDeTransporte.abrir(transporte, dependencias, aoCair);
        }
        if (transporte.tipo() == Transporte.Tipo.LOCAL) {
            throw new IllegalArgumentException("Este computador não tem SSH.");
        }
        return DestinoSsh.direto(host, porta);
    }
}
