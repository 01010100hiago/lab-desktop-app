package br.com.labdesktop.arquivos;

import br.com.labdesktop.arquivos.BackupsLocais.Backup;
import br.com.labdesktop.arquivos.DetectorDeTexto.Texto;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.util.Formato;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Abrir e salvar arquivos de TEXTO do servidor com segurança. O que garante que editar no servidor não perde nada:
 * <ol>
 *   <li><b>Conflito</b>: antes de gravar compara tamanho e data do arquivo no servidor com os de quando foi aberto; se
 *       mudaram, não grava ({@code ARQUIVO_MUDOU}) até o usuário escolher Recarregar, Sobrescrever mesmo assim ou Cancelar.</li>
 *   <li><b>Backup local</b>: o conteúdo que está no servidor agora vai para uma cópia no Mac ({@link BackupsLocais}) ANTES de
 *       ser trocado; se a cópia não puder ser feita, nada é gravado. Desfazer a última edição restaura dessa cópia.</li>
 *   <li><b>Gravação atômica</b>: temporário na mesma pasta, permissões do original, troca por cima ({@link ClienteSftp#gravarAtomico}).
 *       Nada de {@code .bak} no servidor (sujaria o git dos projetos).</li>
 *   <li><b>Salvar com sudo</b> (só quando o usuário pede, depois de a gravação normal dizer que a pasta não é gravável ou que o
 *       arquivo é de outro usuário): um comando FIXO, sem o caminho na linha de comando. O caminho vai no começo do stdin e o
 *       conteúdo logo depois; o script lê a primeira linha e passa o caminho ao {@code tee} entre aspas.</li>
 * </ol>
 */
public final class EditorRemoto {

    /**
     * O comando do "Salvar com sudo". Fixo: o caminho NUNCA é interpolado nele (veio do servidor, pode ter aspas, espaços e
     * {@code $}); chega pelo stdin e o {@code sh} o lê com {@code read -r} e o usa entre aspas duplas. {@code sudo -n} nunca
     * pergunta senha (um {@code exec} não tem terminal). Se o caminho tiver quebra de linha ou outro controle, nem é enviado.
     */
    public static final String COMANDO_DO_SUDO = "sh -c 'IFS= read -r p && exec sudo -n tee -- \"$p\" > /dev/null'";

    private static final Duration PRAZO_DO_SUDO = Duration.ofSeconds(60);
    /** Acima disto o arquivo não é lido para backup (mudou de forma absurda desde que foi aberto). */
    private static final long TETO_DO_BACKUP = 8L * 1024 * 1024;

    /**
     * Um arquivo aberto no editor.
     *
     * @param caminho       o caminho como o usuário o abriu (pode ser um link)
     * @param caminhoReal   o caminho para onde se grava: o arquivo de verdade, depois de seguir os links, sempre dentro da raiz
     * @param texto         o conteúdo com {@code \n} em todas as quebras
     * @param quebraDeLinha {@code "\n"} ou {@code "\r\n"}, a original do arquivo
     * @param tamanho       tamanho no servidor quando foi aberto (ou salvo por último)
     * @param modificadoEm  data no servidor quando foi aberto (ou salvo por último)
     */
    public record Aberto(String caminho, String caminhoReal, String texto, String quebraDeLinha, boolean quebrasMistas,
                         long tamanho, Instant modificadoEm, int permissoes, String dono) {

        public String nome() {
            return CaminhoRemoto.nome(caminho);
        }
    }

    private final ServicoDeArquivos servico;
    private final BackupsLocais backups;
    private final ProvisionadorRemoto.Executor executor;

    /**
     * @param executor roda o comando do sudo na sessão SSH ({@code conexao::executar}); {@code null} desliga o "Salvar com sudo"
     */
    public EditorRemoto(ServicoDeArquivos servico, BackupsLocais backups, ProvisionadorRemoto.Executor executor) {
        this.servico = servico;
        this.backups = backups;
        this.executor = executor;
    }

    public boolean temSudo() {
        return executor != null;
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir
    // ---------------------------------------------------------------------------------------------

    /**
     * @throws ErroSftp {@code NAO_E_TEXTO} para pasta, binário, não-UTF-8 e arquivo acima de 1 MB (a frase manda baixar)
     */
    public Aberto abrir(String caminho) throws ErroSftp {
        String como = servico.escopo().validar(caminho);
        String real = servico.resolverDentroDaRaiz(como);
        EntradaRemota e = servico.cliente().estatisticas(real);
        String nome = CaminhoRemoto.nome(como);
        if (e.ehPasta()) {
            throw new ErroSftp(ErroSftp.Tipo.NAO_E_TEXTO, "\"" + nome + "\" é uma pasta.");
        }
        if (e.tipo() != EntradaRemota.Tipo.ARQUIVO) {
            throw new ErroSftp(ErroSftp.Tipo.NAO_E_TEXTO, "\"" + nome + "\" não é um arquivo comum, então não abre no editor.");
        }
        if (e.tamanho() > DetectorDeTexto.LIMITE) {
            throw new ErroSftp(ErroSftp.Tipo.NAO_E_TEXTO, "\"" + nome + "\" é grande demais para editar aqui (" + Formato.fmtBytes(e.tamanho())
                + "; o limite é 1 MB). Use Baixar para abrir no seu Mac.");
        }
        byte[] bytes = servico.cliente().lerArquivo(real, DetectorDeTexto.LIMITE + 1);
        if (bytes.length > DetectorDeTexto.LIMITE) {
            throw new ErroSftp(ErroSftp.Tipo.NAO_E_TEXTO, "\"" + nome + "\" é grande demais para editar aqui. Use Baixar.");
        }
        Texto t = DetectorDeTexto.detectar(bytes);
        if (t == null) {
            throw new ErroSftp(ErroSftp.Tipo.NAO_E_TEXTO, "\"" + nome + "\" não parece um arquivo de texto (é binário ou não está em UTF-8), "
                + "então não abre no editor. Use Baixar.");
        }
        return new Aberto(como, real, t.texto(), t.quebraDeLinha(), t.quebrasMistas(), e.tamanho(), e.modificadoEm(), e.permissoes(),
            e.dono());
    }

    // ---------------------------------------------------------------------------------------------
    // Salvar
    // ---------------------------------------------------------------------------------------------

    /**
     * Grava o texto do editor no arquivo.
     *
     * @param sobrescrever {@code true} depois de o usuário escolher "Sobrescrever mesmo assim" no aviso de conflito
     * @return o arquivo como ficou (tamanho e data novos), para a próxima gravação comparar com ele
     * @throws ErroSftp {@code ARQUIVO_MUDOU}, {@code SEM_PERMISSAO} e {@code OUTRO_DONO} (a tela oferece Salvar com sudo), e o resto
     */
    public Aberto salvar(Aberto base, String textoDoEditor, boolean sobrescrever) throws ErroSftp {
        EntradaRemota atual = conferirEGuardar(base, sobrescrever);
        byte[] novos = DetectorDeTexto.paraDisco(textoDoEditor, base.quebraDeLinha());
        int permissoes = atual != null ? atual.permissoes() : base.permissoes();
        servico.cliente().gravarAtomico(base.caminhoReal(), novos, permissoes);
        return depoisDeGravar(base, textoDoEditor);
    }

    /** Como {@link #salvar}, mas por {@code sudo tee}: o arquivo mantém dono e permissões (o {@code tee} grava no mesmo arquivo). */
    public Aberto salvarComSudo(Aberto base, String textoDoEditor, boolean sobrescrever) throws ErroSftp {
        if (executor == null) {
            throw new ErroSftp(ErroSftp.Tipo.OUTRO, "O sudo não está disponível nesta conexão.");
        }
        String caminho = base.caminhoReal();
        if (!CaminhoRemoto.semControle(caminho)) {
            throw ErroSftp.nomeInvalido("O caminho tem caracteres que não posso passar ao sudo.");
        }
        conferirEGuardar(base, sobrescrever);
        byte[] conteudo = DetectorDeTexto.paraDisco(textoDoEditor, base.quebraDeLinha());
        byte[] caminhoBytes = (caminho + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] entrada = new byte[caminhoBytes.length + conteudo.length];
        System.arraycopy(caminhoBytes, 0, entrada, 0, caminhoBytes.length);
        System.arraycopy(conteudo, 0, entrada, caminhoBytes.length, conteudo.length);
        ResultadoExec r;
        try {
            r = executor.executar(COMANDO_DO_SUDO, entrada, PRAZO_DO_SUDO);
        } catch (IOException e) {
            throw ErroSftp.de(e, "gravar este arquivo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ErroSftp.cancelado();
        }
        if (r.status() != 0) {
            throw new ErroSftp(ErroSftp.Tipo.OUTRO, mensagemDoSudo(r.stderr()));
        }
        return depoisDeGravar(base, textoDoEditor);
    }

    static String mensagemDoSudo(String erro) {
        String e = erro == null ? "" : erro.toLowerCase(Locale.ROOT);
        if (e.contains("password is required") || e.contains("a terminal is required") || e.contains("no tty present")) {
            return "Este usuário não pode usar sudo sem senha neste servidor, então não consegui salvar assim.";
        }
        if (e.contains("not in the sudoers") || e.contains("may not run sudo") || e.contains("not allowed to execute")) {
            return "Este usuário não tem permissão de sudo neste servidor.";
        }
        if (e.contains("not found")) {
            return "O servidor não tem o sudo instalado.";
        }
        String linha = erro == null ? "" : erro.lines().findFirst().orElse("").replaceAll("\\p{Cntrl}", " ").strip();
        return "O servidor recusou salvar com sudo" + (linha.isEmpty() ? "." : ": " + (linha.length() > 160 ? linha.substring(0, 160) + "…" : linha));
    }

    /**
     * O passo comum: confere o conflito e põe o que está no servidor agora num backup local.
     *
     * @return o arquivo como está no servidor, ou {@code null} se foi apagado (e o usuário mandou sobrescrever)
     */
    private EntradaRemota conferirEGuardar(Aberto base, boolean sobrescrever) throws ErroSftp {
        EntradaRemota atual;
        try {
            atual = servico.cliente().estatisticas(base.caminhoReal());
        } catch (ErroSftp e) {
            if (e.tipo() != ErroSftp.Tipo.NAO_EXISTE) {
                throw e;
            }
            atual = null;
        }
        if (atual == null) {
            if (!sobrescrever) {
                throw new ErroSftp(ErroSftp.Tipo.ARQUIVO_MUDOU, "O arquivo foi apagado do servidor desde que você abriu.");
            }
            return null;
        }
        if (!sobrescrever && mudou(base, atual)) {
            throw new ErroSftp(ErroSftp.Tipo.ARQUIVO_MUDOU, "O arquivo mudou no servidor desde que você abriu.");
        }
        if (atual.tamanho() > TETO_DO_BACKUP) {
            throw new ErroSftp(ErroSftp.Tipo.ARQUIVO_MUDOU, "O arquivo no servidor ficou grande demais (" + Formato.fmtBytes(atual.tamanho())
                + ") para eu guardar uma cópia e trocá-lo com segurança.");
        }
        byte[] anterior = servico.cliente().lerArquivo(base.caminhoReal(), (int) Math.max(1, atual.tamanho()) + 1);
        try {
            backups.guardar(base.caminhoReal(), anterior);
        } catch (IOException e) {
            throw new ErroSftp(ErroSftp.Tipo.OUTRO, "Não consegui guardar a cópia de segurança no seu Mac, então NÃO salvei. "
                + "Confira o espaço em disco.", e);
        }
        return atual;
    }

    private static boolean mudou(Aberto base, EntradaRemota atual) {
        if (atual.tamanho() != base.tamanho()) {
            return true;
        }
        Instant a = atual.modificadoEm();
        Instant b = base.modificadoEm();
        if (a == null || b == null) {
            return a != b;
        }
        return a.getEpochSecond() != b.getEpochSecond(); // o SFTP v3 só guarda segundos
    }

    private Aberto depoisDeGravar(Aberto base, String textoDoEditor) throws ErroSftp {
        EntradaRemota novo = servico.cliente().estatisticas(base.caminhoReal());
        return new Aberto(base.caminho(), base.caminhoReal(), textoDoEditor, base.quebraDeLinha(), false, novo.tamanho(),
            novo.modificadoEm(), novo.permissoes(), novo.dono());
    }

    // ---------------------------------------------------------------------------------------------
    // Desfazer a última edição
    // ---------------------------------------------------------------------------------------------

    /** A cópia mais recente deste arquivo, se houver (habilita "Desfazer a última edição"). */
    public Optional<Backup> ultimaVersaoGuardada(Aberto arquivo) {
        try {
            return backups.ultimo(arquivo.caminhoReal());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Devolve o arquivo ao que ele era antes da última edição (a cópia mais recente). O conteúdo de agora também vira uma cópia,
     * então desfazer pode ser desfeito.
     */
    public Aberto desfazerUltimaEdicao(Aberto base) throws ErroSftp {
        Backup b = ultimaVersaoGuardada(base).orElseThrow(() -> new ErroSftp(ErroSftp.Tipo.NAO_EXISTE,
            "Não há versão anterior guardada deste arquivo."));
        byte[] bytes;
        try {
            bytes = backups.ler(b);
        } catch (IOException e) {
            throw new ErroSftp(ErroSftp.Tipo.OUTRO, "Não consegui ler a cópia guardada no seu Mac.", e);
        }
        Texto t = DetectorDeTexto.detectar(bytes);
        if (t == null) {
            throw new ErroSftp(ErroSftp.Tipo.NAO_E_TEXTO, "A cópia guardada não é um texto que o editor abra.");
        }
        Aberto com = new Aberto(base.caminho(), base.caminhoReal(), t.texto(), t.quebraDeLinha(), t.quebrasMistas(), base.tamanho(),
            base.modificadoEm(), base.permissoes(), base.dono());
        return salvar(com, t.texto(), true);
    }
}
