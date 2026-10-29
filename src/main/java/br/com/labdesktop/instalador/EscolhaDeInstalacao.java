package br.com.labdesktop.instalador;

import br.com.labdesktop.instalador.ResultadoDaVerificacao.Item;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * O que o usuário escolheu na tela "O que instalar neste servidor": <b>Completo</b> (tudo o que o app sabe instalar neste servidor),
 * <b>Escolher</b> (os itens que ele marcou) ou <b>Só SSH</b> (nada). Modelo PURO, sem Swing e sem servidor: recebe a lista de descrições
 * ({@link ItemDeInstalacao}) e o relatório das verificações (só leitura) e responde quais passos rodam, quais verificações valem e se a
 * combinação é válida.
 *
 * <p><b>Nunca instala metade.</b> Um item que depende de outro (o agente precisa do Docker) só vale se a dependência já está no servidor
 * ({@link ItemDeInstalacao#resolvidoNoServidor}) ou se foi marcada junto; senão a combinação é inválida, {@link #problemas()} diz por que
 * em português simples e o {@link InstaladorDeAgente} recusa a escolha.
 */
public final class EscolhaDeInstalacao {

    /** As três respostas da tela. */
    public enum Tipo {
        COMPLETO, ESCOLHER, SO_SSH
    }

    private final Tipo tipo;
    private final List<ItemDeInstalacao> catalogo;
    private final RelatorioDeVerificacao relatorio;
    private final Set<String> marcados;

    private EscolhaDeInstalacao(Tipo tipo, List<ItemDeInstalacao> catalogo, RelatorioDeVerificacao relatorio, Set<String> marcados) {
        this.tipo = tipo;
        this.catalogo = List.copyOf(catalogo);
        this.relatorio = relatorio;
        this.marcados = Set.copyOf(marcados);
    }

    /** Completo: todos os itens que a tela oferece para este servidor (o Docker só se faltar). */
    public static EscolhaDeInstalacao completo(List<ItemDeInstalacao> catalogo, RelatorioDeVerificacao relatorio) {
        Set<String> todos = new LinkedHashSet<>();
        for (ItemDeInstalacao i : catalogo) {
            if (i.oferecido().test(relatorio)) {
                todos.add(i.id());
            }
        }
        return new EscolhaDeInstalacao(Tipo.COMPLETO, catalogo, relatorio, todos);
    }

    /**
     * Escolher: só os itens marcados.
     *
     * @throws IllegalArgumentException um id marcado que não existe ou que a tela não oferece para este servidor
     */
    public static EscolhaDeInstalacao escolher(List<ItemDeInstalacao> catalogo, RelatorioDeVerificacao relatorio, Collection<String> marcados) {
        Set<String> oferecidos = new LinkedHashSet<>();
        for (ItemDeInstalacao i : catalogo) {
            if (i.oferecido().test(relatorio)) {
                oferecidos.add(i.id());
            }
        }
        for (String id : marcados) {
            if (!oferecidos.contains(id)) {
                throw new IllegalArgumentException("Este item não é oferecido para este servidor: " + id);
            }
        }
        return new EscolhaDeInstalacao(Tipo.ESCOLHER, catalogo, relatorio, new LinkedHashSet<>(marcados));
    }

    /** Só SSH, não instalar nada: o servidor segue usável por SSH (terminal, Segurança e Arquivos). */
    public static EscolhaDeInstalacao soSsh() {
        return new EscolhaDeInstalacao(Tipo.SO_SSH, List.of(), null, Set.of());
    }

    public Tipo tipo() {
        return tipo;
    }

    /** Os itens que a tela oferece para este servidor, na ordem do catálogo. */
    public List<ItemDeInstalacao> oferecidos() {
        List<ItemDeInstalacao> l = new ArrayList<>();
        for (ItemDeInstalacao i : catalogo) {
            if (relatorio != null && i.oferecido().test(relatorio)) {
                l.add(i);
            }
        }
        return l;
    }

    /** Os itens escolhidos (Completo: todos os oferecidos; Só SSH: nenhum), na ordem do catálogo. */
    public List<ItemDeInstalacao> itens() {
        List<ItemDeInstalacao> l = new ArrayList<>();
        for (ItemDeInstalacao i : catalogo) {
            if (marcados.contains(i.id())) {
                l.add(i);
            }
        }
        return l;
    }

    /** Há algo para instalar? */
    public boolean instalaAlgo() {
        return !marcados.isEmpty();
    }

    /**
     * Por que esta combinação não vale, em português simples (vazio quando vale). Só SSH nunca tem problema. Escolher sem nenhum item e um
     * item que precisa de outro que nem está no servidor nem foi marcado são problemas.
     */
    public List<String> problemas() {
        List<String> p = new ArrayList<>();
        if (tipo == Tipo.SO_SSH) {
            return p;
        }
        if (marcados.isEmpty()) {
            p.add("Marque pelo menos um item, ou escolha \"Só SSH\" para não instalar nada.");
            return p;
        }
        for (ItemDeInstalacao item : itens()) {
            for (String idDaDependencia : item.dependeDe()) {
                ItemDeInstalacao dep = catalogo.stream().filter(c -> c.id().equals(idDaDependencia)).findFirst().orElse(null);
                if (dep == null) {
                    p.add(item.titulo() + " depende de um item que o app não conhece (" + idDaDependencia + "): não dá para instalá-lo.");
                } else if (!marcados.contains(dep.id()) && !dep.resolvidoNoServidor().test(relatorio)) {
                    p.add(item.titulo() + " precisa do " + dep.titulo() + ", que falta neste servidor. "
                        + (dep.oferecido().test(relatorio) ? "Marque o " + dep.titulo() + " também, ou desmarque o " + item.titulo() + "."
                        : "Resolva isso no servidor antes."));
                }
            }
        }
        return p;
    }

    /** A combinação vale: dá para instalar (ou, em Só SSH, não há o que instalar). */
    public boolean valida() {
        return problemas().isEmpty();
    }

    /** Os passos do instalador que esta escolha executa, na ordem da instalação (nunca um passo de um item não escolhido). */
    public List<PassoDeInstalacao> passos() {
        Set<PassoDeInstalacao> s = EnumSet.noneOf(PassoDeInstalacao.class);
        for (ItemDeInstalacao i : itens()) {
            s.addAll(i.passos());
        }
        return List.copyOf(s);
    }

    /** As verificações que valem para esta escolha: só as dos itens escolhidos. */
    public Set<Item> verificacoes() {
        Set<Item> s = EnumSet.noneOf(Item.class);
        for (ItemDeInstalacao i : itens()) {
            s.addAll(i.verificacoes());
        }
        return s;
    }

    /** Os ids marcados (Completo: os oferecidos). */
    public Set<String> marcados() {
        return marcados;
    }
}
