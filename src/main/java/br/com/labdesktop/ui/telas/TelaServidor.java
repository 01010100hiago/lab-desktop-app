package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.CartoesAjustaveis;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.Arrays;
import javax.swing.JPanel;

/**
 * A página de UM servidor (ARQUITETURA 8.1): duas abas no topo, "Visão geral" (a aberta de partida) e "Projetos" (a
 * {@link TelaProjetos}, sem mudar em nada). Clicar no servidor na barra lateral abre esta página. A aba escolhida fica
 * lembrada enquanto o app estiver aberto: a tela de cada servidor é criada uma vez e só escondida quando o usuário vai para
 * outro lugar, então voltar ao servidor mostra a aba em que ele estava.
 *
 * <p>Quem consulta o servidor é cada aba, ao ficar visível ({@code HierarchyListener}): a aba que não está na tela não
 * consulta nada. Uma aba escondida dentro de um {@code CardLayout} não está {@code showing}, então a regra vale sozinha.
 */
public final class TelaServidor extends Painel {

    private static final long serialVersionUID = 1L;

    /** As abas. */
    public enum Aba {
        VISAO_GERAL("visao-geral", "Visão geral"),
        PROJETOS("projetos", "Projetos");

        private final String id;
        private final String rotulo;

        Aba(String id, String rotulo) {
            this.id = id;
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }

        static Aba de(String id) {
            return Arrays.stream(values()).filter(a -> a.id.equals(id)).findFirst().orElse(VISAO_GERAL);
        }
    }

    private final ServidorLab servidor;
    private final TelaProjetos projetos;
    private final VisaoGeralDoServidor visaoGeral;
    private final Abas abas = new Abas();
    private final CardLayout cartoes = new CartoesAjustaveis();
    private final JPanel corpo = new Painel(cartoes);
    private Aba atual = Aba.VISAO_GERAL;

    /**
     * @param servidor    o servidor vivo ({@code null} nos testes com dados fixos)
     * @param projetos    a lista de projetos do servidor (a aba "Projetos")
     * @param aoAtualizar o que o botão Atualizar da visão geral faz
     */
    public TelaServidor(ServidorLab servidor, TelaProjetos projetos, Runnable aoAtualizar) {
        super(new BorderLayout());
        fundo(Tokens.BG);
        this.servidor = servidor;
        this.projetos = projetos;
        this.visaoGeral = new VisaoGeralDoServidor(servidor, aoAtualizar);

        for (Aba a : Aba.values()) {
            abas.adicionar(a.id, a.rotulo);
        }
        abas.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 12));
        abas.aoSelecionar(id -> mostrarAba(Aba.de(id)));
        corpo.add(visaoGeral, Aba.VISAO_GERAL.id);
        corpo.add(projetos, Aba.PROJETOS.id);
        add(abas, BorderLayout.NORTH);
        add(corpo, BorderLayout.CENTER);
        cartoes.show(corpo, Aba.VISAO_GERAL.id);
        getAccessibleContext().setAccessibleName("Servidor");
    }

    /** Mostra uma aba (e marca o rótulo). */
    public void mostrarAba(Aba aba) {
        atual = aba;
        abas.selecionar(aba.id);
        cartoes.show(corpo, aba.id);
        corpo.revalidate();
        corpo.repaint();
    }

    public Aba abaAtual() {
        return atual;
    }

    public TelaProjetos projetos() {
        return projetos;
    }

    public VisaoGeralDoServidor visaoGeral() {
        return visaoGeral;
    }

    public ServidorLab servidor() {
        return servidor;
    }

    /** A aba escolhida no rótulo, para os testes conferirem que rótulo e conteúdo andam juntos. */
    public Aba abaMarcada() {
        return Aba.de(abas.selecionada());
    }

    public Abas abas() {
        return abas;
    }

    /** Solta as assinaturas das duas abas (o servidor saiu da lista ou o cofre foi bloqueado). */
    public void soltar() {
        visaoGeral.soltar();
        projetos.soltar();
    }
}
