package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DerivadorArgon2idTest {

    private static final byte[] SAL = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    @Test
    void bateComOVetorDeReferenciaDoArgon2id() {
        // password / somesalt, t=2, m=65536 KiB, p=1, 32 bytes: o exemplo "argon2id v=19" do repositório de referência
        // do Argon2, conferido aqui também com `openssl kdf ARGON2ID` (OpenSSL 3.6). Prova que os parâmetros
        // do BouncyCastle estão ligados na ordem certa (memória, iterações, lanes) e que a variante é a id v1.3.
        byte[] chave = DerivadorArgon2id.derivar("password".toCharArray(),
            "somesalt".getBytes(StandardCharsets.US_ASCII), new ParametrosKdf(65_536, 2, 1));
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", HexFormat.of().formatHex(chave));
    }

    @Test
    void ehDeterministicoEDependeDeSalSenhaEParametros() {
        char[] senha = "uma senha qualquer".toCharArray();
        ParametrosKdf p = ParametrosKdf.MINIMO;
        byte[] a = DerivadorArgon2id.derivar(senha, SAL, p);
        assertEquals(32, a.length);
        assertArrayEquals(a, DerivadorArgon2id.derivar(senha, SAL, p));

        byte[] outroSal = SAL.clone();
        outroSal[0] ^= 1;
        assertFalse(Arrays.equals(a, DerivadorArgon2id.derivar(senha, outroSal, p)));
        assertFalse(Arrays.equals(a, DerivadorArgon2id.derivar("uma senha qualquer!".toCharArray(), SAL, p)));
        assertFalse(Arrays.equals(a, DerivadorArgon2id.derivar(senha, SAL, new ParametrosKdf(19_456, 3, 1))));
        assertFalse(Arrays.equals(a, DerivadorArgon2id.derivar(senha, SAL, new ParametrosKdf(19_457, 2, 1))));
        assertFalse(Arrays.equals(a, DerivadorArgon2id.derivar(senha, SAL, new ParametrosKdf(19_456, 2, 2))));
    }

    @Test
    void naoZeraOCharArrayDoChamador() {
        char[] senha = "senha-do-chamador".toCharArray();
        DerivadorArgon2id.derivar(senha, SAL, ParametrosKdf.MINIMO);
        assertEquals("senha-do-chamador", new String(senha));
    }

    @Test
    void senhaVaiParaUtf8SemNormalizacaoUnicode() {
        String composto = "café";        // é precomposto
        String decomposto = "café";    // e + acento combinante
        assertArrayEquals("cafÃ©".getBytes(StandardCharsets.ISO_8859_1),
            DerivadorArgon2id.senhaParaBytes(composto.toCharArray()));
        assertFalse(Arrays.equals(DerivadorArgon2id.senhaParaBytes(composto.toCharArray()),
            DerivadorArgon2id.senhaParaBytes(decomposto.toCharArray())));
        // E portanto as duas grafias dão chaves diferentes (a senha vale exatamente como digitada).
        assertFalse(Arrays.equals(
            DerivadorArgon2id.derivar(composto.toCharArray(), SAL, ParametrosKdf.MINIMO),
            DerivadorArgon2id.derivar(decomposto.toCharArray(), SAL, ParametrosKdf.MINIMO)));
        assertArrayEquals("ação ❤".getBytes(StandardCharsets.UTF_8), DerivadorArgon2id.senhaParaBytes("ação ❤".toCharArray()));
    }

    @Test
    void custoDeProducaoFicaEntreMeioESegundosETrezentosMilissegundosAQuatro() {
        // ARQUITETURA 5.4: alvo de cerca de 1 s no desbloqueio; faixa aceita 0,3 a 4 s para não ser frágil.
        // Mede o tempo de CPU da thread (o Argon2id do BouncyCastle roda as lanes em série numa só thread), não
        // o relógio de parede: com a máquina ocupada (load alto, outro build) o relógio dobra sem que o custo
        // para o defensor tenha mudado, e o teste falharia por motivo alheio ao código.
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        boolean medeCpu = threads.isCurrentThreadCpuTimeSupported();
        DerivadorArgon2id.derivar("aquecimento".toCharArray(), SAL, ParametrosKdf.MINIMO); // JIT
        long paredeIni = System.nanoTime();
        long cpuIni = medeCpu ? threads.getCurrentThreadCpuTime() : 0;
        byte[] chave = DerivadorArgon2id.derivar("senha de calibragem".toCharArray(), SAL, ParametrosKdf.PADRAO);
        double parede = (System.nanoTime() - paredeIni) / 1e9;
        double segundos = medeCpu ? (threads.getCurrentThreadCpuTime() - cpuIni) / 1e9 : parede;
        assertEquals(32, chave.length);
        assertTrue(segundos >= 0.3 && segundos <= 4.0, "Argon2id de produção levou " + segundos
            + " s de " + (medeCpu ? "CPU" : "relógio") + " (esperado 0,3 a 4; relógio de parede: " + parede + " s)");
    }

    @Test
    void parametrosPadraoSaoOsDoDocumento() {
        assertEquals(new ParametrosKdf(262_144, 6, 1), ParametrosKdf.PADRAO);
        assertTrue(ParametrosKdf.MINIMO.maisFracoQue(ParametrosKdf.PADRAO));
        assertFalse(ParametrosKdf.PADRAO.maisFracoQue(ParametrosKdf.MINIMO));
        assertFalse(ParametrosKdf.PADRAO.maisFracoQue(ParametrosKdf.PADRAO));
        // Paralelismo não conta como custo.
        assertFalse(new ParametrosKdf(262_144, 6, 1).maisFracoQue(new ParametrosKdf(262_144, 6, 4)));
    }

    @Test
    void faixaDeParametros() {
        assertTrue(ParametrosKdf.dentroDaFaixa(19_456, 2, 1));
        assertTrue(ParametrosKdf.dentroDaFaixa(1_048_576, 10, 8));
        assertFalse(ParametrosKdf.dentroDaFaixa(19_455, 2, 1));
        assertFalse(ParametrosKdf.dentroDaFaixa(1_048_577, 2, 1));
        assertFalse(ParametrosKdf.dentroDaFaixa(19_456, 1, 1));
        assertFalse(ParametrosKdf.dentroDaFaixa(19_456, 11, 1));
        assertFalse(ParametrosKdf.dentroDaFaixa(19_456, 2, 0));
        assertFalse(ParametrosKdf.dentroDaFaixa(19_456, 2, 9));
        assertFalse(ParametrosKdf.dentroDaFaixa(0xFFFFFFFFL, 2, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ParametrosKdf(1, 1, 1));
    }
}
