package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDeConexao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A faixa de aviso desenhada: ícone e texto, os botões certos, a contagem regressiva e o Retomar das pausadas. */
@ExtendWith(VerificaEdt.class)
class FaixaAvisoTest {

    private static final Instant T0 = Instant.parse("2026-10-24T15:00:00Z");

    private static LimitesConta limites(double cinco, double semana) {
        return FixturesDeApi.de("{\"disponivel\":true,\"antiga\":false,\"lido_em\":\"2026-10-24T14:57:00Z\","
            + "\"cinco_horas\":{\"usado_pct\":" + cinco + ",\"zera_em\":\"2026-10-24T17:10:00Z\"},"
            + "\"semana\":{\"usado_pct\":" + semana + ",\"zera_em\":\"2026-10-29T18:00:00Z\"},"
            + "\"freio\":{\"pct\":90,\"ativo\":true},"
            + "\"pausados\":[{\"projeto\":\"app-loja\",\"motivo\":\"teto\"},{\"projeto\":\"lab\",\"motivo\":\"freio\"}],"
            + "\"por_projeto\":{\"app-loja\":{\"usado_pp\":22.1,\"teto_pct\":20,\"estado\":\"pausado\"}}}", LimitesConta.class);
    }

    private static EstadoServidor reconectando(int tentativa, Instant proxima, LimitesConta l) {
        return new EstadoServidor("s1", "lab", EstadoConexao.RECONECTANDO, new Falha(Falha.Tipo.REDE, "sem rota"), tentativa,
            proxima, Instant.parse("2026-10-24T14:05:00Z"), 1, Estado2fa.DESLIGADO, "mac", null, l);
    }

    private final List<Acao> acoes = new ArrayList<>();
    private final List<String> retomadas = new ArrayList<>();
    private final AtomicReference<Instant> agora = new AtomicReference<>(T0);

    private FaixaAviso faixa() {
        return Visual.naEdt(() -> new FaixaAviso(new FaixaAviso.Ouvinte() {
            @Override
            public void aoAcionar(Acao acao) {
                acoes.add(acao);
            }

            @Override
            public void aoRetomar(String projeto) {
                retomadas.add(projeto);
            }
        }, agora::get));
    }

    @Test
    void semAvisoFicaEscondida() {
        Visual.tema(Tema.CLARO);
        FaixaAviso f = faixa();
        Visual.naEdt(() -> f.atualizar(new EstadoServidor("s1", "lab", EstadoConexao.CONECTADO, null, 0, null, null, 1,
            Estado2fa.DESLIGADO, "mac", null, null)));
        assertFalse(Visual.naEdt(f::isVisible));
        Visual.naEdt(() -> f.atualizar(null));
        assertTrue(Visual.naEdt(f::avisosMostrados).isEmpty());
    }

    @Test
    void asTresFaixasApareceNaOrdemEOsBotoesChamamOOuvinte() {
        Visual.tema(Tema.CLARO);
        FaixaAviso f = faixa();
        Visual.naEdt(() -> f.atualizar(reconectando(3, T0.plusSeconds(8), limites(82, 92))));
        assertTrue(Visual.naEdt(f::isVisible));
        assertEquals(3, Visual.naEdt(f::avisosMostrados).size());

        Visual.naEdt(() -> f.botaoDaAcao(Acao.TENTAR_AGORA).doClick());
        assertEquals(List.of(Acao.TENTAR_AGORA), acoes);

        assertNotNull(Visual.naEdt(() -> f.botaoDeRetomar("app-loja")));
        Visual.naEdt(() -> f.botaoDeRetomar("lab").doClick());
        assertEquals(List.of("lab"), retomadas, "o Retomar leva o nome REAL do projeto, não 'sessão do lab'");
    }

    @Test
    void aContagemRegressivaAndaComORelogioSemRecriarOQueNaoMudou() {
        Visual.tema(Tema.CLARO);
        FaixaAviso f = faixa();
        Visual.naEdt(() -> f.atualizar(reconectando(3, T0.plusSeconds(8), null)));
        assertTrue(Visual.naEdt(f::relogioCorrendo), "reconectando liga o relógio de 1 s");
        AvisoDeConexao antes = (AvisoDeConexao) Visual.naEdt(f::avisosMostrados).get(0);
        assertTrue(antes.texto().contains("próxima em 8 s"), antes.texto());

        agora.set(T0.plusSeconds(3));
        Visual.naEdt(() -> f.atualizar(reconectando(3, T0.plusSeconds(8), null)));
        AvisoDeConexao depois = (AvisoDeConexao) Visual.naEdt(f::avisosMostrados).get(0);
        assertTrue(depois.texto().contains("próxima em 5 s"), depois.texto());

        Visual.naEdt(() -> f.atualizar(new EstadoServidor("s1", "lab", EstadoConexao.CONECTADO, null, 0, null, null, 1,
            Estado2fa.DESLIGADO, "mac", null, null)));
        assertFalse(Visual.naEdt(f::relogioCorrendo), "conectado desliga o relógio");
    }

    @Test
    void oRetomarFicaOcupadoEnquantoAAcaoRoda() {
        Visual.tema(Tema.CLARO);
        FaixaAviso f = faixa();
        Visual.naEdt(() -> f.atualizar(reconectando(1, null, limites(10, 10))));
        Visual.naEdt(() -> f.definirOcupados(Set.of("app-loja")));
        assertTrue(Visual.naEdt(() -> f.botaoDeRetomar("app-loja").ocupado()));
        assertFalse(Visual.naEdt(() -> f.botaoDeRetomar("app-loja").isEnabled()));
        assertFalse(Visual.naEdt(() -> f.botaoDeRetomar("lab").ocupado()));
        Visual.naEdt(() -> f.definirOcupados(Set.of()));
        assertFalse(Visual.naEdt(() -> f.botaoDeRetomar("app-loja").ocupado()));
        assertTrue(Visual.naEdt(() -> f.botaoDeRetomar("app-loja").isEnabled()));
    }

    @Test
    void ocupacaoSobreviveAUmaReconstrucaoDaFaixa() {
        Visual.tema(Tema.CLARO);
        FaixaAviso f = faixa();
        Visual.naEdt(() -> f.atualizar(reconectando(1, null, limites(10, 10))));
        Visual.naEdt(() -> f.definirOcupados(Set.of("app-loja")));
        Visual.naEdt(() -> f.atualizar(reconectando(2, null, limites(10, 10)))); // muda o texto: reconstrói
        assertTrue(Visual.naEdt(() -> f.botaoDeRetomar("app-loja").ocupado()), "o botão novo nasce ocupado");
    }

    @Test
    void desenhaNosDoisTemasComIconeETexto() {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            FaixaAviso f = faixa();
            Visual.naEdt(() -> f.atualizar(reconectando(3, T0.plusSeconds(8), limites(82, 92))));
            int altura = Visual.alturaPreferida(f, 1100);
            BufferedImage img = Visual.renderizar(f, 1100, altura);
            Visual.salvar(img, "faixa-de-aviso-" + tema.name().toLowerCase());
            assertNull(f.botaoDaAcao(Acao.DIGITAR_CODIGO));
        }
    }
}
