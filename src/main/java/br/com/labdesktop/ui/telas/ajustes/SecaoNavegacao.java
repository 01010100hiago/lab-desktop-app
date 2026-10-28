package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Teclas;
import br.com.labdesktop.ui.telas.ajustes.BlocoDeAbas.AbaConhecida;
import br.com.labdesktop.ui.telas.TelaProjeto;
import br.com.labdesktop.ui.telas.TelaServidor;
import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.List;

/**
 * Ajustes, Navegação: o usuário põe as abas da página do servidor (Resumo, Segurança, Containers...) e da página do projeto (Visão geral, Logs, Terminal...) na ordem em que
 * trabalha e esconde as que não usa. Vale para todos os servidores e projetos, é lembrado entre uma abertura e outra e muda na hora. Os atalhos {@code ⌘1, ⌘2...} contam
 * as abas na ordem daqui.
 */
final class SecaoNavegacao extends Painel {

    private static final long serialVersionUID = 1L;

    private final BlocoDeAbas servidor;
    private final BlocoDeAbas projeto;

    SecaoNavegacao(ControleDeAjustes controle) {
        super(new BorderLayout());
        List<AbaConhecida> doServidor = Arrays.stream(TelaServidor.Aba.values()).map(a -> new AbaConhecida(a.id(), a.rotulo())).toList();
        List<AbaConhecida> doProjeto = Arrays.stream(TelaProjeto.Aba.values()).map(a -> new AbaConhecida(a.id(), a.rotulo())).toList();
        servidor = new BlocoDeAbas("servidor", doServidor, () -> controle.preferencias().abasDoServidor(), nova -> {
            controle.atualizarPreferencias(p -> p.comAbasDoServidor(nova));
            controle.aoMudarAsAbas();
        }, controle::abaAbertaDoServidor);
        projeto = new BlocoDeAbas("projeto", doProjeto, () -> controle.preferencias().abasDoProjeto(), nova -> {
            controle.atualizarPreferencias(p -> p.comAbasDoProjeto(nova));
            controle.aoMudarAsAbas();
        }, controle::abaAbertaDoProjeto);
        add(Paginas.coluna(12,
            Paginas.grupo("Abas do servidor"),
            Paginas.ajuda("A barra de cima da página de cada servidor. Use as setas para mudar a ordem e desmarque o que você não usa. Algumas abas só aparecem quando o servidor tem o "
                + "recurso (por exemplo, Segurança não existe em Este computador), mesmo marcadas aqui."),
            servidor,
            Caixa.espacoVertical(10),
            Paginas.grupo("Abas do projeto"),
            Paginas.ajuda("A barra de cima da página de cada projeto."),
            projeto,
            Caixa.espacoVertical(6),
            Paginas.ajuda("Atalhos: " + Teclas.nome() + "+1 abre a primeira aba do servidor que aparece na barra, " + Teclas.nome() + "+2 a segunda, e assim por diante até "
                + Teclas.nome() + "+9. Eles seguem a ordem que você deixar aqui. A aba que está aberta nunca some da barra.")), BorderLayout.NORTH);
        getAccessibleContext().setAccessibleName("Navegação");
    }

    /** Refaz as linhas (a aba aberta pode ter mudado desde a última vez que esta seção apareceu). */
    void atualizar() {
        servidor.atualizar();
        projeto.atualizar();
    }

    BlocoDeAbas blocoDoServidor() {
        return servidor;
    }

    BlocoDeAbas blocoDoProjeto() {
        return projeto;
    }
}
