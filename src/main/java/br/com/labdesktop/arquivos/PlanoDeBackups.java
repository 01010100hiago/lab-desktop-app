package br.com.labdesktop.arquivos;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * O plano de baixar arquivos da área de backups de um projeto: quais entram (com nome local SEGURO), quais são recusados e por quê, quanto pesa, se passa do limite que pede
 * confirmação e se cabe no disco do computador. Puro (a única E/S é perguntar o espaço livre à pasta de destino) e testável.
 *
 * <p>O backup mora em {@code <pasta do projeto>/backups/<arquivo>}; o caminho remoto é montado com o nome já validado ({@link CaminhoRemoto#juntar}), e a cerca do
 * {@link ServicoDeArquivos} (a raiz travada no projeto, sem seguir link para fora) confere de novo na hora de baixar.
 */
public final class PlanoDeBackups {

    /** A pasta da área de backups dentro da pasta do projeto. */
    public static final String PASTA = "backups";
    /** Acima disto o usuário confirma antes (o mesmo limite das outras transferências). */
    public static final long LIMITE_DE_AVISO = FilaDeTransferencias.LIMITE_DE_AVISO;
    /** Folga que sempre sobra no disco além do que se baixa (o sistema precisa de espaço para trabalhar). */
    public static final long FOLGA_DO_DISCO = 64L * 1024 * 1024;

    /** Um backup como a lista do projeto o diz. */
    public record Backup(String arquivo, Long tamanho, Instant quando) { }

    /** Um item que entra, com o nome local. */
    public record Item(EntradaRemota entrada, String nomeLocal, boolean nomeMudou) { }

    /** Um item que não entra e por quê. */
    public record Recusado(String arquivo, String motivo) { }

    private final List<Item> itens;
    private final List<Recusado> recusados;
    private final long bytes;
    private final List<String> semTamanho;

    private PlanoDeBackups(List<Item> itens, List<Recusado> recusados, long bytes, List<String> semTamanho) {
        this.itens = List.copyOf(itens);
        this.recusados = List.copyOf(recusados);
        this.bytes = bytes;
        this.semTamanho = List.copyOf(semTamanho);
    }

    /**
     * @param pastaDoProjeto o caminho do projeto no servidor
     * @param escolhidos     os backups que o usuário escolheu
     */
    public static PlanoDeBackups de(String pastaDoProjeto, List<Backup> escolhidos) {
        List<Item> itens = new ArrayList<>();
        List<Recusado> recusados = new ArrayList<>();
        long bytes = 0;
        List<String> semTamanho = new ArrayList<>();
        java.util.Set<String> locais = new java.util.HashSet<>();
        for (Backup b : escolhidos) {
            String arquivo = b.arquivo() == null ? "" : b.arquivo();
            Optional<NomeLocalSeguro.Nome> nome = NomeLocalSeguro.de(arquivo);
            if (nome.isEmpty()) {
                recusados.add(new Recusado(arquivo, "O nome \"" + resumido(arquivo) + "\" não é um nome de arquivo seguro para gravar neste computador; não foi baixado."));
                continue;
            }
            String remoto;
            try {
                remoto = CaminhoRemoto.juntar(CaminhoRemoto.juntar(pastaDoProjeto, PASTA), arquivo);
            } catch (ErroSftp e) {
                recusados.add(new Recusado(arquivo, "O nome \"" + resumido(arquivo) + "\" não vale como nome de arquivo do servidor; não foi baixado."));
                continue;
            }
            String local = nome.get().seguro();
            if (!locais.add(local.toLowerCase(java.util.Locale.ROOT))) {
                recusados.add(new Recusado(arquivo, "\"" + resumido(arquivo) + "\" ficaria com o mesmo nome de outro arquivo escolhido neste computador; baixe um de cada vez."));
                continue;
            }
            long tamanho = b.tamanho() == null ? -1 : Math.max(-1, b.tamanho());
            itens.add(new Item(new EntradaRemota(local, remoto, EntradaRemota.Tipo.ARQUIVO, false, tamanho, b.quando(), -1, "", "", null), local, nome.get().mudou()));
            bytes += Math.max(0, tamanho);
            if (tamanho < 0) {
                semTamanho.add(local);
            }
        }
        return new PlanoDeBackups(itens, recusados, bytes, semTamanho);
    }

    private static String resumido(String s) {
        String limpo = s.replaceAll("\\p{Cntrl}", "?");
        return limpo.length() <= 60 ? limpo : limpo.substring(0, 59) + "…";
    }

    public List<Item> itens() {
        return itens;
    }

    public List<EntradaRemota> entradas() {
        return itens.stream().map(Item::entrada).toList();
    }

    public List<Recusado> recusados() {
        return recusados;
    }

    /**
     * Os arquivos (nome local) cujo tamanho o servidor NÃO disse. Eles não entram na soma ({@link #bytes}) e o app não consegue conferir o tamanho depois nem saber se cabem no disco:
     * a tela avisa antes de baixar, em vez de fingir que pesam zero.
     */
    public List<String> semTamanho() {
        return semTamanho;
    }

    /** A soma dos tamanhos que o servidor disse (os que ele não disse ficam de fora: ver {@link #semTamanho}). */
    public long bytes() {
        return bytes;
    }

    public boolean vazio() {
        return itens.isEmpty();
    }

    /** Passa do limite que pede confirmação? */
    public boolean grande() {
        return bytes > LIMITE_DE_AVISO;
    }

    /** Algum nome local não é o do servidor (caractere trocado)? A tela avisa. */
    public boolean algumNomeMudou() {
        return itens.stream().anyMatch(Item::nomeMudou);
    }

    /**
     * Cabe no disco? Compara o que se vai baixar mais a folga com o espaço livre da pasta de destino.
     *
     * @return {@code Optional.empty()} se cabe (ou se o sistema não disse); senão a frase que diz quanto falta
     */
    public Optional<String> faltaDeEspaco(Path pastaDeDestino) {
        long livre;
        try {
            FileStore fs = Files.getFileStore(pastaDeDestino);
            livre = fs.getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return faltaDeEspaco(livre);
    }

    /** O primeiro item cujo caminho passa do limite do Windows com esta pasta de destino, ou vazio. */
    public Optional<String> caminhoLongoDemaisNoWindows(Path pastaDeDestino) {
        for (Item i : itens) {
            Optional<String> longo = NomeLocalSeguro.longoDemaisNoWindows(pastaDeDestino.toAbsolutePath().toString(), i.nomeLocal());
            if (longo.isPresent()) {
                return longo;
            }
        }
        return Optional.empty();
    }

    /** O mesmo com o espaço livre já em mãos (os testes). */
    public Optional<String> faltaDeEspaco(long livre) {
        long precisa = bytes + FOLGA_DO_DISCO;
        if (livre >= precisa) {
            return Optional.empty();
        }
        return Optional.of("Não há espaço livre suficiente neste computador: faltam " + formatar(precisa - livre) + " (o backup pesa " + formatar(bytes)
            + " e o app deixa " + formatar(FOLGA_DO_DISCO) + " de folga). Libere espaço ou escolha outro disco.");
    }

    private static String formatar(long bytes) {
        return br.com.labdesktop.util.Formato.fmtBytes(bytes);
    }
}
