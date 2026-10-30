package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.teste.ShellDeTeste;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Encerrar e forçar com o {@code sh} e o {@code ps} de VERDADE sobre processos de verdade (um {@code sleep} aberto pelo teste): o PID reaproveitado
 * NÃO é morto, SIGTERM e depois SIGKILL, o processo de outro usuário, o que já saiu.
 */
class ControleRemotoComShellTest {

    @TempDir
    Path tmp;

    private ProcessosDeVerdade processos;
    private ShellDeTeste shell;
    private final String eu = System.getProperty("user.name");
    private final List<String> etapas = new ArrayList<>();
    private final List<Integer> espera = new ArrayList<>();

    @BeforeEach
    void montar() {
        assumeTrue(ShellDeTeste.temShell(), "precisa de /bin/sh e do ps");
        processos = new ProcessosDeVerdade();
        shell = new ShellDeTeste(tmp);
    }

    @AfterEach
    void limpar() {
        if (processos != null) {
            processos.close();
        }
    }

    private ControleRemoto controle(int esperaDoTerm, int esperaDoKill) {
        ProvisionadorRemoto.Executor linha = shell;
        return new ControleRemoto(linha, ExecutorDeArgumentos.porSsh(linha), AmbienteDeProcessos.servidor(eu, Set.of()), ms -> { }, esperaDoTerm, esperaDoKill);
    }

    private final ObservadorDeProcesso observador = new ObservadorDeProcesso() {
        @Override
        public void etapa(String texto) {
            etapas.add(texto);
        }

        @Override
        public void aguardando(int passados, int total) {
            espera.add(passados);
        }
    };

    private ProcessoAlvo alvoDe(int pid, String usuario) {
        return new ProcessoAlvo(pid, "sleep", usuario, null, null);
    }

    @Test
    void leAIdentidadeRealComAHoraDeInicio() throws Exception {
        int pid = processos.abrirEducado();
        Previa p = controle(2, 1).preparar(alvoDe(pid, eu));
        assertEquals(pid, p.identidade().pid());
        assertTrue(p.identidade().inicio().matches("\\w{3} \\w{3} \\d{1,2} \\d\\d:\\d\\d:\\d\\d \\d{4}"), p.identidade().inicio());
        assertEquals(Via.PROCESSO, p.oferta().via());
        assertEquals(Oferta.Situacao.LIBERADO, p.oferta().situacao());
    }

    @Test
    void sigtermEncerraEOProgressoAparece() throws Exception {
        int pid = processos.abrirEducado();
        ControleRemoto c = controle(5, 2);
        Previa p = c.preparar(alvoDe(pid, eu));
        Resultado r = c.encerrar(p, observador, new Cancelamento());
        assertEquals(Desfecho.ENCERRADO, r.desfecho(), r.mensagem());
        assertFalse(ProcessosDeVerdade.vivo(pid), "o processo realmente saiu");
        assertTrue(etapas.get(0).contains("SIGTERM"), etapas.toString());
        assertFalse(espera.isEmpty(), "o app mostrou a espera");
    }

    @Test
    void processoQueIgnoraSigtermFicaRodandoEOSigkillSoSaiPorOutroPedido() throws Exception {
        int pid = processos.abrirTeimoso();
        ControleRemoto c = controle(3, 2);
        Previa p = c.preparar(alvoDe(pid, eu));
        Resultado educado = c.encerrar(p, observador, new Cancelamento());
        assertEquals(Desfecho.AINDA_RODANDO, educado.desfecho(), educado.mensagem());
        assertTrue(ProcessosDeVerdade.vivo(pid), "o SIGTERM não matou: o app NÃO forçou sozinho");
        assertEquals(List.of(1, 2, 3), espera);

        Resultado forcado = c.forcar(p, observador, new Cancelamento());
        assertEquals(Desfecho.ENCERRADO, forcado.desfecho(), forcado.mensagem());
        assertFalse(ProcessosDeVerdade.vivo(pid));
        assertTrue(shell.recebidos.stream().anyMatch(l -> l.contains("kill -TERM")), "primeiro SIGTERM");
        assertTrue(shell.recebidos.stream().anyMatch(l -> l.contains("kill -KILL")), "e só depois SIGKILL");
        assertTrue(shell.recebidos.stream().noneMatch(l -> l.contains("sudo")));
    }

    @Test
    void pidReaproveitadoNaoEMorto() throws Exception {
        int a = processos.abrirEducado();
        int b = processos.abrirEducado();
        ControleRemoto c = controle(2, 1);
        Previa deA = c.preparar(alvoDe(a, eu));
        // o número de A "passou" a ser de B: o que o app leu de A (hora de início, nome) já não bate com o que há agora no número
        IdentidadeDoProcesso idB = c.preparar(alvoDe(b, eu)).identidade();
        IdentidadeDoProcesso falsa = new IdentidadeDoProcesso(b, idB.uid(), "Mon Jan 1 00:00:00 2001", idB.nome());
        Previa reaproveitado = new Previa(alvoDe(b, eu), deA.oferta(), falsa, null);
        Resultado r = c.encerrar(reaproveitado, observador, new Cancelamento());
        assertEquals(Desfecho.MUDOU, r.desfecho(), r.mensagem());
        assertTrue(ProcessosDeVerdade.vivo(b), "o processo que ficou com o número NÃO foi morto");
        assertTrue(r.mensagem().contains("Nada foi enviado"));
        Resultado f = c.forcar(reaproveitado, observador, new Cancelamento());
        assertEquals(Desfecho.FALHOU, f.desfecho(), "o à força nem vale sem o pedido educado ter saído por esta prévia");
        assertTrue(ProcessosDeVerdade.vivo(b), "nem à força");
    }

    @Test
    void nomeDiferenteDaListaNaHoraDeAbrirEMudou() throws Exception {
        int pid = processos.abrirEducado();
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class,
            () -> controle(2, 1).preparar(new ProcessoAlvo(pid, "node", eu, null, null)));
        assertEquals(Desfecho.MUDOU, f.desfecho());
        assertTrue(ProcessosDeVerdade.vivo(pid));
    }

    @Test
    void processoQueJaSaiuNaoEEnviadoNada() throws Exception {
        int pid = processos.abrirEducado();
        ControleRemoto c = controle(2, 1);
        Previa p = c.preparar(alvoDe(pid, eu));
        ProcessosDeVerdade.matarAMao(pid);
        Resultado r = c.encerrar(p, observador, new Cancelamento());
        assertEquals(Desfecho.JA_SAIU, r.desfecho(), r.mensagem());
        assertTrue(r.desfecho().deuCerto());
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class, () -> c.preparar(alvoDe(pid, eu)));
        assertEquals(Desfecho.JA_SAIU, f.desfecho());
    }

    @Test
    void processoDeOutroUsuarioDaSemPermissaoComOComandoParaODono() throws Exception {
        assumeFalse("root".equals(eu), "como root o kill valeria de verdade");
        // um processo de root com nome "comum" (letras, números, ponto, traço, sublinhado) e de root DE VERDADE (uid e ruid 0: o `sudo` aparece com uid efetivo 0 mas é do usuário, e o kill passaria): o app SÓ monta o comando para o dono com um nome assim (ComandoParaODono.NOME). Num Linux
        // os primeiros processos de root acima de 100 são threads do kernel ("kworker/R-charg"), com barra no nome: para elas o app recusa montar o comando (e é o certo), então não servem aqui.
        Process ps = new ProcessBuilder("/bin/sh", "-c", "ps -axo pid=,uid=,ruid=,comm= | awk '$2==0 && $3==0 && $1>100 && $4 ~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/ {print $1; exit}'").start();
        String achado = new String(ps.getInputStream().readAllBytes()).strip();
        ps.waitFor();
        assumeTrue(!achado.isEmpty(), "não há processo de root para provar a recusa");
        int pidDeRoot = Integer.parseInt(achado);
        ControleRemoto c = controle(2, 1);
        IdentidadeDoProcesso lida = IdentidadeDoProcesso.deSaidaDoPs(
            new String(new ProcessBuilder("/bin/sh", "-c", "LC_ALL=C ps -o pid=,uid=,lstart=,comm= -p " + pidDeRoot).start().getInputStream().readAllBytes()), pidDeRoot).orElseThrow();
        ProcessoAlvo alvo = new ProcessoAlvo(pidDeRoot, ProcessoAlvo.nomeCurto(lida.nome()), "root", null, null);
        Oferta oferta = AvaliadorDeProcessos.avaliar(new ProcessoAlvo(pidDeRoot, "programa-qualquer", "root", null, null), c.ambiente());
        assertEquals(Oferta.Situacao.SEM_PERMISSAO, oferta.situacao());
        Resultado r = c.encerrar(new Previa(alvo, oferta, lida, null), observador, new Cancelamento());
        assertEquals(Desfecho.SEM_PERMISSAO, r.desfecho(), r.mensagem());
        assertEquals("ps -o pid,user,lstart,comm -p " + pidDeRoot + "\nps -o comm= -p " + pidDeRoot + " | grep -Eq '" + regexDe(lida.nome()) + "' && sudo kill -TERM " + pidDeRoot, r.comandoParaODono());
        assertTrue(r.mensagem().contains("não usa sudo nem digita senha"), r.mensagem());
        assertNotEquals("", r.comandoParaODono());
    }

    /** O trecho do grep para este nome (cortado em 15 letras e sem {@code $} se passou disso). */
    private static String regexDe(String nome) {
        String curto = ProcessoAlvo.nomeCurto(nome);
        boolean cortado = curto.length() > 15;
        return "(^|/)" + (cortado ? curto.substring(0, 15) : curto).replace(".", "\\.") + (cortado ? "" : "$");
    }

    @Test
    void cancelarDuranteAEsperaPara() throws Exception {
        int pid = processos.abrirTeimoso();
        Cancelamento cancelamento = new Cancelamento();
        ProvisionadorRemoto.Executor linha = shell;
        ControleRemoto c = new ControleRemoto(linha, ExecutorDeArgumentos.porSsh(linha), AmbienteDeProcessos.servidor(eu, Set.of()), ms -> cancelamento.cancelar(), 5, 2);
        Previa p = c.preparar(alvoDe(pid, eu));
        Resultado r = c.encerrar(p, observador, cancelamento);
        assertEquals(Desfecho.CANCELADO, r.desfecho());
        assertTrue(ProcessosDeVerdade.vivo(pid), "o app só parou de esperar; o pedido já tinha saído");
    }

    @Test
    void servidorSemPsComHoraDeInicioNaoEnviaNada() throws Exception {
        int pid = processos.abrirEducado();
        ProvisionadorRemoto.Executor semLstart = (comando, stdin, prazo) -> comando.contains("lstart")
            ? new ResultadoExec(1, new byte[0], "ps: unknown option -- lstart".getBytes(), false) : shell.executar(comando, stdin, prazo);
        ControleRemoto c = new ControleRemoto(semLstart, ExecutorDeArgumentos.porSsh(semLstart), AmbienteDeProcessos.servidor(eu, Set.of()), ms -> { }, 2, 1);
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class, () -> c.preparar(alvoDe(pid, eu)));
        assertEquals(Desfecho.SEM_CONFERENCIA, f.desfecho());
        assertTrue(ProcessosDeVerdade.vivo(pid));
        assertEquals("ps -o pid,user,lstart,comm -p " + pid + "\nps -o comm= -p " + pid + " | grep -Eq '(^|/)sleep$' && sudo kill -TERM " + pid, f.comandoParaODono());
    }

    @Test
    void protegidoNemChegaALerNada() {
        ControleRemoto c = controle(2, 1);
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class, () -> c.preparar(new ProcessoAlvo(1, "systemd", eu, null, null)));
        assertEquals(Desfecho.PROTEGIDO, f.desfecho());
        assertTrue(shell.recebidos.isEmpty(), "nada foi enviado ao servidor");
    }

    @Test
    void forcarSemOPedidoEducadoTerSaidoPorEstaPreviaERecusado() throws Exception {
        int pid = processos.abrirTeimoso();
        ControleRemoto c = controle(2, 1);
        Previa p = c.preparar(alvoDe(pid, eu));
        assertFalse(p.pedidoEducadoEnviado());
        Resultado r = c.forcar(p, observador, new Cancelamento());
        assertEquals(Desfecho.FALHOU, r.desfecho(), r.mensagem());
        assertTrue(r.mensagem().contains("Primeiro peça"), r.mensagem());
        assertTrue(ProcessosDeVerdade.vivo(pid), "nada foi enviado");
        assertTrue(shell.recebidos.stream().noneMatch(l -> l.contains("kill -KILL")), "o servidor nem recebeu o kill");
        // com o pedido educado saído por ESTA prévia, o à força passa
        assertEquals(Desfecho.AINDA_RODANDO, c.encerrar(p, observador, new Cancelamento()).desfecho());
        assertTrue(p.pedidoEducadoEnviado());
        assertEquals(Desfecho.ENCERRADO, c.forcar(p, observador, new Cancelamento()).desfecho());
        // outra prévia do mesmo processo não herda o pedido
        int outro = processos.abrirTeimoso();
        Previa nova = c.preparar(alvoDe(outro, eu));
        assertEquals(Desfecho.FALHOU, c.forcar(nova, observador, new Cancelamento()).desfecho());
        assertTrue(ProcessosDeVerdade.vivo(outro));
    }

    /** O servidor imprime banner e mensagens soltas do .bashrc em volta do que o app pediu. */
    private ControleRemoto controleComRuido(int esperaDoTerm) {
        ProvisionadorRemoto.Executor ruidoso = (comando, stdin, prazo) -> {
            ResultadoExec r = shell.executar(comando, stdin, prazo);
            return new ResultadoExec(r.status(), ("Bem-vindo ao servidor!\nenviado\nja-saiu\n" + r.stdout() + "Até logo\nmudou\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), r.stderr().getBytes(), false);
        };
        return new ControleRemoto(ruidoso, ExecutorDeArgumentos.porSsh(ruidoso), AmbienteDeProcessos.servidor(eu, Set.of()), ms -> { }, esperaDoTerm, 1);
    }

    @Test
    void ruidoDoBashrcEmVoltaDaSaidaNaoConfundeOAppNemEsconde() throws Exception {
        int pid = processos.abrirEducado();
        ControleRemoto c = controleComRuido(5);
        Previa p = c.preparar(alvoDe(pid, eu));
        Resultado r = c.encerrar(p, observador, new Cancelamento());
        assertEquals(Desfecho.ENCERRADO, r.desfecho(), r.mensagem());
        assertFalse(ProcessosDeVerdade.vivo(pid));
    }

    @Test
    void ruidoSemAMarcaNuncaViraResposta() throws Exception {
        int pid = processos.abrirEducado();
        ProvisionadorRemoto.Executor so = (comando, stdin, prazo) -> comando.startsWith("sh -c")
            ? new ResultadoExec(0, "enviado\n".getBytes(), new byte[0], false) : shell.executar(comando, stdin, prazo);
        ControleRemoto c = new ControleRemoto(so, ExecutorDeArgumentos.porSsh(so), AmbienteDeProcessos.servidor(eu, Set.of()), ms -> { }, 2, 1);
        Previa p = c.preparar(alvoDe(pid, eu));
        Resultado r = c.encerrar(p, observador, new Cancelamento());
        assertEquals(Desfecho.FALHOU, r.desfecho(), "a palavra solta 'enviado' não é a resposta do comando");
        assertTrue(ProcessosDeVerdade.vivo(pid));
    }

    @Test
    void processoDeOutroUsuarioEscondidoPeloServidorNaoVaiComoJaSaiu() throws Exception {
        int pid = processos.abrirEducado();
        ControleRemoto c = controle(2, 1);
        Previa real = c.preparar(alvoDe(pid, eu));
        ProcessosDeVerdade.matarAMao(pid); // o ps não o acha mais (como o ps de outro usuário sob hidepid)
        IdentidadeDoProcesso deOutro = new IdentidadeDoProcesso(pid, "999999", real.identidade().inicio(), real.identidade().nome());
        Resultado r = c.encerrar(new Previa(real.alvo(), real.oferta(), deOutro, null), observador, new Cancelamento());
        assertEquals(Desfecho.SEM_CONFERENCIA, r.desfecho(), r.mensagem());
        assertFalse(r.desfecho().deuCerto(), "sem prova de que saiu");
        // o mesmo número, do MESMO usuário, que sumiu: aí sim, já saiu
        Resultado seu = c.encerrar(real, observador, new Cancelamento());
        assertEquals(Desfecho.JA_SAIU, seu.desfecho());
    }

    private ControleRemoto controleComPsDefeituoso(java.util.function.Supplier<ResultadoExec> depoisDoPedido, int esperaDoTerm) {
        java.util.concurrent.atomic.AtomicBoolean pedidoSaiu = new java.util.concurrent.atomic.AtomicBoolean();
        ProvisionadorRemoto.Executor defeituoso = (comando, stdin, prazo) -> {
            if (comando.startsWith("sh -c")) {
                pedidoSaiu.set(true);
                return shell.executar(comando, stdin, prazo);
            }
            if (pedidoSaiu.get() && comando.contains("ps -o")) {
                return depoisDoPedido.get();
            }
            return shell.executar(comando, stdin, prazo);
        };
        return new ControleRemoto(defeituoso, ExecutorDeArgumentos.porSsh(defeituoso), AmbienteDeProcessos.servidor(eu, Set.of()), ms -> { }, esperaDoTerm, 1);
    }

    @Test
    void psIlegivelDepoisDoPedidoNaoVaiComoFechou() throws Exception {
        for (java.util.function.Supplier<ResultadoExec> defeito : List.<java.util.function.Supplier<ResultadoExec>>of(
            () -> new ResultadoExec(0, "ps: coisa estranha que o app não entende\n".getBytes(), new byte[0], false),
            () -> new ResultadoExec(2, new byte[0], "ps: erro interno\n".getBytes(), false),
            () -> new ResultadoExec(127, new byte[0], new byte[0], false),
            () -> new ResultadoExec(0, new byte[0], new byte[0], false))) {
            int pid = processos.abrirTeimoso();
            ControleRemoto c = controleComPsDefeituoso(defeito, 2);
            Previa p = c.preparar(alvoDe(pid, eu));
            Resultado r = c.encerrar(p, observador, new Cancelamento());
            assertEquals(Desfecho.SEM_CONFERENCIA, r.desfecho(), r.mensagem());
            assertFalse(r.desfecho().deuCerto());
            assertTrue(r.mensagem().contains("não conseguiu"), r.mensagem());
        }
    }

    @Test
    void soAConfirmacaoDeVerdadeContaComoFechou() throws Exception {
        int pid = processos.abrirEducado();
        // o ps do servidor diz, com a saída vazia e o status 1, que o número não existe mais: é a confirmação
        ControleRemoto c = controleComPsDefeituoso(() -> new ResultadoExec(1, new byte[0], new byte[0], false), 2);
        Resultado r = c.encerrar(c.preparar(alvoDe(pid, eu)), observador, new Cancelamento());
        assertEquals(Desfecho.ENCERRADO, r.desfecho(), r.mensagem());
    }
}
