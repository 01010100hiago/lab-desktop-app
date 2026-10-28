package br.com.labdesktop.cloudflare;

import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.ComandosDeProjeto;
import br.com.labdesktop.projetos.Diferenca;
import br.com.labdesktop.projetos.EscritorDeCompose;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.projetos.GabaritoDoProjeto;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * "Publicar na internet": dá a um serviço de um projeto um endereço público ({@code lab-demo.example.com}) pelo túnel da Cloudflare. Em DUAS fases, como o resto do app:
 * <ol>
 *   <li>{@link #preparar}: só LÊ (o compose, as regras do túnel, o DNS) e devolve a {@link Previa}: TUDO o que será feito, com a diferença legível de cada arquivo, e o que
 *       impede de seguir, se algo impede. Nada é escrito;</li>
 *   <li>{@link #executar}: só depois do clique de confirmação. Na ordem: (1) compose: {@code container_name} único e a rede {@code edge} (cópia datada, conferência com o
 *       {@code docker compose config}); (2) a regra no túnel (e, no arquivo, a conferência pelo cloudflared e o reinício dele); (3) o registro de DNS (CNAME para
 *       {@code <UUID>.cfargotunnel.com}, com proxy e o comentário {@code lab-desktop:}); (4) opcionalmente recria o projeto para o container entrar na rede; (5) testa o endereço.</li>
 * </ol>
 * <b>Reversão:</b> se um passo de (1) a (4) falha, desfaz SÓ o que esta execução fez, de trás para frente (apaga o DNS que criou, devolve o config.yml da cópia e reinicia o
 * cloudflared, devolve o compose), e diz o que NÃO conseguiu desfazer, em frases para fazer à mão. O teste (5) que não passa NÃO desfaz nada: tudo está no lugar e o motivo
 * aparece (o DNS novo pode levar minutos). Nunca desfaz depois, sem pedido. Cancelar entre os passos também desfaz.
 */
public final class PublicadorNaInternet implements AutoCloseable {

    /** Recria o projeto (o {@code docker compose up -d}) para o container entrar na rede nova. Bloqueia; lança o que deu errado. */
    @FunctionalInterface
    public interface AplicadorNoProjeto {
        void aplicar(String projeto) throws Exception;
    }

    /**
     * O que o usuário escolheu.
     *
     * @param servico      o serviço do compose
     * @param containerName o nome único do container (se o serviço já tem {@code container_name}, vale o dele)
     * @param porta        a porta de DENTRO do container
     * @param rotulo       o nome do endereço sem o domínio ({@code lab-demo})
     */
    public record Pedido(String projeto, String servico, String containerName, int porta, String rotulo, boolean recriarProjeto) { }

    /** A prévia: tudo o que {@link #executar} faria, e o que impede. */
    public record Previa(Pedido pedido, String hostname, String url, String destinoDaRegra, ComposeComEdge.Situacao compose, String diferencaDoCompose, String blocoParaCopiar,
                         String ondeEstaORegistro, String diferencaDoTunel, boolean dnsJaEraDoApp, List<String> passos, List<String> avisos, List<String> bloqueios,
                         // o estado lido, para o executar conferir que nada mudou
                         byte[] bytesDoCompose, ComposeComEdge.Proposta proposta, Tunel.Mudanca mudancaDoTunel) {
        public Previa {
            passos = List.copyOf(passos);
            avisos = List.copyOf(avisos);
            bloqueios = List.copyOf(bloqueios);
        }

        /** Pode publicar? */
        public boolean podePublicar() {
            return bloqueios.isEmpty();
        }

        /** O que impede, numa frase só ({@code null} se nada). */
        public String bloqueio() {
            return bloqueios.isEmpty() ? null : String.join(" ", bloqueios);
        }
    }

    /** Como terminou. */
    public enum Desfecho { PUBLICADO, FALHOU, CANCELADO }

    /**
     * O resultado da execução.
     *
     * @param falha         a frase do que falhou ({@code null} se publicou)
     * @param etapaDaFalha  o passo que falhou
     * @param desfeito      o que a reversão devolveu
     * @param naoConsegui   o que a reversão NÃO conseguiu devolver (o usuário faz à mão)
     * @param copias        as cópias datadas que o app guardou (compose e config.yml)
     */
    public record Resultado(Desfecho desfecho, String hostname, String url, TestadorDeUrl.Resultado teste, String falha, Etapa etapaDaFalha, List<String> desfeito,
                            List<String> naoConsegui, List<String> copias, List<String> avisos) {
        public Resultado {
            desfeito = List.copyOf(desfeito);
            naoConsegui = List.copyOf(naoConsegui);
            copias = List.copyOf(copias);
            avisos = List.copyOf(avisos);
        }
    }

    static final String ARQUIVO_COMPOSE = GabaritoDoProjeto.ARQUIVO_COMPOSE;
    private static final int LIMITE_DO_COMPOSE = 512 * 1024;
    private static final int PERMISSAO_DO_COMPOSE = 0644;
    private static final Duration PRAZO_DA_CONFERENCIA = Duration.ofSeconds(60);
    private static final DateTimeFormatter DATA_DA_COPIA = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AmbienteDeProjetos ambiente;
    private final ApiCloudflare api;
    private final ConfiguracaoCloudflare cfg;
    private final Tunel tunel;
    private final AplicadorNoProjeto aplicador;
    private final TestadorDeUrl testador;
    private final Clock relogio;

    public PublicadorNaInternet(AmbienteDeProjetos ambiente, ApiCloudflare api, ConfiguracaoCloudflare cfg, Tunel tunel, AplicadorNoProjeto aplicador, TestadorDeUrl testador,
                                Clock relogio) {
        this.ambiente = ambiente;
        this.api = api;
        this.cfg = cfg;
        this.tunel = tunel;
        this.aplicador = aplicador;
        this.testador = testador;
        this.relogio = relogio;
    }

    @Override
    public void close() {
        api.close();
    }

    /** Uma sugestão de nome de container: {@code <projeto>-<serviço>}, cortada para caber (até 41 caracteres). */
    public static String sugerirContainer(String projeto, String servico) {
        String s = projeto + "-" + servico;
        if (s.length() > 41) {
            s = s.substring(0, 41);
        }
        s = s.replaceAll("[-_.]+$", "");
        return s.matches("^[a-z0-9][a-z0-9_.-]{0,40}$") ? s : projeto;
    }

    // =====================================================================================================
    // Fase 1: só leitura
    // =====================================================================================================

    /** Lê e calcula. Não escreve nada. @throws FalhaDoProjeto não deu para ler (sem conexão, sem permissão...) */
    public Previa preparar(Pedido p) throws FalhaDoProjeto {
        if (ambiente.local()) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Publicar na internet pede um servidor com o cloudflared (o túnel). Este computador não tem um túnel configurado pelo app.");
        }
        validar(p);
        String hostname = NomeDoEndereco.hostname(p.rotulo(), cfg.zonaNome());
        String servico = NomeDoEndereco.servicoDoTunel(p.containerName(), p.porta());
        List<String> bloqueios = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        List<String> passos = new ArrayList<>();

        // --- o compose ---
        byte[] bytes = null;
        ComposeComEdge.Proposta proposta = null;
        String diferencaDoCompose = "";
        String bloco = "";
        ComposeComEdge.Situacao situacaoDoCompose = ComposeComEdge.Situacao.MANUAL;
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String pasta = CaminhoRemoto.juntar(ambiente.raizDosProjetos(), p.projeto());
            String arquivo = CaminhoRemoto.juntar(pasta, ARQUIVO_COMPOSE);
            if (!c.existe(pasta)) {
                bloqueios.add("A pasta do projeto não existe mais no servidor.");
            } else {
                bytes = lerBytes(c, arquivo);
                if (bytes == null) {
                    bloqueios.add("O projeto não tem docker-compose.yml: adicione um container ao projeto antes de publicar.");
                } else {
                    String texto = decodificar(bytes);
                    if (texto == null) {
                        bloqueios.add("O docker-compose.yml não está em UTF-8: o app não escreve nele. Faça à mão (abaixo) e depois confira de novo.");
                        proposta = ComposeComEdge.propor("services:\n  " + p.servico() + ":\n    image: x\n", p.servico(), p.containerName());
                        bloco = proposta.blocoParaCopiar();
                    } else {
                        proposta = ComposeComEdge.propor(texto, p.servico(), p.containerName());
                        situacaoDoCompose = proposta.situacao();
                        bloco = proposta.blocoParaCopiar();
                        switch (proposta.situacao()) {
                            case PRONTO -> passos.add("docker-compose.yml: o serviço " + p.servico() + " já tem o container_name " + proposta.containerName() + " e a rede edge. Nada a escrever.");
                            case EDITAVEL -> {
                                diferencaDoCompose = Diferenca.legivel(texto, proposta.novoTexto(), 2);
                                passos.add("docker-compose.yml do projeto " + p.projeto() + ": dar o nome " + proposta.containerName() + " ao serviço " + p.servico()
                                    + " e ligá-lo à rede edge (guarda uma cópia datada antes e confere o resultado com docker compose config).");
                            }
                            case MANUAL -> {
                                passos.add("docker-compose.yml: o app NÃO o escreve (" + String.join(" ", proposta.motivos()) + ") — copie o bloco abaixo para o arquivo.");
                                bloqueios.add("Seu docker-compose.yml precisa da mudança abaixo, e o app não reescreve compose do usuário: copie o bloco, cole no arquivo e clique em Conferir de novo. ("
                                    + String.join(" ", proposta.motivos()) + ")");
                            }
                        }
                        if (proposta.situacao() != ComposeComEdge.Situacao.MANUAL && !proposta.containerName().equals(p.containerName())) {
                            avisos.add("O serviço já tem container_name \"" + proposta.containerName() + "\": o app usa esse nome (e não " + p.containerName() + ") na regra do túnel.");
                        }
                    }
                }
            }
        } catch (ErroSftp e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_CONEXAO, "Não consegui ler o docker-compose.yml no servidor: " + e.mensagemSimples(), null, e);
        }
        String nomeFinal = proposta != null && proposta.situacao() != ComposeComEdge.Situacao.MANUAL ? proposta.containerName() : p.containerName();
        String servicoDaRegra = NomeDoEndereco.servicoDoTunel(nomeFinal, p.porta());

        // --- a regra do túnel ---
        Tunel.Mudanca mudanca = tunel.prepararAcrescimo(hostname, servicoDaRegra, "projeto " + p.projeto() + ", " + LocalDate.now(relogio));
        if (mudanca.bloqueio() != null) {
            bloqueios.add(mudanca.bloqueio());
        }
        avisos.addAll(mudanca.avisos());
        if (mudanca.semEfeito()) {
            passos.add("Túnel: a regra " + hostname + " -> " + servicoDaRegra + " já está " + tunel.emOnde() + ".");
        } else {
            passos.add("Túnel: acrescentar " + tunel.emOnde() + " a regra " + hostname + " -> " + servicoDaRegra + ", antes da regra final"
                + (tunel.reiniciaOCloudflared() ? "; guardar uma cópia datada, conferir com o cloudflared e reiniciar SÓ o container " + cfg.containerDoCloudflared() : "") + ".");
        }

        // --- o DNS ---
        boolean dnsDoApp = false;
        String alvo = NomeDoEndereco.alvoDoTunel(cfg.tunelId());
        try {
            for (RegistroDnsCf r : api.registrosDnsComONome(cfg.zonaId(), hostname)) {
                // QUALQUER tipo no mesmo nome conta: um CNAME não convive com TXT, MX ou A no mesmo nome (a Cloudflare recusaria na hora de criar, no meio da publicação)
                java.util.Optional<String> doProjeto = NomeDoEndereco.projetoDoComentario(r.comentario());
                if (r.tipo().equals("CNAME") && r.conteudo().equalsIgnoreCase(alvo) && doProjeto.isPresent()) {
                    if (doProjeto.get().equals(p.projeto())) {
                        dnsDoApp = true;
                    } else {
                        bloqueios.add("O endereço " + hostname + " já é do app, mas do projeto " + doProjeto.get() + " (e não de " + p.projeto() + "): reaproveitá-lo tiraria o endereço de lá. "
                            + "Escolha outro nome, ou remova o endereço antes na aba Cloudflare do servidor.");
                    }
                } else {
                    bloqueios.add("Já existe um registro de DNS chamado " + hostname + " (" + r.tipo() + (r.conteudo().isEmpty() ? "" : " para " + r.conteudo()) + ") que não foi criado por este app. "
                        + "Escolha outro nome para o endereço.");
                }
            }
        } catch (ErroCloudflare e) {
            throw FalhasDaCloudflare.de(e);
        }
        passos.add(dnsDoApp ? "DNS: o registro " + hostname + " já existe, é do app e aponta para este túnel: será reaproveitado."
            : "DNS: criar o registro CNAME " + hostname + " -> " + alvo + " (com o proxy da Cloudflare, marcado como do app).");
        if (p.recriarProjeto()) {
            passos.add("Projeto: recriar o projeto (docker compose up -d) para o container entrar na rede edge. Os containers do projeto que mudaram são recriados.");
        } else if (situacaoDoCompose != ComposeComEdge.Situacao.PRONTO) {
            avisos.add("O projeto NÃO será recriado agora: o container só entra na rede edge quando for recriado (Iniciar o projeto). Até lá o endereço responde com erro.");
        }
        passos.add("Testar " + NomeDoEndereco.urlPublica(hostname) + " (o DNS novo pode levar alguns minutos).");
        return new Previa(p, hostname, NomeDoEndereco.urlPublica(hostname), servicoDaRegra, situacaoDoCompose, diferencaDoCompose, bloco, tunel.emOnde(), mudanca.diferenca(), dnsDoApp, passos,
            avisos, bloqueios, bytes, proposta, mudanca);
    }

    private void validar(Pedido p) throws FalhaDoProjeto {
        if (!ValidadorDeNomes.projetoValido(p.projeto())) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Nome de projeto inválido.");
        }
        if (p.servico() == null || !p.servico().matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$")) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Escolha o serviço do projeto que vai para a internet.");
        }
        if (p.containerName() == null || !p.containerName().matches("^[a-z0-9][a-z0-9_.-]{0,40}$") || !ValidadorDeNomes.containerValido(p.containerName())) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O nome do container usa letras minúsculas, números, ponto, hífen ou sublinhado (até 41 caracteres).");
        }
        if (p.porta() < 1 || p.porta() > 65535) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "A porta vai de 1 a 65535.");
        }
        if (!NomeDoEndereco.valido(p.rotulo())) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, NomeDoEndereco.conferir(p.rotulo()).mensagem().isEmpty() ? "Escreva o nome do endereço." : NomeDoEndereco.conferir(p.rotulo()).mensagem());
        }
    }

    // =====================================================================================================
    // Fase 2: depois do clique de confirmação
    // =====================================================================================================

    private record Desfazer(Etapa etapa, String descricao, Supplier<List<String>> acao) { }

    /**
     * Faz o que a {@link Previa} mostrou. {@code previa.podePublicar()} tem de ser verdadeiro. Bloqueia (rede, SSH, minutos): thread de fundo. Nunca lança por falha do
     * passo: devolve {@link Resultado} com o que aconteceu e o que foi desfeito.
     */
    public Resultado executar(Previa previa, ObservadorDaEtapa obs, Cancelamento cancelamento) {
        if (!previa.podePublicar()) {
            return new Resultado(Desfecho.FALHOU, previa.hostname(), previa.url(), null, previa.bloqueio(), null, List.of(), List.of(), List.of(), previa.avisos());
        }
        api.cancelarQuando(() -> cancelamento != null && cancelamento.cancelado()); // a espera de um 429 não prende o Cancelar
        List<Desfazer> pilha = new ArrayList<>();
        List<String> copias = new ArrayList<>();
        List<String> avisosDaExecucao = new ArrayList<>();
        Etapa atual = Etapa.COMPOSE;
        try {
            // 1. compose
            atual = Etapa.COMPOSE;
            conferir(cancelamento);
            comporNoServidor(previa, obs, pilha, copias, avisosDaExecucao);

            // 2. a regra do túnel (e, no arquivo, a conferência e o reinício)
            atual = Etapa.REGRA;
            conferir(cancelamento);
            Tunel.Aplicada aplicada = previa.mudancaDoTunel().aplicar(obs, cancelamento);
            if (aplicada.copia() != null) {
                copias.add(aplicada.copia());
            }
            pilha.add(new Desfazer(Etapa.REGRA, "a regra no túnel", aplicada::desfazer));

            // 3. o DNS
            atual = Etapa.DNS;
            conferir(cancelamento);
            criarDns(previa, obs, pilha);

            // 4. recriar o projeto
            atual = Etapa.PROJETO;
            conferir(cancelamento);
            if (previa.pedido().recriarProjeto()) {
                obs.etapa(Etapa.PROJETO, ObservadorDaEtapa.Estado.ANDANDO, "Recriando o projeto (pode levar alguns minutos)…");
                try {
                    aplicador.aplicar(previa.pedido().projeto());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.", null, e);
                } catch (Exception e) {
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "Não consegui recriar o projeto: " + TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), 300), null, e);
                }
                obs.etapa(Etapa.PROJETO, ObservadorDaEtapa.Estado.FEITO, "Projeto recriado.");
            } else {
                obs.etapa(Etapa.PROJETO, ObservadorDaEtapa.Estado.PULADO, "O projeto não foi recriado agora: o container entra na rede edge quando for recriado.");
            }
            conferir(cancelamento);
        } catch (FalhaDoProjeto f) {
            obs.etapa(atual, ObservadorDaEtapa.Estado.FALHOU, f.mensagem());
            return reverter(previa, f, atual, pilha, obs, copias, avisosDaExecucao);
        }

        // 5. testar (não desfaz nada se não passar)
        obs.etapa(Etapa.TESTE, ObservadorDaEtapa.Estado.ANDANDO, "Abrindo " + previa.url() + "…");
        TestadorDeUrl.Resultado teste = testador.testar(previa.hostname(), cancelamento);
        obs.etapa(Etapa.TESTE, teste.noAr() || teste.estado() == TestadorDeUrl.Estado.EM_DUVIDA ? ObservadorDaEtapa.Estado.FEITO : ObservadorDaEtapa.Estado.FALHOU, teste.frase());
        return new Resultado(Desfecho.PUBLICADO, previa.hostname(), previa.url(), teste, null, null, List.of(), List.of(), copias, juntar(previa.avisos(), avisosDaExecucao));
    }

    private static void conferir(Cancelamento c) throws FalhaDoProjeto {
        if (c != null && c.cancelado()) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.");
        }
    }

    private static List<String> juntar(List<String> a, List<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    private Resultado reverter(Previa previa, FalhaDoProjeto f, Etapa etapa, List<Desfazer> pilha, ObservadorDaEtapa obs, List<String> copias, List<String> avisosDaExecucao) {
        List<String> desfeito = new ArrayList<>();
        List<String> naoConsegui = new ArrayList<>();
        for (int i = pilha.size() - 1; i >= 0; i--) {
            Desfazer d = pilha.get(i);
            List<String> sobrou;
            try {
                sobrou = d.acao().get();
            } catch (RuntimeException e) {
                sobrou = List.of("Não consegui desfazer " + d.descricao() + ": " + TextoSeguro.limparLinha(Redator.redigir(e.getMessage())));
            }
            if (sobrou.isEmpty()) {
                desfeito.add("Desfiz " + d.descricao() + ".");
                obs.etapa(d.etapa(), ObservadorDaEtapa.Estado.DESFEITO, "Desfeito: " + d.descricao() + ".");
            } else {
                naoConsegui.addAll(sobrou);
            }
        }
        if (etapa == Etapa.PROJETO && previa.pedido().recriarProjeto() && pilha.stream().anyMatch(d -> d.etapa() == Etapa.COMPOSE)) {
            // o compose voltou, mas o "docker compose up" do passo já mexeu nos containers (nome e rede novos): desfazer o arquivo não os recria como eram
            naoConsegui.add("O docker-compose.yml voltou como era, mas os containers do projeto " + previa.pedido().projeto() + " já tinham sido recriados com o nome e a rede novos: "
                + "use Iniciar no projeto para recriá-los como estavam.");
        }
        boolean cancelou = f.tipo() == FalhaDoProjeto.Tipo.CANCELADO;
        return new Resultado(cancelou ? Desfecho.CANCELADO : Desfecho.FALHOU, previa.hostname(), previa.url(), null, f.mensagem(), etapa, desfeito, naoConsegui, copias, juntar(previa.avisos(), avisosDaExecucao));
    }

    // ---------------------------------------------------------------------------------------------
    // Os passos
    // ---------------------------------------------------------------------------------------------

    private void comporNoServidor(Previa previa, ObservadorDaEtapa obs, List<Desfazer> pilha, List<String> copias, List<String> avisos) throws FalhaDoProjeto {
        ComposeComEdge.Proposta prop = previa.proposta();
        if (prop == null || prop.situacao() == ComposeComEdge.Situacao.PRONTO) {
            obs.etapa(Etapa.COMPOSE, ObservadorDaEtapa.Estado.PULADO, "O docker-compose.yml já está pronto.");
            return;
        }
        if (prop.situacao() != ComposeComEdge.Situacao.EDITAVEL) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O app não escreve neste docker-compose.yml: copie o bloco para o arquivo.");
        }
        obs.etapa(Etapa.COMPOSE, ObservadorDaEtapa.Estado.ANDANDO, "Conferindo o compose novo com o Docker e gravando…");
        byte[] novo = prop.novoTexto().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String pasta = CaminhoRemoto.juntar(ambiente.raizDosProjetos(), previa.pedido().projeto());
            String arquivo = CaminhoRemoto.juntar(pasta, ARQUIVO_COMPOSE);
            if (!Arrays.equals(lerBytes(c, arquivo), previa.bytesDoCompose())) {
                throw mudou();
            }
            conferirComODocker(pasta, novo);
            byte[] naHora = lerBytes(c, arquivo);
            if (!Arrays.equals(naHora, previa.bytesDoCompose())) {
                throw mudou();
            }
            // as permissões que o arquivo já tinha valem para o novo e para a cópia (um compose 0600 do usuário não vira 0644 por causa do app)
            int modo = permissoesDe(c, arquivo);
            String copia = nomeDaCopia(c, pasta);
            c.gravarAtomico(CaminhoRemoto.juntar(pasta, copia), naHora, modo);
            copias.add(CaminhoRemoto.juntar(pasta, copia));
            // a cópia fica na pasta do projeto e o git a veria como arquivo novo (e levaria o conteúdo do compose para o repositório)
            EscritorDeCompose.cuidarDaCopiaNoGit(c, pasta, copia, avisos);
            c.gravarAtomico(arquivo, novo, modo);
            byte[] original = previa.bytesDoCompose();
            pilha.add(new Desfazer(Etapa.COMPOSE, "o docker-compose.yml", () -> {
                try (ClienteSftp d = ambiente.abrirArquivos()) {
                    byte[] agora = lerBytes(d, arquivo);
                    if (agora != null && !Arrays.equals(agora, novo) && !Arrays.equals(agora, original)) {
                        // alguém mexeu no arquivo depois da gravação do app: devolver a cópia por cima apagaria a mudança dele
                        return List.of("O docker-compose.yml mudou depois que o app o gravou (outra pessoa mexeu): NÃO o sobrescrevi com a cópia datada (" + copia
                            + "). Confira o arquivo e, se quiser, copie a cópia de volta à mão.");
                    }
                    d.gravarAtomico(arquivo, original, modo);
                    return List.of();
                } catch (ErroSftp | FalhaDoProjeto e) {
                    return List.of("Não consegui devolver o docker-compose.yml como estava (" + (e instanceof ErroSftp es ? es.mensagemSimples() : ((FalhaDoProjeto) e).mensagem()) + "): copie a cópia datada (" + copia + ") de volta por cima dele.");
                }
            }));
            obs.etapa(Etapa.COMPOSE, ObservadorDaEtapa.Estado.FEITO, "docker-compose.yml gravado (cópia: " + copia + ").");
        } catch (ErroSftp e) {
            if (e.tipo() == ErroSftp.Tipo.OUTRO_DONO) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_PERMISSAO, "O docker-compose.yml é de outro usuário do servidor, e o app não troca arquivo de outro dono. Copie o bloco para o arquivo à mão.", null, e);
            }
            throw new FalhaDoProjeto(e.tipo() == ErroSftp.Tipo.SEM_PERMISSAO ? FalhaDoProjeto.Tipo.SEM_PERMISSAO : FalhaDoProjeto.Tipo.OUTRO,
                "Não consegui gravar o docker-compose.yml: " + e.mensagemSimples(), null, e);
        }
    }

    /** O modo (rwx) que o arquivo tem agora; 0644 só se o servidor não disse. */
    private static int permissoesDe(ClienteSftp c, String arquivo) {
        try {
            int p = c.estatisticas(arquivo).permissoes() & 0777;
            return p == 0 ? PERMISSAO_DO_COMPOSE : p;
        } catch (ErroSftp e) {
            return PERMISSAO_DO_COMPOSE;
        }
    }

    private static FalhaDoProjeto mudou() {
        return new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O docker-compose.yml mudou depois que você viu a diferença. Nada foi gravado: confira de novo.");
    }

    private void conferirComODocker(String pasta, byte[] novo) throws FalhaDoProjeto {
        ResultadoExec r;
        try {
            r = ambiente.executor().executar(ComandosDeProjeto.validarCompose(ambiente.paraOSistema().apply(pasta)), Map.of(), novo, PRAZO_DA_CONFERENCIA);
        } catch (ProgramaNaoEncontradoException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O Docker não está instalado onde o projeto roda, então não dá para conferir o compose. Nada foi gravado.", null, e);
        } catch (IOException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "Não consegui conferir o compose com o Docker (a conexão caiu ou o Docker não respondeu). Nada foi gravado.", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado. Nada foi gravado.", null, e);
        } catch (IllegalArgumentException e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O caminho do projeto tem um caractere que o app não passa ao servidor. Nada foi gravado.", null, e);
        }
        if (r.status() != 0) {
            String motivo = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(r.stderr())), 400);
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O Docker não aceitou o compose com a rede edge (" + (motivo.isEmpty() ? "sem mensagem" : motivo) + "). Nada foi gravado.");
        }
    }

    private void criarDns(Previa previa, ObservadorDaEtapa obs, List<Desfazer> pilha) throws FalhaDoProjeto {
        if (previa.dnsJaEraDoApp()) {
            obs.etapa(Etapa.DNS, ObservadorDaEtapa.Estado.PULADO, "O registro de DNS já existia (é do app): reaproveitado.");
            return;
        }
        obs.etapa(Etapa.DNS, ObservadorDaEtapa.Estado.ANDANDO, "Criando o registro de DNS na Cloudflare…");
        String alvo = NomeDoEndereco.alvoDoTunel(cfg.tunelId());
        String comentario = NomeDoEndereco.comentarioDoDns(previa.pedido().projeto());
        RegistroDnsCf criado = null;
        try {
            criado = api.criarCname(cfg.zonaId(), previa.hostname(), alvo, true, comentario);
        } catch (ErroCloudflare e) {
            if (e.incerto()) {
                // sem resposta: pode ter sido criado. Confere pelo nome antes de dizer que falhou.
                try {
                    for (RegistroDnsCf r : api.registrosDnsComONome(cfg.zonaId(), previa.hostname())) {
                        if (r.tipo().equals("CNAME") && r.conteudo().equalsIgnoreCase(alvo) && comentario.equals(r.comentario())) {
                            criado = r;
                        }
                    }
                } catch (ErroCloudflare conferencia) {
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.INCERTO, e.mensagem() + " E também não consegui conferir se o registro foi criado: " + conferencia.mensagem(), null, e);
                }
            }
            if (criado == null) {
                throw FalhasDaCloudflare.de(e);
            }
        }
        String id = criado.id();
        pilha.add(new Desfazer(Etapa.DNS, "o registro de DNS " + previa.hostname(), () -> {
            try {
                api.removerDns(cfg.zonaId(), id);
                return List.of();
            } catch (ErroCloudflare e) {
                if (e.tipo() == ErroCloudflare.Tipo.NAO_ENCONTRADO) {
                    return List.of(); // já não está lá
                }
                return List.of("Não consegui apagar o registro de DNS " + previa.hostname() + " (" + e.mensagem() + "): apague-o no painel da Cloudflare (DNS do domínio " + cfg.zonaNome() + ").");
            }
        }));
        obs.etapa(Etapa.DNS, ObservadorDaEtapa.Estado.FEITO, "Registro " + previa.hostname() + " criado.");
    }

    private String nomeDaCopia(ClienteSftp c, String pasta) throws ErroSftp {
        String base = ARQUIVO_COMPOSE + "." + DATA_DA_COPIA.format(LocalDateTime.now(relogio)) + ".bak";
        String nome = base;
        for (int i = 2; c.existe(CaminhoRemoto.juntar(pasta, nome)); i++) {
            nome = base + "-" + i;
        }
        return nome;
    }

    private static byte[] lerBytes(ClienteSftp c, String caminho) throws ErroSftp, FalhaDoProjeto {
        try {
            byte[] bytes = c.lerArquivo(caminho, LIMITE_DO_COMPOSE + 1);
            if (bytes.length > LIMITE_DO_COMPOSE) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O arquivo " + CaminhoRemoto.nome(caminho) + " é grande demais para o app mexer (mais de 512 KB).");
            }
            return bytes;
        } catch (ErroSftp e) {
            if (e.tipo() == ErroSftp.Tipo.NAO_EXISTE) {
                return null;
            }
            throw e;
        }
    }

    private static String decodificar(byte[] bytes) {
        return TunelPorArquivo.decodificar(bytes);
    }
}
