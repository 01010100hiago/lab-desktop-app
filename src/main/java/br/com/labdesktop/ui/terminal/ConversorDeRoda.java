package br.com.labdesktop.ui.terminal;

/**
 * Traduz a roda e o trackpad em Page Up e Page Down para a TELA ALTERNATIVA (ARQUITETURA 7.4). O Claude Code desenha em tela alternativa, o
 * tmux então guarda 0 linhas de histórico e a rolagem da conversa é do próprio Claude, que responde a Page Up e Page Down; o JediTerm,
 * sem mouse reporting, ignora a roda nessa tela.
 *
 * <p>Acumula a rotação PRECISA ({@code getPreciseWheelRotation}, negativa = roda para cima, o sinal que o Swing já entrega no macOS
 * com a rolagem natural): o trackpad manda dezenas de eventos fracionados por gesto e não pode disparar dezenas de páginas. Uma
 * página por {@link #NOTCHES_POR_PAGINA} notches equivalentes, e no máximo UMA página por evento (um gesto forte não vira um salto
 * de várias páginas). Mudar de sentido recomeça a conta. Não é thread-safe: só a EDT usa.
 */
final class ConversorDeRoda {

    /** Quantos notches de roda valem uma página. */
    static final double NOTCHES_POR_PAGINA = 3.0;

    private double acumulado;

    /**
     * @param rotacaoPrecisa o {@code getPreciseWheelRotation()} do evento
     * @return {@code -1} para Page Up (a roda foi para cima), {@code +1} para Page Down, {@code 0} se ainda não deu uma página
     */
    int aoRodar(double rotacaoPrecisa) {
        if (rotacaoPrecisa == 0 || Double.isNaN(rotacaoPrecisa)) {
            return 0;
        }
        if (acumulado != 0 && Math.signum(acumulado) != Math.signum(rotacaoPrecisa)) {
            acumulado = 0; // virou o sentido: o que sobrou do outro lado não conta
        }
        acumulado += rotacaoPrecisa;
        if (Math.abs(acumulado) < NOTCHES_POR_PAGINA) {
            return 0;
        }
        int sentido = acumulado < 0 ? -1 : 1;
        acumulado = 0; // teto de uma página por evento: o excesso de um gesto forte é descartado
        return sentido;
    }

    /** Zera o que sobrou (a tela mudou, ou o terminal foi trocado). */
    void zerar() {
        acumulado = 0;
    }
}
