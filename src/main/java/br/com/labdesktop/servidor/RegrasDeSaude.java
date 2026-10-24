package br.com.labdesktop.servidor;

import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Tentativas;
import br.com.labdesktop.util.Formato;
import java.util.List;

/**
 * Os limiares da primeira linha de indicadores da Visão geral do servidor, numa classe PURA (sem Swing) para os testes
 * baterem nas fronteiras. A tela só traduz o {@link NivelDeSaude} em selo (texto e ícone, nunca só cor).
 *
 * <ul>
 *   <li><b>CPU</b>, pela temperatura: atenção a partir de 85 °C, crítico a partir de 90 °C (o lab é um notebook que já
 *       chegou a 87 °C; é a mesma marca que a guarda de calor do Sondador usa).</li>
 *   <li><b>Memória</b>, pelo que está DISPONÍVEL (não pelo que está "usada", que inclui o cache): atenção abaixo de 15 %,
 *       crítico abaixo de 8 %.</li>
 *   <li><b>Disco</b>, pelo mais cheio: atenção a partir de 75 %, crítico a partir de 90 %.</li>
 *   <li><b>Containers</b>: sempre OK com dados (parados são normais: projeto desligado não é problema).</li>
 *   <li><b>Segurança</b>: atenção se houve tentativa de entrada em 24 h; crítico se o SSH aceita senha ou se entrou alguém
 *       de fora da rede local nas últimas 24 h.</li>
 * </ul>
 */
public final class RegrasDeSaude {

    public static final double TEMPERATURA_ATENCAO = 85.0;
    public static final double TEMPERATURA_CRITICA = 90.0;
    public static final double DISCO_ATENCAO_PCT = 75.0;
    public static final double DISCO_CRITICO_PCT = 90.0;
    public static final double MEMORIA_ATENCAO_DISPONIVEL_PCT = 15.0;
    public static final double MEMORIA_CRITICA_DISPONIVEL_PCT = 8.0;

    /** Um indicador avaliado: o nível e a causa em uma frase. */
    public record Avaliacao(NivelDeSaude nivel, String causa) { }

    private RegrasDeSaude() { }

    /** Temperatura da CPU em °C. */
    public static NivelDeSaude temperatura(Double celsius) {
        if (celsius == null || celsius.isNaN()) {
            return NivelDeSaude.SEM_DADO;
        }
        return celsius >= TEMPERATURA_CRITICA ? NivelDeSaude.CRITICO
            : celsius >= TEMPERATURA_ATENCAO ? NivelDeSaude.ATENCAO : NivelDeSaude.OK;
    }

    /** Porcentagem USADA de um disco (0 a 100). */
    public static NivelDeSaude disco(Double pctUsado) {
        if (pctUsado == null || pctUsado.isNaN()) {
            return NivelDeSaude.SEM_DADO;
        }
        return pctUsado >= DISCO_CRITICO_PCT ? NivelDeSaude.CRITICO
            : pctUsado >= DISCO_ATENCAO_PCT ? NivelDeSaude.ATENCAO : NivelDeSaude.OK;
    }

    /** Porcentagem DISPONÍVEL da memória (0 a 100): quanto menos sobra, pior. */
    public static NivelDeSaude memoria(Double pctDisponivel) {
        if (pctDisponivel == null || pctDisponivel.isNaN()) {
            return NivelDeSaude.SEM_DADO;
        }
        return pctDisponivel < MEMORIA_CRITICA_DISPONIVEL_PCT ? NivelDeSaude.CRITICO
            : pctDisponivel < MEMORIA_ATENCAO_DISPONIVEL_PCT ? NivelDeSaude.ATENCAO : NivelDeSaude.OK;
    }

    /** % disponível da memória, a partir do que o agente manda; {@code null} sem total. */
    public static Double pctDisponivel(InfoServidor.Memoria m) {
        if (m == null || m.total() == null || m.total() <= 0) {
            return null;
        }
        Long livre = m.disponivel() != null ? m.disponivel() : m.usada() != null ? Long.valueOf(m.total() - m.usada()) : null;
        return livre == null ? null : livre * 100.0 / m.total();
    }

    /** % do disco usado; {@code null} sem total. */
    public static Double pctUsado(InfoServidor.Disco d) {
        if (d == null || d.total() == null || d.total() <= 0 || d.usado() == null) {
            return null;
        }
        return d.usado() * 100.0 / d.total();
    }

    /** O disco mais cheio, ou {@code null} se nenhum tem medida. */
    public static InfoServidor.Disco discoMaisCheio(List<InfoServidor.Disco> discos) {
        InfoServidor.Disco pior = null;
        for (InfoServidor.Disco d : discos == null ? List.<InfoServidor.Disco>of() : discos) {
            Double p = pctUsado(d);
            if (p != null && (pior == null || p > pctUsado(pior))) {
                pior = d;
            }
        }
        return pior;
    }

    /** Containers: sem dado só se o Docker não informou; com dado é sempre OK (parados são normais). */
    public static NivelDeSaude containers(InfoServidor.DockerInfo docker) {
        return docker == null || docker.rodando() == null ? NivelDeSaude.SEM_DADO : NivelDeSaude.OK;
    }

    /** A segurança, com a causa. Sem nenhuma seção legível é {@code SEM_DADO}. */
    public static Avaliacao seguranca(ResumoDeSeguranca r) {
        if (r == null) {
            return new Avaliacao(NivelDeSaude.SEM_DADO, "Segurança ainda não lida");
        }
        NivelDeSaude nivel = NivelDeSaude.SEM_DADO;
        String causa = "Não consegui ler o registro de segurança";
        if (r.ssh().lida() && Boolean.TRUE.equals(r.ssh().dados().senhaLigada())) {
            return new Avaliacao(NivelDeSaude.CRITICO, "O SSH aceita senha");
        }
        if (r.tentativas().lida()) {
            Tentativas t = r.tentativas().dados();
            if (t.foraDaRedeEm24h() > 0) {
                return new Avaliacao(NivelDeSaude.CRITICO, "Login aceito de fora da rede local nas últimas 24 h");
            }
            if (t.em24h() > 0) {
                return new Avaliacao(NivelDeSaude.ATENCAO, Formato.fmtNum(t.em24h()) + (t.parcial() ? "+" : "")
                    + (t.em24h() == 1 ? " tentativa de entrada em 24 h" : " tentativas de entrada em 24 h"));
            }
            nivel = NivelDeSaude.OK;
            causa = "Nenhuma tentativa de entrada em 24 h";
        } else if (r.ssh().lida()) {
            nivel = NivelDeSaude.OK;
            causa = "SSH só com chave; registro de tentativas indisponível";
        }
        return new Avaliacao(nivel, causa);
    }

    /** A saúde geral: o PIOR entre os indicadores (com a causa dele); tudo OK diz que não há alerta. */
    public static Avaliacao saude(List<Avaliacao> indicadores) {
        Avaliacao pior = null;
        for (Avaliacao a : indicadores) {
            if (a.nivel() != NivelDeSaude.SEM_DADO && (pior == null || a.nivel().ordinal() > pior.nivel().ordinal())) {
                pior = a;
            }
        }
        if (pior == null) {
            return new Avaliacao(NivelDeSaude.SEM_DADO, "Ainda sem dados do servidor");
        }
        return pior.nivel() == NivelDeSaude.OK ? new Avaliacao(NivelDeSaude.OK, "Nenhum alerta agora") : pior;
    }
}
