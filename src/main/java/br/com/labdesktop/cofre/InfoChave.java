package br.com.labdesktop.cofre;

import java.time.Instant;

/**
 * O que a tela pode saber de uma chave: tudo menos a privada (ARQUITETURA 4.3, "nenhum DTO de tela contém
 * segredo").
 *
 * @param publica   linha completa da chave pública, pronta para o {@code authorized_keys}: {@code ssh-ed25519 AAAA... lab-desktop@mac}
 * @param impressao {@code SHA256:...}
 * @param origem    quem criou a chave: decide se o app pode tirá-la do servidor ao remover o servidor
 */
public record InfoChave(String id, String nome, String algoritmo, String publica, String impressao, Instant criadaEm,
                        Origem origem) {

    /**
     * De onde a chave veio. Só uma chave que o PRÓPRIO app criou é dele para revogar: ela existe só neste cofre e em nenhum
     * outro lugar. Uma chave importada de um arquivo do usuário (o {@code ~/.ssh/id_ed25519} dele, por exemplo) é usada também
     * no terminal e em outras máquinas, e tirá-la do servidor tiraria o acesso dele por fora do app.
     */
    public enum Origem {
        /** Criada pelo app ao cadastrar o servidor: existe só neste cofre. */
        GERADA_PELO_APP,
        /** Importada de um arquivo do usuário: o app não a tira do servidor. */
        IMPORTADA,
        /** Cofre de uma versão que não gravava a origem: por segurança, tratada como se fosse do usuário. */
        DESCONHECIDA;

        /** {@code true} só se o app pode tirar esta chave do {@code authorized_keys} ao remover o servidor. */
        public boolean podeSerTiradaDoServidor() {
            return this == GERADA_PELO_APP;
        }
    }

    public InfoChave {
        origem = origem == null ? Origem.DESCONHECIDA : origem;
    }

    /**
     * O que a linha de {@link #linhaDeAuthorizedKeys} restringe DE VERDADE e o que não restringe, em texto para o usuário
     * (a tela "Adicionar servidor" o mostra ao lado do comando). A linha só tem {@code no-agent-forwarding},
     * {@code no-X11-forwarding} e {@code permitopen}: não tem {@code no-pty}, {@code restrict} nem {@code command=}, então
     * a chave AINDA abre shell. O app precisa disso: registrar o token, configurar o 2FA e abrir o terminal são
     * comandos por SSH {@code exec}. Se um dia a linha ganhar uma dessas restrições, o teste {@code InfoChaveTest} falha e
     * este texto tem de mudar junto.
     */
    public static final String RESTRICOES_DA_LINHA = "O comando só acrescenta uma linha ao authorized_keys. Ela impede que a "
        + "chave sirva de agente SSH, de X11 e de túnel para qualquer porta que não seja a do agente do painel, então a "
        + "chave não vira pivô para o resto da sua rede. Ela NÃO tira o shell: quem tiver esta chave ainda abre uma sessão "
        + "e executa comandos como este usuário, porque o app precisa disso (registrar o token, configurar o 2FA e, mais "
        + "adiante, abrir o terminal). Por isso a chave fica só neste cofre. Depois clique em Testar conexão.";

    /**
     * A linha que vai no {@code ~/.ssh/authorized_keys} do servidor (ARQUITETURA 5.8): sem agente nem X11 e com
     * {@code permitopen} só para o agente, para a chave não servir de pivô para a LAN.
     */
    public String linhaDeAuthorizedKeys(int portaAgente) {
        if (portaAgente < 1 || portaAgente > 65_535) {
            throw new IllegalArgumentException("Porta do agente fora de 1 a 65535.");
        }
        return "no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:" + portaAgente + "\" " + publica;
    }

    /**
     * O comando pronto para colar numa sessão {@code ssh} já aberta no servidor (com a chave antiga): cria a pasta
     * se faltar, acrescenta a linha e fecha as permissões. A linha vai entre aspas simples, com qualquer aspa
     * simples do texto escapada, para o shell nunca interpretar nada do comentário da chave.
     */
    public String comandoDeInstalacao(int portaAgente) {
        String linha = linhaDeAuthorizedKeys(portaAgente).replace("'", "'\\''");
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '" + linha + "' >> ~/.ssh/authorized_keys"
            + " && chmod 600 ~/.ssh/authorized_keys";
    }
}
