package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.RecursosDoAgente;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O servidor com agente do perfil MONITOR (sem Claude, sem projetos): a aba Projetos some, o Resumo e os Containers mostram só o que o
 * monitor sabe, e nada fala de Claude. Com o agente do lab, tudo como antes. Os PNGs dos dois temas saem em {@code build/telas/}.
 */
@ExtendWith(VerificaEdt.class)
class TelaServidorSemClaudeTest {

    private static EstadoServidor estadoCom(String perfil, RecursosDoAgente recursos) {
        VersaoAgente v = new VersaoAgente(1, "v22.5.0", false, "mac", "desligado", perfil, recursos, "monitor".equals(perfil) ? "0.1.0-abc1234" : null);
        return new EstadoServidor("s1", "vps-1", EstadoConexao.CONECTADO, null, 0, null, null, 0, Estado2fa.DESLIGADO, "mac", v, null);
    }

    private static void alimentar(TelaServidor t, EstadoServidor e) {
        Visual.naEdt(() -> {
            t.aplicarRecursos(e.temProjetos());
            for (Aba a : Aba.values()) {
                PaginaDoServidor v = t.pagina(a);
                if (v == null) {
                    continue;
                }
                v.aoMudarEstado(e);
                v.aoReceberServidor(FixturesDeApi.servidorSintetico());
                v.aoReceberDetalhe(FixturesDeApi.detalheSintetico());
            }
        });
    }

    /** Os textos do que está À VISTA (o que está escondido não conta: o usuário não o lê). */
    private static String visiveis(java.awt.Component raiz) {
        return Visual.naEdt(() -> {
            StringBuilder sb = new StringBuilder();
            juntar(raiz, sb);
            return sb.toString();
        });
    }

    private static void juntar(java.awt.Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof javax.swing.text.JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton b && b.getText() != null) {
            sb.append(b.getText()).append('\n');
        } else if (c instanceof br.com.labdesktop.ui.componentes.Selo sel) {
            sb.append(sel.texto()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                juntar(f, sb);
            }
        }
    }

    @Test
    void semProjetosAAbaProjetosSomeESemClaudeOsContainersNaoFalamDele() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = TelaServidorTest.nova();
        alimentar(t, estadoCom("monitor", RecursosDoAgente.SO_MONITOR));

        assertFalse(Visual.naEdt(() -> t.abas().visivel(Aba.PROJETOS.id())), "a aba Projetos não existe neste servidor");
        assertFalse(Visual.naEdt(t::temProjetos));
        for (Aba a : Aba.values()) {
            if (a != Aba.PROJETOS) {
                assertTrue(Visual.naEdt(() -> t.abas().visivel(a.id())), a + " fica");
            }
        }
        assertEquals("Containers", Visual.naEdt(() -> t.containers().bloco().getName()).replace("bloco-", ""), "o título não fala de Claude");
        Visual.naEdt(() -> t.mostrarAba(Aba.CONTAINERS));
        String containers = visiveis(t.containers());
        assertFalse(containers.toLowerCase().contains("claude"), containers);
        assertTrue(containers.contains("Rodando") && containers.contains("no total, 10 containers"), containers);
        Visual.naEdt(() -> t.mostrarAba(Aba.RESUMO));
        String resumo = visiveis(t.resumo());
        assertFalse(resumo.toLowerCase().contains("claude"), resumo);
        // o atalho Cmd+1 ("Projetos") não leva a uma aba que não existe
        Visual.naEdt(() -> t.mostrarAba(Aba.PROJETOS));
        assertEquals(Aba.RESUMO, t.abaAtual());

        Visual.naEdt(() -> t.mostrarAba(Aba.RESUMO));
        Visual.salvar(Visual.renderizar(t, 1100, 700), "servidor-monitor-resumo-claro");
        Visual.naEdt(() -> t.mostrarAba(Aba.CONTAINERS));
        Visual.salvar(Visual.renderizar(t, 1100, 700), "servidor-monitor-containers-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(t));
        Visual.salvar(Visual.renderizar(t, 1100, 700), "servidor-monitor-containers-escuro");
        Visual.naEdt(() -> t.mostrarAba(Aba.RESUMO));
        Visual.salvar(Visual.renderizar(t, 1100, 700), "servidor-monitor-resumo-escuro");
        Visual.tema(Tema.CLARO);
    }

    @Test
    void comOAgenteDoLabEComAgenteAntigoAAbaProjetosEOsContainersSaoOsDeSempre() {
        Visual.tema(Tema.CLARO);
        for (EstadoServidor e : new EstadoServidor[] {estadoCom("lab", RecursosDoAgente.TUDO), estadoCom(null, null)}) {
            TelaServidor t = TelaServidorTest.nova();
            alimentar(t, e);
            assertTrue(Visual.naEdt(() -> t.abas().visivel(Aba.PROJETOS.id())), "agente " + e.versao().perfil());
            assertTrue(Visual.naEdt(t::temProjetos));
            Visual.naEdt(() -> t.mostrarAba(Aba.CONTAINERS));
            assertTrue(visiveis(t.containers()).contains("Containers e sessões do Claude"), "o bloco de containers e sessões do Claude de sempre");
            assertTrue(visiveis(t.containers()).contains("SESSÕES DO CLAUDE ABERTAS"));
        }
    }

    @Test
    void voltandoOAgenteDoLabAAbaProjetosVolta() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = TelaServidorTest.nova();
        alimentar(t, estadoCom("monitor", RecursosDoAgente.SO_MONITOR));
        assertFalse(Visual.naEdt(() -> t.abas().visivel(Aba.PROJETOS.id())));
        alimentar(t, estadoCom("lab", RecursosDoAgente.TUDO));
        assertTrue(Visual.naEdt(() -> t.abas().visivel(Aba.PROJETOS.id())));
    }

    @Test
    void setasDasAbasPulamAAbaEscondida() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = TelaServidorTest.nova();
        alimentar(t, estadoCom("monitor", RecursosDoAgente.SO_MONITOR));
        Visual.naEdt(() -> t.abas().botao(Aba.MAQUINA.id()).doClick());
        var seta = t.abas().botao(Aba.MAQUINA.id()).getActionMap().get("proxima-aba");
        Visual.naEdt(() -> seta.actionPerformed(new java.awt.event.ActionEvent(t.abas().botao(Aba.MAQUINA.id()), 0, "")));
        assertEquals(Aba.ARQUIVOS, Visual.naEdt(t::abaMarcada), "da Máquina a seta vai direto a Arquivos, sem parar na aba escondida");
    }
}
