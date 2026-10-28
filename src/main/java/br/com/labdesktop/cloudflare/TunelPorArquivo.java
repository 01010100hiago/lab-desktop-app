package br.com.labdesktop.cloudflare;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.Diferenca;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * O túnel gerido por ARQUIVO (o do usuário): as regras estão num {@code config.yml} no servidor, que o cloudflared lê quando sobe. O app o edita por SFTP e
 * reinicia SÓ o container do cloudflared.
 *
 * <p>Passos de uma mudança, nesta ordem, cada um só depois do clique de confirmação: (1) relê o arquivo e para se mudou desde que o usuário viu a diferença;
 * (2) guarda uma cópia datada ao lado ({@code config.yml.AAAAMMDD-HHMMSS.bak}); (3) grava o texto novo NO LUGAR (o mesmo arquivo, e não um novo trocado por cima:
 * o painel e o cloudflared do lab montam o ARQUIVO, e um arquivo novo os deixaria olhando o antigo; ver {@code ClienteSftp.gravarNoLugar}); (4) o cloudflared
 * confere as regras ({@code cloudflared tunnel ingress validate} dentro do container; falhou, devolve o arquivo e NADA é reiniciado); (5) reinicia o container e
 * espera ele voltar (não voltou, devolve o arquivo e reinicia de novo). O arquivo gravado antes do reinício não afeta o túnel que está no ar: o cloudflared só lê o
 * arquivo ao subir.
 *
 * <p>Premissas (documentadas em ARQUITETURA 7.11): o cloudflared roda num container Docker (o do usuário: {@code cloudflared}, com o arquivo em
 * {@code config.yml} do cloudflared); o usuário do SSH alcança o Docker (grupo docker, ou {@code sudo -n}); o container ENXERGA o arquivo (o caminho
 * de dentro dele sai do {@code docker inspect}, ou é o informado na tela). Cloudflared como serviço do sistema (systemd) não é coberto.
 */
public final class TunelPorArquivo implements Tunel {

    private static final int LIMITE_DO_ARQUIVO = 512 * 1024;
    /** Só se o servidor não disser o modo do arquivo: a cópia datada nasce com o MESMO modo do config.yml (nunca mais aberta que ele). */
    private static final int PERMISSAO_PADRAO_DA_COPIA = 0640;
    private static final Duration PRAZO_DO_COMANDO = Duration.ofSeconds(60);
    private static final Duration ESPERA_DO_REINICIO = Duration.ofSeconds(25);
    /** Quanto tempo o cloudflared tem de ficar de pé, sem cair nem reiniciar, para valer como "voltou". */
    static final Duration ESTAVEL_POR = Duration.ofSeconds(5);
    private static final Duration PASSO_DA_ESPERA = Duration.ofSeconds(1);
    private static final DateTimeFormatter DATA_DA_COPIA = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Quem espera (nos testes, não dorme). */
    @FunctionalInterface
    public interface Esperador {
        void esperar(Duration d) throws InterruptedException;
    }

    private final AmbienteDeProjetos ambiente;
    private final ConfiguracaoCloudflare cfg;
    private final boolean comSudo;
    private final Clock relogio;
    private final Esperador esperador;

    public TunelPorArquivo(AmbienteDeProjetos ambiente, ConfiguracaoCloudflare cfg, boolean comSudo) {
        this(ambiente, cfg, comSudo, Clock.systemDefaultZone(), d -> Thread.sleep(d));
    }

    public TunelPorArquivo(AmbienteDeProjetos ambiente, ConfiguracaoCloudflare cfg, boolean comSudo, Clock relogio, Esperador esperador) {
        if (!cfg.porArquivo()) {
            throw new IllegalArgumentException("Este túnel não é gerido por arquivo.");
        }
        this.ambiente = ambiente;
        this.cfg = cfg;
        this.comSudo = comSudo;
        this.relogio = relogio;
        this.esperador = esperador;
    }

    @Override
    public String onde() {
        return "o config.yml do servidor (" + cfg.caminhoDoConfig() + ")";
    }

    @Override
    public boolean reiniciaOCloudflared() {
        return true;
    }

    @Override
    public boolean regraTemMarca() {
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Leitura (nada muda)
    // ---------------------------------------------------------------------------------------------

    /** O que {@link #conferirServidor} acha: o arquivo, o túnel que ele diz e o container. */
    public record Conferencia(boolean arquivoExiste, String tunelDoArquivo, MontagensDoContainer container, List<String> problemas, List<String> avisos) {
        public Conferencia {
            problemas = List.copyOf(problemas);
            avisos = List.copyOf(avisos);
        }

        public boolean ok() {
            return problemas.isEmpty();
        }
    }

    /**
     * Confere o servidor para a tela "Conectar": o {@code config.yml} existe e tem o formato que o app edita? Diz qual túnel (qual UUID)? O container do cloudflared
     * existe e está rodando? Onde ele enxerga o arquivo? Só LEITURA (SFTP e {@code docker inspect}).
     */
    public static Conferencia conferirServidor(AmbienteDeProjetos ambiente, String caminhoDoConfig, String container, boolean comSudo) {
        List<String> problemas = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        boolean existe = false;
        String tunel = "";
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            byte[] bytes = lerBytes(c, caminhoDoConfig);
            if (bytes == null) {
                problemas.add("O arquivo " + caminhoDoConfig + " não existe no servidor (ou o seu usuário não o enxerga).");
            } else {
                existe = true;
                String texto = decodificar(bytes);
                if (texto == null) {
                    problemas.add("O config.yml não está em UTF-8: o app não mexe nele para não estragar os acentos.");
                } else {
                    try {
                        ConfigYmlDoCloudflared.Analise a = ConfigYmlDoCloudflared.analisar(texto);
                        tunel = a.tunel();
                        if (!a.temIngress() && !texto.isBlank()) {
                            problemas.add("Este config.yml não tem a seção \"ingress:\" (formato antigo, com url:). O app só edita o formato com regras de ingress.");
                        } else if (!a.temRegraFinal() && a.temIngress()) {
                            avisos.add("O config.yml não tem a regra final (http_status:404), que o cloudflared exige: o app a acrescenta junto com a primeira regra nova.");
                        }
                    } catch (ConfigYmlDoCloudflared.Problema p) {
                        problemas.add(p.getMessage());
                    }
                }
            }
        } catch (ErroSftp | FalhaDoProjeto e) {
            problemas.add("Não consegui ler o config.yml: " + (e instanceof FalhaDoProjeto f ? f.mensagem() : ((ErroSftp) e).mensagemSimples()));
        }
        MontagensDoContainer m = MontagensDoContainer.NAO_EXISTE;
        try {
            ResultadoExec r = ambiente.executor().executar(ComandosCloudflared.inspecionar(comSudo, container), PRAZO_DO_COMANDO);
            if (r.status() != 0) {
                problemas.add(explicarDocker(r.stderr(), container));
            } else {
                m = MontagensDoContainer.ler(r.stdout(), caminhoDoConfig);
                if (!m.existe()) {
                    problemas.add("O container " + container + " não existe no servidor.");
                } else {
                    if (!m.rodando()) {
                        problemas.add("O container " + container + " existe, mas não está rodando: suba o cloudflared antes de publicar.");
                    }
                    if (m.caminhoDoConfigNoContainer().isEmpty()) {
                        avisos.add("Não achei onde o container " + container + " enxerga o config.yml (nenhuma montagem dele leva a esse arquivo): informe o caminho de dentro do container, "
                            + "ou confira se o arquivo é mesmo o que o cloudflared usa.");
                    }
                }
            }
        } catch (IOException e) {
            problemas.add("Não consegui falar com o Docker do servidor (a conexão caiu ou o prazo acabou).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problemas.add("Cancelado.");
        } catch (IllegalArgumentException e) {
            problemas.add("O nome do container ou o caminho tem um caractere que o app não passa ao servidor.");
        }
        return new Conferencia(existe, tunel, m, problemas, avisos);
    }

    static String explicarDocker(String stderr, String container) {
        String m = stderr == null ? "" : stderr.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("permission denied")) {
            return "O usuário do app no servidor não alcança o Docker (precisa estar no grupo docker, ou ter sudo sem senha para o docker). O app nunca pede senha de administrador.";
        }
        if (m.contains("no such object") || m.contains("no such container") || m.contains("no such")) {
            return "O container " + container + " não existe no servidor.";
        }
        if (m.contains("cannot connect") || m.contains("is the docker daemon running")) {
            return "O Docker do servidor não está respondendo.";
        }
        String limpo = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(stderr)), 200);
        return "O Docker do servidor recusou a conferência" + (limpo.isEmpty() ? "." : ": " + limpo);
    }

    static byte[] lerBytes(ClienteSftp c, String caminho) throws ErroSftp, FalhaDoProjeto {
        try {
            byte[] bytes = c.lerArquivo(caminho, LIMITE_DO_ARQUIVO + 1);
            if (bytes.length > LIMITE_DO_ARQUIVO) {
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

    /** UTF-8 estrito; {@code null} se os bytes não são UTF-8 (ou não há arquivo). */
    static String decodificar(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    @Override
    public Mudanca prepararAcrescimo(String hostname, String servico, String observacao) throws FalhaDoProjeto {
        return preparar(false, hostname, servico, observacao);
    }

    @Override
    public Mudanca prepararRemocao(String hostname, String servicoEsperado) throws FalhaDoProjeto {
        return preparar(true, hostname, servicoEsperado, null);
    }

    private Mudanca preparar(boolean remover, String hostname, String servico, String observacao) throws FalhaDoProjeto {
        byte[] original;
        String texto;
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            original = lerBytes(c, cfg.caminhoDoConfig());
        } catch (ErroSftp e) {
            throw semConfig(e);
        }
        if (original == null) {
            return Bloqueada.de("O config.yml não existe em " + cfg.caminhoDoConfig() + ". Confira o caminho na tela Cloudflare do servidor.");
        }
        texto = decodificar(original);
        if (texto == null) {
            return Bloqueada.de("O config.yml não está em UTF-8: o app não mexe nele para não estragar os acentos. Acrescente a regra à mão:\n" + regraParaCopiar(hostname, servico));
        }
        List<String> avisos = new ArrayList<>();
        String novo;
        boolean semEfeito = false;
        try {
            ConfigYmlDoCloudflared.Analise analise = ConfigYmlDoCloudflared.analisar(texto);
            if (ValidadorDeNomes.uuidDoTunelValido(analise.tunel()) && !analise.tunel().equals(cfg.tunelId())) {
                return Bloqueada.de("O config.yml é do túnel " + analise.tunel() + ", e a tela Cloudflare está com o túnel " + cfg.tunelId() + ". Reconecte escolhendo o túnel certo.");
            }
            if (!ValidadorDeNomes.uuidDoTunelValido(analise.tunel())) {
                avisos.add("O config.yml usa o NOME do túnel (ou não o diz): o app usa o UUID escolhido na tela Cloudflare (" + cfg.tunelId() + ") para o DNS.");
            }
            if (remover) {
                try {
                    novo = ConfigYmlDoCloudflared.remover(texto, hostname);
                } catch (ConfigYmlDoCloudflared.Problema p) {
                    if (p.tipo() == ConfigYmlDoCloudflared.Problema.Tipo.NAO_ENCONTRADA) {
                        novo = texto;
                        semEfeito = true; // já não está lá
                    } else {
                        return Bloqueada.de(p.getMessage());
                    }
                }
            } else {
                ConfigYmlDoCloudflared.Edicao e = ConfigYmlDoCloudflared.acrescentar(texto, hostname, servico, observacao);
                novo = e.texto();
                semEfeito = e.jaEstava();
                avisos.addAll(e.avisos());
            }
        } catch (ConfigYmlDoCloudflared.Problema p) {
            return Bloqueada.de(p.getMessage() + (p.tipo() == ConfigYmlDoCloudflared.Problema.Tipo.FORMATO_NAO_SUPORTADO && !remover ? "\nPara acrescentar à mão:\n" + regraParaCopiar(hostname, servico) : ""));
        }
        String caminhoNoContainer = cfg.caminhoNoContainer();
        if (!semEfeito) {
            try {
                ResultadoExec r = ambiente.executor().executar(ComandosCloudflared.inspecionar(comSudo, cfg.containerDoCloudflared()), PRAZO_DO_COMANDO);
                if (r.status() != 0) {
                    return Bloqueada.de(explicarDocker(r.stderr(), cfg.containerDoCloudflared()));
                }
                MontagensDoContainer m = MontagensDoContainer.ler(r.stdout(), cfg.caminhoDoConfig());
                if (!m.existe()) {
                    return Bloqueada.de("O container " + cfg.containerDoCloudflared() + " não existe no servidor.");
                }
                if (!m.rodando()) {
                    return Bloqueada.de("O container " + cfg.containerDoCloudflared() + " não está rodando: suba o cloudflared antes (o app precisa dele para conferir as regras).");
                }
                if (caminhoNoContainer == null) {
                    caminhoNoContainer = m.caminhoDoConfigNoContainer().orElse(null);
                }
                if (caminhoNoContainer == null) {
                    return Bloqueada.de("Não descobri onde o container " + cfg.containerDoCloudflared() + " enxerga o config.yml. Informe o caminho de dentro do container na tela Cloudflare.");
                }
            } catch (IOException e) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_CONEXAO, "Não consegui falar com o Docker do servidor (a conexão caiu ou o prazo acabou).", null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.", null, e);
            } catch (IllegalArgumentException e) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O nome do container tem um caractere que o app não passa ao servidor.", null, e);
            }
            avisos.add("O cloudflared será reiniciado (alguns segundos): os endereços do túnel inteiro ficam fora do ar nesse intervalo.");
        }
        String diferenca = Diferenca.legivel(texto, novo, 2);
        return new MudancaDeArquivo(remover, original, novo.getBytes(StandardCharsets.UTF_8), diferenca, avisos, semEfeito, caminhoNoContainer);
    }

    private static FalhaDoProjeto semConfig(ErroSftp e) {
        FalhaDoProjeto.Tipo t = switch (e.tipo()) {
            case SEM_CONEXAO, SFTP_INDISPONIVEL -> FalhaDoProjeto.Tipo.SEM_CONEXAO;
            case SEM_PERMISSAO -> FalhaDoProjeto.Tipo.SEM_PERMISSAO;
            case CANCELADO -> FalhaDoProjeto.Tipo.CANCELADO;
            default -> FalhaDoProjeto.Tipo.OUTRO;
        };
        return new FalhaDoProjeto(t, "Não consegui ler o config.yml no servidor: " + e.mensagemSimples(), null, e);
    }

    private static String regraParaCopiar(String hostname, String servico) {
        return "  - hostname: " + hostname + "\n    service: " + servico + "\n(antes da regra final, a que tem service: http_status:404; depois reinicie o cloudflared)";
    }

    // ---------------------------------------------------------------------------------------------
    // A mudança bloqueada
    // ---------------------------------------------------------------------------------------------

    private record Bloqueada(String bloqueio) implements Mudanca {
        static Bloqueada de(String frase) {
            return new Bloqueada(frase);
        }

        @Override
        public String diferenca() {
            return "";
        }

        @Override
        public List<String> avisos() {
            return List.of();
        }

        @Override
        public boolean semEfeito() {
            return false;
        }

        @Override
        public Aplicada aplicar(ObservadorDaEtapa obs, Cancelamento cancelamento) throws FalhaDoProjeto {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, bloqueio);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Aplicar
    // ---------------------------------------------------------------------------------------------

    private final class MudancaDeArquivo implements Mudanca {
        private final boolean remover;
        private final byte[] original;
        private final byte[] novo;
        private final String diferenca;
        private final List<String> avisos;
        private final boolean semEfeito;
        private final String caminhoNoContainer;

        MudancaDeArquivo(boolean remover, byte[] original, byte[] novo, String diferenca, List<String> avisos, boolean semEfeito, String caminhoNoContainer) {
            this.remover = remover;
            this.original = original;
            this.novo = novo;
            this.diferenca = diferenca;
            this.avisos = List.copyOf(avisos);
            this.semEfeito = semEfeito;
            this.caminhoNoContainer = caminhoNoContainer;
        }

        @Override
        public String diferenca() {
            return diferenca;
        }

        @Override
        public List<String> avisos() {
            return avisos;
        }

        @Override
        public String bloqueio() {
            return null;
        }

        @Override
        public boolean semEfeito() {
            return semEfeito;
        }

        @Override
        public Aplicada aplicar(ObservadorDaEtapa obs, Cancelamento cancelamento) throws FalhaDoProjeto {
            Etapa regra = remover ? Etapa.REMOVER_REGRA : Etapa.REGRA;
            if (semEfeito) {
                obs.etapa(regra, ObservadorDaEtapa.Estado.PULADO, remover ? "A regra já não estava no config.yml." : "A regra já estava no config.yml.");
                return new AplicadaDeArquivo(null);
            }
            conferirCancelamento(cancelamento);
            obs.etapa(regra, ObservadorDaEtapa.Estado.ANDANDO, "Guardando uma cópia datada do config.yml e gravando a regra…");
            String copia;
            try (ClienteSftp c = ambiente.abrirArquivos()) {
                byte[] agora = lerBytes(c, cfg.caminhoDoConfig());
                if (!Arrays.equals(agora, original)) {
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O config.yml mudou depois que você viu a diferença. Nada foi gravado: confira de novo.");
                }
                copia = nomeDaCopia(c, cfg.caminhoDoConfig());
                c.gravarAtomico(copia, original, permissoesDoConfig(c));
                try {
                    c.gravarNoLugar(cfg.caminhoDoConfig(), novo);
                } catch (ErroSftp e) {
                    // pode ter gravado pela metade: devolve o que havia
                    String sobrou = restaurar(c);
                    throw erroDeGravacao(e, sobrou);
                }
            } catch (ErroSftp e) {
                throw erroDeGravacao(e, ""); // a leitura ou a cópia datada falhou: nada foi escrito no config.yml
            }
            AplicadaDeArquivo aplicada = new AplicadaDeArquivo(copia);
            obs.etapa(regra, ObservadorDaEtapa.Estado.FEITO, "Regra gravada no config.yml (cópia: " + CaminhoRemoto.nome(copia) + ").");
            try {
                conferirCancelamento(cancelamento);
                obs.etapa(Etapa.CONFERIR_E_REINICIAR, ObservadorDaEtapa.Estado.ANDANDO, "O cloudflared confere as regras…");
                conferirComOCloudflared();
                conferirCancelamento(cancelamento);
                obs.etapa(Etapa.CONFERIR_E_REINICIAR, ObservadorDaEtapa.Estado.ANDANDO, "Reiniciando o cloudflared…");
                aplicada.reiniciou = true;
                reiniciar();
                esperarOCloudflared();
                obs.etapa(Etapa.CONFERIR_E_REINICIAR, ObservadorDaEtapa.Estado.FEITO, "O cloudflared voltou com as regras novas.");
            } catch (FalhaDoProjeto f) {
                List<String> naoDesfez = aplicada.desfazer();
                obs.etapa(Etapa.CONFERIR_E_REINICIAR, ObservadorDaEtapa.Estado.FALHOU, f.mensagem());
                throw comOQueSobrou(f, naoDesfez, aplicada.reiniciou ? "O config.yml foi devolvido como estava e o cloudflared foi reiniciado de novo." : "O config.yml foi devolvido como estava; nada foi reiniciado.");
            }
            return aplicada;
        }

        private FalhaDoProjeto erroDeGravacao(ErroSftp e, String sobrou) {
            String base = switch (e.tipo()) {
                case SEM_PERMISSAO -> "O seu usuário no servidor não pode escrever no config.yml (ele é de outro usuário, ou está só para leitura). Acrescente a regra à mão (abaixo) e reinicie o cloudflared.";
                case NAO_EXISTE -> "O config.yml sumiu do servidor no meio do caminho.";
                default -> "Não consegui gravar o config.yml: " + e.mensagemSimples();
            };
            String comando = regraParaCopiar("(o endereço)", "(o serviço)");
            return new FalhaDoProjeto(e.tipo() == ErroSftp.Tipo.SEM_PERMISSAO ? FalhaDoProjeto.Tipo.SEM_PERMISSAO : FalhaDoProjeto.Tipo.OUTRO,
                base + (sobrou.isEmpty() ? " Nada ficou pela metade." : " " + sobrou), e.tipo() == ErroSftp.Tipo.SEM_PERMISSAO ? comando : null, e);
        }

        private void conferirComOCloudflared() throws FalhaDoProjeto {
            ResultadoExec r = rodar(ComandosCloudflared.validarRegras(comSudo, cfg.containerDoCloudflared(), caminhoNoContainer), "conferir as regras com o cloudflared");
            if (r.status() != 0) {
                String motivo = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(r.stderr().isBlank() ? r.stdout() : r.stderr())), 400);
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O cloudflared não aceitou as regras (" + (motivo.isEmpty() ? "sem mensagem" : motivo) + ").");
            }
        }

        private void reiniciar() throws FalhaDoProjeto {
            ResultadoExec r = rodar(ComandosCloudflared.reiniciar(comSudo, cfg.containerDoCloudflared()), "reiniciar o cloudflared");
            if (r.status() != 0) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, explicarDocker(r.stderr(), cfg.containerDoCloudflared()));
            }
        }

        /**
         * Espera o cloudflared voltar E FICAR de pé: "rodando" numa foto só não basta (um cloudflared que cai um segundo depois, por credencial, montagem ou rede,
         * aparece como rodando logo depois do restart). Só vale quando ele está rodando, sem estar reiniciando sozinho, com a MESMA vida (mesmo instante de partida e
         * mesmo número de reinícios do Docker) por {@link #ESTAVEL_POR}. Mudou de vida no meio, a contagem recomeça; a espera total é {@link #ESPERA_DO_REINICIO}.
         * O relógio é a soma das pausas que o {@link Esperador} fez (nos testes, nada dorme).
         */
        private void esperarOCloudflared() throws FalhaDoProjeto {
            Duration decorrido = Duration.ZERO;
            Duration estavelDesde = null;
            String vida = null;
            boolean chegouARodar = false;
            while (true) {
                try {
                    ResultadoExec r = ambiente.executor().executar(ComandosCloudflared.inspecionar(comSudo, cfg.containerDoCloudflared()), PRAZO_DO_COMANDO);
                    MontagensDoContainer m = r.status() == 0 ? MontagensDoContainer.ler(r.stdout(), cfg.caminhoDoConfig()) : MontagensDoContainer.NAO_EXISTE;
                    if (m.deP()) {
                        chegouARodar = true;
                        if (!m.marcaDaVida().equals(vida)) {
                            vida = m.marcaDaVida();
                            estavelDesde = decorrido;
                        } else if (decorrido.minus(estavelDesde).compareTo(ESTAVEL_POR) >= 0) {
                            return;
                        }
                    } else {
                        vida = null;
                        estavelDesde = null;
                    }
                } catch (IOException e) {
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_CONEXAO, "A conexão com o servidor caiu enquanto o cloudflared reiniciava.", null, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.", null, e);
                }
                if (decorrido.compareTo(ESPERA_DO_REINICIO) > 0) {
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, chegouARodar
                        ? "O cloudflared subiu, mas não ficou de pé depois de reiniciar com a regra nova (ele caiu ou reiniciou sozinho de novo)."
                        : "O cloudflared não voltou a rodar depois de reiniciar com a regra nova.");
                }
                try {
                    esperador.esperar(PASSO_DA_ESPERA);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.", null, e);
                }
                decorrido = decorrido.plus(PASSO_DA_ESPERA);
            }
        }

        private ResultadoExec rodar(List<String> comando, String oQue) throws FalhaDoProjeto {
            try {
                return ambiente.executor().executar(comando, PRAZO_DO_COMANDO);
            } catch (ProgramaNaoEncontradoException e) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "O Docker não está instalado onde o cloudflared roda.", null, e);
            } catch (IOException e) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.INCERTO, "Não consegui " + oQue + ": a conexão caiu ou o prazo acabou. O resultado é incerto.", null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.", null, e);
            } catch (IllegalArgumentException e) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "O nome do container ou o caminho tem um caractere que o app não passa ao servidor.", null, e);
            }
        }

        /** Devolve o conteúdo de antes NO LUGAR; devolve uma frase do que não deu, ou vazio. */
        private String restaurar(ClienteSftp c) {
            try {
                c.gravarNoLugar(cfg.caminhoDoConfig(), original);
                return "";
            } catch (ErroSftp e) {
                return "Não consegui devolver o config.yml como estava (" + e.mensagemSimples() + "): copie a cópia datada de volta por cima dele.";
            }
        }

        private FalhaDoProjeto comOQueSobrou(FalhaDoProjeto f, List<String> naoDesfez, String desfeito) {
            StringBuilder sb = new StringBuilder(f.mensagem()).append(' ');
            sb.append(naoDesfez.isEmpty() ? desfeito : "Atenção: " + String.join(" ", naoDesfez));
            return new FalhaDoProjeto(f.tipo(), sb.toString(), f.comandoParaCopiar(), f);
        }

        private void conferirCancelamento(Cancelamento c) throws FalhaDoProjeto {
            if (c != null && c.cancelado()) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.");
            }
        }

        private final class AplicadaDeArquivo implements Aplicada {
            private final String copia;
            volatile boolean reiniciou;

            AplicadaDeArquivo(String copia) {
                this.copia = copia;
            }

            @Override
            public String copia() {
                return copia;
            }

            @Override
            public List<String> desfazer() {
                List<String> naoDesfez = new ArrayList<>();
                if (copia == null) {
                    return naoDesfez; // não havia escrita
                }
                try (ClienteSftp c = ambiente.abrirArquivos()) {
                    String s = restaurar(c);
                    if (!s.isEmpty()) {
                        naoDesfez.add(s);
                        return naoDesfez;
                    }
                } catch (RuntimeException e) {
                    naoDesfez.add("Não consegui devolver o config.yml como estava: copie a cópia datada (" + CaminhoRemoto.nome(copia) + ") de volta por cima dele.");
                    return naoDesfez;
                }
                if (reiniciou) {
                    try {
                        reiniciar();
                        esperarOCloudflared();
                    } catch (FalhaDoProjeto f) {
                        naoDesfez.add("O config.yml foi devolvido, mas não consegui reiniciar o cloudflared de novo (" + f.mensagem() + "): reinicie-o à mão (docker restart "
                            + cfg.containerDoCloudflared() + ").");
                    }
                }
                return naoDesfez;
            }
        }
    }

    /** O modo que o config.yml tem agora (o arquivo em si é gravado NO LUGAR e o conserva; a cópia nasce igual a ele). */
    private int permissoesDoConfig(ClienteSftp c) {
        try {
            int p = c.estatisticas(cfg.caminhoDoConfig()).permissoes() & 0777;
            return p == 0 ? PERMISSAO_PADRAO_DA_COPIA : p;
        } catch (ErroSftp e) {
            return PERMISSAO_PADRAO_DA_COPIA;
        }
    }

    private String nomeDaCopia(ClienteSftp c, String caminho) throws ErroSftp {
        String base = caminho + "." + DATA_DA_COPIA.format(LocalDateTime.now(relogio)) + ".bak";
        String nome = base;
        for (int i = 2; c.existe(nome); i++) {
            nome = base + "-" + i;
        }
        return nome;
    }
}
