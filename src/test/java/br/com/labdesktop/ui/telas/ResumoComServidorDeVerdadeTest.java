package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O Resumo ligado a um {@link ServidorLab} de verdade (SSH embutido, agente falso, cofre falso): o histórico só é pedido com a aba assinando e só se o agente declara; trocar de período
 * troca a assinatura e é lembrado por servidor; o registro vem por SSH; servidor sem agente continua com Registro e diz "Instale o agente". Nada de rede além do servidor de teste.
 */
@ExtendWith(VerificaEdt.class)
class ResumoComServidorDeVerdadeTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreFalso cofre;
    private ServidorLab lab;
    private final MemoriaDeCaminhos memoria = MemoriaDeCaminhos.emMemoria();
    private final DialogosGravados dialogos = new DialogosGravados();
    private final AvisosGravados avisos = new AvisosGravados();

    @BeforeEach
    void montar() throws Exception {
        Visual.preparar();
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        TokenAgente token = TokenAgente.gerar();
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta()).comToken(token, "mac-de-teste");
        ssh.aceitar(cofre.par().getPublic());
        ssh.comandoRemoto(agente.comandosPorSsh());
        agente.tokens.put(token.hashSha256Hex(), "mac-de-teste");
        agente.agoraDoAgente = Instant.parse("2026-10-28T15:00:00Z");
    }

    @AfterEach
    void desmontar() throws Exception {
        if (lab != null) {
            lab.fechar().join();
        }
        agente.close();
        ssh.close();
    }

    private ApoioDoResumo apoio() {
        return new ApoioDoResumo(dialogos, avisos, new CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("t")), EscolhedorLocal.doSistema(() -> null), memoria,
            (d, b) -> { }, ZoneId::systemDefault, Instant::now, null);
    }

    private ServidorLab conectar(EstadoConexao esperado) {
        lab = new ServidorLab(CofreFalso.ID, cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), Agendador.doFundo(), Fundo::naEdt, Fundo.ioVirtual());
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == esperado);
        return lab;
    }

    private PaginaResumo pagina() {
        return Visual.naEdt(() -> new PaginaResumo(lab, () -> { }, aba -> { }, apoio()));
    }

    @Test
    void agenteNovoPedeSoAJanelaDaTelaTrocaDeAssinaturaELembraOPeriodoPorServidor() {
        agente.historico = true;
        conectar(EstadoConexao.CONECTADO);
        PaginaResumo p = pagina();
        Visual.naEdt(p::assinar);
        Espera.ate(PRAZO, "o 10m foi pedido e chegou", () -> agente.janelasPedidas.contains("10m")
            && Visual.naEdt(() -> p.blocoDeTendencias().ultimaVisao().situacao()) == BlocoDeTendencias.Situacao.OK);
        assertEquals(JanelaDeTempo.DEZ_MINUTOS, Visual.naEdt(p::janelaAssinada));
        assertEquals(6, Visual.naEdt(() -> p.blocoDeTendencias().graficos()).size());
        assertEquals(60, Visual.naEdt(() -> p.blocoDeTendencias().graficos()).get(2).quantidadeDePontos(), "o Disco tem dado: o agente novo grava");

        Visual.naEdt(() -> p.escolherJanelaParaTeste(JanelaDeTempo.UM_DIA));
        assertEquals(JanelaDeTempo.UM_DIA, Visual.naEdt(p::janelaAssinada), "trocou a assinatura para o dia");
        Espera.ate(PRAZO, "o dia chegou", () -> agente.janelasPedidas.contains("1d")
            && Visual.naEdt(() -> p.blocoDeTendencias().graficos().get(0).quantidadeDePontos()) == 288);
        assertEquals("1d", memoria.janelaDasTendencias(lab.id()), "o período foi lembrado para ESTE servidor");

        // soltou o 10m: depois da troca ninguém mais o pede
        int dezMinutos = (int) agente.janelasPedidas.stream().filter("10m"::equals).count();
        Espera.dormir(600);
        assertEquals(dezMinutos, agente.janelasPedidas.stream().filter("10m"::equals).count(), "a janela solta não é pedida de novo");

        // esconder a aba solta tudo
        Visual.naEdt(p::soltar);
        assertFalse(Visual.naEdt(p::historicoAssinado));
        int total = agente.janelasPedidas.size();
        Espera.dormir(600);
        assertEquals(total, agente.janelasPedidas.size(), "aba escondida: nenhuma chamada ao histórico");

        // uma página nova do mesmo servidor abre no período lembrado
        PaginaResumo outra = pagina();
        assertEquals(JanelaDeTempo.UM_DIA, outra.janelaEscolhida());
        assertTrue(Visual.naEdt(() -> outra.blocoDeTendencias().botaoDaJanela(JanelaDeTempo.UM_DIA).isSelected()));
    }

    @Test
    void agenteDeAntesNuncaChamaOEndpointNovoEUsaOsDezMinutosDeApiServidor() {
        agente.historico = false;
        conectar(EstadoConexao.CONECTADO);
        PaginaResumo p = pagina();
        Visual.naEdt(p::assinar);
        Espera.ate(PRAZO, "info chegou", () -> Visual.naEdt(() -> p.blocoDeTendencias().graficos().get(0).quantidadeDePontos()) > 0);
        assertEquals(List.of(), agente.janelasPedidas, "agente de antes: o endpoint novo nunca é chamado");
        assertFalse(Visual.naEdt(p::historicoAssinado));
        assertFalse(Visual.naEdt(() -> p.blocoDeTendencias().botaoDaJanela(JanelaDeTempo.UM_MES).isEnabled()));
        assertEquals(0, Visual.naEdt(() -> p.blocoDeTendencias().graficos()).get(2).quantidadeDePontos(), "sem Disco no agente de antes: 'sem dado ainda', sem erro");
        assertEquals("sem dado ainda", Visual.naEdt(() -> p.blocoDeTendencias().legendas()).get(2));
        assertTrue(Visual.naEdt(() -> p.blocoDeTendencias().aviso().getText()).contains("últimos 10 minutos"));
    }

    @Test
    void historicoComDefeitoMantemOQueTemEDizSemDerrubarAConexao() {
        agente.historico = true;
        conectar(EstadoConexao.CONECTADO);
        PaginaResumo p = pagina();
        Visual.naEdt(p::assinar);
        Espera.ate(PRAZO, "10m chegou", () -> Visual.naEdt(() -> p.blocoDeTendencias().ultimaVisao().situacao()) == BlocoDeTendencias.Situacao.OK);
        agente.historicoComDefeito = true;
        Visual.naEdt(() -> p.escolherJanelaParaTeste(JanelaDeTempo.UM_MES));
        Espera.ate(PRAZO, "falhou", () -> Visual.naEdt(p::historicoFalhou));
        assertEquals(BlocoDeTendencias.Situacao.FALHOU_SEM_DADO, Visual.naEdt(() -> p.blocoDeTendencias().ultimaVisao().situacao()), "o mês nunca chegou");
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        agente.historicoComDefeito = false;
        Visual.naEdt(() -> p.escolherJanelaParaTeste(JanelaDeTempo.DEZ_MINUTOS));
        Espera.ate(PRAZO, "voltou ao normal", () -> !Visual.naEdt(p::historicoFalhou));
    }

    @Test
    void oRegistroSaiDoSshAssinaSoComACaixaVisivelEPausaNaoLeNada() {
        conectar(EstadoConexao.CONECTADO);
        PaginaResumo p = pagina();
        PainelDoRegistro r = p.caixaDoRegistro().painel();
        Espera.dormir(500);
        assertEquals(List.of(), agente.comandosDeRegistro, "a caixa não está visível: nenhum comando");
        Visual.naEdt(() -> r.forcarVisivelParaTeste(true));
        Espera.ate(PRAZO, "linhas", () -> Visual.naEdt(() -> r.linhasQueAparecem().size()) == 4);
        assertTrue(Visual.naEdt(() -> r.linhasQueAparecem().get(0).mensagem()).startsWith("Failed password"));
        assertEquals(List.of("journalctl -n 200 --no-pager -o short-iso"), agente.comandosDeRegistro);
        assertTrue(Visual.naEdt(r::textoDoStatus).contains("journalctl"), Visual.naEdt(r::textoDoStatus));

        Visual.naEdt(() -> r.botaoPausar().doClick());
        int comandos = agente.comandosDeRegistro.size();
        Visual.naEdt(() -> r.botaoAtualizar().doClick()); // em pausa: lê UMA vez
        Espera.ate(PRAZO, "uma leitura avulsa", () -> agente.comandosDeRegistro.size() == comandos + 1);
        Espera.dormir(500);
        assertEquals(comandos + 1, agente.comandosDeRegistro.size(), "e não leu mais");
        assertFalse(Visual.naEdt(r::assinada), "depois da leitura avulsa soltou de novo");
    }

    @Test
    void semAgenteInstaladoOResumoSegueComRegistroPorSshEDizInstaleOAgente() {
        agente.fora = true;
        agente.diagnostico = "ausente";
        conectar(EstadoConexao.SSH_OK_SEM_AGENTE);
        Espera.ate(PRAZO, () -> "situação=" + lab.estado().situacaoDoAgente(), () -> lab.estado().situacaoDoAgente().oferecerInstalar());
        PaginaResumo p = pagina();
        Visual.naEdt(p::assinar);
        Espera.ate(PRAZO, "o corpo aparece sem a leitura do agente", () -> Visual.naEdt(p::mostraCorpo));
        assertEquals(BlocoDeTendencias.Situacao.SEM_AGENTE, Visual.naEdt(() -> p.blocoDeTendencias().ultimaVisao().situacao()));
        assertEquals("Instale o agente para ver a tendência.", Visual.naEdt(() -> p.blocoDeTendencias().aviso().getText()));
        assertFalse(Visual.naEdt(() -> p.blocoDeTendencias().botaoRelatorio().isEnabled()), "sem agente não há relatório");
        Visual.naEdt(() -> p.caixaDoRegistro().painel().forcarVisivelParaTeste(true));
        Espera.ate(PRAZO, "o registro vem pelo SSH mesmo sem o agente", () -> Visual.naEdt(() -> p.caixaDoRegistro().painel().linhasQueAparecem().size()) == 4);
        assertEquals(List.of(), agente.janelasPedidas);
        Visual.salvar(Visual.renderizar(p, 884, 640), "resumo-sem-agente-com-registro-claro");
    }
}
