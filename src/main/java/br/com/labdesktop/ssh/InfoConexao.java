package br.com.labdesktop.ssh;

import java.time.Instant;

/**
 * Retrato imutável de uma {@link ConexaoSsh}, entregue a quem a observa. Sem segredo.
 *
 * @param estado             onde a máquina de estados está
 * @param falha              última causa de falha (pode acompanhar RECONECTANDO e os estados de erro); {@code null} quando está bem
 * @param tentativa          número da tentativa de reconexão em curso (0 quando conectado)
 * @param proximaTentativaEm quando a próxima tentativa automática dispara; {@code null} se não há uma agendada
 * @param semConexaoDesde    desde quando não há conexão (para "sem conexão desde HH:MM"); {@code null} se há
 * @param geracao            cresce a cada túnel novo (o {@code ClienteApi} é por geração)
 * @param portaLocal         porta efêmera do túnel em 127.0.0.1; {@code null} sem túnel
 */
public record InfoConexao(
    EstadoConexao estado,
    Falha falha,
    int tentativa,
    Instant proximaTentativaEm,
    Instant semConexaoDesde,
    long geracao,
    Integer portaLocal) {

    public static InfoConexao inicial() {
        return new InfoConexao(EstadoConexao.DESCONECTADO, null, 0, null, null, 0, null);
    }
}
