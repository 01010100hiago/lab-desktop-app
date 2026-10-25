package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.componentes.ListaDeAtencao;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Resumo": UMA tela, sem rolagem (regra dura, com teste: cabe em 1100 x 700). De cima para baixo: o cabeçalho
 * enxuto, a linha de 6 indicadores (cada um clicável, abre a aba do assunto) e, em duas colunas, as Tendências (grade
 * compacta de gráficos) e "Precisa de atenção" (no máximo 5 linhas, do mais grave ao menos grave, cada uma levando à aba do
 * assunto). Tudo em linhas dentro de uma caixa por coluna, nada de cartões empilhados.
 *
 * <p>Usa {@code /api/servidor}, o detalhe do servidor (bateria e containers sem proprietário) e a leitura de segurança (o indicador
 * e a lista de atenção precisam dela). Containers, Processos e Máquina nunca assinam a segurança.
 */
public final class PaginaResumo extends PaginaDoServidor {

    private static final long serialVersionUID = 1L;

    private final transient Consumer<Aba> aoNavegar;
    private final CabecalhoDoServidor cabecalho;
    private final FaixaDeKpis kpis = new FaixaDeKpis();
    private final BlocoDeTendencias tendencias = new BlocoDeTendencias();
    private final ListaDeAtencao atencao = new ListaDeAtencao();
    private final CaixaDoServidor caixaDeAtencao = new CaixaDoServidor("Precisa de atenção", "alerta");

    /**
     * @param aoNavegar abre uma aba da página (os indicadores e as linhas de atenção a chamam)
     */
    public PaginaResumo(ServidorLab servidor, Runnable aoAtualizar, Consumer<Aba> aoNavegar) {
        super(servidor, aoAtualizar);
        this.aoNavegar = aoNavegar;
        this.cabecalho = new CabecalhoDoServidor(aoAtualizar == null ? () -> { } : aoAtualizar);
        kpis.ligar(Map.of("seguranca", Aba.SEGURANCA.rotulo(), "containers", Aba.CONTAINERS.rotulo(), "cpu", Aba.PROCESSOS.rotulo(),
            "memoria", Aba.PROCESSOS.rotulo(), "disco", Aba.MAQUINA.rotulo()), this::abrirDoIndicador);
        iniciar();
    }

    @Override
    boolean usaServidor() {
        return true;
    }

    @Override
    boolean usaDetalhe() {
        return true;
    }

    @Override
    boolean usaSeguranca() {
        return true;
    }

    @Override
    JComponent montarTopo() {
        return cabecalho;
    }

    @Override
    JComponent montarConteudo() {
        caixaDeAtencao.linha(atencao, 0);
        JPanel colunas = new Painel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        g.gridx = 0;
        g.weightx = 0.62;
        g.insets = new Insets(0, 0, 0, 7);
        colunas.add(tendencias, g);
        g.gridx = 1;
        g.weightx = 0.38;
        g.insets = new Insets(0, 7, 0, 0);
        colunas.add(caixaDeAtencao, g);
        JPanel conteudo = new Painel(new java.awt.BorderLayout(0, 12)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        conteudo.add(kpis, java.awt.BorderLayout.NORTH);
        conteudo.add(colunas, java.awt.BorderLayout.CENTER);
        return conteudo;
    }

    @Override
    void atualizarTopo() {
        cabecalho.atualizar(estado, info, ultimaLeitura, fuso());
    }

    @Override
    void atualizarConteudo() {
        kpis.atualizar(ModeloDeKpis.de(info, seguranca));
        tendencias.atualizar(info);
        List<ListaDeAtencao.Item> itens = new ArrayList<>();
        for (ModeloDeAtencao.Atencao a : ModeloDeAtencao.de(info, detalhe, seguranca)) {
            itens.add(new ListaDeAtencao.Item(a.gravidade(), a.texto(), a.destino().rotulo(), () -> aoNavegar.accept(a.destino())));
        }
        atencao.definir(itens);
        caixaDeAtencao.apoio(Rotulo.dica("clique para abrir"));
        caixaDeAtencao.terminar();
    }

    private void abrirDoIndicador(String chave) {
        Aba destino = switch (chave) {
            case "seguranca" -> Aba.SEGURANCA;
            case "containers" -> Aba.CONTAINERS;
            case "cpu", "memoria" -> Aba.PROCESSOS;
            case "disco" -> Aba.MAQUINA;
            default -> null;
        };
        if (destino != null) {
            aoNavegar.accept(destino);
        }
    }

    // ---- para os testes ----

    FaixaDeKpis faixaDeKpis() {
        return kpis;
    }

    BlocoDeTendencias blocoDeTendencias() {
        return tendencias;
    }

    ListaDeAtencao listaDeAtencao() {
        return atencao;
    }

    CabecalhoDoServidor cabecalho() {
        return cabecalho;
    }
}
