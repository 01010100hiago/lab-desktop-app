package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.EstadoDeCarga.Fase;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** O componente do "carregando": a frase, o prazo com relógio falso, o "Tentar de novo" e os PNGs nos dois temas. */
@ExtendWith(VerificaEdt.class)
class CarregandoTest {

    private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-10-28T12:00:00Z"));

    private Carregando novo(String assunto) {
        Visual.tema(Tema.CLARO);
        return Visual.naEdt(() -> new Carregando(assunto, agora::get, Duration.ofSeconds(15)));
    }

    @Test
    void nasceMostrandoAFrasePadraoEOGirandoSemSaida() {
        Carregando c = novo("projetos");
        assertEquals("Carregando projetos...", Visual.naEdt(c::textoDaFrase));
        assertEquals(Fase.CARREGANDO, c.estado().fase());
        assertFalse(Visual.naEdt(() -> c.botaoTentarDeNovo().isVisible()));
        assertEquals("", Visual.naEdt(c::textoDaDica));
    }

    @Test
    void passadoOPrazoDizQueDemorouEOferecePerguntarDeNovo() {
        Carregando c = novo("projetos");
        agora.set(agora.get().plusSeconds(14));
        Visual.naEdt(c::passouOTempo);
        assertEquals("Carregando projetos...", Visual.naEdt(c::textoDaFrase), "aos 14 s ainda está no prazo");
        agora.set(agora.get().plusSeconds(1));
        Visual.naEdt(c::passouOTempo);
        assertEquals("Demorou mais que o normal.", Visual.naEdt(c::textoDaFrase));
        assertTrue(Visual.naEdt(() -> c.botaoTentarDeNovo().isVisible()));
        assertEquals("Tentar de novo", Visual.naEdt(() -> c.botaoTentarDeNovo().getText()));
        assertTrue(Visual.naEdt(c::textoDaDica).startsWith("O servidor ainda não respondeu"));
    }

    @Test
    void tentarDeNovoVoltaACarregandoRecomecaOPrazoEChamaAAcaoDaTela() {
        Carregando c = novo("projetos");
        AtomicInteger acoes = new AtomicInteger();
        Visual.naEdt(() -> c.aoTentarDeNovo(acoes::incrementAndGet));
        agora.set(agora.get().plusSeconds(20));
        Visual.naEdt(c::passouOTempo);
        agora.set(agora.get().plusSeconds(1));
        Visual.naEdt(() -> c.botaoTentarDeNovo().doClick(0));
        assertEquals(1, acoes.get(), "o botão pergunta ao servidor de novo");
        assertEquals("Carregando projetos...", Visual.naEdt(c::textoDaFrase));
        assertEquals(agora.get(), c.estado().inicio(), "o prazo recomeça no clique");
        agora.set(agora.get().plusSeconds(14));
        Visual.naEdt(c::passouOTempo);
        assertEquals(Fase.CARREGANDO, c.estado().fase());
    }

    @Test
    void falharMostraAFraseDoErroComSaidaEOdadoQueChegaParaTudo() {
        Carregando c = novo("a pasta");
        Visual.naEdt(() -> c.falhou("Sem permissão para ler esta pasta."));
        assertEquals("Sem permissão para ler esta pasta.", Visual.naEdt(c::textoDaFrase));
        assertTrue(Visual.naEdt(() -> c.botaoTentarDeNovo().isVisible()));
        Visual.naEdt(c::chegou);
        assertEquals(Fase.PRONTO, c.estado().fase());
    }

    @Test
    void iniciarDeNovoZeraOPrazoDeUmaEsperaAntiga() {
        Carregando c = novo("projetos");
        agora.set(agora.get().plusSeconds(60));
        Visual.naEdt(c::iniciar);
        assertEquals(Fase.CARREGANDO, c.estado().fase());
        Visual.naEdt(c::passouOTempo);
        assertEquals(Fase.CARREGANDO, c.estado().fase(), "uma tela montada há muito tempo não nasce \"demorando\"");
    }

    @Test
    void pngsDoCarregandoEDoDemorouNosDoisTemas() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Carregando c = Visual.naEdt(() -> new Carregando("projetos", agora::get, Duration.ofSeconds(15)));
            Visual.salvar(Visual.renderizar(c, 900, 260), "carregando-projetos-" + t.name().toLowerCase());
            agora.set(agora.get().plusSeconds(16));
            Visual.naEdt(c::passouOTempo);
            Visual.salvar(Visual.renderizar(c, 900, 260), "carregando-demorou-" + t.name().toLowerCase());
            agora.set(agora.get().minusSeconds(16));
        }
    }
}
