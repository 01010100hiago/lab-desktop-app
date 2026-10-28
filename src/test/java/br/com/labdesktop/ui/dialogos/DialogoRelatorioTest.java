package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.HistoricoSintetico;
import br.com.labdesktop.tendencias.DadosDeTendencia;
import br.com.labdesktop.tendencias.ExportadorDeRelatorio;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** O relatório de tendências: tabela (mín, média, máx, P95, hora do pico), resumo, troca de período, estimativa do disco, copiar e salvar (CSV e texto). PNGs claro e escuro. */
@ExtendWith(VerificaEdt.class)
class DialogoRelatorioTest {

    static final Instant AGORA = Instant.parse("2026-10-28T15:00:00Z");
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Duration PRAZO = Duration.ofSeconds(15);

    final AvisosGravados avisos = new AvisosGravados();
    final Clipboard area = new Clipboard("teste");
    final List<JanelaDeTempo> pedidas = new CopyOnWriteArrayList<>();
    final List<String> gravados = new CopyOnWriteArrayList<>();
    volatile Optional<Path> escolha = Optional.of(Path.of("/pasta/qualquer"));
    final List<String> nomesSugeridos = new ArrayList<>();
    volatile CountDownLatch segurar;
    volatile boolean falhar;

    EscolhedorLocal escolhedor() {
        return new EscolhedorLocal() {
            @Override
            public List<Path> escolherParaEnviar() {
                return List.of();
            }

            @Override
            public Optional<Path> escolherPastaParaBaixar(Path padrao) {
                return Optional.empty();
            }

            @Override
            public Optional<Path> escolherArquivoParaSalvar(String nomeSugerido, String extensao, String descricao) {
                nomesSugeridos.add(nomeSugerido);
                return escolha.map(p -> p.resolve(nomeSugerido));
            }
        };
    }

    DialogoRelatorio abrir(JanelaDeTempo inicial, boolean podeLongo, DadosDeTendencia jaTenho) {
        Visual.tema(Tema.CLARO);
        DialogoRelatorio.Fonte fonte = j -> {
            pedidas.add(j);
            CountDownLatch l = segurar;
            if (l != null) {
                l.await();
            }
            if (falhar) {
                throw new IOException("o agente não respondeu");
            }
            return DadosDeTendencia.deHistorico(j, HistoricoSintetico.dados(j, AGORA, 0), AGORA);
        };
        return Visual.naEdt(() -> new DialogoRelatorio(fonte, inicial, jaTenho, podeLongo, "lab", SP, () -> AGORA, escolhedor(),
            (destino, bytes) -> gravados.add(destino + "|" + new String(bytes, StandardCharsets.UTF_8)), new CopiadorDeTexto(() -> area), avisos));
    }

    static DadosDeTendencia dados(JanelaDeTempo j) {
        return DadosDeTendencia.deHistorico(j, HistoricoSintetico.dados(j, AGORA, 0), AGORA);
    }

    @Test
    void mostraATabelaComOsCincoNumerosEAHoraDoPicoDeCadaMetricaEAsFrasesDeResumo() {
        DialogoRelatorio d = abrir(JanelaDeTempo.UMA_SEMANA, true, dados(JanelaDeTempo.UMA_SEMANA));
        assertEquals(JanelaDeTempo.UMA_SEMANA, d.periodo());
        String t = TextosDeTela.de(d.raiz());
        for (String titulo : List.of("MÉTRICA", "MÍNIMO", "MÉDIA", "MÁXIMO", "P95", "HORA DO PICO")) {
            assertTrue(t.contains(titulo), titulo + " em " + t);
        }
        for (String nome : List.of("CPU", "Memória", "Disco", "Escrita no disco", "Download", "Upload")) {
            assertTrue(t.contains(nome), nome);
        }
        assertEquals(6, Visual.naEdt(() -> d.relatorio().linhas().size()));
        String resumo = Visual.naEdt(d::textoDoResumo);
        assertTrue(resumo.contains("• Período: última semana"), resumo);
        assertTrue(resumo.contains("CPU chegou a 91,0%"), "o pico de CPU vem dos máximos: " + resumo);
        assertTrue(resumo.contains("Estimativa do disco: no ritmo atual, enche em cerca de"), "uma semana de disco subindo: " + resumo);
        assertTrue(resumo.contains("É uma estimativa"), resumo);
        assertEquals("Última semana, até 28/10/2026 12:00.", Visual.naEdt(d::textoDaSituacao));
        assertTrue(Visual.naEdt(() -> d.botaoCopiarTexto().isEnabled() && d.botaoSalvarCsv().isEnabled() && d.botaoSalvarTexto().isEnabled()));
        Visual.salvar(Visual.renderizar(d.raiz(), 880, Visual.alturaPreferida(d.raiz(), 880)), "dialogo-relatorio-semana-claro");
        Visual.salvarNaGaleria(Visual.renderizar(d.raiz(), 880, Visual.alturaPreferida(d.raiz(), 880)), "relatorio-claro", d.titulo(), false, MolduraDeJanela.Tipo.DIALOGO);
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
        Visual.salvar(Visual.renderizar(d.raiz(), 880, Visual.alturaPreferida(d.raiz(), 880)), "dialogo-relatorio-semana-escuro");
        Visual.salvarNaGaleria(Visual.renderizar(d.raiz(), 880, Visual.alturaPreferida(d.raiz(), 880)), "relatorio-escuro", d.titulo(), true, MolduraDeJanela.Tipo.DIALOGO);
        Visual.tema(Tema.CLARO);
    }

    @Test
    void periodoDeDezMinutosNaoEstimaODiscoEDizPorqueEOsBotoesDaoOPeriodo() {
        DialogoRelatorio d = abrir(JanelaDeTempo.DEZ_MINUTOS, true, dados(JanelaDeTempo.DEZ_MINUTOS));
        assertTrue(Visual.naEdt(d::textoDoResumo).contains("Estimativa do disco: precisa de pelo menos 24 horas de dados. Escolha 1 dia ou mais."), Visual.naEdt(d::textoDoResumo));
        for (JanelaDeTempo j : JanelaDeTempo.values()) {
            assertTrue(Visual.naEdt(() -> d.botaoDaJanela(j).isEnabled()), j.name());
        }
        assertTrue(Visual.naEdt(() -> d.botaoDaJanela(JanelaDeTempo.DEZ_MINUTOS).isSelected()));
    }

    @Test
    void trocarDePeriodoCarregaEmFundoMostraCarregandoEDescartaResposta_deUmPeriodoQueOdonoJaTrocou() throws Exception {
        CountDownLatch porta = new CountDownLatch(1);
        DialogoRelatorio d = abrir(JanelaDeTempo.DEZ_MINUTOS, true, dados(JanelaDeTempo.DEZ_MINUTOS));
        segurar = porta;
        Visual.naEdt(() -> d.botaoDaJanela(JanelaDeTempo.UM_MES).doClick());
        assertTrue(Visual.naEdt(d::carregando));
        assertEquals("Carregando último mês…", Visual.naEdt(d::textoDaSituacao));
        assertFalse(Visual.naEdt(d.botaoSalvarCsv()::isEnabled), "sem dado do período novo ainda não há o que salvar");
        // o usuário troca de novo antes da resposta do mês chegar
        CountDownLatch segunda = new CountDownLatch(1);
        segurar = segunda;
        Visual.naEdt(() -> d.botaoDaJanela(JanelaDeTempo.UM_DIA).doClick());
        segunda.countDown();
        Espera.ate(PRAZO, "o dia chegou", () -> !Visual.naEdt(d::carregando));
        assertEquals(JanelaDeTempo.UM_DIA, d.periodo());
        porta.countDown(); // a resposta do MÊS, atrasada, chega agora e é jogada fora
        Espera.dormir(300);
        assertEquals(JanelaDeTempo.UM_DIA, Visual.naEdt(() -> d.relatorio().dados().janela()), "a resposta atrasada não troca o que o usuário vê");
        assertEquals(List.of(JanelaDeTempo.UM_MES, JanelaDeTempo.UM_DIA), pedidas);
    }

    @Test
    void falhaAoCarregarDizEmFraseSimplesENaoDeixaSalvarNada() {
        DialogoRelatorio d = abrir(JanelaDeTempo.DEZ_MINUTOS, true, dados(JanelaDeTempo.DEZ_MINUTOS));
        falhar = true;
        Visual.naEdt(() -> d.botaoDaJanela(JanelaDeTempo.UMA_HORA).doClick());
        Espera.ate(PRAZO, "erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        assertTrue(Visual.naEdt(d::textoDoErro).startsWith("Não consegui carregar última hora:"), Visual.naEdt(d::textoDoErro));
        assertFalse(Visual.naEdt(d.botaoSalvarCsv()::isEnabled));
        assertFalse(Visual.naEdt(d.botaoCopiarTexto()::isEnabled));
        falhar = false;
        Visual.naEdt(() -> d.botaoDaJanela(JanelaDeTempo.UM_DIA).doClick());
        Espera.ate(PRAZO, "voltou", () -> Visual.naEdt(d::textoDoErro).isEmpty() && !Visual.naEdt(d::carregando));
        assertTrue(Visual.naEdt(d.botaoSalvarCsv()::isEnabled));
    }

    @Test
    void agenteDeAntesSoDeixaOBotaoDeDezMinutos() {
        DialogoRelatorio d = abrir(JanelaDeTempo.DEZ_MINUTOS, false, dados(JanelaDeTempo.DEZ_MINUTOS));
        assertFalse(d.podeLongoPrazo());
        assertTrue(Visual.naEdt(() -> d.botaoDaJanela(JanelaDeTempo.DEZ_MINUTOS).isEnabled()));
        for (JanelaDeTempo j : List.of(JanelaDeTempo.UMA_HORA, JanelaDeTempo.UM_DIA, JanelaDeTempo.UMA_SEMANA, JanelaDeTempo.UM_MES)) {
            assertFalse(Visual.naEdt(() -> d.botaoDaJanela(j).isEnabled()), j.name());
            assertTrue(Visual.naEdt(() -> d.botaoDaJanela(j).getToolTipText()).contains("Atualize o agente"));
        }
    }

    @Test
    void copiarTextoLevaOTextoDoRelatorio() throws Exception {
        DialogoRelatorio d = abrir(JanelaDeTempo.UM_DIA, true, dados(JanelaDeTempo.UM_DIA));
        Visual.naEdt(() -> d.botaoCopiarTexto().doClick());
        String copiado = (String) area.getContents(null).getTransferData(DataFlavor.stringFlavor);
        assertEquals(ExportadorDeRelatorio.texto(d.relatorio(), "lab", AGORA), copiado);
        assertTrue(copiado.startsWith("Relatório de tendências do servidor lab\nGerado em 28/10/2026 12:00"), copiado);
        assertTrue(copiado.contains("TABELA"));
    }

    @Test
    void salvarCsvEMandaOCsvDeUmaPlanilhaParaOQueODonoEscolheNoSeletorDoSistema() {
        DialogoRelatorio d = abrir(JanelaDeTempo.UM_DIA, true, dados(JanelaDeTempo.UM_DIA));
        Visual.naEdt(() -> d.botaoSalvarCsv().doClick());
        Espera.ate(PRAZO, "gravou", () -> !gravados.isEmpty());
        assertEquals(List.of("tendencias-lab-1-dia-20261028-1200.csv"), nomesSugeridos);
        String g = gravados.get(0);
        assertTrue(g.startsWith("/pasta/qualquer/tendencias-lab-1-dia-20261028-1200.csv|﻿Data e hora;CPU (%);"), g.substring(0, 120));
        assertEquals(289, g.substring(g.indexOf('|') + 1).split("\r\n").length, "o cabeçalho e as 288 células do dia");
        Espera.ate(PRAZO, "avisou", () -> avisos.disse("ok", "Relatório salvo em"));
        assertFalse(Visual.naEdt(d::ultimoArquivoSalvo).isEmpty());
    }

    @Test
    void salvarTextoGravaOMesmoTextoQueSeCopia() {
        DialogoRelatorio d = abrir(JanelaDeTempo.UMA_HORA, true, dados(JanelaDeTempo.UMA_HORA));
        Visual.naEdt(() -> d.botaoSalvarTexto().doClick());
        Espera.ate(PRAZO, "gravou", () -> !gravados.isEmpty());
        assertEquals(List.of("tendencias-lab-1-hora-20261028-1200.txt"), nomesSugeridos);
        assertEquals(ExportadorDeRelatorio.texto(d.relatorio(), "lab", AGORA), gravados.get(0).substring(gravados.get(0).indexOf('|') + 1));
    }

    @Test
    void cancelarOSeletorNaoGravaNemAvisaNada() {
        DialogoRelatorio d = abrir(JanelaDeTempo.UM_DIA, true, dados(JanelaDeTempo.UM_DIA));
        escolha = Optional.empty();
        Visual.naEdt(() -> d.botaoSalvarCsv().doClick());
        Espera.dormir(300);
        assertEquals(List.of(), gravados);
        assertTrue(avisos.todos.isEmpty(), avisos.todos.toString());
    }

    @Test
    void falhaDeGravacaoDizEmPortuguesSimples(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        DialogoRelatorio d = Visual.naEdt(() -> new DialogoRelatorio(j -> dados(j), JanelaDeTempo.UM_DIA, dados(JanelaDeTempo.UM_DIA), true, "lab", SP, () -> AGORA, escolhedor(),
            (destino, bytes) -> {
                throw new java.nio.file.AccessDeniedException(destino.toString());
            }, new CopiadorDeTexto(() -> area), avisos));
        Visual.naEdt(() -> d.botaoSalvarCsv().doClick());
        Espera.ate(PRAZO, "erro", () -> avisos.todos.stream().anyMatch(a -> a.tipo().equals("erro")));
        assertTrue(avisos.todos.stream().anyMatch(a -> a.texto().startsWith("Não consegui salvar o relatório:")), avisos.todos.toString());
    }

    @Test
    void oArquivoNuncaSobrescreveOutroEOGravadorDeProducaoRecusaSobrescrever(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("relatorio.csv");
        Files.writeString(a, "do usuário");
        // o nome "livre" acrescenta -2, -3...
        java.lang.reflect.Method livre = DialogoRelatorio.class.getDeclaredMethod("livre", Path.class);
        livre.setAccessible(true);
        Path segundo = (Path) livre.invoke(null, a);
        assertEquals("relatorio-2.csv", segundo.getFileName().toString());
        Files.writeString(segundo, "x");
        assertEquals("relatorio-3.csv", ((Path) livre.invoke(null, a)).getFileName().toString());
        assertEquals(tmp.resolve("outro.csv"), livre.invoke(null, tmp.resolve("outro.csv")), "nome que não existe fica como está");
        // e, mesmo que alguém tente gravar por cima, o gravador de produção não deixa
        org.junit.jupiter.api.Assertions.assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> DialogoRelatorio.gravadorDoSistema().gravar(a, new byte[] {1}));
        assertEquals("do usuário", Files.readString(a));
        DialogoRelatorio.gravadorDoSistema().gravar(tmp.resolve("novo.csv"), "ok".getBytes(StandardCharsets.UTF_8));
        assertEquals("ok", Files.readString(tmp.resolve("novo.csv")));
    }
}
