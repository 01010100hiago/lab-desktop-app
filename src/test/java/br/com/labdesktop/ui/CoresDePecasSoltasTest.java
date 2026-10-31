package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.teste.SistemaDoTeste;
import br.com.labdesktop.ui.AuditoriaDeCores.Medida;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.ItemDeMenuPerigo;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * As cores do que o catálogo de telas e diálogos não alcançava. Menus de contexto (o do servidor e o da lista do registro, com o item vermelho de
 * "Remover"), dicas de ferramenta, o seletor de arquivos do sistema (o {@code JFileChooser} que o {@code EscolhedorLocal} abre) e o texto-dica dos campos vazios. Cada
 * peça nos dois temas, montada e trocada de tema com ela já criada.
 */
@ExtendWith(VerificaEdt.class)
class CoresDePecasSoltasTest {

    private final AvisosGravados avisos = new AvisosGravados();

    private JPopupMenu menuComItemPerigo() {
        JPopupMenu m = new JPopupMenu();
        m.add(new JMenuItem("Atualizar agora"));
        m.add(new JMenuItem("Conectar"));
        m.addSeparator();
        m.add(new ItemDeMenuPerigo("Remover o servidor", Tokens.PERIGO));
        m.add(new ItemDeMenuPerigo("Remover o agente", Tokens.PERIGO));
        return m;
    }

    @Test
    void menusDicasSeletorDeArquivosECamposVaziosEnxergamSeNosDoisTemas() throws Exception {
        List<String> todas = new ArrayList<>();
        List<String> ruins = new ArrayList<>();
        for (Tema inicial : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(inicial);
            var menu = Visual.naEdt(this::menuComItemPerigo);
            var registro = Visual.naEdt(() -> new br.com.labdesktop.ui.telas.PainelDoRegistro(true, null,
                new br.com.labdesktop.ui.componentes.CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("t")), avisos, () -> java.time.ZoneId.of("America/Sao_Paulo")));
            JToolTip dica = Visual.naEdt(() -> {
                JToolTip t = new JToolTip();
                t.setTipText("Adicionar um servidor");
                return t;
            });
            JFileChooser seletor = Visual.naEdt(() -> new JFileChooser(Path.of(".").toFile()));
            JTextField vazio = Visual.naEdt(() -> Campos.texto(24, "Nome do servidor"));
            JTextField busca = Visual.naEdt(() -> Campos.busca(24, "Buscar na ajuda..."));
            Painel soltos = Visual.naEdt(() -> {
                Painel p = new Painel(new FlowLayout(FlowLayout.LEFT, 12, 12));
                p.fundo(Tokens.SUPERFICIE);
                p.add(vazio);
                p.add(busca);
                return p;
            });
            for (Tema agora : new Tema[] {inicial, inicial == Tema.CLARO ? Tema.ESCURO : Tema.CLARO}) {
                if (agora != inicial) {
                    Visual.tema(agora);
                    Visual.naEdt(() -> {
                        SwingUtilities.updateComponentTreeUI(menu);
                        SwingUtilities.updateComponentTreeUI(dica);
                        SwingUtilities.updateComponentTreeUI(seletor);
                        SwingUtilities.updateComponentTreeUI(soltos);
                    });
                }
                String ctx = agora + (agora == inicial ? " montado" : " trocado de " + inicial) + " | ";
                List<Medida> medidas = new ArrayList<>();
                medidas.addAll(Visual.naEdt(() -> AuditoriaDeCores.auditar(menu, 260, Visual.naEdt(() -> menu.getPreferredSize().height), true)));
                medidas.addAll(Visual.naEdt(() -> AuditoriaDeCores.auditar(Visual.naEdt(() -> registro.menuDeContexto()), 320, 70, true)));
                medidas.add(Visual.naEdt(() -> AuditoriaDeCores.medirPeca("dica de ferramenta", dica, dica.getPreferredSize().width + 4, dica.getPreferredSize().height + 4, AuditoriaDeCores.MINIMO_DE_TEXTO)));
                // No Windows o seletor de arquivos tem o painel de atalhos do FlatLaf (Documentos, Desktop...): o ícone de cada atalho é do shell do Windows (azulado, e o app não o desenha nem o recolore).
                // Só o contraste DESSES ícones (no estado apertado) fica de fora; o resto do seletor (nomes, tabela, botões, campos) continua medido, e no Mac e no Linux o painel nem existe.
                List<Medida> doSeletor = Visual.naEdt(() -> AuditoriaDeCores.auditar(seletor, 720, 480, true));
                medidas.addAll(doSeletor.stream().filter(m -> !(SistemaDoTeste.windows() && m.quem().contains("@FlatShortcutsPanel") && m.quem().endsWith("(icone)"))).toList());
                medidas.add(Visual.naEdt(() -> AuditoriaDeCores.medirPeca("campo vazio com dica (placeholder)", vazio, vazio.getPreferredSize().width, vazio.getPreferredSize().height, AuditoriaDeCores.MINIMO_DE_TEXTO)));
                medidas.add(Visual.naEdt(() -> AuditoriaDeCores.medirPeca("campo de busca vazio com dica", busca, busca.getPreferredSize().width, busca.getPreferredSize().height, AuditoriaDeCores.MINIMO_DE_TEXTO)));
                for (Medida m : medidas) {
                    todas.add(ctx + m);
                    if (m.falhou()) {
                        ruins.add(ctx + m);
                    }
                }
                Visual.salvar(Visual.renderizar(menu, 260, Visual.naEdt(() -> menu.getPreferredSize().height)), "menu-de-contexto-" + agora.name().toLowerCase() + (agora == inicial ? "" : "-trocado"));
                Visual.salvar(Visual.renderizar(soltos, 700, 70), "campos-vazios-" + agora.name().toLowerCase() + (agora == inicial ? "" : "-trocado"));
                Visual.salvar(Visual.renderizar(seletor, 720, 480), "seletor-de-arquivos-" + agora.name().toLowerCase() + (agora == inicial ? "" : "-trocado"));
            }
        }
        Files.write(Path.of("build/auditoria-de-cores-pecas-soltas.txt"), todas);
        Files.write(Path.of("build/auditoria-de-cores-pecas-soltas-ruins.txt"), ruins);
        assertTrue(todas.size() > 40, "poucas medidas: " + todas.size());
        assertTrue(ruins.isEmpty(), ruins.size() + " medidas abaixo do limite (as 15 primeiras):\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }
}
