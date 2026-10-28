package br.com.labdesktop.ssh;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;

/**
 * Por que a conexão não está de pé. A {@code mensagem} é texto em pt-BR já limpo e redigido (pode ir para a
 * tela e para o log); {@code tipo} deixa a tela escolher a ação certa (abrir o diálogo de confiar no host,
 * oferecer reprovisionar o token...).
 */
public record Falha(Tipo tipo, String mensagem) {

    /** O que deu errado, em categorias que a tela sabe tratar. */
    public enum Tipo {
        /** Host nunca confirmado pelo usuário: falta o {@code DialogoConfiarHost}. */
        PIN_AUSENTE,
        /** A chave do host mudou (ou o algoritmo negociado não tem pin): possível MITM. */
        PIN_DIVERGENTE,
        /** O servidor recusou a chave pública do cofre. */
        CHAVE_RECUSADA,
        /** A chave guardada no cofre não pôde ser lida. */
        CHAVE_ILEGIVEL,
        /** Rede: recusado, sem rota, prazo estourado, sessão que caiu. */
        REDE,
        /** O agente respondeu 401 sem {@code codigo}. */
        TOKEN_RECUSADO,
        /** O agente exige o código do autenticador. */
        CODIGO_2FA_PENDENTE,
        /** SSH de pé, agente não responde. */
        AGENTE_FORA,
        /** SSH de pé e o agente responde, mas é de antes do app nativo: não tem {@code GET /api/versao} (404). */
        AGENTE_DESATUALIZADO,
        /** "Este computador": o agente do laboratório local não responde (a tela fala de Docker, não de SSH). */
        LOCAL_AGENTE_FORA,
        /** "Este computador": o agente local é de antes do app nativo. */
        LOCAL_AGENTE_DESATUALIZADO
    }

    public Falha {
        if (tipo == null) {
            throw new IllegalArgumentException("tipo");
        }
        mensagem = TextoSeguro.limparLinha(Redator.redigir(mensagem));
    }
}
