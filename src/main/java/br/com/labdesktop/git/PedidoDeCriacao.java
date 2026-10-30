package br.com.labdesktop.git;

/**
 * O que o usuário decidiu na janela "Criar repositório Git".
 *
 * @param conferencia   a conferência que a janela mostrou
 * @param nome          o nome de quem assina, quando o git ainda não sabia (senão {@code null})
 * @param email         o e-mail de quem assina, quando o git ainda não sabia (senão {@code null})
 * @param incluirGrandes incluir os arquivos acima do limite de tamanho
 * @param confirmouRepositoriosAbaixo o usuário confirmou, de forma explícita, que quer criar um repositório na pasta principal MESMO com repositório(s) em subpasta(s) (o app põe essas subpastas no
 *                      {@code .gitignore}: o repositório novo não terá o código nem o histórico delas). Sem isto, o motor RECUSA criar quando há repositório abaixo, qualquer que seja a tela
 */
public record PedidoDeCriacao(ConferenciaDoRepositorio conferencia, String nome, String email, boolean incluirGrandes, boolean confirmouRepositoriosAbaixo) {

    /** Sem a confirmação (falsa): o que todo pedido sem a confirmação usa. */
    public PedidoDeCriacao(ConferenciaDoRepositorio conferencia, String nome, String email, boolean incluirGrandes) {
        this(conferencia, nome, email, incluirGrandes, false);
    }
}
