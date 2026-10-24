package br.com.labdesktop.cofre;

import java.security.KeyPair;
import java.util.List;
import java.util.Optional;

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
     * Pede para ser avisado quando o cofre for bloqueado (menu, auto-bloqueio, encerramento): é o gancho para
     * fechar canais e sessões SSH e descartar terminais (ARQUITETURA 5.6, passo 3). O {@code ouvinte} roda UMA vez,
     * numa thread qualquer, DEPOIS de os segredos terem sido zerados; se o cofre já estiver bloqueado, roda na hora.
     * Exceção lançada por um ouvinte não impede os outros. A implementação padrão não faz nada (fakes de teste).
     */
    default void aoBloquear(Runnable ouvinte) { }
}
