package br.com.labdesktop.ui.galeria;

import br.com.labdesktop.ui.VerificaEdt;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** A imagem de galeria da tela "Servidores" (gerenciar servidores) com três cartões em estados diferentes: conectado, "Sem porta aberta" (AWS SSM) e "Protegido". */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaServidoresTest {

    @TempDir
    Path tmp;

    @Test
    void listaDeServidoresComTresEstados() throws Exception {
        try (CenaDeServidores c = new CenaDeServidores(tmp)) {
            c.abrirServidores();
            c.fotografar("servidores-lista");
        }
    }
}
