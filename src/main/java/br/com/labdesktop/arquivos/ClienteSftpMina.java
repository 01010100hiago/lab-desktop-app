package br.com.labdesktop.arquivos;

import br.com.labdesktop.arquivos.EntradaRemota.Tipo;
import br.com.labdesktop.ssh.SftpIndisponivelException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attribute;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.CloseableHandle;
import org.apache.sshd.sftp.client.SftpClient.CopyMode;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O {@link ClienteSftp} sobre o {@code SftpClient} do MINA, aberto na sessão SSH que a {@code ConexaoSsh} já mantém: UM canal
 * por gerenciador, aberto na primeira chamada, REABERTO sozinho se a sessão caiu e foi refeita (a chamada que pegou a queda
 * falha com "O servidor desconectou, reconectando…", e as leituras são repetidas uma vez no canal novo).
 *
 * <p>Pontos que contam:
 * <ul>
 *   <li><b>Nome vindo do servidor é hostil</b>: um nome com barra ou {@code ..} numa listagem faria um download escrever fora da
 *       pasta escolhida; essas entradas são descartadas antes de virarem {@link EntradaRemota}.</li>
 *   <li><b>Nunca segue link ao apagar</b>: a decisão usa {@code lstat}, e o apagar recursivo remove o link, não o destino.</li>
 *   <li><b>Gravar é atômico</b>: temporário na mesma pasta e troca por cima; sem a extensão {@code posix-rename@openssh.com} e
 *       sem SFTP v5+, a troca desvia o arquivo antigo antes de pôr o novo e o devolve se algo falhar.</li>
 * </ul>
 */
public final class ClienteSftpMina implements ClienteSftp {

    private static final Logger LOG = LoggerFactory.getLogger(ClienteSftpMina.class);

    /** Pasta com mais itens que isto: a contagem para e o total vira "pelo menos". */
    static final int TETO_DA_CONTAGEM = 100_000;
    /** Quantos links por listagem têm o destino lido (cada um custa uma ida e volta). */
    private static final int LINKS_RESOLVIDOS_POR_LISTAGEM = 400;
    private static final int BLOCO = 64 * 1024;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final FabricaDeSftp fabrica;
    private SftpClient canal; // sob "this"
    private boolean fechado; // sob "this"
    /** Só os testes: finge um servidor sem a extensão {@code posix-rename} e de SFTP v3. */
    private volatile boolean semTrocaAtomica;

    public ClienteSftpMina(FabricaDeSftp fabrica) {
        this.fabrica = fabrica;
    }

    /** Só para os testes: força o caminho de reserva da troca de arquivo (servidor v3 sem posix-rename). */
    void forcarSemTrocaAtomica(boolean sim) {
        this.semTrocaAtomica = sim;
    }

    // ---------------------------------------------------------------------------------------------
    // Canal
    // ---------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Operacao<T> {
        T executar(SftpClient cliente) throws IOException;
    }

    private synchronized SftpClient canal() throws ErroSftp {
        if (fechado) {
            throw new ErroSftp(ErroSftp.Tipo.SEM_CONEXAO, "A tela de arquivos foi fechada.");
        }
        if (canal != null && canal.isOpen() && !canal.isClosing()) {
            return canal;
        }
        fecharCanalQuieto();
        try {
            canal = fabrica.abrir();
            return canal;
        } catch (SftpIndisponivelException e) {
            throw new ErroSftp(ErroSftp.Tipo.SFTP_INDISPONIVEL, "Este servidor não aceita transferência de arquivos (SFTP).", e);
        } catch (IOException e) {
            throw ErroSftp.semConexao(e);
        }
    }

    private synchronized void descartar(SftpClient quem) {
        if (canal == quem) {
            fecharCanalQuieto();
        }
    }

    private void fecharCanalQuieto() {
        SftpClient c = canal;
        canal = null;
        if (c != null) {
            try {
                c.close();
            } catch (IOException | RuntimeException e) {
                LOG.debug("Falha ao fechar o canal SFTP: {}", e.toString());
            }
        }
    }

    /**
     * Roda uma operação traduzindo qualquer erro. Se a sessão tinha caído (canal morto), descarta o canal e, nas operações que
     * podem se repetir sem efeito colateral ({@code repetivel}), tenta de novo UMA vez num canal novo.
     */
    private <T> T usar(String oQue, boolean repetivel, Operacao<T> operacao) throws ErroSftp {
        for (int tentativa = 0;; tentativa++) {
            SftpClient c = canal();
            try {
                return operacao.executar(c);
            } catch (ErroSftp e) {
                if (e.tipo() == ErroSftp.Tipo.SEM_CONEXAO) {
                    descartar(c);
                }
                throw e;
            } catch (IOException | RuntimeException e) {
                ErroSftp traduzido = ErroSftp.de(e, oQue);
                if (traduzido.tipo() == ErroSftp.Tipo.SEM_CONEXAO) {
                    descartar(c);
                    if (repetivel && tentativa == 0) {
                        continue;
                    }
                }
                throw traduzido;
            }
        }
    }

    @Override
    public synchronized void close() {
        fechado = true;
        fecharCanalQuieto();
    }

    private static String limpo(String caminho) throws ErroSftp {
        if (caminho == null || !CaminhoRemoto.semControle(caminho) || caminho.isEmpty()) {
            throw ErroSftp.nomeInvalido("O caminho tem caracteres que não posso usar.");
        }
        return caminho;
    }

    private static String sufixo() {
        byte[] b = new byte[5];
        ALEATORIO.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Nome do temporário: curto o bastante para nunca estourar os 255 bytes do sistema de arquivos. */
    private static String nomeTemporario(String pasta, String nomeFinal, String marca) {
        String base = nomeFinal.length() > 60 ? nomeFinal.substring(0, 60) : nomeFinal;
        String nome = "." + base + ".labdesktop-" + marca + "-" + sufixo() + ".tmp";
        return pasta.equals("/") ? "/" + nome : pasta + "/" + nome;
    }

    // ---------------------------------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------------------------------

    @Override
    public ListagemDePasta listar(String caminho, int limite, Predicate<String> nomeAceito) throws ErroSftp {
        String c = limpo(caminho);
        int teto = Math.max(1, limite);
        return usar("abrir esta pasta", true, cli -> {
            List<EntradaRemota> guardadas = new ArrayList<>(Math.min(teto, 512));
            int total = 0;
            boolean exato = true;
            try (CloseableHandle h = cli.openDir(c)) {
                AtomicReference<Boolean> fim = new AtomicReference<>(Boolean.FALSE);
                externo:
                while (!Boolean.TRUE.equals(fim.get())) {
                    List<DirEntry> lote = cli.readDir(h, fim);
                    if (lote == null) {
                        break;
                    }
                    for (DirEntry e : lote) {
                        var entrada = paraEntrada(c, e);
                        if (entrada.isEmpty() || !nomeAceito.test(entrada.get().nome())) {
                            continue;
                        }
                        total++;
                        if (guardadas.size() < teto) {
                            guardadas.add(entrada.get());
                        } else if (total >= TETO_DA_CONTAGEM) {
                            exato = false;
                            break externo;
                        }
                    }
                }
            }
            resolverLinks(cli, guardadas);
            return new ListagemDePasta(c, guardadas, total, total > guardadas.size(), exato, Instant.now());
        });
    }

    /**
     * Uma entrada de pasta do servidor virando {@link EntradaRemota}. Vazio para o que não é um filho de verdade: {@code .},
     * {@code ..}, nome vazio ou com barra (um servidor hostil mandaria {@code ../../x} para um download escrever fora da pasta).
     */
    static java.util.Optional<EntradaRemota> paraEntrada(String pasta, DirEntry e) {
        String nome = e.getFilename();
        if (nome == null || nome.isEmpty() || nome.equals(".") || nome.equals("..")) {
            return java.util.Optional.empty();
        }
        if (nome.indexOf('/') >= 0 || nome.indexOf('\0') >= 0) {
            LOG.warn("Nome de entrada descartado (barra ou NUL): {}", nome.replace('\0', '?'));
            return java.util.Optional.empty();
        }
        Attributes a = e.getAttributes();
        Set<Attribute> flags = a.getFlags();
        Tipo tipo = a.isDirectory() ? Tipo.PASTA : a.isRegularFile() ? Tipo.ARQUIVO : a.isSymbolicLink() ? Tipo.ARQUIVO : Tipo.OUTRO;
        long tamanho = flags.contains(Attribute.Size) ? a.getSize() : -1;
        Instant modificado = flags.contains(Attribute.ModifyTime) && a.getModifyTime() != null ? a.getModifyTime().toInstant() : null;
        int permissoes = flags.contains(Attribute.Perms) ? a.getPermissions() & 07777 : -1;
        String dono = a.getOwner();
        String grupo = a.getGroup();
        if (dono == null || dono.isBlank() || grupo == null || grupo.isBlank()) {
            String[] longo = donoEGrupoDoLongName(e.getLongFilename());
            dono = dono == null || dono.isBlank() ? (longo != null ? longo[0] : numero(flags, a.getUserId())) : dono;
            grupo = grupo == null || grupo.isBlank() ? (longo != null ? longo[1] : numero(flags, a.getGroupId())) : grupo;
        }
        String caminho = pasta.equals("/") ? "/" + nome : pasta + "/" + nome;
        return java.util.Optional.of(new EntradaRemota(nome, caminho, tipo, a.isSymbolicLink(), tamanho, modificado, permissoes,
            dono, grupo, null));
    }

    private static String numero(Set<Attribute> flags, int id) {
        return flags.contains(Attribute.OwnerGroup) && id > 0 ? Integer.toString(id) : "";
    }

    /** O SFTP v3 não tem dono em texto, mas a linha "ls -l" que o OpenSSH manda tem: {@code -rw-r--r-- 1 ana dev 12 ...}. */
    static String[] donoEGrupoDoLongName(String longo) {
        if (longo == null || longo.isBlank()) {
            return null;
        }
        String[] t = longo.trim().split("\\s+", 6);
        if (t.length < 5 || t[0].length() < 10 || "-dlcbps".indexOf(t[0].charAt(0)) < 0) {
            return null;
        }
        return new String[] {t[2], t[3]};
    }

    /** Dá aos links o tipo do destino (e o destino em texto): links quebrados viram {@code LINK_QUEBRADO}. */
    private void resolverLinks(SftpClient cli, List<EntradaRemota> entradas) throws IOException {
        int resolvidos = 0;
        for (int i = 0; i < entradas.size(); i++) {
            EntradaRemota e = entradas.get(i);
            if (!e.link()) {
                continue;
            }
            if (resolvidos++ >= LINKS_RESOLVIDOS_POR_LISTAGEM) {
                break;
            }
            entradas.set(i, resolverLink(cli, e));
        }
    }

    private EntradaRemota resolverLink(SftpClient cli, EntradaRemota e) throws IOException {
        String destino = null;
        try {
            destino = cli.readLink(e.caminho());
        } catch (IOException | RuntimeException ex) {
            LOG.debug("readlink falhou: {}", ex.toString());
        }
        try {
            Attributes a = cli.stat(e.caminho()); // stat segue o link
            Tipo tipo = a.isDirectory() ? Tipo.PASTA : a.isRegularFile() ? Tipo.ARQUIVO : Tipo.OUTRO;
            long tamanho = tipo == Tipo.ARQUIVO && a.getFlags().contains(Attribute.Size) ? a.getSize() : e.tamanho();
            return new EntradaRemota(e.nome(), e.caminho(), tipo, true, tamanho, e.modificadoEm(), e.permissoes(), e.dono(),
                e.grupo(), destino);
        } catch (SftpException s) {
            if (s.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || s.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH
                || s.getStatus() == SftpConstants.SSH_FX_LINK_LOOP) {
                return e.comTipo(Tipo.LINK_QUEBRADO, destino);
            }
            return e.comTipo(Tipo.OUTRO, destino);
        }
    }

    @Override
    public EntradaRemota estatisticas(String caminho) throws ErroSftp {
        String c = limpo(caminho);
        return usar("ver este item", true, cli -> {
            Attributes a = cli.lstat(c);
            boolean ehRaiz = c.equals("/");
            String nome = ehRaiz ? "raiz" : CaminhoRemoto.nome(c); // "/" não passa como nome de filho: a raiz é montada à parte
            EntradaRemota e = paraEntrada(CaminhoRemoto.pai(c), new DirEntry(nome, nome, a))
                .orElseThrow(() -> ErroSftp.nomeInvalido("Nome inválido."));
            if (ehRaiz) {
                e = new EntradaRemota("/", "/", e.tipo(), e.link(), e.tamanho(), e.modificadoEm(), e.permissoes(), e.dono(),
                    e.grupo(), null);
            }
            return e.link() ? resolverLink(cli, e) : e;
        });
    }

    @Override
    public boolean existe(String caminho) throws ErroSftp {
        String c = limpo(caminho);
        return usar("ver este item", true, cli -> {
            try {
                cli.lstat(c);
                return true;
            } catch (SftpException s) {
                if (s.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || s.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) {
                    return false;
                }
                throw s;
            }
        });
    }

    @Override
    public byte[] lerArquivo(String caminho, int limite) throws ErroSftp {
        String c = limpo(caminho);
        return usar("abrir este arquivo", true, cli -> {
            ByteArrayOutputStream saida = new ByteArrayOutputStream(Math.min(Math.max(limite, 16), 64 * 1024));
            byte[] buf = new byte[32 * 1024];
            try (InputStream in = cli.read(c)) {
                int n;
                while (saida.size() < limite && (n = in.read(buf, 0, Math.min(buf.length, limite - saida.size()))) > 0) {
                    saida.write(buf, 0, n);
                }
            }
            return saida.toByteArray();
        });
    }

    @Override
    public String lerLink(String caminho) throws ErroSftp {
        String c = limpo(caminho);
        return usar("ver este link", true, cli -> cli.readLink(c));
    }

    @Override
    public String caminhoCanonico(String caminho) throws ErroSftp {
        String c = limpo(caminho);
        return usar("ver este caminho", true, cli -> cli.canonicalPath(c));
    }

    // ---------------------------------------------------------------------------------------------
    // Escrita
    // ---------------------------------------------------------------------------------------------

    @Override
    public void gravarAtomico(String caminho, byte[] conteudo, int permissoes) throws ErroSftp {
        String destino = limpo(caminho);
        String pasta = CaminhoRemoto.pai(destino);
        String temporario = nomeTemporario(pasta, CaminhoRemoto.nome(destino), "gravando");
        usar("gravar este arquivo", false, cli -> {
            boolean criouTemporario = false;
            try {
                try (OutputStream out = cli.write(temporario, OpenMode.Create, OpenMode.Write, OpenMode.Exclusive)) {
                    criouTemporario = true;
                    out.write(conteudo);
                }
                if (permissoes >= 0) {
                    Attributes p = new Attributes();
                    p.setPermissions(permissoes & 07777);
                    cli.setStat(temporario, p);
                }
                Attributes atual = existente(cli, destino);
                if (atual != null) {
                    // O arquivo novo nasce do usuário do app: se o antigo era de outro, a troca mudaria o usuário sem ninguém pedir.
                    String donoDoNovo = cli.stat(temporario).getOwner();
                    String donoDoAntigo = atual.getOwner();
                    if (trocaMudariaODono(donoDoNovo, donoDoAntigo)) {
                        throw new ErroSftp(ErroSftp.Tipo.OUTRO_DONO, "Este arquivo é de \"" + donoDoAntigo
                            + "\" e não do usuário que o app usa neste servidor (" + donoDoNovo + ").");
                    }
                    trocar(cli, temporario, destino);
                } else {
                    cli.rename(temporario, destino);
                }
                criouTemporario = false;
                return null;
            } finally {
                if (criouTemporario) {
                    removerQuieto(cli, temporario);
                }
            }
        });
    }

    /** {@code true} se trocar o arquivo por um novo entregaria a outro dono (os dois donos são conhecidos e diferentes). */
    static boolean trocaMudariaODono(String donoDoNovo, String donoDoAntigo) {
        return donoDoNovo != null && donoDoAntigo != null && !donoDoNovo.isBlank() && !donoDoAntigo.isBlank()
            && !donoDoNovo.equals(donoDoAntigo);
    }

    private static Attributes existente(SftpClient cli, String caminho) throws IOException {
        try {
            return cli.lstat(caminho);
        } catch (SftpException s) {
            if (s.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || s.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) {
                return null;
            }
            throw s;
        }
    }

    /** Põe {@code temporario} no lugar de {@code destino}, que existe, sem nunca deixar o destino sumido. */
    private void trocar(SftpClient cli, String temporario, String destino) throws IOException {
        if (!semTrocaAtomica) {
            OpenSSHPosixRenameExtension px = cli.getExtension(OpenSSHPosixRenameExtension.class);
            if (px != null && px.isSupported()) {
                px.posixRename(temporario, destino);
                return;
            }
            if (cli.getVersion() >= 5) {
                cli.rename(temporario, destino, CopyMode.Overwrite);
                return;
            }
        }
        // SFTP v3 puro: o rename não sobrescreve. Desvia o antigo, põe o novo, apaga o desvio; se o meio falhar, devolve o antigo.
        String desvio = destino + ".labdesktop-antigo-" + sufixo();
        cli.rename(destino, desvio);
        try {
            cli.rename(temporario, destino);
        } catch (IOException | RuntimeException e) {
            try {
                cli.rename(desvio, destino);
            } catch (IOException | RuntimeException ignorado) {
                LOG.error("Não consegui devolver o arquivo antigo depois de uma troca que falhou; ele está em {}", desvio);
            }
            throw e;
        }
        removerQuieto(cli, desvio);
    }

    private static void removerQuieto(SftpClient cli, String caminho) {
        try {
            cli.remove(caminho);
        } catch (IOException | RuntimeException e) {
            LOG.debug("Não consegui apagar o temporário {}: {}", caminho, e.toString());
        }
    }

    @Override
    public void criarPasta(String caminho) throws ErroSftp {
        String c = limpo(caminho);
        usar("criar a pasta aqui", false, cli -> {
            if (existente(cli, c) != null) {
                throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe um item com este nome.");
            }
            cli.mkdir(c);
            return null;
        });
    }

    @Override
    public void criarArquivoVazio(String caminho) throws ErroSftp {
        String c = limpo(caminho);
        usar("criar o arquivo aqui", false, cli -> {
            if (existente(cli, c) != null) {
                throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe um item com este nome.");
            }
            try (CloseableHandle h = cli.open(c, OpenMode.Create, OpenMode.Write, OpenMode.Exclusive)) {
                LOG.trace("arquivo vazio criado: {}", h);
            }
            return null;
        });
    }

    @Override
    public void renomear(String de, String para) throws ErroSftp {
        String origem = limpo(de);
        String destino = limpo(para);
        usar("renomear ou mover isto", false, cli -> {
            if (existente(cli, origem) == null) {
                throw ErroSftp.naoExiste(CaminhoRemoto.nome(origem));
            }
            if (existente(cli, destino) != null) {
                throw new ErroSftp(ErroSftp.Tipo.JA_EXISTE, "Já existe \"" + CaminhoRemoto.nome(destino) + "\" no destino.");
            }
            cli.rename(origem, destino);
            return null;
        });
    }

    @Override
    public void apagar(String caminho, boolean recursivo) throws ErroSftp {
        String c = limpo(caminho);
        usar("apagar isto", false, cli -> {
            apagarEm(cli, c, recursivo);
            return null;
        });
    }

    private static void apagarEm(SftpClient cli, String caminho, boolean recursivo) throws IOException {
        Attributes a = cli.lstat(caminho); // lstat: um link é um link, nunca o que está do outro lado
        if (a.isDirectory() && !a.isSymbolicLink()) {
            if (!recursivo) {
                cli.rmdir(caminho);
                return;
            }
            List<String> filhos = new ArrayList<>();
            List<Boolean> sao = new ArrayList<>();
            try (CloseableHandle h = cli.openDir(caminho)) {
                AtomicReference<Boolean> fim = new AtomicReference<>(Boolean.FALSE);
                while (!Boolean.TRUE.equals(fim.get())) {
                    List<DirEntry> lote = cli.readDir(h, fim);
                    if (lote == null) {
                        break;
                    }
                    for (DirEntry e : lote) {
                        String n = e.getFilename();
                        if (n == null || n.isEmpty() || n.equals(".") || n.equals("..") || n.indexOf('/') >= 0) {
                            continue;
                        }
                        filhos.add(caminho.equals("/") ? "/" + n : caminho + "/" + n);
                        sao.add(e.getAttributes().isDirectory() && !e.getAttributes().isSymbolicLink());
                    }
                }
            }
            for (int i = 0; i < filhos.size(); i++) {
                if (sao.get(i)) {
                    apagarEm(cli, filhos.get(i), true);
                } else {
                    cli.remove(filhos.get(i));
                }
            }
            cli.rmdir(caminho);
        } else {
            cli.remove(caminho);
        }
    }

    @Override
    public ResumoDeTamanho resumir(String caminho, Cancelamento cancelamento) throws ErroSftp {
        String c = limpo(caminho);
        return usar("ver esta pasta", true, cli -> {
            Attributes raiz = cli.lstat(c);
            if (!raiz.isDirectory() || raiz.isSymbolicLink()) {
                return new ResumoDeTamanho(1, 0, raiz.getFlags().contains(Attribute.Size) ? raiz.getSize() : 0, true);
            }
            long arquivos = 0;
            long pastas = 0;
            long bytes = 0;
            long visitados = 0;
            Deque<String> pilha = new ArrayDeque<>();
            pilha.push(c);
            while (!pilha.isEmpty()) {
                cancelamento.conferir();
                String pasta = pilha.pop();
                try (CloseableHandle h = cli.openDir(pasta)) {
                    AtomicReference<Boolean> fim = new AtomicReference<>(Boolean.FALSE);
                    while (!Boolean.TRUE.equals(fim.get())) {
                        List<DirEntry> lote = cli.readDir(h, fim);
                        if (lote == null) {
                            break;
                        }
                        for (DirEntry e : lote) {
                            String n = e.getFilename();
                            if (n == null || n.isEmpty() || n.equals(".") || n.equals("..") || n.indexOf('/') >= 0) {
                                continue;
                            }
                            if (++visitados > TETO_DA_CONTAGEM) {
                                return new ResumoDeTamanho(arquivos, pastas, bytes, false);
                            }
                            Attributes a = e.getAttributes();
                            if (a.isDirectory() && !a.isSymbolicLink()) {
                                pastas++;
                                pilha.push(pasta.equals("/") ? "/" + n : pasta + "/" + n);
                            } else {
                                arquivos++;
                                bytes += a.getFlags().contains(Attribute.Size) ? Math.max(0, a.getSize()) : 0;
                            }
                        }
                    }
                }
            }
            return new ResumoDeTamanho(arquivos, pastas, bytes, true);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Transferência
    // ---------------------------------------------------------------------------------------------

    @Override
    public void enviar(Path local, String remoto, Progresso progresso, Cancelamento cancelamento) throws ErroSftp {
        String destino = limpo(remoto);
        long total;
        try {
            if (!Files.isRegularFile(local, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(local)) {
                throw new ErroSftp(ErroSftp.Tipo.OUTRO, "\"" + local.getFileName() + "\" não é um arquivo comum.");
            }
            total = Files.size(local);
        } catch (IOException e) {
            throw new ErroSftp(ErroSftp.Tipo.OUTRO, "Não consegui ler \"" + local.getFileName() + "\" neste Mac.", e);
        }
        int permissoesLocais = permissoesLocais(local);
        String pasta = CaminhoRemoto.pai(destino);
        String temporario = nomeTemporario(pasta, CaminhoRemoto.nome(destino), "envio");
        usar("gravar nesta pasta", false, cli -> {
            boolean criou = false;
            try {
                try (InputStream in = Files.newInputStream(local);
                     OutputStream out = cli.write(temporario, OpenMode.Create, OpenMode.Write, OpenMode.Exclusive)) {
                    criou = true;
                    copiar(in, out, total, progresso, cancelamento);
                }
                long noServidor = cli.stat(temporario).getSize();
                if (noServidor != total) {
                    throw new ErroSftp(ErroSftp.Tipo.TAMANHO_NAO_CONFERE, "O tamanho de \"" + CaminhoRemoto.nome(destino)
                        + "\" no servidor não confere com o do seu Mac. Envie de novo.");
                }
                Attributes existente = existente(cli, destino);
                int permissoes = existente != null && existente.getFlags().contains(Attribute.Perms)
                    ? existente.getPermissions() & 07777 : permissoesLocais;
                if (permissoes >= 0) {
                    try {
                        Attributes p = new Attributes();
                        p.setPermissions(permissoes);
                        cli.setStat(temporario, p);
                    } catch (IOException e) {
                        LOG.debug("Não consegui ajustar as permissões do arquivo enviado: {}", e.toString());
                    }
                }
                if (existente != null) {
                    trocar(cli, temporario, destino);
                } else {
                    cli.rename(temporario, destino);
                }
                criou = false;
                return null;
            } finally {
                if (criou) {
                    removerQuieto(cli, temporario);
                }
            }
        });
    }

    private static int permissoesLocais(Path local) {
        try {
            int p = 0;
            for (PosixFilePermission f : Files.getPosixFilePermissions(local)) {
                p |= switch (f) {
                    case OWNER_READ -> 0400;
                    case OWNER_WRITE -> 0200;
                    case OWNER_EXECUTE -> 0100;
                    case GROUP_READ -> 040;
                    case GROUP_WRITE -> 020;
                    case GROUP_EXECUTE -> 010;
                    case OTHERS_READ -> 04;
                    case OTHERS_WRITE -> 02;
                    case OTHERS_EXECUTE -> 01;
                };
            }
            return p;
        } catch (IOException | UnsupportedOperationException e) {
            return -1;
        }
    }

    @Override
    public void baixar(String remoto, Path local, Progresso progresso, Cancelamento cancelamento) throws ErroSftp {
        String origem = limpo(remoto);
        Path parcial = local.resolveSibling("." + nomeCurto(local) + ".labdesktop-parcial");
        usar("baixar este arquivo", false, cli -> {
            Attributes a = cli.stat(origem);
            if (a.isDirectory()) {
                throw new ErroSftp(ErroSftp.Tipo.OUTRO, "\"" + CaminhoRemoto.nome(origem) + "\" é uma pasta.");
            }
            long total = a.getFlags().contains(Attribute.Size) ? a.getSize() : -1;
            boolean ok = false;
            try {
                try (InputStream in = cli.read(origem);
                     OutputStream out = Files.newOutputStream(parcial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                    copiar(in, out, total, progresso, cancelamento);
                }
                long gravado = Files.size(parcial);
                if (total >= 0 && gravado != total) {
                    throw new ErroSftp(ErroSftp.Tipo.TAMANHO_NAO_CONFERE, "O tamanho de \"" + local.getFileName()
                        + "\" baixado não confere com o do servidor. Baixe de novo.");
                }
                if (a.getFlags().contains(Attribute.ModifyTime) && a.getModifyTime() != null) {
                    try {
                        Files.setLastModifiedTime(parcial, FileTime.from(a.getModifyTime().toInstant()));
                    } catch (IOException e) {
                        LOG.debug("Não consegui copiar a data do arquivo: {}", e.toString());
                    }
                }
                try {
                    Files.move(parcial, local, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(parcial, local, StandardCopyOption.REPLACE_EXISTING);
                }
                ok = true;
                return null;
            } finally {
                if (!ok) {
                    try {
                        Files.deleteIfExists(parcial);
                    } catch (IOException e) {
                        LOG.debug("Não consegui apagar o parcial {}: {}", parcial, e.toString());
                    }
                }
            }
        });
    }

    private static String nomeCurto(Path local) {
        String n = local.getFileName().toString();
        return n.length() > 100 ? n.substring(0, 100) : n;
    }

    /** Copia em blocos, conferindo o cancelamento a cada um e contando o andamento. */
    private static void copiar(InputStream in, OutputStream out, long total, Progresso progresso, Cancelamento cancelamento)
        throws IOException {
        byte[] buf = new byte[BLOCO];
        long feitos = 0;
        progresso.andamento(0, total);
        int n;
        while ((n = in.read(buf)) > 0) {
            cancelamento.conferir();
            out.write(buf, 0, n);
            feitos += n;
            progresso.andamento(feitos, total);
        }
        cancelamento.conferir();
    }
}
