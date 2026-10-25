package br.com.labdesktop.ui;

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
import br.com.labdesktop.cofre.ImportadorDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.dialogos.DialogoEditarServidor;
import br.com.labdesktop.ui.dialogos.DialogoRemoverServidor;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JMenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Editar e remover servidor pela INTERFACE, de ponta a ponta: cofre de verdade, {@code SshServer} embutido, agente falso,
 * a janela montada sem {@code JFrame} e os diálogos capturados por {@link DialogosGravados}. O usuário clica no cartão ou no
 * menu de contexto, mexe no diálogo e o resultado aparece no cofre, na conexão, na barra e nas telas.
 */
@ExtendWith(VerificaEdt.class)
class EdicaoERemocaoPelaInterfaceTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private PainelPrincipal painel;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private final List<PublicKey> chavesAceitas = new ArrayList<>();
    private final List<String> comandosDeChave = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        ServidorSshDeTeste.Manipulador doAgente = agente.comandosPorSsh();
        ssh.comandoRemoto((linha, stdin, saida) -> {
            if (linha.startsWith("sh -c")) {
                comandosDeChave.add(stdin);
                return 0;
            }
            return doAgente.executar(linha, stdin, saida);
        });
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        if (painel != null) {
            Visual.naEdt(() -> painel.encerrar());
        }
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        ssh.close();
    }

    private ChaveSsh novaChave(String nome) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar(nome, "mac");
        cofre.adicionarChave(chave);
        chavesAceitas.add(chave.paraKeyPair().getPublic());
        ssh.aceitar(chavesAceitas.toArray(PublicKey[]::new));
        return chave;
    }

    /** Uma chave que o usuário trouxe de um ARQUIVO (o id_ed25519 dele): é o que a opção "importar" do assistente guarda. */
    private ChaveSsh novaChaveImportada(String nome) throws Exception {
        java.io.ByteArrayOutputStream arquivo = new java.io.ByteArrayOutputStream();
        org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
            org.apache.sshd.common.config.keys.KeyUtils.generateKeyPair("ssh-ed25519", 256), "usuario@notebook", null, arquivo);
        ChaveSsh chave = ImportadorDeChaves.importar(arquivo.toByteArray(), null, nome, "mac");
        cofre.adicionarChave(chave);
        chavesAceitas.add(chave.paraKeyPair().getPublic());
        ssh.aceitar(chavesAceitas.toArray(PublicKey[]::new));
        return chave;
    }

    private ServidorLab servidor(String nome, ChaveSsh chave, boolean conectar) throws Exception {
        return servidor(nome, chave, conectar, agente.porta());
    }

    private ServidorLab servidor(String nome, ChaveSsh chave, boolean conectar, int portaDoAgente) throws Exception {
        TokenAgente token = TokenAgente.gerar();
        String dispositivo = "mac-" + nome;
        agente.tokens.put(token.hashSha256Hex(), dispositivo);
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), nome, "127.0.0.1", ssh.porta(), "labuser", chave.id(),
            portaDoAgente, List.of(ssh.pin()), Transporte.DIRETO, dispositivo, Estado2fa.DESLIGADO);
        cofre.adicionarServidor(cfg, token);
        ServidorLab lab = gerenciador.adicionar(cfg.id());
        if (conectar) {
            lab.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        }
        return lab;
    }

    private void abrirAJanela() {
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, null, avisos, dialogos, new NavegadorGravado(avisos)));
    }

    private TelaServidores telaDeServidores() {
        return Visual.naEdt(() -> painel.navegacao().telaDeServidores());
    }

    private TelaServidores.CartaoDeServidor cartaoDe(ServidorLab lab) {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        Espera.ate(PRAZO, "cartão", () -> Visual.naEdt(() -> telaDeServidores().cartao(lab.id())) != null);
        return Visual.naEdt(() -> telaDeServidores().cartao(lab.id()));
    }

    private void clicarNoCartao(TelaServidores.CartaoDeServidor cartao, Item item) {
        Espera.ate(PRAZO, () -> "botão " + item + " (ações: " + Visual.naEdt(cartao::acoesMostradas) + ")",
            () -> Visual.naEdt(() -> cartao.botaoDa(item)) != null);
        Visual.naEdt(() -> cartao.botaoDa(item).doClick());
    }

    private static String textosDe(java.awt.Component c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof javax.swing.text.JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                sb.append(textosDe(f));
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Os botões e o menu
    // ---------------------------------------------------------------------------------------------

    @Test
    void editarERemoverAparecemEmTodosOsEstadosDaConexao() {
        for (EstadoConexao conexao : EstadoConexao.values()) {
            EstadoServidor e = new EstadoServidor("s1", "lab", conexao, null, 0, null, null, 0, Estado2fa.DESLIGADO, "mac", null, null);
            List<Item> itens = MenuDoServidor.itensPara(e);
            assertEquals(List.of(Item.EDITAR, Item.REMOVER), itens.subList(itens.size() - 2, itens.size()),
                "Editar e Remover valem em " + conexao + ": é quando a conexão está quebrada que o usuário mais precisa deles");
        }
        assertEquals("Editar…", Item.EDITAR.rotulo());
        assertEquals("Remover…", Item.REMOVER.rotulo());
    }

    @Test
    void ocartaoTemEditarERemoverNoCantoDireitoEOMenuDeContextoTambem() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), true);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "ações", () -> Visual.naEdt(cartao::acoesMostradas).contains(Item.REMOVER));
        assertEquals(List.of(Item.ATUALIZAR, Item.CONFIGURAR_2FA, Item.EDITAR, Item.REMOVER), Visual.naEdt(cartao::acoesMostradas));
        assertEquals("Editar…", Visual.naEdt(() -> cartao.botaoDa(Item.EDITAR).getText()));
        assertEquals("Remover…", Visual.naEdt(() -> cartao.botaoDa(Item.REMOVER).getText()));
        assertEquals(Botao.Variante.PERIGO, Visual.naEdt(() -> cartao.botaoDa(Item.REMOVER).variante()), "remover é ação destrutiva");
        Visual.salvar(Visual.renderizar(painel, 1440, 520), "janela-servidores-editar-remover");

        List<String> rotulos = new ArrayList<>();
        Visual.naEdt(() -> {
            for (var c : MenuDoServidor.popup(lab, painel.acoes()).getComponents()) {
                if (c instanceof JMenuItem m) {
                    rotulos.add(m.getText());
                }
            }
        });
        assertTrue(rotulos.containsAll(List.of("Editar…", "Remover…")), rotulos.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // Editar
    // ---------------------------------------------------------------------------------------------

    @Test
    void editarONomeSalvaNoCofreMostraONomeNovoNaTelaEMantemAImpressao() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), true);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);

        clicarNoCartao(cartao, Item.EDITAR);
        DialogoEditarServidor d = dialogos.ultimoComo(DialogoEditarServidor.class);
        assertEquals("lab", Visual.naEdt(() -> d.campoDoNome().getText()));
        assertEquals("127.0.0.1", Visual.naEdt(() -> d.campoDoHost().getText()));
        assertEquals(String.valueOf(ssh.porta()), Visual.naEdt(() -> d.campoDaPorta().getText()));
        assertEquals("labuser", Visual.naEdt(() -> d.campoDoUsuario().getText()));
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()), "nada mudou ainda");
        assertFalse(Visual.naEdt(d::mostraOAvisoDaImpressao));

        Visual.naEdt(() -> d.campoDoNome().setText("lab da sala"));
        assertTrue(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
        assertFalse(Visual.naEdt(d::mostraOAvisoDaImpressao), "mudar só o nome não apaga a impressão digital");
        Visual.naEdt(() -> d.botaoSalvar().doClick());

        Espera.ate(PRAZO, "gravou no cofre", () -> "lab da sala".equals(cofre.servidor(lab.id()).orElseThrow().nome()));
        Espera.ate(PRAZO, "nome novo no cartão", () -> "lab da sala".equals(Visual.naEdt(cartao::textoDoNome)));
        assertEquals("lab da sala", Visual.naEdt(() -> painel.barra().item(lab.id()).textoDoNome()));
        assertEquals(1, cofre.servidor(lab.id()).orElseThrow().pins().size());
        Espera.ate(PRAZO, "aviso " + "Servidor lab da sala salvo", () -> avisos.disse("ok", "Servidor lab da sala salvo"));
        assertFalse(avisos.disse("atencao", "impressão"), "nenhum aviso de impressão: ela continua valendo");
        Espera.ate(PRAZO, "reconectou", () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
    }

    @Test
    void editarOEnderecoAvisaApagaAImpressaoEPedeParaConfirmarDeNovo() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), true);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        clicarNoCartao(cartao, Item.EDITAR);
        DialogoEditarServidor d = dialogos.ultimoComo(DialogoEditarServidor.class);

        Visual.naEdt(() -> d.campoDoHost().setText("localhost"));

        assertTrue(Visual.naEdt(d::mostraOAvisoDaImpressao), "o aviso aparece enquanto o usuário digita, antes de salvar");
        assertEquals("Como o endereço ou a porta mudou, a impressão digital guardada será apagada e a próxima conexão pedirá "
            + "para você confirmá-la de novo.", Visual.naEdt(d::textoDoAviso));
        Visual.salvar(Visual.renderizar(d.raiz(), 540, Visual.alturaPreferida(d.raiz(), 540)), "dialogo-editar-servidor-endereco");
        Visual.naEdt(() -> d.botaoSalvar().doClick());

        Espera.ate(PRAZO, "pin apagado no cofre", () -> cofre.servidor(lab.id()).orElseThrow().pins().isEmpty());
        assertEquals("localhost", cofre.servidor(lab.id()).orElseThrow().host());
        Espera.ate(PRAZO, "aviso " + "confirme a impressão digital do servidor quando o app pedir", () -> avisos.disse("atencao", "confirme a impressão digital do servidor quando o app pedir"));
        Espera.ate(PRAZO, () -> "pede para confirmar, estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.ERRO_CHAVE_HOST);
        Espera.ate(PRAZO, "cartão pede a conferência", () -> Visual.naEdt(cartao::acoesMostradas).contains(Item.CONFERIR_IMPRESSAO));
        assertTrue(Visual.naEdt(cartao::textoDaImpressao).startsWith("ainda não confirmado"), Visual.naEdt(cartao::textoDaImpressao));
        assertEquals("Confirmar servidor", Visual.naEdt(cartao::textoDoEstado));
    }

    @Test
    void editarValidaComAsRegrasDoAssistenteEMostraAFraseNaTela() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), false);
        abrirAJanela();
        clicarNoCartao(cartaoDe(lab), Item.EDITAR);
        DialogoEditarServidor d = dialogos.ultimoComo(DialogoEditarServidor.class);

        Visual.naEdt(() -> d.campoDaPorta().setText("vinte e dois"));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        assertEquals("A porta do SSH precisa ser um número.", Visual.naEdt(d::textoDoErro));
        Visual.naEdt(() -> d.campoDaPorta().setText(String.valueOf(ssh.porta())));
        assertEquals("", Visual.naEdt(d::textoDoErro), "digitar limpa o erro");
        Visual.naEdt(() -> d.campoDoHost().setText("-oProxyCommand=touch x"));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        assertTrue(Visual.naEdt(d::textoDoErro).contains("endereço"), Visual.naEdt(d::textoDoErro));
        Visual.naEdt(() -> d.campoDoHost().setText("127.0.0.1"));
        Visual.naEdt(() -> d.campoDoNome().setText(" "));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        assertTrue(Visual.naEdt(d::textoDoErro).contains("nome"), Visual.naEdt(d::textoDoErro));

        assertEquals("lab", cofre.servidor(lab.id()).orElseThrow().nome(), "nada foi gravado");
        assertEquals(1, cofre.servidor(lab.id()).orElseThrow().pins().size());
    }

    @Test
    void editarAPortaDoPainelCorrigeODigitoErradoDoPasso1SemApagarAImpressaoDigital() throws Exception {
        int portaErrada = agente.porta() == 65_535 ? agente.porta() - 1 : agente.porta() + 1; // o usuário digitou 7001 em vez de 7000
        ServidorLab lab = servidor("lab", novaChave("k"), false, portaErrada);
        lab.iniciar();
        Espera.ate(PRAZO, () -> "SSH de pé, agente não responde, estado=" + lab.estado(),
            () -> lab.estado().conexao() == EstadoConexao.SSH_OK_SEM_AGENTE);
        abrirAJanela();
        clicarNoCartao(cartaoDe(lab), Item.EDITAR);
        DialogoEditarServidor d = dialogos.ultimoComo(DialogoEditarServidor.class);

        assertEquals(String.valueOf(portaErrada), Visual.naEdt(() -> d.campoDaPortaDoAgente().getText()), "o campo mostra o valor de agora");
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()), "nada mudou ainda");
        assertTrue(Visual.naEdt(() -> textosDe(d.raiz())).contains("Porta do painel no servidor"));
        assertTrue(Visual.naEdt(() -> textosDe(d.raiz())).contains(
            br.com.labdesktop.ui.dialogos.DialogoAdicionarServidor.DICA_DA_PORTA_DO_PAINEL), "a mesma dica do assistente");

        Visual.naEdt(() -> d.campoDaPortaDoAgente().setText("sete mil"));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        assertEquals("A porta do painel precisa ser um número.", Visual.naEdt(d::textoDoErro));
        Visual.naEdt(() -> d.campoDaPortaDoAgente().setText("0"));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        assertTrue(Visual.naEdt(d::textoDoErro).contains("porta do painel"), Visual.naEdt(d::textoDoErro));
        assertEquals(portaErrada, cofre.servidor(lab.id()).orElseThrow().portaAgente(), "nada foi gravado com valor ruim");

        Visual.naEdt(() -> d.campoDaPortaDoAgente().setText(String.valueOf(agente.porta())));
        assertTrue(Visual.naEdt(() -> d.botaoSalvar().isEnabled()), "mudar só a porta do painel já permite salvar");
        assertFalse(Visual.naEdt(d::mostraOAvisoDaImpressao), "a máquina é a mesma: a impressão digital continua valendo");
        Visual.salvar(Visual.renderizar(d.raiz(), 540, Visual.alturaPreferida(d.raiz(), 540)), "dialogo-editar-servidor-porta-do-painel");
        Visual.naEdt(() -> d.botaoSalvar().doClick());

        Espera.ate(PRAZO, "gravou no cofre", () -> cofre.servidor(lab.id()).orElseThrow().portaAgente() == agente.porta());
        assertEquals(1, cofre.servidor(lab.id()).orElseThrow().pins().size(), "a impressão digital NÃO foi apagada");
        assertFalse(avisos.disse("atencao", "impressão"));
        Espera.ate(PRAZO, () -> "reconectou com a porta certa e o agente respondeu, estado=" + lab.estado(),
            () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
    }

    @Test
    void seOCofreNaoGravarAEdicaoOErroApareceNoDialogoQueFicaAbertoParaTentarDeNovo() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), false);
        DialogoEditarServidor d = Visual.naEdt(() -> new DialogoEditarServidor(lab.config(), dados -> {
            throw new br.com.labdesktop.cofre.CofreException("O disco está cheio.");
        }, avisos));
        Visual.naEdt(() -> d.campoDoNome().setText("outro nome"));

        Visual.naEdt(() -> d.botaoSalvar().doClick());

        Espera.ate(PRAZO, "erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        assertEquals("O disco está cheio.", Visual.naEdt(d::textoDoErro));
        assertFalse(Visual.naEdt(d::ocupado));
        assertTrue(Visual.naEdt(() -> d.botaoSalvar().isEnabled()), "dá para tentar de novo");
        assertTrue(Visual.naEdt(() -> d.campoDoNome().isEnabled()));
        assertFalse(avisos.disse("ok", "salvo"));
    }

    @Test
    void seOGerenciadorDisserQueNadaMudouOAppDizNadaMudou() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), false);
        ConfigServidor cfg = lab.config();
        DialogoEditarServidor d = Visual.naEdt(() -> new DialogoEditarServidor(cfg,
            dados -> new GerenciadorServidores.ResultadoDaEdicao(cfg, false, false), avisos));
        Visual.naEdt(() -> d.campoDoNome().setText("lab "));
        Visual.naEdt(() -> d.campoDoNome().setText("lab2"));

        Visual.naEdt(() -> d.botaoSalvar().doClick());

        Espera.ate(PRAZO, "aviso", () -> avisos.disse("info", "Nada mudou."));
        assertFalse(avisos.disse("ok", "salvo"));
    }

    // ---------------------------------------------------------------------------------------------
    // Remover
    // ---------------------------------------------------------------------------------------------

    @Test
    void removerPeloCartaoApagaTudoMostraOEstadoVazioComOConviteParaAdicionar() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), true);
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        Espera.ate(PRAZO, "token", () -> "mac-lab".equals(Visual.naEdt(cartao::textoDoToken)));

        clicarNoCartao(cartao, Item.REMOVER);
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);

        assertEquals("Remover lab", Visual.naEdt(d::textoDoBotaoRemover), "o botão repete o nome do alvo, nunca 'OK'");
        assertTrue(Visual.naEdt(() -> d.caixaDeApagarAChave().isSelected() && d.caixaDeApagarAChave().isEnabled()));
        assertTrue(Visual.naEdt(() -> d.caixaDeRevogar().isSelected() && d.caixaDeRevogar().isEnabled()), "conectado: dá para revogar");
        assertEquals("Apagar também a chave deste servidor do cofre", Visual.naEdt(() -> d.caixaDeApagarAChave().getText()));
        assertEquals("Revogar o acesso deste app no servidor", Visual.naEdt(() -> d.caixaDeRevogar().getText()));
        assertEquals(Botao.Variante.PRIMARIO, Visual.naEdt(() -> d.botaoCancelar().variante()), "Cancelar é o botão em destaque");
        assertEquals(Botao.Variante.PERIGO, Visual.naEdt(() -> d.botaoRemover().variante()));
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            Visual.salvar(Visual.renderizar(d.raiz(), 540, Visual.alturaPreferida(d.raiz(), 540)), "dialogo-remover-servidor-" + tema.name().toLowerCase());
        }
        Visual.tema(Tema.CLARO);

        Visual.naEdt(() -> d.botaoRemover().doClick());

        Espera.ate(PRAZO, () -> "removido (erro='" + Visual.naEdt(d::textoDoErro) + "')", () -> cofre.servidores().isEmpty());
        Espera.ate(PRAZO, "aviso", () -> avisos.disse("ok", "Servidor lab removido."));
        assertTrue(cofre.chaves().isEmpty(), "a chave saiu junto");
        assertTrue(agente.tokens.isEmpty(), "o token foi revogado no agente");
        assertEquals(1, comandosDeChave.size(), "e a chave saiu do acesso do usuário no servidor");
        assertFalse(avisos.todos.stream().anyMatch(a -> a.tipo().equals("atencao")), avisos.todos.toString());
        Espera.ate(PRAZO, "estado vazio", () -> Visual.naEdt(() -> telaDeServidores().mostraOVazio()));
        assertEquals(List.of(), Visual.naEdt(() -> painel.barra().idsExibidos()));
        assertEquals(List.of(), Visual.naEdt(() -> telaDeServidores().idsExibidos()));
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));
        assertTrue(Visual.naEdt(() -> telaDeServidores().botaoAdicionar().isEnabled()), "'Adicionar servidor' segue ali");
        assertEquals("Adicionar servidor", Visual.naEdt(() -> telaDeServidores().botaoAdicionar().getText()));
        Visual.salvar(Visual.renderizar(painel, 1440, 520), "janela-servidores-vazio-depois-de-remover");
    }

    @Test
    void cancelarNaoRemoveNada() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), true);
        abrirAJanela();
        Visual.naEdt(() -> MenuDoServidor.executar(Item.REMOVER, lab, painel.acoes()));
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);

        Visual.naEdt(() -> d.botaoCancelar().doClick());

        Espera.naoAcontece(Duration.ofMillis(600), "remoção depois de cancelar", () -> cofre.servidores().isEmpty());
        assertEquals(List.of(lab), gerenciador.servidores());
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertEquals(1, agente.tokens.size());
        assertTrue(comandosDeChave.isEmpty());
    }

    @Test
    void removerPeloMenuDeContextoDaBarraTambemFunciona() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), true);
        abrirAJanela();
        Visual.naEdt(() -> {
            for (var c : MenuDoServidor.popup(lab, painel.acoes()).getComponents()) {
                if (c instanceof JMenuItem m && "Remover…".equals(m.getText())) {
                    m.doClick();
                }
            }
        });
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);
        Visual.naEdt(() -> d.caixaDeRevogar().setSelected(false));
        Visual.naEdt(() -> d.caixaDeApagarAChave().setSelected(false));

        Visual.naEdt(() -> d.botaoRemover().doClick());

        Espera.ate(PRAZO, "removido", () -> cofre.servidores().isEmpty());
        assertEquals(1, cofre.chaves().size(), "o usuário pediu para manter a chave");
        assertEquals(1, agente.tokens.size(), "e para não revogar nada no servidor");
        assertTrue(comandosDeChave.isEmpty());
    }

    @Test
    void chaveCriadaPeloAppDizQueSeraTiradaDoServidor() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), true);
        abrirAJanela();
        Visual.naEdt(() -> MenuDoServidor.executar(Item.REMOVER, lab, painel.acoes()));
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);

        String texto = Visual.naEdt(d::explicacaoDaRevogacao);
        assertTrue(texto.contains("tira do acesso do usuário labuser a chave que o app criou para este servidor"), texto);
        assertFalse(texto.contains("NÃO a tira"), texto);
    }

    @Test
    void chaveImportadaDeUmArquivoDoDonoNaoETiradaDoServidorEODialogoDizIssoComClareza() throws Exception {
        ServidorLab lab = servidor("lab", novaChaveImportada("de-teste"), true);
        assertEquals(InfoChave.Origem.IMPORTADA, gerenciador.origemDaChaveDe(lab.id()));
        abrirAJanela();
        Visual.naEdt(() -> MenuDoServidor.executar(Item.REMOVER, lab, painel.acoes()));
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);

        String texto = Visual.naEdt(d::explicacaoDaRevogacao);
        assertTrue(texto.startsWith("Apaga o token deste Mac no painel do servidor."), texto);
        assertTrue(texto.contains("veio de um arquivo seu") && texto.contains("NÃO a tira do acesso do usuário labuser")
            && texto.contains("você pode usá-la fora do app"), texto);
        assertFalse(texto.contains("chave que o app criou"), "não promete o que não vai acontecer: " + texto);
        assertTrue(Visual.naEdt(d::explicacaoDaChave).contains("o arquivo original no seu Mac não é mexido"), Visual.naEdt(d::explicacaoDaChave));
        assertTrue(Visual.naEdt(() -> d.caixaDeRevogar().isSelected()), "revogar continua marcada: o token do app sai de qualquer jeito");
        Visual.salvar(Visual.renderizar(d.raiz(), 540, Visual.alturaPreferida(d.raiz(), 540)), "dialogo-remover-servidor-chave-importada");

        Visual.naEdt(() -> d.botaoRemover().doClick());

        Espera.ate(PRAZO, () -> "removido (erro='" + Visual.naEdt(d::textoDoErro) + "')", () -> cofre.servidores().isEmpty());
        Espera.ate(PRAZO, "aviso", () -> avisos.disse("atencao", "NÃO a tirou"));
        assertTrue(agente.tokens.isEmpty(), "o token deste Mac saiu do painel do servidor");
        assertTrue(comandosDeChave.isEmpty(), "a chave do usuário ficou no authorized_keys: ele não perde o acesso por fora do app");
    }

    @Test
    void oBotaoDaFaixaDeChaveQueMudouAbreOdialogoDeRemover() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), false);
        abrirAJanela();

        Visual.naEdt(() -> painel.acoes().executar(lab, ConteudoDosAvisos.Acao.REMOVER_SERVIDOR));

        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);
        assertEquals("Remover lab", Visual.naEdt(d::textoDoBotaoRemover), "abre o diálogo com a confirmação de sempre, não remove direto");
        assertEquals(1, cofre.servidores().size());
    }

    @Test
    void comChaveCompartilhadaApagarAChaveFicaDesabilitadoComAExplicacao() throws Exception {
        ChaveSsh compartilhada = novaChave("compartilhada");
        ServidorLab a = servidor("a", compartilhada, true);
        servidor("b", compartilhada, false);
        abrirAJanela();
        Visual.naEdt(() -> MenuDoServidor.executar(Item.REMOVER, a, painel.acoes()));
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);

        assertFalse(Visual.naEdt(() -> d.caixaDeApagarAChave().isEnabled()));
        assertFalse(Visual.naEdt(() -> d.caixaDeApagarAChave().isSelected()));
        assertTrue(Visual.naEdt(d::explicacaoDaChave).contains("também a usa"), Visual.naEdt(d::explicacaoDaChave));
        assertTrue(Visual.naEdt(d::explicacaoDaChave).contains("o servidor b"), Visual.naEdt(d::explicacaoDaChave));
        Visual.salvar(Visual.renderizar(d.raiz(), 540, Visual.alturaPreferida(d.raiz(), 540)), "dialogo-remover-servidor-chave-compartilhada");

        Visual.naEdt(() -> d.botaoRemover().doClick());

        Espera.ate(PRAZO, "removido", () -> cofre.servidores().size() == 1);
        assertEquals(1, cofre.chaves().size(), "a chave do outro servidor ficou");
        assertTrue(comandosDeChave.isEmpty(), "e não foi tirada do servidor");
    }

    @Test
    void semConexaoRevogarFicaDesabilitadoComAExplicacao() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), false);
        abrirAJanela();
        Visual.naEdt(() -> MenuDoServidor.executar(Item.REMOVER, lab, painel.acoes()));
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);

        assertFalse(Visual.naEdt(() -> d.caixaDeRevogar().isEnabled()));
        assertFalse(Visual.naEdt(() -> d.caixaDeRevogar().isSelected()));
        assertTrue(Visual.naEdt(d::explicacaoDaRevogacao).contains("não está conectado"), Visual.naEdt(d::explicacaoDaRevogacao));
        assertTrue(Visual.naEdt(() -> d.caixaDeApagarAChave().isSelected()), "a chave continua marcada para apagar");
        Visual.salvar(Visual.renderizar(d.raiz(), 540, Visual.alturaPreferida(d.raiz(), 540)), "dialogo-remover-servidor-sem-conexao");

        Visual.naEdt(() -> d.botaoRemover().doClick());

        Espera.ate(PRAZO, "removido", () -> cofre.servidores().isEmpty());
        assertTrue(cofre.chaves().isEmpty());
    }

    @Test
    void removerUmDeDoisQueEstavaNaTelaVoltaParaAListaDeServidoresComOOutro() throws Exception {
        ServidorLab a = servidor("a", novaChave("ka"), true);
        ServidorLab b = servidor("b", novaChave("kb"), true);
        abrirAJanela();
        Visual.naEdt(() -> painel.mostrarServidor(a));
        assertEquals("servidor:" + a.id(), Visual.naEdt(() -> painel.navegacao().atual()));
        Visual.naEdt(() -> MenuDoServidor.executar(Item.REMOVER, a, painel.acoes()));
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);

        Visual.naEdt(() -> d.botaoRemover().doClick());

        Espera.ate(PRAZO, "removido", () -> cofre.servidores().size() == 1);
        Espera.ate(PRAZO, "voltou para a lista de servidores", () -> "servidores".equals(Visual.naEdt(() -> painel.navegacao().atual())));
        assertEquals(List.of(b.id()), Visual.naEdt(() -> painel.barra().idsExibidos()));
        assertEquals(List.of(b.id()), Visual.naEdt(() -> telaDeServidores().idsExibidos()));
        assertFalse(Visual.naEdt(() -> telaDeServidores().mostraOVazio()));
        assertEquals(null, Visual.naEdt(() -> painel.navegacao().telaDe(a.id())), "a tela de projetos dele foi solta");
        assertNotNull(Visual.naEdt(() -> painel.navegacao().telaDe(b.id())));
        assertEquals(EstadoConexao.CONECTADO, b.estado().conexao(), "o outro servidor não foi tocado");
    }

    @Test
    void seOCofreFalharONomeAparecePeloErroEODialogoFicaAbertoParaTentarDeNovo() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), true);
        abrirAJanela();
        Visual.naEdt(() -> MenuDoServidor.executar(Item.REMOVER, lab, painel.acoes()));
        DialogoRemoverServidor d = dialogos.ultimoComo(DialogoRemoverServidor.class);
        cofre.bloquear(); // o cofre fechou no meio: nada pode ser gravado

        Visual.naEdt(() -> d.botaoRemover().doClick());

        Espera.ate(PRAZO, "erro no diálogo", () -> Visual.naEdt(d::textoDoErro).startsWith("Não consegui remover"));
        assertTrue(Visual.naEdt(d::textoDoErro).contains("O cofre está bloqueado"), Visual.naEdt(d::textoDoErro));
        assertFalse(Visual.naEdt(d::ocupado));
        assertTrue(Visual.naEdt(() -> d.botaoRemover().isEnabled()), "dá para tentar de novo");
        assertFalse(avisos.disse("ok", "removido"));
    }

    // ---------------------------------------------------------------------------------------------
    // Visual
    // ---------------------------------------------------------------------------------------------

    @Test
    void desenhaOEditarNosDoisTemas() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"), false);
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoEditarServidor d = Visual.naEdt(() -> new DialogoEditarServidor(lab.config(), dados -> null, avisos));
            Visual.salvar(Visual.renderizar(d.raiz(), 540, Visual.alturaPreferida(d.raiz(), 540)), "dialogo-editar-servidor-" + tema.name().toLowerCase());
        }
    }
}
