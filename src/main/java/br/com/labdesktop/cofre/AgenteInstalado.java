package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.ValidadorDeNomes;

/**
 * Como falar com o agente que ESTE app instalou num servidor: o nome do container (pode não ser {@code painel-lab} quando se testa ao lado
 * do agente do lab) e se o Docker só responde por {@code sudo -n} (usuário fora do grupo docker). Guardado no cofre, ao lado do servidor,
 * para o token, o 2FA, a rotação e o diagnóstico falarem com o container certo, do jeito certo, mesmo depois de o app reiniciar. Não é
 * segredo.
 *
 * @param container nome do container do agente
 * @param comSudo   o Docker do servidor só é alcançado por {@code sudo -n docker}
 */
public record AgenteInstalado(String container, boolean comSudo) {

    public AgenteInstalado {
        if (container == null || !container.matches("^[a-z0-9][a-z0-9_.-]{0,40}$") || !ValidadorDeNomes.containerValido(container)) {
            throw new IllegalArgumentException("Nome de container do agente inválido.");
        }
    }

    /** O comando do Docker: {@code docker} ou {@code sudo -n docker}. */
    public String docker() {
        return comSudo ? "sudo -n docker" : "docker";
    }
}
