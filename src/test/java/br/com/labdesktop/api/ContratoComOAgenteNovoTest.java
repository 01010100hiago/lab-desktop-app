package br.com.labdesktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.HistoricoDoAgente;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.tendencias.DadosDeTendencia;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.tendencias.Metrica;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * O contrato com o agente de monitoramento PÚBLICO, a partir de respostas REAIS dele ({@link FixturesDeApi#lerRespostaDoAgenteNovo}, capturadas de um container do
 * Docker de verdade): cada DTO do app lê a resposta sem perder o que o Resumo e os gráficos usam, e o app entende o que o agente diz de si ({@code recursos}). Ao contrário do
 * {@code ContratoComFixturesReaisTest} (do agente do lab, ainda pendente de captura), este roda sempre.
 */
class ContratoComOAgenteNovoTest {

    @Test
    void aVersaoDiz_monitorSemClaudeSemProjetosComHistoricoETokenSempreExigido() {
        VersaoAgente v = FixturesDeApi.de(FixturesDeApi.lerRespostaDoAgenteNovo("versao"), VersaoAgente.class);
        assertTrue(v.compativel(), "api 1");
        assertTrue(v.perfilMonitor());
        assertFalse(v.ehAgenteDeFora(), "é o agente que o app instala");
        assertEquals(Boolean.TRUE, v.exigeToken());
        assertEquals(Boolean.FALSE, v.temClaude());
        assertFalse(v.temProjetos(), "o app esconde a aba Projetos");
        assertFalse(v.entendeGuiaIa(), "e a caixa do guia para o Claude");
        assertTrue(v.entendeHistorico(), "e chama /api/servidor/historico");
        assertTrue(v.agente().matches("^[0-9]+\\.[0-9]+\\.[0-9]+-[0-9a-f]{7}$"), v.agente());
        assertEquals("desligado", v.totp());
    }

    @Test
    void oServidorTemOQueOResumoMostra() {
        InfoServidor s = FixturesDeApi.de(FixturesDeApi.lerRespostaDoAgenteNovo("servidor"), InfoServidor.class);
        assertNotNull(s.quando());
        assertFalse(s.hostname().isBlank());
        assertFalse(s.sistema().isBlank());
        assertTrue(s.cpu().nucleos() > 0 && s.cpu().uso() != null && s.cpu().porNucleo().size() == s.cpu().nucleos());
        assertTrue(s.memoria().total() > 0 && s.memoria().usada() > 0 && s.memoria().swap() != null);
        assertNotNull(s.rede().interfaceDeRede());
        assertTrue(s.docker().containers() >= s.docker().rodando() && s.docker().versao() != null);
        assertFalse(s.docker().tamanhos().isEmpty(), "o espaço do Docker (imagens, containers, volumes, cache)");
        assertTrue(s.sessoes().isEmpty(), "sem sessões do Claude");
        assertFalse(s.processos().isEmpty());
        assertTrue(s.historico() != null && !s.historico().cpu().isEmpty(), "os 10 minutos de /api/servidor");
    }

    @Test
    void oDetalheTemAMaquinaSemConsumoPorProjeto() {
        DetalheServidor d = FixturesDeApi.de(FixturesDeApi.lerRespostaDoAgenteNovo("detalhe"), DetalheServidor.class);
        assertNotNull(d.maquina());
        assertTrue(d.maquina().nucleos() > 0 && d.maquina().memoriaTotal() > 0);
        assertTrue(d.consumo().isEmpty(), "o agente de leitura não conhece projetos");
        assertFalse(d.processos().isEmpty());
        assertNotNull(d.energia());
    }

    @Test
    void oHistoricoEntraNosGraficosDeTodasAsJanelas() {
        for (String arquivo : new String[] {"historico-10m", "historico-1h"}) {
            HistoricoDoAgente h = FixturesDeApi.de(FixturesDeApi.lerRespostaDoAgenteNovo(arquivo), HistoricoDoAgente.class);
            JanelaDeTempo janela = arquivo.endsWith("10m") ? JanelaDeTempo.DEZ_MINUTOS : JanelaDeTempo.UMA_HORA;
            assertEquals(janela.codigo(), h.janela());
            assertEquals(janela.pontos(), h.cpu().size(), "a grade inteira, com null onde não há dado");
            assertTrue(h.cpu().stream().anyMatch(java.util.Objects::nonNull), "tem ponto de verdade");
            assertNotNull(h.agora().cpu(), "a última leitura real");
            DadosDeTendencia dados = DadosDeTendencia.deHistorico(janela, h, Instant.now());
            assertNotNull(dados);
            assertTrue(dados.temAlgumDado());
            assertTrue(dados.temDado(Metrica.CPU) && dados.temDado(Metrica.MEMORIA) && dados.temDado(Metrica.DISCO), "os três que o agente sempre mede");
        }
    }
}
