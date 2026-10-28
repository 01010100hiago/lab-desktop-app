package br.com.labdesktop.ui.telas.arquivos;

import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JFileChooser;

/**
 * Quem pergunta ao usuário QUAIS arquivos do Mac enviar e PARA QUAL pasta baixar. Existe como interface para os testes
 * responderem sem abrir janela do sistema.
 */
public interface EscolhedorLocal {

    /** Arquivos e pastas do Mac para enviar; lista vazia se o usuário cancelou. */
    List<Path> escolherParaEnviar();

    /** A pasta do Mac onde guardar o que for baixado (começa em {@code padrao}); vazio se cancelou. */
    Optional<Path> escolherPastaParaBaixar(Path padrao);

    /**
     * Onde o usuário quer SALVAR um arquivo que o app gerou (o relatório): o seletor de arquivos do sistema, começando na pasta de Downloads com {@code nomeSugerido}. Vazio se cancelou.
     * Neutro de sistema: nada de caminho fixo de um Mac.
     */
    default Optional<Path> escolherArquivoParaSalvar(String nomeSugerido, String extensao, String descricao) {
        return Optional.empty();
    }

    /** A pasta do computador que o usuário quer importar como projeto (o seletor do sistema, só pastas); vazio se cancelou. */
    default Optional<Path> escolherPastaParaImportar() {
        return Optional.empty();
    }

    /** O de produção: o seletor de arquivos do Swing, sobre a janela que {@code janela} devolver na hora de abrir. */
    static EscolhedorLocal doSistema(java.util.function.Supplier<Component> janela) {
        return new EscolhedorLocal() {
            @Override
            public List<Path> escolherParaEnviar() {
                JFileChooser c = new JFileChooser();
                c.setDialogTitle("Escolha o que enviar ao servidor");
                c.setApproveButtonText("Enviar");
                c.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                c.setMultiSelectionEnabled(true);
                if (c.showOpenDialog(janela.get()) != JFileChooser.APPROVE_OPTION) {
                    return List.of();
                }
                List<Path> l = new ArrayList<>();
                for (File f : c.getSelectedFiles()) {
                    l.add(f.toPath());
                }
                return l;
            }

            @Override
            public Optional<Path> escolherArquivoParaSalvar(String nomeSugerido, String extensao, String descricao) {
                Path padrao = br.com.labdesktop.arquivos.ServicosDeArquivos.pastaDeDownloadsPadrao();
                JFileChooser c = new JFileChooser(padrao.toFile());
                c.setDialogTitle("Salvar o relatório");
                c.setApproveButtonText("Salvar");
                c.setFileSelectionMode(JFileChooser.FILES_ONLY);
                c.setSelectedFile(padrao.resolve(nomeSugerido).toFile());
                c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(descricao, extensao));
                if (c.showSaveDialog(janela.get()) != JFileChooser.APPROVE_OPTION) {
                    return Optional.empty();
                }
                Path escolhido = c.getSelectedFile().toPath();
                String nome = escolhido.getFileName().toString();
                return Optional.of(nome.toLowerCase(java.util.Locale.ROOT).endsWith("." + extensao) ? escolhido : escolhido.resolveSibling(nome + "." + extensao));
            }

            @Override
            public Optional<Path> escolherPastaParaImportar() {
                JFileChooser c = new JFileChooser();
                c.setDialogTitle("Escolha a pasta do projeto que você quer importar");
                c.setApproveButtonText("Importar esta pasta");
                c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                return c.showOpenDialog(janela.get()) == JFileChooser.APPROVE_OPTION ? Optional.of(c.getSelectedFile().toPath()) : Optional.empty();
            }

            @Override
            public Optional<Path> escolherPastaParaBaixar(Path padrao) {
                JFileChooser c = new JFileChooser(padrao == null ? null : padrao.toFile());
                c.setDialogTitle("Escolha a pasta do seu computador para guardar o que vai baixar");
                c.setApproveButtonText("Baixar para aqui");
                c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                return c.showOpenDialog(janela.get()) == JFileChooser.APPROVE_OPTION ? Optional.of(c.getSelectedFile().toPath()) : Optional.empty();
            }
        };
    }
}
