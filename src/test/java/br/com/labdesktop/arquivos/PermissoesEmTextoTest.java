package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PermissoesEmTextoTest {

    @Test
    void palavrasSimples() {
        assertEquals("Todos leem", PermissoesEmTexto.simples(0644));
        assertEquals("Todos leem", PermissoesEmTexto.simples(0755));
        assertEquals("Só o dono", PermissoesEmTexto.simples(0600));
        assertEquals("Dono e grupo", PermissoesEmTexto.simples(0640));
        assertEquals("Grupo altera", PermissoesEmTexto.simples(0664));
        assertEquals("Grupo altera", PermissoesEmTexto.simples(0660));
        assertEquals("Todos alteram", PermissoesEmTexto.simples(0666));
        assertEquals("Só leitura", PermissoesEmTexto.simples(0444));
        assertEquals("Ninguém", PermissoesEmTexto.simples(0));
        assertEquals("—", PermissoesEmTexto.simples(-1));
    }

    @Test
    void explicacaoPorExtensoParaADica() {
        assertEquals("Todos podem ler; só o dono pode alterar.", PermissoesEmTexto.explicacao(0644));
        assertEquals("O dono e o grupo podem ler; o dono e o grupo podem alterar.", PermissoesEmTexto.explicacao(0660));
        assertEquals("Só o dono pode ler; só o dono pode alterar.", PermissoesEmTexto.explicacao(0600));
        assertEquals("Todos podem ler; ninguém pode alterar.", PermissoesEmTexto.explicacao(0444));
        assertEquals("", PermissoesEmTexto.explicacao(-1));
    }

    @Test
    void textoTecnicoEOctal() {
        assertEquals("rwxr-xr-x", PermissoesEmTexto.tecnico(0755));
        assertEquals("rw-------", PermissoesEmTexto.tecnico(0600));
        assertEquals("755", PermissoesEmTexto.octal(0755));
        assertEquals("", PermissoesEmTexto.tecnico(-1));
    }
}
