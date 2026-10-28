package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.EstadoProjeto;
import br.com.labdesktop.servidor.NivelDeSaude;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.telas.ModeloDeKpis.Kpi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** As regras puras da página do projeto (estado, somas, botões, indicadores), com o detalhe SINTÉTICO. */
class ModeloDoProjetoTest {

    private static ModeloDoProjeto padrao() {
        return ModeloDoProjeto.de(FixturesDeApi.detalheDoProjetoSintetico());
    }

    private static Kpi kpi(List<Kpi> l, String chave) {
        return l.stream().filter(k -> k.chave().equals(chave)).findFirst().orElseThrow();
    }

    @Test
    void oAgenteDiz_seOServidorTemClaude_eIssoManda_sobreAPresencaDeSessao() {
        // sem o que o agente diz (agente antigo): vale a presença de sessao no detalhe, como antes
        assertTrue(padrao().temClaude());
        assertTrue(padrao().indicadores().stream().anyMatch(k -> k.chave().equals("consumo")));
        // com recursos.claude = false, mesmo um detalhe que traz sessao não mostra nada do Claude
        ModeloDoProjeto semClaude = ModeloDoProjeto.de(FixturesDeApi.detalheDoProjetoSintetico(), false);
        assertFalse(semClaude.temClaude());
        assertFalse(semClaude.oferecerAbrirClaude());
        assertEquals(4, semClaude.indicadores().size(), "sem Claude não há indicador de consumo nem de limite");
        assertTrue(semClaude.indicadores().stream().noneMatch(k -> k.chave().equals("consumo")));
        // com recursos.claude = true, vale o que o agente diz
        assertTrue(ModeloDoProjeto.de(FixturesDeApi.detalheDoProjetoSintetico(), true).temClaude());
        assertEquals(5, ModeloDoProjeto.de(FixturesDeApi.detalheDoProjetoSintetico(), true).indicadores().size());
    }

    @Test
    void estadoSegueARegraDaLista() {
        assertEquals(EstadoProjeto.PARCIAL, padrao().estado(), "3 de 4 containers rodando");
        assertEquals(Gravidade.ATENCAO, padrao().gravidade());
        DetalheProjeto todos = TelaProjetoTest.detalheCom("\"estado\": \"exited\"", "\"estado\": \"running\"");
        assertEquals(EstadoProjeto.NO_AR, ModeloDoProjeto.de(todos).estado());
        assertEquals(Gravidade.OK, ModeloDoProjeto.de(todos).gravidade());
        DetalheProjeto nenhum = TelaProjetoTest.detalheCom("\"estado\": \"running\"", "\"estado\": \"exited\"");
        assertEquals(EstadoProjeto.PARADO, ModeloDoProjeto.de(nenhum).estado());
        assertEquals(Gravidade.NEUTRO, ModeloDoProjeto.de(nenhum).gravidade());
    }

    @Test
    void semComposeENuncaSubiu() {
        String json = TelaProjetoTest.detalheEmUmaLinha();
        DetalheProjeto semCompose = FixturesDeApi.de(json.replaceFirst("\"composes\": \\[.*?\\], \"containers\"", "\"composes\": [], \"containers\""),
            DetalheProjeto.class);
        assertEquals(EstadoProjeto.SEM_COMPOSE, ModeloDoProjeto.de(semCompose).estado());
        assertFalse(ModeloDoProjeto.de(semCompose).mostrarIniciar());
        assertFalse(ModeloDoProjeto.de(semCompose).mostrarParar());
        DetalheProjeto semContainers = FixturesDeApi.de(json.replaceFirst("\"containers\": \\[.*?\\], \"urls\"", "\"containers\": [], \"urls\""),
            DetalheProjeto.class);
        ModeloDoProjeto m = ModeloDoProjeto.de(semContainers);
        assertEquals(EstadoProjeto.NUNCA_SUBIU, m.estado());
        assertTrue(m.mostrarIniciar(), "tem compose e nada roda: Iniciar");
        assertFalse(m.temContainers());
    }

    @Test
    void somasDeCpuEMemoriaSoContamOsContainersQueRodam() {
        ModeloDoProjeto m = padrao();
        assertEquals(1.4 + 0.3 + 0.8, m.cpuSomada(), 1e-9);
        assertEquals(182452224L + 96468992L + 143654912L, m.memoriaSomada());
        DetalheProjeto parado = TelaProjetoTest.detalheCom("\"estado\": \"running\"", "\"estado\": \"exited\"");
        assertNull(ModeloDoProjeto.de(parado).cpuSomada(), "nenhum container rodando: sem leitura, não zero");
        assertNull(ModeloDoProjeto.de(parado).memoriaSomada());
    }

    @Test
    void botoesDeComposeSeguemOEstado() {
        assertTrue(padrao().mostrarParar(), "algum container roda: Parar");
        assertFalse(padrao().mostrarIniciar());
        DetalheProjeto parado = TelaProjetoTest.detalheCom("\"estado\": \"running\"", "\"estado\": \"exited\"");
        assertTrue(ModeloDoProjeto.de(parado).mostrarIniciar());
    }

    @Test
    void sessaoAbertaAtualizadaNaoOfereceAbrirNemRetomar() {
        ModeloDoProjeto m = padrao();
        assertTrue(m.sessaoAberta());
        assertFalse(m.sessaoDesatualizada());
        assertFalse(m.oferecerAbrirClaude());
        assertFalse(m.oferecerRetomar());
        assertEquals(Instant.parse("2026-10-24T17:10:00Z"), m.sessaoDesde());
    }

    @Test
    void sessaoDesatualizadaGuardaOMotivo() {
        DetalheProjeto d = TelaProjetoTest.detalheCom("\"atualizada\": true, \"faltando\": []",
            "\"atualizada\": false, \"faltando\": [\"imagem antiga (Claude desatualizado)\"]");
        ModeloDoProjeto m = ModeloDoProjeto.de(d);
        assertTrue(m.sessaoDesatualizada());
        assertEquals("imagem antiga (Claude desatualizado)", m.motivoDaSessaoDesatualizada());
    }

    @Test
    void semSessaoOferecerAbrirEPausadoOferecerRetomar() {
        DetalheProjeto fechada = TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false");
        assertTrue(ModeloDoProjeto.de(fechada).oferecerAbrirClaude());
        assertFalse(ModeloDoProjeto.de(fechada).oferecerRetomar());
        ModeloDoProjeto pausado = ModeloDoProjeto.de(TelaProjetoTest.detalhePausado());
        assertTrue(pausado.pausado());
        assertTrue(pausado.oferecerRetomar());
        assertFalse(pausado.oferecerAbrirClaude(), "pausada, o caminho é Retomar");
    }

    @Test
    void gitEMexido() {
        ModeloDoProjeto m = padrao();
        assertEquals("main@a1b2c3d", m.git());
        assertTrue(m.gitSujo());
        assertEquals(Instant.ofEpochMilli(1792605600000L), m.mexido());
    }

    @Test
    void oCodigoEmPalavrasSimplesNuncaComHashCru() {
        String sujo = padrao().textoDoCodigo();
        assertEquals("Branch main, com mudanças que ainda não foram salvas no git", sujo);
        assertFalse(sujo.contains("a1b2c3d"), "o hash fica nos detalhes técnicos, não na frase");
        String limpo = ModeloDoProjeto.de(FixturesDeApi.de(TelaProjetoTest.detalheEmUmaLinha().replaceFirst("\"sujo\": true", "\"sujo\": false"),
            DetalheProjeto.class)).textoDoCodigo();
        assertEquals("Branch main, tudo salvo", limpo);
        String semGit = ModeloDoProjeto.de(FixturesDeApi.de(TelaProjetoTest.detalheEmUmaLinha()
            .replaceFirst("\"git\": \\{.*?\\}, \"backups\"", "\"git\": null, \"backups\""), DetalheProjeto.class)).textoDoCodigo();
        assertEquals("Sem git", semGit);
    }

    @Test
    void semGitEmexidoAusenteNaoQuebram() {
        String json = TelaProjetoTest.detalheEmUmaLinha()
            .replaceFirst("\"git\": \\{.*?\\}, \"backups\"", "\"git\": null, \"backups\"").replaceFirst("\"mexido\": [0-9.]+", "\"mexido\": null");
        ModeloDoProjeto m = ModeloDoProjeto.de(FixturesDeApi.de(json, DetalheProjeto.class));
        assertNull(m.git());
        assertFalse(m.gitSujo());
        assertNull(m.mexido());
    }

    // ---------------------------------------------------------------------------------------------
    // Indicadores
    // ---------------------------------------------------------------------------------------------

    @Test
    void sao5IndicadoresNaOrdemCerta() {
        List<Kpi> k = padrao().indicadores();
        assertEquals(List.of("estado", "containers", "cpu", "memoria", "consumo"), k.stream().map(Kpi::chave).toList());
        assertTrue(k.size() <= ModeloDoProjeto.MAXIMO_DE_INDICADORES);
    }

    @Test
    void indicadorDoEstadoEDeContainers() {
        List<Kpi> k = padrao().indicadores();
        assertEquals("Parcial", kpi(k, "estado").valor());
        assertEquals(NivelDeSaude.ATENCAO, kpi(k, "estado").nivel());
        assertEquals("3 de 4", kpi(k, "containers").valor());
        assertEquals(NivelDeSaude.ATENCAO, kpi(k, "containers").nivel());
        DetalheProjeto todos = TelaProjetoTest.detalheCom("\"estado\": \"exited\"", "\"estado\": \"running\"");
        List<Kpi> k2 = ModeloDoProjeto.de(todos).indicadores();
        assertEquals("No ar", kpi(k2, "estado").valor());
        assertEquals(NivelDeSaude.OK, kpi(k2, "estado").nivel());
        assertEquals("4 de 4", kpi(k2, "containers").valor());
    }

    @Test
    void projetoParadoNaoTemSeloDeProblema() {
        DetalheProjeto parado = TelaProjetoTest.detalheCom("\"estado\": \"running\"", "\"estado\": \"exited\"");
        List<Kpi> k = ModeloDoProjeto.de(parado).indicadores();
        assertEquals("Parado", kpi(k, "estado").valor());
        assertTrue(kpi(k, "estado").semSelo(), "parado é normal, não é problema nem dado que falta");
        assertTrue(kpi(k, "containers").semSelo());
        assertEquals("—", kpi(k, "cpu").valor());
        assertEquals("—", kpi(k, "memoria").valor());
    }

    @Test
    void cpuEMemoriaSaoSoNumeroSemSelo() {
        List<Kpi> k = padrao().indicadores();
        assertEquals("2,5%", kpi(k, "cpu").valor());
        assertEquals("403 MB", kpi(k, "memoria").valor());
        assertTrue(kpi(k, "cpu").semSelo());
        assertTrue(kpi(k, "memoria").semSelo());
    }

    @Test
    void consumoDentroDoLimite() {
        Kpi c = kpi(padrao().indicadores(), "consumo");
        assertEquals("2,0%", c.valor(), "o valor grande é só o número; o teto vem na legenda (cabe nos cartões em 1100)");
        assertEquals("de 20% da semana", c.legenda());
        assertEquals("CONSUMO", c.rotulo());
        assertEquals(NivelDeSaude.OK, c.nivel());
        assertNull(c.seloTexto(), "o selo diz a palavra do nível: OK");
    }

    @Test
    void consumoPausadoViraCriticoComAPalavraPausado() {
        Kpi c = kpi(ModeloDoProjeto.de(TelaProjetoTest.detalhePausado()).indicadores(), "consumo");
        assertEquals("22%", c.valor());
        assertEquals("de 20% da semana", c.legenda());
        assertEquals(NivelDeSaude.CRITICO, c.nivel());
        assertEquals("Pausado", c.seloTexto());
    }

    @Test
    void consumoPertoDoLimiteViraAtencao() {
        DetalheProjeto d = TelaProjetoTest.detalheCom("\"usado_pp\": 1.96, \"estado\": \"ok\"", "\"usado_pp\": 16.5, \"estado\": \"atencao\"");
        Kpi c = kpi(ModeloDoProjeto.de(d).indicadores(), "consumo");
        assertEquals(NivelDeSaude.ATENCAO, c.nivel());
        assertEquals("17%", c.valor(), "a regra do app: uma casa só abaixo de 10");
    }

    @Test
    void consumoLiberadoEscreveLiberado() {
        DetalheProjeto d = TelaProjetoTest.detalheCom("\"liberado\": false", "\"liberado\": true");
        Kpi c = kpi(ModeloDoProjeto.de(d).indicadores(), "consumo");
        assertEquals("Liberado", c.seloTexto());
        assertEquals("liberado na semana", c.legenda());
    }

    @Test
    void consumoSemTetoNaoTemSeloEDiz() {
        Kpi c = kpi(ModeloDoProjeto.de(TelaProjetoTest.detalheSemTeto()).indicadores(), "consumo");
        assertTrue(c.semSelo());
        assertTrue(c.valor().endsWith("%"), c.valor());
        assertEquals("da semana, sem limite", c.legenda());
    }

    @Test
    void consumoSemLimiteNoDetalheMostraTraco() {
        String json = TelaProjetoTest.detalheEmUmaLinha();
        DetalheProjeto d = FixturesDeApi.de(json.substring(0, json.indexOf("\"limite\"")) + "\"limite\": null }", DetalheProjeto.class);
        Kpi c = kpi(ModeloDoProjeto.de(d).indicadores(), "consumo");
        assertEquals("—", c.valor());
        assertTrue(c.semSelo());
    }
}
