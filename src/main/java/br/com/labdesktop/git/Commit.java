package br.com.labdesktop.git;

import java.time.Instant;
import java.util.List;

/**
 * Um commit como a lista da aba Git o mostra.
 *
 * @param hash     o hash inteiro (40 caracteres)
 * @param curto    o hash curto (7 ou mais)
 * @param autor    o nome de quem fez
 * @param email    o e-mail de quem fez (pode ser vazio)
 * @param quando   a data do commit
 * @param pais     os hashes dos pais (dois ou mais é um merge)
 * @param assunto  a primeira linha da mensagem
 * @param arquivos quantos arquivos mudaram ({@code -1} se a lista não disse, como num merge sem diferenças)
 */
public record Commit(String hash, String curto, String autor, String email, Instant quando, List<String> pais, String assunto, int arquivos) {

    public Commit {
        pais = List.copyOf(pais);
        autor = autor == null ? "" : autor;
        email = email == null ? "" : email;
        assunto = assunto == null ? "" : assunto;
    }

    public boolean ehMerge() {
        return pais.size() > 1;
    }
}
