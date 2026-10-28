package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import javax.swing.JComponent;
import javax.swing.border.EmptyBorder;

/**
 * A primeira pergunta de "Adicionar servidor" (e da primeira tela do app): <b>Este computador</b> (o laboratório roda no próprio computador,
 * sem SSH, sem chave e sem túnel) ou <b>Outro servidor (SSH)</b> (o assistente de sempre). Duas opções claras, na mesma janela; nada é
 * instalado nem criado por escolher: "Este computador" leva à tela "Preparar este computador", que só confere e pergunta.
 */
public final class DialogoEscolherServidor extends Dialogo {

    private final Botao este;
    private final Botao outro = Botoes.secundario("Conectar por SSH", "rede");
    private final Botao cancelar = Botoes.fantasma("Cancelar", null);
    private final Runnable aoEscolherEsteComputador;
    private final Runnable aoEscolherOutroServidor;

    /**
     * @param esteComputadorJaExiste o servidor "Este computador" já está na lista (só existe um): a opção vira informação, sem botão
     */
    public DialogoEscolherServidor(boolean esteComputadorJaExiste, Runnable aoEscolherEsteComputador, Runnable aoEscolherOutroServidor) {
        super("Adicionar servidor");
        this.aoEscolherEsteComputador = aoEscolherEsteComputador;
        this.aoEscolherOutroServidor = aoEscolherOutroServidor;
        this.este = esteComputadorJaExiste ? Botoes.secundario("Já está na lista", "ok") : Botoes.primario("Usar este computador", "sistema");
        este.setEnabled(!esteComputadorJaExiste);

        TextoCorrido pergunta = new TextoCorrido("Onde vão rodar os seus projetos?", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO2);
        JComponent opcaoEste = opcao("sistema", "Este computador",
            "O laboratório roda aqui mesmo, num container do Docker. Sem servidor, sem SSH, sem chave. O app confere o que falta (o Docker, "
                + "por exemplo) e ajuda a instalar, sempre perguntando antes: nada é instalado sozinho.", este);
        JComponent opcaoOutro = opcao("servidor", "Outro servidor (SSH)",
            "Um servidor seu na rede ou na internet (um Debian em casa, uma VPS). O app guarda a chave SSH no cofre e conecta por um túnel seguro.",
            outro);
        conteudo(Caixa.coluna(12, cabecalho("servidor", "Adicionar servidor"), pergunta, opcaoEste, opcaoOutro, rodape(null, cancelar)));
        botaoPadrao(esteComputadorJaExiste ? outro : este);

        este.addActionListener(e -> escolher(this.aoEscolherEsteComputador));
        outro.addActionListener(e -> escolher(this.aoEscolherOutroServidor));
        cancelar.addActionListener(e -> fechar());
    }

    private static JComponent opcao(String icone, String titulo, String descricao, Botao botao) {
        Painel cartao = new Painel(new java.awt.BorderLayout(0, 8)).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(10);
        cartao.setBorder(new EmptyBorder(14, 16, 14, 16));
        Rotulo t = new Rotulo(titulo).icone(Icones.colorido(icone, 18, Tokens.DESTAQUE)).negrito();
        t.setIconTextGap(8);
        cartao.add(t, java.awt.BorderLayout.NORTH);
        cartao.add(new TextoCorrido(descricao, LARGURA_DO_TEXTO - 36).corDoToken(Tokens.TEXTO2).tamanho(12.5f), java.awt.BorderLayout.CENTER);
        cartao.add(Caixa.linha(0, botao, Caixa.mola()), java.awt.BorderLayout.SOUTH);
        return cartao;
    }

    private void escolher(Runnable depois) {
        fechar();
        depois.run();
    }

    public Botao botaoEsteComputador() {
        return este;
    }

    public Botao botaoOutroServidor() {
        return outro;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }
}
