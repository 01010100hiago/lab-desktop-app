package br.com.labdesktop.servidor;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.SegredoTotp;
import java.time.Instant;
import java.util.Optional;

/**
 * As operações do 2FA que o diálogo "Configurar 2FA" usa (ARQUITETURA 10.1). {@link ServidorLab} as implementa; a
 * interface existe para o diálogo ser testado com um servidor de mentira, sem SSH. Todas BLOQUEIAM (SSH e rede):
 * thread de fundo, nunca a EDT.
 */
public interface OperacoesDe2fa {

    /** {@code GET /api/versao} agora; o campo {@code totp} diz {@code desligado}, {@code pendente} ou {@code ativo}. */
    VersaoAgente consultarVersao() throws ErroApi;

    /** Gera o segredo no servidor. O {@link SegredoTotp} volta UMA vez; quem o recebe o zera. */
    SegredoTotp iniciarConfiguracao2fa() throws ErroProvisionamento;

    /** Confirma o primeiro código; devolve até quando este Mac ficou confirmado. */
    Optional<Instant> confirmarConfiguracao2fa(String codigo) throws ErroProvisionamento;

    /** Desativa o 2FA (exige um código válido). */
    void desativar2fa(String codigo) throws ErroProvisionamento;
}
