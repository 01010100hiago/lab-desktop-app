package br.com.labdesktop.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * O que a janela "Criar repositório Git" mostra ANTES de qualquer escrita no projeto: se dá para criar, a identidade, os arquivos que entrariam, o que fica de fora (arquivos grandes,
 * repositórios dentro do projeto), as linhas que o app acrescentaria ao {@code .gitignore} e o que o impede.
 *
 * @param situacao        se dá para criar
 * @param falha           a explicação quando não dá (Git ausente, antigo, pasta de outro dono...); senão {@code null}
 * @param versaoDoGit     a versão lida ({@code git version 2.43.0}), ou vazio
 * @param nomeAtual       o {@code user.name} que o git já usa nesta pasta (global ou local), ou vazio
 * @param emailAtual      o {@code user.email} que o git já usa, ou vazio
 * @param arquivos        os arquivos que um {@code git add} levaria, já sem os ignorados e sem os repositórios de dentro (caminhos com barra, relativos à pasta)
 * @param grandes         os que passam de {@link CriadorDeRepositorio#LIMITE_DE_TAMANHO} bytes (estão em {@code arquivos}; ficam de fora a menos que o usuário marque a caixa)
 * @param aninhados       os repositórios dentro do projeto ({@code pasta/}): ficam de fora
 * @param gitignoreAtual  o texto do {@code .gitignore} de hoje, ou {@code null} se o projeto não tem
 * @param segredosQueEntrariam arquivos com cara de segredo que um {@code .gitignore} de dentro do projeto deixaria entrar: impede a criação
 * @param protecaoConfirmada o {@code git check-ignore} confirmou que as linhas de segredo funcionam
 * @param listaIncompleta alguma pasta tem arquivos demais para o app listar: a criação fica desligada
 * @param ignoradosPorNome arquivos cujo nome o app não consegue pôr numa linha de {@code .gitignore} (controle no nome): ficam de fora do commit
 */
public record ConferenciaDoRepositorio(Situacao situacao, FalhaDoGit falha, String versaoDoGit, String nomeAtual, String emailAtual, List<String> arquivos, List<ArquivoGrande> grandes,
                                       List<String> aninhados, String gitignoreAtual, List<String> segredosQueEntrariam, boolean protecaoConfirmada,
                                       boolean listaIncompleta, int ignoradosPorNome) {

    /** Se dá para criar. */
    public enum Situacao {
        PODE_CRIAR,
        JA_E_REPOSITORIO,
        DENTRO_DE_OUTRO,
        GIT_AUSENTE,
        GIT_ANTIGO,
        /** Qualquer outra coisa que impediu de conferir (a {@code falha} explica). */
        NAO_CONFERI
    }

    /** Um arquivo acima do limite de tamanho. */
    public record ArquivoGrande(String caminho, long tamanho) { }

    public ConferenciaDoRepositorio {
        arquivos = List.copyOf(arquivos);
        grandes = List.copyOf(grandes);
        aninhados = List.copyOf(aninhados);
        segredosQueEntrariam = List.copyOf(segredosQueEntrariam);
        versaoDoGit = versaoDoGit == null ? "" : versaoDoGit;
        nomeAtual = nomeAtual == null ? "" : nomeAtual;
        emailAtual = emailAtual == null ? "" : emailAtual;
    }

    /** Uma conferência que parou antes de listar: só a situação e a explicação. */
    public static ConferenciaDoRepositorio parou(Situacao situacao, FalhaDoGit falha, String versao) {
        return new ConferenciaDoRepositorio(situacao, falha, versao, "", "", List.of(), List.of(), List.of(), null, List.of(), false, false, 0);
    }

    public boolean temIdentidade() {
        return !nomeAtual.isBlank() && !emailAtual.isBlank();
    }

    /** O pedido pode ser feito: dá para criar, a proteção de segredos foi confirmada, nada de segredo entraria e a lista está completa. */
    public boolean liberaACriacao() {
        return situacao == Situacao.PODE_CRIAR && protecaoConfirmada && segredosQueEntrariam.isEmpty() && !listaIncompleta;
    }

    /** O que o app faria no {@code .gitignore}: as linhas novas (para a janela mostrar) e o texto final. */
    public PlanejadorDoGitignore.Plano planoDoGitignore(boolean incluirGrandes) {
        return PlanejadorDoGitignore.planejar(gitignoreAtual, extrasDoGitignore(incluirGrandes));
    }

    /** Os caminhos dos arquivos grandes. */
    public List<String> caminhosGrandes() {
        return grandes.stream().map(ArquivoGrande::caminho).toList();
    }

    /**
     * As linhas que o app acrescentaria ao {@code .gitignore}, com o arquivo grande de fora quando o usuário NÃO marcou a caixa de incluí-los: as fixas e, por último, uma linha por arquivo grande.
     */
    public List<String> extrasDoGitignore(boolean incluirGrandes) {
        List<String> r = new ArrayList<>(aninhados.stream().map(a -> PlanejadorDoGitignore.linhaParaCaminho(a, true)).toList());
        if (!incluirGrandes) {
            for (ArquivoGrande g : grandes) {
                if (ValidadorDeGit.caminhoRelativoValido(g.caminho())) {
                    r.add(PlanejadorDoGitignore.linhaParaCaminho(g.caminho(), false));
                }
            }
        }
        return r;
    }

    /** Quantos arquivos entram: todos, ou todos menos os grandes. */
    public int quantosEntram(boolean incluirGrandes) {
        return incluirGrandes ? arquivos.size() : arquivos.size() - grandes.size();
    }

    /** As primeiras pastas e quantos arquivos há em cada uma (a raiz aparece como {@code (raiz)}), da mais cheia para a menos, até {@code ate}. */
    public List<String> primeirasPastas(int ate) {
        Map<String, Integer> n = new TreeMap<>();
        for (String a : arquivos) {
            int i = a.indexOf('/');
            n.merge(i < 0 ? "(raiz)" : a.substring(0, i) + "/", 1, Integer::sum);
        }
        return n.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue() != 0 ? y.getValue() - x.getValue() : x.getKey().compareTo(y.getKey())).limit(ate)
            .map(e -> e.getKey() + " (" + e.getValue() + ")").toList();
    }
}
