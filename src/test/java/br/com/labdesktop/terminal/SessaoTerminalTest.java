package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.AgendadorFalso;
import br.com.labdesktop.terminal.EstadoDoTerminal.Tipo;
import br.com.labdesktop.terminal.TransporteDeTerminal.Disponibilidade;
import br.com.labdesktop.terminal.TransporteFalso.CanalFalso;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** O ciclo de vida do terminal (ARQUITETURA 7.3) com um transporte de mentira e o relógio nas mãos do teste. */
class SessaoTerminalTest {

    private static final Duration PRAZO = Duration.ofSeconds(5);

    private final TransporteFalso transporte = new TransporteFalso().sempreAbre();
    private final AgendadorFalso relogio = new AgendadorFalso();
    private final List<EstadoDoTerminal> historico = new CopyOnWriteArrayList<>();
    private final List<Long> geracoes = new CopyOnWriteArrayList<>();
    private final List<CanalDeTerminal> anexados = new CopyOnWriteArrayList<>();
    private SessaoTerminal sessao;

    @AfterEach
    void fechar() {
        if (sessao != null) {
            sessao.fechar();
        }
    }

    private SessaoTerminal nova(ComandoDeTerminal comando) {
        sessao = new SessaoTerminal(transporte, comando, () -> new TamanhoDeTerminal(100, 30), relogio);
        sessao.adicionarOuvinte(new SessaoTerminal.Ouvinte() {
            @Override
            public void aoMudarEstado(EstadoDoTerminal estado) {
                historico.add(estado);
            }

            @Override
            public void aoAnexar(CanalDeTerminal canal, long geracao) {
                anexados.add(canal);
                geracoes.add(geracao);
                // quem lê a saída é o emulador; aqui, uma thread que a drena até o fim (a sessão espera o EOF ser lido)
                Thread.ofVirtual().start(() -> {
                    try {
                        canal.saida().readAllBytes();
                    } catch (IOException e) {
                        // canal fechado por baixo: fim
                    }
                });
            }
        });
        return sessao;
    }

    private SessaoTerminal doClaude() {
        return nova(new ComandoDeTerminal.SessaoDoClaude("orbita"));
    }

    private void esperarTipo(Tipo tipo) {
        Espera.ate(PRAZO, () -> "estado=" + sessao.estado() + " historico=" + historico, () -> sessao.estado().tipo() == tipo);
    }

    private void esperarAnexos(int n) {
        Espera.ate(PRAZO, () -> "anexos=" + anexados.size() + " estado=" + sessao.estado(), () -> anexados.size() >= n && sessao.estado().ativa());
    }

    private List<Tipo> tipos() {
        List<Tipo> t = new ArrayList<>();
        for (EstadoDoTerminal e : historico) {
            if (t.isEmpty() || t.get(t.size() - 1) != e.tipo()) {
                t.add(e.tipo());
            }
        }
        return t;
    }

    @Test
    void abreComOTamanhoMedidoEFicaAtiva() {
        doClaude().iniciar();
        esperarAnexos(1);
        assertEquals(List.of(Tipo.ATIVA), tipos(), "ABRINDO é o estado de partida: ninguém é avisado dele");
        assertEquals(new TamanhoDeTerminal(100, 30), transporte.tamanhos.get(0));
        assertEquals(new ComandoDeTerminal.SessaoDoClaude("orbita"), transporte.comandos.get(0));
        assertTrue(sessao.ativa(geracoes.get(0)));
        assertFalse(sessao.ativa(geracoes.get(0) + 1), "só a anexação atual escreve");
    }

    @Test
    void quedaDoCanalReanexaEm2sCriandoOutraAnexacaoEDescartandoAAntiga() {
        doClaude().iniciar();
        esperarAnexos(1);
        long primeira = geracoes.get(0);

        transporte.ultimoCanal().fecharRemoto(null); // a rede caiu: canal sem status
        esperarTipo(Tipo.REANEXANDO);
        assertEquals(1, sessao.estado().tentativa());
        assertFalse(sessao.ativa(primeira), "na queda, escrever é proibido");
        assertEquals(1, transporte.verificacoes.get(), "pede ao transporte para conferir a saúde da conexão");
        assertEquals(1, transporte.aberturas.get(), "ainda não tentou: espera 2 s");

        relogio.avancar(Duration.ofMillis(1999));
        assertEquals(1, transporte.aberturas.get());
        relogio.avancar(Duration.ofMillis(1));
        esperarAnexos(2);
        assertEquals(2, transporte.aberturas.get());
        assertTrue(geracoes.get(1) > primeira);
        assertFalse(sessao.ativa(primeira));
        assertTrue(sessao.ativa(geracoes.get(1)));
    }

    @Test
    void asEsperasSao2_4_8_16_e_30SegundosEDepoisOBotaoReconectar() {
        doClaude().iniciar();
        esperarAnexos(1);
        transporte.comAbertura(n -> {
            throw new IOException("sem rota");
        });
        transporte.ultimoCanal().fecharRemoto(null);
        esperarTipo(Tipo.REANEXANDO);

        long[] esperas = {2, 4, 8, 16, 30};
        for (int i = 0; i < esperas.length; i++) {
            int tentativasAntes = transporte.aberturas.get();
            assertEquals(i + 1, sessao.estado().tentativa(), "tentativa " + (i + 1));
            relogio.avancar(Duration.ofSeconds(esperas[i]).minusMillis(1));
            assertEquals(tentativasAntes, transporte.aberturas.get(), "não tentou antes de " + esperas[i] + " s");
            relogio.avancar(Duration.ofMillis(1));
            int proximaTentativa = i + 1;
            if (i < esperas.length - 1) {
                Espera.ate(PRAZO, () -> "estado=" + sessao.estado(),
                    () -> sessao.estado().reanexando() && sessao.estado().tentativa() == proximaTentativa + 1);
            }
        }
        esperarTipo(Tipo.SEM_CONEXAO);
        assertEquals(1 + 5, transporte.aberturas.get(), "a abertura inicial mais as 5 tentativas");
        relogio.avancar(Duration.ofMinutes(10));
        assertEquals(1 + 5, transporte.aberturas.get(), "depois das 5, só o botão Reconectar tenta de novo");
        assertTrue(sessao.estado().motivo().contains("sem rota"));

        // o botão
        transporte.sempreAbre();
        sessao.reconectar();
        esperarAnexos(2);
    }

    @Test
    void sessaoQueNaoExisteNaoEntraEmLaco() {
        doClaude().iniciar();
        esperarAnexos(1);
        CanalFalso c = transporte.ultimoCanal();
        c.imprimir("Error response from daemon: No such container: lab-claude-orbita\r\n");
        c.fecharRemoto(1);
        esperarTipo(Tipo.SESSAO_FECHADA);
        relogio.avancar(Duration.ofMinutes(5));
        assertEquals(1, transporte.aberturas.get(), "não pode reanexar sozinho");
        assertEquals(List.of(Tipo.ATIVA, Tipo.SESSAO_FECHADA), tipos());
    }

    @Test
    void quandoATelaSabeQueASessaoAbriuReanexaNaHora() {
        doClaude().iniciar();
        esperarAnexos(1);
        transporte.ultimoCanal().imprimir("can't find session: work\r\n");
        transporte.ultimoCanal().fecharRemoto(1);
        esperarTipo(Tipo.SESSAO_FECHADA);

        sessao.aoSessaoAberta();
        esperarAnexos(2);
        assertEquals(2, transporte.aberturas.get());
    }

    @Test
    void aoSessaoAbertaNaoMexeComUmTerminalAtivo() {
        doClaude().iniciar();
        esperarAnexos(1);
        sessao.aoSessaoAberta();
        Espera.naoAcontece(Duration.ofMillis(200), "reabriu um terminal ativo", () -> transporte.aberturas.get() > 1);
    }

    @Test
    void statusZeroEFimNormalSemLaco() {
        doClaude().iniciar();
        esperarAnexos(1);
        transporte.ultimoCanal().imprimir("[exited]\r\n");
        transporte.ultimoCanal().fecharRemoto(0);
        esperarTipo(Tipo.ENCERRADA);
        assertEquals(0, sessao.estado().saida());
        relogio.avancar(Duration.ofMinutes(5));
        assertEquals(1, transporte.aberturas.get());
    }

    @Test
    void oShellQueSaiComStatusDeErroEncerraSemReanexar() {
        nova(ComandoDeTerminal.ShellDoServidor.INSTANCIA).iniciar();
        esperarAnexos(1);
        transporte.ultimoCanal().fecharRemoto(130);
        esperarTipo(Tipo.ENCERRADA);
        assertEquals(130, sessao.estado().saida());
        relogio.avancar(Duration.ofMinutes(5));
        assertEquals(1, transporte.aberturas.get());
    }

    @Test
    void semConexaoAsTentativasNaoSaoGastasEOTerminalAnexaQuandoAConexaoVolta() {
        doClaude().iniciar();
        esperarAnexos(1);
        transporte.emitir(Disponibilidade.INDISPONIVEL);
        esperarTipo(Tipo.REANEXANDO);
        assertTrue(transporte.ultimoCanal().fechamentosLocais.get() >= 1, "a sessão fecha o canal na hora em que sabe da queda");

        // dois minutos de rede fora: nenhuma tentativa é gasta e o terminal não desiste
        relogio.avancar(Duration.ofMinutes(2));
        relogio.avancar(Duration.ofMinutes(2));
        assertEquals(1, transporte.aberturas.get(), "sem conexão não há tentativa");
        assertEquals(Tipo.REANEXANDO, sessao.estado().tipo());
        assertEquals(1, sessao.estado().tentativa());

        transporte.emitir(Disponibilidade.DISPONIVEL);
        esperarAnexos(2);
        assertEquals(2, transporte.aberturas.get(), "voltou a conexão: anexa sem esperar o resto da espera");
    }

    @Test
    void bloquearOCofreFechaOCanalEOTerminalNuncaMaisReanexa() {
        doClaude().iniciar();
        esperarAnexos(1);
        CanalFalso c = transporte.ultimoCanal();
        transporte.emitir(Disponibilidade.BLOQUEADO);
        esperarTipo(Tipo.BLOQUEADA);
        assertFalse(c.aberto(), "o canal foi fechado");
        assertFalse(sessao.ativa(geracoes.get(0)));
        transporte.emitir(Disponibilidade.DISPONIVEL);
        relogio.avancar(Duration.ofMinutes(5));
        sessao.reconectar();
        Espera.naoAcontece(Duration.ofMillis(200), "reabriu com o cofre bloqueado", () -> transporte.aberturas.get() > 1);
        assertEquals(Tipo.BLOQUEADA, sessao.estado().tipo());
    }

    @Test
    void servidorQueRecusaOTerminalNaoEntraEmLacoEOReconectarTentaDeNovo() {
        transporte.comAbertura(n -> {
            throw new TerminalRecusadoException("O servidor não aceita mais terminais.", null);
        });
        doClaude().iniciar();
        esperarTipo(Tipo.SEM_CONEXAO);
        assertEquals("O servidor não aceita mais terminais.", sessao.estado().motivo());
        relogio.avancar(Duration.ofMinutes(5));
        assertEquals(1, transporte.aberturas.get());

        transporte.sempreAbre();
        sessao.reconectar();
        esperarAnexos(1);
    }

    @Test
    void aPrimeiraAberturaQueFalhaTentaEm2sSemGastarAsCinco() {
        transporte.comAbertura(n -> {
            if (n == 1) {
                throw new IOException("ainda sem conexão");
            }
            return transporte.sempreAbre().abrirNovo();
        });
        doClaude().iniciar();
        esperarTipo(Tipo.REANEXANDO);
        assertEquals(1, sessao.estado().tentativa());
        relogio.avancar(Duration.ofSeconds(2));
        esperarAnexos(1);
        assertEquals(List.of(Tipo.REANEXANDO, Tipo.ATIVA), tipos());
    }

    @Test
    void fecharAAbaFechaOCanalEPara() {
        doClaude().iniciar();
        esperarAnexos(1);
        CanalFalso c = transporte.ultimoCanal();
        int eventos = historico.size();
        sessao.fechar();
        Espera.ate(PRAZO, "canal fechado", () -> !c.aberto());
        Espera.naoAcontece(Duration.ofMillis(300), "avisou depois de fechada", () -> historico.size() > eventos);
        assertEquals(1, transporte.aberturas.get());
    }

    @Test
    void canalQueAbreDepoisDeAAbaFecharEFechadoNaHora() throws Exception {
        CountDownLatch solta = transporte.segurarAberturas();
        doClaude().iniciar();
        Espera.ate(PRAZO, "abertura em voo", () -> transporte.aberturas.get() == 1);
        sessao.fechar();
        solta.countDown();
        Espera.ate(PRAZO, "o canal que chegou tarde foi fechado", () -> !transporte.canais.isEmpty() && !transporte.canais.get(0).aberto());
        assertTrue(anexados.isEmpty(), "ninguém foi avisado de um canal de uma aba fechada");
    }

    @Test
    void estadoAtivaEhPublicadoAntesDeAnexar() {
        // o conector escreve assim que o widget liga: a sessão já precisa estar ATIVA nesse instante
        List<Boolean> ativaNoAnexo = new CopyOnWriteArrayList<>();
        sessao = new SessaoTerminal(transporte, new ComandoDeTerminal.SessaoDoClaude("orbita"), () -> TamanhoDeTerminal.PADRAO, relogio);
        sessao.adicionarOuvinte(new SessaoTerminal.Ouvinte() {
            @Override
            public void aoMudarEstado(EstadoDoTerminal estado) { }

            @Override
            public void aoAnexar(CanalDeTerminal canal, long geracao) {
                ativaNoAnexo.add(sessao.ativa(geracao));
            }
        });
        sessao.iniciar();
        Espera.ate(PRAZO, "anexou", () -> !ativaNoAnexo.isEmpty());
        assertEquals(List.of(true), ativaNoAnexo);
        assertNull(sessao.estado().motivo());
    }
}
