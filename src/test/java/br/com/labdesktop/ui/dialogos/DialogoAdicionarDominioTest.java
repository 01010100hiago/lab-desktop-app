package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.cloudflare.ComposeComEdge;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * "Adicionar domínio": o assistente em cinco passos curtos (conta, domínio, endereço, serviço e a confirmação, que é o diálogo de publicar). Os quatro primeiros só perguntam; sem Cloudflare
 * conectada o primeiro leva ao "Conectar" e volta; o Voltar existe em todos menos no primeiro; Cancelar não entrega nada. PNGs de cada passo nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class DialogoAdicionarDominioTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private static final DialogoAdicionarDominio.Conexao CONEXAO = new DialogoAdicionarDominio.Conexao("Conta de Teste", "example.com", "site", "lab");

    /** O motor de mentira: conecta quando o teste manda e lê os serviços que o teste põe. */
    private static final class Motor implements DialogoAdicionarDominio.Motor {
        volatile boolean conectada = true;
        volatile List<ComposeComEdge.Servico> servicos = List.of(new ComposeComEdge.Servico("app", null, false, 3000), new ComposeComEdge.Servico("db", "demo-db", false, 5432));
        volatile FalhaDoProjeto falha;
        volatile boolean podeRecriar = true;
        final List<Runnable> aoConectar = new ArrayList<>();
        int leituras;

        @Override
        public String projeto() {
            return "demo";
        }

        @Override
        public Optional<DialogoAdicionarDominio.Conexao> conexao() {
            return conectada ? Optional.of(CONEXAO) : Optional.empty();
        }

        @Override
        public void conectar(Runnable depois) {
            aoConectar.add(depois);
        }

        @Override
        public List<ComposeComEdge.Servico> servicos() throws FalhaDoProjeto {
            leituras++;
            if (falha != null) {
                throw falha;
            }
            return servicos;
        }

        @Override
        public boolean podeRecriar() {
            return podeRecriar;
        }
    }

    final Motor motor = new Motor();
    final AtomicReference<DialogoPublicar.Predefinicao> entregue = new AtomicReference<>();
    final List<String> cancelamentos = new ArrayList<>();

    @AfterEach
    void voltar() {
        Visual.tema(Tema.CLARO);
    }

    private DialogoAdicionarDominio abrir(DialogoPublicar.Predefinicao inicial) {
        Visual.tema(Tema.CLARO);
        return Visual.naEdt(() -> new DialogoAdicionarDominio(motor, inicial, entregue::set, () -> cancelamentos.add("cancelou")));
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private static void continuar(DialogoAdicionarDominio d) {
        Visual.naEdt(() -> d.botaoContinuar().doClick(0));
    }

    @Test
    void passoUmMostraAContaEOContinuarSoValeConectada() {
        DialogoAdicionarDominio d = abrir(null);
        assertEquals("CONTA", ed(d::passoAtual));
        assertEquals("Passo 1 de 5: a conta da Cloudflare", ed(d::textoDoTituloDoPasso));
        assertEquals("CONECTADA", ed(d::seloDaConta));
        assertEquals("Conta da Cloudflare: Conta de Teste. Túnel: site.", ed(d::textoDaConta));
        assertTrue(ed(() -> d.botaoContinuar().isEnabled()));
        assertFalse(ed(() -> d.botaoVoltar().isVisible()), "o primeiro passo não tem Voltar");
        assertTrue(ed(() -> d.botaoTrocarConta().isVisible()));
        assertFalse(ed(() -> d.botaoConectar().isVisible()));
        assertEquals(List.of("1. Conta", "2. Domínio", "3. Endereço", "4. Serviço", "5. Confirmar"), ed(d::trilha));
        CenarioDeCloudflare.fotografar(d, "dialogo-dominio-1-conta");
    }

    @Test
    void semCloudflareConectadaOPrimeiroPassoLevaAoConectarEVolta() {
        motor.conectada = false;
        DialogoAdicionarDominio d = abrir(null);
        assertEquals("NÃO CONECTADA", ed(d::seloDaConta));
        assertFalse(ed(() -> d.botaoContinuar().isEnabled()), "sem conta não segue");
        assertTrue(ed(() -> d.botaoConectar().isVisible()));
        assertTrue(ed(d::textoDaConta).contains("Nada é escrito na Cloudflare nesta etapa"));
        CenarioDeCloudflare.fotografar(d, "dialogo-dominio-0-sem-conta");
        Visual.naEdt(() -> d.botaoConectar().doClick(0));
        assertEquals(1, motor.aoConectar.size(), "abriu o Conectar a Cloudflare");
        motor.conectada = true; // o usuário salvou o token no diálogo de conectar
        Visual.naEdt(() -> motor.aoConectar.get(0).run());
        assertEquals("CONECTADA", ed(d::seloDaConta));
        assertTrue(ed(() -> d.botaoContinuar().isEnabled()), "voltou do Conectar e pode seguir");
        assertFalse(ed(() -> d.botaoConectar().isVisible()));
    }

    @Test
    void passoDoisMostraODominioETrocarLevaAoConectar() {
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        assertEquals("DOMINIO", ed(d::passoAtual));
        assertEquals("example.com", ed(d::textoDoDominio));
        assertTrue(ed(() -> d.botaoVoltar().isVisible()));
        assertTrue(ed(() -> d.botaoTrocarDominio().isVisible()));
        CenarioDeCloudflare.fotografar(d, "dialogo-dominio-2-dominio");
        Visual.naEdt(() -> d.botaoTrocarDominio().doClick(0));
        assertEquals(1, motor.aoConectar.size());
    }

    @Test
    void depoisDeConectarOuTrocarODominioOAssistenteFicaNoPassoOndeEstava() {
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        assertEquals("DOMINIO", ed(d::passoAtual));
        Visual.naEdt(() -> d.botaoTrocarDominio().doClick(0));
        Visual.naEdt(() -> motor.aoConectar.get(0).run()); // voltou da janela de conectar com a mesma conta
        assertEquals("DOMINIO", ed(d::passoAtual), "voltou do Conectar no mesmo passo");
        assertTrue(ed(() -> d.botaoContinuar().isEnabled()));
        Visual.naEdt(() -> d.botaoTrocarConta().doClick(0));
        Visual.naEdt(() -> motor.aoConectar.get(1).run());
        assertEquals("DOMINIO", ed(d::passoAtual));
        // o que o usuário já tinha digitado no passo 3 não se perde por ter passado pelo Conectar no passo 2
        continuar(d);
        Visual.naEdt(() -> d.campoNome().setText("meu-nome"));
        Visual.naEdt(() -> d.botaoVoltar().doClick(0));
        Visual.naEdt(() -> motor.aoConectar.get(0).run());
        assertEquals("DOMINIO", ed(d::passoAtual));
        continuar(d);
        assertEquals("meu-nome", ed(() -> d.campoNome().getText()));
    }

    @Test
    void contaDesconectadaNaJanelaDeConectarLevaDeVoltaAoPassoUm() {
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        Visual.naEdt(() -> d.botaoTrocarDominio().doClick(0));
        motor.conectada = false; // o usuário desconectou a conta lá
        Visual.naEdt(() -> motor.aoConectar.get(0).run());
        assertEquals("CONTA", ed(d::passoAtual), "sem conta os passos de depois não valem");
        assertFalse(ed(() -> d.botaoContinuar().isEnabled()));
    }

    @Test
    void passoTresValidaONomeEMostraOEnderecoFinal() {
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        continuar(d);
        assertEquals("ENDERECO", ed(d::passoAtual));
        assertEquals("lab-demo", ed(() -> d.campoNome().getText()), "sugestão: <começo>-<projeto>");
        assertEquals("Endereço público: https://lab-demo.example.com/", ed(d::textoDoEnderecoFinal));
        assertTrue(ed(() -> d.botaoContinuar().isEnabled()));
        Visual.naEdt(() -> d.campoNome().setText("Lab Demo"));
        assertFalse(ed(() -> d.botaoContinuar().isEnabled()));
        assertTrue(ed(d::textoDoProblemaDoNome).contains("Sem espaços"), ed(d::textoDoProblemaDoNome));
        assertEquals("", ed(d::textoDoEnderecoFinal), "endereço inválido não é mostrado como se valesse");
        Visual.naEdt(() -> d.campoNome().setText("a.b"));
        assertTrue(ed(d::textoDoProblemaDoNome).contains("Só um nível"), ed(d::textoDoProblemaDoNome));
        Visual.naEdt(() -> d.campoNome().setText("meu-site"));
        assertTrue(ed(() -> d.botaoContinuar().isEnabled()));
        assertEquals("Endereço público: https://meu-site.example.com/", ed(d::textoDoEnderecoFinal));
        CenarioDeCloudflare.fotografar(d, "dialogo-dominio-3-endereco");
    }

    @Test
    void passoQuatroLeOsServicosESugerePortaEContainer() {
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        continuar(d);
        continuar(d);
        assertEquals("SERVICO", ed(d::passoAtual));
        Espera.ate(PRAZO, "os serviços chegaram", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 2);
        assertEquals("app", ed(() -> d.caixaDeServicos().getSelectedItem()));
        assertEquals("3000", ed(() -> d.campoPorta().getText()));
        assertEquals("demo-app", ed(() -> d.campoContainer().getText()));
        assertTrue(ed(() -> d.campoContainer().isEnabled()));
        assertTrue(ed(() -> d.caixaDeRecriar().isVisible()));
        assertEquals("Ver o que será criado", ed(() -> d.botaoContinuar().getText()));
        assertTrue(ed(() -> d.botaoContinuar().isEnabled()));
        CenarioDeCloudflare.fotografar(d, "dialogo-dominio-4-servico");
        Visual.naEdt(() -> d.caixaDeServicos().setSelectedItem("db"));
        assertEquals("5432", ed(() -> d.campoPorta().getText()));
        assertEquals("demo-db", ed(() -> d.campoContainer().getText()), "o container_name que o compose já tem vale");
        assertFalse(ed(() -> d.campoContainer().isEnabled()));
    }

    @Test
    void portaOuContainerInvalidosDesligamOContinuar() {
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        continuar(d);
        continuar(d);
        Espera.ate(PRAZO, "serviços", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 2);
        Visual.naEdt(() -> d.campoPorta().setText("99999"));
        assertFalse(ed(() -> d.botaoContinuar().isEnabled()));
        assertTrue(ed(d::textoDoProblemaDoServico).contains("A porta é um número de 1 a 65535"));
        Visual.naEdt(() -> d.campoPorta().setText("8080"));
        Visual.naEdt(() -> d.campoContainer().setText("Nome Ruim"));
        assertFalse(ed(() -> d.botaoContinuar().isEnabled()));
        assertTrue(ed(d::textoDoProblemaDoServico).contains("Nome do container"));
        Visual.naEdt(() -> d.campoContainer().setText("meu-app"));
        assertTrue(ed(() -> d.botaoContinuar().isEnabled()));
    }

    @Test
    void doQuartoPassoSegueParaOUltimoEntregandoOQueFoiEscolhidoEFechando() {
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        continuar(d);
        Visual.naEdt(() -> d.campoNome().setText("meu-site"));
        continuar(d);
        Espera.ate(PRAZO, "serviços", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 2);
        Visual.naEdt(() -> d.campoPorta().setText("8080"));
        Visual.naEdt(() -> d.caixaDeRecriar().setSelected(false));
        continuar(d);
        DialogoPublicar.Predefinicao p = entregue.get();
        assertEquals(new DialogoPublicar.Predefinicao("app", 8080, "demo-app", "meu-site", false), p);
        assertTrue(ed(d::fechou));
        assertEquals(List.of(), cancelamentos, "entregar não é cancelar");
    }

    @Test
    void voltarAndaUmPassoParaTrasEGuardaOQueFoiDigitado() {
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        continuar(d);
        Visual.naEdt(() -> d.campoNome().setText("outro-nome"));
        continuar(d);
        assertEquals("SERVICO", ed(d::passoAtual));
        Visual.naEdt(() -> d.botaoVoltar().doClick(0));
        assertEquals("ENDERECO", ed(d::passoAtual));
        assertEquals("outro-nome", ed(() -> d.campoNome().getText()));
        Visual.naEdt(() -> d.botaoVoltar().doClick(0));
        Visual.naEdt(() -> d.botaoVoltar().doClick(0));
        assertEquals("CONTA", ed(d::passoAtual));
        assertFalse(ed(() -> d.botaoVoltar().isVisible()));
    }

    @Test
    void cancelarNaoEntregaNadaEAvisaQuemAbriu() {
        DialogoAdicionarDominio d = abrir(null);
        Visual.naEdt(() -> d.botaoCancelar().doClick(0));
        assertTrue(ed(d::fechou));
        assertNull(entregue.get());
        assertEquals(List.of("cancelou"), cancelamentos);
    }

    @Test
    void oVoltarDoUltimoPassoReabreNoQuartoComTudoPreenchido() {
        DialogoAdicionarDominio d = abrir(new DialogoPublicar.Predefinicao("db", 5432, "demo-db", "meu-banco", false));
        Espera.ate(PRAZO, "serviços", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 2);
        assertEquals("SERVICO", ed(d::passoAtual), "nasce no passo 4");
        assertEquals("db", ed(() -> d.caixaDeServicos().getSelectedItem()));
        assertEquals("5432", ed(() -> d.campoPorta().getText()));
        assertEquals("demo-db", ed(() -> d.campoContainer().getText()));
        assertFalse(ed(() -> d.caixaDeRecriar().isSelected()));
        Visual.naEdt(() -> d.botaoVoltar().doClick(0));
        assertEquals("meu-banco", ed(() -> d.campoNome().getText()), "o nome do passo 3 também voltou");
    }

    @Test
    void falhaAoLerOCompose_MostraAFraseENaoSegue() {
        motor.falha = new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O docker-compose.yml deste projeto não existe.");
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        continuar(d);
        continuar(d);
        Espera.ate(PRAZO, () -> "erro", () -> !ed(d::textoDoProblemaDoServico).isEmpty());
        assertEquals("O docker-compose.yml deste projeto não existe.", ed(d::textoDoProblemaDoServico));
        assertFalse(ed(() -> d.botaoContinuar().isEnabled()));
        CenarioDeCloudflare.fotografar(d, "dialogo-dominio-4b-sem-compose");
    }

    @Test
    void semServicosNoComposeDizOQueFazer() {
        motor.servicos = List.of();
        DialogoAdicionarDominio d = abrir(null);
        continuar(d);
        continuar(d);
        continuar(d);
        Espera.ate(PRAZO, () -> "frase", () -> ed(d::textoDoProblemaDoServico).contains("Não achei serviços"));
        assertFalse(ed(() -> d.botaoContinuar().isEnabled()));
    }

    @Test
    void servidorSemComoRecriarEscondeACaixaDeRecriar() {
        motor.podeRecriar = false;
        DialogoAdicionarDominio d = abrir(null);
        assertFalse(ed(() -> d.caixaDeRecriar().isVisible()));
    }
}
