package br.com.labdesktop.arquivos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * As operações do gerenciador de arquivos, com a CERCA do {@link Escopo}: tudo o que a tela pede passa por aqui antes de virar
 * chamada SFTP. Aqui moram as regras que valem em qualquer servidor:
 * <ul>
 *   <li><b>Nada fora da raiz do modo.</b> O caminho é normalizado ({@code ..} some) e conferido; e como um LINK dentro da pasta
 *       do projeto pode levar para {@code /} ou para a pasta de outro projeto, cada pasta aberta e cada arquivo lido é resolvido
 *       componente a componente (um {@code lstat} por trecho novo, com cache) e recusado se o destino real sai da raiz.</li>
 *   <li><b>Nunca apagar nem mover o que sustenta o modo</b> (raiz, raiz dos projetos, home, {@code /}, e qualquer ancestral
 *       deles): ver {@link Escopo#protegido}.</li>
 *   <li><b>Nome é um nome</b>: sem barra, sem controle, sem {@code ..}.</li>
 * </ul>
 * Bloqueia (rede): chame de thread de fundo.
 */
public final class ServicoDeArquivos {

    /** Mais saltos que isto num caminho de links = laço de links. */
    private static final int MAXIMO_DE_SALTOS = 32;

    private final ClienteSftp cliente;
    private final Escopo escopo;
    /** caminho -> caminho real, só de trechos que NÃO são link (um link pode mudar de destino e é sempre relido). */
    private final Map<String, String> reais = new ConcurrentHashMap<>();
    private volatile String raizReal;

    public ServicoDeArquivos(ClienteSftp cliente, Escopo escopo) {
        this.cliente = cliente;
        this.escopo = escopo;
    }

    public Escopo escopo() {
        return escopo;
    }

    public ClienteSftp cliente() {
        return cliente;
    }

    /** Esquece o que sabe dos trechos já conferidos (o botão Atualizar: a pasta pode ter mudado por fora). */
    public void esquecerConferencias() {
        reais.clear();
        raizReal = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Cerca
    // ---------------------------------------------------------------------------------------------

    /**
     * O caminho que o servidor de fato usa (seguindo todos os links) se ele fica dentro da raiz do modo.
     *
     * @return o caminho real
     * @throws ErroSftp {@code FORA_DA_RAIZ} se o caminho, ou um link no meio dele, leva para fora
     */
    public String resolverDentroDaRaiz(String caminho) throws ErroSftp {
        String c = escopo.validar(caminho);
        String real = resolver(c, new int[] {0});
        if (!CaminhoRemoto.dentroDe(raizReal(), real)) {
            throw new ErroSftp(ErroSftp.Tipo.FORA_DA_RAIZ, "Este link leva para fora de \"" + escopo.nome()
                + "\". Por segurança, não o sigo.");
        }
        return real;
    }

    private String raizReal() throws ErroSftp {
        String r = raizReal;
        if (r == null) {
            r = resolver(escopo.raiz(), new int[] {0});
            raizReal = r;
        }
        return r;
    }

    private String resolver(String caminho, int[] saltos) throws ErroSftp {
        List<String> partes = CaminhoRemoto.partes(caminho);
        String atual = CaminhoRemoto.RAIZ;
        for (int i = 0; i < partes.size(); i++) {
            String candidato = atual.equals("/") ? "/" + partes.get(i) : atual + "/" + partes.get(i);
            String conhecido = reais.get(candidato);
            if (conhecido != null) {
                atual = conhecido;
                continue;
            }
            EntradaRemota e;
            try {
                e = cliente.estatisticas(candidato);
            } catch (ErroSftp falha) {
                if (falha.tipo() == ErroSftp.Tipo.NAO_EXISTE) {
                    // não existe (ainda): o que sobra do caminho não tem como ser link; vale como está
                    StringBuilder resto = new StringBuilder(candidato);
                    for (int j = i + 1; j < partes.size(); j++) {
                        resto.append('/').append(partes.get(j));
                    }
                    return resto.toString();
                }
                throw falha;
            }
            if (e.link()) {
                if (++saltos[0] > MAXIMO_DE_SALTOS) {
                    throw new ErroSftp(ErroSftp.Tipo.FORA_DA_RAIZ, "Há links demais encadeados neste caminho.");
                }
                if (e.destinoDoLink() == null) {
                    throw new ErroSftp(ErroSftp.Tipo.FORA_DA_RAIZ, "Não consegui conferir para onde este link aponta.");
                }
                String alvo = CaminhoRemoto.resolverAlvo(atual, e.destinoDoLink());
                atual = resolver(alvo, saltos);
            } else {
                atual = candidato;
                reais.put(candidato, atual);
            }
        }
        return atual;
    }

    private String pastaVerificada(String pasta) throws ErroSftp {
        return resolverDentroDaRaiz(pasta);
    }

    // ---------------------------------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------------------------------

    /** A pasta pessoal do usuário no servidor. */
    public String home() throws ErroSftp {
        return CaminhoRemoto.normalizar(cliente.caminhoCanonico("."));
    }

    public ListagemDePasta listar(String pasta, int limite, Predicate<String> nomeAceito) throws ErroSftp {
        String real = pastaVerificada(pasta);
        ListagemDePasta l = cliente.listar(real, limite, nomeAceito);
        // os itens mantêm o caminho pelo qual o usuário chegou (o link), não o real
        String como = escopo.validar(pasta);
        if (como.equals(real)) {
            return l;
        }
        List<EntradaRemota> reescritas = new ArrayList<>(l.entradas().size());
        for (EntradaRemota e : l.entradas()) {
            reescritas.add(new EntradaRemota(e.nome(), como.equals("/") ? "/" + e.nome() : como + "/" + e.nome(), e.tipo(), e.link(),
                e.tamanho(), e.modificadoEm(), e.permissoes(), e.dono(), e.grupo(), e.destinoDoLink()));
        }
        return new ListagemDePasta(como, reescritas, l.total(), l.truncada(), l.totalExato(), l.lidaEm());
    }

    public EntradaRemota estatisticas(String caminho) throws ErroSftp {
        String c = escopo.validar(caminho);
        pastaVerificada(CaminhoRemoto.pai(c));
        return cliente.estatisticas(c);
    }

    // ---------------------------------------------------------------------------------------------
    // Criar, renomear, mover, apagar
    // ---------------------------------------------------------------------------------------------

    public String criarPasta(String pasta, String nome) throws ErroSftp {
        String n = CaminhoRemoto.validarNome(nome);
        String dentro = pastaVerificada(pasta);
        String novo = CaminhoRemoto.juntar(dentro, n);
        cliente.criarPasta(novo);
        return CaminhoRemoto.juntar(escopo.validar(pasta), n);
    }

    public String criarArquivoVazio(String pasta, String nome) throws ErroSftp {
        String n = CaminhoRemoto.validarNome(nome);
        String dentro = pastaVerificada(pasta);
        cliente.criarArquivoVazio(CaminhoRemoto.juntar(dentro, n));
        return CaminhoRemoto.juntar(escopo.validar(pasta), n);
    }

    /** Muda o nome (na mesma pasta). Devolve o caminho novo. */
    public String renomear(EntradaRemota entrada, String novoNome) throws ErroSftp {
        String n = CaminhoRemoto.validarNome(novoNome);
        String atual = escopo.validar(entrada.caminho());
        exigirNaoProtegido(atual, "renomear");
        String pasta = CaminhoRemoto.pai(atual);
        String dentro = pastaVerificada(pasta);
        if (n.equals(entrada.nome())) {
            return atual;
        }
        cliente.renomear(CaminhoRemoto.juntar(dentro, entrada.nome()), CaminhoRemoto.juntar(dentro, n));
        return CaminhoRemoto.juntar(pasta, n);
    }

    /** O resultado de mover (ou apagar) vários: o que deu certo e o que falhou, com a frase de cada falha. */
    public record ResultadoDoLote(int feitos, List<Falha> falhas) {

        /** Uma falha de um item. */
        public record Falha(String nome, String mensagem) { }

        public ResultadoDoLote {
            falhas = List.copyOf(falhas);
        }

        public boolean tudoCerto() {
            return falhas.isEmpty();
        }
    }

    /**
     * Move itens para outra pasta do mesmo modo, um a um: o que não puder (já existe no destino, é a própria pasta, é protegido)
     * fica de fora com o motivo, e o resto vai.
     */
    public ResultadoDoLote mover(List<EntradaRemota> entradas, String pastaDeDestino) throws ErroSftp {
        String destinoReal = pastaVerificada(pastaDeDestino);
        String destinoComo = escopo.validar(pastaDeDestino);
        int feitos = 0;
        List<ResultadoDoLote.Falha> falhas = new ArrayList<>();
        for (EntradaRemota e : entradas) {
            try {
                String origem = escopo.validar(e.caminho());
                exigirNaoProtegido(origem, "mover");
                if (CaminhoRemoto.dentroDe(origem, destinoComo) || CaminhoRemoto.dentroDe(origem, destinoReal)) {
                    throw new ErroSftp(ErroSftp.Tipo.PROTEGIDO, "Não dá para mover uma pasta para dentro dela mesma.");
                }
                String paiReal = pastaVerificada(CaminhoRemoto.pai(origem));
                if (paiReal.equals(destinoReal)) {
                    continue; // já está lá: nada a fazer, nem é falha
                }
                cliente.renomear(CaminhoRemoto.juntar(paiReal, e.nome()), CaminhoRemoto.juntar(destinoReal, e.nome()));
                feitos++;
            } catch (ErroSftp falha) {
                if (falha.tipo() == ErroSftp.Tipo.SEM_CONEXAO) {
                    throw falha; // sem conexão não adianta tentar o resto
                }
                falhas.add(new ResultadoDoLote.Falha(e.nome(), falha.mensagemSimples()));
            }
        }
        return new ResultadoDoLote(feitos, falhas);
    }

    /** Quantos bytes há num item (a soma de dentro, se é pasta): para o aviso de transferência grande antes de baixar. */
    public long somarTamanho(EntradaRemota entrada, Cancelamento cancelamento) throws ErroSftp {
        String c = escopo.validar(entrada.caminho());
        if (entrada.ehPasta() && !entrada.link()) {
            return cliente.resumir(resolverDentroDaRaiz(c), cancelamento).bytes();
        }
        return Math.max(0, entrada.tamanho());
    }

    /** O que vai ser apagado, para o diálogo de confirmação dizer ("143 itens, 12 MB"). */
    public ResumoDeTamanho resumirParaApagar(EntradaRemota entrada, Cancelamento cancelamento) throws ErroSftp {
        String c = escopo.validar(entrada.caminho());
        exigirNaoProtegido(c, "apagar");
        pastaVerificada(CaminhoRemoto.pai(c));
        if (entrada.ehPasta() && !entrada.link()) {
            return cliente.resumir(c, cancelamento);
        }
        return new ResumoDeTamanho(1, 0, Math.max(0, entrada.tamanho()), true);
    }

    /**
     * Apaga. Um link é apagado como link (o destino fica); uma pasta vai com tudo dentro, sem seguir nenhum link.
     *
     * @throws ErroSftp {@code PROTEGIDO} para a raiz do modo, a raiz dos projetos, a home, {@code /} e seus ancestrais
     */
    public void apagar(EntradaRemota entrada) throws ErroSftp {
        String c = escopo.validar(entrada.caminho());
        exigirNaoProtegido(c, "apagar");
        String paiReal = pastaVerificada(CaminhoRemoto.pai(c));
        String alvo = CaminhoRemoto.juntar(paiReal, entrada.nome());
        cliente.apagar(alvo, entrada.ehPasta() && !entrada.link());
    }

    private void exigirNaoProtegido(String caminho, String verbo) throws ErroSftp {
        if (escopo.protegido(caminho)) {
            throw new ErroSftp(ErroSftp.Tipo.PROTEGIDO, "Esta pasta sustenta o gerenciador de arquivos (é a raiz, a pasta dos projetos "
                + "ou a pasta pessoal), por isso não deixo " + verbo + ".");
        }
    }
}
