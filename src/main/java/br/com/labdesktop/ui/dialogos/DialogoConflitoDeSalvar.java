package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import java.util.function.Consumer;
import javax.swing.JComponent;

/**
 * "O arquivo mudou no servidor desde que você abriu", na hora de salvar. Três saídas: Recarregar (traz a versão do servidor e
 * descarta o que foi digitado e não salvo), Sobrescrever mesmo assim (grava a versão do editor; a do servidor vai para uma cópia
 * no Mac) e Cancelar (não faz nada; é o botão em destaque). Também serve para "o arquivo foi apagado".
 */
public final class DialogoConflitoDeSalvar extends Dialogo {

    /** O que o usuário escolheu. */
    public enum Resposta { RECARREGAR, SOBRESCREVER, CANCELAR }

    private final Consumer<Resposta> aoDecidir;
    private final Botao cancelar = Botoes.primario("Cancelar", null);
    private final Botao recarregar = Botoes.secundario("Recarregar", "atualizar");
    private final Botao sobrescrever;
    private boolean decidiu;

    /** @param apagado o arquivo sumiu do servidor (em vez de ter mudado) */
    public DialogoConflitoDeSalvar(String nome, boolean apagado, Consumer<Resposta> aoDecidir) {
        super("O arquivo mudou no servidor");
        this.aoDecidir = aoDecidir;
        String n = TextoSeguro.limparLinha(nome);
        this.sobrescrever = Botoes.perigo(apagado ? "Salvar mesmo assim" : "Sobrescrever mesmo assim", null);
        String t = apagado
            ? "O arquivo " + n + " foi apagado do servidor desde que você abriu. Salvar mesmo assim cria o arquivo de novo com o que "
                + "está no editor. Recarregar não é possível: não há mais o que ler."
            : "O arquivo " + n + " mudou no servidor desde que você abriu: alguém (ou algum programa) o alterou. Recarregar traz a "
                + "versão do servidor e descarta o que você digitou aqui. Sobrescrever mesmo assim grava a sua versão; a que está "
                + "no servidor vai para uma cópia no seu computador, então nada se perde.";
        JComponent corpo = Caixa.coluna(14,
            cabecalho("alerta", apagado ? "O arquivo foi apagado do servidor" : "O arquivo mudou no servidor desde que você abriu"),
            paragrafo(t),
            rodape(null, cancelar, apagado ? null : recarregar, sobrescrever));
        conteudo(corpo);
        botaoPadrao(cancelar);
        cancelar.addActionListener(e -> aoCancelar());
        recarregar.addActionListener(e -> decidir(Resposta.RECARREGAR));
        sobrescrever.addActionListener(e -> decidir(Resposta.SOBRESCREVER));
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoRecarregar() {
        return recarregar;
    }

    public Botao botaoSobrescrever() {
        return sobrescrever;
    }

    @Override
    protected void aoCancelar() {
        decidir(Resposta.CANCELAR);
    }

    private void decidir(Resposta r) {
        if (decidiu) {
            return;
        }
        decidiu = true;
        fechar();
        aoDecidir.accept(r);
    }

    @Override
    protected void aoFechar() {
        if (!decidiu) {
            decidiu = true;
            aoDecidir.accept(Resposta.CANCELAR);
        }
    }
}
