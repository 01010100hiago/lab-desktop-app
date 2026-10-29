package br.com.labdesktop.instalador;

import br.com.labdesktop.instalador.ResultadoDaVerificacao.Item;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A descrição de UMA coisa que o app sabe instalar num servidor, para a tela "O que instalar neste servidor". É só dado: a tela e o modelo
 * da escolha ({@link EscolhaDeInstalacao}) leem a lista de {@link CatalogoDeInstalacao} e não conhecem nenhum item pelo nome, então um item
 * novo no futuro é uma descrição a mais (e os passos que o instalador já sabe fazer), sem código novo na tela.
 *
 * <p>Um item só entra no catálogo se o instalador consegue fazê-lo de forma INDEPENDENTE: com os passos dele sozinhos o servidor fica num
 * estado coerente (nunca "metade"). O que depende de outro item diz isso em {@code dependeDe}.
 *
 * @param id                  identificador estável (minúsculas), usado nas dependências
 * @param titulo              o nome do item, para o usuário
 * @param frase               o que o item acrescenta, em uma frase simples
 * @param alcance             o que o item passa a alcançar no servidor (segurança), em uma frase simples
 * @param exige               o que o servidor precisa ter ou permitir (além do que está em {@code dependeDe}), em uma frase simples
 * @param dependeDe           ids dos itens que precisam estar prontos antes (no servidor ou marcados junto)
 * @param passos              os passos do instalador que este item executa; a união dos itens escolhidos é o que roda
 * @param verificacoes        as verificações prévias que importam para este item: só elas valem (e podem bloquear) quando ele é escolhido
 * @param oferecido           o item aparece na tela para este servidor? (o Docker só aparece se faltar)
 * @param resolvidoNoServidor o que este item dá já está no servidor, de modo que quem depende dele não precisa marcá-lo?
 */
public record ItemDeInstalacao(String id, String titulo, String frase, String alcance, String exige, List<String> dependeDe,
                               List<PassoDeInstalacao> passos, Set<Item> verificacoes, Predicate<RelatorioDeVerificacao> oferecido,
                               Predicate<RelatorioDeVerificacao> resolvidoNoServidor) {

    public ItemDeInstalacao {
        if (id == null || !id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("id do item");
        }
        if (passos == null || passos.isEmpty()) {
            throw new IllegalArgumentException("um item sem passos não instala nada: " + id);
        }
        dependeDe = List.copyOf(dependeDe);
        passos = List.copyOf(passos);
        verificacoes = Set.copyOf(verificacoes);
    }
}
