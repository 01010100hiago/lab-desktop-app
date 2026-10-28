package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A ordem das abas, com valores conhecidos: o padrão, mover, esconder, e a regra de ouro de NUNCA perder uma aba por causa da preferência (ids novos vão para o
 * fim, ids que não existem mais são ignorados, repetidos valem uma vez, tudo escondido mostra tudo).
 */
class OrdemDeAbasTest {

    private static final List<String> CONHECIDAS = List.of("resumo", "seguranca", "containers", "projetos", "arquivos", "terminal");

    @Test
    void semNadaGuardadoValeAOrdemPadraoESemEscondidas() {
        assertTrue(OrdemDeAbas.PADRAO.ehPadrao());
        assertEquals(CONHECIDAS, OrdemDeAbas.PADRAO.ordenar(CONHECIDAS));
        assertEquals(CONHECIDAS, OrdemDeAbas.PADRAO.visiveis(CONHECIDAS));
    }

    @Test
    void aOrdemDoDonoValeEOQueFaltaVaiParaOFimNaOrdemPadrao() {
        OrdemDeAbas o = new OrdemDeAbas(List.of("terminal", "projetos"), Set.of());
        assertEquals(List.of("terminal", "projetos", "resumo", "seguranca", "containers", "arquivos"), o.ordenar(CONHECIDAS));
    }

    @Test
    void idQueEstaVersaoNaoConheceEIgnoradoENuncaPerdeAbaConhecida() {
        OrdemDeAbas o = new OrdemDeAbas(List.of("aba-de-versao-futura", "arquivos", "outra-que-sumiu", "resumo"), Set.of("aba-de-versao-futura"));
        assertEquals(List.of("arquivos", "resumo", "seguranca", "containers", "projetos", "terminal"), o.ordenar(CONHECIDAS));
        assertEquals(6, o.ordenar(CONHECIDAS).size(), "nenhuma aba conhecida se perde");
    }

    @Test
    void idRepetidoValeNaPrimeiraVez() {
        OrdemDeAbas o = new OrdemDeAbas(List.of("arquivos", "resumo", "arquivos", "resumo"), Set.of());
        assertEquals(List.of("arquivos", "resumo"), o.ordem());
        assertEquals(List.of("arquivos", "resumo", "seguranca", "containers", "projetos", "terminal"), o.ordenar(CONHECIDAS));
    }

    @Test
    void abaNovaDeUmaVersaoFuturaAparecePeloFim() {
        OrdemDeAbas guardada = new OrdemDeAbas(List.of("resumo", "seguranca", "containers", "projetos", "arquivos", "terminal"), Set.of());
        List<String> comUmaNova = new ArrayList<>(CONHECIDAS);
        comUmaNova.add(2, "metricas");
        assertEquals(List.of("resumo", "seguranca", "containers", "projetos", "arquivos", "terminal", "metricas"), guardada.ordenar(comUmaNova));
    }

    @Test
    void moverSobeEDesceUmPassoENoLimiteNaoFazNada() {
        OrdemDeAbas o = OrdemDeAbas.PADRAO.mover(CONHECIDAS, "terminal", -1);
        assertEquals(List.of("resumo", "seguranca", "containers", "projetos", "terminal", "arquivos"), o.ordenar(CONHECIDAS));
        o = o.mover(CONHECIDAS, "resumo", 1);
        assertEquals(List.of("seguranca", "resumo", "containers", "projetos", "terminal", "arquivos"), o.ordenar(CONHECIDAS));
        assertSame(o, o.mover(CONHECIDAS, "seguranca", -1), "a primeira não sobe");
        assertSame(o, o.mover(CONHECIDAS, "arquivos", 1), "a última não desce");
        assertSame(o, o.mover(CONHECIDAS, "nao-existe", 1));
    }

    @Test
    void levarParaPoeNaPosicaoPedidaEUsaOsLimites() {
        assertEquals(List.of("terminal", "resumo", "seguranca", "containers", "projetos", "arquivos"),
            OrdemDeAbas.PADRAO.levarPara(CONHECIDAS, "terminal", 0).ordenar(CONHECIDAS));
        assertEquals(List.of("seguranca", "containers", "projetos", "arquivos", "terminal", "resumo"),
            OrdemDeAbas.PADRAO.levarPara(CONHECIDAS, "resumo", 99).ordenar(CONHECIDAS));
        assertEquals(List.of("seguranca", "resumo", "containers", "projetos", "arquivos", "terminal"),
            OrdemDeAbas.PADRAO.levarPara(CONHECIDAS, "resumo", 1).ordenar(CONHECIDAS));
    }

    @Test
    void escondidasSaemDaBarraMasFicamNaLista() {
        OrdemDeAbas o = OrdemDeAbas.PADRAO.escondendo("seguranca").escondendo("containers");
        assertEquals(List.of("resumo", "projetos", "arquivos", "terminal"), o.visiveis(CONHECIDAS));
        assertEquals(CONHECIDAS, o.ordenar(CONHECIDAS), "na tela de Ajustes a aba escondida continua na lista (para voltar)");
        assertTrue(o.oculta("seguranca"));
        assertFalse(o.mostrando("seguranca").oculta("seguranca"));
    }

    @Test
    void tudoEscondidoPorArquivoCorrompidoMostraTudo() {
        OrdemDeAbas o = new OrdemDeAbas(List.of(), new LinkedHashSet<>(CONHECIDAS));
        assertEquals(CONHECIDAS, o.visiveis(CONHECIDAS));
    }

    @Test
    void naoPodeEsconderAAbaDaTelaNemAUltimaQueRestaNemUmaQueNaoExiste() {
        List<String> disponiveis = List.of("resumo", "projetos", "arquivos"); // o servidor não tem Segurança, Containers nem Terminal
        OrdemDeAbas o = OrdemDeAbas.PADRAO;
        assertFalse(o.podeEsconder(CONHECIDAS, disponiveis, "resumo", "resumo"), "a aba que está na tela fica");
        assertFalse(o.podeEsconder(CONHECIDAS, disponiveis, "nao-existe", null));
        assertTrue(o.podeEsconder(CONHECIDAS, disponiveis, "arquivos", "resumo"));
        o = o.escondendo("arquivos");
        assertTrue(o.podeEsconder(CONHECIDAS, disponiveis, "projetos", "resumo"));
        o = o.escondendo("projetos");
        assertFalse(o.podeEsconder(CONHECIDAS, disponiveis, "resumo", null), "a última que aparece não se esconde");
        assertTrue(o.podeEsconder(CONHECIDAS, disponiveis, "terminal", "resumo"), "uma que o servidor nem tem pode ser marcada (não muda o que aparece)");
        assertFalse(o.podeEsconder(CONHECIDAS, disponiveis, "projetos", "resumo"), "já escondida");
    }

    @Test
    void idsForaDoFormatoSaoDescartadosNaCriacao() {
        OrdemDeAbas o = new OrdemDeAbas(Arrays.asList("resumo", null, "", "Com Espaco", "../../etc", "a".repeat(41), "terminal"),
            new LinkedHashSet<>(Arrays.asList("ok-1", null, "X")));
        assertEquals(List.of("resumo", "terminal"), o.ordem());
        assertEquals(Set.of("ok-1"), o.ocultas());
    }

    @Test
    void listaGigantePorArquivoHostilFicaLimitada() {
        List<String> muitos = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            muitos.add("aba-" + i);
        }
        assertEquals(64, new OrdemDeAbas(muitos, Set.of()).ordem().size());
    }

    @Test
    void nulosViramPadrao() {
        OrdemDeAbas o = new OrdemDeAbas(null, null);
        assertTrue(o.ehPadrao());
    }

    @Test
    void asPreferenciasGuardamELeemAsDuasBarras(@TempDir Path tmp) throws Exception {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        Preferencias p = Preferencias.padrao()
            .comAbasDoServidor(new OrdemDeAbas(List.of("terminal", "resumo"), Set.of("seguranca")))
            .comAbasDoProjeto(new OrdemDeAbas(List.of("logs"), Set.of("backups", "consumo")));
        p.salvar(c);
        Preferencias lida = Preferencias.carregar(c);
        assertEquals(p, lida);
        assertEquals(List.of("terminal", "resumo"), lida.abasDoServidor().ordem());
        assertEquals(Set.of("backups", "consumo"), lida.abasDoProjeto().ocultas());
        // o padrão não grava nada das abas: o arquivo fica igual ao de antes deste recurso
        Preferencias.padrao().salvar(c);
        String json = Files.readString(c.arquivoPreferencias());
        assertFalse(json.contains("\"ordem\"") || json.contains("\"ocultas\""), json);
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c));
    }

    @Test
    void arquivoComAbasCorrompidasNaoDerrubaALeituraENuncaPerdeAba(@TempDir Path tmp) throws Exception {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        c.prepararPastas();
        Files.writeString(c.arquivoPreferencias(), "{\"tema\":\"escuro\",\"abasServidor\":{\"ordem\":[\"resumo\",42,null,\"../x\"],\"ocultas\":\"nao-e-lista\"},"
            + "\"abasProjeto\":{\"ordem\":[\"logs\",\"logs\"],\"ocultas\":[\"visao-geral\",\"logs\",\"terminal\",\"arquivos\",\"backups\",\"consumo\",\"internet\"]}}",
            StandardCharsets.UTF_8);
        Preferencias lida = Preferencias.carregar(c);
        // "ocultas" que não é lista: a leitura inteira cai nos padrões (nunca lança); mas nunca some uma aba
        List<String> projeto = List.of("visao-geral", "logs", "terminal", "arquivos", "backups", "consumo", "internet");
        assertEquals(projeto.size(), lida.abasDoProjeto().visiveis(projeto).size(), "tudo escondido no arquivo mostra tudo");
        assertEquals(List.of("a", "b"), lida.abasDoServidor().ordenar(List.of("a", "b")));
    }
}
