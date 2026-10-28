package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A gravação das preferências na saída do app: a última mudança não pode se perder se o app fechar logo depois dela. */
class PreferenciasAtuaisTest {

    @Test
    void descarregarGravaAUltimaMudancaSemEsperarAThreadDeFundo(@TempDir Path tmp) {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        PreferenciasAtuais p = new PreferenciasAtuais(c, Preferencias.padrao());
        p.atualizar(x -> x.comTema(Tema.CLARO));
        p.atualizar(x -> x.comTamanhoFonteTerminal(17));
        p.descarregar(); // é o que Aplicacao.encerrar faz antes de Fundo.encerrar()
        Preferencias lida = Preferencias.carregar(c);
        assertEquals(Tema.CLARO, lida.tema());
        assertEquals(17, lida.tamanhoFonteTerminal());
    }

    @Test
    void descarregarSemMudancaNaoFalhaENaoCriaOutroArquivo(@TempDir Path tmp) {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        PreferenciasAtuais p = new PreferenciasAtuais(c, Preferencias.padrao());
        p.descarregar();
        p.descarregar();
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c));
    }
}
