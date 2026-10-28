package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cofre.InfoChave.Origem;
import br.com.labdesktop.servidor.GerenciadorServidores.OpcoesDeRemocao;
import br.com.labdesktop.servidor.GerenciadorServidores.ResultadoDaRemocao;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComponent;

/**
 * "Remover servidor" (a ação destrutiva repete o nome do alvo no botão, nunca "OK"; e Cancelar é o botão
 * em destaque, o que o Enter aciona). Duas escolhas, ambas marcadas por padrão:
 * <ul>
 *   <li><b>Apagar também a chave deste servidor do cofre</b>: desabilitada, com a razão escrita, se outro servidor usa a
 *       mesma chave (apagá-la quebraria o outro);</li>
 *   <li><b>Revogar o acesso deste app no servidor</b>: só com o app conectado. Apaga o token deste Mac no painel e, SÓ se
 *       foi o app que criou a chave e nenhum outro servidor a usa, tira a chave do acesso do usuário. Uma chave que o usuário
 *       importou de um arquivo (que ele também usa fora do app) nunca é tirada do servidor, e a explicação diz isso.
 *       Melhor esforço: se algo falhar, o app avisa e remove mesmo assim.</li>
 * </ul>
 * O trabalho (SSH, conexão, cofre) roda fora da EDT. Enquanto roda, o diálogo não fecha.
 */
public final class DialogoRemoverServidor extends Dialogo {

    /** Quem remove de verdade (fora da EDT): em produção, {@code GerenciadorServidores.removerServidor}. */
    @FunctionalInterface
    public interface Remocao {
        ResultadoDaRemocao remover(OpcoesDeRemocao opcoes) throws Exception;
    }

    private final String nome;
    private final Remocao remocao;
    private final Notificacoes avisos;
    private final JCheckBox apagarChave = new JCheckBox("Apagar também a chave deste servidor do cofre", true);
    private final JCheckBox revogar = new JCheckBox("Revogar o acesso deste app no servidor", true);
    private final TextoCorrido explicacaoDaChave;
    private final TextoCorrido explicacaoDaRevogacao;
    private final TextoCorrido erro = erroCorrido();
    /** O botão em destaque: o Enter e o Esc o acionam. */
    private final Botao cancelar = Botoes.primario("Cancelar", null);
    private final Botao remover;
    /** O que cada caixa pode ser quando não está ocupado: a regra de cada uma, para voltar ao normal depois de remover/falhar. */
    private final boolean podeApagarAChave;
    private final boolean podeRevogar;
    private boolean ocupado;
    private Runnable desfazerOcupado = () -> { };

    /**
     * @param nome                   nome do servidor (vai no texto do botão)
     * @param usuario                usuário do SSH, para a frase da revogação
     * @param conectado              {@code true} se há conexão SSH de pé (só então dá para revogar o acesso)
     * @param outrosQueUsamAChave    nomes dos outros servidores que usam a mesma chave; não vazio desabilita "apagar a chave"
     * @param origemDaChave          quem criou a chave deste servidor: decide o que a revogação diz que vai fazer com ela
     */
    public DialogoRemoverServidor(String nome, String usuario, boolean conectado, List<String> outrosQueUsamAChave,
                                  Origem origemDaChave, Remocao remocao, Notificacoes avisos) {
        this(nome, usuario, conectado, outrosQueUsamAChave, origemDaChave, remocao, avisos, false);
    }

    /**
     * "Remover" o servidor Este computador: sem chave nem SSH (a caixa da chave não existe), e o texto diz o que NÃO acontece: o laboratório
     * (o agente no Docker) continua rodando; para tirá-lo, "Remover laboratório local".
     */
    public static DialogoRemoverServidor paraEsteComputador(String nome, boolean conectado, Remocao remocao, Notificacoes avisos) {
        return new DialogoRemoverServidor(nome, "", conectado, List.of(), Origem.DESCONHECIDA, remocao, avisos, true);
    }

    private DialogoRemoverServidor(String nome, String usuario, boolean conectado, List<String> outrosQueUsamAChave,
                                   Origem origemDaChave, Remocao remocao, Notificacoes avisos, boolean local) {
        super("Remover servidor");
        this.nome = nome;
        this.remocao = remocao;
        this.avisos = avisos;
        this.remover = Botoes.perigo("Remover " + nome, "lixeira");

        boolean chaveCompartilhada = !outrosQueUsamAChave.isEmpty();
        this.podeApagarAChave = !chaveCompartilhada;
        this.podeRevogar = conectado;
        apagarChave.setOpaque(false);
        revogar.setOpaque(false);
        if (chaveCompartilhada) {
            apagarChave.setSelected(false);
            apagarChave.setEnabled(false);
            explicacaoDaChave = dicaCorrida("A chave fica no cofre porque " + listar(outrosQueUsamAChave)
                + " também a usa. Apagá-la tiraria o acesso " + (outrosQueUsamAChave.size() == 1 ? "dele." : "deles."));
        } else {
            explicacaoDaChave = dicaCorrida("A chave só serve a este servidor. Sem ele, ela não tem mais uso e pode ser apagada "
                + "do cofre." + (origemDaChave == Origem.GERADA_PELO_APP ? ""
                    : " Só a cópia que está no cofre: o arquivo original no seu Mac não é mexido."));
        }
        if (conectado) {
            explicacaoDaRevogacao = dicaCorrida(textoDaRevogacao(usuario, origemDaChave, chaveCompartilhada));
        } else {
            revogar.setSelected(false);
            revogar.setEnabled(false);
            explicacaoDaRevogacao = dicaCorrida("O app não está conectado a este servidor agora, então não dá para revogar o "
                + "acesso por aqui. Depois de remover, quem administra o servidor pode tirar a chave do acesso do usuário "
                + usuario + ".");
        }

        if (local) {
            apagarChave.setSelected(false);
            apagarChave.setEnabled(false);
            apagarChave.setVisible(false);
            explicacaoDaChave.setVisible(false);
            revogar.setText("Apagar o token deste app no agente local");
            explicacaoDaRevogacao.setText(conectado
                ? "O token que este app registrou no agente do laboratório local é apagado. Se algo não der certo, o app avisa e remove mesmo assim."
                : "O agente local não responde agora, então o token dele não pode ser apagado por aqui. Ele fica no agente até o laboratório ser removido.");
        }
        JComponent corpo = Caixa.coluna(14,
            cabecalho("lixeira", "Remover servidor"),
            paragrafo(local
                ? "O app vai parar de falar com " + nome + " e tirá-lo da lista. O laboratório (o agente no Docker) NÃO é removido e continua rodando: "
                    + "para tirá-lo, use \"Remover laboratório local\" antes. Seus projetos não são tocados."
                : "O app vai parar de se conectar a " + nome + " e tirá-lo da lista. O servidor em si não é mexido, "
                + "a não ser pela revogação abaixo."),
            Caixa.coluna(4, apagarChave, explicacaoDaChave),
            Caixa.coluna(4, revogar, explicacaoDaRevogacao),
            erro,
            rodape(null, cancelar, remover));
        conteudo(corpo);
        botaoPadrao(cancelar);

        cancelar.addActionListener(e -> aoCancelar());
        remover.addActionListener(e -> remover());
    }

    /**
     * O que "Revogar" faz de verdade, sem prometer o que não vai acontecer: o token deste Mac sempre sai do painel, mas a
     * chave só sai do acesso do usuário se foi o app que a criou (e nenhum outro servidor a usa). Chave de arquivo do usuário
     * fica, porque ele pode usá-la no terminal e em outras máquinas.
     */
    static String textoDaRevogacao(String usuario, Origem origem, boolean chaveCompartilhada) {
        String inicio = "Apaga o token deste Mac no painel do servidor";
        String fim = " Se algo não der certo, o app avisa e remove mesmo assim.";
        if (chaveCompartilhada) {
            return inicio + ". A chave fica no acesso do usuário " + usuario + " porque outro servidor seu também a usa." + fim;
        }
        return switch (origem) {
            case GERADA_PELO_APP -> inicio + " e tira do acesso do usuário " + usuario + " a chave que o app criou para "
                + "este servidor." + fim;
            case IMPORTADA -> inicio + ". A chave deste servidor veio de um arquivo seu, então o app NÃO a tira do acesso do "
                + "usuário " + usuario + ": você pode usá-la fora do app. Se quiser tirá-la, faça isso no servidor." + fim;
            case DESCONHECIDA -> inicio + ". O app não sabe se esta chave foi criada por ele ou veio de um arquivo seu, então "
                + "por segurança NÃO a tira do acesso do usuário " + usuario + ". Se quiser tirá-la, faça isso no servidor." + fim;
        };
    }

    private static String listar(List<String> nomes) {
        if (nomes.size() == 1) {
            return "o servidor " + nomes.get(0);
        }
        return "os servidores " + String.join(", ", nomes.subList(0, nomes.size() - 1)) + " e " + nomes.get(nomes.size() - 1);
    }

    public JCheckBox caixaDeApagarAChave() {
        return apagarChave;
    }

    public JCheckBox caixaDeRevogar() {
        return revogar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoRemover() {
        return remover;
    }

    public String textoDoBotaoRemover() {
        return remover.getText();
    }

    public String explicacaoDaChave() {
        return explicacaoDaChave.getText();
    }

    public String explicacaoDaRevogacao() {
        return explicacaoDaRevogacao.getText();
    }

    public String textoDoErro() {
        return erro.getText();
    }

    public boolean ocupado() {
        return ocupado;
    }

    private void remover() {
        if (ocupado) {
            return;
        }
        OpcoesDeRemocao opcoes = new OpcoesDeRemocao(apagarChave.isSelected(), revogar.isSelected());
        dizerErro(erro, "");
        ocupar(true);
        Tarefas.rodar(() -> remocao.remover(opcoes), resultado -> {
            ocupar(false);
            fechar();
            if (!resultado.existia()) {
                avisos.info("O servidor " + nome + " já tinha sido removido.");
                return;
            }
            avisos.ok("Servidor " + nome + " removido.");
            for (String aviso : resultado.avisos()) {
                avisos.atencao(aviso);
            }
        }, falha -> {
            ocupar(false);
            dizerErro(erro, "Não consegui remover: " + MensagensDeErro.de(falha));
            reajustar();
        });
    }

    private void ocupar(boolean sim) {
        ocupado = sim;
        if (sim) {
            desfazerOcupado = remover.ocupar();
            cancelar.setEnabled(false);
            apagarChave.setEnabled(false);
            revogar.setEnabled(false);
        } else {
            desfazerOcupado.run();
            desfazerOcupado = () -> { };
            cancelar.setEnabled(true);
            apagarChave.setEnabled(podeApagarAChave);
            revogar.setEnabled(podeRevogar);
        }
    }

    @Override
    protected void aoCancelar() {
        if (!ocupado) {
            fechar();
        }
    }
}
