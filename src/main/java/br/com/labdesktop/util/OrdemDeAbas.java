package br.com.labdesktop.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A ordem das abas de uma barra de navegação (as do servidor ou as do projeto) e quais o usuário escondeu, como a preferência guarda. Modelo PURO (sem Swing),
 * com a regra de ouro de que NUNCA se perde uma aba por causa da preferência:
 *
 * <ul>
 *   <li>id que a preferência traz e esta versão do app não conhece (escrito por uma versão antiga, ou à mão) é ignorado;</li>
 *   <li>id que esta versão conhece e a preferência não traz (aba nova de uma versão futura) vai para o FIM, na ordem padrão;</li>
 *   <li>id repetido vale só na primeira vez;</li>
 *   <li>se tudo estiver escondido (arquivo corrompido ou editado à mão), aparecem todas.</li>
 * </ul>
 *
 * <p>Imutável. A lista {@code ordem} guarda só o que o usuário mexeu: vazia é a ordem padrão. {@code ocultas} é o conjunto de ids escondidos.
 *
 * @param ordem  os ids na ordem pedida (pode faltar id e pode sobrar id desconhecido; {@link #ordenar} resolve)
 * @param ocultas os ids que o usuário escondeu
 */
public record OrdemDeAbas(List<String> ordem, Set<String> ocultas) {

    /** O formato de um id: o mesmo dos ids das abas ({@code "visao-geral"}). Qualquer outra coisa no arquivo é descartada. */
    private static final Pattern ID_VALIDO = Pattern.compile("[a-z0-9-]{1,40}");
    /** Mais ids que isto num arquivo de preferências só pode ser corrompido ou hostil. */
    private static final int MAXIMO_DE_IDS = 64;

    /** Sem nada guardado: a ordem padrão e nenhuma aba escondida. */
    public static final OrdemDeAbas PADRAO = new OrdemDeAbas(List.of(), Set.of());

    public OrdemDeAbas {
        ordem = limpar(ordem);
        ocultas = Set.copyOf(limpar(ocultas == null ? List.of() : new ArrayList<>(ocultas)));
    }

    private static List<String> limpar(Collection<String> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<String> unicos = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && ID_VALIDO.matcher(id).matches() && unicos.size() < MAXIMO_DE_IDS) {
                unicos.add(id);
            }
        }
        return List.copyOf(unicos);
    }

    /**
     * Todos os ids conhecidos, na ordem do usuário: primeiro os da preferência que existem nesta versão (na ordem dela), depois os conhecidos que a
     * preferência não traz (na ordem padrão). Nunca perde nem repete um id conhecido.
     */
    public List<String> ordenar(List<String> conhecidos) {
        Set<String> saida = new LinkedHashSet<>();
        for (String id : ordem) {
            if (conhecidos.contains(id)) {
                saida.add(id);
            }
        }
        saida.addAll(conhecidos);
        return List.copyOf(saida);
    }

    /** Os ids que aparecem na barra, na ordem do usuário (os escondidos ficam de fora; se não sobrar nenhum, aparecem todos). */
    public List<String> visiveis(List<String> conhecidos) {
        List<String> todos = ordenar(conhecidos);
        List<String> aparecem = todos.stream().filter(id -> !ocultas.contains(id)).toList();
        return aparecem.isEmpty() ? todos : aparecem;
    }

    public boolean oculta(String id) {
        return ocultas.contains(id);
    }

    /** O usuário mexeu em algo (ordem ou esconder)? A preferência padrão não grava nada. */
    public boolean ehPadrao() {
        return ordem.isEmpty() && ocultas.isEmpty();
    }

    /** Move o id um passo (-1 sobe, +1 desce) dentro da lista completa; no começo ou no fim não faz nada. */
    public OrdemDeAbas mover(List<String> conhecidos, String id, int passo) {
        List<String> lista = new ArrayList<>(ordenar(conhecidos));
        int de = lista.indexOf(id);
        int para = de + (passo < 0 ? -1 : 1);
        if (de < 0 || para < 0 || para >= lista.size()) {
            return this;
        }
        lista.remove(de);
        lista.add(para, id);
        return new OrdemDeAbas(lista, ocultas);
    }

    /** Põe o id numa posição (0 é a primeira) da lista completa, para arrastar. */
    public OrdemDeAbas levarPara(List<String> conhecidos, String id, int posicao) {
        List<String> lista = new ArrayList<>(ordenar(conhecidos));
        if (!lista.remove(id)) {
            return this;
        }
        lista.add(Math.max(0, Math.min(posicao, lista.size())), id);
        return new OrdemDeAbas(lista, ocultas);
    }

    /**
     * Pode esconder este id? Não pode o protegido (a aba que está na tela) nem o último que ainda aparece entre os {@code disponiveis} (as abas que o servidor
     * tem; uma aba que o servidor não tem nunca aparece, escondida pelo usuário ou não).
     */
    public boolean podeEsconder(List<String> conhecidos, Collection<String> disponiveis, String id, String protegida) {
        if (id == null || id.equals(protegida) || !conhecidos.contains(id) || ocultas.contains(id)) {
            return false;
        }
        return conhecidos.stream().filter(disponiveis::contains).anyMatch(outro -> !outro.equals(id) && !ocultas.contains(outro));
    }

    public OrdemDeAbas escondendo(String id) {
        Set<String> novas = new LinkedHashSet<>(ocultas);
        novas.add(id);
        return new OrdemDeAbas(ordem, novas);
    }

    public OrdemDeAbas mostrando(String id) {
        Set<String> novas = new LinkedHashSet<>(ocultas);
        novas.remove(id);
        return new OrdemDeAbas(ordem, novas);
    }
}
