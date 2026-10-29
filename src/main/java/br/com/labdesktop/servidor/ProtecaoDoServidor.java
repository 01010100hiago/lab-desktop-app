package br.com.labdesktop.servidor;

/**
 * "Servidor protegido": os textos e a guarda única da regra "o app nunca instala, atualiza nem remove nada neste servidor".
 *
 * <p>A regra tem DUAS camadas, de propósito. A primeira é o que a tela OFERECE: nenhum botão nem item de menu de instalar, atualizar ou remover
 * (o agente, o Docker e o Git) aparece num servidor protegido; quem decide é {@link EstadoServidor#ofereceInstalarAgente} e
 * {@link EstadoServidor#ofereceAtualizarERemoverAgente}. A segunda é a DEFESA: mesmo que alguma tela falhe e chame a ação, {@link ServidorLab#instaladorDoAgente},
 * {@link ServidorLab#instaladorDeGit} e as ações de instalar, atualizar e remover da interface chamam {@link #exigirLivre} e recusam com {@link #RECUSA}.
 *
 * <p>Protegido vale por duas razões: o usuário marcou (cofre, {@code ConfigServidor.protegido}), ou o app viu respondendo o agente do lab, instalado fora
 * dele (por exemplo por um script de publicação), e marcou sozinho. "Este computador" nunca é protegido.
 */
public final class ProtecaoDoServidor {

    /** A recusa, igual em toda camada. Em português simples, sem a palavra "erro". */
    public static final String RECUSA = "Este servidor é protegido: o app não instala, atualiza nem remove nada aqui. Para mudar, abra Editar servidor.";

    /** O aviso, uma única vez, quando o app marca sozinho ao ver o agente do lab instalado fora dele. */
    public static final String AVISO_DA_MARCA_AUTOMATICA = "Este servidor já tem o agente do lab, instalado fora do app. Marquei como protegido: o app não vai "
        + "instalar nada aqui. Para desfazer, abra Editar servidor.";

    /** O texto da caixa de marcar (Adicionar e Editar servidor). */
    public static final String ROTULO_DA_CAIXA = "Servidor protegido";

    /** A frase embaixo da caixa. */
    public static final String FRASE_DA_CAIXA = "O app nunca instala, atualiza nem remove nada neste servidor. Use para servidores que já estão em produção.";

    /** A explicação curta do selo "Protegido" (dica ao pousar o mouse e linha da página do servidor). */
    public static final String EXPLICACAO_DO_SELO = "Servidor protegido: o app não instala, atualiza nem remove nada aqui (agente, Docker ou Git). "
        + "Para mudar, abra Editar servidor.";

    /** A recusa quando um agente do lab, instalado fora do app, está respondendo neste servidor (mesmo sem a marca de protegido). */
    public static final String RECUSA_DO_AGENTE_DE_FORA = "Este servidor já tem o agente do lab, instalado fora do app: o app não instala, atualiza nem remove "
        + "nada aqui.";

    /** O que o aviso "agente não instalado" diz no lugar do botão Instalar agente. */
    public static final String TEXTO_DO_AVISO_SEM_AGENTE = "O servidor responde por SSH e não tem o agente. Ele é protegido: o app não instala nada aqui. "
        + "Para mudar, abra Editar servidor. Segurança, Arquivos e Terminal funcionam do mesmo jeito.";

    /** Idem, quando o motivo é a falta de permissão no Docker (o app nem sabe se o agente existe). */
    public static final String TEXTO_DO_AVISO_SEM_PERMISSAO = "O servidor responde por SSH, mas este usuário não pode usar o Docker, então o app não sabe se o agente "
        + "está instalado. O servidor é protegido: o app não instala nada aqui. Para mudar, abra Editar servidor.";

    /** O Git não está instalado e o servidor é protegido. */
    public static final String TEXTO_DO_GIT = "O git não está instalado neste servidor, e sem ele o app não clona repositórios. O servidor é protegido: o app não instala "
        + "nada aqui. Instale o git você mesmo (por exemplo no Terminal) ou abra Editar servidor para mudar.";

    /** A recusa de uma ação de escrita de instalação em servidor protegido. Sem pilha: a mensagem é para o usuário ler. */
    public static final class ServidorProtegidoException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ServidorProtegidoException() {
            super(RECUSA);
        }
    }

    private ProtecaoDoServidor() { }

    /**
     * A guarda única: recusa se o servidor é protegido.
     *
     * @throws ServidorProtegidoException se {@code protegido}
     */
    public static void exigirLivre(boolean protegido) {
        if (protegido) {
            throw new ServidorProtegidoException();
        }
    }
}
