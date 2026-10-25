package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.NivelDeSaude;
import br.com.labdesktop.servidor.RegrasDeSaude;
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.util.Formato;
import java.util.ArrayList;
import java.util.List;

/**
 * O que vai em "Precisa de atenção" no Resumo: uma frase curta por problema, com a aba para onde leva. Puro. Usa os mesmos
 * limiares do {@link RegrasDeSaude} (os indicadores de cima e esta lista nunca discordam). Devolve TODOS os problemas
 * achados; quem mostra ({@code ListaDeAtencao}) ordena por gravidade e fica com os 5 primeiros.
 */
public final class ModeloDeAtencao {

    /** Um problema: gravidade (ATENCAO ou PERIGO), a frase e a aba que mostra o assunto. */
    public record Atencao(Gravidade gravidade, String texto, Aba destino) { }

    private ModeloDeAtencao() { }

    public static List<Atencao> de(InfoServidor info, DetalheServidor detalhe, ResumoDeSeguranca seguranca) {
        List<Atencao> lista = new ArrayList<>();
        acrescentarSeguranca(lista, seguranca);
        if (info != null) {
            for (InfoServidor.Disco d : info.discos()) {
                Double pct = RegrasDeSaude.pctUsado(d);
                NivelDeSaude n = RegrasDeSaude.disco(pct);
                if (n == NivelDeSaude.ATENCAO || n == NivelDeSaude.CRITICO) {
                    lista.add(new Atencao(gravidade(n), "Disco " + d.montagem() + ": " + Formato.fmtNum(pct, 0) + "% cheio", Aba.MAQUINA));
                }
            }
            Double disp = RegrasDeSaude.pctDisponivel(info.memoria());
            NivelDeSaude mem = RegrasDeSaude.memoria(disp);
            if (mem == NivelDeSaude.ATENCAO || mem == NivelDeSaude.CRITICO) {
                lista.add(new Atencao(gravidade(mem), "Memória: só " + Formato.fmtNum(disp, 0) + "% disponível", Aba.PROCESSOS));
            }
            Double temp = info.temperatura();
            NivelDeSaude t = RegrasDeSaude.temperatura(temp);
            if (t == NivelDeSaude.ATENCAO || t == NivelDeSaude.CRITICO) {
                lista.add(new Atencao(gravidade(t), "CPU quente: " + FormatosDoServidor.temperatura(temp), Aba.MAQUINA));
            }
        }
        if (detalhe != null) {
            if (detalhe.energia() != null && Boolean.FALSE.equals(detalhe.energia().tomada())) {
                lista.add(new Atencao(Gravidade.PERIGO, "Servidor na bateria: a tomada caiu ou foi desligada", Aba.MAQUINA));
            }
            for (DetalheServidor.GrupoDeConsumo g : detalhe.consumo()) {
                if (g.projeto() == null && !g.containers().isEmpty()) {
                    int n = g.containers().size();
                    lista.add(new Atencao(Gravidade.ATENCAO, n + (n == 1 ? " container sem dono" : " containers sem dono") + " rodando",
                        Aba.CONTAINERS));
                }
            }
        }
        return List.copyOf(lista);
    }

    private static void acrescentarSeguranca(List<Atencao> lista, ResumoDeSeguranca r) {
        if (r == null) {
            return;
        }
        if (r.ssh().lida() && Boolean.TRUE.equals(r.ssh().dados().senhaLigada())) {
            lista.add(new Atencao(Gravidade.PERIGO, "Segurança: o SSH aceita senha", Aba.SEGURANCA));
        }
        if (r.tentativas().lida()) {
            var t = r.tentativas().dados();
            if (t.foraDaRedeEm24h() > 0) {
                lista.add(new Atencao(Gravidade.PERIGO, "Segurança: login de fora da rede local (24 h)", Aba.SEGURANCA));
            }
            if (t.em24h() > 0) {
                lista.add(new Atencao(Gravidade.ATENCAO, "Segurança: " + Formato.fmtNum(t.em24h(), 0) + (t.parcial() ? "+" : "")
                    + (t.em24h() == 1 ? " tentativa" : " tentativas") + " de entrada em 24 h", Aba.SEGURANCA));
            }
        }
    }

    private static Gravidade gravidade(NivelDeSaude n) {
        return n == NivelDeSaude.CRITICO ? Gravidade.PERIGO : Gravidade.ATENCAO;
    }
}
