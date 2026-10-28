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
    SEM_PERMISSAO_NO_DOCKER,
    /** "Este computador": o Docker não está instalado (o botão é "Preparar este computador"). */
    LOCAL_SEM_DOCKER,
    /** "Este computador": o Docker está instalado e parado. */
    LOCAL_DOCKER_PARADO,
    /** "Este computador": o Docker roda, mas o laboratório local ainda não foi criado (não há o container do agente). */
    LOCAL_NAO_CRIADO,
    /** "Este computador" (Linux): o Docker está instalado, mas este usuário não pode usá-lo (fora do grupo docker): não é "Docker parado". */
    LOCAL_SEM_PERMISSAO,
    /** "Este computador": o container do agente existe e está PARADO (saiu, foi parado): recriá-lo é o "Preparar este computador". */
    LOCAL_CONTAINER_PARADO;

    /** Em "Este computador" falta preparar algo (Docker, laboratório): o botão é "Preparar este computador". */
    public boolean oferecerPreparar() {
        return this == LOCAL_SEM_DOCKER || this == LOCAL_DOCKER_PARADO || this == LOCAL_NAO_CRIADO || this == LOCAL_SEM_PERMISSAO
            || this == LOCAL_CONTAINER_PARADO;
    }

    /** O app deve oferecer "Instalar agente" (o verbo é do usuário: nunca se instala sozinho). */
    public boolean oferecerInstalar() {
        return this == NAO_INSTALADO || this == SEM_PERMISSAO_NO_DOCKER || oferecerPreparar();
    }
}
