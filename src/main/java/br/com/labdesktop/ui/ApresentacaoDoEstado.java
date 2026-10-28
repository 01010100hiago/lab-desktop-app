package br.com.labdesktop.ui;

import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.Falha;

/**
 * Como o estado da conexão aparece na barra de servidores e na lista de servidores (ARQUITETURA 8.2): SEMPRE texto
 * mais ícone, nunca só cor. Puro e testável.
 */
public final class ApresentacaoDoEstado {

    /** O que mostrar: texto curto, nome do ícone SVG e o peso visual. */
    public record Apresentacao(String texto, String icone, Gravidade gravidade) { }

    private ApresentacaoDoEstado() { }

    /** O estado em poucas palavras. */
    public static Apresentacao de(EstadoServidor e) {
        Falha.Tipo falha = e.falha() == null ? null : e.falha().tipo();
        return switch (e.conexao()) {
            case DESCONECTADO -> new Apresentacao("Desconectado", "rede", Gravidade.NEUTRO);
            case CONECTANDO -> new Apresentacao("Conectando…", "atualizar", Gravidade.DESTAQUE);
            case CONECTADO -> e.agenteIncompativel()
                ? new Apresentacao("Agente incompatível", "alerta", Gravidade.ATENCAO)
                : new Apresentacao("Conectado", "ok-circulo", Gravidade.OK);
            case SSH_OK_SEM_AGENTE -> falha == Falha.Tipo.AGENTE_DESATUALIZADO
                ? new Apresentacao("Agente desatualizado", "alerta", Gravidade.ATENCAO)
                : new Apresentacao("Agente não responde", "alerta", Gravidade.ATENCAO);
            case RECONECTANDO -> new Apresentacao(
                e.tentativa() > 0 ? "Reconectando (" + e.tentativa() + ")" : "Reconectando", "atualizar", Gravidade.ATENCAO);
            case ERRO_AUTENTICACAO -> new Apresentacao("Chave recusada", "chave", Gravidade.PERIGO);
            case ERRO_CHAVE_HOST -> falha == Falha.Tipo.PIN_DIVERGENTE
                ? new Apresentacao("Chave do servidor mudou", "erro", Gravidade.PERIGO)
                : new Apresentacao("Confirmar servidor", "alerta", Gravidade.ATENCAO);
            case TOKEN_RECUSADO -> new Apresentacao("Token recusado", "chave", Gravidade.PERIGO);
            case AGUARDANDO_2FA -> new Apresentacao("Aguardando código", "chave", Gravidade.ATENCAO);
            case BLOQUEADO_COFRE -> new Apresentacao("Cofre bloqueado", "chave", Gravidade.NEUTRO);
            case SUSPENSO -> new Apresentacao("Mac em repouso", "pausa", Gravidade.NEUTRO);
        };
    }
}
