package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.AjustesDoApp;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Imagens da galeria do README da Ajuda dentro do app: o tópico "Git do projeto" e o tópico "Sem conexão" (a faixa de ritmo lento), na janela inteira, nos dois temas. A Ajuda é uma tela
 * dos Ajustes (uma aba da janela principal, não um diálogo), por isso a moldura é a de janela. Os textos são os da Ajuda de verdade; o servidor da barra lateral é o de mentira do teste.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaAjudaTest {

    private static final int LARGURA = 1100;
    private static final int ALTURA = 700;

    @TempDir
    Path pasta;

    private CenarioDeUi cenario;
    private br.com.labdesktop.servidor.GerenciadorServidores gerenciador;
    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("galeria");
    private PainelPrincipal painel;
    private PreferenciasAtuais preferencias;
    private TelaAjustes ajustes;

    @BeforeEach
    void montar() throws Exception {
        cenario = new CenarioDeUi(br.com.labdesktop.api.PedidoDeCodigo2fa.NUNCA, false);
        // o servidor da barra lateral se chama "servidor-demo" e o consumo do plano é calmo; o gerenciador nasce depois da edição e já lê o nome novo
        var cfg = cenario.cofre.servidor(br.com.labdesktop.teste.CofreFalso.ID).orElseThrow();
        cenario.cofre.alterarServidor(br.com.labdesktop.teste.CofreFalso.ID, c -> c.editar("servidor-demo", br.com.labdesktop.cofre.Transporte.DIRETO, cfg.host(), cfg.porta(), cfg.usuario(), cfg.portaAgente())
            .comPin(cenario.ssh.pin()));
        String limites = DadosDaGaleria.limites(br.com.labdesktop.teste.MocksDoFront.ler("limites.json"));
        cenario.agente.excecoes = p -> "GET".equals(p.metodo()) && "/api/limites".equals(p.caminho()) ? br.com.labdesktop.teste.AgenteFalso.Resposta.json(200, limites) : null;
        gerenciador = new br.com.labdesktop.servidor.GerenciadorServidores(cenario.cofre, br.com.labdesktop.api.PedidoDeCodigo2fa.NUNCA, br.com.labdesktop.ssh.ConfigSsh.rapida());
        var lab = gerenciador.servidor(br.com.labdesktop.teste.CofreFalso.ID).orElseThrow();
        lab.iniciar();
        br.com.labdesktop.Espera.ate(java.time.Duration.ofSeconds(20), () -> "estado=" + lab.estado(),
            () -> lab.estado().conexao() == br.com.labdesktop.ssh.EstadoConexao.CONECTADO && lab.estado().limites() != null);
        preferencias = new PreferenciasAtuais(CaminhosApp.comPasta(pasta), Preferencias.padrao());
        GerenciadorTema tema = new GerenciadorTema(Tema.CLARO, false, false);
        Visual.naEdt(tema::instalar);
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos),
            br.com.labdesktop.arquivos.ServicosDeArquivos.descartaveis(), preferencias::get));
        Visual.naEdt(() -> painel.instalarAjustes(() -> {
            ajustes = new TelaAjustes(new AjustesDoApp(tema, preferencias, painel, new CopiadorDeTexto(() -> area), avisos));
            return ajustes;
        }));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(painel::encerrar);
        preferencias.descarregar(); // termina a gravação em segundo plano antes de a pasta temporária ser apagada
        gerenciador.fechar().join();
        cenario.close();
        Visual.tema(Tema.CLARO);
    }

    private void abrirOTopico(String id) {
        Visual.naEdt(() -> {
            painel.mostrarAjustes(TelaAjustes.Secao.AJUDA);
            ajustes.abrirTopicoDeAjuda(id);
            painel.setSize(LARGURA, ALTURA);
        });
    }

    @Test
    void ajudaDoGitDoProjeto() {
        abrirOTopico("git-do-projeto");
        assertTrue(ImagemDeDialogo.textos(painel).contains("Como vejo os commits do projeto e crio o repositório Git?"));
        ImagemDeDialogo.gerarJanela(painel, "ajuda-git", LARGURA, ALTURA, "Como vejo os commits do projeto e crio o repositório Git?", "Veja os commits e o que cada um mudou sem sair do app, e crie o repositório do projeto.");
    }

    @Test
    void ajudaDoRitmoLentoSemConexao() {
        abrirOTopico("sem-conexao");
        ImagemDeDialogo.gerarJanela(painel, "ajuda-ritmo-lento", LARGURA, ALTURA, "O que faço quando aparece \"Sem conexão\"?", "O app perdeu a conexão com o servidor; ele tenta de novo sozinho e mostra a última leitura enquanto isso.");
    }
}
