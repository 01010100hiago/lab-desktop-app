package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Os estados dos botões: quem aparece, quem liga e a frase de cada caso. Puro, sem rede. */
class AvaliadorDeProcessosTest {

    private static final AmbienteDeProcessos SERVIDOR = AmbienteDeProcessos.servidor("labuser", Set.of());

    @Test
    void processoComumDoMesmoUsuarioSoTemEncerrarEADizPorQueNaoReinicia() {
        Oferta o = AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3000, "node", "labuser", null, null), SERVIDOR);
        assertEquals(Oferta.Situacao.LIBERADO, o.situacao());
        assertEquals(Via.PROCESSO, o.via());
        assertTrue(o.podeEncerrar());
        assertFalse(o.podeReiniciar());
        assertFalse(o.reiniciarLigado());
        assertTrue(o.porQueNaoReinicia().contains("não sabe como religá-lo"), o.porQueNaoReinicia());
        assertTrue(o.explicacao().contains("SIGTERM") && o.explicacao().contains("SIGKILL"), "a confirmação explica os dois passos");
        assertEquals("", o.comandoParaODono());
    }

    @Test
    void processoDeContainerAgeNoContainerEPodeReiniciar() {
        Oferta o = AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3100, "node", "node", "app-loja-web", "app-loja"), SERVIDOR);
        assertEquals(Via.CONTAINER, o.via());
        assertTrue(o.podeEncerrar());
        assertTrue(o.podeReiniciar());
        assertTrue(o.reiniciarLigado());
        assertTrue(o.explicacao().contains("docker stop") && o.explicacao().contains("docker restart") && o.explicacao().contains("app-loja-web"), o.explicacao());
        assertTrue(o.explicacao().contains("projeto app-loja"));
    }

    @Test
    void processoDeOutroUsuarioViraSemPermissaoComOComandoExatoSemSenha() {
        Oferta o = AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3200, "postgres", "postgres", null, null), SERVIDOR);
        assertEquals(Oferta.Situacao.SEM_PERMISSAO, o.situacao());
        assertTrue(o.podeEncerrar(), "o botão abre o aviso com o comando");
        assertFalse(o.reiniciarLigado());
        assertEquals("sudo kill -TERM 3200", o.comandoParaODono());
        assertTrue(o.explicacao().contains("não usa sudo nem digita senha"));
    }

    @Test
    void processoDoAdministradorTambemEDeOutroUsuario() {
        Oferta o = AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3300, "nginx", "root", null, null), SERVIDOR);
        assertEquals(Oferta.Situacao.SEM_PERMISSAO, o.situacao());
    }

    @Test
    void quandoOUsuarioDoAppEhRootOsDeRootSaoDele_MasOsProtegidosContinuamProtegidos() {
        AmbienteDeProcessos root = AmbienteDeProcessos.servidor("root", Set.of());
        assertEquals(Oferta.Situacao.LIBERADO, AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3300, "nginx", "root", null, null), root).situacao());
        assertEquals(Oferta.Situacao.PROTEGIDO, AvaliadorDeProcessos.avaliar(new ProcessoAlvo(500, "sshd", "root", null, null), root).situacao());
        assertEquals(Oferta.Situacao.PROTEGIDO, AvaliadorDeProcessos.avaliar(new ProcessoAlvo(400, "dockerd", "root", null, null), root).situacao());
        assertEquals(Oferta.Situacao.PROTEGIDO, AvaliadorDeProcessos.avaliar(new ProcessoAlvo(1, "systemd", "root", null, null), root).situacao());
    }

    @Test
    void protegidoNaoOfereceNada() {
        Oferta o = AvaliadorDeProcessos.avaliar(new ProcessoAlvo(500, "sshd", "labuser", null, null), SERVIDOR);
        assertTrue(o.protegido());
        assertFalse(o.podeEncerrar());
        assertFalse(o.reiniciarLigado());
        assertNull(o.via());
        assertFalse(o.protecao().isBlank());
    }

    @Test
    void usuarioDoAppVazioNaoEDonoDeNinguem() {
        AmbienteDeProcessos semUsuario = AmbienteDeProcessos.servidor("", Set.of());
        assertEquals(Oferta.Situacao.SEM_PERMISSAO, AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3000, "node", "", null, null), semUsuario).situacao());
        assertEquals(Oferta.Situacao.SEM_PERMISSAO, AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3000, "node", "labuser", null, null), semUsuario).situacao());
    }

    @Test
    void noWindowsOUsuarioComDominioConfereSemDiferenciarMaiusculas() {
        AmbienteDeProcessos win = new AmbienteDeProcessos(AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS, "ana", Set.of(), Set.of());
        assertEquals(Oferta.Situacao.LIBERADO, AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3000, "notepad.exe", "PC\\ANA", null, null), win).situacao());
        Oferta outro = AvaliadorDeProcessos.avaliar(new ProcessoAlvo(3001, "notepad.exe", "PC\\bia", null, null), win);
        assertEquals(Oferta.Situacao.SEM_PERMISSAO, outro.situacao());
        assertTrue(outro.comandoParaODono().startsWith("taskkill /PID 3001 /T"), outro.comandoParaODono());
        assertFalse(outro.comandoParaODono().contains("sudo"));
    }
}
