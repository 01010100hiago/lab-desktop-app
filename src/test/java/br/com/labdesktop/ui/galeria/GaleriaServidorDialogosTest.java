package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.acesso.InstaladorDeChave;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.InfoChave.Origem;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.GerenciadorServidores.ResultadoDaEdicao;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.transporte.FerramentaExterna;
import br.com.labdesktop.transporte.GuiaDeInstalacao;
import br.com.labdesktop.transporte.RelatorioDeFerramentas;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarServidor;
import br.com.labdesktop.ui.dialogos.DialogoConfiarHost;
import br.com.labdesktop.ui.dialogos.DialogoDasFerramentas;
import br.com.labdesktop.ui.dialogos.DialogoEditarServidor;
import br.com.labdesktop.ui.dialogos.DialogoInstalarChave;
import br.com.labdesktop.ui.dialogos.DialogoRemoverServidor;
import br.com.labdesktop.ui.dialogos.SecaoComoConectar;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Imagens da galeria do README dos diálogos do servidor: "Adicionar servidor" (passo dos dados com "Como conectar" Direto, o passo da senha), "Confiar no servidor", "Instalar a chave com a
 * senha", "Editar servidor", "Remover servidor" e a conferência das ferramentas do computador (AWS, plugin do Session Manager e cloudflared). Os diálogos são os de verdade, com um cofre
 * em pasta temporária e dados inventados (servidor-demo, 192.0.2.x, labuser, i-0123456789abcdef0, ssh.example.com); nenhuma conexão é feita.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaServidorDialogosTest {

    private static final char[] SENHA_MESTRA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final String INSTANCIA = "i-0123456789abcdef0";
    private static final String IMPRESSAO_DA_CHAVE = "SHA256:5MGLihSpPv/h6+y5NnTZfr2nnOaG8vJ9pEy1z3+jIQg";
    private static final String CHAVE_PUBLICA = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExemploDeChavePublicaParaTeste ana@notebook-demo";

    @TempDir
    Path tmp;

    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("galeria");

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA_MESTRA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        Visual.tema(Tema.CLARO);
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private static RelatorioDeFerramentas.Item achada(FerramentaExterna f, String caminho, String versao) {
        return new RelatorioDeFerramentas.Item(f, Optional.of(caminho), versao, List.of());
    }

    private static RelatorioDeFerramentas.Item ausente(FerramentaExterna f) {
        return new RelatorioDeFerramentas.Item(f, Optional.empty(), "", GuiaDeInstalacao.passos(f, SistemaOperacional.MAC));
    }

    private static RelatorioDeFerramentas relatorioDaAws() {
        return new RelatorioDeFerramentas(List.of(achada(FerramentaExterna.AWS, "/opt/homebrew/bin/aws", "aws-cli/2.17.0 Python/3.12 Darwin/25 exe/arm64"), ausente(FerramentaExterna.PLUGIN_SSM)));
    }

    private static RelatorioDeFerramentas relatorioDaCloudflare() {
        return new RelatorioDeFerramentas(List.of(achada(FerramentaExterna.CLOUDFLARED, "/opt/homebrew/bin/cloudflared", "cloudflared version 2024.10.0")));
    }

    private DialogoAdicionarServidor adicionar(boolean primeiro) {
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        Visual.tema(Tema.CLARO);
        return Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, primeiro, avisos, lab -> { },
            (servidor, endereco, resultado, aoDecidir) -> aoDecidir.accept(true), janela -> Optional.empty(), new CopiadorDeTexto(() -> area),
            (lab, depois, soSsh) -> { }, null, GaleriaServidorDialogosTest::relatorioDaAws));
    }

    private static void preencher(DialogoAdicionarServidor d, String nome, String host, String usuario) {
        Visual.naEdt(() -> {
            d.campoDoNome().setText(nome);
            d.campoDoHost().setText(host);
            d.campoDaPorta().setText("22");
            d.campoDoUsuario().setText(usuario);
            d.campoDaPortaDoAgente().setText("7000");
        });
    }

    private static ConfigServidor config(boolean protegido, Transporte transporte, String host) {
        return new ConfigServidor(ConfigServidor.novoId(), "servidor-demo", host, 22, "labuser", "chave-demo", 7000,
            List.of(new Pin("ssh-ed25519", "SHA256:mH3s1p2VwY+BAVOJ7MAG7JIZttP/NSiO9T4w4F165f0")), transporte, null, Estado2fa.DESLIGADO, protegido, false);
    }

    private DialogoEditarServidor editar(ConfigServidor cfg) {
        Visual.tema(Tema.CLARO);
        return Visual.naEdt(() -> new DialogoEditarServidor(cfg, dados -> new ResultadoDaEdicao(cfg, false, false), avisos,
            new DialogoEditarServidor.Acesso("chave-demo", IMPRESSAO_DA_CHAVE, CHAVE_PUBLICA, cfg.protegido() ? null : () -> { }), new CopiadorDeTexto(() -> area), GaleriaServidorDialogosTest::relatorioDaAws));
    }

    // ---------------------------------------------------------------------------------------------
    // Adicionar servidor
    // ---------------------------------------------------------------------------------------------

    @Test
    void adicionarServidorNoPassoDosDadosEComoConectarDireto() {
        DialogoAdicionarServidor d = adicionar(true);
        preencher(d, "servidor-demo", "192.0.2.10", "labuser");
        SecaoComoConectar s = Visual.naEdt(d::secaoComoConectar);
        assertEquals(SecaoComoConectar.Modo.DIRETO, Visual.naEdt(s::modo));
        assertEquals("dados", Visual.naEdt(d::paginaAtual));
        ImagemDeDialogo.gerar(d, "servidor-adicionar-passo-1", "Como conectar", "Direto (SSH)", "AWS Session Manager (SSM)", "Cloudflare", "servidor-demo", "192.0.2.10", "labuser");

        // o mesmo diálogo, para o "Como conectar" Direto de outro servidor, com o endereço e a conta de exemplo
        DialogoAdicionarServidor outro = adicionar(false);
        preencher(outro, "vps-1", "198.51.100.7", "admin");
        ImagemDeDialogo.gerar(outro, "como-conectar-direto", "Como conectar", "Direto (SSH)", "vps-1", "198.51.100.7", "admin");
    }

    @Test
    void adicionarServidorNoPassoDaPrimeiraConexaoComSenha() {
        DialogoAdicionarServidor d = adicionar(true);
        preencher(d, "servidor-demo", "192.0.2.10", "labuser");
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        Espera.ate(PRAZO, () -> "passo da chave (erro='" + Visual.naEdt(d::textoDoErro) + "')", () -> "chave".equals(Visual.naEdt(d::paginaAtual)) && Visual.naEdt(() -> d.chaveEscolhida() != null));
        assertEquals("Senha do usuário labuser no servidor (usada só agora, não fica guardada)", Visual.naEdt(d::rotuloDoCampoDaSenha));
        Visual.naEdt(() -> d.campoDaSenhaDoServidor().setText("exemplo-nao-e-uma-senha")); // o campo só pinta bolinhas
        assertTrue(Visual.naEdt(() -> d.botaoEntrarComSenha().isVisible() && d.botaoEntrarComSenha().isEnabled()));
        ImagemDeDialogo.gerar(d, "servidor-adicionar-senha", "Senha do usuário labuser no servidor (usada só agora, não fica guardada)", "••••");
        assertEquals(1, cofre.chaves().size(), "a chave nasceu sozinha ao avançar");
        assertTrue(cofre.servidores().isEmpty(), "nada foi gravado no cofre além da chave: nenhuma conexão foi feita");
    }

    @Test
    void confiarNoServidorMostraAImpressaoDigitalParaConferir() throws Exception {
        KeyPair par = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        SondagemDeHost.Resultado sonda = new SondagemDeHost.Resultado("ssh-ed25519", KeyUtils.getFingerPrint(par.getPublic()), par.getPublic(),
            "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub");
        Visual.tema(Tema.CLARO);
        DialogoConfiarHost d = Visual.naEdt(() -> new DialogoConfiarHost("servidor-demo", "192.0.2.10:22", sonda, avisos, x -> { }, new CopiadorDeTexto(() -> area)));
        assertFalse(Visual.naEdt(() -> d.botaoConfiar().isEnabled()), "só confia depois de marcar que conferiu");
        ImagemDeDialogo.gerar(d, "servidor-confiar-impressao", "Confiar neste servidor?", "Impressão digital do servidor", "SHA256:", "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",
            "servidor-demo (192.0.2.10:22)");
    }

    // ---------------------------------------------------------------------------------------------
    // Instalar a chave, editar e remover
    // ---------------------------------------------------------------------------------------------

    @Test
    void instalarAChaveComASenhaDoServidor() {
        DialogoInstalarChave.Alvo alvo = new DialogoInstalarChave.Alvo("servidor-demo", "labuser", "192.0.2.10", 22,
            List.of(new Pin("ssh-ed25519", "SHA256:mH3s1p2VwY+BAVOJ7MAG7JIZttP/NSiO9T4w4F165f0")), "chave-demo", IMPRESSAO_DA_CHAVE, CHAVE_PUBLICA);
        List<char[]> senhas = new ArrayList<>();
        Visual.tema(Tema.CLARO);
        DialogoInstalarChave d = Visual.naEdt(() -> new DialogoInstalarChave(alvo, (senha, progresso) -> {
            senhas.add(senha);
            return InstaladorDeChave.Resultado.ADICIONADA;
        }, new CopiadorDeTexto(() -> area), avisos));
        Visual.naEdt(() -> d.campoDaSenha().setText("exemplo-nao-e-uma-senha"));
        assertEquals("Instalar a chave com a senha", d.titulo());
        ImagemDeDialogo.gerar(d, "servidor-instalar-chave", "labuser@192.0.2.10:22", "Senha do usuário labuser no servidor (usada só agora, não fica guardada)", "••••");
        assertTrue(senhas.isEmpty(), "a galeria nunca manda a senha a ninguém");
    }

    @Test
    void editarServidorComACaixaServidorProtegido() {
        DialogoEditarServidor d = editar(config(false, Transporte.DIRETO, "192.0.2.10"));
        assertEquals("Servidor protegido", Visual.naEdt(() -> d.caixaProtegido().getText()));
        assertFalse(Visual.naEdt(() -> d.caixaProtegido().isSelected()));
        ImagemDeDialogo.gerar(d, "servidor-editar", "Editar servidor", "Servidor protegido", "servidor-demo", "192.0.2.10", "labuser", "Instalar a chave com a senha");

        DialogoEditarServidor protegido = editar(config(true, Transporte.DIRETO, "192.0.2.10"));
        assertTrue(Visual.naEdt(() -> protegido.caixaProtegido().isSelected()));
        assertFalse(Visual.naEdt(() -> protegido.botaoInstalarChave().isVisible()), "num servidor protegido o app não instala chave");
        ImagemDeDialogo.gerar(protegido, "servidor-editar-protegido", "Editar servidor", "Servidor protegido", "servidor-demo");
    }

    @Test
    void removerServidorMostraOQueSeraFeitoEOBotaoComONome() {
        Visual.tema(Tema.CLARO);
        DialogoRemoverServidor d = Visual.naEdt(() -> new DialogoRemoverServidor("servidor-demo", "labuser", true, List.of(), Origem.GERADA_PELO_APP, opcoes -> {
            throw new IllegalStateException("a galeria nunca remove nada");
        }, avisos));
        assertEquals("Remover servidor-demo", Visual.naEdt(d::textoDoBotaoRemover));
        ImagemDeDialogo.gerar(d, "servidor-remover", "Remover servidor", "O app vai parar de se conectar a servidor-demo e tirá-lo da lista", "Remover servidor-demo");
    }

    // ---------------------------------------------------------------------------------------------
    // Como conectar: SSM e Cloudflare
    // ---------------------------------------------------------------------------------------------

    @Test
    void comoConectarPeloSsmEPelaCloudflareNoEditarEORelatorioDasFerramentas() {
        DialogoEditarServidor ssm = editar(config(false, Transporte.ssm(INSTANCIA, "us-east-1", "perfil-demo", 22), ConfigServidor.HOST_DO_TRANSPORTE));
        SecaoComoConectar s = Visual.naEdt(ssm::secaoComoConectar);
        assertEquals(SecaoComoConectar.Modo.SSM, Visual.naEdt(s::modo));
        assertEquals(INSTANCIA, s.campoDaInstancia().getText());
        ImagemDeDialogo.gerar(ssm, "como-conectar-editar-ssm", "Como conectar", "AWS Session Manager (SSM)", INSTANCIA, "us-east-1", "perfil-demo", "Conferir as ferramentas neste computador");

        DialogoEditarServidor cloudflare = editar(config(false, Transporte.cloudflared("ssh.example.com"), ConfigServidor.HOST_DO_TRANSPORTE));
        SecaoComoConectar c = Visual.naEdt(cloudflare::secaoComoConectar);
        assertEquals(SecaoComoConectar.Modo.CLOUDFLARE, Visual.naEdt(c::modo));
        assertEquals("ssh.example.com", c.campoDoHostname().getText());
        ImagemDeDialogo.gerar(cloudflare, "como-conectar-editar-cloudflare", "Como conectar", "Cloudflare", "ssh.example.com", "Conferir as ferramentas neste computador");

        // o relatório de "Conferir as ferramentas neste computador": o aws achado e o plugin do Session Manager ausente, com o passo de instalar
        Visual.tema(Tema.CLARO);
        DialogoDasFerramentas daAws = Visual.naEdt(() -> new DialogoDasFerramentas(relatorioDaAws(), new CopiadorDeTexto(() -> area), avisos));
        assertEquals(List.of("brew install --cask session-manager-plugin"), Visual.naEdt(daAws::textosDosPassos));
        ImagemDeDialogo.gerar(daAws, "como-conectar-ssm-config", "encontrada em /opt/homebrew/bin/aws", "não encontrada", "brew install --cask session-manager-plugin",
            "usado pelo modo AWS Session Manager (SSM)", "O app só confere se eles estão aqui: não instala nada");

        Visual.tema(Tema.CLARO);
        DialogoDasFerramentas daCloudflare = Visual.naEdt(() -> new DialogoDasFerramentas(relatorioDaCloudflare(), new CopiadorDeTexto(() -> area), avisos));
        ImagemDeDialogo.gerar(daCloudflare, "como-conectar-cloudflare-config", "encontrada em /opt/homebrew/bin/cloudflared", "usado pelo modo Cloudflare",
            "O app só confere se eles estão aqui: não instala nada");
    }
}
