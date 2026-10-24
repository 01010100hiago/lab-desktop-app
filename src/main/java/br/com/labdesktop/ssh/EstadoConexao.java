package br.com.labdesktop.ssh;

/**
 * Estados de uma {@link ConexaoSsh} (ARQUITETURA 6.3). A tela mostra cada um com ícone e texto (nunca só cor).
 */
public enum EstadoConexao {
    /** Parado: ainda não iniciou, ou foi fechado. */
    DESCONECTADO,
    /** Conectando e autenticando (inclui o primeiro {@code GET /api/versao}). */
    CONECTANDO,
    /** SSH de pé, mas {@code GET /api/versao} falha (agente reiniciando). O SSH e os terminais continuam. */
    SSH_OK_SEM_AGENTE,
    /** Só depois de {@code GET /api/versao} responder 200. */
    CONECTADO,
    /** Falha transitória; mostra a tentativa e a próxima em. */
    RECONECTANDO,
    /** Chave recusada pelo servidor: UMA nova tentativa depois de 5 s e para (não disparar o fail2ban). */
    ERRO_AUTENTICACAO,
    /** Chave do host sem pin ou divergente do pin: sem retry e sem "aceitar mesmo assim". */
    ERRO_CHAVE_HOST,
    /** 401 do agente sem {@code codigo}: token errado ou revogado; oferece reprovisionar. */
    TOKEN_RECUSADO,
    /** O agente exige o código do autenticador (o servidor pede o código) e o usuário ainda não digitou. */
    AGUARDANDO_2FA,
    /** Cofre bloqueado: sessão fechada, nada de rede. */
    BLOQUEADO_COFRE,
    /** Mac dormindo. */
    SUSPENSO;

    /** {@code true} nos estados em que existe (ou pode existir) uma sessão SSH autenticada para rodar {@code exec}. */
    public boolean permiteExec() {
        return this == CONECTADO || this == SSH_OK_SEM_AGENTE || this == TOKEN_RECUSADO || this == AGUARDANDO_2FA;
    }

    /** {@code true} quando o Sondador pode consultar o agente. */
    public boolean permiteConsultas() {
        return this == CONECTADO;
    }

    /** {@code true} nos estados em que o ator NÃO tenta mais sozinho (precisa de uma ação do usuário). */
    public boolean exigeAcaoDoDono() {
        return this == ERRO_CHAVE_HOST || this == TOKEN_RECUSADO || this == AGUARDANDO_2FA
            || this == BLOQUEADO_COFRE;
    }
}
