package br.com.labdesktop.registro;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Uma linha do registro do servidor: TEXTO e nada mais (nunca HTML), já limpa (controles e bidi fora, nenhum token) e com no máximo
 * {@value InterpretadorDeRegistro#LIMITE_DA_LINHA} caracteres.
 *
 * @param hora      quando aconteceu; {@code null} se a linha não traz hora (continuação de uma mensagem)
 * @param origem    quem escreveu (o programa, como {@code sshd} ou {@code kernel}; o container, no registro do laboratório local); pode ser vazia
 * @param mensagem  o texto
 * @param severidade estimativa pelo texto
 */
public record LinhaDeRegistro(Instant hora, String origem, String mensagem, Severidade severidade) {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    public LinhaDeRegistro {
        origem = origem == null ? "" : origem;
        mensagem = mensagem == null ? "" : mensagem;
        severidade = severidade == null ? Severidade.INFO : severidade;
    }

    /** A hora como {@code HH:mm:ss} no fuso dado; o traço se a linha não tem hora. */
    public String horaEmTexto(ZoneId fuso) {
        return hora == null ? "—" : HORA.format(hora.atZone(fuso));
    }

    /** A linha como o usuário a copia: {@code HH:mm:ss  origem  mensagem} (duas espaços entre os campos; sem a origem, só a hora e a mensagem). */
    public String paraCopiar(ZoneId fuso) {
        return horaEmTexto(fuso) + "  " + (origem.isEmpty() ? "" : origem + "  ") + mensagem;
    }
}
