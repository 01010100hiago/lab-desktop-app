package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.registro.ResultadoDoRegistro;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.tendencias.DadosDeTendencia;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.tendencias.Metrica;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Fundo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O histórico de métricas e o registro geral no {@link ServidorLab} de verdade: SSH embutido + agente falso + cofre falso. Só se o agente DIZ {@code recursos.historico}; só com tela
 * assinando; o registro sai do SSH com comandos fixos e funciona mesmo com o agente fora do ar.
 */
class HistoricoERegistroNoServidorLabTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final String NOME_DO_MAC = "mac-de-teste";

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreFalso cofre;
    private ServidorLab lab;

    @BeforeEach
    void montar() throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        TokenAgente token = TokenAgente.gerar();
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta()).comToken(token, NOME_DO_MAC);
        ssh.aceitar(cofre.par().getPublic());
        ssh.comandoRemoto(agente.comandosPorSsh());
        agente.tokens.put(token.hashSha256Hex(), NOME_DO_MAC);
    }

    @AfterEach
    void desmontar() throws Exception {
        if (lab != null) {
            lab.fechar().join();
        }
        agente.close();
        ssh.close();
    }

    private ServidorLab conectar() {
        lab = new ServidorLab(CofreFalso.ID, cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), Agendador.doFundo(), Runnable::run, Fundo.ioVirtual());
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        Espera.ate(PRAZO, () -> "versão=" + lab.estado().versao(), () -> lab.estado().versao() != null);
        return lab;
    }

    // ---------------------------------------------------------------------------------------------
    // O histórico
    // ---------------------------------------------------------------------------------------------

    @Test
    void agenteSemOHistoricoNaoEDeclaradoENadaChamaOEndpointNovo() {
        agente.historico = false;
        conectar();
        assertFalse(lab.estado().entendeHistorico(), "agente de antes: sem recursos.historico");
        // quem respeita a regra (a tela) não assina o tópico novo; se alguém o assinasse mesmo assim, o agente de antes responderia 404 e o app trata como erro normal
        List<ErroApi> erros = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.historico(JanelaDeTempo.UM_DIA), new OuvinteDeTopico<DadosDeTendencia>() {
            @Override
            public void aoAtualizar(DadosDeTendencia v) { }

            @Override
            public void aoFalhar(ErroApi e) {
                erros.add(e);
            }
        });
        Espera.ate(PRAZO, "o agente de antes diz 404 e o app registra a falha sem derrubar nada", () -> !erros.isEmpty());
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao(), "um 404 do endpoint novo não mexe na conexão");
    }

    @Test
    void agenteComHistoricoDeclaraAssinaUmaJanelaERecebeAGradeCompleta() {
        agente.historico = true;
        conectar();
        assertTrue(lab.estado().entendeHistorico());
        List<DadosDeTendencia> recebidos = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.historico(JanelaDeTempo.UM_DIA), recebidos::add);
        Espera.ate(PRAZO, "recebe o dia", () -> !recebidos.isEmpty());
        DadosDeTendencia d = recebidos.get(0);
        assertTrue(d.deLongoPrazo());
        assertEquals(JanelaDeTempo.UM_DIA, d.janela());
        assertEquals(288, d.amostras(Metrica.CPU).size());
        assertEquals(Duration.ofMinutes(5), d.passo());
        for (Metrica m : Metrica.values()) {
            assertTrue(d.temDado(m), m.nome());
        }
        assertEquals(List.of("1d"), agente.janelasPedidas);
    }

    @Test
    void semTelaAssinandoNinguemPedeOHistorico() {
        agente.historico = true;
        conectar();
        Espera.dormir(1_200);
        assertEquals(List.of(), agente.janelasPedidas, "sem tela visível, nenhuma chamada ao endpoint novo");
    }

    @Test
    void soltarAAssinaturaParaDePedirETrocarDeJanelaPedeSoAnova() {
        agente.historico = true;
        conectar();
        List<DadosDeTendencia> a = new CopyOnWriteArrayList<>();
        Inscricao primeira = lab.assinar(Topico.historico(JanelaDeTempo.UMA_HORA), a::add);
        Espera.ate(PRAZO, "1 hora", () -> !a.isEmpty());
        primeira.close();
        List<DadosDeTendencia> b = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.historico(JanelaDeTempo.UMA_SEMANA), b::add);
        Espera.ate(PRAZO, "1 semana", () -> !b.isEmpty());
        assertEquals(252, b.get(0).amostras(Metrica.CPU).size());
        assertEquals(List.of("1h", "7d"), agente.janelasPedidas);
        Espera.dormir(500);
        assertEquals(List.of("1h", "7d"), agente.janelasPedidas, "a janela solta não é pedida mais");
    }

    @Test
    void historicoDoAgenteComDefeitoViraFalhaDoTopicoENaoDerrubaAConexao() {
        agente.historico = true;
        agente.historicoComDefeito = true;
        conectar();
        List<ErroApi> erros = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.historico(JanelaDeTempo.UM_MES), new OuvinteDeTopico<DadosDeTendencia>() {
            @Override
            public void aoAtualizar(DadosDeTendencia v) { }

            @Override
            public void aoFalhar(ErroApi e) {
                erros.add(e);
            }
        });
        Espera.ate(PRAZO, "falha", () -> !erros.isEmpty());
        assertEquals(500, erros.get(0).status());
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
    }

    @Test
    void asCadenciasDoTopicoCrescemComAJanelaESemFocoVaiAUmSexto() {
        assertEquals(java.util.Optional.of(Duration.ofSeconds(10)), Topico.historico(JanelaDeTempo.DEZ_MINUTOS).intervalo(EstadoJanela.ATIVA, false));
        assertEquals(java.util.Optional.of(Duration.ofSeconds(60)), Topico.historico(JanelaDeTempo.DEZ_MINUTOS).intervalo(EstadoJanela.SEM_FOCO, false));
        assertEquals(java.util.Optional.of(Duration.ofMinutes(10)), Topico.historico(JanelaDeTempo.UM_MES).intervalo(EstadoJanela.ATIVA, false));
        assertEquals(java.util.Optional.empty(), Topico.historico(JanelaDeTempo.UM_DIA).intervalo(EstadoJanela.ESCONDIDA, false), "janela escondida: nada");
        assertEquals("historico:7d", Topico.historico(JanelaDeTempo.UMA_SEMANA).chave());
    }

    // ---------------------------------------------------------------------------------------------
    // O registro
    // ---------------------------------------------------------------------------------------------

    @Test
    void oRegistroSaiDoSshComUmComandoFixoMaisNovasEmCimaEFuncionaSemOAgente() {
        agente.fora = true;
        lab = new ServidorLab(CofreFalso.ID, cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), Agendador.doFundo(), Runnable::run, Fundo.ioVirtual());
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.SSH_OK_SEM_AGENTE);
        List<ResultadoDoRegistro> recebidos = new CopyOnWriteArrayList<>();
        lab.assinarRegistro(recebidos::add);
        Espera.ate(PRAZO, "registro", () -> !recebidos.isEmpty());
        ResultadoDoRegistro.Lido lido = assertInstanceOf(ResultadoDoRegistro.Lido.class, recebidos.get(0));
        assertEquals(4, lido.linhas().size());
        assertTrue(lido.linhas().get(0).mensagem().startsWith("Failed password"), "a mais nova em cima");
        assertEquals("do diário do sistema (journalctl)", lido.fonte());
        assertEquals(List.of("journalctl -n 200 --no-pager -o short-iso"), agente.comandosDeRegistro, "um comando fixo, sem sudo");
    }

    @Test
    void semPermissaoParaORegistroDizOComandoExatoComOUsuarioDoServidor() {
        agente.semPermissaoParaORegistro = true;
        conectar();
        List<ResultadoDoRegistro> recebidos = new CopyOnWriteArrayList<>();
        lab.assinarRegistro(recebidos::add);
        Espera.ate(PRAZO, "registro", () -> !recebidos.isEmpty());
        ResultadoDoRegistro.SemPermissao p = assertInstanceOf(ResultadoDoRegistro.SemPermissao.class, recebidos.get(0));
        assertEquals("sudo usermod -aG systemd-journal labuser", p.comandoParaCopiar());
        assertTrue(agente.comandosDeRegistro.stream().noneMatch(c -> c.contains("sudo")), "nunca elevou privilégio: " + agente.comandosDeRegistro);
    }

    @Test
    void semAssinaturaOuComAJanelaEscondidaNinguemLeOSistema() {
        conectar();
        Espera.dormir(1_000);
        assertEquals(List.of(), agente.comandosDeRegistro, "sem a caixa visível, nenhum comando de registro");
        lab.definirJanela(EstadoJanela.ESCONDIDA);
        List<ResultadoDoRegistro> recebidos = new CopyOnWriteArrayList<>();
        lab.assinarRegistro(recebidos::add);
        Espera.dormir(1_000);
        assertEquals(List.of(), agente.comandosDeRegistro, "janela escondida: nada");
        lab.definirJanela(EstadoJanela.ATIVA);
        Espera.ate(PRAZO, "volta a ler", () -> !recebidos.isEmpty());
    }

    @Test
    void atualizarAgoraLeDeNovoSemEsperarOsQuinzeSegundosEOTopicoNaoRodaSemFoco() {
        conectar();
        List<ResultadoDoRegistro> recebidos = new CopyOnWriteArrayList<>();
        lab.assinarRegistro(recebidos::add);
        Espera.ate(PRAZO, "primeira leitura", () -> agente.comandosDeRegistro.size() == 1);
        agente.saidaDoJournal = agente.saidaDoJournal + "2026-10-28T12:10:00-0300 lab app[1]: nova linha\n";
        lab.atualizarRegistroAgora();
        Espera.ate(PRAZO, "segunda leitura", () -> agente.comandosDeRegistro.size() == 2);
        Espera.ate(PRAZO, () -> "recebidos=" + recebidos.size(), () -> recebidos.size() == 2);
        assertEquals(5, ((ResultadoDoRegistro.Lido) recebidos.get(1)).linhas().size());
        assertEquals(java.util.Optional.of(Duration.ofSeconds(15)), lab.topicoDoRegistroParaTeste().intervalo(EstadoJanela.ATIVA, false));
        assertEquals(java.util.Optional.empty(), lab.topicoDoRegistroParaTeste().intervalo(EstadoJanela.SEM_FOCO, false), "sem foco PARA");
        assertEquals(java.util.Optional.of(Duration.ofSeconds(15)), lab.topicoDoRegistroParaTeste().intervalo(EstadoJanela.SEM_FOCO, true), "a menos que o usuário peça cadência cheia");
        assertNotNull(recebidos);
    }
}
