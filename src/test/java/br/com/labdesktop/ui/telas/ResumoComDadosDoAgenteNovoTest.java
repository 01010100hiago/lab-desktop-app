package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.HistoricoDoAgente;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.tendencias.DadosDeTendencia;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O Resumo e os gráficos desenhados com respostas REAIS do agente de monitoramento público (capturadas de um container de verdade: {@code src/test/resources/agente-novo},
 * ver {@link FixturesDeApi#lerRespostaDoAgenteNovo}): sem projetos, sem Claude, com os seis gráficos de 10 minutos e de 1 hora. Os PNGs dos dois temas saem em
 * {@code build/telas/} ({@code servidor-agente-novo-*}).
 */
@ExtendWith(VerificaEdt.class)
class ResumoComDadosDoAgenteNovoTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private static HistoricoDoAgente historico(String arquivo) {
        return FixturesDeApi.de(FixturesDeApi.lerRespostaDoAgenteNovo(arquivo), HistoricoDoAgente.class);
    }

    private static TelaServidor tela(Instant agora) {
        AvisosGravados avisos = new AvisosGravados();
        DialogosGravados dialogos = new DialogosGravados();
        return Visual.naEdt(() -> {
            TelaServidor t = new TelaServidor(null, TelaProjetosTest.nova(avisos, dialogos), () -> { });
            for (Aba a : Aba.values()) {
                if (t.pagina(a) != null) {
                    t.pagina(a).relogio(() -> agora, SP);
                }
            }
            return t;
        });
    }

    /** Alimenta a tela como o Sondador faria com um agente monitor de verdade: versão, servidor, detalhe e o histórico da janela. */
    private static void alimentar(TelaServidor t, JanelaDeTempo janela, String arquivoDoHistorico) {
        VersaoAgente v = FixturesDeApi.de(FixturesDeApi.lerRespostaDoAgenteNovo("versao"), VersaoAgente.class);
        EstadoServidor e = new EstadoServidor("s1", "vps-1", EstadoConexao.CONECTADO, null, 0, null, null, 0, Estado2fa.DESLIGADO, "mac", v, null);
        InfoServidor info = FixturesDeApi.de(FixturesDeApi.lerRespostaDoAgenteNovo("servidor"), InfoServidor.class);
        DetalheServidor detalhe = FixturesDeApi.de(FixturesDeApi.lerRespostaDoAgenteNovo("detalhe"), DetalheServidor.class);
        HistoricoDoAgente h = historico(arquivoDoHistorico);
        Visual.naEdt(() -> {
            t.aplicarRecursos(e.temProjetos());
            for (Aba a : Aba.values()) {
                PaginaDoServidor p = t.pagina(a);
                if (p == null) {
                    continue;
                }
                p.aoMudarEstado(e);
                p.aoReceberServidor(info);
                p.aoReceberDetalhe(detalhe);
            }
            PaginaResumo r = t.resumo();
            r.definirHistoricoDeLongoPrazoParaTeste(true);
            r.lembrarJanelaParaTeste(janela);
            r.aoReceberHistorico(DadosDeTendencia.deHistorico(janela, h, h.quando()));
        });
    }

    @Test
    void oResumoMostraOsDadosDoAgenteNovoSemClaudeESemProjetosNosDoisTemas() {
        Instant agora = historico("historico-10m").quando().plusSeconds(2);
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            TelaServidor t = tela(agora);
            alimentar(t, JanelaDeTempo.DEZ_MINUTOS, "historico-10m");
            Visual.naEdt(() -> t.mostrarAba(Aba.RESUMO));
            String texto = TelaServidorTest.texto(t.resumo());
            assertFalse(texto.toLowerCase().contains("claude"), texto);
            assertFalse(Visual.naEdt(() -> t.abas().visivel(Aba.PROJETOS.id())), "o agente diz projetos: false");
            assertTrue(texto.contains("CPU") && texto.contains("Memória"), "os indicadores e os gráficos: " + texto);
            String sufixo = tema == Tema.CLARO ? "claro" : "escuro";
            Visual.salvar(Visual.renderizar(t, 1100, 760), "servidor-agente-novo-resumo-10min-" + sufixo);
            if (tema == Tema.CLARO) {
                alimentar(t, JanelaDeTempo.UMA_HORA, "historico-1h");
                Visual.salvar(Visual.renderizar(t, 1100, 760), "servidor-agente-novo-resumo-1h-claro");
                Visual.naEdt(() -> t.mostrarAba(Aba.MAQUINA));
                Visual.salvar(Visual.renderizar(t, 1100, 760), "servidor-agente-novo-maquina-claro");
                Visual.naEdt(() -> t.mostrarAba(Aba.CONTAINERS));
                Visual.salvar(Visual.renderizar(t, 1100, 760), "servidor-agente-novo-containers-claro");
            }
        }
        Visual.tema(Tema.CLARO);
    }
}
