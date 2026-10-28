package br.com.labdesktop.util;

import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.ConsultaDeSistema;
import br.com.labdesktop.plataforma.ConsultaPorProcesso;
import br.com.labdesktop.plataforma.SistemaOperacional;
import java.time.Duration;
import java.util.List;

/**
 * Nome deste computador, para o comentário da chave pública ({@code lab-desktop@<nome>}) e para sugerir o {@code nome_do_dispositivo} do token.
 *
 * <p>Não usa {@code InetAddress.getLocalHost()}: ele faz resolução de DNS e pode levar segundos (ou travar) sem rede. No Windows lê {@code COMPUTERNAME} (variável do sistema, sem processo); nos
 * outros {@code HOSTNAME} se existir (o macOS e muitos Linux não a exportam) e senão roda {@code hostname}, que lê o nome local direto, com prazo curto, sem shell.
 */
public final class NomeDoComputador {

    private static final String PADRAO = "pc";

    private NomeDoComputador() { }

    /** Nome do computador, sem quebras de linha; {@code "pc"} se não conseguir. Pode bloquear por até ~2 s: fora da EDT. */
    public static String hostname() {
        return hostname(AmbienteDoSistema.detectar(), new ConsultaPorProcesso());
    }

    /** O mesmo com o ambiente e a consulta dados (os testes provam o Windows e o Mac sem rodar nada). */
    public static String hostname(AmbienteDoSistema ambiente, ConsultaDeSistema consulta) {
        String variavel = ambiente.variavel(ambiente.sistema() == SistemaOperacional.WINDOWS ? "COMPUTERNAME" : "HOSTNAME");
        if (variavel != null) {
            return limpar(variavel);
        }
        ConsultaDeSistema.Resposta r = consulta.perguntar(List.of("hostname"), Duration.ofSeconds(2));
        String saida = r.ok() ? limpar(r.saida()) : "";
        return saida.isEmpty() ? PADRAO : saida;
    }

    private static String limpar(String texto) {
        return texto.replaceAll("[\\r\\n]+", " ").strip();
    }
}
