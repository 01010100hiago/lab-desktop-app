package br.com.labdesktop.servidor;

import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ImportadorDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.ssh.SondagemException;
import br.com.labdesktop.util.NomeDoMac;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O roteiro do "Adicionar servidor" (ARQUITETURA 8.3), sem Swing: gerar ou importar a chave, sondar o host, gravar
 * servidor e pin no cofre, conectar, registrar o token e confirmar que o agente responde. A interface chama cada
 * passo de uma thread de fundo e só desenha o resultado; assim o caminho inteiro é testado contra o
 * {@code SshServer} embutido, sem janela.
 *
 * <p>Tudo aqui BLOQUEIA (cofre, SSH, rede): nunca da EDT. Nenhum passo leva segredo para log ou para texto de
 * erro.
 */
public final class AssistenteDeServidor {

    private static final Logger LOG = LoggerFactory.getLogger(AssistenteDeServidor.class);

    /** Quanto esperar a conexão chegar a um estado em que dá para rodar o registro do token. */
    public static final Duration PRAZO_PARA_CONECTAR = Duration.ofSeconds(30);
    /** Quanto esperar, depois do token, o agente responder e os limites chegarem. */
    public static final Duration PRAZO_PARA_LER_LIMITES = Duration.ofSeconds(15);
    /**
     * Quanto esperar a conexão REVALIDAR o agente antes de dizer que ele é antigo: "Tentar de novo" depois de publicar o
     * painel ainda mostra, por alguns instantes, o veredito de antes.
     */
    public static final Duration FOLGA_PARA_REVALIDAR_O_AGENTE = Duration.ofSeconds(3);
    /** Quanto esperar a leitura (por SSH) que diz se o agente está instalado, antes de tratar o silêncio dele como "agente fora". */
    public static final Duration PRAZO_DO_DIAGNOSTICO = Duration.ofSeconds(15);
    private static final long PASSO_DA_ESPERA_MS = 100;

    /** Os dados do formulário "Adicionar servidor". Validados pelas mesmas regras do cofre. */
    public record Dados(String nome, String host, int porta, String usuario, int portaDoAgente) {

        /** O que o usuário vê na primeira vez (ARQUITETURA 8.3): campos vazios, com a porta SSH padrão (22) e a porta padrão do agente. */
        public static Dados padraoDoPrimeiro() {
            return new Dados("", "", 22, "", ConfigServidor.PORTA_AGENTE_PADRAO);
        }

        /** Lança {@link IllegalArgumentException} com mensagem em pt-BR se algo não vale. */
        public void validar() {
            String nomeLimpo = TextoSeguro.limparLinha(nome);
            if (nomeLimpo.isEmpty() || nomeLimpo.length() > 64) {
                throw new IllegalArgumentException("O nome do servidor deve ter de 1 a 64 caracteres.");
            }
            if (!ValidadorDeNomes.hostValido(host == null ? "" : host.strip())) {
                throw new IllegalArgumentException("O endereço do servidor é inválido (use um IP ou um nome DNS).");
            }
            if (porta < 1 || porta > 65_535) {
                throw new IllegalArgumentException("A porta do SSH vai de 1 a 65535.");
            }
            if (!ValidadorDeNomes.usuarioSshValido(usuario == null ? "" : usuario.strip())) {
                throw new IllegalArgumentException("O usuário do SSH é inválido.");
            }
            if (portaDoAgente < 1 || portaDoAgente > 65_535) {
                throw new IllegalArgumentException("A porta do painel no servidor vai de 1 a 65535.");
            }
        }
    }

    /** Um passo da conclusão, para a tela mostrar o andamento. */
    public enum Etapa {
        CONECTANDO("Conectando ao servidor e autenticando com a chave"),
        REGISTRANDO_TOKEN("Registrando o token deste Mac no agente (por SSH)"),
        TESTANDO_AGENTE("Testando o agente do painel"),
        LENDO_LIMITES("Lendo os limites do plano"),
        PRONTO("Pronto");

        private final String rotulo;

        Etapa(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    /**
     * Como a conclusão terminou quando NÃO deu erro. {@link #AGENTE_NAO_INSTALADO} não é falha: o SSH conectou e o servidor ainda não
     * tem o agente. A tela pergunta ao usuário se quer instalar (ou usar só por SSH); o registro do token só roda depois que o agente
     * existir, para não gerar o erro de container inexistente.
     */
    public enum Conclusao { PRONTO, AGENTE_NAO_INSTALADO }

    /** Por que a conclusão parou; a tela escolhe a mensagem e a ação (tentar de novo, conferir o host...). */
    public static final class ErroDoAssistente extends Exception {
        private static final long serialVersionUID = 1L;

        /** Os motivos que a tela sabe tratar. */
        public enum Motivo { CHAVE_RECUSADA, CHAVE_DO_HOST, TEMPO_ESGOTADO, COFRE_BLOQUEADO, AGENTE_FORA, AGENTE_DESATUALIZADO }

        private final Motivo motivo;

        public ErroDoAssistente(Motivo motivo, String mensagem) {
            super(mensagem);
            this.motivo = motivo;
        }

        public Motivo motivo() {
            return motivo;
        }
    }

    private final CofreDeChaves cofre;
    private final GerenciadorServidores gerenciador;
    private final ConfigSsh cfgSsh;

    public AssistenteDeServidor(CofreDeChaves cofre, GerenciadorServidores gerenciador, ConfigSsh cfgSsh) {
        this.cofre = cofre;
        this.gerenciador = gerenciador;
        this.cfgSsh = cfgSsh;
    }

    // =====================================================================================================
    // Chaves
    // =====================================================================================================

    /** As chaves do cofre, sem a parte privada. */
    public List<InfoChave> chaves() {
        return cofre.chaves();
    }

    /**
     * Gera uma chave Ed25519 nova e já a guarda no cofre (o usuário vai instalar a pública no servidor e voltar: se o
     * diálogo fechar nesse meio tempo, a chave não se perde).
     */
    public InfoChave gerarChave(String nome) throws CofreException {
        ChaveSsh chave = GeradorDeChaves.gerar(nome, NomeDoMac.hostname());
        try {
            cofre.adicionarChave(chave); // o cofre passa a ser o responsável
        } catch (CofreException | RuntimeException e) {
            chave.zerar();
            throw e;
        }
        return chave.info();
    }

    /**
     * Apaga do cofre uma chave que nenhum servidor usa: a que o assistente criou sozinho e o usuário trocou por outra
     * (importada ou já existente), ou que o usuário pediu para gerar de novo. Melhor esforço: se falhar, a chave só fica no cofre,
     * onde "usar uma chave que já está no cofre" ainda a enxerga. Nunca apaga chave em uso.
     *
     * @return {@code true} se apagou
     */
    public boolean descartarChaveNaoUsada(String chaveId) {
        try {
            if (cofre.servidores().stream().anyMatch(s -> s.chaveId().equals(chaveId))) {
                return false;
            }
            cofre.removerChave(chaveId);
            return true;
        } catch (CofreException | RuntimeException e) {
            LOG.warn("Não consegui descartar a chave {} que ninguém usa: {}", chaveId, e.toString());
            return false;
        }
    }

    /**
     * Importa uma chave Ed25519 OpenSSH (por exemplo {@code ~/.ssh/id_ed25519}) para o cofre. O app só lê o arquivo
     * que o usuário escolheu. Se o cofre já tem essa mesma chave (mesma impressão digital), devolve a que já existe.
     *
     * @param passphrase passphrase da chave, ou {@code null}; não é zerada aqui (é de quem chama)
     */
    public InfoChave importarChave(Path arquivo, char[] passphrase, String nome) throws CofreException {
        ChaveSsh chave = ImportadorDeChaves.importarDeArquivo(arquivo, passphrase, nome, NomeDoMac.hostname());
        // O mesmo arquivo importado de novo (para outro servidor, ou outro nome do mesmo) não cria uma segunda cópia: o
        // servidor enxerga UMA chave só, e duas entradas com a mesma chave pública enganariam a conta de "quem mais a usa"
        // na hora de remover um servidor e tirar a chave do authorized_keys.
        InfoChave jaTenho = cofre.chaveComAImpressao(chave.impressao()).orElse(null);
        if (jaTenho != null) {
            chave.zerar();
            return jaTenho;
        }
        try {
            cofre.adicionarChave(chave);
        } catch (CofreException | RuntimeException e) {
            chave.zerar();
            throw e;
        }
        return chave.info();
    }

    // =====================================================================================================
    // Host
    // =====================================================================================================

    /** A sondagem do host: só o key exchange, sem autenticar. O usuário confere a impressão antes de confiar. */
    public SondagemDeHost.Resultado sondar(String host, int porta) throws SondagemException {
        return SondagemDeHost.sondar(host.strip(), porta, cfgSsh);
    }

    /**
     * Grava o servidor no cofre COM o pin que o usuário acabou de confirmar e passa a acompanhá-lo (sem conectar ainda).
     *
     * @throws IllegalArgumentException se os dados não valem
     */
    public ServidorLab cadastrar(Dados dados, String chaveId, Pin pin) throws CofreException {
        dados.validar();
        ConfigServidor config = new ConfigServidor(ConfigServidor.novoId(), dados.nome(), dados.host().strip(),
            dados.porta(), dados.usuario().strip(), chaveId, dados.portaDoAgente(), List.of(pin), Transporte.DIRETO,
            null, Estado2fa.DESLIGADO);
        cofre.adicionarServidor(config, null);
        return gerenciador.adicionar(config.id());
    }

    /**
     * Confiar no host de um servidor que já existe (a conexão parou em {@code ERRO_CHAVE_HOST} por falta de pin):
     * grava o pin que o usuário confirmou e repete a conexão.
     */
    public void confiarNoHost(ServidorLab lab, Pin pin) throws CofreException {
        cofre.gravarPin(lab.id(), pin);
        lab.tentarAgora();
    }

    // =====================================================================================================
    // Conclusão: conectar, registrar o token, testar
    // =====================================================================================================

    /**
     * Conecta (se ainda não conectou), registra o token deste Mac e confirma que o agente responde e que os limites
     * chegam. Se o servidor já tem token deste Mac, não registra de novo. Bloqueia até uns 45 s.
     *
     * @param nomeDoDispositivo {@code ^[a-z0-9-]{1,40}$}; veja {@link #sugerirNomeDoDispositivo()}
     * @param progresso         recebe cada etapa (chamado nesta thread)
     * @throws ErroDoAssistente    a conexão não chegou lá (chave recusada, host sem pin, prazo, painel do servidor sem o
     *                             acesso do app por publicar)
     * @return {@link Conclusao#AGENTE_NAO_INSTALADO} se o SSH conectou e o servidor não tem o agente (a pergunta "quer instalar?" é da
     *         tela; nada foi registrado nem escrito no servidor), ou {@link Conclusao#PRONTO}
     * @throws ErroProvisionamento o registro do token falhou ({@code token_conflito}: o nome já existe no agente)
     */
    public Conclusao concluir(ServidorLab lab, String nomeDoDispositivo, Consumer<Etapa> progresso)
        throws ErroDoAssistente, ErroProvisionamento, CofreException {
        progresso.accept(Etapa.CONECTANDO);
        EstadoConexao antes = lab.estado().conexao();
        iniciarOuRepetir(lab);
        esperarSairDoErro(lab, antes);
        esperarConexaoComSessao(lab);
        if (agenteNaoInstalado(lab)) {
            return Conclusao.AGENTE_NAO_INSTALADO;
        }
        exigirAgenteAtualizado(lab);

        if (lab.estado().semToken()) {
            progresso.accept(Etapa.REGISTRANDO_TOKEN);
            lab.registrarToken(nomeDoDispositivo);
        }

        progresso.accept(Etapa.TESTANDO_AGENTE);
        esperar(lab, PRAZO_PARA_LER_LIMITES, "o agente não ficou de pé", e -> e.conexao() == EstadoConexao.CONECTADO);
        if (!Boolean.FALSE.equals(lab.estado().temClaude())) { // o agente do perfil monitor não tem limites do plano
            progresso.accept(Etapa.LENDO_LIMITES);
            esperar(lab, PRAZO_PARA_LER_LIMITES, "os limites do plano não chegaram", e -> e.limites() != null);
        }
        progresso.accept(Etapa.PRONTO);
        return Conclusao.PRONTO;
    }

    /**
     * O SSH está de pé e o agente não existe neste servidor? Espera a leitura do servidor (só leitura) que decide entre "ainda não
     * instalado" e "instalado e sem resposta", sem passar de {@link #PRAZO_DO_DIAGNOSTICO}. Só devolve {@code true} quando o
     * servidor mesmo disse que o agente falta.
     */
    private boolean agenteNaoInstalado(ServidorLab lab) {
        long limite = System.nanoTime() + PRAZO_DO_DIAGNOSTICO.toNanos();
        while (true) {
            EstadoServidor e = lab.estado();
            if (e.conexao() == EstadoConexao.CONECTADO || agenteDesatualizado(e)) {
                return false;
            }
            if (e.conexao() == EstadoConexao.SSH_OK_SEM_AGENTE) {
                if (e.situacaoDoAgente().oferecerInstalar()) {
                    return true;
                }
                if (e.situacaoDoAgente() != SituacaoDoAgente.DESCONHECIDA) {
                    return false; // o agente existe e não responde: segue a espera normal e, se não vier, o erro de sempre
                }
            }
            if (e.conexao() != EstadoConexao.SSH_OK_SEM_AGENTE && e.conexao() != EstadoConexao.CONECTANDO
                && e.conexao() != EstadoConexao.RECONECTANDO) {
                return false; // token recusado, 2FA etc.: o fluxo de sempre trata
            }
            if (System.nanoTime() > limite) {
                return false;
            }
            dormir();
        }
    }

    /**
     * "Tentar agora" só enfileira o pedido na conexão: logo depois, o retrato ainda mostra o erro de ANTES. Sem esta
     * folga (até 2 s) a espera leria o erro velho e desistiria de uma tentativa que nem começou.
     */
    private void esperarSairDoErro(ServidorLab lab, EstadoConexao antes) {
        if (antes != EstadoConexao.ERRO_AUTENTICACAO && antes != EstadoConexao.ERRO_CHAVE_HOST) {
            return;
        }
        long limite = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (lab.estado().conexao() == antes && System.nanoTime() < limite) {
            dormir();
        }
    }

    private void iniciarOuRepetir(ServidorLab lab) {
        EstadoConexao atual = lab.estado().conexao();
        if (atual == EstadoConexao.DESCONECTADO || atual == EstadoConexao.BLOQUEADO_COFRE) {
            lab.iniciar();
        } else {
            lab.tentarAgora();
        }
    }

    /**
     * O painel do servidor de ANTES do app nativo ainda não sabe registrar o acesso do app: o registro do token falharia com a
     * mensagem crua do {@code node} ("Cannot find module"), e o usuário não saberia que falta
     * publicar o painel. A conexão já sabe (a sonda do {@code GET /api/versao} deu 404: {@code AGENTE_DESATUALIZADO});
     * aqui o assistente diz o que fazer, ANTES de tentar registrar qualquer coisa.
     */
    private void exigirAgenteAtualizado(ServidorLab lab) throws ErroDoAssistente {
        if (!agenteDesatualizado(lab.estado())) {
            return;
        }
        long limite = System.nanoTime() + FOLGA_PARA_REVALIDAR_O_AGENTE.toNanos();
        while (agenteDesatualizado(lab.estado()) && System.nanoTime() < limite) {
            dormir();
        }
        if (agenteDesatualizado(lab.estado())) {
            throw new ErroDoAssistente(ErroDoAssistente.Motivo.AGENTE_DESATUALIZADO,
                "O painel no servidor é anterior a este app (não tem a identificação do app nem GET /api/versao). Publique o painel "
                    + "com o script de publicação do agente e tente de novo.");
        }
    }

    private static boolean agenteDesatualizado(EstadoServidor e) {
        Falha falha = e.falha();
        return e.conexao() == EstadoConexao.SSH_OK_SEM_AGENTE && falha != null && falha.tipo() == Falha.Tipo.AGENTE_DESATUALIZADO;
    }

    private void esperarConexaoComSessao(ServidorLab lab) throws ErroDoAssistente {
        long limite = System.nanoTime() + PRAZO_PARA_CONECTAR.toNanos();
        while (true) {
            EstadoServidor e = lab.estado();
            EstadoConexao c = e.conexao();
            if (c.permiteExec()) {
                return;
            }
            switch (c) {
                case ERRO_AUTENTICACAO -> throw new ErroDoAssistente(ErroDoAssistente.Motivo.CHAVE_RECUSADA,
                    "O servidor recusou a chave. Falta instalar a chave pública no servidor (veja o passo abaixo) e tentar de novo.");
                case ERRO_CHAVE_HOST -> throw new ErroDoAssistente(ErroDoAssistente.Motivo.CHAVE_DO_HOST,
                    e.falha() == null ? "A chave do servidor não confere com a confirmada." : e.falha().mensagem());
                case BLOQUEADO_COFRE -> throw new ErroDoAssistente(ErroDoAssistente.Motivo.COFRE_BLOQUEADO,
                    "O cofre foi bloqueado.");
                default -> { /* conectando, reconectando, suspenso: continua esperando */ }
            }
            if (System.nanoTime() > limite) {
                throw new ErroDoAssistente(ErroDoAssistente.Motivo.TEMPO_ESGOTADO,
                    "Não consegui conectar em " + PRAZO_PARA_CONECTAR.toSeconds() + " s"
                        + (e.falha() == null ? "." : " (" + e.falha().mensagem() + ")."));
            }
            dormir();
        }
    }

    private void esperar(ServidorLab lab, Duration prazo, String oQueFaltou, java.util.function.Predicate<EstadoServidor> pronto)
        throws ErroDoAssistente {
        long limite = System.nanoTime() + prazo.toNanos();
        while (!pronto.test(lab.estado())) {
            EstadoConexao c = lab.estado().conexao();
            if (c == EstadoConexao.BLOQUEADO_COFRE) {
                throw new ErroDoAssistente(ErroDoAssistente.Motivo.COFRE_BLOQUEADO, "O cofre foi bloqueado.");
            }
            if (System.nanoTime() > limite) {
                LOG.info("Assistente: {} (estado {})", oQueFaltou, c);
                throw new ErroDoAssistente(ErroDoAssistente.Motivo.AGENTE_FORA,
                    "Conectei por SSH, mas " + oQueFaltou + " em " + prazo.toSeconds() + " s.");
            }
            dormir();
        }
    }

    private static void dormir() {
        try {
            Thread.sleep(PASSO_DA_ESPERA_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido.", e);
        }
    }

    /** O {@code nome_do_dispositivo} sugerido a partir do nome do Mac. Roda um processo (até ~2 s): fora da EDT. */
    public static String sugerirNomeDoDispositivo() {
        return ValidadorDeNomes.sugerirNomeDeDispositivo(NomeDoMac.hostname());
    }
}
