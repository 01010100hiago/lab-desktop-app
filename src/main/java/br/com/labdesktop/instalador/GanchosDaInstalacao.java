package br.com.labdesktop.instalador;

/**
 * O que o instalador pede ao resto do app e não sabe fazer sozinho: falar com o agente recém-subido pelo túnel (o instalador só roda
 * comandos) e registrar o token deste Mac (o token vive no cofre, que o instalador não conhece). Quem monta o instalador (o
 * {@code ServidorLab}) os liga; os testes usam um fake roteirizado.
 */
public interface GanchosDaInstalacao {

    /** O que o {@code GET /api/versao} pelo túnel respondeu. */
    enum Sonda {
        /** 200: o agente está de pé e o token do app (se há) vale. */
        RESPONDE,
        /** 401 sem código: o agente está de pé, mas não conhece o token do app (agente novo, dados novos). */
        TOKEN_RECUSADO,
        /** Sem resposta (conexão recusada, erro de rede, 5xx): ainda subindo, ou parado. */
        NAO_RESPONDE
    }

    /** Pergunta ao agente pelo túnel. Bloqueia; pode ser chamada várias vezes (o instalador insiste até o prazo). */
    Sonda sondar() throws InterruptedException;

    /**
     * Pode este servidor receber uma instalação, atualização ou remoção? Se NÃO, devolve a frase (em português simples) e o instalador não escreve nada: o
     * servidor é protegido, ou um agente de perfil {@code lab}, instalado fora do app, está respondendo pelo túnel. Só LÊ. Vazio: pode seguir.
     * O padrão (sem proteção) deixa passar: é o que os testes e "Este computador" usam.
     */
    default java.util.Optional<String> recusaDeEscrita() throws InterruptedException {
        return java.util.Optional.empty();
    }

    /**
     * Registra o token deste Mac no agente que acabou de subir (ou o substitui, se o agente não o reconhece). Não faz nada se o token
     * já vale.
     *
     * @param docker o comando do Docker em uso no servidor ({@code docker} ou {@code sudo -n docker}), para o {@code docker exec}
     * @return {@code true} se registrou agora; {@code false} se o token já valia
     * @throws Exception o registro falhou (a mensagem vai para a tela)
     */
    boolean registrarToken(ParametrosDaInstalacao parametros, String docker) throws Exception;
}
