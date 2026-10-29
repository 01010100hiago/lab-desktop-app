package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.FerramentasFalsasDeTunel;
import br.com.labdesktop.teste.LancadorDeFerramentaFalsa;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.transporte.FerramentaExterna;
import br.com.labdesktop.transporte.GuiaDeInstalacao;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import br.com.labdesktop.transporte.RelatorioDeFerramentas;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.SecaoComoConectar.Modo;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComponent;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Como conectar" nos dois diálogos, com o cofre em arquivo temporário, o SSH embutido do repo e um túnel externo de MENTIRA (a ferramenta falsa, um processo Java do
 * teste): os três modos, cada um só com os campos dele, a validação ao digitar, persistir no cofre, o "Adicionar servidor" que testa a conexão pelo mesmo caminho, a conferência das
 * ferramentas e as telas sem rolagem. Nada de AWS, Cloudflare nem servidor real.
 */
@ExtendWith(VerificaEdt.class)
class ComoConectarNosDialogosTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final String INSTANCIA = "i-0123456789abcdef0";

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private LancadorDeFerramentaFalsa lancador;
    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);
    private final AvisosGravados avisos = new AvisosGravados();
    private final List<ServidorLab> concluidos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        agente.fora = true;
        agente.diagnostico = "ausente";
        ssh.comandoRemoto(agente.comandosPorSsh());
        lancador = new LancadorDeFerramentaFalsa(ssh.porta(), Map.of());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), ServicosLocais.padrao(), FerramentasFalsasDeTunel.abridor(lancador, registro));
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        registro.encerrarTodos();
        cofre.close();
        agente.close();
        ssh.close();
        Visual.tema(Tema.CLARO);
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private static RelatorioDeFerramentas relatorioComUmaAchadaEUmaNao() {
        return new RelatorioDeFerramentas(List.of(
            new RelatorioDeFerramentas.Item(FerramentaExterna.AWS, Optional.of("/opt/homebrew/bin/aws"), "aws-cli/2.17.0 Python/3.12 Darwin/25 exe/arm64", List.of()),
            new RelatorioDeFerramentas.Item(FerramentaExterna.PLUGIN_SSM, Optional.empty(), "", GuiaDeInstalacao.passos(FerramentaExterna.PLUGIN_SSM, SistemaOperacional.MAC)),
            new RelatorioDeFerramentas.Item(FerramentaExterna.CLOUDFLARED, Optional.of("/opt/homebrew/bin/cloudflared"), "cloudflared version 2024.10.0", List.of())));
    }

    private DialogoAdicionarServidor adicionar(SecaoComoConectar.Conferencia conferencia) {
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        return Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, false, avisos, concluidos::add,
            (servidor, endereco, resultado, aoDecidir) -> aoDecidir.accept(true), janela -> Optional.empty(),
            new CopiadorDeTexto(() -> new Clipboard("teste")), (lab, depois, soSsh) -> { }, null, conferencia));
    }

    private DialogoAdicionarServidor adicionar() {
        return adicionar(() -> relatorioComUmaAchadaEUmaNao());
    }

    private ServidorLab servidorNoCofre(Transporte transporte, List<Pin> pins) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
        cofre.adicionarChave(chave);
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), "nuvem", "192.0.2.5", 22, "ubuntu", chave.id(), 7000, pins, transporte, null, Estado2fa.DESLIGADO);
        cofre.adicionarServidor(cfg, null);
        return gerenciador.adicionar(cfg.id()); // sem iniciar: nenhuma conexão
    }

    private DialogoEditarServidor editar(ServidorLab lab, List<Dados> gravados, SecaoComoConectar.Conferencia conferencia) {
        return Visual.naEdt(() -> new DialogoEditarServidor(lab.config(), dados -> {
            gravados.add(dados);
            return gerenciador.atualizar(lab.id(), dados);
        }, avisos, null, new CopiadorDeTexto(() -> new Clipboard("teste")), conferencia));
    }

    private DialogoEditarServidor editar(ServidorLab lab) {
        return editar(lab, new ArrayList<>(), () -> relatorioComUmaAchadaEUmaNao());
    }

    private static void escrever(javax.swing.JTextField campo, String texto) {
        Visual.naEdt(() -> campo.setText(texto));
    }

    private static String textos(java.awt.Component c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof javax.swing.text.JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton b && b.getText() != null) {
            sb.append(b.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                sb.append(textos(f));
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // A seção
    // ---------------------------------------------------------------------------------------------

    @Test
    void osDoisDialogosTemOsTresModosEODiretoVemMarcado() throws Exception {
        DialogoAdicionarServidor add = adicionar();
        SecaoComoConectar s = Visual.naEdt(add::secaoComoConectar);
        assertEquals(Modo.DIRETO, Visual.naEdt(s::modo));
        assertEquals("Direto (SSH)", s.radioDireto().getText());
        assertEquals("AWS Session Manager (SSM)", s.radioSsm().getText());
        assertEquals("Cloudflare", s.radioCloudflare().getText());
        assertTrue(s.radioDireto().isSelected());
        assertTrue(Visual.naEdt(() -> textos(add.raiz())).contains("Como conectar"));

        ServidorLab lab = servidorNoCofre(Transporte.DIRETO, List.of());
        SecaoComoConectar e = Visual.naEdt(() -> editar(lab).secaoComoConectar());
        assertEquals(Modo.DIRETO, Visual.naEdt(e::modo), "um servidor que já existe abre como direto: nada foi migrado");
        assertEquals("192.0.2.5", e.campoDoHost().getText());
        assertEquals("22", e.campoDaPorta().getText());
    }

    @Test
    void cadaModoMostraSoOsCamposDele() {
        SecaoComoConectar s = Visual.naEdt(() -> adicionar().secaoComoConectar());
        assertEquals(List.of("campo-host", "campo-porta"), Visual.naEdt(s::camposVisiveis));
        assertEquals("", Visual.naEdt(s::textoDaExplicacao));

        Visual.naEdt(() -> s.radioSsm().doClick());
        assertEquals(Modo.SSM, Visual.naEdt(s::modo));
        assertEquals(List.of("campo-instancia", "campo-regiao", "campo-perfil", "campo-porta-ssh-da-instancia"), Visual.naEdt(s::camposVisiveis));
        assertEquals(SecaoComoConectar.DICA_DO_SSM, Visual.naEdt(s::textoDaExplicacao));

        Visual.naEdt(() -> s.radioCloudflare().doClick());
        assertEquals(List.of("campo-hostname-cloudflare"), Visual.naEdt(s::camposVisiveis));
        assertEquals(SecaoComoConectar.DICA_DA_CLOUDFLARE, Visual.naEdt(s::textoDaExplicacao));

        Visual.naEdt(() -> s.radioDireto().doClick());
        assertEquals(List.of("campo-host", "campo-porta"), Visual.naEdt(s::camposVisiveis));
    }

    @Test
    void validaAoDigitarComMensagemSimplesSoDoCampoPreenchidoEErrado() {
        SecaoComoConectar s = Visual.naEdt(() -> adicionar().secaoComoConectar());
        Visual.naEdt(() -> s.radioSsm().doClick());
        assertEquals("", Visual.naEdt(s::textoDaMensagem), "campos ainda vazios: nada de erro antes de o usuário digitar");

        escrever(s.campoDaInstancia(), "i-0abc;rm -rf");
        assertTrue(Visual.naEdt(s::textoDaMensagem).startsWith("O ID da instância começa com i-"), Visual.naEdt(s::textoDaMensagem));
        escrever(s.campoDaInstancia(), INSTANCIA);
        assertEquals("", Visual.naEdt(s::textoDaMensagem));

        escrever(s.campoDaRegiao(), "US EAST 1");
        assertTrue(Visual.naEdt(s::textoDaMensagem).startsWith("A região parece com us-east-1"));
        escrever(s.campoDaRegiao(), "us-east-1");
        assertEquals("", Visual.naEdt(s::textoDaMensagem));

        escrever(s.campoDoPerfil(), "-oProxyCommand=x");
        assertTrue(Visual.naEdt(s::textoDaMensagem).contains("não começa com hífen"), Visual.naEdt(s::textoDaMensagem));
        escrever(s.campoDoPerfil(), "meu-perfil");
        assertEquals("", Visual.naEdt(s::textoDaMensagem));

        escrever(s.campoDaPortaSsh(), "abc");
        assertEquals("A porta SSH da instância precisa ser um número.", Visual.naEdt(s::textoDaMensagem));
        escrever(s.campoDaPortaSsh(), "70000");
        assertEquals("A porta SSH da instância vai de 1 a 65535.", Visual.naEdt(s::textoDaMensagem));
        escrever(s.campoDaPortaSsh(), "22");
        assertEquals("", Visual.naEdt(s::textoDaMensagem));
        assertEquals(Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 22), Visual.naEdt(s::transporte));

        Visual.naEdt(() -> s.radioCloudflare().doClick());
        escrever(s.campoDoHostname(), "https://ssh.example.com/x");
        assertTrue(Visual.naEdt(s::textoDaMensagem).startsWith("Digite só o nome"), Visual.naEdt(s::textoDaMensagem));
        escrever(s.campoDoHostname(), "ssh.example.com");
        assertEquals("", Visual.naEdt(s::textoDaMensagem));
        assertEquals(Transporte.cloudflared("ssh.example.com"), Visual.naEdt(s::transporte));
    }

    @Test
    void continuarSemPreencherOModoSemPortaAbertaCobraOQueFaltaEFicaNoPasso1() {
        DialogoAdicionarServidor d = adicionar();
        SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
        Visual.naEdt(() -> {
            d.campoDoNome().setText("nuvem");
            d.campoDoUsuario().setText("ubuntu");
            s.radioSsm().doClick();
            d.botaoContinuar().doClick();
        });
        assertEquals("dados", Visual.naEdt(d::paginaAtual));
        assertTrue(Visual.naEdt(() -> textos(d.raiz())).contains("Digite o ID da instância"));

        Visual.naEdt(() -> {
            s.radioCloudflare().doClick();
            d.botaoContinuar().doClick();
        });
        assertEquals("dados", Visual.naEdt(d::paginaAtual));
        assertTrue(Visual.naEdt(() -> textos(d.raiz())).contains("Digite o endereço da Cloudflare"));
    }

    @Test
    void nosModosSemPortaAbertaOEnderecoEAPortaDoSshNaoSaoPedidosNemValidados() {
        DialogoAdicionarServidor d = adicionar();
        SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
        Visual.naEdt(() -> {
            d.campoDoNome().setText("nuvem");
            d.campoDoUsuario().setText("ubuntu");
            d.campoDaPortaDoAgente().setText("7000");
            d.campoDoHost().setText(""); // vazio e inválido no modo direto, irrelevante aqui
            d.campoDaPorta().setText("não é número");
            s.radioSsm().doClick();
            s.campoDaInstancia().setText(INSTANCIA);
            s.campoDaRegiao().setText("us-east-1");
            d.botaoContinuar().doClick();
        });
        assertEquals("chave", Visual.naEdt(d::paginaAtual), "foi para o passo da chave sem pedir endereço nem porta");
    }

    // ---------------------------------------------------------------------------------------------
    // Adicionar servidor: testa a conexão pelo mesmo caminho
    // ---------------------------------------------------------------------------------------------

    @Test
    void adicionarPelaCloudflareSondaConectaEGravaOTransporteEOPin() throws Exception {
        DialogoAdicionarServidor d = adicionar();
        SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
        Visual.naEdt(() -> {
            d.campoDoNome().setText("nuvem");
            d.campoDoUsuario().setText("labuser");
            d.campoDaPortaDoAgente().setText(String.valueOf(agente.porta()));
            s.radioCloudflare().doClick();
            s.campoDoHostname().setText("ssh.example.com");
            d.botaoContinuar().doClick();
        });
        Espera.ate(PRAZO, "chave", () -> Visual.naEdt(() -> d.chaveEscolhida() != null && d.mostraACaixaDaChave()));
        InfoChave chave = Visual.naEdt(d::chaveEscolhida);
        PublicKey publica = PublicKeyEntry.parsePublicKeyEntry(chave.publica()).resolvePublicKey(null, Collections.emptyMap(), PublicKeyEntryResolver.FAILING);
        ssh.aceitar(publica);
        Visual.naEdt(() -> d.botaoTestar().doClick());
        Espera.ate(PRAZO, () -> "pergunta do agente (conexão=" + Visual.naEdt(d::estadoDaConexao) + ", msg=" + Visual.naEdt(d::mensagemDaConexao) + ")",
            () -> Visual.naEdt(d::perguntandoDoAgente));

        ServidorLab lab = Visual.naEdt(d::servidorCadastrado);
        ConfigServidor c = cofre.servidor(lab.id()).orElseThrow();
        assertEquals(Transporte.cloudflared("ssh.example.com"), c.transporte());
        assertEquals(ConfigServidor.HOST_DO_TRANSPORTE, c.host());
        assertEquals(List.of(ssh.pin()), c.pins(), "o pin é o do SSH atrás do túnel, confirmado pelo usuário");
        assertTrue(lab.estado().semPortaAberta());
        assertTrue(lancador.lancados.size() >= 2, "a sondagem e a conexão passaram pelo túnel: " + lancador.lancados.size());
        for (var l : lancador.lancados) {
            assertEquals("/ferramentas/cloudflared", l.argumentosDoApp().get(0));
        }
    }

    @Test
    void adicionarPeloSsmDizAFraseDoTunelQuandoOProgramaNaoSobe() throws Exception {
        LancadorDeFerramentaFalsa quebrado = new LancadorDeFerramentaFalsa(ssh.porta(),
            Map.of("LABTEST_SAIR_LOGO_TEXTO", "The SSO session associated with this profile has expired or is otherwise invalid."));
        GerenciadorServidores g = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), ServicosLocais.padrao(), FerramentasFalsasDeTunel.abridor(quebrado, registro));
        try {
            AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, g, ConfigSsh.rapida());
            DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, false, avisos, concluidos::add,
                (servidor, endereco, resultado, aoDecidir) -> aoDecidir.accept(true), janela -> Optional.empty(), new CopiadorDeTexto(() -> new Clipboard("teste")),
                (lab, depois, soSsh) -> { }, null, () -> relatorioComUmaAchadaEUmaNao()));
            SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
            Visual.naEdt(() -> {
                d.campoDoNome().setText("nuvem");
                d.campoDoUsuario().setText("ubuntu");
                s.radioSsm().doClick();
                s.campoDaInstancia().setText(INSTANCIA);
                s.campoDaRegiao().setText("us-east-1");
                s.campoDoPerfil().setText("meu-perfil");
                d.botaoContinuar().doClick();
            });
            Espera.ate(PRAZO, "chave", () -> Visual.naEdt(() -> d.chaveEscolhida() != null && d.mostraACaixaDaChave()));
            Visual.naEdt(() -> d.botaoTestar().doClick());
            Espera.ate(PRAZO, "o erro do túnel", () -> avisos.todos.stream().anyMatch(a -> a.texto().contains("Seu login da AWS expirou")) || Visual.naEdt(() -> textos(d.raiz())).contains("Seu login da AWS expirou"));
            String tudo = Visual.naEdt(() -> textos(d.raiz())) + avisos.todos;
            assertTrue(tudo.contains("Seu login da AWS expirou. No terminal rode: aws sso login --profile meu-perfil e tente de novo."), tudo);
            String frase = tudo.lines().filter(l -> l.contains("Seu login da AWS expirou")).findFirst().orElseThrow();
            assertFalse(frase.contains("127.0.0.1") || frase.contains("localPortNumber"), "nenhuma porta local aleatória na frase do usuário: " + frase);
        } finally {
            g.fechar().join();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Editar servidor: persistir com o cofre temporário
    // ---------------------------------------------------------------------------------------------

    @Test
    void editarDoDiretoParaSsmPersisteMantemOPinEOAvisoDizQueOModoNaoApagaAConfianca() throws Exception {
        Pin pin = ssh.pin();
        ServidorLab lab = servidorNoCofre(Transporte.DIRETO, List.of(pin));
        List<Dados> gravados = new ArrayList<>();
        DialogoEditarServidor d = editar(lab, gravados, () -> relatorioComUmaAchadaEUmaNao());
        SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
        assertFalse(Visual.naEdt(d::mostraOAvisoDaImpressao));

        Visual.naEdt(() -> {
            s.radioSsm().doClick();
            s.campoDaInstancia().setText(INSTANCIA);
            s.campoDaRegiao().setText("us-east-1");
        });
        assertTrue(Visual.naEdt(() -> d.botaoSalvar().isEnabled()), "trocar o modo é uma mudança");
        assertTrue(Visual.naEdt(d::mostraOAvisoDaImpressao));
        assertEquals(DialogoEditarServidor.AVISO_DA_TROCA_DE_MODO, Visual.naEdt(d::textoDoAviso));

        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Espera.ate(PRAZO, "gravou", () -> cofre.servidor(lab.id()).orElseThrow().transporte().tipo() == Transporte.Tipo.SSM);
        ConfigServidor c = cofre.servidor(lab.id()).orElseThrow();
        assertEquals(Transporte.ssm(INSTANCIA, "us-east-1", null, 22), c.transporte());
        assertEquals(List.of(pin), c.pins(), "o pin ficou: trocar o modo não pede para confirmar o servidor de novo");
        assertEquals(Transporte.ssm(INSTANCIA, "us-east-1", null, 22), gravados.get(0).transporte());
        assertTrue(lab.estado().semPortaAberta(), "o estado do servidor já fala do novo modo");
    }

    @Test
    void editarOutraInstanciaApagaAImpressaoEAvisaEmQuantoSeDigita() throws Exception {
        ServidorLab lab = servidorNoCofre(Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 22), List.of(ssh.pin()));
        DialogoEditarServidor d = editar(lab);
        SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
        assertEquals(Modo.SSM, Visual.naEdt(s::modo));
        assertEquals(INSTANCIA, s.campoDaInstancia().getText());
        assertEquals("us-east-1", s.campoDaRegiao().getText());
        assertEquals("meu-perfil", s.campoDoPerfil().getText());
        assertEquals("22", s.campoDaPortaSsh().getText());
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));

        escrever(s.campoDoPerfil(), "outro-perfil");
        assertTrue(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
        assertFalse(Visual.naEdt(d::mostraOAvisoDaImpressao), "mudar só o perfil da AWS não apaga a impressão");

        escrever(s.campoDaInstancia(), "i-0fff1234def567890");
        assertTrue(Visual.naEdt(d::mostraOAvisoDaImpressao));
        assertEquals(DialogoEditarServidor.AVISO_DA_IMPRESSAO_DO_DESTINO, Visual.naEdt(d::textoDoAviso));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Espera.ate(PRAZO, "gravou", () -> cofre.servidor(lab.id()).orElseThrow().pins().isEmpty());
        assertEquals("i-0fff1234def567890", cofre.servidor(lab.id()).orElseThrow().transporte().instanciaId());
        Espera.ate(PRAZO, "avisou", () -> avisos.disse("atencao", "confirme a impressão digital"));
    }

    @Test
    void editarDoSsmDeVoltaAoDiretoPedeEnderecoEPortaEPersiste() throws Exception {
        Pin pin = ssh.pin();
        ServidorLab lab = servidorNoCofre(Transporte.ssm(INSTANCIA, "us-east-1", null, 22), List.of(pin));
        DialogoEditarServidor d = editar(lab);
        SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
        Visual.naEdt(() -> s.radioDireto().doClick());
        Visual.naEdt(() -> d.botaoSalvar().doClick()); // sem endereço: não grava
        assertTrue(Visual.naEdt(() -> d.textoDoErro()).contains("endereço") || Visual.naEdt(() -> d.textoDoErro()).contains("porta"), Visual.naEdt(() -> d.textoDoErro()));
        assertEquals(Transporte.Tipo.SSM, cofre.servidor(lab.id()).orElseThrow().transporte().tipo());

        escrever(s.campoDoHost(), "192.0.2.9");
        escrever(s.campoDaPorta(), "2222");
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Espera.ate(PRAZO, "gravou", () -> cofre.servidor(lab.id()).orElseThrow().transporte().tipo() == Transporte.Tipo.DIRETO);
        ConfigServidor c = cofre.servidor(lab.id()).orElseThrow();
        assertEquals("192.0.2.9", c.host());
        assertEquals(2222, c.porta());
        assertEquals(List.of(pin), c.pins());
    }

    @Test
    void editarServidorDiretoSemMexerNaSecaoContinuaIgual() throws Exception {
        ServidorLab lab = servidorNoCofre(Transporte.DIRETO, List.of(ssh.pin()));
        List<Dados> gravados = new ArrayList<>();
        DialogoEditarServidor d = editar(lab, gravados, () -> relatorioComUmaAchadaEUmaNao());
        Visual.naEdt(() -> d.campoDoNome().setText("outro nome"));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Espera.ate(PRAZO, "gravou", () -> cofre.servidor(lab.id()).orElseThrow().nome().equals("outro nome"));
        ConfigServidor c = cofre.servidor(lab.id()).orElseThrow();
        assertEquals(Transporte.DIRETO, c.transporte());
        assertEquals("192.0.2.5", c.host());
        assertEquals(List.of(ssh.pin()), c.pins());
    }

    // ---------------------------------------------------------------------------------------------
    // Conferir as ferramentas
    // ---------------------------------------------------------------------------------------------

    @Test
    void conferirAsFerramentasRodaForaDaEdtEEntregaORelatorioNosDoisDialogos() throws Exception {
        AtomicBoolean naEdt = new AtomicBoolean(true);
        SecaoComoConectar.Conferencia conferencia = () -> {
            naEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
            return relatorioComUmaAchadaEUmaNao();
        };
        List<RelatorioDeFerramentas> recebidos = new CopyOnWriteArrayList<>();
        DialogoAdicionarServidor add = adicionar(conferencia);
        Visual.naEdt(() -> add.usarMostradorDasFerramentas(recebidos::add));
        Visual.naEdt(() -> add.secaoComoConectar().botaoConferir().doClick());
        Espera.ate(PRAZO, "relatório do Adicionar", () -> recebidos.size() == 1);
        assertFalse(naEdt.get(), "a conferência roda numa thread de fundo");
        assertEquals(3, recebidos.get(0).itens().size());

        ServidorLab lab = servidorNoCofre(Transporte.DIRETO, List.of());
        DialogoEditarServidor ed = editar(lab, new ArrayList<>(), conferencia);
        Visual.naEdt(() -> ed.usarMostradorDasFerramentas(recebidos::add));
        Visual.naEdt(() -> ed.secaoComoConectar().botaoConferir().doClick());
        Espera.ate(PRAZO, "relatório do Editar", () -> recebidos.size() == 2);
        assertEquals("Conferir as ferramentas neste computador", ed.secaoComoConectar().botaoConferir().getText());
    }

    @Test
    void conferirQueFalhaMostraOErroEDevolveOBotao() throws Exception {
        DialogoAdicionarServidor add = adicionar(() -> {
            throw new java.io.IOException("não deu para procurar");
        });
        Visual.naEdt(() -> add.secaoComoConectar().botaoConferir().doClick());
        Espera.ate(PRAZO, "erro", () -> avisos.todos.stream().anyMatch(a -> a.tipo().equals("erro")));
        assertTrue(Visual.naEdt(() -> add.secaoComoConectar().botaoConferir().isEnabled()));
    }

    @Test
    void oDialogoDasFerramentasMostraAchadaENaoAchadaComOPassoCopiavel() {
        Clipboard area = new Clipboard("teste");
        DialogoDasFerramentas d = Visual.naEdt(() -> new DialogoDasFerramentas(relatorioComUmaAchadaEUmaNao(), new CopiadorDeTexto(() -> area), avisos));
        assertEquals(List.of("encontrada em /opt/homebrew/bin/aws (aws-cli/2.17.0 Python/3.12 Darwin/25 exe/arm64)", "não encontrada",
            "encontrada em /opt/homebrew/bin/cloudflared (cloudflared version 2024.10.0)"), Visual.naEdt(d::textosDasSituacoes));
        assertEquals(List.of("brew install --cask session-manager-plugin"), Visual.naEdt(d::textosDosPassos));
        assertEquals(1, d.botoesDeCopiar().size(), "só o que falta tem passo para copiar");
        Visual.naEdt(() -> d.botoesDeCopiar().get(0).doClick());
        assertEquals("brew install --cask session-manager-plugin", Visual.naEdt(() -> {
            try {
                return (String) area.getContents(null).getTransferData(DataFlavor.stringFlavor);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        String tudo = Visual.naEdt(() -> textos(d.raiz()));
        assertTrue(tudo.contains("O app só confere se eles estão aqui: não instala nada"), tudo);
        assertTrue(tudo.contains("usado pelo modo AWS Session Manager (SSM)") && tudo.contains("usado pelo modo Cloudflare"), tudo);
    }

    @Test
    void asInstrucoesDeCadaSistemaAparecemNoDialogo() {
        for (SistemaOperacional so : SistemaOperacional.values()) {
            RelatorioDeFerramentas r = new RelatorioDeFerramentas(List.of(
                new RelatorioDeFerramentas.Item(FerramentaExterna.AWS, Optional.empty(), "", GuiaDeInstalacao.passos(FerramentaExterna.AWS, so)),
                new RelatorioDeFerramentas.Item(FerramentaExterna.PLUGIN_SSM, Optional.empty(), "", GuiaDeInstalacao.passos(FerramentaExterna.PLUGIN_SSM, so)),
                new RelatorioDeFerramentas.Item(FerramentaExterna.CLOUDFLARED, Optional.empty(), "", GuiaDeInstalacao.passos(FerramentaExterna.CLOUDFLARED, so))));
            DialogoDasFerramentas d = Visual.naEdt(() -> new DialogoDasFerramentas(r, new CopiadorDeTexto(() -> new Clipboard("teste")), avisos));
            assertEquals(3, Visual.naEdt(d::textosDosPassos).size(), so.name());
            assertEquals(List.of("não encontrada", "não encontrada", "não encontrada"), Visual.naEdt(d::textosDasSituacoes));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sem rolagem, sem transbordo, e os PNGs
    // ---------------------------------------------------------------------------------------------

    private static final int LARGURA_DO_DIALOGO = 560;

    /** A largura em que a janela abriria (o que o conteúdo pede), nunca menos que os 560 de sempre. */
    private static int larguraNatural(JComponent raiz) {
        Visual.alturaPreferida(raiz, LARGURA_DO_DIALOGO);
        return Math.max(LARGURA_DO_DIALOGO, Visual.naEdt(() -> raiz.getPreferredSize().width));
    }

    private void semTransbordoEPng(JComponent raiz, String nome) {
        int largura = larguraNatural(raiz);
        assertTrue(largura <= 700, nome + ": o diálogo pede " + largura + " px de largura");
        int altura = Visual.alturaPreferida(raiz, largura);
        List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(raiz));
        assertTrue(achados.isEmpty(), nome + ": " + achados);
        Visual.salvar(Visual.renderizar(raiz, largura, altura), nome + "-claro");
        boolean galeria = nome.equals("dialogo-adicionar-servidor-ssm") || nome.equals("dialogo-adicionar-servidor-cloudflare");
        if (galeria) { // galeria do README
            Visual.salvarNaGaleria(Visual.renderizar(raiz, largura, altura), nome.replace("dialogo-adicionar-servidor-", "como-conectar-") + "-claro", "Adicionar servidor", false,
                MolduraDeJanela.Tipo.DIALOGO);
        }
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(raiz));
        Visual.salvar(Visual.renderizar(raiz, largura, altura), nome + "-escuro");
        if (galeria) {
            Visual.salvarNaGaleria(Visual.renderizar(raiz, largura, altura), nome.replace("dialogo-adicionar-servidor-", "como-conectar-") + "-escuro", "Adicionar servidor", true,
                MolduraDeJanela.Tipo.DIALOGO);
        }
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(raiz));
    }

    @Test
    void oPasso1DoAdicionarComSsmECloudflareCabeSemRolagemEGravaOsPngs() throws Exception {
        DialogoAdicionarServidor d = adicionar();
        SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
        Visual.naEdt(() -> {
            d.campoDoNome().setText("nuvem");
            d.campoDoUsuario().setText("ubuntu");
            s.radioSsm().doClick();
            s.campoDaInstancia().setText(INSTANCIA);
            s.campoDaRegiao().setText("us-east-1");
            s.campoDoPerfil().setText("meu-perfil");
        });
        int alturaSsm = Visual.alturaPreferida(d.raiz(), LARGURA_DO_DIALOGO);
        assertTrue(alturaSsm <= 560, "o passo 1 com SSM pede " + alturaSsm + " px");
        assertFalse(Visual.naEdt(() -> d.mioloRolavel().rola()), "o passo 1 com SSM não rola");
        semTransbordoEPng(d.raiz(), "dialogo-adicionar-servidor-ssm");

        Visual.naEdt(() -> {
            s.radioCloudflare().doClick();
            s.campoDoHostname().setText("ssh.example.com");
        });
        int alturaCloud = Visual.alturaPreferida(d.raiz(), LARGURA_DO_DIALOGO);
        assertTrue(alturaCloud <= 560, "o passo 1 com Cloudflare pede " + alturaCloud + " px");
        assertFalse(Visual.naEdt(() -> d.mioloRolavel().rola()), "o passo 1 com Cloudflare não rola");
        semTransbordoEPng(d.raiz(), "dialogo-adicionar-servidor-cloudflare");

        // com um erro de digitação à vista (a linha a mais) ainda cabe sem rolar
        Visual.naEdt(() -> s.campoDoHostname().setText("https://ssh.example.com"));
        assertTrue(Visual.alturaPreferida(d.raiz(), LARGURA_DO_DIALOGO) <= 600);
        assertFalse(Visual.naEdt(() -> d.mioloRolavel().rola()));
    }

    @Test
    void oEditarComSsmCabeSemRolagemEGravaOPng() throws Exception {
        // sem rolagem numa tela de 900 px de altura (o diálogo cresce até 80% dela), e a altura inteira cabe numa janela de 700 px (barra de título inclusa)
        Dialogo.definirAlturaDaTelaParaTeste(900);
        try {
            for (Transporte t : new Transporte[] {Transporte.DIRETO, Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 22), Transporte.cloudflared("ssh.example.com")}) {
                ServidorLab lab = servidorNoCofre(t, List.of(ssh.pin()));
                DialogoEditarServidor d = Visual.naEdt(() -> new DialogoEditarServidor(lab.config(), dados -> null, avisos,
                    new DialogoEditarServidor.Acesso("lab", "SHA256:5MGLihSpPv/h6+y5NnTZfr2nnOaG8vJ9pEy1z3+jIQg", "ssh-ed25519 AAAAexemplo lab", () -> { }),
                    new CopiadorDeTexto(() -> new Clipboard("teste")), () -> relatorioComUmaAchadaEUmaNao()));
                int altura = Visual.alturaPreferida(d.raiz(), LARGURA_DO_DIALOGO);
                assertFalse(Visual.naEdt(() -> d.mioloRolavel().rola()), "o Editar (" + t.tipo() + ") não rola numa tela de 900 px");
                assertTrue(altura + 32 <= 700, "o Editar (" + t.tipo() + ") pede " + altura + " px mais a barra de título: cabe numa janela de 700");
                if (t.tipo() == Transporte.Tipo.SSM) {
                    semTransbordoEPng(d.raiz(), "dialogo-editar-servidor-ssm");
                }
            }
        } finally {
            Dialogo.definirAlturaDaTelaParaTeste(0);
        }
        // e o limite de altura do Editar, numa tela de 800 px (a de um notebook comum): o diálogo não passa de 608
        ServidorLab lab = servidorNoCofre(Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 22), List.of());
        DialogoEditarServidor d = editar(lab);
        assertTrue(Visual.alturaPreferida(d.raiz(), LARGURA_DO_DIALOGO) <= 608);
    }

    @Test
    void asFerramentasGravamOPng() throws Exception {
        DialogoDasFerramentas d = Visual.naEdt(() -> new DialogoDasFerramentas(relatorioComUmaAchadaEUmaNao(), new CopiadorDeTexto(() -> new Clipboard("teste")), avisos));
        semTransbordoEPng(d.raiz(), "dialogo-ferramentas-do-computador");
        assertNotNull(d.botaoFechar());
    }
}
