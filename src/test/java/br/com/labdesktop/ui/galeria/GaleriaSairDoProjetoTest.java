package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.AjustesDoApp;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * As imagens da galeria da pergunta "Sair de <projeto>?" com a caixa "Não perguntar de novo" (desmarcada e marcada) e da opção que desfaz isso em Ajustes, Terminal. A pergunta é
 * o diálogo de verdade, com o mesmo texto que a tela do servidor usa; o nome do projeto é inventado.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaSairDoProjetoTest {

    private static final String TEXTO = "Este projeto tem um terminal aberto. Se sair da página dele agora, o terminal fecha e o que estiver rodando nele para. A conversa do Claude continua no servidor.";
    private static final String DICA = "Para voltar a ser perguntado: Ajustes, Terminal.";

    @TempDir
    Path tmp;

    private final List<DialogoConfirmar.Decisao> decisoes = new ArrayList<>();

    private DialogoConfirmar pergunta() {
        Visual.tema(Preferencias.Tema.CLARO);
        return Visual.naEdt(() -> DialogoConfirmar.comNaoPerguntarDeNovo("Sair de loja-web?", "alerta", TEXTO, "Sair do projeto", true, "Não perguntar de novo", DICA, decisoes::add));
    }

    @Test
    void perguntaDesmarcada() {
        DialogoConfirmar d = pergunta();
        assertFalse(Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().isSelected()));
        ImagemDeDialogo.gerar(d, "sair-do-projeto", "Sair de loja-web?", "Não perguntar de novo", DICA, "Sair do projeto", "Cancelar");
        assertTrue(decisoes.isEmpty(), "gerar a imagem não decide nada");
    }

    @Test
    void perguntaMarcada() {
        DialogoConfirmar d = pergunta();
        Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().doClick(0));
        assertTrue(Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().isSelected()));
        ImagemDeDialogo.gerar(d, "sair-do-projeto-marcada", "Sair de loja-web?", "Não perguntar de novo", DICA, "Sair do projeto", "Cancelar");
        assertTrue(Visual.naEdt(() -> d.caixaNaoPerguntarDeNovo().isSelected()), "a marca continua nos dois temas");
        assertTrue(decisoes.isEmpty(), "gerar a imagem não decide nada");
    }

    @Test
    void ajustesTerminalComAOpcao() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            GerenciadorTema tema = new GerenciadorTema(Preferencias.padrao().tema(), false, false).comConsultaDoSistema(() -> false);
            Visual.naEdt(tema::instalar);
            PreferenciasAtuais preferencias = new PreferenciasAtuais(CaminhosApp.comPasta(tmp.resolve("app")), Preferencias.padrao());
            try {
                AjustesDoApp controle = new AjustesDoApp(tema, preferencias, c.painel, new CopiadorDeTexto(() -> new Clipboard("teste")), c.avisos);
                Visual.naEdt(() -> c.painel.instalarAjustes(() -> new TelaAjustes(controle)));
                Visual.naEdt(() -> c.painel.mostrarAjustes(Secao.TERMINAL));
                Visual.rolarParaOTopo(c.painel);
                String t = c.textos();
                assertTrue(t.contains("Ao sair de um projeto") && t.contains("Perguntar antes de sair de um projeto com terminal aberto"), t);
                c.fotografar("ajustes-terminal");
            } finally {
                preferencias.descarregar(); // termina a gravação em segundo plano antes de a pasta temporária ser apagada
            }
        }
    }
}
