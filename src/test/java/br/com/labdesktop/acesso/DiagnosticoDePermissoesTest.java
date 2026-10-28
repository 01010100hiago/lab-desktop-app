package br.com.labdesktop.acesso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticoDePermissoesTest {

    @Test
    void pastaPessoalComEscritaDeGrupoOuOutrosEApontada() {
        List<String> p = DiagnosticoDePermissoes.problemas("drwxrwxr-x 5 ana ana 4096 out 28 10:00 /home/ana\ndrwx------ 2 ana ana 4096 out 28 10:00 /home/ana/.ssh\n"
            + "-rw------- 1 ana ana 100 out 28 10:00 /home/ana/.ssh/authorized_keys\n");
        assertEquals(1, p.size());
        assertTrue(p.get(0).startsWith("a pasta pessoal do usuário (/home/ana) aceita escrita por grupo ou outros (drwxrwxr-x)"), p.get(0));
        assertTrue(p.get(0).contains("StrictModes") && p.get(0).contains("O app não altera isso"), p.get(0));
    }

    @Test
    void tudoCertoOuSaidaIncompreensivelNaoApontaNada() {
        assertEquals(List.of(), DiagnosticoDePermissoes.problemas("drwxr-xr-x 5 ana ana 4096 out 28 10:00 /home/ana\ndrwx------ 2 ana ana 4096 out 28 /home/ana/.ssh\n"));
        assertEquals(List.of(), DiagnosticoDePermissoes.problemas("ls: cannot access\nBem-vindo!"));
        assertEquals(List.of(), DiagnosticoDePermissoes.problemas(null));
    }

    @Test
    void authorizedKeysEPastaSshTambemEntram() {
        List<String> p = DiagnosticoDePermissoes.problemas("drwxr-xr-x 5 ana ana 4096 out 28 10:00 /home/ana\ndrwxrwx--- 2 ana ana 4096 out 28 10:00 /home/ana/.ssh\n"
            + "-rw-rw-rw- 1 ana ana 100 out 28 10:00 /home/ana/.ssh/authorized_keys\n");
        assertEquals(2, p.size());
        assertTrue(p.get(0).startsWith("a pasta .ssh"));
        assertTrue(p.get(1).startsWith("o arquivo authorized_keys"));
    }
}
