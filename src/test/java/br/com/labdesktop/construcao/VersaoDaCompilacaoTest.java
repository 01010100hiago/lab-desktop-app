package br.com.labdesktop.construcao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O cálculo da versão do app a partir do git: a contagem de commits, a base, o sufixo "-alterado", a data e o que acontece sem git, com histórico raso ou com lixo. */
class VersaoDaCompilacaoTest {

    private static final LocalDate DIA = LocalDate.of(2026, 10, 30);
    private static final String COMMIT = "a207ad4c1d2e3f4a5b6c7d8e9f00112233445566";

    /** O git de mentira: responde pelo primeiro argumento (ou pelos dois) e guarda o que foi perguntado. */
    private static final class GitFalso implements VersaoDaCompilacao.Git {
        final Map<String, Optional<String>> respostas = new HashMap<>();
        final List<List<String>> perguntas = new ArrayList<>();

        GitFalso com(String chave, String resposta) {
            respostas.put(chave, Optional.ofNullable(resposta));
            return this;
        }

        static GitFalso normal() {
            return new GitFalso().com("rev-parse --is-shallow-repository", "false").com("rev-list --count HEAD", "320").com("rev-parse HEAD", COMMIT)
                .com("status --porcelain --untracked-files=normal", "");
        }

        @Override
        public Optional<String> executar(List<String> argumentos) {
            perguntas.add(argumentos);
            return respostas.getOrDefault(String.join(" ", argumentos), Optional.empty());
        }
    }

    private static VersaoDaCompilacao calcular(GitFalso git) {
        return VersaoDaCompilacao.calcular("0.1", git, DIA);
    }

    private static void assertReserva(VersaoDaCompilacao v) {
        assertEquals("0.1.0", v.versao());
        assertEquals(0, v.numero());
        assertEquals("desconhecido", v.commit());
        assertFalse(v.veioDoGit());
        assertEquals("2026-10-30", v.data(), "a data é a da compilação mesmo sem git");
    }

    @Test
    void versaoEBaseMaisONumeroDeCommitsECommitCurtoDeSeteDigitos() {
        VersaoDaCompilacao v = calcular(GitFalso.normal());
        assertEquals("0.1.320", v.versao());
        assertEquals(320, v.numero());
        assertEquals("a207ad4", v.commit());
        assertEquals("2026-10-30", v.data());
        assertTrue(v.veioDoGit());
        assertEquals("0.1.320 (a207ad4, 2026-10-30)", v.toString());
    }

    @Test
    void aBaseVemDeQuemChamaENaoDeUmaConstanteNoCodigo() {
        assertEquals("1.4.320", VersaoDaCompilacao.calcular("1.4", GitFalso.normal(), DIA).versao());
        assertEquals("12.34.320", VersaoDaCompilacao.calcular(" 12.34 ", GitFalso.normal(), DIA).versao(), "espaços nas pontas não contam");
        VersaoDaCompilacao v = VersaoDaCompilacao.calcular("12.34", GitFalso.normal(), DIA);
        assertEquals(12, v.major());
        assertEquals(34, v.minor());
        assertEquals("12.34", v.base());
    }

    @Test
    void arvoreComMudancasNaoCommitadasAcrescentaAlterado() {
        VersaoDaCompilacao v = calcular(GitFalso.normal().com("status --porcelain --untracked-files=normal", " M src/main/java/X.java"));
        assertEquals("a207ad4-alterado", v.commit());
        assertEquals("0.1.320", v.versao(), "o número não muda: o sufixo vai só no commit");
        assertTrue(v.veioDoGit());
    }

    @Test
    void arquivoNovoSemGitAddTambemContaComoAlterado() {
        assertEquals("a207ad4-alterado", calcular(GitFalso.normal().com("status --porcelain --untracked-files=normal", "?? novo.txt")).commit());
    }

    @Test
    void seOGitNaoConsegueDizerOEstadoDaArvoreValeAlteradoENaoLimpa() {
        assertEquals("a207ad4-alterado", calcular(GitFalso.normal().com("status --porcelain --untracked-files=normal", null)).commit());
    }

    @Test
    void semGitNoPathOuForaDeUmRepositorioCaiNaBaseComZero() {
        assertReserva(calcular(new GitFalso())); // todas as respostas vazias: o git não rodou
    }

    @Test
    void historicoRasoCaiNaBaseComZeroPorqueAContagemSeriaFalsa() {
        assertReserva(calcular(GitFalso.normal().com("rev-parse --is-shallow-repository", "true").com("rev-list --count HEAD", "1")));
    }

    @Test
    void gitAntigoQueRepeteAOpcaoEmVezDeResponderCaiNaReserva() {
        assertReserva(calcular(GitFalso.normal().com("rev-parse --is-shallow-repository", "--is-shallow-repository")));
        assertReserva(calcular(GitFalso.normal().com("rev-parse --is-shallow-repository", "")));
    }

    @Test
    void repositorioSemCommitsCaiNaReserva() {
        assertReserva(calcular(GitFalso.normal().com("rev-list --count HEAD", null).com("rev-parse HEAD", null)));
    }

    @Test
    void contagemQueNaoEUmNumeroPositivoCaiNaReserva() {
        for (String ruim : new String[] {"0", "-3", "abc", "", "12x", "3.5", "99999999999999999999"}) {
            assertReserva(calcular(GitFalso.normal().com("rev-list --count HEAD", ruim)));
        }
        assertEquals("0.1.7", calcular(GitFalso.normal().com("rev-list --count HEAD", " 7 ")).versao(), "espaços nas pontas não atrapalham");
    }

    @Test
    void nomeDeCommitQueNaoParecemUmCommitCaiNaReserva() {
        for (String ruim : new String[] {"HEAD", "zzzzzzz", "a207ad4", "A207AD4C1D2E3F4A5B6C7D8E9F00112233445566", COMMIT.substring(0, 39), COMMIT + "0", "fatal: bad revision 'HEAD'", COMMIT + " x", ""}) {
            assertReserva(calcular(GitFalso.normal().com("rev-parse HEAD", ruim)));
        }
    }

    @Test
    void repositorioComHashDe64DigitosTambemServe() {
        String sha256 = "0123456789abcdef".repeat(4);
        assertEquals("0123456", calcular(GitFalso.normal().com("rev-parse HEAD", sha256)).commit());
    }

    @Test
    void baseForaDoFormatoEErroDeConfiguracaoENaoCaiNaReserva() {
        for (String ruim : new String[] {"0.1.0", "abc", "", "1", "1.", ".1", "01.2", "1.-2", "1.2-rc", "0,1"}) {
            assertThrows(IllegalArgumentException.class, () -> VersaoDaCompilacao.calcular(ruim, GitFalso.normal(), DIA), "base: '" + ruim + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> VersaoDaCompilacao.calcular(null, GitFalso.normal(), DIA));
    }

    @Test
    void dataTemSempreAnoMesEDiaComZerosEViraDoDiaDado() {
        assertEquals("2026-01-05", VersaoDaCompilacao.calcular("0.1", GitFalso.normal(), LocalDate.of(2026, 1, 5)).data());
        assertEquals("2027-12-31", VersaoDaCompilacao.calcular("0.1", new GitFalso(), LocalDate.of(2027, 12, 31)).data());
    }

    @Test
    void seOHistoricoERasoOGitNemEPerguntadoSobreAContagem() {
        GitFalso git = GitFalso.normal().com("rev-parse --is-shallow-repository", "true");
        calcular(git);
        assertEquals(List.of(List.of("rev-parse", "--is-shallow-repository")), git.perguntas);
    }
}
