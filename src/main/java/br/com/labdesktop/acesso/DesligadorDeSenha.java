package br.com.labdesktop.acesso;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ssh.ProvaDeChave;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.ssh.SondaDeMetodos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Desligar o login por senha neste servidor". Duas fases, e a segunda SÓ roda depois do clique do usuário e do nome do
 * servidor digitado:
 * <ol>
 *   <li>{@link #avaliar()}: só LEITURA. Vê se o usuário é root ou tem {@code sudo -n}, se o servidor tem {@code systemd-run} (para o desfazer automático),
 *       lê a configuração efetiva ({@code sshd -T}) e os arquivos, prevê se a regra do app valeria no meio do que já existe e diz o que encontrou. Recusa o que não é
 *       seguro (métodos combinados, login por chave desligado, arquivo anterior que manda mais, pasta que não é lida, servidor sem como armar o desfazer).</li>
 *   <li>{@link #desligar}: cópia datada do arquivo que ela altera, ARMA um desfazer no próprio servidor (um timer do systemd de 2 minutos), grava
 *       {@code sshd_config.d/10-lab-desktop.conf}, valida com {@code sshd -t} ANTES de aplicar, RECARREGA (nunca reinicia) o sshd, prova com uma conexão nova só por
 *       chave que ainda entra, confere o resultado (a configuração geral, a do usuário, e os métodos que o servidor oferece numa conexão nova) e só então CANCELA o timer.
 *       Se a prova ou a conferência falha, desfaz sozinho. Se o app morre, a conexão cai ou o app fica sem resposta no meio, o servidor desfaz sozinho em 2 minutos.
 *       Depois da operação o app NUNCA religa a senha: mostra o comando de reverter.</li>
 * </ol>
 * Sem permissão (nem root, nem {@code sudo -n}) o app NÃO tenta e NÃO pede senha de sudo: mostra o comando exato para o usuário rodar. Todos os comandos são os fixos
 * de {@link ComandosDoAcesso}. O que o app NÃO sabe, ele diz que não sabe: uma queda de conexão no meio é "talvez aplicado", nunca "nada foi gravado". Bloqueia (SSH): thread de fundo.
 */
public final class DesligadorDeSenha {

    private static final Logger LOG = LoggerFactory.getLogger(DesligadorDeSenha.class);
    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final Duration INTERVALO_PADRAO_DA_PROVA = Duration.ofMillis(1500);
    private static final int TENTATIVAS_DA_PROVA = 3;
    private static final byte[] SEM_ENTRADA = new byte[0];
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** O que o usuário pode fazer no servidor sem digitar senha. */
    public enum Permissao { ADMINISTRADOR, SUDO_SEM_SENHA, NENHUMA }

    /** Por que o app não vai desligar (ou não precisa). */
    public enum Motivo {
        /** Sem root nem {@code sudo -n}: o usuário roda o comando. */
        SEM_PERMISSAO,
        /** O login por senha já está desligado. */
        JA_DESLIGADA,
        /** O servidor exige combinar métodos (por exemplo chave mais código): mexer poderia trancar todo mundo. */
        METODOS_COMBINADOS,
        /** O login por chave está desligado no servidor: desligar a senha trancaria o usuário. */
        CHAVE_DESLIGADA,
        /** Outro arquivo, que vem antes do do app, define a opção: a regra do app não valeria. */
        ARQUIVO_ANTERIOR,
        /** O {@code sshd_config} não lê a pasta {@code sshd_config.d}: a regra do app seria ignorada. */
        NAO_LE_A_PASTA,
        /** O servidor não tem {@code systemd-run}: o app não consegue armar o desfazer automático no servidor, e sem ele não mexe. */
        SEM_DESFAZER_NO_SERVIDOR,
        /** Não consegui ler a configuração do servidor. */
        NAO_LI
    }

    /** O que impede (ou dispensa) o desligar, com a frase para o usuário. */
    public record Bloqueio(Motivo motivo, String texto) { }

    /**
     * O que a leitura encontrou. {@code bloqueio == null} quer dizer "pode desligar".
     *
     * @param prefixo   {@code ""} (root) ou {@code "sudo -n "}, o que as escritas vão usar
     * @param achados   frases simples do que foi encontrado (arquivos, UsePAM, métodos...)
     * @param avisos    o que não impede mas o usuário precisa saber (bloco Match, outro arquivo que mexe em senha depois do nosso...)
     * @param temArquivoProprio o arquivo do app já existe (uma segunda vez)
     */
    public record Avaliacao(Permissao permissao, String prefixo, Boolean senhaLigada, Boolean tecladoLigado, Boolean usaPam, String metodos,
                            List<String> achados, List<String> avisos, boolean temArquivoProprio, Bloqueio bloqueio) {

        public boolean podeDesligar() {
            return bloqueio == null;
        }
    }

    /** Os passos que a tela mostra. */
    public enum Passo {
        COPIA("Guardar uma cópia datada do que vai mudar"),
        ARMAR("Armar o desfazer automático no servidor (2 minutos)"),
        GRAVAR("Gravar a regra do app"),
        VALIDAR("Conferir a configuração antes de aplicar"),
        RECARREGAR("Recarregar o sshd (sem reiniciar)"),
        PROVAR("Entrar de novo só com a chave"),
        CONFERIR("Conferir o resultado no servidor"),
        CANCELAR("Cancelar o desfazer automático");

        private final String rotulo;

        Passo(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    /** Como um passo está. */
    public enum Estado { ANDANDO, FEITO, FALHOU, DESFEITO }

    /** Para a tela acompanhar. Chamado na thread que roda o desligar. */
    @FunctionalInterface
    public interface Observador {
        void passo(Passo passo, Estado estado, String frase);
    }

    /** Como terminou. */
    public enum Desfecho {
        /** Desligou e conferiu (a configuração geral, a do usuário e os métodos que o servidor oferece): ele não aceita mais senha. */
        DESLIGADA,
        /** Desligou na regra geral, mas um bloco {@code Match} ainda aceita senha para este usuário e endereço. O app não desfaz (a chave segue entrando). */
        PARCIAL,
        /** A chave entrou e o arquivo está gravado, mas o app NÃO conseguiu conferir se a senha está mesmo desligada. Nunca afirma. */
        SEM_CONFERENCIA,
        /** Já estava desligado: nada foi mexido. */
        JA_ESTAVA_DESLIGADA,
        /** O app não podia (sem permissão, ou algo inseguro): nada foi mexido; veja o texto. */
        NAO_APLICADA,
        /** Falhou antes de recarregar (cópia, desfazer automático, gravação ou validação): o que foi gravado foi desfeito e o sshd nem foi tocado. */
        FALHOU_SEM_EFEITO,
        /** Recarregou, mas a prova da chave ou a conferência falhou: o app desfez e recarregou de novo. */
        DESFEITA,
        /** A conexão caiu ou o app foi interrompido no meio e o app NÃO sabe o que ficou: talvez aplicado. O servidor desfaz sozinho em 2 minutos. */
        INCERTO,
        /** Falhou e o app NÃO conseguiu desfazer: o texto diz o comando que o usuário precisa rodar. */
        PRECISA_DE_AJUDA
    }

    /**
     * O resultado.
     *
     * @param resumo       a frase principal para o usuário
     * @param detalhes     o que mais saber (copiar, avisos, o que o app encontrou)
     * @param senhaLigadaDepois o que o {@code sshd -T} disse no fim ({@code null}: não consegui conferir)
     * @param comandoParaReverter o comando para voltar ao login por senha (sempre o mesmo)
     * @param copiaDoPrincipal o nome da cópia datada que o app fez ({@code null}: não houve)
     */
    public record Resultado(Desfecho desfecho, String resumo, List<String> detalhes, Boolean senhaLigadaDepois, String comandoParaReverter,
                            String copiaDoPrincipal) { }

    private final ProvisionadorRemoto.Executor executor;
    private final ProvaDeChave prova;
    private final Supplier<LocalDateTime> relogio;
    private final SondaDeMetodos sonda;
    private final String usuario;
    private final Duration intervaloDaProva;

    /**
     * @param executor a conexão SSH que o app já tem (ou um falso, nos testes)
     * @param prova    abre uma conexão NOVA só com a chave e prova que entra
     * @param relogio  a data e a hora de agora (o carimbo da cópia)
     * @param sonda    lê os métodos que o servidor oferece numa conexão nova ({@code null}: não há como conferir assim)
     * @param usuario  o usuário do SSH (para conferir a configuração PARA ele, com os blocos Match); {@code null}: não confere
     */
    public DesligadorDeSenha(ProvisionadorRemoto.Executor executor, ProvaDeChave prova, Supplier<LocalDateTime> relogio, SondaDeMetodos sonda, String usuario) {
        this(executor, prova, relogio, sonda, usuario, INTERVALO_PADRAO_DA_PROVA);
    }

    /** Com o intervalo entre as tentativas da prova trocável (os testes não esperam). */
    public DesligadorDeSenha(ProvisionadorRemoto.Executor executor, ProvaDeChave prova, Supplier<LocalDateTime> relogio, SondaDeMetodos sonda, String usuario,
                             Duration intervaloDaProva) {
        this.executor = executor;
        this.prova = prova;
        this.relogio = relogio;
        this.sonda = sonda;
        this.usuario = usuario;
        this.intervaloDaProva = intervaloDaProva;
    }

    /** O conteúdo que o app grava no arquivo dele (os dois nomes do teclado interativo: o OpenSSH antigo só conhece {@code ChallengeResponseAuthentication}). */
    public static String conteudoDaRegra(LocalDateTime agora) {
        return "# Criado pelo Lab Desktop em " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(agora) + ".\n"
            + "# Para voltar ao login por senha: apague este arquivo e recarregue o ssh (systemctl reload ssh).\n"
            + "PasswordAuthentication no\n"
            + "KbdInteractiveAuthentication no\n"
            + "ChallengeResponseAuthentication no\n";
    }

    // =====================================================================================================
    // Fase 1: só leitura
    // =====================================================================================================

    /** Só lê. Pode rodar assim que o diálogo abre. */
    public Avaliacao avaliar() throws IOException, InterruptedException {
        ResultadoExec permissoes = rodar(ComandosDoAcesso.lerPermissoes());
        Permissao permissao = lerPermissao(permissoes.stdout());
        if (permissao == Permissao.NENHUMA) {
            return new Avaliacao(Permissao.NENHUMA, null, null, null, null, null, List.of(), List.of(), false, new Bloqueio(Motivo.SEM_PERMISSAO,
                "Este usuário não é administrador e não tem sudo sem senha, e o app nunca pede a senha de sudo. Para desligar o login por "
                    + "senha, rode você mesmo, no servidor, o comando abaixo. " + ComandosDoAcesso.AVISO_DO_COMANDO_MANUAL));
        }
        String p = permissao == Permissao.ADMINISTRADOR ? ComandosDoAcesso.SEM_PREFIXO : ComandosDoAcesso.COM_SUDO;
        ResultadoExec sshdT = rodar(ComandosDoAcesso.lerConfiguracaoEfetiva(p));
        if (sshdT.status() != 0) {
            return semLeitura(p, "Não consegui ler a configuração do sshd neste servidor (sshd -T falhou).");
        }
        AnaliseDoSshd.Efetiva efetiva = AnaliseDoSshd.lerEfetiva(sshdT.stdout());
        ResultadoExec principal = rodar(ComandosDoAcesso.lerConfigPrincipal(p));
        ResultadoExec pastaTexto = rodar(ComandosDoAcesso.lerPastaDaConfig(p));
        if (principal.status() != 0 || pastaTexto.status() != 0) {
            return semLeitura(p, "Não consegui ler os arquivos de configuração do sshd.");
        }
        SortedMap<String, String> pasta = AnaliseDoSshd.arquivosDaPasta(pastaTexto.stdout());
        boolean temArquivoProprio = pasta.containsKey(AnaliseDoSshd.ARQUIVO_NOSSO);
        SortedMap<String, String> semOProprio = new java.util.TreeMap<>(pasta);
        semOProprio.remove(AnaliseDoSshd.ARQUIVO_NOSSO);

        List<String> achados = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        achados.add("Login por senha agora: " + (efetiva.senha() == null ? "não informado" : efetiva.senha() ? "ligado" : "desligado") + ".");
        achados.add("Login por chave: " + (efetiva.chave() == null ? "não informado" : efetiva.chave() ? "ligado" : "desligado") + ".");
        if (Boolean.TRUE.equals(efetiva.teclado())) {
            achados.add("Teclado interativo (pode pedir a senha pelo PAM): ligado. O app desliga junto, senão a senha ainda entraria por ele.");
        }
        if (efetiva.usaPam() != null) {
            achados.add("UsePAM: " + (efetiva.usaPam() ? "sim" : "não") + ". O app não mexe nisso.");
        }
        List<String> quemMexe = AnaliseDoSshd.quemMexeEmSenha(principal.stdout(), semOProprio);
        if (!quemMexe.isEmpty()) {
            achados.add("Arquivos que já falam de senha: " + String.join(", ", quemMexe) + ".");
        }
        boolean temMatch = AnaliseDoSshd.temMatch(principal.stdout(), semOProprio);
        if (temMatch) {
            avisos.add("A configuração tem um bloco Match (regras para certos usuários ou endereços). O app muda só a regra geral e, no fim, confere também a "
                + "configuração para este usuário e este endereço: se o Match ainda aceitar senha, o resultado diz.");
        }

        Bloqueio bloqueio = decidir(efetiva, pastaTexto.stdout(), principal.stdout(), semOProprio, temArquivoProprio, quemMexe);
        if (bloqueio == null) {
            ResultadoExec systemd = rodar(ComandosDoAcesso.verificarSystemdRun());
            if (!systemd.stdout().contains("systemd-run=sim")) {
                bloqueio = new Bloqueio(Motivo.SEM_DESFAZER_NO_SERVIDOR, "Este servidor não tem o systemd-run, e o app só mexe no login por senha depois de armar, no próprio "
                    + "servidor, um desfazer automático de " + ComandosDoAcesso.PRAZO_DO_DESFAZER_NO_SERVIDOR / 60 + " minutos (para o servidor voltar sozinho se a conexão cair). "
                    + "Nada foi alterado. Se você quiser desligar a senha mesmo assim, rode o comando manual abaixo e teste uma conexão nova antes de fechar a sessão.");
            }
        }
        return new Avaliacao(permissao, p, efetiva.senha(), efetiva.teclado(), efetiva.usaPam(), efetiva.metodos(), List.copyOf(achados),
            List.copyOf(avisos), temArquivoProprio, bloqueio);
    }

    private static Avaliacao semLeitura(String prefixo, String texto) {
        return new Avaliacao(prefixo.isEmpty() ? Permissao.ADMINISTRADOR : Permissao.SUDO_SEM_SENHA, prefixo, null, null, null, null, List.of(),
            List.of(), false, new Bloqueio(Motivo.NAO_LI, texto + " O app não mexe no que não consegue conferir."));
    }

    private static Bloqueio decidir(AnaliseDoSshd.Efetiva e, String pastaTexto, String principal, SortedMap<String, String> semOProprio,
                                    boolean temArquivoProprio, List<String> quemMexe) {
        if (Boolean.FALSE.equals(e.senha()) && !Boolean.TRUE.equals(e.teclado())) {
            return new Bloqueio(Motivo.JA_DESLIGADA, "O login por senha já está desligado neste servidor. Não há nada a fazer.");
        }
        if (Boolean.FALSE.equals(e.chave())) {
            return new Bloqueio(Motivo.CHAVE_DESLIGADA, "O login por chave está desligado neste servidor (PubkeyAuthentication no). Desligar a "
                + "senha agora trancaria você para fora. O app não faz isso.");
        }
        String metodos = e.metodos();
        if (metodos != null && !metodos.equals("any") && !metodos.equals("publickey")) {
            return new Bloqueio(Motivo.METODOS_COMBINADOS, "O servidor exige uma combinação de métodos de entrada (" + metodos + "), por exemplo "
                + "chave mais um código. Mudar a senha aí poderia trancar todo mundo. O app não mexe nisso.");
        }
        if (!AnaliseDoSshd.pastaExiste(pastaTexto)) {
            return new Bloqueio(Motivo.NAO_LE_A_PASTA, "Este servidor não tem a pasta " + ComandosDoAcesso.PASTA_DA_CONFIG + ". O app não edita o "
                + "arquivo principal do sshd.");
        }
        AnaliseDoSshd.Previsao previsao = AnaliseDoSshd.prever(principal, semOProprio, conteudoDaRegra(LocalDateTime.of(2000, 1, 1, 0, 0)));
        if (!previsao.incluiAPasta()) {
            return new Bloqueio(Motivo.NAO_LE_A_PASTA, "O arquivo principal do sshd não lê a pasta " + ComandosDoAcesso.PASTA_DA_CONFIG
                + ", então a regra do app seria ignorada. O app não edita o arquivo principal.");
        }
        List<String> atrapalham = new ArrayList<>();
        if (!previsao.nossaVale(previsao.senha()) && !"no".equals(previsao.senha().valor())) {
            atrapalham.add(previsao.senha().arquivo() == null ? "padrão do sshd" : previsao.senha().arquivo());
        }
        if (Boolean.TRUE.equals(e.teclado()) && !previsao.nossaVale(previsao.teclado()) && !"no".equals(previsao.teclado().valor())) {
            atrapalham.add(previsao.teclado().arquivo() == null ? "padrão do sshd" : previsao.teclado().arquivo());
        }
        if (!atrapalham.isEmpty()) {
            return new Bloqueio(Motivo.ARQUIVO_ANTERIOR, "O arquivo " + String.join(" e ", atrapalham.stream().distinct().toList()) + " define a regra "
                + "antes do arquivo do app, e o sshd vale o primeiro valor que lê. A regra do app não teria efeito. O app não mexe em arquivos "
                + "que não são dele: edite esse arquivo você mesmo.");
        }
        return null;
    }

    private static Permissao lerPermissao(String saida) {
        boolean root = false;
        boolean sudo = false;
        for (String l : saida.split("\\R")) {
            String t = l.strip();
            if (t.equals("uid=0")) {
                root = true;
            }
            if (t.equals("sudo=sim")) {
                sudo = true;
            }
        }
        return root ? Permissao.ADMINISTRADOR : sudo ? Permissao.SUDO_SEM_SENHA : Permissao.NENHUMA;
    }

    // =====================================================================================================
    // Fase 2: escreve (só depois do clique e do nome digitado)
    // =====================================================================================================

    /** O que já foi feito no servidor até agora (para saber o que desfazer). As marcas valem desde ANTES de o comando ser enviado: uma queda no meio conta como "talvez". */
    private static final class Andamento {
        boolean desfazerArmado;
        boolean gravado;
        boolean recarregado;
        String copia;
    }

    /**
     * Desliga o login por senha. Só depois do clique do usuário e do nome do servidor digitado; a tela nunca chama isto sozinha.
     *
     * @param avaliacao o que {@link #avaliar()} encontrou (se não pode desligar, não escreve nada)
     */
    public Resultado desligar(Avaliacao avaliacao, Observador observador) {
        String reverter = ComandosDoAcesso.comandoParaReverter();
        if (avaliacao.bloqueio() != null) {
            Desfecho d = avaliacao.bloqueio().motivo() == Motivo.JA_DESLIGADA ? Desfecho.JA_ESTAVA_DESLIGADA : Desfecho.NAO_APLICADA;
            return new Resultado(d, avaliacao.bloqueio().texto(), List.of(), avaliacao.senhaLigada(), reverter, null);
        }
        String p = avaliacao.prefixo();
        LocalDateTime agora = relogio.get();
        String carimbo = CARIMBO.format(agora);
        boolean tinhaProprio = avaliacao.temArquivoProprio();
        Andamento a = new Andamento();
        a.copia = tinhaProprio ? ComandosDoAcesso.nomeDaCopiaDoProprio(carimbo) : ComandosDoAcesso.nomeDaCopiaDoPrincipal(carimbo);
        List<String> detalhes = new ArrayList<>(avaliacao.avisos());
        try {
            // 1. cópia datada do que o app REALMENTE altera (o arquivo dele, se já existia); a do sshd_config é só um extra
            observador.passo(Passo.COPIA, Estado.ANDANDO, "");
            if (tinhaProprio) {
                ResultadoExec copiaProprio = rodar(ComandosDoAcesso.copiaDoArquivoProprio(p, carimbo));
                if (copiaProprio.status() != 0) {
                    observador.passo(Passo.COPIA, Estado.FALHOU, "A cópia do arquivo do app não foi feita.");
                    return new Resultado(Desfecho.FALHOU_SEM_EFEITO, "Não consegui guardar a versão anterior do arquivo do app, então não mexi em nada.",
                        List.of(erroCurto(copiaProprio)), avaliacao.senhaLigada(), reverter, null);
                }
            }
            ResultadoExec copiaPrincipal = rodar(ComandosDoAcesso.copiaDoArquivoPrincipal(p, carimbo));
            if (copiaPrincipal.status() == 0) {
                detalhes.add("Cópia do arquivo principal do sshd: " + ComandosDoAcesso.nomeDaCopiaDoPrincipal(carimbo) + " (o app não o altera; pode apagá-la quando quiser).");
            } else {
                detalhes.add("A cópia extra do sshd_config não foi feita; não era necessária, porque o app não altera esse arquivo.");
            }
            observador.passo(Passo.COPIA, Estado.FEITO, tinhaProprio ? ComandosDoAcesso.nomeDaCopiaDoProprio(carimbo) : "o app só cria um arquivo novo");

            // 2. armar o desfazer NO SERVIDOR, antes de qualquer mudança
            observador.passo(Passo.ARMAR, Estado.ANDANDO, "");
            a.desfazerArmado = true;
            ResultadoExec armou = rodar(ComandosDoAcesso.armarDesfazerNoServidor(p, carimbo, tinhaProprio));
            if (armou.status() != 0) {
                a.desfazerArmado = false;
                observador.passo(Passo.ARMAR, Estado.FALHOU, "Não consegui armar o desfazer.");
                return new Resultado(Desfecho.FALHOU_SEM_EFEITO, "Não consegui armar o desfazer automático no servidor, então não mexi na configuração do SSH.",
                    List.of(erroCurto(armou)), avaliacao.senhaLigada(), reverter, a.copia);
            }
            observador.passo(Passo.ARMAR, Estado.FEITO, "se o app não cancelar, o servidor desfaz sozinho em " + ComandosDoAcesso.PRAZO_DO_DESFAZER_NO_SERVIDOR + " s");

            // 3. gravar
            observador.passo(Passo.GRAVAR, Estado.ANDANDO, "");
            a.gravado = true;
            ResultadoExec gravou = rodar(ComandosDoAcesso.gravarArquivoProprio(p), conteudoDaRegra(agora).getBytes(StandardCharsets.UTF_8));
            if (gravou.status() != 0) {
                observador.passo(Passo.GRAVAR, Estado.FALHOU, "Não consegui gravar o arquivo.");
                return desfazerESair(avaliacao, observador, a, carimbo, Desfecho.FALHOU_SEM_EFEITO, "Não consegui gravar o arquivo do app em "
                    + ComandosDoAcesso.PASTA_DA_CONFIG + ".", List.of(erroCurto(gravou)), reverter);
            }
            observador.passo(Passo.GRAVAR, Estado.FEITO, ComandosDoAcesso.ARQUIVO_PROPRIO);

            // 4. validar ANTES de aplicar
            observador.passo(Passo.VALIDAR, Estado.ANDANDO, "");
            ResultadoExec valido = rodar(ComandosDoAcesso.validarConfiguracao(p));
            if (valido.status() != 0) {
                observador.passo(Passo.VALIDAR, Estado.FALHOU, "O sshd rejeitou a configuração.");
                return desfazerESair(avaliacao, observador, a, carimbo, Desfecho.FALHOU_SEM_EFEITO, "O sshd não aceitou a configuração com a regra do app, então nada foi "
                    + "recarregado e o arquivo foi desfeito.", List.of(erroCurto(valido)), reverter);
            }
            observador.passo(Passo.VALIDAR, Estado.FEITO, "sshd -t sem erros.");

            // 5. recarregar (nunca reiniciar)
            observador.passo(Passo.RECARREGAR, Estado.ANDANDO, "");
            a.recarregado = true;
            ResultadoExec recarregou = rodar(ComandosDoAcesso.recarregarSshd(p));
            if (recarregou.status() != 0) {
                a.recarregado = false; // o servidor respondeu que NÃO recarregou
                observador.passo(Passo.RECARREGAR, Estado.FALHOU, "Não consegui recarregar o sshd.");
                return desfazerESair(avaliacao, observador, a, carimbo, Desfecho.FALHOU_SEM_EFEITO, "Não consegui recarregar o sshd (o serviço pode ter outro nome neste "
                    + "servidor). O arquivo foi desfeito e o servidor continua como estava.", List.of(erroCurto(recarregou)), reverter);
            }
            observador.passo(Passo.RECARREGAR, Estado.FEITO, "sshd recarregado; a sua sessão continua aberta.");

            // 6. provar com conexão nova só por chave (algumas tentativas, só se foi problema de rede)
            observador.passo(Passo.PROVAR, Estado.ANDANDO, "");
            try {
                provarComTentativas();
            } catch (FalhaDeEntradaPorSenha falha) {
                observador.passo(Passo.PROVAR, Estado.FALHOU, "A chave não entrou.");
                return desfazerESair(avaliacao, observador, a, carimbo, Desfecho.DESFEITA, "Depois de desligar a senha, a conexão nova só com a chave NÃO entrou ("
                    + falha.getMessage() + ")", List.of(), reverter);
            }
            observador.passo(Passo.PROVAR, Estado.FEITO, "A chave entrou numa conexão nova.");

            // 7. conferir o efeito: configuração geral, a do usuário (Match) e o que o servidor oferece numa conexão nova
            observador.passo(Passo.CONFERIR, Estado.ANDANDO, "");
            ResultadoExec geral = rodar(ComandosDoAcesso.lerConfiguracaoEfetiva(p));
            AnaliseDoSshd.Efetiva efGeral = geral.status() == 0 ? AnaliseDoSshd.lerEfetiva(geral.stdout()) : null;
            AnaliseDoSshd.Efetiva efUsuario = lerConfiguracaoDoUsuario(p);
            List<String> oferecidos = lerMetodosOferecidos();
            boolean geralAindaAceita = efGeral != null && (Boolean.TRUE.equals(efGeral.senha()) || Boolean.TRUE.equals(efGeral.teclado()));
            boolean ofereceSenha = oferecidos != null && (oferecidos.contains("password") || oferecidos.contains("keyboard-interactive"));
            if (geralAindaAceita || ofereceSenha) {
                observador.passo(Passo.CONFERIR, Estado.FALHOU, ofereceSenha ? "O servidor ainda oferece senha." : "O servidor ainda aceita senha.");
                return desfazerESair(avaliacao, observador, a, carimbo, Desfecho.DESFEITA, ofereceSenha
                    ? "O servidor continua oferecendo senha numa conexão nova, mesmo com a regra gravada (alguma outra configuração manda mais)"
                    : "O servidor continua aceitando senha mesmo com a regra gravada (alguma outra configuração manda mais)", List.of(), reverter);
            }
            boolean usuarioAindaAceita = efUsuario != null && (Boolean.TRUE.equals(efUsuario.senha()) || Boolean.TRUE.equals(efUsuario.teclado()));
            boolean confirmouGeral = efGeral != null && Boolean.FALSE.equals(efGeral.senha()) && !Boolean.TRUE.equals(efGeral.teclado());
            boolean confirmouOferta = oferecidos != null;
            observador.passo(Passo.CONFERIR, Estado.FEITO, usuarioAindaAceita ? "um bloco Match ainda aceita senha"
                : confirmouGeral && confirmouOferta ? "passwordauthentication no, e o servidor só oferece chave" : "não consegui conferir tudo");

            // 8. só agora o desfazer automático sai de cena
            observador.passo(Passo.CANCELAR, Estado.ANDANDO, "");
            Cancelamento cancelou = cancelarDesfazer(p, carimbo);
            if (cancelou == Cancelamento.JA_DISPAROU) {
                observador.passo(Passo.CANCELAR, Estado.FALHOU, "O desfazer automático já tinha disparado.");
                return new Resultado(Desfecho.DESFEITA, "O desfazer automático do servidor disparou antes de o app terminar (a operação demorou mais de "
                    + ComandosDoAcesso.PRAZO_DO_DESFAZER_NO_SERVIDOR + " s), então o servidor VOLTOU ao que era. Pode tentar de novo.", List.copyOf(detalhes),
                    Boolean.TRUE, reverter, a.copia);
            }
            if (cancelou == Cancelamento.PENDENTE) {
                observador.passo(Passo.CANCELAR, Estado.FALHOU, "Não consegui cancelar o desfazer automático.");
                return new Resultado(Desfecho.PRECISA_DE_AJUDA, "A regra está aplicada e a chave entra, mas o app NÃO conseguiu cancelar o desfazer automático do servidor: "
                    + "em poucos minutos ele vai voltar o servidor a aceitar senha por conta própria. Para impedir, rode no servidor: sudo systemctl stop "
                    + ComandosDoAcesso.nomeDoDesfazerNoServidor(carimbo) + ".timer", List.copyOf(detalhes), efGeral == null ? null : efGeral.senha(), reverter, a.copia);
            }
            observador.passo(Passo.CANCELAR, Estado.FEITO, "");
            detalhes.add("Servidores que o provedor ou uma ferramenta de configuração (cloud-init, painel) gerencia podem desfazer esta mudança sozinhos: se a senha voltar, é isso.");
            detalhes.add("Agentes do provedor (guest agent, droplet-agent, OS Login) podem reescrever o authorized_keys: se a chave sumir, é isso.");
            if (usuarioAindaAceita) {
                detalhes.add("Conferi a configuração para o usuário " + usuario + " vindo deste endereço: um bloco Match ainda aceita senha nesse caso. O app não mexe em Match.");
                return new Resultado(Desfecho.PARCIAL, "O login por senha foi desligado na regra geral, mas um bloco Match ainda aceita senha para este usuário e este "
                    + "endereço. A chave segue entrando.", List.copyOf(detalhes), Boolean.TRUE, reverter, a.copia);
            }
            if (!(confirmouGeral && confirmouOferta)) {
                if (efUsuario == null) {
                    detalhes.add("Não consegui conferir a configuração para este usuário e este endereço: se houver bloco Match, ele não foi conferido.");
                }
                detalhes.add("A prova da chave passou e o arquivo está gravado.");
                return new Resultado(Desfecho.SEM_CONFERENCIA, "A chave entra e a regra está gravada, mas NÃO consegui conferir se a senha está mesmo desligada neste servidor.",
                    List.copyOf(detalhes), efGeral == null ? null : efGeral.senha(), reverter, a.copia);
            }
            if (efUsuario == null) {
                detalhes.add("Não consegui conferir a configuração para este usuário e este endereço: se houver bloco Match, ele não foi conferido.");
            }
            return new Resultado(Desfecho.DESLIGADA, "Login por senha: desligado. O servidor só oferece a chave numa conexão nova.", List.copyOf(detalhes), Boolean.FALSE,
                reverter, a.copia);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Desligar a senha parou por um erro de conexão: {}", Redator.redigir(e));
            return recuperarDepoisDeErro(avaliacao, observador, a, carimbo, reverter, "A conexão com o servidor parou no meio (" + Redator.redigir(e) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return incerto(a, carimbo, reverter, "O app foi interrompido no meio");
        }
    }

    private enum Cancelamento { CANCELADO, JA_DISPAROU, PENDENTE }

    /** Para o timer do servidor. Se não consegue, confere o estado: ou ele já disparou (e o servidor voltou) ou continua pendente. */
    private Cancelamento cancelarDesfazer(String p, String carimbo) throws IOException, InterruptedException {
        for (int i = 0; i < 2; i++) {
            if (rodar(ComandosDoAcesso.cancelarDesfazerNoServidor(p, carimbo)).status() == 0) {
                return Cancelamento.CANCELADO;
            }
            ResultadoExec geral = rodar(ComandosDoAcesso.lerConfiguracaoEfetiva(p));
            if (geral.status() == 0) {
                AnaliseDoSshd.Efetiva e = AnaliseDoSshd.lerEfetiva(geral.stdout());
                if (Boolean.TRUE.equals(e.senha())) {
                    return Cancelamento.JA_DISPAROU;
                }
            }
        }
        return Cancelamento.PENDENTE;
    }

    private void provarComTentativas() throws FalhaDeEntradaPorSenha, InterruptedException {
        for (int tentativa = 1; ; tentativa++) {
            try {
                prova.provar();
                return;
            } catch (FalhaDeEntradaPorSenha f) {
                // só falha de REDE merece outra tentativa (o sshd acabou de recarregar); chave recusada é resposta, não azar
                if (f.motivo() != FalhaDeEntradaPorSenha.Motivo.SEM_CONEXAO || tentativa >= TENTATIVAS_DA_PROVA) {
                    throw f;
                }
                esperar();
            }
        }
    }

    private void esperar() throws InterruptedException {
        if (!intervaloDaProva.isZero()) {
            Thread.sleep(intervaloDaProva.toMillis());
        }
    }

    private AnaliseDoSshd.Efetiva lerConfiguracaoDoUsuario(String p) throws IOException, InterruptedException {
        if (usuario == null) {
            return null;
        }
        try {
            ResultadoExec r = rodar(ComandosDoAcesso.lerConfiguracaoParaEsteUsuario(p, usuario));
            return r.status() == 0 ? AnaliseDoSshd.lerEfetiva(r.stdout()) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> lerMetodosOferecidos() throws InterruptedException {
        if (sonda == null) {
            return null;
        }
        try {
            return sonda.ler();
        } catch (RuntimeException e) {
            LOG.debug("A sonda dos métodos oferecidos falhou: {}", Redator.redigir(e));
            return null;
        }
    }

    /**
     * A conexão caiu (ou outro erro) no meio. O app NÃO sabe o que ficou: tenta ler o estado real do servidor (algumas vezes, dando tempo da conexão voltar); se lê, desfaz o que
     * houver e diz que conferiu; se não lê, diz que NÃO sabe e que o servidor desfaz sozinho.
     */
    private Resultado recuperarDepoisDeErro(Avaliacao avaliacao, Observador observador, Andamento a, String carimbo, String reverter, String motivo) {
        if (!a.desfazerArmado && !a.gravado && !a.recarregado) {
            return new Resultado(Desfecho.FALHOU_SEM_EFEITO, motivo + ", antes de qualquer mudança na configuração do SSH (só cópias de segurança podem ter sido feitas).", List.of(),
                avaliacao.senhaLigada(), reverter, a.copia);
        }
        String p = avaliacao.prefixo();
        for (int i = 0; i < 3; i++) {
            try {
                ResultadoExec arquivo = rodar(ComandosDoAcesso.lerArquivoProprio(p));
                ResultadoExec geral = rodar(ComandosDoAcesso.lerConfiguracaoEfetiva(p));
                if (arquivo.status() == 0 && geral.status() == 0) {
                    boolean existe = !arquivo.stdout().isBlank();
                    Resultado r = desfazerESair(avaliacao, observador, a, carimbo, Desfecho.DESFEITA, motivo + ". Conferi o servidor (o arquivo do app "
                        + (existe ? "existia" : "não existia") + ")", List.of(), reverter);
                    return r;
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug("Ainda sem conexão para reler o servidor: {}", Redator.redigir(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                esperar();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return incerto(a, carimbo, reverter, motivo);
    }

    private static Resultado incerto(Andamento a, String carimbo, String reverter, String motivo) {
        String armado = a.desfazerArmado ? " O desfazer automático foi armado no servidor: em até " + ComandosDoAcesso.PRAZO_DO_DESFAZER_NO_SERVIDOR / 60
            + " minutos ele apaga a regra do app e recarrega o sshd sozinho." : " O desfazer automático NÃO chegou a ser armado.";
        return new Resultado(Desfecho.INCERTO, motivo + ". O app NÃO sabe se a regra chegou a ser gravada ou aplicada." + armado + " Reconecte, espere esse prazo e confira em "
            + "Servidor > Segurança > SSH e bloqueio. Para voltar atrás à mão, rode no servidor o comando de reverter abaixo.", List.of(
            "Se o desfazer automático estiver ativo, para cancelá-lo: sudo systemctl stop " + ComandosDoAcesso.nomeDoDesfazerNoServidor(carimbo) + ".timer"), null, reverter, a.copia);
    }

    /** Apaga (ou restaura) o arquivo do app, recarrega se já tinha recarregado, confere e para o timer; diz o que aconteceu. */
    private Resultado desfazerESair(Avaliacao avaliacao, Observador observador, Andamento a, String carimbo, Desfecho desfechoSeDerCerto, String motivo, List<String> extras,
                                    String reverter) {
        String p = avaliacao.prefixo();
        boolean desfez = true;
        List<String> detalhes = new ArrayList<>(extras);
        Boolean senhaDepois = null;
        try {
            if (!desfazerArquivo(p, carimbo, avaliacao.temArquivoProprio())) {
                desfez = false;
            }
            if (desfez && a.recarregado) {
                ResultadoExec valido = rodar(ComandosDoAcesso.validarConfiguracao(p));
                ResultadoExec recarregou = valido.status() == 0 ? rodar(ComandosDoAcesso.recarregarSshd(p)) : valido;
                if (recarregou.status() != 0) {
                    desfez = false;
                    detalhes.add(erroCurto(recarregou));
                }
            }
            if (desfez) {
                ResultadoExec geral = rodar(ComandosDoAcesso.lerConfiguracaoEfetiva(p));
                if (geral.status() == 0) {
                    senhaDepois = AnaliseDoSshd.lerEfetiva(geral.stdout()).senha();
                }
                if (a.desfazerArmado) {
                    rodar(ComandosDoAcesso.cancelarDesfazerNoServidor(p, carimbo)); // melhor esforço: se não parar, ele só repete o que já foi feito
                }
            }
        } catch (IOException | RuntimeException e) {
            desfez = false;
            detalhes.add("A conexão caiu ao desfazer: " + Redator.redigir(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            desfez = false;
        }
        if (desfez) {
            observador.passo(Passo.GRAVAR, Estado.DESFEITO, "Arquivo do app removido" + (a.recarregado ? " e sshd recarregado." : "."));
            detalhes.add(senhaDepois == null ? "Não consegui reler a configuração depois de desfazer." : senhaDepois
                ? "Conferi: o servidor voltou a aceitar senha, como era antes."
                : "Atenção: depois de desfazer, o sshd -T ainda mostra a senha desligada (a regra pode vir de outro arquivo). Confira em Segurança.");
            Desfecho d = desfechoSeDerCerto;
            if (d == Desfecho.DESFEITA) {
                return new Resultado(d, motivo + ", então o app DESFEZ o que tinha feito" + (a.recarregado ? " e recarregou o sshd" : "") + ".", List.copyOf(detalhes),
                    senhaDepois, reverter, a.copia);
            }
            return new Resultado(d, motivo + " O app desfez o que tinha gravado.", List.copyOf(detalhes), senhaDepois, reverter, a.copia);
        }
        detalhes.add("O desfazer automático do servidor, se estiver ativo, repete isto em até " + ComandosDoAcesso.PRAZO_DO_DESFAZER_NO_SERVIDOR / 60 + " minutos.");
        detalhes.add("Para desfazer à mão, no servidor: " + reverter);
        return new Resultado(Desfecho.PRECISA_DE_AJUDA, motivo + ", e o app NÃO conseguiu desfazer sozinho. Rode você mesmo o comando abaixo no "
            + "servidor para apagar o arquivo do app e recarregar o sshd.", List.copyOf(detalhes), null, reverter, a.copia);
    }

    /** Apaga o arquivo do app, ou devolve a versão anterior dele. {@code true} se terminou bem. */
    private boolean desfazerArquivo(String p, String carimbo, boolean tinhaProprio) {
        try {
            ResultadoExec r = tinhaProprio ? rodar(ComandosDoAcesso.restaurarArquivoProprio(p, carimbo)) : rodar(ComandosDoAcesso.apagarArquivoProprio(p));
            return r.status() == 0;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Não consegui desfazer o arquivo do app: {}", Redator.redigir(e));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String erroCurto(ResultadoExec r) {
        String texto = Redator.redigir(r.stderr()).strip();
        if (texto.isEmpty()) {
            texto = "o comando terminou com código " + r.status();
        }
        return "Resposta do servidor: " + (texto.length() > 300 ? texto.substring(0, 299) + "…" : texto);
    }

    private ResultadoExec rodar(String comando) throws IOException, InterruptedException {
        return rodar(comando, SEM_ENTRADA);
    }

    private ResultadoExec rodar(String comando, byte[] entrada) throws IOException, InterruptedException {
        return executor.executar(comando, entrada, PRAZO);
    }
}
