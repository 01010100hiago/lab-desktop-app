package br.com.labdesktop.terminal;

/**
 * Em que pé está um {@link SessaoTerminal} (ARQUITETURA 7.3). Sem segredo; seguro entre threads.
 *
 * @param tipo      o estado
 * @param tentativa em {@code REANEXANDO}, a tentativa de reanexação que vem (1 a 5)
 * @param saida     em {@code ENCERRADA}, o status de saída do programa remoto
 * @param motivo    em {@code SEM_CONEXAO}, o texto que explica por que o terminal não abriu (pode ser {@code null})
 */
public record EstadoDoTerminal(Tipo tipo, int tentativa, Integer saida, String motivo) {

    public enum Tipo {
        /** Abrindo o canal pela primeira vez (ou pelo botão "Reconectar"). */
        ABRINDO,
        /** Canal aberto e ligado ao terminal: as teclas chegam ao servidor. */
        ATIVA,
        /** O canal caiu: tentando reanexar sozinho (2, 4, 8, 16 e 30 s). As teclas são descartadas. */
        REANEXANDO,
        /** As cinco tentativas acabaram (ou o servidor recusou abrir): falta o botão "Reconectar". */
        SEM_CONEXAO,
        /** A sessão do Claude não existe no servidor (container ou tmux ausentes). Sem laço de reanexação. */
        SESSAO_FECHADA,
        /** O programa remoto terminou sozinho (sessão do tmux acabou, ou o {@code exit} do shell). */
        ENCERRADA,
        /** O cofre foi bloqueado: terminal descartado, canal fechado. */
        BLOQUEADA
    }

    public static final EstadoDoTerminal ABRINDO = new EstadoDoTerminal(Tipo.ABRINDO, 0, null, null);
    public static final EstadoDoTerminal ATIVA = new EstadoDoTerminal(Tipo.ATIVA, 0, null, null);
    public static final EstadoDoTerminal SESSAO_FECHADA = new EstadoDoTerminal(Tipo.SESSAO_FECHADA, 0, null, null);
    public static final EstadoDoTerminal BLOQUEADA = new EstadoDoTerminal(Tipo.BLOQUEADA, 0, null, null);

    public static EstadoDoTerminal reanexando(int tentativa) {
        return new EstadoDoTerminal(Tipo.REANEXANDO, tentativa, null, null);
    }

    public static EstadoDoTerminal semConexao(String motivo) {
        return new EstadoDoTerminal(Tipo.SEM_CONEXAO, 0, null, motivo);
    }

    public static EstadoDoTerminal encerrada(Integer saida) {
        return new EstadoDoTerminal(Tipo.ENCERRADA, 0, saida, null);
    }

    public boolean ativa() {
        return tipo == Tipo.ATIVA;
    }

    public boolean reanexando() {
        return tipo == Tipo.REANEXANDO;
    }
}
