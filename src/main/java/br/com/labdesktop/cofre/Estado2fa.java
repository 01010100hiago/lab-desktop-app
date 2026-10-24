package br.com.labdesktop.cofre;

import java.util.Locale;

/**
 * Estado do 2FA (TOTP) de um servidor, como o agente o informa no campo {@code totp} de
 * {@code GET /api/versao} (CONTRATO-API 3.1 e seção 6). No cofre é só uma DICA da última leitura (para a tela
 * saber se oferece "Configurar" ou "Desativar" antes da primeira resposta); a fonte da verdade é o agente.
 */
public enum Estado2fa {
    /** Sem 2FA configurado: nada é exigido. */
    DESLIGADO("desligado"),
    /** Segredo gerado e ainda não confirmado: não exige código. */
    PENDENTE("pendente"),
    /** Confirmado: token sem confirmação válida recebe 401 o pedido de código. */
    ATIVO("ativo");

    private final String rotulo;

    Estado2fa(String rotulo) {
        this.rotulo = rotulo;
    }

    /** O texto do agente ({@code "desligado"}, {@code "pendente"}, {@code "ativo"}). */
    public String rotulo() {
        return rotulo;
    }

    /** Lê o texto do agente; desconhecido ou {@code null} vira {@link #DESLIGADO}. */
    public static Estado2fa deRotulo(String texto) {
        if (texto != null) {
            String t = texto.strip().toLowerCase(Locale.ROOT);
            for (Estado2fa e : values()) {
                if (e.rotulo.equals(t)) {
                    return e;
                }
            }
        }
        return DESLIGADO;
    }
}
