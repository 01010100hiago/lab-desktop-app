package br.com.labdesktop.ui.componentes;

/**
 * Quem mostra mensagens rápidas ao usuário (os "toasts" do painel web: "projeto: no ar", "Erro em x: ..."). Existe como
 * interface para as telas e os diálogos não conhecerem a janela, e para os testes gravarem o que foi dito.
 * Sempre chamado na EDT.
 */
public interface Notificacoes {

    void ok(String texto);

    void info(String texto);

    void atencao(String texto);

    void erro(String texto);

    /** Uma mensagem com um botão de ação ("Tentar de novo"). */
    void comAcao(String texto, String rotuloDaAcao, Runnable acao);
}
