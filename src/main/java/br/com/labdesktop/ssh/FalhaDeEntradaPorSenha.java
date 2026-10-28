package br.com.labdesktop.ssh;

/**
 * Por que a entrada com senha (ou a prova da chave que vem logo depois) não deu certo, já em português simples e sem nenhum segredo. A mensagem é
 * a que o usuário lê; o {@link Motivo} é o que a tela usa para escolher o caminho (tentar de novo, mostrar a chave pública, conferir o endereço).
 * Nunca guarda a senha, nem na mensagem, nem na causa.
 */
public final class FalhaDeEntradaPorSenha extends Exception {

    private static final long serialVersionUID = 1L;

    /** O que aconteceu. */
    public enum Motivo {
        /** O servidor respondeu "senha errada". */
        SENHA_INCORRETA,
        /** O servidor não oferece login por senha (só chave): cai no caminho de colar a chave pública. */
        SERVIDOR_SEM_SENHA,
        /** O servidor largou a conexão depois da senha: conta bloqueada ou limite de tentativas do servidor. */
        CONTA_BLOQUEADA,
        /** A senha venceu: o servidor pede para trocá-la antes. */
        SENHA_EXPIRADA,
        /** A chave do servidor não é a que o usuário confirmou: nada foi enviado. */
        HOST_MUDOU,
        /** O servidor está de pé mas não aceitou a chave: não permite login por chave (ou ela não foi lida). */
        SEM_LOGIN_POR_CHAVE,
        /** Sem conexão (endereço, porta, rede, tempo). */
        SEM_CONEXAO,
        /** O comando que instala a chave não terminou bem. */
        FALHOU_AO_INSTALAR,
        /** Pausa de proteção: tentativas demais seguidas. */
        MUITAS_TENTATIVAS,
        /** Um servidor que já existe e ainda não tem impressão digital confirmada: o app não manda senha a quem não conferiu. */
        SEM_IMPRESSAO_CONFIRMADA,
        /** A senha entrou e a chave foi gravada, mas a conexão de prova caiu num servidor com outra chave de host (a senha JÁ foi aceita, a chave JÁ está no servidor confirmado). */
        HOST_DA_PROVA_DIFERENTE
    }

    private final Motivo motivo;

    public FalhaDeEntradaPorSenha(Motivo motivo, String mensagem) {
        super(mensagem);
        this.motivo = motivo;
    }

    public FalhaDeEntradaPorSenha(Motivo motivo, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
