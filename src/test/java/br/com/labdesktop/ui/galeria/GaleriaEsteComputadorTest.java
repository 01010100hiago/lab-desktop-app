package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.local.ConfigLocal;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.SondaFalsa;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoEscolherServidor;
import br.com.labdesktop.ui.dialogos.DialogoPrepararComputador;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * As imagens de galeria do "Este computador" (o laboratório local, sem SSH): a janela inteira do servidor com o aviso "Preparar este computador" (o Docker está de pé e o
 * laboratório ainda não foi criado) e o diálogo "Preparar este computador" na página que só CONFERE e mostra o que vai acontecer. O computador é o simulado do repositório (nenhum
 * Docker de verdade roda), com a pasta de usuário inventada ({@code /Users/ana}), e o agente é o de mentira.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaEsteComputadorTest {

    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private AgenteDoLab agente;
    private ComputadorSimulado pc;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private PainelPrincipal painel;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        agente = new AgenteDoLab();
        agente.fora = true;
        pc = new ComputadorSimulado();
        pc.execDoAgente = agente.comandosPorSsh();
        SondaFalsa sonda = new SondaFalsa();
        sonda.programas.add("brew");
        // os fatos do computador da imagem são de exemplo (nada da máquina que roda o teste)
        sonda.sistema = "macOS 15.1";
        sonda.memoriaTotal = 8L * 1024 * 1024 * 1024;
        sonda.bytesLivres = 96L * 1024 * 1024 * 1024;
        pc.versaoDoDocker = "27.3.1";
        PlataformaLocal plataforma = new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", Map.of());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), "uma-senha-mestra-de-teste".toCharArray(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), new ServicosLocais(pc, plataforma, sonda, ConfigLocal.rapida()));
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, assistente, avisos, dialogos, new NavegadorGravado(avisos), ServicosDeArquivos.descartaveis()));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(() -> painel.encerrar());
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        Visual.tema(Tema.CLARO);
    }

    /** O clique em "Usar este computador": cadastra o servidor local (sem chave, sem SSH) e abre o "Preparar este computador". */
    private ServidorLab usarEsteComputador() {
        Visual.naEdt(() -> painel.acoes().adicionarServidor());
        DialogoEscolherServidor escolha = dialogos.ultimoComo(DialogoEscolherServidor.class);
        assertEquals("Usar este computador", Visual.naEdt(() -> escolha.botaoEsteComputador().getText()));
        Visual.naEdt(() -> escolha.botaoEsteComputador().doClick());
        Espera.ate(PRAZO, "o diálogo de preparar abriu", () -> dialogos.ultimo() instanceof DialogoPrepararComputador);
        Espera.ate(PRAZO, "o servidor entrou na lista", () -> gerenciador.servidores().size() == 1);
        return gerenciador.servidores().get(0);
    }

    @Test
    void janelaDoServidorLocalComOAvisoPrepararEsteComputador() {
        ServidorLab lab = usarEsteComputador();
        assertTrue(lab.ehLocal());
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.LOCAL_NAO_CRIADO);
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        TelaServidor ts = Visual.naEdt(() -> painel.navegacao().telaServidorDe(lab.id()));
        Visual.naEdt(() -> ts.pagina(Aba.RESUMO).assinar());
        Espera.ate(PRAZO, () -> "o aviso: " + CenaDoServidor.textosDe(painel).replace('\n', '|'), () -> {
            String t = CenaDoServidor.textosDe(painel);
            return t.contains("Preparar este computador") && t.contains("O laboratório local ainda não foi criado");
        });
        String t = CenaDoServidor.textosDe(painel);
        assertFalse(t.toLowerCase(java.util.Locale.ROOT).contains("ssh"), "nada de SSH na fala do local: " + t);
        CenaDoServidor.confereDadosInventados(t);
        assertFalse(t.contains("127.0.0.1"), "o endereço do agente local não entra nesta imagem: " + t);
        Visual.naEdt(() -> painel.setSize(1440, 860));
        assertTrue(Visual.naEdt(() -> SemTransbordo.achados(painel)).isEmpty(), "a tela transborda");
        if (CenaDoServidor.deveGravar("este-computador")) {
            Visual.galeriaNosDoisTemas(painel, 1440, 860, "este-computador", "Lab Desktop", MolduraDeJanela.Tipo.JANELA);
        }
    }

    @Test
    void dialogoPrepararEsteComputador() {
        usarEsteComputador();
        DialogoPrepararComputador d = dialogos.ultimoComo(DialogoPrepararComputador.class);
        assertEquals("Preparar este computador", d.titulo());
        Visual.naEdt(d::iniciarConferencia);
        Espera.ate(PRAZO, () -> "página=" + Visual.naEdt(d::paginaAtual), () -> "resultado".equals(Visual.naEdt(d::paginaAtual)));
        assertTrue(pc.escritas().isEmpty(), "só leituras até o clique: " + pc.escritas());
        String pasta = Visual.naEdt(() -> d.campoDaPasta().getText());
        assertEquals("/Users/ana/LabLocal/projects", pasta); // a pasta de usuário é a inventada (ana), nunca a do computador de teste
        assertFalse(Visual.naEdt(() -> d.botaoCriar().getText()).isEmpty());
        CenaDoServidor.fotografarDialogo(d, "preparar-este-computador");
    }
}
