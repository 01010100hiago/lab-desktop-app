package br.com.labdesktop.teste;

import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.GerenciadorServidores;

/**
 * O usuário ligando ou desligando "Servidor protegido" pelo MESMO caminho de produção, o do Editar servidor ({@code GerenciadorServidores.atualizar}), sem diálogo:
 * manda os dados que já estão no cofre com a caixa no valor pedido.
 */
public final class EdicaoDaProtecao {

    private EdicaoDaProtecao() { }

    /** @return {@code true} se mudou alguma coisa */
    public static boolean definir(GerenciadorServidores gerenciador, String servidorId, boolean protegido) throws CofreException {
        ConfigServidor c = gerenciador.servidor(servidorId).orElseThrow().config();
        return gerenciador.atualizar(servidorId, new Dados(c.nome(), c.host(), c.porta(), c.usuario(), c.portaAgente(), protegido)).mudouAlgo();
    }
}
