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
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.FerramentasFalsasDeTunel;
import br.com.labdesktop.teste.LancadorDeFerramentaFalsa;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.transporte.FerramentaExterna;
import br.com.labdesktop.transporte.LocalizadorDeFerramenta;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conexão sem porta aberta pela JANELA, com o cofre temporário, o SSH embutido e a ferramenta falsa: o selo "Sem porta aberta: SSM" ou "Cloudflare" no cartão e na
 * página (só nos servidores que usam esses modos), o aviso do servidor SEM a ferramenta (só ele; o outro segue normal) com o passo de instalar copiável, e o aviso de "Seu login da AWS
 * expirou". PNGs claro e escuro. Nada de AWS nem Cloudflare de verdade.
 */
@ExtendWith(VerificaEdt.class)
class SemPortaAbertaNaJanelaTest {

    private static final char[] SENHA_MESTRA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private PainelPrincipal painel;
    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private final java.util.List<br.com.labdesktop.cofre.InfoChave> chaves = new java.util.ArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        agente.fora = true;
        agente.diagnostico = "ausente";
        agente.perfil = "monitor";
        ssh.comandoRemoto(agente.comandosPorSsh());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA_MESTRA.clone(), ParametrosKdf.MINIMO);
    }

    /** Monta o gerenciador (com a ferramenta falsa dada) e o assistente; os servidores já gravados no cofre entram. */
    private void gerenciadorCom(Map<String, String> comportamento, LocalizadorDeFerramenta localizador) {
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(ssh.porta(), comportamento);
        this.lancador = lancador;
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), ServicosLocais.padrao(),
            FerramentasFalsasDeTunel.abridor(lancador, localizador, registro));
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
    }

    private LancadorDeFerramentaFalsa lancador;

    @AfterEach
    void desmontar() throws Exception {
        if (painel != null) {
            Visual.naEdt(() -> painel.encerrar());
        }
        if (gerenciador != null) {
            gerenciador.fechar().join();
        }
        registro.encerrarTodos();
        cofre.close();
        agente.close();
        ssh.close();
        Visual.tema(Tema.CLARO);
    }

    private ConfigServidor gravar(String nome, Transporte transporte) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("k-" + nome, "mac");
        cofre.adicionarChave(chave);
        ssh.aceitar(java.util.stream.Stream.concat(chaves.stream().map(c -> c.publica()), java.util.stream.Stream.of(chave.publica()))
            .map(SemPortaAbertaNaJanelaTest::publica).toArray(java.security.PublicKey[]::new));
        chaves.add(chave.info());
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), nome, "127.0.0.1", ssh.porta(), "labuser", chave.id(), agente.porta(), List.of(ssh.pin()),
            transporte, null, Estado2fa.DESLIGADO);
        cofre.adicionarServidor(cfg, null);
        return cfg;
    }

    private static java.security.PublicKey publica(String linha) {
        try {
            return org.apache.sshd.common.config.keys.PublicKeyEntry.parsePublicKeyEntry(linha)
                .resolvePublicKey(null, java.util.Collections.emptyMap(), org.apache.sshd.common.config.keys.PublicKeyEntryResolver.FAILING);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void abrirAJanela() {
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, assistente, avisos, dialogos, new NavegadorGravado(avisos)));
    }

    private TelaServidores.CartaoDeServidor cartaoDe(ServidorLab lab) {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        Espera.ate(PRAZO, "cartão", () -> Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id())) != null);
        return Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id()));
    }

    private static java.awt.Component achar(java.awt.Component raiz, String nome) {
        if (nome.equals(raiz.getName()) && raiz.isVisible()) {
            return raiz;
        }
        if (raiz instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                if (f.isVisible()) {
                    java.awt.Component r = achar(f, nome);
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        return null;
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
                if (f.isVisible()) {
                    sb.append(textosDe(f));
                }
            }
        }
        return sb.toString();
    }

    private void doisTemas(java.awt.Component c, int largura, int altura, String nome) {
        Visual.salvar(Visual.renderizar((javax.swing.JComponent) c, largura, altura), nome + "-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(c));
        Visual.salvar(Visual.renderizar((javax.swing.JComponent) c, largura, altura), nome + "-escuro");
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(c));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void oSeloDoModoApareceNoCartaoENaPaginaSoNosServidoresSemPortaAberta() throws Exception {
        gerenciadorCom(Map.of(), FerramentasFalsasDeTunel.tudoInstalado());
        ConfigServidor nuvem = gravar("nuvem", Transporte.cloudflared("ssh.example.com"));
        ConfigServidor aws = gravar("conta-aws", Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22));
        ConfigServidor comum = gravar("lab", Transporte.DIRETO);
        ServidorLab labNuvem = gerenciador.adicionar(nuvem.id());
        ServidorLab labAws = gerenciador.adicionar(aws.id());
        ServidorLab labComum = gerenciador.adicionar(comum.id());
        gerenciador.iniciarTodos();
        for (ServidorLab l : List.of(labNuvem, labAws, labComum)) {
            Espera.ate(PRAZO, () -> l.estado().nome() + ": " + l.estado().conexao() + " " + l.estado().falha(), () -> l.estado().conexao() == EstadoConexao.SSH_OK_SEM_AGENTE);
        }
        abrirAJanela();
        TelaServidores.CartaoDeServidor cartaoNuvem = cartaoDe(labNuvem);
        TelaServidores.CartaoDeServidor cartaoAws = cartaoDe(labAws);
        TelaServidores.CartaoDeServidor cartaoComum = cartaoDe(labComum);
        Espera.ate(PRAZO, "selos nos cartões", () -> Visual.naEdt(() -> cartaoNuvem.seloDoModo().isVisible() && cartaoAws.seloDoModo().isVisible()));

        assertEquals("SEM PORTA ABERTA: CLOUDFLARE", Visual.naEdt(() -> cartaoNuvem.seloDoModo().texto()));
        assertEquals("SEM PORTA ABERTA: SSM", Visual.naEdt(() -> cartaoAws.seloDoModo().texto()));
        assertFalse(Visual.naEdt(() -> cartaoComum.seloDoModo().isVisible()), "o servidor direto não ganha selo: nada mudou para ele");
        assertEquals(br.com.labdesktop.ui.Gravidade.NEUTRO, Visual.naEdt(() -> cartaoNuvem.seloDoModo().gravidade()), "grafite, sem âmbar");
        assertTrue(Visual.naEdt(() -> cartaoNuvem.seloDoModo().getToolTipText()).contains("Editar servidor"));

        // o endereço do cartão diz onde está o servidor, sem inventar host e porta
        String textos = Visual.naEdt(() -> textosDe(cartaoAws));
        assertTrue(textos.contains("labuser@i-0123456789abcdef0 (us-east-1, perfil meu-perfil), painel na porta"), textos);
        assertFalse(textos.contains("transporte.invalid"), textos);

        Visual.galeriaNosDoisTemas(painel, 1280, 960, "sem-porta-aberta", "Lab Desktop", MolduraDeJanela.Tipo.JANELA); // galeria do README: o selo no cartão

        // a página do servidor (Resumo) mostra o selo no cabeçalho
        Visual.naEdt(() -> painel.mostrarServidor(labNuvem));
        TelaServidor tela = Visual.naEdt(() -> painel.navegacao().telaServidorDe(labNuvem.id()));
        Visual.naEdt(() -> tela.resumo().aoMudarEstado(labNuvem.estado()));
        Visual.renderizar(tela, 1100, 700);
        Selo seloDaPagina = (Selo) Visual.naEdt(() -> achar(tela, "selo-modo-de-conexao"));
        assertNotNull(seloDaPagina, "o selo está no cabeçalho da página");
        assertEquals("SEM PORTA ABERTA: CLOUDFLARE", seloDaPagina.texto());
        doisTemas(painel, 1280, 560, "janela-servidores-sem-porta-aberta");
        doisTemas(tela, 1100, 700, "servidor-sem-porta-aberta-pagina");

        Visual.naEdt(() -> painel.mostrarServidor(labComum));
        TelaServidor telaComum = Visual.naEdt(() -> painel.navegacao().telaServidorDe(labComum.id()));
        Visual.naEdt(() -> telaComum.resumo().aoMudarEstado(labComum.estado()));
        Visual.renderizar(telaComum, 1100, 700);
        assertEquals(null, Visual.naEdt(() -> achar(telaComum, "selo-modo-de-conexao")), "a página do servidor direto não tem o selo");
    }

    @Test
    void semAFerramentaSoEsseServidorMostraOAvisoComOPassoDeInstalarEOOutroSegueNormal() throws Exception {
        LocalizadorDeFerramenta semAws = f -> f == FerramentaExterna.AWS ? Optional.empty() : FerramentasFalsasDeTunel.tudoInstalado().localizar(f);
        gerenciadorCom(Map.of(), semAws);
        ConfigServidor aws = gravar("conta-aws", Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22));
        ConfigServidor comum = gravar("lab", Transporte.DIRETO);
        ServidorLab labAws = gerenciador.adicionar(aws.id());
        ServidorLab labComum = gerenciador.adicionar(comum.id());
        gerenciador.iniciarTodos();
        EstadoServidor[] foto = new EstadoServidor[1]; // o servidor oscila entre Reconectando e Conectando: a tela é desenhada com UM retrato fixo
        Espera.ate(PRAZO, () -> "estado=" + labAws.estado().conexao() + " falha=" + labAws.estado().falha(), () -> {
            EstadoServidor e = labAws.estado();
            if (e.conexao() == EstadoConexao.RECONECTANDO && e.falha() != null) {
                foto[0] = e;
                return true;
            }
            return false;
        });
        Espera.ate(PRAZO, () -> "estado=" + labComum.estado().conexao(), () -> labComum.estado().conexao() == EstadoConexao.SSH_OK_SEM_AGENTE);

        assertEquals(Falha.Tipo.TRANSPORTE_FERRAMENTA_AUSENTE, labAws.estado().falha().tipo());
        assertEquals("brew install awscli", labAws.estado().falha().textoCopiavel());
        assertTrue(labComum.estado().falha() == null || !labComum.estado().falha().ehDoTransporte(), "o outro servidor não vê o problema do aws");

        abrirAJanela();
        TelaServidores.CartaoDeServidor cartaoAws = cartaoDe(labAws);
        TelaServidores.CartaoDeServidor cartaoComum = cartaoDe(labComum);
        Espera.ate(PRAZO, "o cartão mostra a causa", () -> Visual.naEdt(() -> textosDe(cartaoAws)).contains("Não encontrei o programa aws"));
        String doCartao = Visual.naEdt(() -> textosDe(cartaoAws));
        assertTrue(doCartao.contains("brew install awscli"), doCartao);
        assertFalse(Visual.naEdt(() -> textosDe(cartaoComum)).contains("aws"), "o cartão do servidor comum não fala de aws");

        Visual.naEdt(() -> painel.mostrarServidor(labAws));
        TelaServidor tela = Visual.naEdt(() -> painel.navegacao().telaServidorDe(labAws.id()));
        Visual.naEdt(() -> tela.resumo().aoMudarEstado(foto[0]));
        Visual.renderizar(tela, 1100, 700);
        String daPagina = Visual.naEdt(() -> textosDe(tela));
        assertTrue(daPagina.contains("Não encontrei o programa aws (AWS CLI) neste computador"), daPagina);
        assertTrue(daPagina.contains("O app não instala nada"), daPagina);
        assertNotNull(Visual.naEdt(() -> achar(tela, "copiar-do-aviso")), "o botão Copiar do passo de instalar");
        assertTrue(Visual.naEdt(() -> tela.resumo().situacao()).textoCopiavel().equals("brew install awscli"));
        doisTemas(tela, 1100, 700, "servidor-aviso-ferramenta-ausente");
        assertTrue(lancador.lancados.isEmpty(), "nada foi lançado sem o aws");
    }

    @Test
    void loginDaAwsExpiradoAparecePelaJanelaComOComandoParaCopiar() throws Exception {
        gerenciadorCom(Map.of("LABTEST_SAIR_LOGO_TEXTO", "The SSO session associated with this profile has expired or is otherwise invalid."), FerramentasFalsasDeTunel.tudoInstalado());
        ConfigServidor aws = gravar("conta-aws", Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22));
        ServidorLab lab = gerenciador.adicionar(aws.id());
        lab.iniciar();
        EstadoServidor[] foto = new EstadoServidor[1];
        Espera.ate(PRAZO, () -> "estado=" + lab.estado().conexao() + " falha=" + lab.estado().falha(), () -> {
            EstadoServidor e = lab.estado();
            if (e.conexao() == EstadoConexao.RECONECTANDO && e.falha() != null && e.falha().tipo() == Falha.Tipo.TRANSPORTE_LOGIN_AWS) {
                foto[0] = e;
                return true;
            }
            return false;
        });
        abrirAJanela();
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        TelaServidor tela = Visual.naEdt(() -> painel.navegacao().telaServidorDe(lab.id()));
        Visual.naEdt(() -> tela.resumo().aoMudarEstado(foto[0]));
        Visual.renderizar(tela, 1100, 700);
        String daPagina = Visual.naEdt(() -> textosDe(tela));
        assertTrue(daPagina.contains("Seu login da AWS expirou. No terminal rode: aws sso login --profile meu-perfil e tente de novo."), daPagina);
        assertNotNull(Visual.naEdt(() -> achar(tela, "copiar-do-aviso")));
        assertEquals("aws sso login --profile meu-perfil", Visual.naEdt(() -> tela.resumo().situacao()).textoCopiavel());
        doisTemas(tela, 1100, 700, "servidor-aviso-login-da-aws");
        TelaServidores.CartaoDeServidor cartao = cartaoDe(lab);
        assertTrue(Visual.naEdt(() -> textosDe(cartao)).contains("Seu login da AWS expirou"), Visual.naEdt(() -> textosDe(cartao)));
    }
}
