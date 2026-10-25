package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.NivelDeSaude;
import br.com.labdesktop.servidor.ParsersDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.teste.SaidasDeSeguranca;
import br.com.labdesktop.ui.telas.ModeloDeKpis.Kpi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A primeira linha: no máximo seis, valores e níveis por indicador, e a Saúde como o pior deles. */
class ModeloDeKpisTest {

    static final Instant AGORA = Instant.parse("2026-10-24T21:00:00Z");

    static ResumoDeSeguranca resumo(String registro, String sshd, boolean fail2banAtivo) {
        return new ResumoDeSeguranca(AGORA,
            Leitura.lida(ParsersDeSeguranca.lerRegistroDoSsh(registro, AGORA, false)),
            Leitura.lida(ParsersDeSeguranca.lerPortas(SaidasDeSeguranca.SS)),
            Leitura.lida(ParsersDeSeguranca.lerNft(SaidasDeSeguranca.NFT, 2222)),
            Leitura.lida(ParsersDeSeguranca.lerSshd(sshd)),
            Leitura.lida(ParsersDeSeguranca.lerFail2ban(fail2banAtivo, SaidasDeSeguranca.FAIL2BAN)));
    }

    static ResumoDeSeguranca comAtaque() {
        return resumo(SaidasDeSeguranca.registroComAtaque(AGORA), SaidasDeSeguranca.SSHD_SEGURO, true);
    }

    static ResumoDeSeguranca calmo() {
        return resumo(SaidasDeSeguranca.registroCalmo(AGORA), SaidasDeSeguranca.SSHD_SEGURO, true);
    }

    private static Kpi kpi(List<Kpi> l, String chave) {
        return l.stream().filter(k -> k.chave().equals(chave)).findFirst().orElseThrow();
    }

    @Test
    void sempreExatamenteSeisIndicadoresNaOrdemCombinadaEOMaximoEOTeto() {
        List<Kpi> k = ModeloDeKpis.de(FixturesDeApi.servidorSintetico(), calmo());
        assertEquals(List.of("saude", "cpu", "memoria", "disco", "containers", "seguranca"), k.stream().map(Kpi::chave).toList());
        assertTrue(k.size() <= ModeloDeKpis.MAXIMO);
        assertEquals(6, ModeloDeKpis.MAXIMO);
        assertEquals(6, ModeloDeKpis.de(null, null).size(), "sem dado nenhum a linha continua com os seis, em traço");
    }

    @Test
    void valoresELegendasComOsDadosSinteticos() {
        List<Kpi> k = ModeloDeKpis.de(FixturesDeApi.servidorSintetico(), calmo());
        assertEquals("8,3%", kpi(k, "cpu").valor());
        assertEquals("62 °C, 4 núcleos", kpi(k, "cpu").legenda(), "a temperatura vem junto, na legenda");
        assertEquals(NivelDeSaude.OK, kpi(k, "cpu").nivel());
        assertEquals("34%", kpi(k, "memoria").valor(), "em uso real: total menos disponível");
        assertEquals("swap 36%", kpi(k, "memoria").legenda());
        assertEquals("60%", kpi(k, "disco").valor());
        assertEquals("35,0 GB livres em /", kpi(k, "disco").legenda());
        assertEquals("8/10", kpi(k, "containers").valor());
        assertEquals("2 parados", kpi(k, "containers").legenda());
        assertEquals(NivelDeSaude.OK, kpi(k, "containers").nivel(), "parados são normais");
        assertEquals("0", kpi(k, "seguranca").valor());
        assertEquals("2 IPs bloqueados", kpi(k, "seguranca").legenda());
        assertEquals(NivelDeSaude.OK, kpi(k, "seguranca").nivel());
        assertEquals("Tudo OK", kpi(k, "saude").valor());
        assertEquals(NivelDeSaude.OK, kpi(k, "saude").nivel());
    }

    @Test
    void comAtaqueASegurancaViraAtencaoEASaudeCarregaACausa() {
        List<Kpi> k = ModeloDeKpis.de(FixturesDeApi.servidorSintetico(), comAtaque());
        assertEquals("7", kpi(k, "seguranca").valor());
        assertEquals(NivelDeSaude.CRITICO, kpi(k, "seguranca").nivel(), "houve login aceito de fora da rede local nas 24 h");
        assertEquals(NivelDeSaude.CRITICO, kpi(k, "saude").nivel());
        assertEquals("Login aceito de fora da rede local nas últimas 24 h", kpi(k, "saude").legenda());
        assertEquals("Crítico", kpi(k, "saude").valor());
    }

    @Test
    void soTentativasSemLoginDeForaDaAtencaoComAContagemNaCausa() {
        String registro = SaidasDeSeguranca.registroComAtaque(AGORA).lines().filter(l -> !l.contains("Accepted password")).reduce("", (a, b) -> a + b + "\n");
        List<Kpi> k = ModeloDeKpis.de(FixturesDeApi.servidorSintetico(), resumo(registro, SaidasDeSeguranca.SSHD_SEGURO, false));
        assertEquals(NivelDeSaude.ATENCAO, kpi(k, "seguranca").nivel());
        assertEquals("7 tentativas de entrada em 24 h", kpi(k, "saude").legenda());
        assertEquals("sem bloqueio automático", kpi(k, "seguranca").legenda());
    }

    @Test
    void sshComSenhaECriticoMesmoSemTentativas() {
        List<Kpi> k = ModeloDeKpis.de(FixturesDeApi.servidorSintetico(),
            resumo(SaidasDeSeguranca.registroCalmo(AGORA), SaidasDeSeguranca.SSHD_COM_SENHA, true));
        assertEquals(NivelDeSaude.CRITICO, kpi(k, "seguranca").nivel());
        assertEquals("O SSH aceita senha", kpi(k, "saude").legenda());
    }

    @Test
    void discoCriticoEMemoriaBaixaGovernamASaude() {
        InfoServidor base = FixturesDeApi.servidorSintetico();
        String json = FixturesDeApi.lerRecurso("servidor-sintetico.json")
            .replace("\"usado\": 64424509440", "\"usado\": 92341796864")   // 86% de 100 GB
            .replace("\"livre\": 37580963840", "\"livre\": 8000000000");
        InfoServidor cheio = FixturesDeApi.de(json, InfoServidor.class);
        List<Kpi> k = ModeloDeKpis.de(cheio, calmo());
        assertEquals(NivelDeSaude.ATENCAO, kpi(k, "disco").nivel());
        assertEquals("Disco / a 86%", kpi(k, "saude").legenda());
        assertEquals(base.discos().size(), cheio.discos().size());
    }

    @Test
    void cpuAtencaoEm85CriticoEm90() {
        String quente = FixturesDeApi.lerRecurso("servidor-sintetico.json").replace("\"temperatura\": 62", "\"temperatura\": 87");
        List<Kpi> k = ModeloDeKpis.de(FixturesDeApi.de(quente, InfoServidor.class), calmo());
        assertEquals(NivelDeSaude.ATENCAO, kpi(k, "cpu").nivel());
        assertEquals("CPU a 87 °C", kpi(k, "saude").legenda());
        String critico = quente.replace("\"temperatura\": 87", "\"temperatura\": 90");
        assertEquals(NivelDeSaude.CRITICO, kpi(ModeloDeKpis.de(FixturesDeApi.de(critico, InfoServidor.class), calmo()), "cpu").nivel());
    }

    @Test
    void semDadoCadaIndicadorMostraTracoESaudeFicaSemDado() {
        List<Kpi> k = ModeloDeKpis.de(null, null);
        for (Kpi x : k) {
            assertEquals(NivelDeSaude.SEM_DADO, x.nivel(), x.chave());
        }
        assertEquals("—", kpi(k, "cpu").valor());
        assertEquals("—", kpi(k, "saude").valor());
        assertEquals("lendo…", kpi(k, "seguranca").legenda());
    }

    @Test
    void campoAusenteViraTracoSemExcecao() {
        InfoServidor vazio = FixturesDeApi.de("{\"cpu\":{},\"memoria\":{},\"docker\":{},\"discos\":[{}]}", InfoServidor.class);
        List<Kpi> k = ModeloDeKpis.de(vazio, null);
        assertEquals("—", kpi(k, "cpu").valor());
        assertEquals("—", kpi(k, "memoria").valor());
        assertEquals("—", kpi(k, "disco").valor());
        assertEquals("—", kpi(k, "containers").valor());
    }
}
