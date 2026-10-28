package br.com.labdesktop.servidor;

/**
 * O que o app descobriu, por uma LEITURA por SSH (nunca escrita), sobre o agente de um servidor que está de pé por SSH mas não
 * responde pelo túnel. É o que separa "o agente ainda não foi instalado" (o app oferece instalar, sem nunca instalar sozinho) de
 * "o agente está lá e não responde" (reiniciando, travado).
 */
public enum SituacaoDoAgente {
    /** Ainda não se perguntou ao servidor (ou o agente responde e a pergunta não vale). */
    DESCONHECIDA,
    /** Não há container do agente neste servidor (ou nem há Docker): é o caso do "Instalar agente". */
    NAO_INSTALADO,
    /** O container do agente existe e não responde pelo túnel. */
    INSTALADO_SEM_RESPOSTA,
    /** Há Docker, mas o usuário do app não pode usá-lo (falta o grupo docker): não dá para saber se o agente existe. */
    SEM_PERMISSAO_NO_DOCKER;

    /** O app deve oferecer "Instalar agente" (o verbo é do usuário: nunca se instala sozinho). */
    public boolean oferecerInstalar() {
        return this == NAO_INSTALADO || this == SEM_PERMISSAO_NO_DOCKER;
    }
}
