package br.com.labdesktop.ui;

import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.Pausado;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.ProtecaoDoServidor;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.util.Formato;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * O que a {@code FaixaAviso} diz sobre um servidor (ARQUITETURA 6.3, 8.2 e 9): o problema de conexão (com a ação
 * certa), o crédito do plano a partir de 75 % (perigo a partir de 90 %) e as sessões pausadas. É a tradução de
 * {@code UI.avisoCredito} do painel web, mais a faixa de conexão que o app nativo precisa ("sem conexão desde
 * HH:MM": a tela mantém o último retrato e nunca fica vazia).
 *
 * <p>Puro: recebe o retrato do servidor e o instante, devolve registros. Quem desenha é a {@code FaixaAviso}.
 */
public final class ConteudoDosAvisos {

    /** O que um botão da faixa de conexão faz. */
    public enum Acao {
        TENTAR_AGORA("Tentar agora"),
        CONECTAR("Conectar"),
        DIGITAR_CODIGO("Digitar código"),
        CONFERIR_IMPRESSAO("Conferir impressão"),
        REGISTRAR_TOKEN("Registrar token"),
        /** Abre a confirmação "Instalar agente" (o app nunca instala sozinho: é só o botão que leva ao diálogo). */
        INSTALAR_AGENTE("Instalar agente"),
        /** "Este computador": abre "Preparar este computador" (que só confere e pergunta; nada se instala sem a confirmação). */
        PREPARAR_COMPUTADOR("Preparar este computador"),
        /** Abre o diálogo "Remover servidor" (que pede confirmação): o caminho para quem reinstalou o servidor. */
        REMOVER_SERVIDOR("Remover servidor"),
        /** "Chave recusada": abre "Instalar a chave com a senha" (o app só usa a senha uma vez, depois do clique, e prova a chave numa conexão nova). */
        INSTALAR_CHAVE("Instalar a chave com a senha…");

        private final String rotulo;

        Acao(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    /** Uma faixa. */
    public sealed interface Aviso permits AvisoDeConexao, AvisoDeCredito, AvisoDePausas {
        Gravidade gravidade();

        String icone();
    }

    /** Problema de conexão, agente ou token, com o botão que resolve. */
    public record AvisoDeConexao(Gravidade gravidade, String icone, String titulo, String texto, List<Acao> acoes)
        implements Aviso {
    }

    /** Crédito do plano em 75 % ou mais. As partes são "janela de 5 h em 42%", "semana em 61% (zera em 5 d 3 h)". */
    public record AvisoDeCredito(Gravidade gravidade, String icone, String titulo, List<String> partes) implements Aviso {
    }

    /** Sessões pausadas pelo teto do projeto ou pelo freio global (no máximo 3 nomes; o resto vira "e mais N"). */
    public record AvisoDePausas(Gravidade gravidade, String icone, String titulo, List<Pausada> itens, int maisN)
        implements Aviso {
    }

    /** Uma sessão pausada; {@code projeto} é o nome real (para Retomar) e {@code nome} o que se mostra. */
    public record Pausada(String projeto, String nome, String motivo) { }

    /**
     * O que fazer quando a chave do servidor mudou, em palavras simples. O único caminho que existe é remover o servidor e
     * adicioná-lo de novo (o assistente pede a conferência da impressão digital outra vez). Não há "aceitar mesmo assim"
     * de propósito.
     */
    public static final String TEXTO_DA_CHAVE_QUE_MUDOU = "Se você reinstalou o servidor, use \"Remover servidor\" e "
        + "adicione-o de novo para o app conferir a impressão digital outra vez. Se não reinstalou, não conecte: pode ser "
        + "outra máquina.";

    /** O que fazer quando o servidor recusa a chave do app, em palavras simples: o botão "Instalar a chave com a senha…" resolve se o usuário ainda tem senha. */
    public static final String TEXTO_DA_CHAVE_RECUSADA = "Se você ainda tem a senha do usuário no servidor, use \"Instalar a chave com a senha…\": o app usa a senha uma vez e confere que a chave "
        + "entra. Sem a senha, peça a quem administra o servidor para adicionar a chave.";

    /**
     * O que o usuário lê, na faixa de aviso, no Resumo e no cartão do servidor, quando o SSH conecta e o agente não está instalado. É uma
     * pergunta com resposta, não um erro: Segurança, Arquivos e Terminal funcionam do mesmo jeito.
     */
    public static final String TEXTO_DO_AGENTE_NAO_INSTALADO = "O servidor responde por SSH, mas o agente ainda não está instalado. "
        + "Sem ele você não vê CPU, memória, containers nem projetos (Segurança e Arquivos já funcionam).";

    /** "Este computador": o Docker roda e o laboratório ainda não foi criado. */
    public static final String TEXTO_DO_LOCAL_NAO_CRIADO = "O Docker está rodando, mas o laboratório local ainda não foi criado. Clique em Preparar este "
        + "computador: o app confere o que falta e cria o laboratório só depois da sua confirmação. O terminal e os arquivos deste computador já funcionam.";
    public static final String TEXTO_DO_LOCAL_DOCKER_PARADO = "O laboratório local precisa do Docker rodando. Clique em Preparar este computador para o "
        + "app ajudar a abri-lo e esperar ele ligar. O terminal e os arquivos deste computador já funcionam.";
    public static final String TEXTO_DO_LOCAL_SEM_DOCKER = "O laboratório local roda no Docker, e ele não está instalado neste computador. Clique em "
        + "Preparar este computador: o app mostra as opções de instalação e só instala o que você confirmar. O terminal e os arquivos já funcionam.";

    /** "Este computador" no Linux: o usuário não está no grupo docker. O comando é o do Docker, com {@code $USER} (o shell dele o troca pelo usuário). */
    public static final String TEXTO_DO_LOCAL_SEM_PERMISSAO = "O Docker está instalado, mas este usuário do computador não pode usá-lo (não está no grupo "
        + "docker). O app não tenta elevar privilégio. Rode no Terminal: sudo usermod -aG docker $USER  e depois saia e entre de novo no sistema. "
        + "Clique em Preparar este computador para ver isso com calma.";
    /** "Este computador": o container do agente existe e está parado. */
    public static final String TEXTO_DO_LOCAL_CONTAINER_PARADO = "O Docker está rodando, mas o agente do laboratório local está parado. Clique em Preparar "
        + "este computador: o app recria o agente só depois da sua confirmação, e os dados e o token ficam.";

    /** No máximo 3 nomes de sessões pausadas na faixa. */
    public static final int MAX_PAUSADAS = 3;

    private ConteudoDosAvisos() { }

    /** Todas as faixas do servidor, na ordem em que aparecem: conexão, crédito, pausas. */
    public static List<Aviso> de(EstadoServidor e, Instant agora, ZoneId fuso) {
        List<Aviso> faixas = new ArrayList<>();
        AvisoDeConexao conexao = deConexao(e, agora, fuso);
        if (conexao != null) {
            faixas.add(conexao);
        }
        LimitesConta limites = e.limites();
        AvisoDeCredito credito = deCredito(limites, agora, fuso);
        if (credito != null) {
            faixas.add(credito);
        }
        AvisoDePausas pausas = dePausas(limites);
        if (pausas != null) {
            faixas.add(pausas);
        }
        return faixas;
    }

    // ---------------------------------------------------------------------------------------------
    // Conexão
    // ---------------------------------------------------------------------------------------------

    /** A faixa do problema de conexão, ou {@code null} quando está tudo bem (ou conectando pela primeira vez). */
    public static AvisoDeConexao deConexao(EstadoServidor e, Instant agora, ZoneId fuso) {
        String nome = e.nome();
        Falha falha = e.falha();
        Falha.Tipo tipo = falha == null ? null : falha.tipo();
        String detalhe = falha == null ? "" : " " + falha.mensagem();
        return switch (e.conexao()) {
            case CONECTANDO, BLOQUEADO_COFRE -> null;
            case CONECTADO -> {
                if (e.agenteIncompativel()) {
                    yield new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "O agente de " + nome + " fala outra versão do contrato",
                        "Atualize o app ou o painel do servidor (o agente informou a versão da API "
                            + (e.versao().api() == null ? "desconhecida" : e.versao().api()) + ").", List.of());
                }
                if (e.semToken()) {
                    yield new AvisoDeConexao(Gravidade.DESTAQUE, "chave", "Este computador ainda não tem token em " + nome,
                        "O agente ainda aceita chamadas sem token, mas o token identifica este app (e é exigido depois do corte).",
                        List.of(Acao.REGISTRAR_TOKEN));
                }
                yield null;
            }
            case DESCONECTADO -> new AvisoDeConexao(Gravidade.NEUTRO, "rede", nome + " está desconectado",
                "O app não está conectado a este servidor.", List.of(Acao.CONECTAR));
            case RECONECTANDO -> {
                String desde = e.semConexaoDesde() == null ? "" : " desde " + Formato.fmtHora(e.semConexaoDesde(), fuso)
                    .substring(0, 5);
                yield new AvisoDeConexao(Gravidade.ATENCAO, "atualizar", "Sem conexão com " + nome + desde,
                    "Reconectando" + (e.tentativa() > 0 ? " (tentativa " + e.tentativa() + proxima(e, agora) + ")" : "")
                        + ". Os dados na tela são os da última leitura." + detalhe, List.of(Acao.TENTAR_AGORA));
            }
            case SSH_OK_SEM_AGENTE -> e.local() ? deConexaoLocal(e, nome, detalhe) : tipo == Falha.Tipo.AGENTE_DESATUALIZADO
                ? new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "O agente de " + nome + " é de antes do app nativo",
                    "Falta publicar o painel com o acesso.js: a rota /api/versao não existe lá (404).", List.of(Acao.TENTAR_AGORA))
                : e.protegido() && e.situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO
                ? new AvisoDeConexao(Gravidade.NEUTRO, "servidor", "O agente não está instalado em " + nome + ", e o servidor é protegido",
                    ProtecaoDoServidor.TEXTO_DO_AVISO_SEM_AGENTE, List.of())
                : e.protegido() && e.situacaoDoAgente() == SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER
                ? new AvisoDeConexao(Gravidade.NEUTRO, "alerta", "Sem permissão no Docker de " + nome + ", e o servidor é protegido",
                    ProtecaoDoServidor.TEXTO_DO_AVISO_SEM_PERMISSAO, List.of())
                : e.situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO
                ? new AvisoDeConexao(Gravidade.ATENCAO, "servidor", "O agente ainda não está instalado em " + nome,
                    TEXTO_DO_AGENTE_NAO_INSTALADO, List.of(Acao.INSTALAR_AGENTE))
                : e.situacaoDoAgente() == SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER
                ? new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "Sem permissão no Docker de " + nome,
                    "O servidor responde por SSH, mas este usuário não pode usar o Docker, então o app não sabe se o agente está instalado. "
                        + "Clique em Instalar agente para ver o que falta e o comando exato para quem administra o servidor.",
                    List.of(Acao.INSTALAR_AGENTE))
                : new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "O agente do painel em " + nome + " não responde",
                    "O SSH está de pé; o app insiste a cada poucos segundos (o painel pode estar reiniciando)." + detalhe,
                    List.of(Acao.TENTAR_AGORA));
            case ERRO_AUTENTICACAO -> tipo == Falha.Tipo.CHAVE_NAO_USADA
                ? new AvisoDeConexao(Gravidade.PERIGO, "chave", "O app não conseguiu usar a chave em " + nome, falha.mensagem() + " Depois, tente de novo.", List.of(Acao.TENTAR_AGORA))
                : tipo == Falha.Tipo.CHAVE_RECUSADA
                ? new AvisoDeConexao(Gravidade.PERIGO, "chave", nome + " recusou a chave deste app", falha.mensagem() + " " + TEXTO_DA_CHAVE_RECUSADA,
                    List.of(Acao.INSTALAR_CHAVE, Acao.TENTAR_AGORA))
                : new AvisoDeConexao(Gravidade.PERIGO, "chave", nome + " recusou a chave deste app",
                    "Se a chave não estiver lá, peça a quem administra o servidor para adicioná-la ao acesso do usuário. Depois, tente de novo.",
                    List.of(Acao.TENTAR_AGORA));
            case ERRO_CHAVE_HOST -> tipo == Falha.Tipo.PIN_DIVERGENTE
                ? new AvisoDeConexao(Gravidade.PERIGO, "erro", "A chave do servidor " + nome + " mudou",
                    "O app não conecta, para o caso de ser outra máquina fazendo-se passar pelo servidor. " + TEXTO_DA_CHAVE_QUE_MUDOU,
                    List.of(Acao.REMOVER_SERVIDOR))
                : new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "Confirme o servidor " + nome,
                    "A chave do servidor ainda não foi confirmada: compare a impressão digital antes de conectar.",
                    List.of(Acao.CONFERIR_IMPRESSAO));
            case TOKEN_RECUSADO -> new AvisoDeConexao(Gravidade.PERIGO, "chave", "O agente de " + nome + " não aceita o token deste computador",
                "O token foi revogado ou o cofre foi recriado. Registre um token novo.", List.of(Acao.REGISTRAR_TOKEN));
            case AGUARDANDO_2FA -> new AvisoDeConexao(Gravidade.ATENCAO, "chave", "Código do autenticador pendente",
                nome + " pede o código de 6 dígitos do autenticador para este computador.", List.of(Acao.DIGITAR_CODIGO));
            case SUSPENSO -> new AvisoDeConexao(Gravidade.NEUTRO, "pausa", "O computador está em repouso",
                "A conexão com " + nome + " volta quando ele acordar.", List.of());
        };
    }

    /** "Este computador" com o agente fora: o que falta, em palavras de Docker (nunca "SSH"), e o botão que leva à tela que pergunta. */
    private static AvisoDeConexao deConexaoLocal(EstadoServidor e, String nome, String detalhe) {
        return switch (e.situacaoDoAgente()) {
            case LOCAL_NAO_CRIADO -> new AvisoDeConexao(Gravidade.ATENCAO, "sistema", "O laboratório local ainda não foi criado",
                TEXTO_DO_LOCAL_NAO_CRIADO, List.of(Acao.PREPARAR_COMPUTADOR));
            case LOCAL_DOCKER_PARADO -> new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "O Docker não está rodando neste computador",
                TEXTO_DO_LOCAL_DOCKER_PARADO, List.of(Acao.PREPARAR_COMPUTADOR));
            case LOCAL_SEM_DOCKER -> new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "O Docker não está instalado neste computador",
                TEXTO_DO_LOCAL_SEM_DOCKER, List.of(Acao.PREPARAR_COMPUTADOR));
            case LOCAL_SEM_PERMISSAO -> new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "Este usuário não pode usar o Docker",
                TEXTO_DO_LOCAL_SEM_PERMISSAO, List.of(Acao.PREPARAR_COMPUTADOR, Acao.TENTAR_AGORA));
            case LOCAL_CONTAINER_PARADO -> new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "O agente do laboratório local está parado",
                TEXTO_DO_LOCAL_CONTAINER_PARADO, List.of(Acao.PREPARAR_COMPUTADOR, Acao.TENTAR_AGORA));
            default -> new AvisoDeConexao(Gravidade.ATENCAO, "alerta", "O agente do laboratório local não responde",
                "O Docker está rodando e o agente existe, mas não responde. O app insiste a cada poucos segundos (ele pode estar reiniciando); se não "
                    + "voltar, clique em Preparar este computador: ele confere tudo e recria o agente se for preciso." + detalhe,
                List.of(Acao.PREPARAR_COMPUTADOR, Acao.TENTAR_AGORA));
        };
    }

    private static String proxima(EstadoServidor e, Instant agora) {
        if (e.proximaTentativaEm() == null) {
            return "";
        }
        long s = Math.max(0, Duration.between(agora, e.proximaTentativaEm()).toSeconds());
        return ", próxima em " + s + " s";
    }

    // ---------------------------------------------------------------------------------------------
    // Crédito e pausas
    // ---------------------------------------------------------------------------------------------

    /**
     * A faixa de crédito, a partir de 75 % (perigo a partir de 90 %), só com leitura {@code disponivel} e não
     * {@code antiga}. {@code null} abaixo disso.
     */
    public static AvisoDeCredito deCredito(LimitesConta l, Instant agora, ZoneId fuso) {
        if (l == null || !l.leituraUtil()) {
            return null;
        }
        List<Janela> janelas = new ArrayList<>();
        acrescentar(janelas, "janela de 5 h", l.cincoHoras());
        acrescentar(janelas, "semana", l.semana());
        double pior = janelas.stream().mapToDouble(j -> j.fracao).max().orElse(0);
        if (pior < LimitesConta.LIMIAR_ATENCAO / 100.0) {
            return null;
        }
        boolean perigo = pior >= LimitesConta.LIMIAR_PERIGO / 100.0;
        List<String> partes = new ArrayList<>();
        for (Janela j : janelas) {
            String t = j.rotulo + " em " + Formato.fmtPct(j.fracao);
            String zera = TextosDeLimite.restante(j.zeraEm, agora);
            partes.add(j.fracao >= LimitesConta.LIMIAR_ATENCAO / 100.0 && zera != null ? t + " (zera em " + zera + ")" : t);
        }
        return new AvisoDeCredito(perigo ? Gravidade.PERIGO : Gravidade.ATENCAO, "alerta",
            perigo ? "Crédito do Claude quase no fim" : "Crédito do Claude acima de 75%", partes);
    }

    private record Janela(String rotulo, double fracao, Instant zeraEm) { }

    private static void acrescentar(List<Janela> janelas, String rotulo, LimitesConta.Janela j) {
        if (j != null && j.usadoPct() != null && Double.isFinite(j.usadoPct())) {
            janelas.add(new Janela(rotulo, j.usadoPct() / 100.0, j.zeraEm()));
        }
    }

    /** A faixa das sessões pausadas ({@code pausados[]} de {@code /api/limites}); vale sempre, com ou sem leitura do plano. */
    public static AvisoDePausas dePausas(LimitesConta l) {
        if (l == null || l.pausados().isEmpty()) {
            return null;
        }
        List<Pausada> itens = new ArrayList<>();
        for (Pausado p : l.pausados().subList(0, Math.min(MAX_PAUSADAS, l.pausados().size()))) {
            LimitesConta.LimiteProjeto info = l.porProjeto().get(p.projeto());
            String motivo = "freio".equals(p.motivo())
                ? "pausada pelo freio global"
                : "pausada pelo limite" + (info != null && info.tetoPct() != null && info.usadoPp() != null
                    ? " (" + Formato.fmtNum(info.usadoPp()) + "% de " + Formato.fmtNum(info.tetoPct()) + "%)" : "");
            itens.add(new Pausada(p.projeto(), TextosDeLimite.nomeAmigavel(p.projeto()), motivo));
        }
        int total = l.pausados().size();
        return new AvisoDePausas(Gravidade.ATENCAO, "pausa", total == 1 ? "Sessão pausada" : "Sessões pausadas",
            itens, Math.max(0, total - MAX_PAUSADAS));
    }
}
