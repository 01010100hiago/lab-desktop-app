package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.cloudflare.LabSimulado;
import br.com.labdesktop.git.LeitorDeGit;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.telas.FluxosDeProjeto;
import br.com.labdesktop.ui.telas.TelaProjeto;
import br.com.labdesktop.ui.telas.TelaServidor;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.text.JTextComponent;

/**
 * O mundo das imagens da galeria que mostram a página de um PROJETO dentro da janela inteira: o servidor de mentira (SSH embutido e agente de teste, que responde com os dados inventados
 * de {@link DadosDaGaleria}), a {@link PainelPrincipal} com a barra lateral de servidores e a página do projeto {@code loja-web} aberta pelo mesmo caminho do clique na lista. Cada imagem sai
 * da janela de 1440 x 860 com a moldura do macOS, nos dois temas.
 */
final class CenaDaGaleria implements AutoCloseable {

    static final Duration PRAZO = Duration.ofSeconds(40);
    static final int LARGURA = 1440;
    static final int ALTURA = 860;
    static final List<String> CONTAINERS = List.of("loja-web-web-1", "loja-web-api-1", "loja-web-worker-1", "loja-web-banco-1");

    final Instant agora = Instant.now();
    final CenarioDeUi cenario;
    final br.com.labdesktop.servidor.GerenciadorServidores gerenciador;
    final ServidorLab lab;
    final AvisosGravados avisos = new AvisosGravados();
    final PainelPrincipal painel;
    final Path raizDoServidor;
    CenarioDeCloudflare nuvem;
    ServidorDeTerminalFalso terminal;
    private final String acoesAntes;
    private boolean encerrada;

    /** O servidor de teste conectado, com o projeto inventado, os arquivos dele numa pasta do teste e a janela montada (ainda na lista de servidores). */
    CenaDaGaleria(Path pasta) throws Exception {
        Visual.tema(br.com.labdesktop.util.Preferencias.Tema.CLARO);
        acoesAntes = System.getProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true");
        cenario = new CenarioDeUi(PedidoDeCodigo2fa.NUNCA, false);
        // o usuário já desligou a marca "Servidor protegido" deste servidor de exemplo: o agente do lab que responde aqui não o deixa protegido (os botões de instalar valem)
        // o nome do servidor na tela é "servidor-demo" (em vez de "lab"); o gerenciador nasce DEPOIS da edição e já lê o nome novo
        var cfg = cenario.cofre.servidor(CofreFalso.ID).orElseThrow();
        cenario.cofre.alterarServidor(CofreFalso.ID, config -> config.editar("servidor-demo", br.com.labdesktop.cofre.Transporte.DIRETO, cfg.host(), cfg.porta(), cfg.usuario(), cfg.portaAgente())
            .comPin(cenario.ssh.pin()).comProtegido(true).comProtegido(false));
        gerenciador = new br.com.labdesktop.servidor.GerenciadorServidores(cenario.cofre, PedidoDeCodigo2fa.NUNCA, br.com.labdesktop.ssh.ConfigSsh.rapida());
        lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        cenario.agente.detalhes.put(DadosDaGaleria.PROJETO, DadosDaGaleria.detalheDoProjeto(agora));
        for (String c : CONTAINERS) {
            cenario.agente.logs.put(DadosDaGaleria.PROJETO + "/" + c, DadosDaGaleria.logs(agora));
        }
        String servidor = DadosDaGaleria.servidorComProcessos(MocksDoFront.ler("servidor.json"));
        String detalheDoServidor = DadosDaGaleria.detalheDoServidorComProcessos(br.com.labdesktop.api.FixturesDeApi.lerRecurso("servidor-detalhe-sintetico.json"));
        String limites = DadosDaGaleria.limites(MocksDoFront.ler("limites.json"));
        cenario.agente.excecoes = p -> {
            if (!"GET".equals(p.metodo())) {
                return null;
            }
            return switch (p.caminho()) {
                case "/api/servidor" -> Resposta.json(200, servidor);
                case "/api/servidor/detalhe" -> Resposta.json(200, detalheDoServidor);
                case "/api/limites" -> Resposta.json(200, limites);
                default -> null;
            };
        };
        raizDoServidor = pasta.resolve("servidor");
        Files.createDirectories(raizDoServidor);
        DadosDaGaleria.criarArquivosDoProjeto(raizDoServidor, agora);
        cenario.ssh.comSftp(raizDoServidor.toRealPath());
        // o dono dos arquivos é um usuário do servidor de exemplo, não o de quem roda o teste: o SFTP de teste diria o nome do usuário do computador
        cenario.ssh.sshd.setSubsystemFactories(java.util.List.of(new org.apache.sshd.sftp.server.SftpSubsystemFactory.Builder().withFileSystemAccessor(new DonoInventado()).build()));
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        ServicosDeArquivos servicos = new ServicosDeArquivos(MemoriaDeCaminhos.emMemoria(), pasta.resolve("backups"));
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos), servicos));
    }

    /** O dono e o grupo de todo arquivo do servidor de teste: {@code labuser}. */
    private static final class DonoInventado implements org.apache.sshd.sftp.server.SftpFileSystemAccessor {

        private static final class Pessoa implements java.nio.file.attribute.UserPrincipal, java.nio.file.attribute.GroupPrincipal {
            @Override
            public String getName() {
                return "labuser";
            }

            @Override
            public String toString() {
                return getName();
            }
        }

        @Override
        public java.nio.file.attribute.UserPrincipal resolveFileOwner(org.apache.sshd.sftp.server.SftpSubsystemProxy sftp, Path file, java.nio.file.attribute.UserPrincipal dono) {
            return new Pessoa();
        }

        @Override
        public java.nio.file.attribute.GroupPrincipal resolveGroupOwner(org.apache.sshd.sftp.server.SftpSubsystemProxy sftp, Path file, java.nio.file.attribute.GroupPrincipal grupo) {
            return new Pessoa();
        }

        @Override
        public java.util.NavigableMap<String, Object> resolveReportedFileAttributes(org.apache.sshd.sftp.server.SftpSubsystemProxy sftp, Path file, int flags,
                                                                                    java.util.NavigableMap<String, Object> attributes, java.nio.file.LinkOption... opcoes) {
            java.util.NavigableMap<String, Object> r = new java.util.TreeMap<>(attributes.comparator());
            r.putAll(attributes);
            if (r.containsKey("owner")) {
                r.put("owner", new Pessoa());
            }
            if (r.containsKey("group")) {
                r.put("group", new Pessoa());
            }
            return r;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Montar o cenário
    // ---------------------------------------------------------------------------------------------

    /** Liga a Cloudflare FALSA ao servidor: conta de exemplo, zona example.com e o endereço lab-loja-web.example.com já publicado para o projeto. */
    void ligarACloudflare(Path pasta) throws Exception {
        nuvem = new CenarioDeCloudflare(pasta.resolve("nuvem"));
        nuvem.prepararOServidorDoDono("name: \"loja-web\"\nservices:\n  web:\n    image: loja-web-web:latest\n    ports:\n      - \"127.0.0.1:3040:3000\"\n    restart: unless-stopped\n",
            "tunnel: " + LabSimulado.UUID + "\ncredentials-file: /etc/cloudflared/" + LabSimulado.UUID + ".json\n\ningress:\n"
                + "  # lab-desktop: lab-loja-web.example.com (projeto loja-web, 2026-10-27)\n"
                + "  - hostname: lab-loja-web.example.com\n    service: http://loja-web-web-1:3000\n  - service: http_status:404\n");
        nuvem.conectarPorArquivo();
        nuvem.cf.semear("lab-loja-web.example.com", "CNAME", LabSimulado.UUID + ".cfargotunnel.com", "lab-desktop: " + DadosDaGaleria.PROJETO);
        lab.definirCloudflare(nuvem.servico);
    }

    /** O shell do projeto passa a responder com este roteiro (o que o servidor de mentira escreve na tela do terminal). */
    void ligarOShell(ServidorDeTerminalFalso.Roteiro roteiro) {
        org.apache.sshd.server.command.CommandFactory antigo = cenario.ssh.sshd.getCommandFactory();
        terminal = new ServidorDeTerminalFalso(cenario.ssh);
        org.apache.sshd.server.command.CommandFactory dele = cenario.ssh.sshd.getCommandFactory();
        // só a linha do shell do projeto ("cd '/srv/...'") vai para o terminal de mentira; o resto continua com o agente de teste
        cenario.ssh.sshd.setCommandFactory((canal, linha) -> linha.startsWith("cd '") ? dele.createCommand(canal, linha) : antigo.createCommand(canal, linha));
        terminal.roteiro(roteiro);
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir o projeto
    // ---------------------------------------------------------------------------------------------

    ServidorLab lab() {
        return lab;
    }

    TelaServidor servidor() {
        return Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
    }

    TelaProjeto tela() {
        return Visual.naEdt(() -> servidor().projetoAberto());
    }

    /** O que um clique na linha do projeto faz: mostra a página dele na janela. {@code leitor}: o Git de mentira (ou {@code null} para deixar o do app). */
    TelaProjeto abrirProjeto(TelaProjeto.Aba aba, LeitorDeGit leitor) {
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(lab, DadosDaGaleria.PROJETO));
        TelaProjeto t = tela();
        if (leitor != null) {
            ligarOGit(t, leitor);
        }
        Visual.naEdt(() -> t.mostrarAba(aba));
        Visual.naEdt(t::assinar); // sem janela de verdade ninguém "aparece": a página e a aba da frente assinam à mão
        if (aba == TelaProjeto.Aba.ARQUIVOS) {
            Visual.naEdt(() -> t.arquivos().iniciar()); // o gerenciador de arquivos não é uma PaginaDoProjeto: começa a ler pela própria API
        } else {
            Visual.naEdt(() -> pagina(t, aba).assinar());
        }
        return t;
    }

    /** Troca o leitor do Git da página (no app é o SSH do servidor; aqui, o histórico inventado). */
    void ligarOGit(TelaProjeto t, LeitorDeGit leitor) {
        Object contexto = Reflexo.chamar(t, "contexto");
        Reflexo.definirCampo(contexto, "leitorDeGit", (Supplier<LeitorDeGit>) () -> leitor);
        Reflexo.definirCampo(contexto, "pastaDoProjeto", (Supplier<String>) () -> DadosDaGaleria.PASTA);
    }

    /** A página (aba) do projeto: uma classe de pacote, por isso devolvida como objeto. */
    static PaginaDaAba pagina(TelaProjeto t, TelaProjeto.Aba aba) {
        return new PaginaDaAba(Reflexo.chamar(t, "pagina", aba));
    }

    /** Uma aba da página do projeto, alcançada por reflexão. */
    record PaginaDaAba(Object objeto) {

        void assinar() {
            Reflexo.chamar(objeto, "assinar");
        }

        <T> T chamar(String metodo, Object... argumentos) {
            return Reflexo.chamar(objeto, metodo, argumentos);
        }
    }

    void esperarODetalhe(TelaProjeto t) {
        Espera.ate(PRAZO, () -> "o detalhe de " + t.projeto() + " | " + textos(), () -> {
            Object visao = Reflexo.chamar(t, "visaoGeral");
            return Visual.naEdt(() -> (Boolean) Reflexo.chamar(visao, "mostraCorpo"));
        });
    }

    // ---------------------------------------------------------------------------------------------
    // O que a tela mostra
    // ---------------------------------------------------------------------------------------------

    /** Todo o texto VISÍVEL da janela: rótulos, campos, botões, selos e as células das tabelas. */
    String textos() {
        return Visual.naEdt(() -> {
            StringBuilder sb = new StringBuilder();
            coletar(painel, sb);
            return sb.toString();
        });
    }

    String textosDe(Component c) {
        return Visual.naEdt(() -> {
            StringBuilder sb = new StringBuilder();
            coletar(c, sb);
            return sb.toString();
        });
    }

    private static void coletar(Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof JTextComponent t && t.getText() != null) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof Selo s) {
            sb.append(s.texto()).append('\n');
        } else if (c instanceof javax.swing.JComboBox<?> combo) {
            sb.append(combo.getSelectedItem()).append('\n');
        } else if (c instanceof AbstractButton b && b.getText() != null) {
            sb.append(b.getText()).append('\n');
        } else if (c instanceof JTable t) {
            for (int i = 0; i < t.getRowCount(); i++) {
                for (int j = 0; j < t.getColumnCount(); j++) {
                    Component celula = t.prepareRenderer(t.getCellRenderer(i, j), i, j); // o que a célula MOSTRA (o valor dela pode ser um objeto)
                    sb.append(celula instanceof JLabel l ? l.getText() : String.valueOf(t.getValueAt(i, j))).append(" | ");
                }
                sb.append('\n');
            }
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                coletar(f, sb);
            }
        }
    }

    /** Confere que a tela tem estes textos, nenhum "Carregando" e nada que não caiba na janela. */
    void conferir(String descricao, String... esperados) {
        Visual.renderizar(painel, LARGURA, ALTURA);
        String t = textos();
        for (String e : esperados) {
            assertTrue(t.contains(e), descricao + ": faltou \"" + e + "\" na tela:\n" + t);
        }
        assertFalse(t.contains("Carregando") && !t.contains("Carregando mais"), descricao + ": ainda carregando:\n" + t);
        assertFalse(t.contains("Lendo o Git") || t.contains("Lendo da Cloudflare"), descricao + ": ainda lendo:\n" + t);
        assertEquals(List.of(), Visual.naEdt(() -> SemTransbordo.achados(painel)), descricao + ": algo transborda");
        semDadoDoComputador(descricao, t);
    }

    /** Nada do computador de teste na imagem: caminho do Mac, nome do usuário, o endereço local do SSH embutido. */
    static void semDadoDoComputador(String descricao, String texto) {
        for (String proibido : new String[] {"/Users/", "/var/folders", "/private/", "/tmp/", "127.0.0.1", "localhost", ComputadorDeTeste.usuario(), ComputadorDeTeste.casa()}) {
            if (proibido != null && proibido.length() > 2) {
                assertFalse(texto.contains(proibido), descricao + ": a tela mostra \"" + proibido + "\":\n" + texto);
            }
        }
    }

    /** Grava as duas imagens da galeria (claro e escuro) com a moldura de janela do macOS: {@code build/galeria/<nome>-claro.png} e {@code -escuro.png}. */
    void fotografar(String nome) {
        Visual.galeriaNosDoisTemas(painel, LARGURA, ALTURA, nome, "Lab Desktop", MolduraDeJanela.Tipo.JANELA);
        assertTrue(Files.exists(Path.of("build", "galeria", nome + "-claro.png")), nome + "-claro.png");
        assertTrue(Files.exists(Path.of("build", "galeria", nome + "-escuro.png")), nome + "-escuro.png");
    }

    // ---------------------------------------------------------------------------------------------

    @Override
    public void close() throws java.io.IOException {
        if (encerrada) {
            return;
        }
        encerrada = true;
        Visual.naEdt(painel::encerrar);
        gerenciador.fechar().join();
        cenario.close();
        if (nuvem != null) {
            nuvem.close();
        }
        if (acoesAntes == null) {
            System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        } else {
            System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, acoesAntes);
        }
        Visual.tema(br.com.labdesktop.util.Preferencias.Tema.CLARO);
    }
}
