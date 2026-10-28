package br.com.labdesktop.ui;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.cofre.CofreBloqueadoException;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.SondagemException;

/**
 * Traduz o que pode dar errado em frases em pt-BR para o usuário (toasts e mensagens dentro dos diálogos). Texto que
 * vem do servidor já foi limpo e redigido nas exceções ({@code ErroApi}, {@code ErroProvisionamento}); aqui só se
 * escolhe a frase, e qualquer outra coisa passa pelo {@code Redator}.
 */
public final class MensagensDeErro {

    private MensagensDeErro() { }

    /** A frase para uma exceção qualquer. Nunca devolve {@code null}. */
    public static String de(Throwable erro) {
        if (erro instanceof ErroApi e) {
            return daApi(e);
        }
        if (erro instanceof ErroProvisionamento e) {
            return doProvisionamento(e);
        }
        if (erro instanceof br.com.labdesktop.instalador.PacoteDoAgenteAusenteException) {
            return erro.getMessage();
        }
        if (erro instanceof CofreBloqueadoException) {
            return "O cofre está bloqueado.";
        }
        if (erro instanceof CofreException || erro instanceof SondagemException || erro instanceof IllegalArgumentException) {
            return Redator.redigir(erro);
        }
        return "Algo deu errado: " + Redator.redigir(erro);
    }

    public static String daApi(ErroApi e) {
        return switch (e) {
            case ErroApi.ErroRede r -> "Sem resposta do servidor (a conexão caiu ou o agente não respondeu).";
            case ErroApi.RespostaInvalida r -> "O agente respondeu algo que o app não entendeu.";
            case ErroApi.NaoAutorizado n -> n.totpRequerido()
                ? "O servidor pede o código do autenticador."
                : "O agente não reconhece o token deste computador.";
            case ErroApi.Conflito c -> c.mensagem();
            case ErroApi.NaoEncontrado n -> n.mensagem();
            case ErroApi.ServidorErro s -> s.mensagem();
        };
    }

    public static String doProvisionamento(ErroProvisionamento e) {
        return switch (e.codigo()) {
            case ErroProvisionamento.TOTP_INCORRETO ->
                "Código incorreto ou já usado. Espere o código do celular mudar e confira a hora do celular.";
            case ErroProvisionamento.SEM_SESSAO ->
                "Sem conexão SSH com o servidor agora. Espere conectar e tente de novo.";
            case ErroProvisionamento.TOTP_JA_ATIVO -> "O 2FA já está ativo neste servidor.";
            case ErroProvisionamento.TOKEN_CONFLITO ->
                "Já existe um token com esse nome de dispositivo no servidor (de outro computador, ou de um cofre antigo).";
            case "totp_requerido" -> "O código do autenticador é necessário e não foi informado.";
            default -> e.getMessage();
        };
    }
}
