package br.com.labdesktop.seguranca;

/**
 * As duas "dobras" de caractere que a régua da senha-mestra usa para comparar a senha com a lista de senhas comuns,
 * sempre caractere por caractere (a senha nunca vira {@code String}):
 * <ul>
 *   <li><b>plana</b>: minúscula e sem acento ("Ação" fica "acao");</li>
 *   <li><b>leet</b>: a plana mais a troca de dígitos e símbolos pelas letras que eles imitam ("P@ssw0rd" fica
 *       "password"). A palavra da lista passa pela mesma dobra, então a comparação não precisa adivinhar se um "1"
 *       é "i" ou "l": os dois caem na mesma letra ("i"), dos dois lados.</li>
 * </ul>
 */
final class Dobra {

    private static final String COM_ACENTO = "àáâãäåèéêëìíîïòóôõöùúûüçñý";
    private static final String SEM_ACENTO = "aaaaaaeeeeiiiiooooouuuucny";
    /** As letras para as quais existe um dígito ou símbolo que as imita (as que entram no cálculo de variações de leet). */
    private static final String LETRAS_COM_SUBSTITUTO = "abceilostxzg";

    private Dobra() { }

    /** Minúscula e sem acento. */
    static char plano(char c) {
        char m = Character.toLowerCase(c);
        if (m < 0x80) {
            return m;
        }
        int i = COM_ACENTO.indexOf(m);
        return i >= 0 ? SEM_ACENTO.charAt(i) : m;
    }

    /** A dobra leet de um caractere que JÁ está na forma plana. */
    static char leet(char p) {
        return switch (p) {
            case '0' -> 'o';
            case '1', '!', '|', 'l' -> 'i';
            case '3' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '7', '+' -> 't';
            case '8' -> 'b';
            case '6', '9' -> 'g';
            case '2' -> 'z';
            case '(', '{', '[', '<' -> 'c';
            case '%' -> 'x';
            default -> p;
        };
    }

    /** {@code true} para o dígito ou símbolo que imita uma letra (o "0" de "p4ssw0rd"), e só para ele. */
    static boolean ehSubstituto(char p) {
        return !Character.isLetter(p) && leet(p) != p;
    }

    /** {@code true} para a letra que tem algum dígito ou símbolo que a imita. */
    static boolean letraComSubstituto(char p) {
        return LETRAS_COM_SUBSTITUTO.indexOf(p) >= 0;
    }
}
