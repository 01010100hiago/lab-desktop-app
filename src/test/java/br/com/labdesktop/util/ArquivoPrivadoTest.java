package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArquivoPrivadoTest {

    @Test
    void gravaComZeroSeisZeroZeroSubstituiOAntigoENaoDeixaTemporario(@TempDir Path tmp) throws Exception {
        Path alvo = tmp.resolve("estado.json");
        ArquivoPrivado.gravarAtomico(alvo, "um".getBytes());
        ArquivoPrivado.gravarAtomico(alvo, "dois".getBytes());
        assertArrayEquals("dois".getBytes(), Files.readAllBytes(alvo));
        // no Windows quem protege é a ACL (ProtecaoAcl): a conferência por sistema está em SistemaDoTeste.conferirPrivado
        br.com.labdesktop.teste.SistemaDoTeste.conferirPrivado("rw-------", Permissoes.comoTexto(alvo));
        assertFalse(Files.exists(tmp.resolve("estado.json.tmp")));
    }

    @Test
    void temporarioVelhoDeUmaQuedaNaoAtrapalha(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("estado.json.tmp"), "lixo de uma gravação interrompida");
        Path alvo = tmp.resolve("estado.json");
        ArquivoPrivado.gravarAtomico(alvo, "novo".getBytes());
        assertEquals("novo", Files.readString(alvo));
    }

    @Test
    void lerRecusaAcimaDoTetoSemAlocar(@TempDir Path tmp) throws Exception {
        Path alvo = tmp.resolve("grande");
        Files.write(alvo, new byte[100]);
        assertEquals(100, ArquivoPrivado.lerLimitado(alvo, 100).length);
        assertThrows(IOException.class, () -> ArquivoPrivado.lerLimitado(alvo, 99));
    }
}
