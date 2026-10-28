package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.telas.TelaProjetoTest.Cena;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * "Nada de texto miúdo": renderiza as telas principais (o Resumo e as abas do servidor, a lista de projetos e cada aba da
 * página do projeto) e reprova QUALQUER componente de texto com fonte menor que 12 px, em 100 % e em 130 %. É o vigia da escala
 * central ({@code ui.tema.Fontes}): um {@code deriveFont(11f)} solto numa tela nova faz este teste cair.
 */
@ExtendWith(VerificaEdt.class)
class TesteFontesMinimas {

    private static final int LARGURA = 1100 - BarraServidores.LARGURA;
    private static final int ALTURA = 700 - 28;

    @AfterEach
    void voltarA100() {
        Visual.naEdt(() -> Fontes.definirPercentual(100));
    }

    /** Reúne os que mostram texto e têm fonte abaixo do mínimo. */
    private static List<String> miudos(Component raiz, float minimo, String onde) {
        List<String> ruins = new ArrayList<>();
        Visual.naEdt(() -> varrer(raiz, minimo, onde, ruins));
        return ruins;
    }

    private static void varrer(Component c, float minimo, String onde, List<String> ruins) {
        if (!c.isVisible()) {
            return;
        }
        String texto = null;
        Font fonte = c.getFont();
        if (c instanceof Selo s) {
            texto = s.texto();
            fonte = s.fonteDoTexto();
        } else if (c instanceof JLabel l) {
            texto = l.getText();
        } else if (c instanceof AbstractButton b) {
            texto = b.getText();
        } else if (c instanceof JTextComponent t) {
            texto = t.getText().isEmpty() ? "(campo)" : t.getText();
        } else if (c instanceof JComboBox<?> cb) {
            texto = "(lista)";
            fonte = cb.getFont();
        }
        if (texto != null && !texto.isEmpty() && fonte != null && fonte.getSize2D() < minimo) {
            ruins.add(onde + ": '" + (texto.length() > 40 ? texto.substring(0, 40) + "…" : texto) + "' em " + c.getClass().getSimpleName()
                + " com " + fonte.getSize2D() + " px");
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                varrer(f, minimo, onde, ruins);
            }
        }
    }

    private static float minimoDe(int percentual) {
        return (float) Math.floor(Fontes.MINIMO * percentual / 100f * 2) / 2f;
    }

    private static void conferirServidor(int percentual) {
        Visual.naEdt(() -> Fontes.definirPercentual(percentual));
        Visual.tema(Tema.CLARO);
        TelaServidor t = TelaServidorTest.nova();
        TelaServidorTest.alimentar(t, EstadoConexao.CONECTADO, TelaServidorTest.resumoGrande(60, 6), true);
        List<String> ruins = new ArrayList<>();
        for (Aba aba : Aba.values()) {
            Visual.naEdt(() -> t.mostrarAba(aba));
            if (aba == Aba.SEGURANCA) {
                for (String sub : new String[] {PaginaSeguranca.SUB_TENTATIVAS, PaginaSeguranca.SUB_ORIGENS, PaginaSeguranca.SUB_LOGINS,
                    PaginaSeguranca.SUB_FIREWALL, PaginaSeguranca.SUB_SSH}) {
                    Visual.naEdt(() -> t.seguranca().mostrarSubAba(sub));
                    Visual.renderizar(t, LARGURA, ALTURA);
                    ruins.addAll(miudos(t, minimoDe(percentual), percentual + "% servidor/" + aba + "/" + sub));
                }
            } else {
                Visual.renderizar(t, LARGURA, ALTURA);
                ruins.addAll(miudos(t, minimoDe(percentual), percentual + "% servidor/" + aba));
            }
        }
        assertTrue(ruins.isEmpty(), "texto miúdo na página do servidor:\n" + String.join("\n", ruins));
    }

    private static void conferirProjetos(int percentual) {
        Visual.naEdt(() -> Fontes.definirPercentual(percentual));
        Visual.tema(Tema.CLARO);
        TelaProjetos tela = TelaProjetosTest.nova(new AvisosGravados(), new DialogosGravados(), true);
        Visual.naEdt(() -> {
            tela.aoMudarEstado(TelaProjetosTest.estado(EstadoConexao.CONECTADO));
            tela.aoReceberProjetos(TelaProjetosTest.visaoDoMock());
        });
        Visual.renderizar(tela, LARGURA, 900);
        List<String> ruins = miudos(tela, minimoDe(percentual), percentual + "% lista de projetos");
        assertTrue(ruins.isEmpty(), "texto miúdo na lista de projetos:\n" + String.join("\n", ruins));
    }

    private static void conferirProjeto(int percentual) {
        Visual.naEdt(() -> Fontes.definirPercentual(percentual));
        List<String> ruins = new ArrayList<>();
        for (TelaProjeto.Aba aba : TelaProjeto.Aba.values()) {
            Visual.tema(Tema.ESCURO);
            Cena c = new Cena(true, aba).com(EstadoConexao.CONECTADO, TelaProjetoTest.desatualizadaComTudo())
                .comConsumo(FixturesDeApi.consumoSintetico());
            Visual.naEdt(() -> {
                c.tela.logs().receber(FixturesDeApi.lerRecursoDoProjeto("logs-sintetico.txt"), 300);
                c.tela.terminal().receberTela(FixturesDeApi.telaSintetica("tela-login"));
            });
            Visual.renderizar(c.tela, LARGURA, ALTURA);
            ruins.addAll(miudos(c.tela, minimoDe(percentual), percentual + "% projeto/" + aba));
            if (aba == TelaProjeto.Aba.CONSUMO) {
                Visual.naEdt(() -> c.tela.consumo().subAbas().botao(PaginaConsumo.SUB_SESSOES).doClick());
                Visual.renderizar(c.tela, LARGURA, ALTURA);
                ruins.addAll(miudos(c.tela, minimoDe(percentual), percentual + "% projeto/consumo/sessões"));
            }
        }
        assertTrue(ruins.isEmpty(), "texto miúdo na página do projeto:\n" + String.join("\n", ruins));
    }

    @Test
    void paginaDoServidorSemTextoMiudoEm100() {
        conferirServidor(100);
    }

    @Test
    void listaDeProjetosSemTextoMiudoEm100() {
        conferirProjetos(100);
    }

    @Test
    void paginaDoProjetoSemTextoMiudoEm100() {
        conferirProjeto(100);
    }

    @Test
    void tudoSemTextoMiudoTambemEm130() {
        conferirServidor(130);
        conferirProjetos(130);
        conferirProjeto(130);
    }

    @Test
    void oVigiaEnxergaTextoMiudo() {
        // sanidade do próprio teste: uma fonte de 11 é pega, uma de 12 não (um vigia quebrado deixaria tudo verde para sempre)
        Visual.tema(Tema.CLARO);
        JLabel miudo = Visual.naEdt(() -> {
            JLabel l = new JLabel("pequeno");
            l.setFont(l.getFont().deriveFont(11f));
            return l;
        });
        JLabel certo = Visual.naEdt(() -> {
            JLabel l = new JLabel("legível");
            l.setFont(l.getFont().deriveFont(12f));
            return l;
        });
        assertEquals(1, miudos(miudo, 12f, "teste").size());
        assertEquals(0, miudos(certo, 12f, "teste").size());
    }

    @Test
    void osIndicadoresTemValorGrandeDe24OuMais() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = TelaServidorTest.nova();
        TelaServidorTest.alimentar(t, EstadoConexao.CONECTADO, TelaServidorTest.resumoGrande(3, 1), true);
        for (var cartao : Visual.naEdt(() -> t.resumo().faixaDeKpis().cartoes())) {
            float tamanho = Visual.naEdt(() -> TelaServidorTest.todos(cartao).stream().filter(x -> x instanceof JLabel l
                && l.getText().equals(cartao.textoDoValor())).findFirst().orElseThrow().getFont().getSize2D());
            assertTrue(tamanho >= 24f, cartao.textoDoValor() + " com " + tamanho);
        }
    }
}
