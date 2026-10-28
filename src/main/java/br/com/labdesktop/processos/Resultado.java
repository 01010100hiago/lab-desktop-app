package br.com.labdesktop.processos;

/**
 * O resultado de uma ação sobre um processo, claro e sem segredo.
 *
 * @param mensagem         a frase para o usuário (nunca vazia)
 * @param comandoParaODono sem permissão: o comando exato para o usuário rodar sozinho ({@code ""} nos outros casos)
 */
public record Resultado(Desfecho desfecho, String mensagem, String comandoParaODono) {

    public Resultado {
        mensagem = mensagem == null || mensagem.isBlank() ? desfecho.rotulo() + "." : mensagem;
        comandoParaODono = comandoParaODono == null ? "" : comandoParaODono;
    }

    public static Resultado de(Desfecho d, String mensagem) {
        return new Resultado(d, mensagem, "");
    }
}
