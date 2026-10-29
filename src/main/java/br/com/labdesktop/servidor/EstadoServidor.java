package br.com.labdesktop.servidor;

import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.Transporte;
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
 * @param protegido          "Servidor protegido", JÁ valendo agora: a marca do cofre, ou o agente do lab visto por fora nesta sessão (ver
 *                           {@link ProtecaoDoServidor}). Com ela o app não oferece nem faz instalar, atualizar nem remover nada neste servidor
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
    boolean local,
    boolean protegido,
    Transporte transporte) {

    public EstadoServidor {
        if (situacaoDoAgente == null) {
            situacaoDoAgente = SituacaoDoAgente.DESCONHECIDA;
        }
        transporte = transporte == null ? Transporte.DIRETO : transporte;
    }

    /** Sem o modo de conectar (direto): o que toda tela e todo teste que não tratam de conexão sem porta aberta usam. */
    public EstadoServidor(String servidorId, String nome, EstadoConexao conexao, Falha falha, int tentativa,
                          Instant proximaTentativaEm, Instant semConexaoDesde, long geracao, Estado2fa estado2fa,
                          String nomeDoDispositivo, VersaoAgente versao, LimitesConta limites, SituacaoDoAgente situacaoDoAgente, boolean local, boolean protegido) {
        this(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde, geracao, estado2fa, nomeDoDispositivo, versao,
            limites, situacaoDoAgente, local, protegido, Transporte.DIRETO);
    }

    /** Sem a marca de protegido (falsa): o que a maioria dos testes e das telas que não tratam de proteção usa. */
    public EstadoServidor(String servidorId, String nome, EstadoConexao conexao, Falha falha, int tentativa,
                          Instant proximaTentativaEm, Instant semConexaoDesde, long geracao, Estado2fa estado2fa,
                          String nomeDoDispositivo, VersaoAgente versao, LimitesConta limites, SituacaoDoAgente situacaoDoAgente, boolean local) {
        this(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde, geracao, estado2fa, nomeDoDispositivo, versao,
            limites, situacaoDoAgente, local, false);
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
            limites, info.estado() == EstadoConexao.SSH_OK_SEM_AGENTE ? situacaoDoAgente : SituacaoDoAgente.DESCONHECIDA, local, protegido, transporte);
    }

    EstadoServidor comVersao(VersaoAgente nova, Estado2fa novoEstado2fa) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, novoEstado2fa, nomeDoDispositivo, nova, limites, situacaoDoAgente, local, protegido, transporte);
    }

    EstadoServidor comLimites(LimitesConta novos) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeDoDispositivo, versao, novos, situacaoDoAgente, local, protegido, transporte);
    }

    EstadoServidor comEstado2fa(Estado2fa novo) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, novo, nomeDoDispositivo, versao, limites, situacaoDoAgente, local, protegido, transporte);
    }

    EstadoServidor comDispositivo(String nomeNovo) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeNovo, versao, limites, situacaoDoAgente, local, protegido, transporte);
    }

    /** Copia com o nome novo (o usuário editou o servidor). */
    EstadoServidor comNome(String nomeNovo) {
        return new EstadoServidor(servidorId, nomeNovo, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeDoDispositivo, versao, limites, situacaoDoAgente, local, protegido, transporte);
    }

    EstadoServidor comSituacaoDoAgente(SituacaoDoAgente nova) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeDoDispositivo, versao, limites, nova, local, protegido, transporte);
    }

    /** Copia com a marca de protegido nova (o usuário mudou a caixa, ou o app viu o agente do lab de fora). */
    EstadoServidor comProtegido(boolean novo) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeDoDispositivo, versao, limites, situacaoDoAgente, local, novo, transporte);
    }

    /** Copia com o modo de conectar novo (o usuário trocou em Editar servidor). */
    EstadoServidor comTransporte(Transporte novo) {
        return new EstadoServidor(servidorId, nome, conexao, falha, tentativa, proximaTentativaEm, semConexaoDesde,
            geracao, estado2fa, nomeDoDispositivo, versao, limites, situacaoDoAgente, local, protegido, novo);
    }

    /** O servidor conecta SEM PORTA ABERTA (AWS Session Manager ou Cloudflare)? */
    public boolean semPortaAberta() {
        return transporte.ehTunel();
    }

    /**
     * O app deve OFERECER "Instalar agente" (menu, cartão, página, aviso)? O SSH está de pé sem o agente, a leitura disse que dá para instalar e o servidor NÃO é
     * protegido. É a única conta: toda tela pergunta aqui, e nenhuma olha {@link SituacaoDoAgente#oferecerInstalar} sozinha para decidir se mostra o botão.
     */
    public boolean ofereceInstalarAgente() {
        return !protegido && conexao == EstadoConexao.SSH_OK_SEM_AGENTE && situacaoDoAgente.oferecerInstalar();
    }

    /** O app deve oferecer "Atualizar agente" e "Remover agente"? Só com o agente do perfil monitor que o app instalou, respondendo, e o servidor NÃO protegido. */
    public boolean ofereceAtualizarERemoverAgente() {
        return !protegido && conexao == EstadoConexao.CONECTADO && agenteDoMonitor();
    }

    /** Faltaria o agente e o app o ofereceria, não fosse a proteção: o texto do aviso troca "Instalar agente" pela explicação de que o servidor é protegido. */
    public boolean agenteFaltaMasServidorProtegido() {
        return protegido && conexao == EstadoConexao.SSH_OK_SEM_AGENTE && situacaoDoAgente.oferecerInstalar();
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
