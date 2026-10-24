package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.RegrasDeSaude.Avaliacao;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ConfiguracaoDoSsh;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Tentativas;
import br.com.labdesktop.servidor.ResumoDeSeguranca.TipoDeTentativa;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** As fronteiras dos limiares: 84,9 / 85 / 89,9 / 90 e companhia. */
class RegrasDeSaudeTest {

    @Test
    void temperaturaDaCpuAtencaoEm85CriticoEm90() {
        assertEquals(NivelDeSaude.SEM_DADO, RegrasDeSaude.temperatura(null));
        assertEquals(NivelDeSaude.OK, RegrasDeSaude.temperatura(0.0));
        assertEquals(NivelDeSaude.OK, RegrasDeSaude.temperatura(84.9));
        assertEquals(NivelDeSaude.ATENCAO, RegrasDeSaude.temperatura(85.0));
        assertEquals(NivelDeSaude.ATENCAO, RegrasDeSaude.temperatura(89.9));
        assertEquals(NivelDeSaude.CRITICO, RegrasDeSaude.temperatura(90.0));
        assertEquals(NivelDeSaude.CRITICO, RegrasDeSaude.temperatura(105.0));
    }

    @Test
    void discoAtencaoEm75CriticoEm90() {
        assertEquals(NivelDeSaude.SEM_DADO, RegrasDeSaude.disco(null));
        assertEquals(NivelDeSaude.OK, RegrasDeSaude.disco(74.9));
        assertEquals(NivelDeSaude.ATENCAO, RegrasDeSaude.disco(75.0));
        assertEquals(NivelDeSaude.ATENCAO, RegrasDeSaude.disco(89.9));
        assertEquals(NivelDeSaude.CRITICO, RegrasDeSaude.disco(90.0));
        assertEquals(NivelDeSaude.CRITICO, RegrasDeSaude.disco(100.0));
    }

    @Test
    void memoriaPeloQueEstaDisponivelAtencaoAbaixoDe15CriticoAbaixoDe8() {
        assertEquals(NivelDeSaude.SEM_DADO, RegrasDeSaude.memoria(null));
        assertEquals(NivelDeSaude.OK, RegrasDeSaude.memoria(50.0));
        assertEquals(NivelDeSaude.OK, RegrasDeSaude.memoria(15.0));
        assertEquals(NivelDeSaude.ATENCAO, RegrasDeSaude.memoria(14.9));
        assertEquals(NivelDeSaude.ATENCAO, RegrasDeSaude.memoria(8.0));
        assertEquals(NivelDeSaude.CRITICO, RegrasDeSaude.memoria(7.9));
        assertEquals(NivelDeSaude.CRITICO, RegrasDeSaude.memoria(0.0));
    }

    @Test
    void percentuaisVindosDoAgente() {
        var m = new InfoServidor.Memoria(1000L, 900L, 100L, null, null, null, null);
        assertEquals(10.0, RegrasDeSaude.pctDisponivel(m), 1e-9);
        var semDisponivel = new InfoServidor.Memoria(1000L, 900L, null, null, null, null, null);
        assertEquals(10.0, RegrasDeSaude.pctDisponivel(semDisponivel), 1e-9, "sem o campo, total menos usada");
        assertEquals(null, RegrasDeSaude.pctDisponivel(null));
        assertEquals(null, RegrasDeSaude.pctDisponivel(new InfoServidor.Memoria(0L, 0L, 0L, null, null, null, null)));
        assertEquals(75.0, RegrasDeSaude.pctUsado(new InfoServidor.Disco("/", 100L, 75L, 25L)), 1e-9);
        assertEquals(null, RegrasDeSaude.pctUsado(new InfoServidor.Disco("/", null, 75L, 25L)));
    }

    @Test
    void oDiscoMaisCheioEscolhePeloPercentual() {
        var a = new InfoServidor.Disco("/", 1000L, 500L, 500L);
        var b = new InfoServidor.Disco("/srv", 100L, 91L, 9L);
        assertEquals("/srv", RegrasDeSaude.discoMaisCheio(List.of(a, b)).montagem());
        assertEquals(null, RegrasDeSaude.discoMaisCheio(List.of()));
    }

    @Test
    void containersSempreOkComDadoParadosSaoNormais() {
        assertEquals(NivelDeSaude.SEM_DADO, RegrasDeSaude.containers(null));
        var parados = new InfoServidor.DockerInfo("1", 30, 1, 0, 29, 0, 0, null);
        assertEquals(NivelDeSaude.OK, RegrasDeSaude.containers(parados));
    }

    private static Tentativas tentativas(int em24h, int fora) {
        Map<TipoDeTentativa, Integer> tipos = new EnumMap<>(TipoDeTentativa.class);
        return new Tentativas(em24h, em24h, false, tipos, List.of(), 0, List.of(), fora);
    }

    private static ResumoDeSeguranca seg(Tentativas t, Boolean senha) {
        return new ResumoDeSeguranca(Instant.EPOCH, t == null ? Leitura.semPermissao() : Leitura.lida(t), Leitura.indisponivel(),
            Leitura.indisponivel(), senha == null ? Leitura.indisponivel()
            : Leitura.lida(new ConfiguracaoDoSsh(22, senha, "no", true, 3)), Leitura.indisponivel());
    }

    @Test
    void segurancaZeroTentativasOkComTentativasAtencaoSenhaOuLoginDeForaCritico() {
        assertEquals(NivelDeSaude.SEM_DADO, RegrasDeSaude.seguranca(null).nivel());
        assertEquals(NivelDeSaude.SEM_DADO, RegrasDeSaude.seguranca(seg(null, null)).nivel());
        assertEquals(NivelDeSaude.OK, RegrasDeSaude.seguranca(seg(tentativas(0, 0), false)).nivel());
        Avaliacao atencao = RegrasDeSaude.seguranca(seg(tentativas(1, 0), false));
        assertEquals(NivelDeSaude.ATENCAO, atencao.nivel());
        assertEquals("1 tentativa de entrada em 24 h", atencao.causa());
        assertEquals("120 tentativas de entrada em 24 h", RegrasDeSaude.seguranca(seg(tentativas(120, 0), false)).causa());
        assertEquals(NivelDeSaude.CRITICO, RegrasDeSaude.seguranca(seg(tentativas(0, 1), false)).nivel());
        Avaliacao senha = RegrasDeSaude.seguranca(seg(tentativas(0, 0), true));
        assertEquals(NivelDeSaude.CRITICO, senha.nivel());
        assertEquals("O SSH aceita senha", senha.causa());
    }

    @Test
    void segurancaSoComSshLidoNaoFingeQueViuAsTentativas() {
        assertEquals(NivelDeSaude.OK, RegrasDeSaude.seguranca(seg(null, false)).nivel());
    }

    @Test
    void saudeEOPiorEntreOsIndicadoresComACausaDele() {
        List<Avaliacao> todos = List.of(new Avaliacao(NivelDeSaude.OK, "a"), new Avaliacao(NivelDeSaude.ATENCAO, "Disco / a 78%"),
            new Avaliacao(NivelDeSaude.CRITICO, "Disco /srv a 91%"), new Avaliacao(NivelDeSaude.SEM_DADO, "x"));
        assertEquals(new Avaliacao(NivelDeSaude.CRITICO, "Disco /srv a 91%"), RegrasDeSaude.saude(todos));
        assertEquals(new Avaliacao(NivelDeSaude.OK, "Nenhum alerta agora"),
            RegrasDeSaude.saude(List.of(new Avaliacao(NivelDeSaude.OK, "a"), new Avaliacao(NivelDeSaude.SEM_DADO, "b"))));
        assertEquals(NivelDeSaude.SEM_DADO, RegrasDeSaude.saude(List.of(new Avaliacao(NivelDeSaude.SEM_DADO, "b"))).nivel());
        assertEquals(NivelDeSaude.SEM_DADO, RegrasDeSaude.saude(List.of()).nivel());
    }

    @Test
    void emEmpateASaudeFicaComOPrimeiro() {
        var a = new Avaliacao(NivelDeSaude.ATENCAO, "primeiro");
        var b = new Avaliacao(NivelDeSaude.ATENCAO, "segundo");
        assertEquals("primeiro", RegrasDeSaude.saude(List.of(a, b)).causa());
        assertEquals(NivelDeSaude.CRITICO, NivelDeSaude.OK.pior(NivelDeSaude.CRITICO));
        assertEquals(NivelDeSaude.ATENCAO, NivelDeSaude.ATENCAO.pior(NivelDeSaude.OK));
    }
}
