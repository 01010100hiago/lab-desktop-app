package br.com.labdesktop.servidor;

import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import java.time.Instant;

/**
 * O retrato imutável de um servidor para a interface: conexão, 2FA, versão do agente e os limites do plano
 * (que alimentam a barra lateral, a faixa de crédito e o ícone da barra de menus). Sem segredo: pode ser
 * guardado e impresso à vontade.
 *
 * @param servidorId         id interno do servidor no cofre
 * @param nome               nome mostrado ao usuário
 * @param conexao            estado da conexão SSH e do agente
 * @param falha              a causa da falha atual, se houver (já limpa e redigida)
 * @param tentativa          tentativa de reconexão em curso
 * @param proximaTentativaEm quando a próxima tentativa dispara ("Tentar agora" pula a espera)
 * @param semConexaoDesde    para "sem conexão desde HH:MM"; {@code null} conectado
 * @param geracao            geração do túnel (cresce a cada reconexão)
 * @param estado2fa          a última leitura do campo {@code totp} de {@code /api/versao}
 * @param nomeDoDispositivo  nome do token deste Mac no agente; {@code null} sem token registrado
 * @param versao             última resposta de {@code /api/versao}; {@code null} antes da primeira
 * @param limites            último {@code /api/limites}; {@code null} antes da primeira leitura
 * @param situacaoDoAgente   o que a leitura por SSH disse do agente quando ele não responde (instalado ou não)
 * @param local              é o servidor "Este computador" (sem SSH): a tela fala de Docker e do laboratório local, não de SSH
 */
public record EstadoServidor(
    String servidorId,
    String nome,
    EstadoConexao conexao,
    Falha falha,
    int tentativa,
    Instant proximaTentativaEm,
    Instant semConexaoDesde,
    long geracao,
    Estado2fa estado2fa,
    String nomeDoDispositivo,
    VersaoAgente versao,
    LimitesConta limites,
    SituacaoDoAgente situacaoDoAgente,
    boolean local) {

    public EstadoServidor {
        if (situacaoDoAgente == null) {
            situacaoDoAgente = SituacaoDoAgente.DESCONHECIDA;
        }
    }

    /** Servidor remoto (por SSH): o que sempre foi. */
    public EstadoServidor(String servidorId, String nome, EstadoConexao conexao, Falha falha, int tentativa,
                          Instant proximaTentativaEm, Instant semConexaoDesde, long geracao, Estado2fa estado2fa,
                          String nomeDoDispositivo, VersaoAgente versao, LimitesConta limites, SituacaoDoAgente situacaoDoAgente) {
        this(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde, geracao, estado2fa, nomeDoDispositivo, versao,
            limites, situacaoDoAgente, false);
    }

    /** Sem a situação do agente (desconhecida): o que quase todo teste e a criação do servidor usam. */
    public EstadoServidor(String servidorId, String nome, EstadoConexao conexao, Falha falha, int tentativa,
                          Instant proximaTentativaEm, Instant semConexaoDesde, long geracao, Estado2fa estado2fa,
                          String nomeDoDispositivo, VersaoAgente versao, LimitesConta limites) {
        this(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde, geracao, estado2fa,
            nomeDoDispositivo, versao, limites, SituacaoDoAgente.DESCONHECIDA, false);
    }

    /** Copia com o retrato novo da conexão SSH. */
    EstadoServidor comConexao(br.com.labdesktop.ssh.InfoConexao info) {
        return new EstadoServidor(servidorId, nome, info.estado(), info.falha(), info.tentativa(),
            info.proximaTentativaEm(), info.semConexaoDesde(), info.geracao(), estado2fa, nomeDoDispositivo, versao,
            limites, info.estado() == EstadoConexao.SSH_OK_SEM_AGENTE ? situacaoDoAgente : SituacaoDoAgente.DESCONHECIDA, local);
    }

    EstadoServidor comVersao(VersaoAgente nova, Estado2fa novoEstado2fa) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, novoEstado2fa, nomeDoDispositivo, nova, limites, situacaoDoAgente, local);
    }

    EstadoServidor comLimites(LimitesConta novos) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeDoDispositivo, versao, novos, situacaoDoAgente, local);
    }

    EstadoServidor comEstado2fa(Estado2fa novo) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, novo, nomeDoDispositivo, versao, limites, situacaoDoAgente, local);
    }

    EstadoServidor comDispositivo(String nomeNovo) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeNovo, versao, limites, situacaoDoAgente, local);
    }

    /** Copia com o nome novo (o usuário editou o servidor). */
    EstadoServidor comNome(String nomeNovo) {
        return new EstadoServidor(servidorId, nomeNovo, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeDoDispositivo, versao, limites, situacaoDoAgente, local);
    }

    EstadoServidor comSituacaoDoAgente(SituacaoDoAgente nova) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeDoDispositivo, versao, limites, nova, local);
    }

    /** O % usado da semana, para a barra lateral e o ícone; {@code null} sem leitura que valha. */
    public Double pctDaSemana() {
        return limites == null || !limites.leituraUtil() || limites.semana() == null ? null : limites.semana().usadoPct();
    }

    /** Quantas sessões estão pausadas (teto ou freio). */
    public int sessoesPausadas() {
        return limites == null ? 0 : limites.pausados().size();
    }

    /** O agente fala outra versão do contrato (a tela avisa "agente incompatível"). */
    public boolean agenteIncompativel() {
        return versao != null && !versao.compativel();
    }

    /**
     * O servidor tem sessões do Claude, segundo o agente ({@code recursos.claude} de {@code /api/versao}); {@code null} quando o agente
     * não disse (agente antigo, ou ainda não respondeu): quem pergunta decide o que assume.
     */
    public Boolean temClaude() {
        if (local) {
            return Boolean.FALSE; // as sessões do Claude do laboratório local ficam DESLIGADAS (opção avançada; tudo funciona sem IA)
        }
        return versao == null ? null : versao.temClaude();
    }

    /**
     * O servidor tem projetos? Desconhecido vale como sim: a aba Projetos só some quando o agente diz que o servidor não os tem. Em "Este
     * computador" sempre tem: os projetos são as pastas do laboratório local (o agente dele é do perfil monitor e não os conhece).
     */
    public boolean temProjetos() {
        return local || versao == null || versao.temProjetos();
    }

    /** O agente do lab entende {@code guia_ia} ao criar projeto (a caixa "Incluir guia para o Claude" só aparece com Claude e com isto). */
    public boolean entendeGuiaIa() {
        return versao != null && versao.entendeGuiaIa();
    }

    /**
     * O agente deste servidor tem o histórico de métricas de até um mês ({@code recursos.historico}). Só então o app chama {@code GET /api/servidor/historico}; agente de
     * antes, ou que ainda não respondeu: não (cai nos 10 minutos de {@code /api/servidor}, sem erro).
     */
    public boolean entendeHistorico() {
        return versao != null && versao.entendeHistorico();
    }

    /**
     * O agente deste servidor sabe criar projeto ({@code POST /api/projetos/novo}): respondeu, não é do perfil monitor e tem projetos (o do lab
     * de produção, de antes dos {@code recursos}, também). Sem agente (ainda não respondeu, ou não está instalado): não.
     */
    public boolean agenteCriaProjetos() {
        return !local && versao != null && !versao.perfilMonitor() && versao.temProjetos();
    }

    /** O agente é do perfil monitor (o que o app instala): é o único que o app atualiza e remove. */
    public boolean agenteDoMonitor() {
        return versao != null && versao.perfilMonitor();
    }

    /** O token deste Mac ainda não foi registrado (ou o cofre não o tem). */
    public boolean semToken() {
        return nomeDoDispositivo == null;
    }
}
