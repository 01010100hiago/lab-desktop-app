package br.com.labdesktop.acesso;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ssh.ProvaDeChave;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
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
 *   <li>{@link #avaliar()}: só LEITURA. Vê se o usuário é root ou tem {@code sudo -n}, lê a configuração efetiva ({@code sshd -T}) e os arquivos
 *       ({@code sshd_config} e {@code sshd_config.d}), prevê se a regra do app valeria no meio do que já existe e diz o que encontrou, em frases
 *       simples. Recusa o que não é seguro (métodos combinados, login por chave desligado, arquivo anterior que manda mais, pasta que não é lida).</li>
 *   <li>{@link #desligar}: cópia datada do arquivo, grava {@code sshd_config.d/10-lab-desktop.conf}, valida com {@code sshd -t} ANTES de aplicar,
 *       RECARREGA (nunca reinicia) o sshd, prova com uma conexão NOVA só por chave que ainda entra e confere {@code sshd -T}. Se a prova ou a
 *       conferência falha, desfaz sozinho o que acabou de fazer (apaga ou restaura o arquivo e recarrega) e diz o que aconteceu. Esse desfazer
 *       é só desta operação; depois dela o app NUNCA religa a senha: mostra o comando de reverter.</li>
 * </ol>
 * Sem permissão (nem root, nem {@code sudo -n}) o app NÃO tenta e NÃO pede senha de sudo: mostra o comando exato para o usuário rodar. Todos os
 * comandos são os fixos de {@link ComandosDoAcesso}. Bloqueia (SSH): thread de fundo.
 */
public final class DesligadorDeSenha {

    private static final Logger LOG = LoggerFactory.getLogger(DesligadorDeSenha.class);
    private static final Duration PRAZO = Duration.ofSeconds(30);
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
        COPIA("Copiar o arquivo do sshd (cópia datada)"),
        GRAVAR("Gravar a regra do app"),
        VALIDAR("Conferir a configuração antes de aplicar"),
        RECARREGAR("Recarregar o sshd (sem reiniciar)"),
        PROVAR("Entrar de novo só com a chave"),
        CONFERIR("Conferir o resultado no servidor");

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
        /** Desligou e conferiu: o servidor não aceita mais senha. */
        DESLIGADA,
        /** Já estava desligado: nada foi mexido. */
        JA_ESTAVA_DESLIGADA,
        /** O app não podia (sem permissão, ou algo inseguro): nada foi mexido; veja o texto. */
        NAO_APLICADA,
        /** Falhou antes de recarregar (cópia, gravação ou validação): o que foi gravado foi desfeito e o sshd nem foi tocado. */
        FALHOU_SEM_EFEITO,
        /** Recarregou, mas a prova da chave ou a conferência falhou: o app desfez e recarregou de novo. */
        DESFEITA,
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

    /**
     * @param executor a conexão SSH que o app já tem (ou um falso, nos testes)
     * @param prova    abre uma conexão NOVA só com a chave e prova que entra
     * @param relogio  a data e a hora de agora (o carimbo da cópia)
     */
    public DesligadorDeSenha(ProvisionadorRemoto.Executor executor, ProvaDeChave prova, Supplier<LocalDateTime> relogio) {
        this.executor = executor;
        this.prova = prova;
        this.relogio = relogio;
    }

    /** O conteúdo que o app grava no arquivo dele. */
    public static String conteudoDaRegra(LocalDateTime agora) {
        return "# Criado pelo Lab Desktop em " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(agora) + ".\n"
            + "# Para voltar ao login por senha: apague este arquivo e recarregue o ssh (systemctl reload ssh).\n"
            + "PasswordAuthentication no\n"
            + "KbdInteractiveAuthentication no\n";
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
                    + "senha, rode você mesmo, no servidor, o comando abaixo."));
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
            avisos.add("A configuração tem um bloco Match (regras para certos usuários ou endereços). O app muda só a regra geral: nesses casos "
                + "o servidor ainda pode aceitar senha.");
        }

        Bloqueio bloqueio = decidir(efetiva, pastaTexto.stdout(), principal.stdout(), semOProprio, temArquivoProprio, quemMexe);
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
        Andamento atual = new Andamento();
        atual.copia = ComandosDoAcesso.nomeDaCopiaDoPrincipal(carimbo);
        try {
            // 1. cópia datada
            observador.passo(Passo.COPIA, Estado.ANDANDO, "");
            ResultadoExec copia = rodar(ComandosDoAcesso.copiaDoArquivoPrincipal(p, carimbo));
            if (copia.status() != 0) {
                observador.passo(Passo.COPIA, Estado.FALHOU, "A cópia não foi feita.");
                return new Resultado(Desfecho.FALHOU_SEM_EFEITO, "Não consegui fazer a cópia de segurança do arquivo do sshd. Nada foi alterado.",
                    List.of(erroCurto(copia)), avaliacao.senhaLigada(), reverter, null);
            }
            if (tinhaProprio) {
                ResultadoExec copiaProprio = rodar(ComandosDoAcesso.copiaDoArquivoProprio(p, carimbo));
                if (copiaProprio.status() != 0) {
                    observador.passo(Passo.COPIA, Estado.FALHOU, "A cópia do arquivo do app não foi feita.");
                    return new Resultado(Desfecho.FALHOU_SEM_EFEITO, "Não consegui guardar a versão anterior do arquivo do app. Nada foi alterado.",
                        List.of(erroCurto(copiaProprio)), avaliacao.senhaLigada(), reverter, null);
                }
            }
            observador.passo(Passo.COPIA, Estado.FEITO, ComandosDoAcesso.nomeDaCopiaDoPrincipal(carimbo));

            // 2. gravar
            observador.passo(Passo.GRAVAR, Estado.ANDANDO, "");
            ResultadoExec gravou = rodar(ComandosDoAcesso.gravarArquivoProprio(p), conteudoDaRegra(agora).getBytes(StandardCharsets.UTF_8));
            atual.gravado = true;
            if (gravou.status() != 0) {
                observador.passo(Passo.GRAVAR, Estado.FALHOU, "Não consegui gravar o arquivo.");
                desfazerArquivo(p, carimbo, tinhaProprio);
                return new Resultado(Desfecho.FALHOU_SEM_EFEITO, "Não consegui gravar o arquivo do app em " + ComandosDoAcesso.PASTA_DA_CONFIG
                    + ". Nada foi alterado.", List.of(erroCurto(gravou)), avaliacao.senhaLigada(), reverter, atual.copia);
            }
            observador.passo(Passo.GRAVAR, Estado.FEITO, ComandosDoAcesso.ARQUIVO_PROPRIO);

            // 3. validar ANTES de aplicar
            observador.passo(Passo.VALIDAR, Estado.ANDANDO, "");
            ResultadoExec valido = rodar(ComandosDoAcesso.validarConfiguracao(p));
            if (valido.status() != 0) {
                observador.passo(Passo.VALIDAR, Estado.FALHOU, "O sshd rejeitou a configuração.");
                desfazerArquivo(p, carimbo, tinhaProprio);
                observador.passo(Passo.GRAVAR, Estado.DESFEITO, "Arquivo apagado.");
                return new Resultado(Desfecho.FALHOU_SEM_EFEITO, "O sshd não aceitou a configuração com a regra do app, então nada foi recarregado "
                    + "e o arquivo foi desfeito.", List.of(erroCurto(valido)), avaliacao.senhaLigada(), reverter, atual.copia);
            }
            observador.passo(Passo.VALIDAR, Estado.FEITO, "sshd -t sem erros.");

            // 4. recarregar (nunca reiniciar)
            observador.passo(Passo.RECARREGAR, Estado.ANDANDO, "");
            ResultadoExec recarregou = rodar(ComandosDoAcesso.recarregarSshd(p));
            if (recarregou.status() != 0) {
                observador.passo(Passo.RECARREGAR, Estado.FALHOU, "Não consegui recarregar o sshd.");
                desfazerArquivo(p, carimbo, tinhaProprio);
                observador.passo(Passo.GRAVAR, Estado.DESFEITO, "Arquivo apagado.");
                return new Resultado(Desfecho.FALHOU_SEM_EFEITO, "Não consegui recarregar o sshd (o serviço pode ter outro nome neste servidor). O "
                    + "arquivo foi desfeito e o servidor continua como estava.", List.of(erroCurto(recarregou)), avaliacao.senhaLigada(), reverter,
                    atual.copia);
            }
            atual.recarregado = true;
            observador.passo(Passo.RECARREGAR, Estado.FEITO, "sshd recarregado; a sua sessão continua aberta.");

            // 5. provar com conexão nova só por chave
            observador.passo(Passo.PROVAR, Estado.ANDANDO, "");
            try {
                prova.provar();
            } catch (FalhaDeEntradaPorSenha falha) {
                observador.passo(Passo.PROVAR, Estado.FALHOU, "A chave não entrou.");
                return desfazerTudo(avaliacao, observador, atual, carimbo, "Depois de desligar a senha, a conexão nova só com a chave NÃO entrou ("
                    + falha.getMessage() + ")", reverter);
            }
            observador.passo(Passo.PROVAR, Estado.FEITO, "A chave entrou numa conexão nova.");

            // 6. conferir o efeito
            observador.passo(Passo.CONFERIR, Estado.ANDANDO, "");
            ResultadoExec depois = rodar(ComandosDoAcesso.lerConfiguracaoEfetiva(p));
            Boolean senhaDepois = depois.status() == 0 ? AnaliseDoSshd.lerEfetiva(depois.stdout()).senha() : null;
            Boolean tecladoDepois = depois.status() == 0 ? AnaliseDoSshd.lerEfetiva(depois.stdout()).teclado() : null;
            if (Boolean.TRUE.equals(senhaDepois) || Boolean.TRUE.equals(tecladoDepois)) {
                observador.passo(Passo.CONFERIR, Estado.FALHOU, "O servidor ainda aceita senha.");
                return desfazerTudo(avaliacao, observador, atual, carimbo, "O servidor continua aceitando senha mesmo com a regra gravada (alguma outra "
                    + "configuração manda mais)", reverter);
            }
            observador.passo(Passo.CONFERIR, Estado.FEITO, senhaDepois == null ? "Não consegui conferir." : "passwordauthentication no");
            List<String> detalhes = new ArrayList<>(avaliacao.avisos());
            detalhes.add("Cópia do arquivo do sshd: " + atual.copia + ".");
            detalhes.add("Servidores que o provedor ou uma ferramenta de configuração (cloud-init, painel) gerencia podem desfazer esta mudança sozinhos: "
                + "se a senha voltar, é isso.");
            if (senhaDepois == null) {
                detalhes.add("Não consegui reler a configuração para confirmar; a prova da chave passou e o arquivo está gravado.");
            }
            return new Resultado(Desfecho.DESLIGADA, "Login por senha: desligado. O servidor só aceita a chave.", List.copyOf(detalhes), senhaDepois,
                reverter, atual.copia);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Desligar a senha parou por um erro de conexão: {}", Redator.redigir(e));
            return recuperarDepoisDeErro(avaliacao, observador, atual, carimbo, reverter, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return recuperarDepoisDeErro(avaliacao, observador, atual, carimbo, reverter, e);
        }
    }

    /** O que já foi feito no servidor até agora (para saber o que desfazer). */
    private static final class Andamento {
        boolean gravado;
        boolean recarregado;
        String copia;
    }

    private Resultado recuperarDepoisDeErro(Avaliacao avaliacao, Observador observador, Andamento atual, String carimbo, String reverter, Exception e) {
        String motivo = "A conexão com o servidor parou no meio (" + Redator.redigir(e) + ")";
        if (!atual.gravado) {
            return new Resultado(Desfecho.FALHOU_SEM_EFEITO, motivo + ". Nada foi gravado.", List.of(), avaliacao.senhaLigada(), reverter, atual.copia);
        }
        return desfazerTudo(avaliacao, observador, atual, carimbo, motivo, reverter);
    }

    /** Apaga (ou restaura) o arquivo do app, recarrega se já tinha recarregado e prova de novo; diz o que aconteceu. */
    private Resultado desfazerTudo(Avaliacao avaliacao, Observador observador, Andamento atual, String carimbo, String motivo, String reverter) {
        String p = avaliacao.prefixo();
        boolean desfez = true;
        List<String> detalhes = new ArrayList<>();
        try {
            if (!desfazerArquivo(p, carimbo, avaliacao.temArquivoProprio())) {
                desfez = false;
            }
            if (desfez && atual.recarregado) {
                ResultadoExec valido = rodar(ComandosDoAcesso.validarConfiguracao(p));
                ResultadoExec recarregou = valido.status() == 0 ? rodar(ComandosDoAcesso.recarregarSshd(p)) : valido;
                if (recarregou.status() != 0) {
                    desfez = false;
                    detalhes.add(erroCurto(recarregou));
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
            observador.passo(Passo.GRAVAR, Estado.DESFEITO, "Arquivo do app removido e sshd recarregado.");
            detalhes.add("O login por senha continua como estava antes. Cópia do arquivo do sshd: " + atual.copia + ".");
            return new Resultado(Desfecho.DESFEITA, motivo + ", então o app DESFEZ o que tinha feito e recarregou o sshd. Nada ficou diferente do "
                + "que era.", List.copyOf(detalhes), avaliacao.senhaLigada(), reverter, atual.copia);
        }
        detalhes.add("Para desfazer à mão, no servidor: " + reverter);
        return new Resultado(Desfecho.PRECISA_DE_AJUDA, motivo + ", e o app NÃO conseguiu desfazer sozinho. Rode você mesmo o comando abaixo no "
            + "servidor para apagar o arquivo do app e recarregar o sshd.", List.copyOf(detalhes), avaliacao.senhaLigada(), reverter, atual.copia);
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
