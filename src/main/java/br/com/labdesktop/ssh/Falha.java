package br.com.labdesktop.ssh;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;

/**
 * Por que a conexão não está de pé. A {@code mensagem} é texto em pt-BR já limpo e redigido (pode ir para a
 * tela e para o log); {@code tipo} deixa a tela escolher a ação certa (abrir o diálogo de confiar no host,
 * oferecer reprovisionar o token...).
 */
public record Falha(Tipo tipo, String mensagem, String textoCopiavel) {

    /** O que deu errado, em categorias que a tela sabe tratar. */
    public enum Tipo {
        /** Host nunca confirmado pelo usuário: falta o {@code DialogoConfiarHost}. */
        PIN_AUSENTE,
        /** A chave do host mudou (ou o algoritmo negociado não tem pin): possível MITM. */
        PIN_DIVERGENTE,
        /** O servidor recusou a chave pública do cofre. */
        CHAVE_RECUSADA,
        /** O app não chegou a usar a chave (não conseguiu assinar ou oferecê-la): a causa está no log, e não no servidor. */
        CHAVE_NAO_USADA,
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
        LOCAL_AGENTE_DESATUALIZADO,
        /** Sem porta aberta: falta o programa (aws, plugin do Session Manager ou cloudflared) neste computador. */
        TRANSPORTE_FERRAMENTA_AUSENTE,
        /** Sem porta aberta: o login da AWS expirou (ou não existe). */
        TRANSPORTE_LOGIN_AWS,
        /** Sem porta aberta: a instância não está ligada ao Session Manager. */
        TRANSPORTE_INSTANCIA_SEM_SSM,
        /** Sem porta aberta: a Cloudflare pede login no navegador (o endereço vai no texto copiável). */
        TRANSPORTE_LOGIN_CLOUDFLARE,
        /** Sem porta aberta: o túnel não subiu, caiu, ou o SSH não respondeu por ele. */
        TRANSPORTE_OUTRO
    }

    public Falha {
        if (tipo == null) {
            throw new IllegalArgumentException("tipo");
        }
        mensagem = TextoSeguro.limparLinha(Redator.redigir(mensagem));
        if (textoCopiavel != null) {
            String limpo = TextoSeguro.limpar(Redator.redigir(textoCopiavel)).strip();
            textoCopiavel = limpo.isEmpty() ? null : TextoSeguro.truncar(limpo, 800);
        }
    }

    public Falha(Tipo tipo, String mensagem) {
        this(tipo, mensagem, null);
    }

    /** A falha de um túnel de transporte sem porta aberta, na categoria que a tela sabe tratar. */
    public static Falha doTunel(br.com.labdesktop.transporte.FalhaDoTunel f) {
        Tipo tipo = switch (f.tipo()) {
            case FERRAMENTA_AUSENTE -> Tipo.TRANSPORTE_FERRAMENTA_AUSENTE;
            case LOGIN_AWS_EXPIRADO -> Tipo.TRANSPORTE_LOGIN_AWS;
            case INSTANCIA_SEM_SSM -> Tipo.TRANSPORTE_INSTANCIA_SEM_SSM;
            case CLOUDFLARE_PEDE_LOGIN -> Tipo.TRANSPORTE_LOGIN_CLOUDFLARE;
            case PRAZO_ESTOURADO, SAIU, NAO_INICIOU, PORTA_OCUPADA -> Tipo.TRANSPORTE_OUTRO;
        };
        return new Falha(tipo, f.mensagem(), f.textoCopiavel());
    }

    /** É uma falha do túnel de transporte (e não do SSH, da chave ou do agente)? */
    public boolean ehDoTransporte() {
        return switch (tipo) {
            case TRANSPORTE_FERRAMENTA_AUSENTE, TRANSPORTE_LOGIN_AWS, TRANSPORTE_INSTANCIA_SEM_SSM, TRANSPORTE_LOGIN_CLOUDFLARE, TRANSPORTE_OUTRO -> true;
            default -> false;
        };
    }
}
