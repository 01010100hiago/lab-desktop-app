package br.com.labdesktop.construcao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.construcao.VersaoDoPacote.Sistema;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O {@code --app-version} do jpackage por sistema: valores conhecidos, as regras de cada instalador e o crescimento a cada compilação. */
class VersaoDoPacoteTest {

    /** Uma versão sem git de verdade: a base e o número que o teste quiser. */
    private static VersaoDaCompilacao v(String base, int numero) {
        String sha = "a207ad4c1d2e3f4a5b6c7d8e9f00112233445566";
        return VersaoDaCompilacao.calcular(base, args -> switch (String.join(" ", args)) {
            case "rev-parse --is-shallow-repository" -> Optional.of("false");
            case "rev-list --count HEAD" -> Optional.of(Integer.toString(numero));
            case "rev-parse HEAD" -> Optional.of(sha);
            default -> Optional.of("");
        }, LocalDate.of(2026, 10, 30));
    }

    @Test
    void valoresConhecidosPorSistema() {
        VersaoDaCompilacao v = v("0.1", 320);
        assertEquals("1.1.320", VersaoDoPacote.appVersion(Sistema.MAC, v));
        assertEquals("0.1.320", VersaoDoPacote.appVersion(Sistema.WINDOWS, v));
        assertEquals("0.1.320", VersaoDoPacote.appVersion(Sistema.LINUX, v));
        assertEquals("320", VersaoDoPacote.cfBundleVersion(v), "o CFBundleVersion do Mac é o número de commits");
    }

    @Test
    void noMacOPrimeiroNumeroSomaUmSempreParaNuncaRecuar() {
        assertEquals("2.0.5", VersaoDoPacote.appVersion(Sistema.MAC, v("1.0", 5)));
        assertEquals("1.1.0", VersaoDoPacote.appVersion(Sistema.MAC, v("0.1", 0)), "a versão de reserva (sem git) também é aceita");
        assertEquals("1.0.1", VersaoDoPacote.appVersion(Sistema.MAC, v("0.0", 1)));
        // a ordem do pacote acompanha a do app, também na virada de 0.x para 1.x
        assertTrue(compara(VersaoDoPacote.appVersion(Sistema.MAC, v("0.1", 99999)), VersaoDoPacote.appVersion(Sistema.MAC, v("0.2", 1))) < 0);
        assertTrue(compara(VersaoDoPacote.appVersion(Sistema.MAC, v("0.9", 99999)), VersaoDoPacote.appVersion(Sistema.MAC, v("1.0", 0))) < 0);
        // e o pacote de antes desta regra (0.1.0 -> 1.1.0) fica abaixo do primeiro com número de commits
        assertTrue(compara("1.1.0", VersaoDoPacote.appVersion(Sistema.MAC, v("0.1", 321))) < 0);
    }

    @Test
    void aVersaoCrescePorCompilacaoEmTodosOsSistemas() {
        for (Sistema s : Sistema.values()) {
            assertTrue(compara(VersaoDoPacote.appVersion(s, v("0.1", 320)), VersaoDoPacote.appVersion(s, v("0.1", 321))) < 0, s.name());
            assertTrue(compara(VersaoDoPacote.appVersion(s, v("0.1", 9)), VersaoDoPacote.appVersion(s, v("0.1", 10))) < 0, s + ": 9 abaixo de 10 (numérico, não texto)");
        }
    }

    @Test
    void cadaSistemaSoRecebeUmaVersaoQueOInstaladorAceita() {
        for (String base : List.of("0.0", "0.1", "0.9", "1.0", "3.14", "254.255")) {
            for (int n : new int[] {0, 1, 320, 65535}) {
                VersaoDaCompilacao v = v(base, n);
                // macOS: três números e o primeiro maior que zero
                assertTrue(VersaoDoPacote.appVersion(Sistema.MAC, v).matches("[1-9]\\d*\\.\\d+\\.\\d+"), base + "." + n);
                // Windows MSI: maior e menor de 0 a 255, compilação de 0 a 65535
                String[] w = VersaoDoPacote.appVersion(Sistema.WINDOWS, v).split("\\.");
                assertEquals(3, w.length);
                assertTrue(Integer.parseInt(w[0]) <= 255 && Integer.parseInt(w[1]) <= 255 && Integer.parseInt(w[2]) <= 65535, base + "." + n);
                // Linux (deb e rpm): só números e pontos, começando por dígito
                assertTrue(VersaoDoPacote.appVersion(Sistema.LINUX, v).matches("\\d+(\\.\\d+){1,2}"), base + "." + n);
            }
        }
    }

    @Test
    void noWindowsForaDoLimiteDoMsiOBuildFalhaComAExplicacaoENaoTrunca() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> VersaoDoPacote.appVersion(Sistema.WINDOWS, v("0.1", 65536)));
        assertTrue(e.getMessage().contains("0.1.65536") && e.getMessage().contains("65535"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> VersaoDoPacote.appVersion(Sistema.WINDOWS, v("256.0", 1)));
        assertThrows(IllegalArgumentException.class, () -> VersaoDoPacote.appVersion(Sistema.WINDOWS, v("0.256", 1)));
        // nos outros sistemas o mesmo número passa
        assertEquals("1.1.65536", VersaoDoPacote.appVersion(Sistema.MAC, v("0.1", 65536)));
        assertEquals("0.1.65536", VersaoDoPacote.appVersion(Sistema.LINUX, v("0.1", 65536)));
    }

    /** Compara versões numéricas separadas por ponto, parte a parte (como o macOS, o Windows e o deb comparam). */
    private static int compara(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
