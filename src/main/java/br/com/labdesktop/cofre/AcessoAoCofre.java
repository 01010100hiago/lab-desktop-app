package br.com.labdesktop.cofre;

import java.security.KeyPair;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * O que os módulos {@code ssh}, {@code api} e {@code servidor} enxergam do cofre. É uma interface estreita de
 * propósito: esses módulos testam com um fake de 20 linhas em vez de abrir um cofre real (e pagar o Argon2id).
 * {@link CofreDeChaves} a implementa.
 *
 * <h2>Regras de uso</h2>
 * <ul>
 *   <li>Leituras podem lançar {@link CofreBloqueadoException} (não verificada) se o cofre foi bloqueado no meio.
 *       Trate como "parar de trabalhar", não como erro de tela.</li>
 *   <li>Escritas são duráveis quando retornam (o arquivo já foi regravado de forma atômica) e lançam
 *       {@link CofreException} se o disco falhar, caso em que NADA mudou na memória. Bloqueiam por ~dezenas de
 *       milissegundos: chame de thread de fundo, nunca da EDT.</li>
 *   <li>Os segredos devolvidos ({@link TokenAgente}) pertencem ao cofre: não os feche nem zere. O
 *       {@link KeyPair} é uma instância nova a cada chamada: solte a referência logo depois da autenticação.</li>
 * </ul>
 */
public interface AcessoAoCofre {

    /** Todos os servidores cadastrados, na ordem do cofre. */
    List<ConfigServidor> servidores();

    /** Um servidor pelo id interno, ou vazio. */
    Optional<ConfigServidor> servidor(String servidorId);

    /**
     * Reconstrói, em memória, o par de chaves SSH do servidor para entregar a
     * {@code ClientSession.addPublicKeyIdentity(keyPair)}. Nada vai a disco.
     *
     * @throws IllegalArgumentException se o id não existe
     * @throws CofreException           se a chave guardada não for legível
     */
    KeyPair keyPairDoServidor(String servidorId) throws CofreException;

    /** O token do cliente nativo para este servidor, ou vazio enquanto não foi registrado. */
    Optional<TokenAgente> tokenDoServidor(String servidorId);

    /**
     * Grava (ou troca, por algoritmo) o pin da chave do host, depois de o usuário conferir a impressão. Só o
     * {@code DialogoConfiarHost} (ou "redefinir pin", com a senha-mestra) deve chamar isto: o
     * {@code VerificadorDeChaveDoServidor} só COMPARA, nunca grava.
     */
    void gravarPin(String servidorId, Pin pin) throws CofreException;

    /** Apaga os pins do servidor ("redefinir pin": o host volta a ser desconhecido). */
    void limparPins(String servidorId) throws CofreException;

    /**
     * Guarda o token do cliente nativo e o {@code nome_do_dispositivo} com que ele foi registrado no agente. O
     * cofre passa a ser o responsável pelo {@code token} (zera quando ele for trocado ou o cofre bloqueado). Chame só
     * depois de registrar e testar no agente (CONTRATO-API seção 6).
     */
    void gravarToken(String servidorId, TokenAgente token, String nomeDoDispositivo) throws CofreException;

    /** Atualiza a dica do estado do 2FA lida de {@code GET /api/versao}. Sem mudança, não regrava o arquivo. */
    void gravarEstado2fa(String servidorId, Estado2fa estado) throws CofreException;

    /**
     * Aplica uma mudança à configuração do servidor (nome, endereço, porta, usuário, pins...), de forma atômica com as
     * outras escritas: o que a função recebe é a configuração de AGORA, então uma dica do 2FA gravada no meio do caminho não
     * se perde. A função decide também os pins (é onde mora a regra "mudou o endereço, apaga o pin": veja
     * {@link ConfigServidor#editar}). Devolver uma configuração igual não regrava o arquivo. O token não muda.
     *
     * @throws IllegalArgumentException se o servidor não existe, se a função mudar o id ou apontar para uma chave que não está no cofre
     */
    void alterarServidor(String servidorId, UnaryOperator<ConfigServidor> mudanca) throws CofreException;

    /**
     * Remove o servidor e zera o token dele, numa gravação só. Com {@code apagarAChaveSeLivre}, apaga também a chave SSH dele
     * (e zera a parte privada na memória) desde que nenhum OUTRO servidor a use; se outro a usa, a chave fica.
     *
     * @return {@code true} se a chave também foi apagada
     * @throws IllegalArgumentException se o servidor não existe
     */
    boolean removerServidor(String servidorId, boolean apagarAChaveSeLivre) throws CofreException;

    /** A chave SSH (sem a parte privada) pelo id, por exemplo para mostrar a pública ou tirá-la do servidor. */
    Optional<InfoChave> infoDaChave(String chaveId);

    /**
     * Como falar com o agente que este app instalou no servidor (container e sudo), ou vazio se ele nunca o instalou aqui (o agente do lab, por
     * exemplo: vale o padrão {@code docker exec -i painel-lab}). A implementação padrão não lembra nada (fakes de teste).
     */
    default Optional<AgenteInstalado> agenteInstalado(String servidorId) {
        return Optional.empty();
    }

    /** Guarda como falar com o agente instalado ({@code null} apaga: o agente foi removido). Sem mudança, não regrava o arquivo. */
    default void gravarAgenteInstalado(String servidorId, AgenteInstalado agente) throws CofreException { }

    /**
     * Pede para ser avisado quando o cofre for bloqueado (menu, auto-bloqueio, encerramento): é o gancho para
     * fechar canais e sessões SSH e descartar terminais (ARQUITETURA 5.6, passo 3). O {@code ouvinte} roda UMA vez,
     * numa thread qualquer, DEPOIS de os segredos terem sido zerados; se o cofre já estiver bloqueado, roda na hora.
     * Exceção lançada por um ouvinte não impede os outros. A implementação padrão não faz nada (fakes de teste).
     */
    default void aoBloquear(Runnable ouvinte) { }
}
