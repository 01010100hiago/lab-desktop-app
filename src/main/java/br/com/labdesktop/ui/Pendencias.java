package br.com.labdesktop.ui;

/**
 * O que sair do app agora jogaria fora: transferências de arquivos ainda andando e arquivos abertos no editor com alterações que não foram salvas. Quem sai (fechar a janela fora do
 * Mac, "Sair", Cmd+Q) pergunta antes quando {@link #vazio()} é falso.
 */
public record Pendencias(int transferencias, int editoresComAlteracoes) {

    public static final Pendencias NENHUMAS = new Pendencias(0, 0);

    public boolean vazio() {
        return transferencias <= 0 && editoresComAlteracoes <= 0;
    }

    public Pendencias somar(Pendencias outra) {
        return new Pendencias(transferencias + outra.transferencias, editoresComAlteracoes + outra.editoresComAlteracoes);
    }

    /** A frase do aviso (texto do app, sem nada do servidor). */
    public String texto() {
        String andamento = transferencias == 1 ? "1 transferência de arquivos ainda está andando" : transferencias + " transferências de arquivos ainda estão andando";
        String editor = editoresComAlteracoes == 1 ? "1 arquivo aberto no editor tem alterações que você não salvou"
            : editoresComAlteracoes + " arquivos abertos no editor têm alterações que você não salvou";
        if (transferencias > 0 && editoresComAlteracoes > 0) {
            return andamento + " e " + editor + ". Se sair agora, as transferências param no meio e o que você digitou se perde.";
        }
        if (transferencias > 0) {
            return andamento + ". Se sair agora, ela" + (transferencias == 1 ? " para" : "s param") + " no meio.";
        }
        return editor + ". Se sair agora, o que você digitou se perde.";
    }
}
