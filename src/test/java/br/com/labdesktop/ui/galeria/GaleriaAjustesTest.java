package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.AjustesDoApp;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.ajustes.ControleDeAjustes;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.InformacoesDoApp;
import br.com.labdesktop.util.Preferencias;
import java.awt.datatransfer.Clipboard;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A imagem de galeria de Ajustes &gt; Sobre na janela inteira: versão do app, do agente empacotado, do Java, o sistema e as licenças. A única coisa do computador de teste que a tela
 * mostraria é a pasta de dados (a pasta temporária do teste): ela é trocada pela pasta de dados de um Mac comum, escrita com {@code ~}, como o app a mostra.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaAjustesTest {

    static final String PASTA_DE_DADOS = "~/Library/Application Support/LabDesktop";
    static final String VERSAO_DO_APP = "0.1.0";
    /** O Java, o sistema e a arquitetura da imagem são de exemplo (a imagem não mostra os do computador de teste). */
    static final String JAVA_DE_EXEMPLO = "21.0.5 (Temurin)";
    static final String SISTEMA_DE_EXEMPLO = "macOS 15.1";
    static final String ARQUITETURA_DE_EXEMPLO = "aarch64";
    static final String AGENTE_VERSAO = "0.1.0-8a948b9";
    static final String AGENTE_COMMIT = "8a948b9302d0092c2b8dc42b2701cfebe95b4f86";

    @TempDir
    Path tmp;

    @Test
    void sobre() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            GerenciadorTema tema = new GerenciadorTema(Preferencias.padrao().tema(), false, false).comConsultaDoSistema(() -> false);
            Visual.naEdt(tema::instalar);
            PreferenciasAtuais preferencias = new PreferenciasAtuais(CaminhosApp.comPasta(tmp.resolve("app")), Preferencias.padrao());
            try {
                ControleDeAjustes real = new AjustesDoApp(tema, preferencias, c.painel, new CopiadorDeTexto(() -> new Clipboard("teste")), c.avisos);
                InformacoesDoApp lida = real.informacoes();
                // o teste roda sem o jar do app (versão "dev", agente "não incluído") e com a pasta de dados temporária: a imagem mostra um app instalado, com a versão do
                // build.gradle.kts, um agente empacotado de exemplo (versão e commit inventados) e a pasta de dados de um Mac comum
                InformacoesDoApp informacoes = new InformacoesDoApp(lida.nome(), VERSAO_DO_APP, AGENTE_VERSAO, AGENTE_COMMIT, JAVA_DE_EXEMPLO, SISTEMA_DE_EXEMPLO,
                    ARQUITETURA_DE_EXEMPLO, PASTA_DE_DADOS);
                ControleDeAjustes controle = (ControleDeAjustes) Proxy.newProxyInstance(ControleDeAjustes.class.getClassLoader(), new Class<?>[] {ControleDeAjustes.class},
                    (proxy, metodo, args) -> {
                        if (metodo.getName().equals("informacoes")) {
                            return informacoes;
                        }
                        try {
                            return metodo.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
                Visual.naEdt(() -> c.painel.instalarAjustes(() -> new TelaAjustes(controle)));
                Visual.naEdt(() -> c.painel.mostrarAjustes(Secao.SOBRE));
                Visual.rolarParaOTopo(c.painel);
                String t = c.textos();
                assertTrue(t.contains("Versão do app") && t.contains("Java") && t.contains("Sistema") && t.contains("Pasta de dados"), t);
                assertTrue(t.contains(PASTA_DE_DADOS) && t.contains("Copiar informações") && t.contains("Software livre e gratuito"), t);
                assertTrue(t.contains(AGENTE_VERSAO) && t.contains(VERSAO_DO_APP), t);
                assertFalse(t.contains(tmp.toString()), "a pasta temporária do teste não pode aparecer: " + t);
                c.fotografar("ajustes-sobre", 960);
            } finally {
                preferencias.descarregar(); // termina a gravação em segundo plano antes de a pasta temporária ser apagada
            }
        }
    }
}
