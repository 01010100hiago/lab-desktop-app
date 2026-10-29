package br.com.labdesktop.teste;

import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.transporte.AbridorDeTuneis;
import br.com.labdesktop.transporte.ConfigDoTunel;
import br.com.labdesktop.transporte.DestinoSsh;
import br.com.labdesktop.transporte.PortasLocais;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import java.time.Duration;
import java.util.Map;

/**
 * O programa de uma JVM FILHA do teste do gancho de desligamento: abre um túnel (com a ferramenta falsa) pelo registro DO PROCESSO, que tem o gancho, escreve o pid da
 * ferramenta e SAI sem fechar o túnel. Quem prova que o processo da ferramenta não ficou órfão é o teste que a lançou.
 */
public final class SaidaDaJvmComTunel {

    private SaidaDaJvmComTunel() { }

    public static void main(String[] args) throws Exception {
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(1, Map.of());
        AbridorDeTuneis abridor = new AbridorDeTuneis(lancador, FerramentasFalsasDeTunel.tudoInstalado(), PortasLocais.doSistema(), RegistroDeTuneis.doProcesso(),
            ConfigDoTunel.padrao().comPrazo(Duration.ofSeconds(25)), AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of("PATH", "/usr/bin")));
        DestinoSsh d = abridor.abrir(Transporte.cloudflared("ssh.example.com"), "x", 1);
        System.out.println("PID " + lancador.lancados.get(0).processo().pid());
        System.out.println("PORTA " + d.porta());
        System.out.flush();
        if (args.length > 0 && args[0].equals("morrer-sem-gancho")) {
            Runtime.getRuntime().halt(0); // sem gancho nenhum: o caso que NÃO dá para cobrir (kill -9)
        }
        System.exit(0); // saída normal: roda os ganchos de desligamento
    }
}
