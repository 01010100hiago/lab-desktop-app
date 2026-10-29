package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.ResultadoDaInstalacao.Desfecho;
import br.com.labdesktop.instalador.ResultadoDaVerificacao.Estado;
import br.com.labdesktop.instalador.ResultadoDaVerificacao.Item;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.teste.ServidorSimulado;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A camada mais funda da proteção: o instalador do agente e o do Git recusam ANTES de qualquer escrita quando o servidor diz que não pode (protegido,
 * ou um agente do lab, de fora, responde), e, sem recusa, fazem tudo como antes.
 */
class InstaladorComProtecaoTest {

    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1});
    private static final String RECUSA = "Este servidor é protegido: o app não instala, atualiza nem remove nada aqui. Para mudar, abra Editar servidor.";

    private final ParametrosDaInstalacao p = ParametrosDaInstalacao.padrao(7000);
    private final ServidorSimulado servidor = new ServidorSimulado(p, PACOTE);
    private final GanchosFalsos ganchos = new GanchosFalsos();
    private final ObservadorGravado obs = new ObservadorGravado();
    private final InstaladorDeAgente instalador = new InstaladorDeAgente(servidor, p, PACOTE, ganchos).comSonda(Duration.ofMillis(150), Duration.ofMillis(10));

    // ---------------------------------------------------------------------------------------------
    // O instalador do agente
    // ---------------------------------------------------------------------------------------------

    @Test
    void aConferenciaMostraOProblemaSemComandoEBloqueiaOInstalar() throws Exception {
        servidor.noGrupoDocker = true;
        ganchos.recusa = Optional.of(RECUSA);
        RelatorioDeVerificacao r = instalador.verificar(ModoDaInstalacao.INSTALAR);

        assertTrue(r.bloqueado());
        ResultadoDaVerificacao problema = r.de(Item.CONTAINER);
        assertEquals(Estado.PROBLEMA, problema.estado());
        assertEquals(RECUSA, problema.frase());
        assertNull(problema.comandoSugerido(), "não há o que o usuário rodar: o app não instala aqui");
        assertEquals(List.of(problema), r.problemas().stream().filter(x -> x.item() == Item.CONTAINER).toList());

        servidor.limparRegistro();
        ResultadoDaInstalacao feito = instalador.instalar(obs, Cancelamento.NUNCA);
        assertEquals(Desfecho.RECUSADA, feito.desfecho());
        assertTrue(servidor.escritas().isEmpty(), "nada foi escrito: " + servidor.escritas());
        assertTrue(servidor.envios.isEmpty());
    }

    @Test
    void semRecusaAConferenciaEOInstalarSaoOsDeSempre() throws Exception {
        servidor.noGrupoDocker = true;
        RelatorioDeVerificacao r = instalador.verificar(ModoDaInstalacao.INSTALAR);
        assertFalse(r.bloqueado(), r.problemas().toString());
        assertEquals(Estado.OK, r.de(Item.CONTAINER).estado());
        ResultadoDaInstalacao feito = instalador.instalar(obs, Cancelamento.NUNCA);
        assertEquals(Desfecho.CONCLUIDA, feito.desfecho(), feito.erro());
        assertFalse(servidor.escritas().isEmpty());
        assertTrue(ganchos.consultasDeRecusa >= 2, "perguntou ao servidor na conferência e de novo antes de escrever");
    }

    @Test
    void seOAgenteDeForaAparecerDepoisDaConferenciaOInstalarRecusaAntesDeEscrever() throws Exception {
        servidor.noGrupoDocker = true;
        assertFalse(instalador.verificar(ModoDaInstalacao.INSTALAR).bloqueado());
        ganchos.recusa = Optional.of("Este servidor já tem o agente do lab, instalado fora do app: o app não instala, atualiza nem remove nada aqui.");
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.RECUSADA, r.desfecho());
        assertTrue(r.erro().contains("agente do lab"), r.erro());
        assertTrue(servidor.escritas().isEmpty(), "nada foi escrito: " + servidor.escritas());
        assertTrue(servidor.envios.isEmpty());
        assertEquals(List.of(), ganchos.chamadas, "nem a sonda nem o token");
    }

    @Test
    void removerRecusaSemTocarEmNada() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = p.container();
        servidor.rotuloDoContainer = PACOTE.versao();
        ganchos.recusa = Optional.of(RECUSA);
        servidor.limparRegistro();

        ResultadoDaRemocao r = instalador.remover(obs, Cancelamento.NUNCA);

        assertFalse(r.removido());
        assertTrue(r.recusada());
        assertEquals(List.of(RECUSA), r.avisos());
        assertTrue(servidor.comandos.isEmpty(), "nem uma leitura: " + servidor.comandos);
        assertEquals(p.container(), servidor.containerNome, "o container continua lá");
    }

    @Test
    void atualizarTambemRecusa() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = p.container();
        servidor.rotuloDoContainer = "0.0.9-velha";
        ganchos.recusa = Optional.of(RECUSA);
        RelatorioDeVerificacao r = instalador.verificar(ModoDaInstalacao.ATUALIZAR);
        assertEquals(Estado.PROBLEMA, r.de(Item.CONTAINER).estado());
        servidor.limparRegistro();
        assertEquals(Desfecho.RECUSADA, instalador.instalar(obs, Cancelamento.NUNCA).desfecho());
        assertTrue(servidor.escritas().isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // O instalador do Git
    // ---------------------------------------------------------------------------------------------

    /** Servidor mínimo: git ausente, root, apt-get; anota o que rodou. */
    private static final class ServidorDeGit implements ProvisionadorRemoto.Executor {
        final List<String> linhas = new ArrayList<>();
        boolean gitInstalado;

        @Override
        public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException {
            linhas.add(comando);
            if (comando.equals("git --version")) {
                return gitInstalado ? ok("git version 2.43.0\n") : new ResultadoExec(127, new byte[0], "sh: git: command not found".getBytes(StandardCharsets.UTF_8), false);
            }
            if (comando.equals("cat /etc/os-release")) {
                return ok("PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\n");
            }
            if (comando.equals(InstaladorDeGit.lerGerenciadores())) {
                return ok("gerenciador=apt-get\n");
            }
            if (comando.equals(InstaladorDeGit.lerUid())) {
                return ok("0\n");
            }
            gitInstalado = true; // qualquer comando de instalar "instala" o git
            return ok("");
        }

        private static ResultadoExec ok(String saida) {
            return new ResultadoExec(0, saida.getBytes(StandardCharsets.UTF_8), new byte[0], false);
        }

        long escritas() {
            return linhas.stream().filter(l -> l.contains("install -y git") || l.endsWith(" update")).count();
        }
    }

    @Test
    void oGitProtegidoNaoInstalaMasAindaConfere() throws Exception {
        ServidorDeGit s = new ServidorDeGit();
        InstaladorDeGit git = new InstaladorDeGit(s).comProtecao(() -> Optional.of(RECUSA));
        InstaladorDeGit.Previa previa = git.conferir();
        assertEquals(InstaladorDeGit.Situacao.PODE_INSTALAR, previa.situacao(), "conferir é leitura e continua valendo");
        assertFalse(git.conferirOGit().instalado());

        List<String> passos = new ArrayList<>();
        InstaladorDeGit.Resultado r = git.instalar(previa, passos::add, new br.com.labdesktop.arquivos.Cancelamento());

        assertFalse(r.instalou());
        assertEquals(RECUSA, r.mensagem());
        assertEquals(0, s.escritas(), "nenhum comando de instalar rodou: " + s.linhas);
        assertTrue(passos.isEmpty());
        assertFalse(s.gitInstalado);
    }

    @Test
    void oGitSemProtecaoInstalaComoSempre() throws Exception {
        ServidorDeGit s = new ServidorDeGit();
        InstaladorDeGit git = new InstaladorDeGit(s).comProtecao(Optional::empty);
        InstaladorDeGit.Resultado r = git.instalar(git.conferir(), passo -> { }, new br.com.labdesktop.arquivos.Cancelamento());
        assertTrue(r.instalou(), r.mensagem());
        assertTrue(s.escritas() >= 2, s.linhas.toString());

        ServidorDeGit s2 = new ServidorDeGit();
        InstaladorDeGit padrao = new InstaladorDeGit(s2); // sem chamar comProtecao: o de antes
        assertTrue(padrao.instalar(padrao.conferir(), passo -> { }, new br.com.labdesktop.arquivos.Cancelamento()).instalou());
    }

    @Test
    void aProtecaoDoGitEhLidaACadaTentativa() throws Exception {
        ServidorDeGit s = new ServidorDeGit();
        boolean[] protegido = {false};
        InstaladorDeGit git = new InstaladorDeGit(s).comProtecao(() -> protegido[0] ? Optional.of(RECUSA) : Optional.empty());
        InstaladorDeGit.Previa previa = git.conferir();
        protegido[0] = true; // o usuário protegeu entre a conferência e o clique
        assertFalse(git.instalar(previa, passo -> { }, new br.com.labdesktop.arquivos.Cancelamento()).instalou());
        assertEquals(0, s.escritas());
    }
}
