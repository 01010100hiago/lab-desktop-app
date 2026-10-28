package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.acesso.ComandosDoAcesso;
import br.com.labdesktop.servidor.ParsersDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.SaidasDeSeguranca;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A aba Segurança, sub-aba "SSH e bloqueio": "Login por senha" ligado ou desligado (lido do {@code sshd -T}), o botão que só ABRE a
 * confirmação quando a senha está ligada e o "Como reverter" quando está desligada. Somente leitura: nada muda ao clicar no botão.
 */
@ExtendWith(VerificaEdt.class)
class SegurancaDesligarSenhaTest {

    private static ResumoDeSeguranca comSshd(String sshd) {
        ResumoDeSeguranca base = TelaServidorTest.resumoGrande(10, 2);
        return new ResumoDeSeguranca(base.lidoEm(), base.tentativas(), base.portas(), base.firewall(),
            Leitura.lida(ParsersDeSeguranca.lerSshd(sshd)), base.fail2ban());
    }

    private static PaginaSeguranca pagina(Runnable aoDesligar, String sshd) {
        return Visual.naEdt(() -> {
            PaginaSeguranca p = new PaginaSeguranca(null, () -> { }, aoDesligar);
            p.aoMudarEstado(TelaServidorTest.estado(EstadoConexao.CONECTADO));
            p.aoReceberServidor(br.com.labdesktop.api.FixturesDeApi.servidorSintetico());
            p.aoReceberSeguranca(comSshd(sshd));
            p.mostrarSubAba(PaginaSeguranca.SUB_SSH);
            return p;
        });
    }

    private static Botao botaoDeDesligar(PaginaSeguranca p) {
        return Visual.naEdt((java.util.function.Supplier<Botao>) () -> {
            for (Component c : TelaServidorTest.todos(p)) {
                if (c instanceof Botao b && "desligar-a-senha".equals(b.getName())) {
                    return b;
                }
            }
            return null;
        });
    }

    @Test
    void comAsSenhaLigadaOLoginPorSenhaAparecePerigoEHaOBotaoQueSoAbreAConfirmacao() {
        Visual.tema(Tema.CLARO);
        AtomicInteger aberturas = new AtomicInteger();
        PaginaSeguranca p = pagina(aberturas::incrementAndGet, SaidasDeSeguranca.SSHD_COM_SENHA);

        String ssh = Visual.naEdt(() -> p.textoDoPainel(PaginaSeguranca.SUB_SSH));
        assertTrue(ssh.contains("Login por senha"), ssh);
        assertTrue(ssh.contains("ligada: qualquer um pode tentar adivinhar uma senha"), ssh);
        assertFalse(ssh.contains("Entrada por senha"), "o nome do campo agora é 'Login por senha'");
        Botao b = botaoDeDesligar(p);
        assertEquals("Desligar o login por senha…", Visual.naEdt(b::getText));
        assertEquals(0, aberturas.get(), "mostrar a aba não abre nada");
        assertTrue(ssh.contains("Nada muda ao clicar neste botão"), ssh);

        Visual.naEdt(() -> { b.doClick(); });
        assertEquals(1, aberturas.get(), "o clique só chama a abertura da confirmação");

        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            PaginaSeguranca nova = pagina(() -> { }, SaidasDeSeguranca.SSHD_COM_SENHA);
            int altura = Visual.alturaPreferida(nova, 1100);
            assertTrue(altura <= 700, "a aba de Segurança com o botão cabe numa janela de 1100 x 700 sem rolar (pede " + altura + " px)");
            List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(nova));
            assertTrue(achados.isEmpty(), "transborda: " + achados);
            Visual.salvar(Visual.renderizar(nova, 1100, 700), "seguranca-ssh-senha-ligada-" + tema.name().toLowerCase());
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void comAsSenhaDesligadaDizOkEMostraComoReverterSemBotaoDeLigar() {
        Visual.tema(Tema.CLARO);
        PaginaSeguranca p = pagina(() -> { }, SaidasDeSeguranca.SSHD_SEGURO);
        String ssh = Visual.naEdt(() -> p.textoDoPainel(PaginaSeguranca.SUB_SSH));
        assertTrue(ssh.contains("desligada: só entra quem tem a chave"), ssh);
        assertTrue(ssh.contains(ComandosDoAcesso.comandoParaReverter()), "o 'como reverter' aparece como texto: " + ssh);
        assertNull(botaoDeDesligar(p), "desligada: não há o que desligar, e o app NÃO oferece religar");
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            PaginaSeguranca nova = pagina(() -> { }, SaidasDeSeguranca.SSHD_SEGURO);
            Visual.salvar(Visual.renderizar(nova, 1100, 700), "seguranca-ssh-senha-desligada-" + tema.name().toLowerCase());
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void senhaDesligadaMasTecladoInterativoLigadoEParcialEAmareloEAindaTemOBotao() {
        Visual.tema(Tema.CLARO);
        String parcial = SaidasDeSeguranca.SSHD_SEGURO.replace("kbdinteractiveauthentication no", "kbdinteractiveauthentication yes") + "usepam yes\n";
        PaginaSeguranca p = pagina(() -> { }, parcial);
        String ssh = Visual.naEdt(() -> p.textoDoPainel(PaginaSeguranca.SUB_SSH));
        assertTrue(ssh.contains("parcial: a senha ainda entra pelo teclado interativo"), ssh);
        assertFalse(ssh.contains("desligada: só entra quem tem a chave"), "não diz que está desligada: " + ssh);
        assertTrue(ssh.contains("Teclado interativo (PAM)") && ssh.contains("ligado"), ssh);
        assertTrue(Visual.naEdt(() -> p.textosDoResumo()).get(3).contains("Parcial"), Visual.naEdt(() -> p.textosDoResumo()).toString());
        assertNotNull(botaoDeDesligar(p), "dá para completar o desligar");
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            PaginaSeguranca nova = pagina(() -> { }, parcial);
            Visual.salvar(Visual.renderizar(nova, 1100, 700), "seguranca-ssh-senha-parcial-" + tema.name().toLowerCase());
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void semAcaoDadaOuSemLeituraNaoHaBotao() {
        Visual.tema(Tema.CLARO);
        PaginaSeguranca semAcao = pagina(null, SaidasDeSeguranca.SSHD_COM_SENHA);
        assertNull(botaoDeDesligar(semAcao), "sem quem abra a confirmação (por exemplo, em Este computador) o botão não existe");
        PaginaSeguranca semLeitura = Visual.naEdt(() -> {
            PaginaSeguranca p = new PaginaSeguranca(null, () -> { }, () -> { });
            p.aoMudarEstado(TelaServidorTest.estado(EstadoConexao.CONECTADO));
            p.aoReceberServidor(br.com.labdesktop.api.FixturesDeApi.servidorSintetico());
            ResumoDeSeguranca base = TelaServidorTest.resumoGrande(10, 2);
            p.aoReceberSeguranca(new ResumoDeSeguranca(base.lidoEm(), base.tentativas(), base.portas(), base.firewall(), Leitura.semPermissao(), base.fail2ban()));
            p.mostrarSubAba(PaginaSeguranca.SUB_SSH);
            return p;
        });
        assertNull(botaoDeDesligar(semLeitura), "sem ler o sshd (sem sudo) não se sabe se a senha está ligada: nada de botão");
    }
}
