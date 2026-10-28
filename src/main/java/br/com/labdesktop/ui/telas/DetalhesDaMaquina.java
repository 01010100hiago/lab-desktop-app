package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.DetalheServidor.Bateria;
import br.com.labdesktop.api.modelo.DetalheServidor.Energia;
import br.com.labdesktop.api.modelo.DetalheServidor.Maquina;
import br.com.labdesktop.api.modelo.DetalheServidor.Sensor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.InfoServidor.Cpu;
import br.com.labdesktop.api.modelo.InfoServidor.Memoria;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.componentes.BarraSeveridade;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.CamposEmGrade.Campo;
import br.com.labdesktop.ui.telas.TabelaDeLinhas.Coluna;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Máquina": tudo sobre o servidor como máquina, em duas colunas e sem recolher. Versões (painel, Claude Code da imagem
 * das sessões, Node, Docker, Compose, pnpm), a máquina (fabricante, modelo, BIOS, placa, processador), os discos por
 * montagem, os sensores de temperatura, a energia (tomada e baterias), a CPU por núcleo e a carga, a rede e a memória em
 * detalhe. Sensores, energia e máquina vêm de {@code /api/servidor/detalhe}; o resto, de {@code /api/servidor}.
 */
final class DetalhesDaMaquina extends Painel {

    private static final long serialVersionUID = 1L;

    private final CamposEmGrade versoes = new CamposEmGrade();
    private final CamposEmGrade maquina = new CamposEmGrade();
    private final TabelaDeLinhas discos = new TabelaDeLinhas(new Coluna("Montagem", 1.0, false, true), new Coluna("Usado", 0.7, true, false),
        new Coluna("Total", 0.7, true, false), new Coluna("Livre", 0.7, true, false), new Coluna("Uso", 1.0, true, false));
    private final TabelaDeLinhas sensores = new TabelaDeLinhas(new Coluna("Sensor", 1.6, false, false),
        new Coluna("Agora", 0.5, true, false), new Coluna("Limite", 0.5, true, false), new Coluna("Crítico", 0.5, true, false));
    private final JPanel energia = Caixa.coluna(4);
    private final JPanel nucleos = new Painel();
    private final CamposEmGrade carga = new CamposEmGrade();
    private final CamposEmGrade rede = new CamposEmGrade();
    private final CamposEmGrade memoria = new CamposEmGrade();
    private final Rotulo cabecalhoDoDetalhe = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    /** "Este computador": o que o agente mede é a máquina virtual do Docker, e a rede é a do container dele. */
    private final Rotulo notaDoLocal = new Rotulo("Este computador: CPU, memória, discos e sensores são os da máquina virtual do Docker (é nela que o agente roda). A rede é só a do agente, não a do computador.")
        .corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private boolean local;

    DetalhesDaMaquina() {
        super(new java.awt.BorderLayout());
        fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));
        nucleos.setLayout(new java.awt.GridLayout(0, 2, 16, 4));
        JPanel esquerda = Caixa.coluna(18, secao("Máquina", maquina), secao("Versões", versoes), secao("Discos", discos),
            secao("Energia", energia));
        JPanel direita = Caixa.coluna(18, secao("Sensores de temperatura", sensores), secao("CPU por núcleo e carga",
            Caixa.coluna(8, nucleos, carga)), secao("Rede e memória", Caixa.coluna(8, rede, memoria)));
        JPanel miolo = new Painel(new GridLayout(1, 2, 32, 0));
        miolo.add(esquerda);
        miolo.add(direita);
        notaDoLocal.setName("nota-do-computador-local");
        notaDoLocal.setVisible(false);
        notaDoLocal.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        JPanel topo = Caixa.coluna(0, notaDoLocal, cabecalhoDoDetalhe);
        add(topo, java.awt.BorderLayout.NORTH);
        add(miolo, java.awt.BorderLayout.CENTER);
        setName("detalhes-da-maquina");
        atualizar(null, null, false);
    }

    private static JPanel secao(String titulo, JComponent corpo) {
        Rotulo t = new Rotulo(titulo.toUpperCase(Locale.forLanguageTag("pt-BR"))).corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f);
        JPanel p = Caixa.coluna(6, t, corpo);
        p.setName("secao-" + titulo);
        return p;
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /**
     * @param detalheFalhou a consulta do detalhe falhou e ainda não há nenhum valor dele (sensores, energia e máquina
     *                      mostram "não consegui ler" em vez de ficarem para sempre em "carregando")
     */
    /** "Este computador": diz que CPU, memória e disco são os da máquina virtual do Docker e que a rede é só a do agente. */
    void definirLocal(boolean sim) {
        this.local = sim;
        notaDoLocal.setVisible(sim);
        notaDoLocal.setToolTipText(sim ? notaDoLocal.getText() : null);
    }

    void atualizar(InfoServidor info, DetalheServidor detalhe, boolean detalheFalhou) {
        cabecalhoDoDetalhe.setText(detalhe == null ? (detalheFalhou ? "Não consegui ler os sensores, a energia e o hardware agora."
            : "Lendo os sensores, a energia e o hardware…") : "");
        cabecalhoDoDetalhe.setVisible(detalhe == null);

        InfoServidor.NodeInfo node = info == null ? null : info.node();
        var img = node == null ? java.util.Map.<String, String>of() : node.imagemSessao();
        InfoServidor.DockerInfo dk = info == null ? null : info.docker();
        versoes.definir(List.of(
            new Campo("Painel (Node)", FormatosDoServidor.versao(node == null ? null : node.painel())),
            new Campo("Claude Code (sessões)", FormatosDoServidor.versao(img.get("claude"))),
            new Campo("Node (sessões)", FormatosDoServidor.versao(img.get("node"))),
            new Campo("Docker", FormatosDoServidor.versao(dk != null && dk.versao() != null ? dk.versao() : img.get("docker"))),
            new Campo("Compose", FormatosDoServidor.versao(img.get("compose"))),
            new Campo("pnpm", FormatosDoServidor.versao(img.get("pnpm")))));

        Maquina m = detalhe == null ? null : detalhe.maquina();
        Cpu cpu = info == null ? null : info.cpu();
        String modelo = m == null ? null : juntar(" ", m.fabricante(), m.modelo());
        maquina.definir(List.of(
            new Campo("Fabricante e modelo", modelo),
            new Campo("Placa", m == null ? null : m.placa()),
            new Campo("BIOS", m == null ? null : juntar(", ", m.bios(), m.biosData())),
            new Campo("Processador", m != null && m.cpuModelo() != null ? m.cpuModelo() : cpu == null ? null : cpu.modelo()),
            new Campo("Núcleos", numero(m != null && m.nucleos() != null ? m.nucleos() : cpu == null ? null : cpu.nucleos())),
            new Campo("Memória", m != null && m.memoriaTotal() != null ? FormatosDoServidor.bytes(m.memoriaTotal())
                : info == null || info.memoria() == null ? null : FormatosDoServidor.bytes(info.memoria().total())),
            new Campo("Arquitetura", m == null ? null : m.arquitetura())));

        atualizarDiscos(info);
        atualizarSensores(detalhe);
        atualizarEnergia(detalhe);
        atualizarNucleos(cpu);
        atualizarCarga(info);
        atualizarRedeEMemoria(info);
        revalidate();
        repaint();
    }

    private static String numero(Integer n) {
        return n == null ? null : String.valueOf(n);
    }

    private static String juntar(String sep, String... partes) {
        List<String> ok = new ArrayList<>();
        for (String p : partes) {
            if (p != null && !p.isBlank()) {
                ok.add(p);
            }
        }
        return ok.isEmpty() ? null : String.join(sep, ok);
    }

    private void atualizarDiscos(InfoServidor info) {
        List<List<String>> linhas = new ArrayList<>();
        for (InfoServidor.Disco d : info == null ? List.<InfoServidor.Disco>of() : info.discos()) {
            Double pct = br.com.labdesktop.servidor.RegrasDeSaude.pctUsado(d);
            String uso = FormatosDoServidor.porcentagem(pct == null ? null : pct / 100);
            if (pct != null && pct >= 90) {
                uso += " (crítico)";
            } else if (pct != null && pct >= 75) {
                uso += " (atenção)";
            }
            linhas.add(List.of(d.montagem() == null ? FormatosDoServidor.SEM_DADO : d.montagem(), FormatosDoServidor.bytes(d.usado()),
                FormatosDoServidor.bytes(d.total()), FormatosDoServidor.bytes(d.livre()), uso));
        }
        discos.definir(linhas, FormatosDoServidor.SEM_DADO);
    }

    private void atualizarSensores(DetalheServidor d) {
        List<List<String>> linhas = new ArrayList<>();
        for (Sensor s : d == null ? List.<Sensor>of() : d.sensores()) {
            String nome = "Package id 0".equals(s.rotulo()) ? "Processador (pacote)"
                : s.rotulo() != null && s.rotulo().startsWith("Core ") ? "Núcleo " + s.rotulo().substring(5)
                : "acpitz".equals(s.chip()) ? "Placa (ACPI)" : s.rotulo();
            linhas.add(List.of(nome == null ? FormatosDoServidor.SEM_DADO : nome,
                FormatosDoServidor.temperatura(s.c()), FormatosDoServidor.temperatura(s.max()),
                FormatosDoServidor.temperatura(s.crit())));
        }
        sensores.definir(linhas, d == null ? FormatosDoServidor.SEM_DADO : "Nenhum sensor de temperatura encontrado.");
    }

    private void atualizarEnergia(DetalheServidor d) {
        energia.removeAll();
        Energia e = d == null ? null : d.energia();
        if (e == null) {
            energia.add(rotulo(FormatosDoServidor.SEM_DADO, Tokens.TEXTO2));
        } else {
            String linha = e.tomada() == null ? "Fonte de energia desconhecida"
                : e.tomada() ? "Na tomada" : "Na bateria: a tomada caiu ou foi desligada";
            energia.add(rotulo(linha, e.tomada() != null && !e.tomada() ? Tokens.PERIGO : Tokens.TEXTO));
            for (Bateria b : e.baterias()) {
                energia.add(rotulo(b.nome() + ": " + (b.carga() == null ? "?" : FormatosDoServidor.percentual(b.carga()))
                    + (b.estado() == null ? "" : ", " + b.estado())
                    + (b.saude() == null ? "" : ", saúde " + FormatosDoServidor.porcentagem(b.saude())), Tokens.TEXTO2));
            }
            if (e.baterias().isEmpty()) {
                // texto longo: quebra em linhas em vez de ser cortado
                energia.add(new br.com.labdesktop.ui.componentes.TextoCorrido("Sem bateria: se a energia cair, o servidor "
                    + "desliga na hora, sem tempo de salvar nada. Um nobreak resolve.", 320).corDoToken(Tokens.TEXTO3).tamanho(12f));
            }
        }
        for (java.awt.Component c : energia.getComponents()) {
            ((JComponent) c).setAlignmentX(LEFT_ALIGNMENT); // uma coluna de BoxLayout mistura os alinhamentos dos filhos
        }
        energia.revalidate();
    }

    private static Rotulo rotulo(String texto, String token) {
        Rotulo r = new Rotulo(texto).corDoToken(token).tamanho(12.5f).truncavel();
        r.setToolTipText(texto);
        return r;
    }

    private void atualizarNucleos(Cpu cpu) {
        nucleos.removeAll();
        List<Double> usos = cpu == null ? List.of() : cpu.porNucleo();
        for (int i = 0; i < usos.size(); i++) {
            Double u = usos.get(i);
            BarraSeveridade barra = new BarraSeveridade(BarraSeveridade.Tamanho.FINA);
            barra.definir(u == null ? 0 : u, u == null ? Severidade.NORMAL : Severidade.doCredito(u), "núcleo " + (i + 1));
            Rotulo nome = new Rotulo("Núcleo " + (i + 1)).corDoToken(Tokens.TEXTO3).tamanho(11.5f);
            nome.setPreferredSize(new Dimension(58, 16));
            Rotulo pct = new Rotulo(FormatosDoServidor.porcentagem(u)).tamanho(11.5f).aDireita();
            pct.setPreferredSize(new Dimension(40, 16));
            nucleos.add(Caixa.linha(6, nome, barra, pct));
        }
        if (usos.isEmpty()) {
            nucleos.add(rotulo(FormatosDoServidor.SEM_DADO, Tokens.TEXTO2));
        }
        nucleos.revalidate();
    }

    private void atualizarCarga(InfoServidor info) {
        List<Double> c = info == null ? List.of() : info.carga();
        Integer n = info == null || info.cpu() == null ? null : info.cpu().nucleos();
        String[] nomes = {"Em 1 minuto", "Em 5 minutos", "Em 15 minutos"};
        List<Campo> campos = new ArrayList<>();
        for (int i = 0; i < nomes.length; i++) {
            Double v = i < c.size() ? c.get(i) : null;
            Gravidade g = null;
            String texto = FormatosDoServidor.carga(v);
            if (v != null && n != null && n > 0) {
                Severidade sev = Severidade.doCredito(v / n);
                texto += " (" + FormatosDoServidor.porcentagem(v / n) + " da capacidade)";
                g = sev == Severidade.NORMAL ? null : sev == Severidade.ALTA ? Gravidade.ATENCAO : Gravidade.PERIGO;
            }
            campos.add(new Campo("Carga " + nomes[i].toLowerCase(Locale.ROOT), texto, g));
        }
        carga.definir(campos);
    }

    private void atualizarRedeEMemoria(InfoServidor info) {
        InfoServidor.Rede r = info == null ? null : info.rede();
        rede.definir(List.of(
            new Campo(local ? "Placa de rede (do container do agente)" : "Placa de rede", r == null ? null : r.interfaceDeRede()),
            new Campo(local ? "Descida (só do agente)" : "Descida (download)", r == null ? null : FormatosDoServidor.bitsPorSegundo(r.rxBps())),
            new Campo(local ? "Subida (só do agente)" : "Subida (upload)", r == null ? null : FormatosDoServidor.bitsPorSegundo(r.txBps()))));
        Memoria m = info == null ? null : info.memoria();
        InfoServidor.Swap s = m == null ? null : m.swap();
        memoria.definir(List.of(
            new Campo("Em uso", m == null ? null : FormatosDoServidor.bytes(m.usada()) + " de " + FormatosDoServidor.bytes(m.total())),
            new Campo("Disponível", m == null ? null : FormatosDoServidor.bytes(m.disponivel())),
            new Campo("Cache (arquivos recentes)", m == null ? null : FormatosDoServidor.bytes(m.cache())),
            new Campo("Swap (reserva no disco)", s == null ? null : FormatosDoServidor.bytes(s.usada()) + " de " + FormatosDoServidor.bytes(s.total()))));
    }

    // ---- para os testes ----

    List<Campo> camposDeVersoes() {
        return versoes.campos();
    }

    List<Campo> camposDeRede() {
        return rede.campos();
    }

    boolean notaDoLocalVisivel() {
        return notaDoLocal.isVisible();
    }

    List<Campo> camposDaMaquina() {
        return maquina.campos();
    }

    List<List<String>> linhasDeDiscos() {
        return discos.linhas();
    }

    List<List<String>> linhasDeSensores() {
        return sensores.linhas();
    }

    String textoDeEnergia() {
        return Textos.de(energia);
    }

    String textoCompleto() {
        return Textos.de(this);
    }
}
