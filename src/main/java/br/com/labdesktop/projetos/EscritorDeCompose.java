package br.com.labdesktop.projetos;

import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Adicionar container": escreve um {@link BlocoDeServico} no {@code docker-compose.yml} de um projeto, com as garantias do usuário:
 * <ol>
 *   <li>{@link #preparar} só LÊ: devolve a diferença legível do que seria escrito (ou, num compose complexo, só o bloco para copiar). Nada muda;</li>
 *   <li>{@link #gravar} só depois do clique: relê o arquivo (se mudou desde a diferença, para), CONFERE o resultado com
 *       {@code docker compose config} (se o Docker o recusa, ou não dá para conferir, NÃO grava), guarda uma cópia datada do compose de hoje, acrescenta
 *       ao {@code .env} as variáveis que faltam (uma senha aleatória que nunca é mostrada; chave que já existe nunca é trocada) e então troca o compose
 *       de uma vez;</li>
 *   <li>só o esqueleto é escrito ({@link ComposeDoProjeto}); o compose do usuário nunca é reescrito;</li>
 *   <li>"Iniciar" é outro clique, de outra tela: este escritor nunca sobe nada.</li>
 * </ol>
 */
public final class EscritorDeCompose {

    /** O que {@link #preparar} mostra ao usuário antes de gravar. */
    public record Previa(String projeto, ComposeDoProjeto.Situacao situacao, String original, String novo, BlocoDeServico bloco, String diferenca,
                         byte[] bytesLidos) {

        /** O app pode escrever neste arquivo (é o esqueleto, ou não existe)? Senão, só mostra o bloco para copiar. */
        public boolean gravavel() {
            return novo != null;
        }

        public String textoParaCopiar() {
            return bloco.textoParaCopiar();
        }
    }

    /** O que {@link #gravar} fez. */
    public record Gravado(String copia, List<String> variaveisNovas, List<String> avisos) {
        public Gravado {
            variaveisNovas = List.copyOf(variaveisNovas);
            avisos = List.copyOf(avisos);
        }
    }

    static final int LIMITE_DO_COMPOSE = 512 * 1024;
    private static final Duration PRAZO_DA_CONFERENCIA = Duration.ofSeconds(60);
    private static final int PERMISSAO_DO_COMPOSE = 0644;
    private static final int PERMISSAO_DO_ENV = 0600;
    private static final DateTimeFormatter DATA_DA_COPIA = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** O valor que a conferência dá às senhas (só precisa estar definido; a senha de verdade nunca sai do app). */
    private static final String VALOR_DA_CONFERENCIA = "conferencia";

    private final AmbienteDeProjetos ambiente;
    private final Clock relogio;
    private final SecureRandom azar;

    public EscritorDeCompose(AmbienteDeProjetos ambiente) {
        this(ambiente, Clock.systemDefaultZone(), new SecureRandom());
    }

    public EscritorDeCompose(AmbienteDeProjetos ambiente, Clock relogio, SecureRandom azar) {
        this.ambiente = ambiente;
        this.relogio = relogio;
        this.azar = azar;
    }

    // ---------------------------------------------------------------------------------------------
    // Ver (só leitura)
    // ---------------------------------------------------------------------------------------------

    /** Lê o compose do projeto e mostra o que o bloco mudaria. Não escreve nada. */
    public Previa preparar(String projeto, BlocoDeServico bloco) throws FalhaDoProjeto {
        exigirProjeto(projeto);
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String pasta = CaminhoRemoto.juntar(ambiente.raizDosProjetos(), projeto);
            if (!c.existe(pasta)) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "A pasta do projeto não existe mais no servidor.");
            }
            byte[] bytes = lerBytes(c, CaminhoRemoto.juntar(pasta, GabaritoDoProjeto.ARQUIVO_COMPOSE));
            String original = decodificar(bytes);
            if (bytes != null && original == null) {
                // não é UTF-8 (um arquivo em Latin-1/ANSI): decodificar corromperia os acentos, então o app não escreve neste arquivo, só mostra o bloco
                return new Previa(projeto, ComposeDoProjeto.Situacao.COMPLEXO, null, null, bloco, "", bytes);
            }
            ComposeDoProjeto.Situacao situacao = ComposeDoProjeto.situacaoDe(original);
            Optional<String> novo = ComposeDoProjeto.acrescentar(original, projeto, bloco);
            String diferenca = novo.map(n -> Diferenca.legivel(original == null ? "" : original, n, 2)).orElse("");
            return new Previa(projeto, situacao, original, novo.orElse(null), bloco, diferenca, bytes);
        } catch (ErroSftp e) {
            throw Falhas.de(e, "ler o docker-compose.yml");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Gravar (depois do clique)
    // ---------------------------------------------------------------------------------------------

    /** Grava o que {@link #preparar} mostrou, com as garantias da classe. {@code previa.gravavel()} tem de ser verdadeiro. */
    public Gravado gravar(Previa previa) throws FalhaDoProjeto {
        if (!previa.gravavel()) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Este compose tem mais coisas do que o app mexe sozinho: copie o bloco e cole no arquivo.");
        }
        exigirProjeto(previa.projeto());
        List<String> avisos = new ArrayList<>();
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String pasta = CaminhoRemoto.juntar(ambiente.raizDosProjetos(), previa.projeto());
            String arquivo = CaminhoRemoto.juntar(pasta, GabaritoDoProjeto.ARQUIVO_COMPOSE);
            if (!Arrays.equals(lerBytes(c, arquivo), previa.bytesLidos())) {
                throw mudou();
            }
            conferirComODocker(pasta, previa);
            // a conferência pode levar até um minuto: relê logo antes de guardar a cópia e de trocar, e só segue se o arquivo é exatamente o que foi
            // visto e conferido (a cópia datada sai destes BYTES, fiéis ao que havia, qualquer que seja a codificação)
            byte[] naHora = lerBytes(c, arquivo);
            if (!Arrays.equals(naHora, previa.bytesLidos())) {
                throw mudou();
            }
            String copia = null;
            if (naHora != null) {
                copia = nomeDaCopia(c, pasta);
                c.gravarAtomico(CaminhoRemoto.juntar(pasta, copia), naHora, PERMISSAO_DO_COMPOSE);
                cuidarDaCopiaNoGit(c, pasta, copia, avisos);
            }
            List<String> variaveisNovas = acrescentarAoEnv(c, pasta, previa.bloco(), avisos);
            c.gravarAtomico(arquivo, previa.novo().getBytes(StandardCharsets.UTF_8), PERMISSAO_DO_COMPOSE);
            return new Gravado(copia, variaveisNovas, avisos);
        } catch (ErroSftp e) {
            if (e.tipo() == ErroSftp.Tipo.OUTRO_DONO) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_PERMISSAO, "Este arquivo é de outro usuário do servidor, e o app não troca arquivo de outro dono. "
                    + "Nada foi gravado: use \"Copiar o bloco\" e cole no arquivo.", null, e);
            }
            throw Falhas.de(e, "gravar o docker-compose.yml");
        }
    }

    /** O padrão que deixa as cópias datadas do compose fora do git. */
    static final String PADRAO_DAS_COPIAS = GabaritoDoProjeto.ARQUIVO_COMPOSE + ".*.bak";

    /**
     * A cópia datada fica na pasta do projeto e iria para o git junto. Se o {@code .gitignore} é exatamente o do esqueleto do app, o padrão entra nele
     * (o app mexe no que é dele); se é do usuário, o app não o reescreve: só avisa, com a linha a acrescentar.
     */
    public static void cuidarDaCopiaNoGit(ClienteSftp c, String pasta, String copia, List<String> avisos) {
        try {
            String ignore = CaminhoRemoto.juntar(pasta, ".gitignore");
            String atual = lerTexto(c, ignore);
            if (atual == null) {
                if (c.existe(CaminhoRemoto.juntar(pasta, ".git"))) {
                    avisos.add("A cópia " + copia + " fica na pasta do projeto e o git a veria como arquivo novo: acrescente " + PADRAO_DAS_COPIAS + " ao .gitignore.");
                }
                return;
            }
            if (atual.equals(GabaritoDoProjeto.GITIGNORE)) {
                c.gravarAtomico(ignore, (atual + PADRAO_DAS_COPIAS + "\n").getBytes(StandardCharsets.UTF_8), PERMISSAO_DO_COMPOSE);
                return;
            }
            boolean jaIgnora = atual.lines().map(String::strip).anyMatch(l -> l.equals("*.bak") || l.equals(PADRAO_DAS_COPIAS) || l.equals("docker-compose*.bak"));
            if (!jaIgnora) {
                avisos.add("A cópia " + copia + " fica na pasta do projeto e o git a veria como arquivo novo: acrescente " + PADRAO_DAS_COPIAS + " ao .gitignore (o app não "
                    + "reescreve o .gitignore que é seu).");
            }
        } catch (ErroSftp | FalhaDoProjeto e) {
            avisos.add("A cópia " + copia + " fica na pasta do projeto; confira se o .gitignore a deixa de fora.");
        }
    }

    private static FalhaDoProjeto mudou() {
        return new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O docker-compose.yml mudou depois que você viu a diferença. Nada foi gravado: abra "
            + "\"Adicionar container\" de novo para ver o que muda agora.");
    }

    private void conferirComODocker(String pasta, Previa previa) throws FalhaDoProjeto {
        Map<String, String> ambienteDaConferencia = new LinkedHashMap<>();
        previa.bloco().variaveis().forEach(v -> ambienteDaConferencia.put(v.nome(), VALOR_DA_CONFERENCIA));
        ResultadoExec r;
        try {
            r = ambiente.executor().executar(ComandosDeProjeto.validarCompose(ambiente.paraOSistema().apply(pasta)), ambienteDaConferencia,
                previa.novo().getBytes(StandardCharsets.UTF_8), PRAZO_DA_CONFERENCIA);
        } catch (ProgramaNaoEncontradoException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O Docker não está instalado neste computador, então não dá para conferir o compose. "
                + "Nada foi gravado.", null, e);
        } catch (ComandoDemorouException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.INCERTO, "A conferência do docker compose demorou demais. Nada foi gravado.", null, e);
        } catch (IOException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "Não consegui conferir o compose com o Docker (a conexão caiu ou o Docker não respondeu). "
                + "Nada foi gravado.", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado. Nada foi gravado.", null, e);
        } catch (IllegalArgumentException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O caminho do projeto tem um caractere que o app não passa ao servidor, então não dá "
                + "para conferir o compose. Nada foi gravado: use \"Copiar o bloco\".", null, e);
        }
        if (r.status() != 0) {
            String motivo = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(r.stderr())), 400);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O Docker não aceitou o compose com este container (" + (motivo.isEmpty() ? "sem mensagem" : motivo)
                + "). Nada foi gravado.");
        }
    }

    private List<String> acrescentarAoEnv(ClienteSftp c, String pasta, BlocoDeServico bloco, List<String> avisos) throws ErroSftp, FalhaDoProjeto {
        if (bloco.variaveis().isEmpty()) {
            return List.of();
        }
        String env = CaminhoRemoto.juntar(pasta, ".env");
        String atual = lerTexto(c, env);
        Map<String, String> novas = new LinkedHashMap<>();
        for (BlocoDeServico.Variavel v : bloco.variaveis()) {
            novas.put(v.nome(), EnvDoProjeto.novaSenha(azar));
        }
        EnvDoProjeto.Mescla m = EnvDoProjeto.acrescentar(atual, novas);
        if (m.mudou()) {
            c.gravarAtomico(env, m.texto().getBytes(StandardCharsets.UTF_8), PERMISSAO_DO_ENV);
        }
        // os NOMES (nunca os valores) vão para o .env.example, se o arquivo está lá e aceita a escrita
        try {
            String exemplo = CaminhoRemoto.juntar(pasta, ".env.example");
            String atualExemplo = lerTexto(c, exemplo);
            Map<String, String> nomes = new LinkedHashMap<>();
            bloco.variaveis().forEach(v -> nomes.put(v.nome(), ""));
            EnvDoProjeto.Mescla me = EnvDoProjeto.acrescentar(atualExemplo, nomes);
            if (me.mudou()) {
                c.gravarAtomico(exemplo, me.texto().getBytes(StandardCharsets.UTF_8), PERMISSAO_DO_COMPOSE);
            }
        } catch (ErroSftp e) {
            avisos.add("Não consegui acrescentar os nomes das variáveis ao .env.example (" + e.mensagemSimples() + "). Pode fazê-lo à mão.");
        }
        return m.acrescentadas();
    }

    private String nomeDaCopia(ClienteSftp c, String pasta) throws ErroSftp {
        String base = GabaritoDoProjeto.ARQUIVO_COMPOSE + "." + DATA_DA_COPIA.format(LocalDateTime.now(relogio)) + ".bak";
        String nome = base;
        for (int i = 2; c.existe(CaminhoRemoto.juntar(pasta, nome)); i++) {
            nome = base + "-" + i;
        }
        return nome;
    }

    /** Os bytes do arquivo, ou {@code null} se ele não existe. */
    private static byte[] lerBytes(ClienteSftp c, String caminho) throws ErroSftp, FalhaDoProjeto {
        try {
            byte[] bytes = c.lerArquivo(caminho, LIMITE_DO_COMPOSE + 1);
            if (bytes.length > LIMITE_DO_COMPOSE) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O arquivo " + CaminhoRemoto.nome(caminho) + " é grande demais para o app mexer (mais de 512 KB).");
            }
            return bytes;
        } catch (ErroSftp e) {
            if (e.tipo() == ErroSftp.Tipo.NAO_EXISTE) {
                return null;
            }
            throw e;
        }
    }

    /** UTF-8 estrito: {@code null} se os bytes não são texto UTF-8 válido (e também se não há arquivo). */
    static String decodificar(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** O texto do arquivo, ou {@code null} se ele não existe ou não é UTF-8 (o .env e o .env.example: nesses o app só acrescenta e nunca reescreve o que não entende). */
    private static String lerTexto(ClienteSftp c, String caminho) throws ErroSftp, FalhaDoProjeto {
        byte[] bytes = lerBytes(c, caminho);
        if (bytes != null && decodificar(bytes) == null) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O arquivo " + CaminhoRemoto.nome(caminho) + " não está em UTF-8: o app não mexe nele para não estragar os acentos. "
                + "Nada foi gravado.");
        }
        return bytes == null ? null : decodificar(bytes);
    }

    private static void exigirProjeto(String projeto) throws FalhaDoProjeto {
        if (!br.com.labdesktop.seguranca.ValidadorDeNomes.projetoValido(projeto)) { // o do agente: um projeto que já existe vale
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Nome de projeto inválido.");
        }
    }
}
