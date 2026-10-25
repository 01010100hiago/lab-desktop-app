package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.DetectorDeTexto.Texto;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DetectorDeTextoTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void textoUtf8ComAcentoEhTexto() {
        Texto t = DetectorDeTexto.detectar(b("olá, mundo\nsegunda linha\n"));
        assertNotNull(t);
        assertEquals("olá, mundo\nsegunda linha\n", t.texto());
        assertEquals("\n", t.quebraDeLinha());
        assertFalse(t.quebrasMistas());
    }

    @Test
    void arquivoVazioEhTexto() {
        Texto t = DetectorDeTexto.detectar(new byte[0]);
        assertNotNull(t);
        assertEquals("", t.texto());
    }

    @Test
    void byteNuloNosPrimeiros8KbEBinario() {
        assertNull(DetectorDeTexto.detectar(new byte[] {'a', 0, 'b'}));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
        assertNull(DetectorDeTexto.detectar(png));
    }

    @Test
    void byteNuloDepoisDos8KbTambemNaoEhTexto() {
        byte[] g = new byte[9000];
        java.util.Arrays.fill(g, (byte) 'a');
        g[8500] = 0;
        assertNull(DetectorDeTexto.detectar(g));
    }

    @Test
    void naoEhUtf8EhBinario() {
        assertNull(DetectorDeTexto.detectar(new byte[] {'c', 'a', 'f', (byte) 0xE9})); // "café" em Latin-1
        assertNull(DetectorDeTexto.detectar(new byte[] {(byte) 0xC3})); // UTF-8 cortado no meio
    }

    @Test
    void quebraDeLinhaDoWindowsEMantidaNaVolta() {
        byte[] original = b("a\r\nb\r\nc");
        Texto t = DetectorDeTexto.detectar(original);
        assertEquals("a\nb\nc", t.texto());
        assertEquals("\r\n", t.quebraDeLinha());
        assertFalse(t.quebrasMistas());
        assertArrayEquals(original, DetectorDeTexto.paraDisco(t.texto(), t.quebraDeLinha()), "ida e volta sem mudar um byte");
    }

    @Test
    void quebrasMistasSaoAvisadasEUnificadas() {
        Texto t = DetectorDeTexto.detectar(b("a\r\nb\nc\r\nd"));
        assertTrue(t.quebrasMistas());
        assertEquals("\r\n", t.quebraDeLinha(), "a mais comum");
        assertEquals("a\nb\nc\nd", t.texto());
        Texto cr = DetectorDeTexto.detectar(b("a\rb"));
        assertTrue(cr.quebrasMistas());
        assertEquals("a\nb", cr.texto());
    }

    @Test
    void ultimaQuebraEBomNaoSeMexem() {
        byte[] original = b("﻿linha\n");
        Texto t = DetectorDeTexto.detectar(original);
        assertArrayEquals(original, DetectorDeTexto.paraDisco(t.texto(), t.quebraDeLinha()));
        byte[] semFinal = b("sem quebra no fim");
        assertArrayEquals(semFinal, DetectorDeTexto.paraDisco(DetectorDeTexto.detectar(semFinal).texto(), "\n"));
    }
}
