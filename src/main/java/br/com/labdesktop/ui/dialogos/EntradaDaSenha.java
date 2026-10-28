package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.servidor.AssistenteDeServidor.EtapaDoPrimeiroAcesso;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import javax.swing.JComponent;
import javax.swing.JPasswordField;

/**
 * A peça da SENHA do usuário no servidor, dividida pelo assistente "Adicionar servidor" e por "Instalar a chave com a senha" de um servidor que já existe
 *: o campo (que só mostra o que o usuário digitou e nunca guarda), o rótulo com o nome do usuário, a linha de andamento ("Entrando no servidor com a senha…") e a
 * tradução de cada falha em palavras simples e no que a tela faz a seguir ({@link Reacao}).
 *
 * <p>A senha vive só no campo e, no clique, vai para um {@code char[]} que passa a ser de quem o recebe: {@link #entregar()} copia e apaga o campo de verdade
 * ({@link Campos#apagarSenha}), então se algo falhar o usuário digita de novo. Nada de {@code String}, de log nem de {@code toString} com a senha.
 */
final class EntradaDaSenha {

    private final JPasswordField campo = Campos.senha(24);
    private final Rotulo rotulo = Rotulo.rotuloDeCampo("Senha do usuário do servidor");
    private final TextoCorrido andamento = new TextoCorrido("", Dialogo.LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO2).tamanho(12.5f);

    EntradaDaSenha() {
        campo.getAccessibleContext().setAccessibleName("Senha do usuário no servidor");
        andamento.setVisible(false);
    }

    /** O campo, o rótulo, a dica e a linha de andamento, em coluna; {@code depois} vai embaixo (o estado e o erro do diálogo). */
    JComponent bloco(String dica, JComponent... depois) {
        JComponent[] todos = new JComponent[4 + depois.length];
        todos[0] = rotulo;
        todos[1] = Caixa.linha(0, campo, Caixa.mola());
        todos[2] = new TextoCorrido(dica, Dialogo.LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
        todos[3] = andamento;
        System.arraycopy(depois, 0, todos, 4, depois.length);
        return Caixa.coluna(6, todos);
    }

    /** O rótulo com o nome do usuário: "Senha do usuário labuser no servidor (usada só agora, não fica guardada)". */
    void rotularPara(String usuario) {
        rotulo.setText("Senha do usuário " + usuario + " no servidor (usada só agora, não fica guardada)");
    }

    JPasswordField campo() {
        return campo;
    }

    String rotuloDoCampo() {
        return rotulo.getText();
    }

    /** Quantos caracteres há no campo (a cópia que o campo entrega é zerada na hora). */
    int digitados() {
        char[] copia = campo.getPassword();
        int n = copia.length;
        Zeragem.zerar(copia);
        return n;
    }

    /** Uma cópia da senha, para quem for usá-la zerar, e o campo apagado de verdade: ele não guarda mais nada. */
    char[] entregar() {
        char[] senha = campo.getPassword();
        Campos.apagarSenha(campo);
        return senha;
    }

    void apagar() {
        Campos.apagarSenha(campo);
    }

    void focar() {
        campo.requestFocusInWindow();
    }

    void habilitar(boolean sim) {
        campo.setEnabled(sim);
    }

    void mostrarAndamento(EtapaDoPrimeiroAcesso etapa) {
        andamento.setText(etapa.rotulo() + "…");
        andamento.setVisible(true);
    }

    void esconderAndamento() {
        andamento.setVisible(false);
    }

    /** A frase de andamento que aparece agora, ou vazio. */
    String andamento() {
        return andamento.isVisible() ? andamento.getText() : "";
    }

    /**
     * O que fazer com uma falha da entrada com senha: a frase para o usuário e o que a tela faz a seguir.
     *
     * @param mensagem            o que o usuário lê
     * @param mostrarOCaminhoSemSenha o servidor não aceita senha ou não honrou a chave: no assistente abre as "Opções avançadas" (a chave pública e o comando)
     * @param esquecerOHost       a chave do servidor não era a confirmada: nada vale mais, a conferência tem de ser refeita
     * @param focarNaSenha        o usuário pode digitar de novo (senha errada, conta bloqueada, senha vencida, pausa)
     * @param parar               a tela não oferece tentar de novo (host que mudou, sem impressão, sem senha no servidor): o caminho é outro
     */
    record Reacao(String mensagem, boolean mostrarOCaminhoSemSenha, boolean esquecerOHost, boolean focarNaSenha, boolean parar) {

        static Reacao de(Throwable falha) {
            if (!(falha instanceof FalhaDeEntradaPorSenha f)) {
                return new Reacao(MensagensDeErro.de(falha), false, false, false, false);
            }
            return switch (f.motivo()) {
                case SERVIDOR_SEM_SENHA, SEM_LOGIN_POR_CHAVE -> new Reacao(f.getMessage(), true, false, false, f.motivo() == FalhaDeEntradaPorSenha.Motivo.SERVIDOR_SEM_SENHA);
                case HOST_MUDOU, SEM_IMPRESSAO_CONFIRMADA -> new Reacao(f.getMessage(), false, true, false, true);
                case SENHA_INCORRETA, CONTA_BLOQUEADA, SENHA_EXPIRADA, MUITAS_TENTATIVAS -> new Reacao(f.getMessage(), false, false, true, false);
                default -> new Reacao(f.getMessage(), false, false, false, false);
            };
        }
    }
}
