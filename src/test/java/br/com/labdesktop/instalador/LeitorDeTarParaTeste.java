package br.com.labdesktop.instalador;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Lê um tar.gz de arquivos comuns (o que o {@link MontadorDoPacote} gera), conferindo a soma de controle de cada cabeçalho. */
final class LeitorDeTarParaTeste {

    private LeitorDeTarParaTeste() { }

    record Entrada(String nome, int modo, int uid, int gid, long data, byte[] conteudo) { }

    static Map<String, Entrada> ler(byte[] tarGz) throws IOException {
        byte[] tar = new GZIPInputStream(new ByteArrayInputStream(tarGz)).readAllBytes();
        Map<String, Entrada> r = new LinkedHashMap<>();
        int pos = 0;
        while (pos + 512 <= tar.length) {
            byte[] c = Arrays.copyOfRange(tar, pos, pos + 512);
            if (todoZero(c)) {
                break;
            }
            String nome = texto(c, 0, 100);
            int modo = (int) octal(c, 100, 8);
            int uid = (int) octal(c, 108, 8);
            int gid = (int) octal(c, 116, 8);
            long tamanho = octal(c, 124, 12);
            long data = octal(c, 136, 12);
            long soma = octal(c, 148, 8);
            byte[] copia = c.clone();
            for (int i = 148; i < 156; i++) {
                copia[i] = ' ';
            }
            long calculada = 0;
            for (byte b : copia) {
                calculada += b & 0xFF;
            }
            if (calculada != soma) {
                throw new IOException("soma de controle errada em " + nome);
            }
            if (c[156] != '0') {
                throw new IOException("só arquivo comum: " + nome);
            }
            if (!"ustar".equals(texto(c, 257, 5))) {
                throw new IOException("sem a marca ustar em " + nome);
            }
            byte[] conteudo = Arrays.copyOfRange(tar, pos + 512, pos + 512 + (int) tamanho);
            r.put(nome, new Entrada(nome, modo, uid, gid, data, conteudo));
            pos += 512 + (int) (((tamanho + 511) / 512) * 512);
        }
        return r;
    }

    private static boolean todoZero(byte[] c) {
        for (byte b : c) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String texto(byte[] c, int de, int max) {
        int fim = de;
        while (fim < de + max && c[fim] != 0) {
            fim++;
        }
        return new String(c, de, fim - de, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] c, int de, int max) {
        String t = texto(c, de, max).strip();
        return t.isEmpty() ? 0 : Long.parseLong(t, 8);
    }
}
