package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.registro.FiltroDeRegistro;
import br.com.labdesktop.registro.InterpretadorDeRegistro;
import br.com.labdesktop.registro.LinhaDeRegistro;
import br.com.labdesktop.registro.ResultadoDoRegistro;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.HistoricoSintetico;
import br.com.labdesktop.tendencias.DadosDeTendencia;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.tendencias.Metrica;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoRegistro;
import br.com.labdesktop.ui.dialogos.DialogoRelatorio;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Os seis gráficos de tendência (períodos, esqueleto, falhas, agente antigo), a caixa do Registro (lista, filtros, pausa, cópia, estados) e os botões Relatório e Ver tudo, no
 * Resumo de verdade desenhado sem janela. Tudo é alimentado à mão: nenhuma rede.
 */
@ExtendWith(VerificaEdt.class)
class ResumoComTendenciasERegistroTest {

    static final Instant AGORA = Instant.parse("2026-10-28T15:00:00Z");
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    final AvisosGravados avisos = new AvisosGravados();
    final DialogosGravados dialogos = new DialogosGravados();
    final Clipboard area = new Clipboard("teste");
    final List<String> salvos = new ArrayList<>();

    ApoioDoResumo apoio() {
        return new ApoioDoResumo(dialogos, avisos, new CopiadorDeTexto(() -> area), new EscolhedorLocal() {
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
                return Optional.of(Path.of("/pasta-de-teste", nomeSugerido));
            }
        }, MemoriaDeCaminhos.emMemoria(), (destino, bytes) -> salvos.add(destino + "|" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8)), () -> SP, () -> AGORA, null);
    }

    PaginaResumo pagina() {
        Visual.tema(Tema.CLARO);
        PaginaResumo p = Visual.naEdt(() -> new PaginaResumo(null, () -> { }, aba -> { }, apoio()));
        Visual.naEdt(() -> {
            p.relogio(() -> AGORA, SP);
            p.aoMudarEstado(TelaProjetosTest.estado(EstadoConexao.CONECTADO));
            p.aoReceberServidor(FixturesDeApi.servidorSintetico());
            p.aoReceberDetalhe(FixturesDeApi.detalheSintetico());
            p.aoReceberSeguranca(ModeloDeKpisTest.comAtaque());
        });
        return p;
    }

    static DadosDeTendencia dados(JanelaDeTempo j) {
        return DadosDeTendencia.deHistorico(j, HistoricoSintetico.dados(j, AGORA, 0), AGORA);
    }

    // ---------------------------------------------------------------------------------------------
    // Tendências
    // ---------------------------------------------------------------------------------------------

    @Test
    void comOAgenteNovoOsSeisGraficosTemDadoComValorDeAgoraELegenda() {
        PaginaResumo p = pagina();
        Visual.naEdt(() -> {
            p.definirHistoricoDeLongoPrazoParaTeste(true);
            p.aoReceberHistorico(dados(JanelaDeTempo.DEZ_MINUTOS));
        });
        BlocoDeTendencias b = p.blocoDeTendencias();
        assertEquals(List.of("CPU", "Memória", "Disco", "Escrita no disco", "Download", "Upload"), Visual.naEdt(b::nomes));
        for (var g : Visual.naEdt(b::graficos)) {
            assertEquals(60, g.quantidadeDePontos());
        }
        List<String> agora = Visual.naEdt(b::valoresDeAgora);
        assertTrue(agora.get(0).endsWith("%") && agora.get(2).endsWith("%"), agora.toString());
        assertTrue(agora.get(3).endsWith("B/s") || agora.get(3).endsWith("MB/s"), "escrita em bytes: " + agora.get(3));
        assertTrue(agora.get(4).endsWith("b/s") && agora.get(5).endsWith("b/s"), "download e upload em bits: " + agora);
        for (String l : Visual.naEdt(b::legendas)) {
            assertTrue(l.startsWith("mín ") && l.contains(" · méd ") && l.contains(" · máx "), l);
        }
        assertEquals("últimos 10 minutos", Visual.naEdt(() -> b.aviso().getText()));
        assertFalse(Visual.naEdt(b::temAviso));
    }

    @Test
    void esqueletoAteOPrimeiroDadoDoPeriodoEDepoisOsGraficos() {
        PaginaResumo p = pagina();
        Visual.naEdt(() -> p.definirHistoricoDeLongoPrazoParaTeste(true));
        BlocoDeTendencias b = p.blocoDeTendencias();
        assertEquals(BlocoDeTendencias.Situacao.CARREGANDO, Visual.naEdt(() -> b.ultimaVisao().situacao()));
        assertTrue(Visual.naEdt(b::legendas).stream().allMatch(l -> l.equals("carregando…")), "todos em esqueleto");
        Visual.salvar(Visual.renderizar(p, 884, 640), "tendencias-carregando");
        Visual.naEdt(() -> p.aoReceberHistorico(dados(JanelaDeTempo.DEZ_MINUTOS)));
        assertEquals(BlocoDeTendencias.Situacao.OK, Visual.naEdt(() -> b.ultimaVisao().situacao()));
        assertFalse(Visual.naEdt(b::legendas).contains("carregando…"));
    }

    @Test
    void trocarOPeriodoMostraEsqueletoAteChegarEGuardaOUltimoDadoDeCadaPeriodo() {
        PaginaResumo p = pagina();
        Visual.naEdt(() -> {
            p.definirHistoricoDeLongoPrazoParaTeste(true);
            p.aoReceberHistorico(dados(JanelaDeTempo.DEZ_MINUTOS));
        });
        BlocoDeTendencias b = p.blocoDeTendencias();
        Visual.naEdt(() -> p.escolherJanelaParaTeste(JanelaDeTempo.UMA_SEMANA));
        assertEquals(JanelaDeTempo.UMA_SEMANA, p.janelaEscolhida());
        assertEquals("1 semana", Visual.naEdt(() -> b.botaoDaJanela(JanelaDeTempo.UMA_SEMANA).getText()));
        assertTrue(Visual.naEdt(() -> b.botaoDaJanela(JanelaDeTempo.UMA_SEMANA).isSelected()));
        assertEquals(BlocoDeTendencias.Situacao.CARREGANDO, Visual.naEdt(() -> b.ultimaVisao().situacao()), "a semana ainda não chegou");
        Visual.naEdt(() -> p.aoReceberHistorico(dados(JanelaDeTempo.UMA_SEMANA)));
        assertEquals(252, Visual.naEdt(b::graficos).get(0).quantidadeDePontos());
        assertEquals("última semana", Visual.naEdt(() -> b.aviso().getText()));
        // voltar a 10 min: o dado de 10 min que já tinha aparece na hora (nada de esqueleto)
        Visual.naEdt(() -> p.escolherJanelaParaTeste(JanelaDeTempo.DEZ_MINUTOS));
        assertEquals(BlocoDeTendencias.Situacao.OK, Visual.naEdt(() -> b.ultimaVisao().situacao()));
        assertEquals(60, Visual.naEdt(b::graficos).get(0).quantidadeDePontos());
    }

    @Test
    void periodoGrandeComOComecoSemDadoDizDesdeQuandoHaDado() {
        PaginaResumo p = pagina();
        Visual.naEdt(() -> {
            p.definirHistoricoDeLongoPrazoParaTeste(true);
            p.escolherJanelaParaTeste(JanelaDeTempo.UM_DIA);
            p.aoReceberHistorico(DadosDeTendencia.deHistorico(JanelaDeTempo.UM_DIA, HistoricoSintetico.dados(JanelaDeTempo.UM_DIA, AGORA, 200), AGORA));
        });
        BlocoDeTendencias b = p.blocoDeTendencias();
        String aviso = Visual.naEdt(() -> b.aviso().getText());
        assertTrue(aviso.startsWith("Só há dado desde 28/10 "), aviso);
        assertTrue(Visual.naEdt(b::temAviso));
        assertEquals(88, Visual.naEdt(b::graficos).get(0).quantidadeDePontos(), "as 200 primeiras células não têm dado: 288 menos 200");
        Visual.salvar(Visual.renderizar(p, 884, 640), "tendencias-periodo-parcial-claro");
    }

    @Test
    void agenteDeAntesSoTemOBotaoDeDezMinutosEOsOutrosApagadosComAExplicacao() {
        PaginaResumo p = pagina();
        BlocoDeTendencias b = p.blocoDeTendencias();
        for (JanelaDeTempo j : JanelaDeTempo.values()) {
            boolean ligado = Visual.naEdt(() -> b.botaoDaJanela(j).isEnabled());
            assertEquals(j == JanelaDeTempo.DEZ_MINUTOS, ligado, j.name());
            if (!ligado) {
                assertTrue(Visual.naEdt(() -> b.botaoDaJanela(j).getToolTipText()).contains("Atualize o agente"));
            }
        }
        Visual.naEdt(() -> p.escolherJanelaParaTeste(JanelaDeTempo.UM_DIA));
        assertEquals(JanelaDeTempo.DEZ_MINUTOS, p.janelaEscolhida(), "o botão apagado não faz nada");
        Visual.naEdt(() -> p.lembrarJanelaParaTeste(JanelaDeTempo.UM_DIA)); // como se a memória tivesse lembrado um dia, de quando o agente era outro
        assertEquals(JanelaDeTempo.UM_DIA, p.janelaEscolhida());
        assertEquals(JanelaDeTempo.DEZ_MINUTOS, p.janelaEfetiva(), "o que aparece são os 10 minutos");
        assertEquals("Este agente só guarda 10 min. Atualize o agente.", Visual.naEdt(() -> b.aviso().getText()));
        assertEquals(60, Visual.naEdt(b::graficos).get(0).quantidadeDePontos());
        assertFalse(p.historicoAssinado());
    }

    @Test
    void falhaDoPeriodoMantemOUltimoDadoEDizENaoSomeComOGrafico() {
        PaginaResumo p = pagina();
        Visual.naEdt(() -> {
            p.definirHistoricoDeLongoPrazoParaTeste(true);
            p.aoReceberHistorico(dados(JanelaDeTempo.DEZ_MINUTOS));
        });
        BlocoDeTendencias b = p.blocoDeTendencias();
        Visual.naEdt(() -> {
            try {
                var m = PaginaResumo.class.getDeclaredMethod("aoFalharHistorico");
                m.setAccessible(true);
                m.invoke(p);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(p.historicoFalhou());
        assertEquals(BlocoDeTendencias.Situacao.FALHOU_COM_DADO, Visual.naEdt(() -> b.ultimaVisao().situacao()));
        assertEquals("Não consegui carregar: mostrando o último dado.", Visual.naEdt(() -> b.aviso().getText()));
        assertEquals(60, Visual.naEdt(b::graficos).get(0).quantidadeDePontos(), "o último dado continua");
        Visual.naEdt(() -> p.aoReceberHistorico(dados(JanelaDeTempo.DEZ_MINUTOS)));
        assertFalse(p.historicoFalhou(), "chegou dado novo: o aviso some");
    }

    @Test
    void semDadoDeUmaMetricaDizSemDadoAindaSoNela() {
        PaginaResumo p = pagina();
        var h = HistoricoSintetico.dados(JanelaDeTempo.DEZ_MINUTOS, AGORA, 0);
        var semDisco = new br.com.labdesktop.api.modelo.HistoricoDoAgente(h.janela(), h.passoS(), h.inicio(), h.fim(), h.quando(), h.cpu(), h.cpuMax(), h.mem(), List.of(),
            h.discoEscritaBps(), h.discoEscritaMaxBps(), h.rxBps(), h.txBps());
        Visual.naEdt(() -> {
            p.definirHistoricoDeLongoPrazoParaTeste(true);
            p.aoReceberHistorico(DadosDeTendencia.deHistorico(JanelaDeTempo.DEZ_MINUTOS, semDisco, AGORA));
        });
        List<String> l = Visual.naEdt(p.blocoDeTendencias()::legendas);
        assertEquals("sem dado ainda", l.get(2), "Disco");
        assertTrue(l.get(0).startsWith("mín"), "CPU tem");
        assertEquals("—", Visual.naEdt(p.blocoDeTendencias()::valoresDeAgora).get(2));
    }

    @Test
    void oBotaoRelatorioAbreODialogoComOPeriodoEscolhido() {
        PaginaResumo p = pagina();
        Visual.naEdt(() -> {
            p.definirHistoricoDeLongoPrazoParaTeste(true);
            p.escolherJanelaParaTeste(JanelaDeTempo.UM_DIA);
            p.aoReceberHistorico(dados(JanelaDeTempo.UM_DIA));
            p.blocoDeTendencias().botaoRelatorio().doClick();
        });
        DialogoRelatorio d = dialogos.ultimoComo(DialogoRelatorio.class);
        assertEquals(JanelaDeTempo.UM_DIA, d.periodo());
        assertTrue(d.podeLongoPrazo());
        assertFalse(d.carregando(), "usou o dado que a tela já tinha");
        assertEquals(6, Visual.naEdt(() -> d.relatorio().linhas().size()));
    }

    // ---------------------------------------------------------------------------------------------
    // Registro
    // ---------------------------------------------------------------------------------------------

    static final String JOURNAL = """
        2026-10-28T11:55:01-0300 lab sshd[1203]: Accepted publickey for labuser from 10.0.0.9 port 51234 ssh2
        2026-10-28T11:56:10-0300 lab kernel: usb 1-1: new high-speed USB device number 5
        2026-10-28T11:58:00-0300 lab dockerd[900]: level=warning msg="Your kernel does not support swap limit capabilities"
        2026-10-28T11:59:30-0300 lab sshd[1250]: Failed password for invalid user admin from 203.0.113.9 port 4000 ssh2
        """;

    static ResultadoDoRegistro.Lido lido(String saida, String fonte) {
        var l = InterpretadorDeRegistro.doSistema(saida, AGORA, SP);
        return new ResultadoDoRegistro.Lido(InterpretadorDeRegistro.maisNovasEmCima(l.linhas()), fonte, l.cortada());
    }

    static ResultadoDoRegistro.Lido muitasLinhas() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String sev = i % 17 == 0 ? "Failed password for root" : i % 7 == 0 ? "warning: tentando de novo" : "Started job " + i;
            sb.append("2026-10-28T11:").append(String.format("%02d", i / 4)).append(':').append(String.format("%02d", (i % 4) * 15)).append("-0300 lab app[").append(i).append("]: ").append(sev).append('\n');
        }
        return lido(sb.toString(), "do diário do sistema (journalctl)");
    }

    /** Uma Fonte falsa: conta quantas vezes assinaram e soltaram, e guarda o ouvinte para o teste entregar resultados. */
    static final class FonteFalsa implements PainelDoRegistro.Fonte {
        final AtomicInteger assinou = new AtomicInteger();
        final AtomicInteger soltou = new AtomicInteger();
        final AtomicInteger atualizouAgora = new AtomicInteger();
        OuvinteDeTopico<ResultadoDoRegistro> ouvinte;

        @Override
        public Inscricao assinar(OuvinteDeTopico<ResultadoDoRegistro> o) {
            assinou.incrementAndGet();
            ouvinte = o;
            return soltou::incrementAndGet;
        }

        @Override
        public void atualizarAgora() {
            atualizouAgora.incrementAndGet();
        }
    }

    PainelDoRegistro novoPainel(FonteFalsa fonte, boolean compacto) {
        Visual.tema(Tema.CLARO);
        PainelDoRegistro p = Visual.naEdt(() -> new PainelDoRegistro(compacto, fonte, new CopiadorDeTexto(() -> area), avisos, () -> SP));
        p.relogio(() -> AGORA);
        return p;
    }

    @Test
    void registroMostraMaisNovasEmCimaComSeveridadeEmPalavraOrigemEMensagem() {
        PainelDoRegistro p = novoPainel(null, true);
        assertEquals("esqueleto", p.cartaoAtual(), "antes da primeira leitura: esqueleto");
        Visual.naEdt(() -> p.aoReceber(lido(JOURNAL, "do diário do sistema (journalctl)")));
        assertEquals("lista", p.cartaoAtual());
        List<LinhaDeRegistro> l = Visual.naEdt(p::linhasQueAparecem);
        assertEquals(4, l.size());
        assertTrue(l.get(0).mensagem().startsWith("Failed password"), "a mais nova primeiro");
        assertEquals("sshd", l.get(0).origem());
        assertEquals("Erro", l.get(0).severidade().palavra());
        String status = Visual.naEdt(p::textoDoStatus);
        assertTrue(status.contains("journalctl") && status.contains("12:00:00"), status);
        // a linha é descrita ao leitor de tela com a PALAVRA (e não só com a cor), e a hora está no fuso do usuário
        var linhaDesenhada = Visual.naEdt(() -> p.lista().getCellRenderer().getListCellRendererComponent(p.lista(), l.get(0), 0, false, false));
        String descricao = Visual.naEdt(() -> ((javax.swing.JComponent) linhaDesenhada).getAccessibleContext().getAccessibleName());
        assertTrue(descricao.startsWith("Erro: 11:59:30  sshd  Failed password"), descricao);
        // e é PINTADA: o ícone e a palavra na cor de erro, nas linhas de erro; as de info não têm nada dessa cor
        Visual.renderizar(p, 520, 200);
        var imagem = Visual.renderizar(p, 520, 200);
        Visual.salvar(imagem, "registro-linhas-claro");
        assertTrue(Visual.contarPixels(imagem, br.com.labdesktop.ui.tema.Tokens.cor(br.com.labdesktop.ui.tema.Tokens.PERIGO), 40) > 20, "a linha de erro tem ícone e palavra na cor de erro");
    }

    @Test
    void filtroPorTextoESeveridadeAvisosIncluemErrosEMostraQuantasPassam() {
        PainelDoRegistro p = novoPainel(null, true);
        Visual.naEdt(() -> p.aoReceber(lido(JOURNAL, "do diário do sistema (journalctl)")));
        Visual.naEdt(() -> p.botaoDoNivel(FiltroDeRegistro.Nivel.AVISOS).doClick());
        assertEquals(2, Visual.naEdt(p::linhasQueAparecem).size());
        Visual.naEdt(() -> p.botaoDoNivel(FiltroDeRegistro.Nivel.ERROS).doClick());
        assertEquals(1, Visual.naEdt(p::linhasQueAparecem).size());
        Visual.naEdt(() -> p.botaoDoNivel(FiltroDeRegistro.Nivel.TUDO).doClick());
        Visual.naEdt(() -> p.campoDoFiltro().setText("usb"));
        assertEquals(1, Visual.naEdt(p::linhasQueAparecem).size());
        assertTrue(Visual.naEdt(p::textoDoStatus).contains("1/4"), Visual.naEdt(p::textoDoStatus));
        Visual.naEdt(() -> p.campoDoFiltro().setText("nada disto"));
        assertEquals("mensagem", p.cartaoAtual());
        assertEquals("Nenhuma linha passa pelo filtro.", Visual.naEdt(p::tituloDaMensagem));
        Visual.naEdt(() -> p.campoDoFiltro().setText(""));
        assertEquals("lista", p.cartaoAtual());
        assertEquals(4, Visual.naEdt(p::linhasQueAparecem).size());
    }

    @Test
    void registroVazioDizNenhumaLinhaSemParecerErro() {
        PainelDoRegistro p = novoPainel(null, true);
        Visual.naEdt(() -> p.aoReceber(new ResultadoDoRegistro.Lido(List.of(), "do diário do sistema (journalctl)", false)));
        assertEquals("mensagem", p.cartaoAtual());
        assertEquals("Nenhuma linha no registro.", Visual.naEdt(p::tituloDaMensagem));
    }

    @Test
    void semPermissaoMostraAFraseOComandoExatoEUmBotaoParaCopiar() throws Exception {
        PainelDoRegistro p = novoPainel(null, true);
        Visual.naEdt(() -> p.aoReceber(new ResultadoDoRegistro.SemPermissao("Seu usuário não tem permissão para ler o registro do sistema.", "sudo usermod -aG systemd-journal labuser",
            "Este comando põe o seu usuário no grupo que lê o registro do sistema. Rode-o você mesmo no servidor (o app nunca pede nem usa senha de administrador).")));
        assertEquals("mensagem", p.cartaoAtual());
        assertEquals("Seu usuário não tem permissão para ler o registro do sistema.", Visual.naEdt(p::tituloDaMensagem));
        assertTrue(Visual.naEdt(p::textoDaMensagem).contains("o app nunca pede nem usa senha de administrador"));
        assertEquals("sudo usermod -aG systemd-journal labuser", Visual.naEdt(p::comandoMostrado));
        Visual.naEdt(() -> p.botaoCopiarComando().doClick());
        assertEquals("sudo usermod -aG systemd-journal labuser", area.getContents(null).getTransferData(DataFlavor.stringFlavor));
        Visual.salvar(Visual.renderizar(p, 520, 200), "registro-sem-permissao-claro");
    }

    @Test
    void indisponivelEFalhaDeLeituraDizemEmPalavrasSimples() {
        PainelDoRegistro p = novoPainel(null, true);
        Visual.naEdt(() -> p.aoReceber(new ResultadoDoRegistro.Indisponivel("O Docker não está aberto. Abra o Docker e o registro do laboratório volta a aparecer aqui.")));
        assertEquals("Não há registro para mostrar.", Visual.naEdt(p::tituloDaMensagem));
        assertTrue(Visual.naEdt(p::textoDaMensagem).contains("Abra o Docker"));
        PainelDoRegistro q = novoPainel(null, true);
        Visual.naEdt(q::aoFalharLeitura);
        assertEquals("Não consegui ler o registro agora.", Visual.naEdt(q::tituloDaMensagem), "falha sem nenhuma leitura antes");
        Visual.naEdt(() -> q.aoReceber(lido(JOURNAL, "do diário do sistema (journalctl)")));
        Visual.naEdt(q::aoFalharLeitura);
        assertEquals("lista", q.cartaoAtual(), "com leitura antes, a lista fica");
        assertTrue(Visual.naEdt(q::textoDoStatus).startsWith("sem leitura nova"), Visual.naEdt(q::textoDoStatus));
    }

    @Test
    void selecaoECopiaDeLinhasComAHoraDoDonoEOMenuCopiarTudo() throws Exception {
        PainelDoRegistro p = novoPainel(null, true);
        Visual.naEdt(() -> p.aoReceber(lido(JOURNAL, "do diário do sistema (journalctl)")));
        Visual.naEdt(() -> p.copiarSelecionadas());
        assertTrue(avisos.disse("info", "Selecione"), "sem seleção, o app diz como fazer: " + avisos.todos);
        Visual.naEdt(() -> p.lista().setSelectionInterval(0, 1));
        Visual.naEdt(p::copiarSelecionadas);
        String copiado = (String) area.getContents(null).getTransferData(DataFlavor.stringFlavor);
        assertEquals("11:59:30  sshd  Failed password for invalid user admin from 203.0.113.9 port 4000 ssh2\n11:58:00  dockerd  level=warning msg=\"Your kernel does not support swap limit capabilities\"",
            copiado);
        assertTrue(avisos.disse("info", "2 linhas copiadas"), avisos.todos.toString());
        Visual.naEdt(p::copiarTudo);
        assertEquals(4, ((String) area.getContents(null).getTransferData(DataFlavor.stringFlavor)).split("\n").length);
        // o atalho de teclado (Cmd/Ctrl + C) está ligado à mesma ação
        assertNotNull(Visual.naEdt(() -> p.lista().getActionMap().get("copiar-linhas")));
    }

    @Test
    void atualizacaoPreservaASelecaoEOMesmoTopoQuandoODonoRolouParaAsLinhasVelhas() {
        PainelDoRegistro p = novoPainel(null, true);
        ResultadoDoRegistro.Lido a = muitasLinhas();
        Visual.naEdt(() -> p.aoReceber(a));
        Visual.renderizar(p, 520, 200);
        LinhaDeRegistro escolhida = a.linhas().get(50);
        Visual.naEdt(() -> p.lista().setSelectedValue(escolhida, true));
        // chega uma linha NOVA em cima
        List<LinhaDeRegistro> maisUma = new ArrayList<>(a.linhas());
        maisUma.add(0, new LinhaDeRegistro(AGORA, "app", "linha nova de agora", br.com.labdesktop.registro.Severidade.INFO));
        Visual.naEdt(() -> p.aoReceber(new ResultadoDoRegistro.Lido(maisUma, a.fonte(), false)));
        assertEquals(201, Visual.naEdt(p::linhasQueAparecem).size());
        assertEquals(escolhida, Visual.naEdt(() -> p.lista().getSelectedValue()), "a linha selecionada continua selecionada");
        assertEquals(51, Visual.naEdt(() -> p.lista().getSelectedIndex()));
    }

    @Test
    void pausarContinuarEAtualizarEmPausaComUmaFonteVisivel() {
        FonteFalsa f = new FonteFalsa();
        PainelDoRegistro p = novoPainel(f, true);
        // sem janela de verdade o painel nunca está "showing": o teste chama o que o HierarchyListener chamaria ao aparecer e ao esconder
        Visual.naEdt(() -> p.forcarVisivelParaTeste(true));
        assertEquals(1, f.assinou.get());
        assertTrue(Visual.naEdt(p::assinada));
        Visual.naEdt(() -> p.botaoPausar().doClick());
        assertTrue(Visual.naEdt(p::pausado));
        assertEquals(1, f.soltou.get(), "pausar solta a assinatura: nenhuma leitura enquanto pausado");
        assertFalse(Visual.naEdt(p::assinada));
        assertTrue(Visual.naEdt(p::textoDoStatus).toLowerCase().contains("em pausa"), Visual.naEdt(p::textoDoStatus));
        // Atualizar em pausa: lê UMA vez (assina, recebe e solta)
        Visual.naEdt(() -> p.botaoAtualizar().doClick());
        assertEquals(2, f.assinou.get());
        assertEquals(1, f.atualizouAgora.get());
        Visual.naEdt(() -> f.ouvinte.aoAtualizar(lido(JOURNAL, "do diário do sistema (journalctl)")));
        assertEquals(2, f.soltou.get(), "recebeu a leitura avulsa e soltou de novo");
        assertTrue(Visual.naEdt(p::pausado));
        assertEquals(4, Visual.naEdt(p::linhasQueAparecem).size());
        // Continuar: volta a assinar
        Visual.naEdt(() -> p.botaoPausar().doClick());
        assertFalse(Visual.naEdt(p::pausado));
        assertEquals(3, f.assinou.get());
        // escondido (outra aba na frente): solta
        Visual.naEdt(() -> p.forcarVisivelParaTeste(false));
        assertEquals(3, f.soltou.get());
    }

    @Test
    void verTudoAbreAJanelaMaiorComAMesmaLista() {
        FonteFalsa f = new FonteFalsa();
        PaginaResumo p = pagina();
        Visual.naEdt(() -> p.caixaDoRegistro().painel().botaoVerTudo().doClick());
        DialogoRegistro d = dialogos.ultimoComo(DialogoRegistro.class);
        assertEquals("Registro do servidor", d.titulo());
        Visual.naEdt(() -> d.painel().aoReceber(muitasLinhas()));
        assertEquals(200, Visual.naEdt(() -> d.painel().linhasQueAparecem().size()));
        assertFalse(Visual.naEdt(() -> d.painel().botaoVerTudo().isVisible()), "na janela grande não há \"Ver tudo\"");
        Visual.salvar(Visual.renderizar(d.raiz(), 920, 560), "registro-ver-tudo-claro");
        Visual.naEdt(() -> d.botaoFechar().doClick());
        assertNotNull(f);
    }

    @Test
    void ordemENomesSaoOsDoPedidoNaTelaInteira() {
        PaginaResumo p = pagina();
        String t = br.com.labdesktop.ui.TextosDeTela.de(p);
        assertTrue(t.indexOf("Tendências") < t.indexOf("Registro do servidor"), "o Registro vem abaixo das Tendências");
        assertTrue(t.contains("Relatório"));
        assertTrue(t.contains("Ver tudo") || Visual.naEdt(() -> p.caixaDoRegistro().painel().botaoVerTudo().isVisible()));
        assertEquals(6, Metrica.values().length);
    }
}
