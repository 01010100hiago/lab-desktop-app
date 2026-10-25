package br.com.labdesktop.servidor;

import br.com.labdesktop.api.ErroApi;

/**
 * Quem recebe os dados de um {@link Topico}. As chamadas chegam pelo despachante de entrega do {@link Sondador}
 * (a EDT, em produção), então a tela pode tocar em componentes direto. Os valores são DTOs imutáveis.
 *
 * <p>Só chega valor NOVO: o Sondador compara com o anterior e não repete (equivalente ao {@code ultimoJson} do
 * front). Quem se inscreve depois recebe o último valor conhecido logo na inscrição.
 */
public interface OuvinteDeTopico<T> {

    /** Dado novo (ou o último conhecido, na inscrição). */
    void aoAtualizar(T valor);

    /**
     * A consulta deu certo e o valor é IGUAL ao anterior (por isso não houve {@link #aoAtualizar}). A tela que mostra "lido às"
     * usa isto para a hora não ficar velha quando nada muda. Por padrão não faz nada.
     */
    default void aoConfirmar() { }

    /**
     * A consulta falhou. {@code ErroRede} não é erro de tela: a tela mantém o último snapshot e mostra "sem
     * conexão desde HH:MM" (o estado vem de {@link EstadoServidor}). Por padrão não faz nada.
     */
    default void aoFalhar(ErroApi erro) { }
}
