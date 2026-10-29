package br.com.labdesktop.instalador;

import br.com.labdesktop.instalador.ResultadoDaVerificacao.Item;
import java.util.EnumSet;
import java.util.List;

/**
 * Tudo o que o app sabe instalar num servidor, como uma lista de descrições ({@link ItemDeInstalacao}). Hoje são dois, e só dois, porque
 * são os únicos que os passos reais de {@link InstaladorDeAgente} conseguem fazer de forma independente:
 *
 * <ul>
 *   <li><b>Docker</b>: o passo {@link PassoDeInstalacao#DOCKER}. Sozinho deixa o servidor coerente (o Docker instalado e nada mais).</li>
 *   <li><b>Agente de monitoramento</b>: os cinco passos seguintes, JUNTOS. Não existe "só enviar o pacote", "só construir a imagem" nem
 *       "só registrar o token": a imagem precisa do pacote, o container precisa da imagem e o token precisa do container (e sem o token
 *       o app não consegue falar com o agente, que já nasce exigindo-o). Por isso são UM item, que depende do Docker.</li>
 * </ul>
 *
 * Não entram aqui o Git (tem o fluxo e a confirmação dele, em Servidor > Máquina), o Claude nem nenhuma IA (o app nunca instala IA).
 */
public final class CatalogoDeInstalacao {

    public static final String ID_DO_DOCKER = "docker";
    public static final String ID_DO_AGENTE = "agente";

    private static final ItemDeInstalacao DOCKER = new ItemDeInstalacao(ID_DO_DOCKER, "Docker",
        "Roda containers. O agente e os seus projetos precisam dele.",
        "Instala como administrador, sem mexer no firewall.",
        "Debian ou Ubuntu, com root ou sudo sem senha.",
        List.of(),
        List.of(PassoDeInstalacao.DOCKER),
        EnumSet.of(Item.SISTEMA, Item.PERMISSAO, Item.DOCKER),
        RelatorioDeVerificacao::dockerAusente,
        r -> !r.dockerAusente());

    private static final ItemDeInstalacao AGENTE = new ItemDeInstalacao(ID_DO_AGENTE, "Agente de monitoramento",
        "Mostra CPU, memória, disco, processos e containers.",
        "O Docker (equivale a root); escuta só em 127.0.0.1.",
        "Espaço e porta livres e internet.",
        List.of(ID_DO_DOCKER),
        List.of(PassoDeInstalacao.ENVIAR_PACOTE, PassoDeInstalacao.CONSTRUIR_IMAGEM, PassoDeInstalacao.SUBIR_CONTAINER,
            PassoDeInstalacao.SONDAR, PassoDeInstalacao.REGISTRAR_TOKEN),
        EnumSet.allOf(Item.class),
        r -> true,
        r -> false);

    private static final List<ItemDeInstalacao> ITENS = List.of(DOCKER, AGENTE);

    private CatalogoDeInstalacao() { }

    /** Os itens, na ordem em que a tela os mostra (e na ordem em que rodam: quem depende vem depois). */
    public static List<ItemDeInstalacao> itens() {
        return ITENS;
    }
}
