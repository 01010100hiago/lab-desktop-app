package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.teste.ShellDeTeste;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarServidor;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pergunta final do "Adicionar servidor" (manter ou desligar o login por senha), na galeria do README. Para chegar nela o teste percorre o caminho todo pelos controles do diálogo, contra o
 * SSH embutido (com a senha de mentira {@code exemplo-nao-e-uma-senha}), o {@code sh} de verdade instalando a chave numa pasta temporária e o agente falso. Nada é do servidor de ninguém.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaAdicionarServidorPorSenhaTest {

    private static final char[] SENHA_MESTRA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(40);
    private static final String SENHA_DO_SERVIDOR = "exemplo-nao-e-uma-senha";

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private ShellDeTeste shell;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("galeria");
    private final List<ServidorLab> concluidos = new ArrayList<>();
    private final List<ServidorLab> pedidosParaDesligarASenha = new ArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        assumeTrue(ShellDeTeste.temShell(), "precisa de /bin/sh");
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        ssh.aceitarSenha("labuser", SENHA_DO_SERVIDOR);
        agente = new AgenteDoLab();
        agente.perfil = "monitor"; // o agente que o app instala: sem perfil o app marcaria o servidor como protegido
        shell = new ShellDeTeste(Files.createDirectories(tmp.resolve("casa-do-servidor")), ssh).outrosComandos(agente.comandosPorSsh());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA_MESTRA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        if (gerenciador != null) {
            gerenciador.fechar().join();
            cofre.close();
            agente.close();
            ssh.close();
        }
        Visual.tema(Tema.CLARO);
    }

    private DialogoAdicionarServidor assistenteNaTela() {
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add, (servidor, endereco, resultado, aoDecidir) -> aoDecidir.accept(true),
            janela -> Optional.empty(), new CopiadorDeTexto(() -> area), null, (lab, depois) -> {
                pedidosParaDesligarASenha.add(lab);
                depois.run();
            }));
        Visual.naEdt(() -> {
            d.campoDoNome().setText("servidor-demo");
            d.campoDoHost().setText("127.0.0.1"); // o SSH embutido do teste; a imagem mostra o endereço de exemplo
            d.campoDaPorta().setText(String.valueOf(ssh.porta()));
            d.campoDoUsuario().setText("labuser");
            d.campoDaPortaDoAgente().setText(String.valueOf(agente.porta()));
        });
        return d;
    }

    private static void esperarPagina(DialogoAdicionarServidor d, String pagina) {
        Espera.ate(PRAZO, () -> "página " + pagina + " (está em " + Visual.naEdt(d::paginaAtual) + ", erro='" + Visual.naEdt(d::textoDoErro) + "')",
            () -> pagina.equals(Visual.naEdt(d::paginaAtual)));
    }

    @Test
    void aPerguntaFinalSobreManterOuDesligarOLoginPorSenha() {
        DialogoAdicionarServidor d = assistenteNaTela();
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        Espera.ate(PRAZO, () -> "passo da chave (erro='" + Visual.naEdt(d::textoDoErro) + "')", () -> "chave".equals(Visual.naEdt(d::paginaAtual)) && Visual.naEdt(() -> d.chaveEscolhida() != null));
        Visual.naEdt(() -> d.campoDaSenhaDoServidor().setText(SENHA_DO_SERVIDOR));
        Visual.naEdt(() -> d.botaoEntrarComSenha().doClick());
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, () -> "pronto (msg=" + Visual.naEdt(d::mensagemDaConexao) + ")", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        Visual.naEdt(() -> d.botaoConcluir().doClick());
        esperarPagina(d, "senha");
        assertEquals("Manter a senha", Visual.naEdt(() -> d.botaoManterASenha().getText()));
        assertEquals(List.of(), pedidosParaDesligarASenha, "perguntar não desliga nada");
        assertTrue(ssh.comandos.stream().noneMatch(c -> c.contains("sshd")), "nenhum comando do sshd rodou: " + ssh.comandos);
        ImagemDeDialogo.gerar(d, "servidor-adicionar-pergunta-agente", "Manter a senha", "É mais seguro: ninguém consegue adivinhar a senha. Você continua entrando pelo app, que usa a chave.");
    }
}
