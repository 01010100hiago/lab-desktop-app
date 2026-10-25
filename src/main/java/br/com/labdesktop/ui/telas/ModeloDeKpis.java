package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.NivelDeSaude;
import br.com.labdesktop.servidor.RegrasDeSaude;
import br.com.labdesktop.servidor.RegrasDeSaude.Avaliacao;
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.util.Formato;
import java.util.ArrayList;
import java.util.List;

/**
 * A primeira linha da Visão geral: no máximo {@value #MAXIMO} indicadores (Saúde, CPU, Memória, Disco, Containers e
 * Segurança), com o valor grande, uma legenda de uma linha e o nível (OK, ATENÇÃO, CRÍTICO). Puro: os limiares estão em
 * {@link RegrasDeSaude}; aqui só se escolhe o que mostrar e em que palavras. Sem dado, o indicador mostra traço.
 */
public final class ModeloDeKpis {

    /** O teto de indicadores na primeira linha (só o essencial de um lab). */
    public static final int MAXIMO = 6;

    /**
     * Um indicador.
     *
     * @param chave   saude, cpu, memoria, disco, containers ou seguranca
     * @param rotulo  o nome curto em cima ("CPU")
     * @param valor   o número grande, em fonte mono
     * @param legenda uma linha embaixo
     * @param dica    o texto completo (tooltip e leitor de tela)
     * @param causa   a frase que a Saúde usa quando este é o pior
     * @param seloTexto a palavra do selo no lugar de OK, Atenção ou Crítico (a página do projeto diz "Pausado"); vazio: o
     *                  indicador não tem selo (só informa um número); {@code null}: a palavra do nível
     */
    public record Kpi(String chave, String rotulo, String icone, String valor, String legenda, String dica,
                      NivelDeSaude nivel, String causa, String seloTexto) {

        /** Indicador comum: o selo diz a palavra do nível. */
        public Kpi(String chave, String rotulo, String icone, String valor, String legenda, String dica, NivelDeSaude nivel,
                   String causa) {
            this(chave, rotulo, icone, valor, legenda, dica, nivel, causa, null);
        }

        /** Sem selo: o indicador só mostra o número. */
        public boolean semSelo() {
            return seloTexto != null && seloTexto.isEmpty();
        }
    }

    private ModeloDeKpis() { }

    /** Monta os seis indicadores. {@code info} e {@code seguranca} podem ser {@code null} (ainda não chegaram). */
    public static List<Kpi> de(InfoServidor info, ResumoDeSeguranca seguranca) {
        Kpi cpu = cpu(info);
        Kpi memoria = memoria(info);
        Kpi disco = disco(info);
        Kpi containers = containers(info);
        Kpi segur = seguranca(seguranca);
        Avaliacao saude = RegrasDeSaude.saude(List.of(
            new Avaliacao(cpu.nivel(), cpu.causa()), new Avaliacao(memoria.nivel(), memoria.causa()),
            new Avaliacao(disco.nivel(), disco.causa()), new Avaliacao(containers.nivel(), containers.causa()),
            new Avaliacao(segur.nivel(), segur.causa())));
        String valor = switch (saude.nivel()) {
            case OK -> "Tudo OK";
            case ATENCAO -> "Atenção";
            case CRITICO -> "Crítico";
            case SEM_DADO -> FormatosDoServidor.SEM_DADO;
        };
        Kpi geral = new Kpi("saude", "SAÚDE", "pulso", valor, saude.causa(),
            "Saúde geral do servidor: o pior estado entre CPU, memória, disco, containers e segurança. " + saude.causa() + ".",
            saude.nivel(), saude.causa());
        List<Kpi> lista = new ArrayList<>(List.of(geral, cpu, memoria, disco, containers, segur));
        return List.copyOf(lista);
    }

    private static Kpi cpu(InfoServidor info) {
        InfoServidor.Cpu c = info == null ? null : info.cpu();
        Double temp = c == null ? null : c.temperatura();
        NivelDeSaude nivel = RegrasDeSaude.temperatura(temp);
        String uso = c == null ? FormatosDoServidor.SEM_DADO : FormatosDoServidor.porcentagem(c.uso());
        String nucleos = c == null || c.nucleos() == null ? null : c.nucleos() + " núcleos";
        // o uso é o número grande; a temperatura vem junto, na legenda (cabe em uma linha em qualquer largura)
        String valor = uso;
        String legenda = temp == null ? (c == null ? FormatosDoServidor.SEM_DADO : "sem sensor de calor")
            : FormatosDoServidor.temperatura(temp) + (nucleos == null ? "" : ", " + nucleos);
        String dica = "Uso da CPU: " + uso + ". Temperatura: " + FormatosDoServidor.temperatura(temp)
            + " (atenção a partir de 85 °C, crítico a partir de 90 °C).";
        return new Kpi("cpu", "CPU", "cpu", valor, legenda, dica, nivel, "CPU a " + FormatosDoServidor.temperatura(temp));
    }

    private static Kpi memoria(InfoServidor info) {
        InfoServidor.Memoria m = info == null ? null : info.memoria();
        Double disp = RegrasDeSaude.pctDisponivel(m);
        NivelDeSaude nivel = RegrasDeSaude.memoria(disp);
        String valor = disp == null ? FormatosDoServidor.SEM_DADO : FormatosDoServidor.porcentagem((100 - disp) / 100);
        String legenda = FormatosDoServidor.SEM_DADO;
        String dica = "Memória em uso real (o total menos o que está disponível, sem contar o cache).";
        if (m != null) {
            InfoServidor.Swap s = m.swap();
            boolean temSwap = s != null && s.total() != null && s.total() > 0 && s.usada() != null;
            legenda = temSwap ? "swap " + FormatosDoServidor.porcentagem((double) s.usada() / s.total()) : "sem swap";
            dica = "Em uso: " + FormatosDoServidor.bytes(m.total() == null || disp == null ? null : m.total() * (100 - disp) / 100)
                + " de " + FormatosDoServidor.bytes(m.total()) + "; disponível " + FormatosDoServidor.bytes(m.disponivel())
                + (temSwap ? "; swap (reserva em disco) " + FormatosDoServidor.bytes(s.usada()) + " de " + FormatosDoServidor.bytes(s.total()) : "")
                + ". Atenção quando o disponível cai abaixo de 15%, crítico abaixo de 8%.";
        }
        return new Kpi("memoria", "MEMÓRIA", "memoria", valor, legenda, dica, nivel,
            "Memória com " + (disp == null ? FormatosDoServidor.SEM_DADO : Formato.fmtNum(disp, 0) + "%") + " disponível");
    }

    private static Kpi disco(InfoServidor info) {
        InfoServidor.Disco d = info == null ? null : RegrasDeSaude.discoMaisCheio(info.discos());
        Double pct = RegrasDeSaude.pctUsado(d);
        NivelDeSaude nivel = RegrasDeSaude.disco(pct);
        if (d == null) {
            return new Kpi("disco", "DISCO", "disco", FormatosDoServidor.SEM_DADO, FormatosDoServidor.SEM_DADO,
                "Sem leitura de disco.", nivel, "Disco sem leitura");
        }
        Long livre = d.livre() != null ? d.livre() : d.total() - d.usado();
        String valor = FormatosDoServidor.porcentagem(pct / 100);
        String legenda = FormatosDoServidor.bytes(livre) + " livres em " + d.montagem();
        String dica = "O disco mais cheio: " + d.montagem() + ", " + FormatosDoServidor.bytes(d.usado()) + " de "
            + FormatosDoServidor.bytes(d.total()) + " (" + valor + "). Atenção a partir de 75%, crítico a partir de 90%.";
        return new Kpi("disco", "DISCO", "disco", valor, legenda, dica, nivel,
            "Disco " + d.montagem() + " a " + Formato.fmtNum(pct, 0) + "%");
    }

    private static Kpi containers(InfoServidor info) {
        InfoServidor.DockerInfo d = info == null ? null : info.docker();
        NivelDeSaude nivel = RegrasDeSaude.containers(d);
        if (d == null || d.rodando() == null) {
            return new Kpi("containers", "CONTAINERS", "docker", FormatosDoServidor.SEM_DADO, FormatosDoServidor.SEM_DADO,
                "Sem leitura do Docker.", nivel, "Docker sem leitura");
        }
        int parados = d.parados() == null ? 0 : d.parados();
        int pausados = d.pausados() == null ? 0 : d.pausados();
        int total = d.containers() != null ? d.containers() : d.rodando() + parados + pausados;
        String valor = d.rodando() + "/" + total;
        String legenda = parados == 0 && pausados == 0 ? "nenhum parado"
            : parados + (parados == 1 ? " parado" : " parados") + (pausados > 0 ? ", " + pausados + " em pausa" : "");
        String dica = d.rodando() + " de " + total + " containers rodando; " + parados + " parados e " + pausados
            + " pausados. Parado é normal: é um projeto desligado.";
        return new Kpi("containers", "CONTAINERS", "docker", valor, legenda, dica, nivel, "Containers");
    }

    private static Kpi seguranca(ResumoDeSeguranca r) {
        Avaliacao a = RegrasDeSaude.seguranca(r);
        String valor = FormatosDoServidor.SEM_DADO;
        String legenda;
        String dica;
        if (r == null) {
            legenda = "lendo…";
            dica = "Lendo o registro de segurança do servidor.";
        } else {
            if (r.tentativas().lida()) {
                valor = Formato.fmtNum(r.tentativas().dados().em24h(), 0) + (r.tentativas().dados().parcial() ? "+" : "");
            }
            legenda = legendaDaSeguranca(r);
            dica = a.causa() + ". " + legenda + ".";
        }
        return new Kpi("seguranca", "SEGURANÇA", "chave", valor, legenda, dica, a.nivel(), a.causa());
    }

    private static String legendaDaSeguranca(ResumoDeSeguranca r) {
        if (r.tentativas().situacao() == ResumoDeSeguranca.Leitura.Situacao.SEM_PERMISSAO) {
            return "sem permissão para ler";
        }
        if (r.fail2ban().lida()) {
            ResumoDeSeguranca.EstadoFail2ban f = r.fail2ban().dados();
            if (!f.ativo()) {
                return "sem bloqueio automático";
            }
            int n = f.banidosAgora() == null ? 0 : f.banidosAgora();
            return n + (n == 1 ? " IP bloqueado" : " IPs bloqueados");
        }
        return r.tentativas().lida() ? "tentativas de entrada" : "registro indisponível";
    }
}
