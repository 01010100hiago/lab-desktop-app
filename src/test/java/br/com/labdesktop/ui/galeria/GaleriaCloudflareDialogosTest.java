package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cloudflare.ComposeComEdge;
import br.com.labdesktop.cloudflare.ErroCloudflare;
import br.com.labdesktop.cloudflare.LabSimulado;
import br.com.labdesktop.cloudflare.ObservadorDaEtapa;
import br.com.labdesktop.cloudflare.PublicadorNaInternet;
import br.com.labdesktop.cloudflare.ServicoCloudflare;
import br.com.labdesktop.cloudflare.TestadorDeUrl;
import br.com.labdesktop.cloudflare.TunelCf;
import br.com.labdesktop.cloudflare.TunelPorArquivo;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarDominio;
import br.com.labdesktop.ui.dialogos.DialogoConectarCloudflare;
import br.com.labdesktop.ui.dialogos.DialogoPublicar;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Imagens da galeria do README dos diálogos da Cloudflare: "Conectar a Cloudflare" (o passo do token, que fica mascarado, e a escolha do túnel e do arquivo no servidor), o
 * assistente "Adicionar domínio" (passos 1, 3 e 4) e "Publicar na internet" com a prévia do que muda. Tudo contra a Cloudflare FALSA e um servidor simulado, com dados
 * inventados (example.com, loja-web, app.example.com).
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaCloudflareDialogosTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final String CONTA = "Conta de Exemplo";

    private static final String COMPOSE = "name: \"loja-web\"\n"
        + "services:\n"
        + "  app:\n"
        + "    image: node:22-alpine\n"
        + "    ports:\n"
        + "      - \"3000:3000\"\n"
        + "    networks:\n"
        + "      - default\n"
        + "    restart: unless-stopped\n"
        + "  db:\n"
        + "    image: postgres:17-alpine\n"
        + "    container_name: loja-web-db\n"
        + "    restart: unless-stopped\n";

    private static final String CONFIG = "tunnel: " + LabSimulado.UUID + "\n"
        + "credentials-file: /etc/cloudflared/" + LabSimulado.UUID + ".json\n"
        + "\n"
        + "ingress:\n"
        + "  - hostname: blog.example.com\n"
        + "    service: http://blog:8080\n"
        + "  - service: http_status:404\n";

    @TempDir
    Path tmp;

    private CenarioDeCloudflare c;
    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("galeria");
    private final List<String> paginasAbertas = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        c = new CenarioDeCloudflare(tmp);
        c.cf.contas.clear();
        c.cf.contas.add(new CloudflareFalsa.Conta(CloudflareFalsa.CONTA, CONTA));
        c.cf.zonas.clear();
        c.cf.zonas.add(new CloudflareFalsa.Zona(CloudflareFalsa.ZONA, "example.com", CloudflareFalsa.CONTA, CONTA, "active"));
        c.lab.escrever(LabSimulado.PROJETOS + "/loja-web/docker-compose.yml", COMPOSE);
        c.lab.escrever(LabSimulado.CONFIG, CONFIG);
    }

    @AfterEach
    void desmontar() {
        c.close();
        Visual.tema(Tema.CLARO);
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    // ---------------------------------------------------------------------------------------------
    // Conectar a Cloudflare
    // ---------------------------------------------------------------------------------------------

    private DialogoConectarCloudflare.Motor motorDoConectar() {
        ServicoCloudflare s = c.servico;
        return new DialogoConectarCloudflare.Motor() {
            @Override
            public Optional<ConfiguracaoCloudflare> escolhaAtual() {
                return s.escolha();
            }

            @Override
            public ServicoCloudflare.Descoberta descobrir(char[] token) throws ErroCloudflare {
                return s.descobrir(token);
            }

            @Override
            public List<TunelCf> tuneis(ServicoCloudflare.Descoberta d, String contaId) throws ErroCloudflare {
                return s.tuneisDaConta(d, contaId);
            }

            @Override
            public TunelPorArquivo.Conferencia conferirServidor(String caminho, String container) {
                return s.conferirServidor(caminho, container);
            }

            @Override
            public void salvar(ServicoCloudflare.Descoberta d, ConfiguracaoCloudflare escolha) throws CofreException {
                throw new IllegalStateException("a galeria nunca grava a conexão");
            }

            @Override
            public void abrirPagina(String url) {
                paginasAbertas.add(url);
            }
        };
    }

    private DialogoConectarCloudflare abrirOConectar() {
        Visual.tema(Tema.CLARO);
        return Visual.naEdt(() -> new DialogoConectarCloudflare(motorDoConectar(), avisos, new CopiadorDeTexto(() -> area), () -> { }));
    }

    @Test
    void conectarACloudflareComOTokenMascaradoEAsPermissoes() {
        DialogoConectarCloudflare d = abrirOConectar();
        assertEquals(DialogoConectarCloudflare.Pagina.TOKEN, ed(d::paginaAtual));
        Visual.naEdt(() -> d.campoDoToken().setText("exemplo-nao-e-um-token")); // o campo de senha só pinta bolinhas
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()), "com o token digitado dá para verificar");
        ImagemDeDialogo.gerar(d, "cloudflare-conectar", "Conectar a Cloudflare", "Zona > DNS > Editar", "Zona > Zona > Ler", "Cloudflare Tunnel > Editar", "Zona específica",
            "vale como uma senha do DNS", "nunca vai ao servidor", "Meu perfil > Tokens de API", "Verificar o token", "••••");
        assertTrue(ed(() -> d.campoDoToken().getPassword().length) > 0, "o token continua só no campo de senha");
    }

    @Test
    void configuracaoDoTunelPorArquivoNoServidor() {
        DialogoConectarCloudflare d = abrirOConectar();
        Visual.naEdt(() -> d.campoDoToken().setText(CloudflareFalsa.TOKEN_CERTO)); // o token da Cloudflare FALSA do teste: nunca aparece na imagem
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, () -> "página: " + ed(d::paginaAtual) + " erro=" + ed(d::textoDoErroDoToken), () -> ed(d::paginaAtual) == DialogoConectarCloudflare.Pagina.ESCOLHA);
        Espera.ate(PRAZO, () -> "túneis: " + ed(d::itensDeTunel), () -> ed(d::itensDeTunel).size() >= 2);
        assertTrue(ed(d::itensDeTunel).get(0).contains("gerido por arquivo no servidor"), ed(d::itensDeTunel).toString());
        assertTrue(ed(d::blocoDoArquivoVisivel), "o primeiro túnel é o do arquivo: o bloco do config.yml aparece");
        assertEquals(LabSimulado.CONFIG, ed(() -> d.campoCaminho().getText()));
        Visual.naEdt(() -> d.botaoConferirNoServidor().doClick());
        Espera.ate(PRAZO, () -> "conferência: " + ed(d::textoDaConferencia), () -> ed(d::textoDaConferencia).startsWith("Tudo certo"));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()), "conferido o servidor, dá para salvar");
        ImagemDeDialogo.gerar(d, "cloudflare-tunel-configuracao", "O túnel é gerido por um arquivo no servidor", "config.yml no servidor", LabSimulado.CONFIG,
            "Container do cloudflared", "Conferir no servidor", "Tudo certo", "Salvar e conectar", CONTA, "example.com");
        assertTrue(ed(() -> d.campoDoToken().getPassword().length) == 0, "depois de verificar o token o campo é apagado");
    }

    // ---------------------------------------------------------------------------------------------
    // Adicionar domínio
    // ---------------------------------------------------------------------------------------------

    private static final DialogoAdicionarDominio.Conexao CONEXAO = new DialogoAdicionarDominio.Conexao(CONTA, "example.com", "site", "lab");

    private final class MotorDoDominio implements DialogoAdicionarDominio.Motor {
        @Override
        public String projeto() {
            return "loja-web";
        }

        @Override
        public Optional<DialogoAdicionarDominio.Conexao> conexao() {
            return Optional.of(CONEXAO);
        }

        @Override
        public void conectar(Runnable depois) { }

        @Override
        public List<ComposeComEdge.Servico> servicos() throws FalhaDoProjeto {
            return List.of(new ComposeComEdge.Servico("app", null, false, 3000), new ComposeComEdge.Servico("db", "loja-web-db", false, 5432));
        }

        @Override
        public boolean podeRecriar() {
            return true;
        }
    }

    private DialogoAdicionarDominio abrirOAdicionarDominio() {
        Visual.tema(Tema.CLARO);
        return Visual.naEdt(() -> new DialogoAdicionarDominio(new MotorDoDominio(), null, p -> { }, () -> { }));
    }

    private static void continuar(DialogoAdicionarDominio d) {
        Visual.naEdt(() -> d.botaoContinuar().doClick(0));
    }

    @Test
    void assistenteAdicionarDominioNosPassosDaContaDoEnderecoEDoServico() {
        DialogoAdicionarDominio d = abrirOAdicionarDominio();
        assertEquals("CONTA", ed(d::passoAtual));
        ImagemDeDialogo.gerar(d, "cloudflare-adicionar-dominio-passo-1", "Passo 1 de 5: a conta da Cloudflare", "CONECTADA", "Conta da Cloudflare: " + CONTA, "5. Confirmar");

        continuar(d);
        continuar(d);
        assertEquals("ENDERECO", ed(d::passoAtual));
        Visual.naEdt(() -> d.campoNome().setText("app"));
        assertEquals("Endereço público: https://app.example.com/", ed(d::textoDoEnderecoFinal));
        ImagemDeDialogo.gerar(d, "cloudflare-adicionar-dominio", "Endereço público: https://app.example.com/", "example.com", "3. Endereço");

        continuar(d);
        assertEquals("SERVICO", ed(d::passoAtual));
        Espera.ate(PRAZO, "os serviços do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 2);
        assertEquals("3000", ed(() -> d.campoPorta().getText()));
        assertEquals("loja-web-app", ed(() -> d.campoContainer().getText()));
        ImagemDeDialogo.gerar(d, "cloudflare-adicionar-dominio-servico", "app", "3000", "loja-web-app", "Ver o que será criado", "4. Serviço");
    }

    // ---------------------------------------------------------------------------------------------
    // Publicar na internet
    // ---------------------------------------------------------------------------------------------

    @Test
    void publicarNaInternetComAPreviaDeTudoQueMuda() throws Exception {
        c.conectar(new ConfiguracaoCloudflare(CloudflareFalsa.CONTA, CONTA, CloudflareFalsa.ZONA, "example.com", LabSimulado.UUID, "lab", ConfiguracaoCloudflare.Tipo.ARQUIVO, "lab",
            LabSimulado.CONFIG, "cloudflared", null));
        PublicadorNaInternet pub = c.servico.publicador(p -> { });
        DialogoPublicar.Motor motor = new DialogoPublicar.Motor() {
            @Override
            public String projeto() {
                return "loja-web";
            }

            @Override
            public String zona() {
                return "example.com";
            }

            @Override
            public String prefixo() {
                return "lab";
            }

            @Override
            public boolean podeRecriar() {
                return true;
            }

            @Override
            public List<ComposeComEdge.Servico> servicos() throws FalhaDoProjeto {
                return c.servico.servicosDoProjeto("loja-web");
            }

            @Override
            public PublicadorNaInternet.Previa preparar(PublicadorNaInternet.Pedido pedido) throws FalhaDoProjeto {
                return pub.preparar(pedido);
            }

            @Override
            public PublicadorNaInternet.Resultado executar(PublicadorNaInternet.Previa previa, ObservadorDaEtapa obs, Cancelamento cancelamento) {
                throw new IllegalStateException("a galeria nunca publica");
            }

            @Override
            public TestadorDeUrl.Resultado testarDeNovo(String hostname, Cancelamento cancelamento) {
                throw new IllegalStateException("a galeria nunca testa o endereço");
            }

            @Override
            public void abrirEndereco(String hostname) { }

            @Override
            public void encerrar() {
                pub.close();
            }
        };
        Visual.tema(Tema.CLARO);
        DialogoPublicar d = Visual.naEdt(() -> new DialogoPublicar(motor, avisos, new CopiadorDeTexto(() -> area), () -> { }));
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 2);
        Visual.naEdt(() -> d.caixaDeServicos().setSelectedItem("app"));
        Visual.naEdt(() -> d.campoNome().setText("app"));
        Visual.naEdt(() -> d.botaoConferir().doClick());
        Espera.ate(PRAZO, () -> "prévia: " + ed(d::textoDaSituacao) + " " + ed(d::textoDoBloqueio), () -> ed(() -> d.previa() != null && "app.example.com".equals(d.previa().hostname())));
        assertTrue(ed(() -> d.botaoPublicar().isEnabled()), "com a prévia à vista o botão libera");
        assertTrue(ed(d::textoDoCompose).contains("+     container_name: loja-web-app"), ed(d::textoDoCompose));
        assertTrue(ed(d::textoDoTunel).contains("+   - hostname: app.example.com"), ed(d::textoDoTunel));
        assertFalse(ed(d::textoDosPassos).isEmpty());
        ImagemDeDialogo.gerar(d, "cloudflare-publicar", "Endereço público: https://app.example.com/", "+     container_name: loja-web-app", "+   - hostname: app.example.com",
            "reiniciar SÓ o container cloudflared", "O cloudflared será reiniciado", "app.example.com");
        assertEquals(List.of(), c.cf.escritas(), "a galeria só olha a prévia: nada foi escrito na Cloudflare");
        assertEquals(0, c.lab.reinicios.get(), "o cloudflared não foi reiniciado");
        assertEquals(COMPOSE, c.lab.ler(LabSimulado.PROJETOS + "/loja-web/docker-compose.yml"));
        assertEquals(CONFIG, c.lab.ler(LabSimulado.CONFIG));
    }
}
