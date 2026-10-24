package br.com.labdesktop.servidor;

/**
 * Em que pé está a janela do app, do ponto de vista de quanto vale consultar o servidor (ARQUITETURA 6.7).
 *
 * <p>Janela MINIMIZADA conta como {@link #ESCONDIDA}, não como sem foco: minimizada, o quadro continua "visível" para o
 * Swing ({@code isShowing()}) e as telas não soltariam a assinatura, mas ninguém está olhando, e cada
 * {@code /api/projetos} custa um {@code docker ps} mais um {@code docker exec} por sessão aberta num servidor que já anda
 * perto de 87 °C. O comportamento é este: com a janela minimizada, só {@code /api/limites}, a cada
 * 60 s.
 */
public enum EstadoJanela {
    /** Visível e em foco: cadência cheia (10 s para os tópicos de tela). */
    ATIVA,
    /** Visível mas sem foco: cadência ×6 (60 s). Preferência pode manter a cheia. */
    SEM_FOCO,
    /**
     * Fechada para a barra de menus OU minimizada: só o que alimenta faixa de crédito, ícone e notificações
     * ({@code LIMITES}).
     */
    ESCONDIDA;

    /**
     * A regra única, sem Swing (para a janela e para os testes).
     *
     * @param visivel    a janela está na tela (não foi escondida pelo fechar)
     * @param minimizada está no Dock
     * @param ativa      tem o foco do teclado
     */
    public static EstadoJanela de(boolean visivel, boolean minimizada, boolean ativa) {
        if (!visivel || minimizada) {
            return ESCONDIDA;
        }
        return ativa ? ATIVA : SEM_FOCO;
    }
}
