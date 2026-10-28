package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.JComponent;

/**
 * Ajustes, Segurança: os containers que o usuário marcou como "nunca encerrar nem reiniciar por este app" (no diálogo de parar um container, o botão "Proteger este container"), por
 * servidor, cada um com o botão Liberar. O app também protege SOZINHO, pelo nome, o que carrega o tráfego e o acesso (túnel, proxy, o Docker, o agente do painel): isso não se
 * desliga aqui, e a seção diz quais são.
 */
final class SecaoSeguranca extends Painel {

    private static final long serialVersionUID = 1L;

    private final transient ControleDeAjustes controle;
    private final JComponent lista = Caixa.coluna(6);
    private final List<Botao> botoesDeLiberar = new ArrayList<>();
    private final List<String> linhas = new ArrayList<>();

    SecaoSeguranca(ControleDeAjustes controle) {
        super(new BorderLayout());
        this.controle = controle;
        add(Paginas.coluna(12,
            Paginas.grupo("Containers protegidos"),
            Paginas.ajuda("O app nunca para nem reinicia estes containers pela lista de processos ou de containers, nem a pedido de ninguém. Para marcar um, abra Parar o container "
                + "e use Proteger este container."),
            Paginas.cartao(lista),
            Caixa.espacoVertical(4),
            Paginas.grupo("Sempre protegidos, sem você marcar"),
            Paginas.ajuda("O que leva o tráfego e o acesso ao servidor fica de fora pelo nome: túnel (cloudflared), proxies (traefik, caddy, nginx-proxy e qualquer nome com proxy), "
                + "o motor do Docker e o agente do painel. Também ficam de fora o sshd, os shells, o sftp-server e os processos do sistema. Isso não se desliga aqui.")), BorderLayout.NORTH);
        atualizar();
        getAccessibleContext().setAccessibleName("Segurança");
    }

    /** Refaz a lista com o que está nas preferências agora. */
    void atualizar() {
        lista.removeAll();
        botoesDeLiberar.clear();
        linhas.clear();
        Map<String, Set<String>> marcados = new TreeMap<>(controle.preferencias().containersProtegidos());
        boolean algum = false;
        for (Map.Entry<String, Set<String>> e : marcados.entrySet()) {
            String servidor = controle.nomeDoServidor(e.getKey());
            for (String container : new java.util.TreeSet<>(e.getValue())) {
                algum = true;
                Botao liberar = Botoes.secundario("Liberar", null);
                liberar.setName("liberar-" + e.getKey() + "-" + container);
                liberar.getAccessibleContext().setAccessibleName("Liberar o container " + container + " do servidor " + servidor);
                liberar.addActionListener(ev -> {
                    controle.atualizarPreferencias(p -> p.comContainerProtegido(e.getKey(), container, false));
                    atualizar();
                });
                botoesDeLiberar.add(liberar);
                String texto = servidor + "  ·  " + container;
                linhas.add(texto);
                Rotulo r = new Rotulo(texto).truncavel();
                lista.add(Caixa.linha(10, r, Caixa.mola(), liberar));
            }
        }
        if (!algum) {
            lista.add(new Rotulo("Você não marcou nenhum container.").corDoToken(Tokens.TEXTO2).tamanho(12.5f));
        }
        lista.revalidate();
        lista.repaint();
    }

    List<String> linhas() {
        return List.copyOf(linhas);
    }

    List<Botao> botoesDeLiberar() {
        return List.copyOf(botoesDeLiberar);
    }
}
