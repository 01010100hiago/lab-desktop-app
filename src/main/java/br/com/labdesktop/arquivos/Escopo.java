package br.com.labdesktop.arquivos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Até onde o gerenciador de arquivos pode ir e o que ele nunca apaga. Cada modo da tela é um escopo:
 * <ul>
 *   <li><b>projeto</b>: a pasta do projeto ({@code /srv/lab/projects/orbita}); sair dela é impossível;</li>
 *   <li><b>servidor</b>: a pasta que o usuário escolheu no seletor (Projetos, Minha pasta ou, em Avançado, {@code /});</li>
 * </ul>
 * A raiz do escopo é o teto: nenhum caminho, nenhum {@code ..} e (com o {@link ServicoDeArquivos}) nenhum link simbólico leva
 * para fora dela.
 *
 * <p>Os <b>intocáveis</b> são os caminhos que nenhuma tela apaga: a raiz do escopo, a raiz dos projetos, a pasta pessoal do
 * usuário e {@code /}. Apagar também é recusado para um ANCESTRAL de um intocável (apagar {@code /srv/lab} apagaria a raiz dos
 * projetos junto).
 *
 * @param nome       como a raiz aparece na tela ("Projetos", "orbita", "Minha pasta", "Tudo (/)")
 * @param raiz       o teto, em forma canônica
 * @param intocaveis o que nunca se apaga nem se move
 * @param blindados  pastas cujo CONTEÚDO inteiro também é intocável (em "Este computador": o cofre e os dados do próprio app, para o gerenciador de
 *                   arquivos, até no modo "Tudo", nunca apagar nem mover o cofre, o token, as preferências nem os backups); vazio no servidor remoto
 */
public record Escopo(String nome, String raiz, Set<String> intocaveis, Set<String> blindados) {

    /** Sem blindados (o servidor remoto). */
    public Escopo(String nome, String raiz, Set<String> intocaveis) {
        this(nome, raiz, intocaveis, Set.of());
    }

    public Escopo {
        try {
            raiz = CaminhoRemoto.normalizar(raiz);
            Set<String> limpos = new LinkedHashSet<>();
            limpos.add(raiz);
            limpos.add(CaminhoRemoto.RAIZ);
            for (String i : intocaveis == null ? Set.<String>of() : intocaveis) {
                if (i != null && !i.isBlank()) {
                    limpos.add(CaminhoRemoto.normalizar(i));
                }
            }
            intocaveis = Set.copyOf(limpos);
            Set<String> b = new LinkedHashSet<>();
            for (String i : blindados == null ? Set.<String>of() : blindados) {
                if (i != null && !i.isBlank()) {
                    b.add(CaminhoRemoto.normalizar(i));
                }
            }
            blindados = Set.copyOf(b);
        } catch (ErroSftp e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (nome == null || nome.isBlank()) {
            nome = CaminhoRemoto.nome(raiz).isEmpty() ? "Tudo (/)" : CaminhoRemoto.nome(raiz);
        }
    }

    /** A pasta de UM projeto, dentro da raiz dos projetos do servidor. */
    public static Escopo projeto(String raizDosProjetos, String nomeDoProjeto, String home) throws ErroSftp {
        String nome = CaminhoRemoto.validarNome(nomeDoProjeto);
        String raiz = CaminhoRemoto.juntar(raizDosProjetos, nome);
        return new Escopo(nome, raiz, intocaveis(raizDosProjetos, home));
    }

    /**
     * SÓ a pasta {@code backups} de um projeto ({@code <projetos>/<projeto>/backups}): a cerca do download de backups. Um link dentro dela que aponta para o resto do projeto
     * ({@code backups/x -> ../.env}) ou para fora ({@code -> /etc/passwd}) leva para fora desta raiz e é recusado, coisa que a cerca da pasta inteira do projeto deixaria passar.
     */
    public static Escopo projetoBackups(String raizDosProjetos, String nomeDoProjeto, String home) throws ErroSftp {
        String nome = CaminhoRemoto.validarNome(nomeDoProjeto);
        String raiz = CaminhoRemoto.juntar(CaminhoRemoto.juntar(raizDosProjetos, nome), PlanoDeBackups.PASTA);
        return new Escopo("backups", raiz, intocaveis(raizDosProjetos, home));
    }

    /** O servidor inteiro a partir de uma pasta (Projetos, Minha pasta, ou {@code /}). */
    public static Escopo servidor(String nome, String raiz, String raizDosProjetos, String home) {
        return new Escopo(nome, raiz, intocaveis(raizDosProjetos, home));
    }

    private static Set<String> intocaveis(String raizDosProjetos, String home) {
        Set<String> s = new LinkedHashSet<>();
        if (raizDosProjetos != null) {
            s.add(raizDosProjetos);
        }
        if (home != null) {
            s.add(home);
        }
        return s;
    }

    /** O mesmo escopo, com estas pastas (e tudo dentro delas) também intocáveis. */
    public Escopo comBlindados(Set<String> pastas) {
        return new Escopo(nome, raiz, intocaveis, pastas);
    }

    /** {@code true} se a raiz é {@code /} (o modo "Tudo"). */
    public boolean ehRaizDoServidor() {
        return raiz.equals(CaminhoRemoto.RAIZ);
    }

    /**
     * Normaliza o caminho e confere que fica dentro da raiz.
     *
     * @throws ErroSftp {@code NOME_INVALIDO} ou {@code FORA_DA_RAIZ}
     */
    public String validar(String caminho) throws ErroSftp {
        String n = CaminhoRemoto.normalizar(caminho);
        if (!CaminhoRemoto.dentroDe(raiz, n)) {
            throw ErroSftp.foraDaRaiz(n);
        }
        return n;
    }

    public boolean contem(String caminho) {
        try {
            validar(caminho);
            return true;
        } catch (ErroSftp e) {
            return false;
        }
    }

    /**
     * {@code true} se o caminho é um intocável ou um ancestral de algum: não se apaga nem se move. O que está DENTRO de um
     * intocável (os projetos, dentro da raiz dos projetos) é comum e liberado.
     */
    public boolean protegido(String caminho) {
        String n;
        try {
            n = CaminhoRemoto.normalizar(caminho);
        } catch (ErroSftp e) {
            return true;
        }
        for (String i : intocaveis) {
            if (CaminhoRemoto.dentroDe(n, i)) { // n == i, ou n é ancestral de i
                return true;
            }
        }
        for (String b : blindados) {
            if (CaminhoRemoto.dentroDe(n, b) || CaminhoRemoto.dentroDe(b, n)) { // n é a pasta blindada, uma acima dela, ou algo DENTRO dela
                return true;
            }
        }
        return false;
    }

    /** O caminho é uma pasta do próprio app (cofre, dados, logs) ou está dentro de uma: o motivo da proteção é outro. */
    public boolean blindado(String caminho) {
        String n;
        try {
            n = CaminhoRemoto.normalizar(caminho);
        } catch (ErroSftp e) {
            return false;
        }
        for (String b : blindados) {
            if (CaminhoRemoto.dentroDe(b, n)) {
                return true;
            }
        }
        return false;
    }

    /** Os trechos do caminho a partir da raiz do escopo, para as migalhas ({@code []} na própria raiz). */
    public List<String> trechosAPartirDaRaiz(String caminho) {
        String rel = CaminhoRemoto.relativo(raiz, caminho);
        return rel.isEmpty() ? List.of() : List.of(rel.split("/"));
    }
}
