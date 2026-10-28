package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.local.ConfigLocal;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.AmbienteDeProcessos;
import br.com.labdesktop.processos.AvaliadorDeProcessos;
import br.com.labdesktop.processos.LeitorDeProcessosLocais;
import br.com.labdesktop.processos.Oferta;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.processos.ProcessosDoSistema;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.SondaFalsa;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A aba Processos de "Este computador": a lista vem do PRÓPRIO sistema (no Mac e no Windows o agente só enxerga a máquina virtual do Docker, e o número de lá é de outro processo aqui), é
 * lida a cada poucos segundos só enquanto a aba está à vista e não depende do agente estar de pé.
 */
@ExtendWith(VerificaEdt.class)
class ProcessosDeEsteComputadorTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(20);

    @TempDir
    Path tmp;

    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private ServidorLab lab;
    private final List<String> abertos = new ArrayList<>();
    private int leituras;

    /** O sistema do computador de mentira: dois processos do usuário e um do sistema. */
    private final ProcessosDoSistema sistema = new ProcessosDoSistema() {
        @Override
        public Optional<Info> ler(long pid) {
            return todos().stream().filter(i -> i.pid() == pid).findFirst();
        }

        @Override
        public List<Info> todos() {
            leituras++;
            return List.of(new Info(5000, "/Applications/Safari.app/Contents/MacOS/Safari", "ana", Instant.parse("2026-10-28T10:00:00Z"), Duration.ofSeconds(100)),
                new Info(4000, "/usr/bin/java", "ana", Instant.parse("2026-10-28T09:00:00Z"), Duration.ofSeconds(50)),
                new Info(300, "/System/Library/CoreServices/WindowServer", "_windowserver", Instant.parse("2026-10-27T09:00:00Z"), Duration.ofSeconds(500)));
        }

        @Override
        public boolean pedirParaFechar(long pid) {
            return false;
        }

        @Override
        public boolean matar(long pid) {
            return false;
        }

        @Override
        public boolean estaVivo(long pid) {
            return true;
        }

        @Override
        public Set<Integer> pidsDoApp() {
            return Set.of(4000);
        }
    };

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        ComputadorSimulado pc = new ComputadorSimulado();
        PlataformaLocal plataforma = new PlataformaLocal(SistemaOperacional.MAC, tmp.toString(), Map.of());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), new ServicosLocais(pc, plataforma, new SondaFalsa(), ConfigLocal.rapida()));
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        lab = assistente.cadastrarLocal("Este computador", 7070);
        lab.definirLeitorDeProcessosLocais(new LeitorDeProcessosLocais(sistema, Instant::now));
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        Visual.tema(Tema.CLARO);
    }

    private final AcoesDeProcessos acoes = new AcoesDeProcessos() {
        @Override
        public boolean liberadas() {
            return true;
        }

        @Override
        public Oferta avaliar(ProcessoAlvo alvo) {
            return AvaliadorDeProcessos.avaliar(alvo, new AmbienteDeProcessos(AmbienteDeProcessos.Tipo.COMPUTADOR_MAC_OU_LINUX, "ana", Set.of(), Set.of(4000)));
        }

        @Override
        public void abrir(ProcessoAlvo alvo, String origem, Acao acao, Runnable depois) {
            abertos.add(acao + ":" + alvo.pid() + ":" + origem);
        }
    };

    @Test
    void alistaVemDoSistemaSemOAgenteEMostraOsBotoesComAProtecaoDoAppEDoSistema() {
        PaginaProcessos p = Visual.naEdt(() -> new PaginaProcessos(lab, () -> { }, acoes));
        assertTrue(Visual.naEdt(p::local));
        assertEquals(Set.of(), Visual.naEdt(p::topicos), "o agente não é consultado: a lista é do sistema");
        Visual.naEdt(() -> p.aoMudarEstado(estadoSemAgente()));
        assertTrue(Visual.naEdt(p::mostraCorpo), "mostra a lista mesmo com o agente fora");
        assertFalse(Visual.naEdt(() -> p.faixaDeDados().isVisible()), "o aviso do agente não diz nada sobre esta lista");
        Visual.naEdt(p::lerLocalAgora);
        Espera.ate(PRAZO, "lista", () -> Visual.naEdt(() -> p.bloco().linhas()).size() == 3);
        List<List<String>> linhas = Visual.naEdt(() -> p.bloco().linhas());
        assertEquals(List.of("WindowServer", "Safari", "java"), linhas.stream().map(l -> l.get(0)).toList(), "o nome sem a pasta; o que gastou mais processador primeiro");
        assertEquals("este computador", linhas.get(0).get(4));
        assertEquals(List.of("Protegido", "Encerrar", "Protegido"), Visual.naEdt(() -> p.bloco().botoes().stream().map(b -> b.getText()).toList()));
        assertTrue(Visual.naEdt(() -> p.bloco().botoes().get(0).getToolTipText()).contains("processo do sistema"));
        assertTrue(Visual.naEdt(() -> p.bloco().botoes().get(2).getToolTipText()).contains("próprio app"));
        assertEquals("Processos deste computador", Visual.naEdt(() -> p.bloco().getName()).replace("bloco-", ""));
        Visual.naEdt(() -> p.bloco().botoes().get(1).doClick(0));
        assertEquals(List.of("ENCERRAR:5000:este computador"), abertos);
    }

    @Test
    void aLeituraSoRodaComAAbaAVistaEUmaPorVezDeNovoDepoisDeAgir() {
        PaginaProcessos p = Visual.naEdt(() -> new PaginaProcessos(lab, () -> { }, acoes));
        assertEquals(0, leituras, "montar a aba não lê nada");
        Visual.naEdt(p::lerLocalAgora);
        Espera.ate(PRAZO, "uma leitura", () -> leituras == 1 && Visual.naEdt(() -> p.bloco().linhas()).size() == 3);
        // o que roda ao terminar uma ação (o "depois" que a aba entrega ao diálogo): a aba lê de novo na hora
        Visual.naEdt(() -> p.bloco().botoes().get(1).doClick(0));
        Visual.naEdt(() -> p.lerLocalAgora());
        Espera.ate(PRAZO, "outra leitura", () -> leituras == 2);
    }

    private static br.com.labdesktop.servidor.EstadoServidor estadoSemAgente() {
        return TelaProjetoTest.estado(EstadoConexao.SSH_OK_SEM_AGENTE);
    }
}
