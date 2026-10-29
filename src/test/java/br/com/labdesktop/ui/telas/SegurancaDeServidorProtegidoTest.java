package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.servidor.ParsersDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.SaidasDeSeguranca;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Na aba Segurança de um servidor protegido o botão "Desligar o login por senha…" não aparece (o app não muda a senha de acesso dele), com o login por senha
 * ligado, que é quando o botão existiria; sem a marca o botão continua como antes.
 */
@ExtendWith(VerificaEdt.class)
class SegurancaDeServidorProtegidoTest {

    private static ResumoDeSeguranca comSenhaLigada() {
        ResumoDeSeguranca base = TelaServidorTest.resumoGrande(10, 2);
        return new ResumoDeSeguranca(base.lidoEm(), base.tentativas(), base.portas(), base.firewall(),
            Leitura.lida(ParsersDeSeguranca.lerSshd(SaidasDeSeguranca.SSHD_COM_SENHA)), base.fail2ban());
    }

    private static ServidorLab servidor(boolean protegido) {
        CofreFalso cofre = new CofreFalso("192.0.2.5", 22, List.of());
        cofre.alterarServidor(CofreFalso.ID, c -> c.comProtegido(protegido));
        return new ServidorLab(CofreFalso.ID, cofre, PedidoDeCodigo2fa.NUNCA);
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

    private static PaginaSeguranca pagina(ServidorLab lab) {
        return Visual.naEdt(() -> {
            PaginaSeguranca p = new PaginaSeguranca(lab, () -> { }, () -> { });
            p.aoMudarEstado(TelaServidorTest.estado(EstadoConexao.CONECTADO));
            p.aoReceberServidor(br.com.labdesktop.api.FixturesDeApi.servidorSintetico());
            p.aoReceberSeguranca(comSenhaLigada());
            p.mostrarSubAba(PaginaSeguranca.SUB_SSH);
            return p;
        });
    }

    @Test
    void servidorProtegidoNaoMostraOBotaoDeDesligarASenha() {
        Visual.tema(Tema.CLARO);
        ServidorLab lab = servidor(true);
        assertTrue(lab.protegido());
        PaginaSeguranca p = pagina(lab);
        assertTrue(Visual.naEdt(() -> p.textoDoPainel(PaginaSeguranca.SUB_SSH)).contains("ligada: qualquer um pode tentar adivinhar uma senha"), "a leitura continua");
        assertNull(botaoDeDesligar(p), "o app não muda a senha de acesso de um servidor protegido");
    }

    @Test
    void servidorComumContinuaComOBotao() {
        Visual.tema(Tema.CLARO);
        PaginaSeguranca p = pagina(servidor(false));
        Botao b = botaoDeDesligar(p);
        assertNotNull(b);
        assertEquals("Desligar o login por senha…", Visual.naEdt(b::getText));
    }
}
