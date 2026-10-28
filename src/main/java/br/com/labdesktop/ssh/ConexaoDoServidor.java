package br.com.labdesktop.ssh;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A conexão de UM servidor, vista de quem a usa (o {@code ServidorLab}): estados, ações, o retrato e como rodar um comando. {@link ConexaoSsh}
 * é a de um servidor remoto (SSH, túnel, heartbeat); {@code ConexaoLocal} (pacote {@code local}) é a de "Este computador": sem SSH, sem túnel,
 * com o agente em 127.0.0.1. Os estados são os mesmos de {@link EstadoConexao}; no local, {@code SSH_OK_SEM_AGENTE} quer dizer "o computador
 * está aí e o agente não responde".
 *
 * <p>Tudo só enfileira (qualquer thread); {@link #executar} bloqueia: thread de fundo, nunca a EDT.
 */
public interface ConexaoDoServidor extends AutoCloseable {

    /** Começa a conectar. Sem efeito se já está em andamento. */
    void iniciar();

    /** "Tentar agora": pula a espera; com o agente fora, token recusado ou 2FA pendente só repete o teste do agente. */
    void tentarAgora();

    /** Joga fora o que há e conecta de novo do zero, com o que o cofre tem agora. */
    void reconectar();

    /** Confere agora se a conexão e o agente respondem. */
    void verificarAgora();

    /** O agente respondeu 401 sem código: o token não vale mais. */
    void informarTokenRecusado();

    /** O agente exige o código do autenticador e o usuário cancelou o pedido. */
    void informarCodigo2faPendente();

    /** O computador vai dormir. */
    void aoDormir();

    /** O computador acordou. */
    void aoAcordar();

    /** O cofre foi bloqueado: nada de rede até desbloquear. */
    void bloquear();

    /** Encerra de vez. Idempotente. */
    CompletableFuture<Void> fechar();

    @Override
    void close();

    /** O retrato atual. Sem segredo; seguro entre threads. */
    InfoConexao info();

    /** Passa a receber cada mudança (na thread da conexão: seja rápido). O primeiro retrato chega logo em seguida. Devolve o que desfaz. */
    Runnable observar(Consumer<InfoConexao> ouvinte);

    /** A ponta atual do agente, se há. */
    Optional<? extends PontaDoAgente> pontaDoAgente();

    /**
     * Roda um comando FIXO (com stdin) neste servidor. No SSH, um canal {@code exec}; no local, um processo (só as linhas de
     * comandos fixos, sem shell).
     *
     * @throws IOException se não há como rodar agora (sem sessão, programa ausente, prazo estourado)
     */
    ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException;
}
