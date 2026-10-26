package br.com.labdesktop.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preferências do usuário em {@code preferencias.json} (ARQUITETURA 5.1): tema, janela, fonte do terminal,
 * Option como Meta, copiar ao selecionar, avisos que disparam notificação e auto-bloqueio. SEM segredo; o
 * arquivo fica fora do cofre de propósito, para a tela poder abrir antes de desbloqueá-lo.
 *
 * <p>Imutável. Mudar uma preferência é {@code prefs.comTema(...)} seguido de {@link #salvar(CaminhosApp)} (E/S:
 * fora da EDT). Ler é tolerante: arquivo ausente, ilegível ou com valor estranho nunca impede o app de abrir,
 * cada campo ruim volta ao padrão. Campo que uma versão futura acrescentar é ignorado na leitura.
 *
 * @param tema               claro, escuro ou o do sistema
 * @param janela             posição e tamanho da janela principal; {@code null} = centralizar no tamanho padrão
 * @param tamanhoFonteTerminal pontos da fonte do terminal (8 a 32; padrão 13)
 * @param opcaoComoMeta      Option vira Meta no terminal (desligado: o ABNT2 gera caracteres com Option)
 * @param copiarAoSelecionar copia ao selecionar no terminal (desligado: sobrescreve a área de transferência)
 * @param avisosAtivos       quais avisos disparam notificação (todos ligados por padrão)
 * @param autoBloqueioMinutos minutos sem uso até bloquear o cofre; 0 = nunca (padrão, decisão 5 de ARQUITETURA 14)
 * @param escalaTexto        fator do tamanho do texto da interface em porcentagem (100, 115 ou 130; Visualizar, "Aumentar texto")
 * @param donoExclusivo      o terminal da sessão do Claude anexa com {@code tmux attach -d}, derrubando os outros clientes (desligado
 *                           por padrão)
 */
public record Preferencias(
    Tema tema,
    Janela janela,
    int tamanhoFonteTerminal,
    boolean opcaoComoMeta,
    boolean copiarAoSelecionar,
    Set<Aviso> avisosAtivos,
    int autoBloqueioMinutos,
    int escalaTexto,
    boolean donoExclusivo) {

    /** Os fatores que o menu oferece (iguais aos de {@code ui.tema.Fontes}: este pacote não importa a interface). */
    public static final int[] ESCALAS_DO_TEXTO = {100, 115, 130};
    public static final int ESCALA_PADRAO = 100;

    /** Sem o fator do texto (vale 100 %) nem o dono exclusivo (desligado). */
    public Preferencias(Tema tema, Janela janela, int tamanhoFonteTerminal, boolean opcaoComoMeta, boolean copiarAoSelecionar,
                        Set<Aviso> avisosAtivos, int autoBloqueioMinutos) {
        this(tema, janela, tamanhoFonteTerminal, opcaoComoMeta, copiarAoSelecionar, avisosAtivos, autoBloqueioMinutos, ESCALA_PADRAO,
            false);
    }

    /** Sem o dono exclusivo: desligado. */
    public Preferencias(Tema tema, Janela janela, int tamanhoFonteTerminal, boolean opcaoComoMeta, boolean copiarAoSelecionar,
                        Set<Aviso> avisosAtivos, int autoBloqueioMinutos, int escalaTexto) {
        this(tema, janela, tamanhoFonteTerminal, opcaoComoMeta, copiarAoSelecionar, avisosAtivos, autoBloqueioMinutos, escalaTexto,
            false);
    }

    private static final Logger LOG = LoggerFactory.getLogger(Preferencias.class);

    /** Maior arquivo de preferências aceito na leitura. */
    static final long TAMANHO_MAXIMO = 256 * 1024;
    public static final int FONTE_PADRAO = 13;
    public static final int FONTE_MIN = 8;
    public static final int FONTE_MAX = 32;
    public static final int AUTO_BLOQUEIO_MAX_MINUTOS = 24 * 60;

    /** Qual aparência o app usa. {@code SISTEMA} segue o claro/escuro do macOS. */
    public enum Tema {
        CLARO, ESCURO, SISTEMA;

        String rotulo() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Tema deRotulo(String texto, Tema padrao) {
            if (texto != null) {
                for (Tema t : values()) {
                    if (t.rotulo().equals(texto.strip().toLowerCase(Locale.ROOT))) {
                        return t;
                    }
                }
            }
            return padrao;
        }
    }

    /** Os avisos que podem virar notificação (ARQUITETURA 9). */
    public enum Aviso {
        PAUSA_NOVA, CREDITO_75, CREDITO_90;

        String rotulo() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Posição e tamanho da janela principal, em pixels. */
    public record Janela(int x, int y, int largura, int altura, boolean maximizada) {

        public static final int LARGURA_MIN = 600;
        public static final int ALTURA_MIN = 400;

        public Janela {
            if (largura < LARGURA_MIN || altura < ALTURA_MIN || largura > 20_000 || altura > 20_000) {
                throw new IllegalArgumentException("Tamanho de janela fora do razoável: " + largura + "x" + altura);
            }
        }
    }

    public Preferencias {
        tema = tema == null ? Tema.SISTEMA : tema;
        tamanhoFonteTerminal = Math.max(FONTE_MIN, Math.min(FONTE_MAX, tamanhoFonteTerminal));
        autoBloqueioMinutos = Math.max(0, Math.min(AUTO_BLOQUEIO_MAX_MINUTOS, autoBloqueioMinutos));
        escalaTexto = escalaValida(escalaTexto);
        avisosAtivos = avisosAtivos == null || avisosAtivos.isEmpty()
            ? Set.of() : Set.copyOf(EnumSet.copyOf(avisosAtivos));
    }

    /** Os padrões: tema do sistema, fonte 13, Option e cópia automática desligados, todos os avisos, sem auto-bloqueio. */
    public static Preferencias padrao() {
        return new Preferencias(Tema.SISTEMA, null, FONTE_PADRAO, false, false,
            EnumSet.allOf(Aviso.class), 0);
    }

    /** O fator válido mais perto do pedido (valor estranho no arquivo vira o mais próximo, nunca derruba a leitura). */
    static int escalaValida(int pedido) {
        int melhor = ESCALAS_DO_TEXTO[0];
        for (int e : ESCALAS_DO_TEXTO) {
            if (Math.abs(e - pedido) < Math.abs(melhor - pedido)) {
                melhor = e;
            }
        }
        return melhor;
    }

    public Preferencias comEscalaTexto(int porcento) {
        return new Preferencias(tema, janela, tamanhoFonteTerminal, opcaoComoMeta, copiarAoSelecionar, avisosAtivos,
            autoBloqueioMinutos, porcento, donoExclusivo);
    }

    public boolean avisoAtivo(Aviso aviso) {
        return avisosAtivos.contains(aviso);
    }

    public Preferencias comTema(Tema novo) {
        return new Preferencias(novo, janela, tamanhoFonteTerminal, opcaoComoMeta, copiarAoSelecionar,
            avisosAtivos, autoBloqueioMinutos, escalaTexto, donoExclusivo);
    }

    public Preferencias comJanela(Janela nova) {
        return new Preferencias(tema, nova, tamanhoFonteTerminal, opcaoComoMeta, copiarAoSelecionar,
            avisosAtivos, autoBloqueioMinutos, escalaTexto, donoExclusivo);
    }

    public Preferencias comTamanhoFonteTerminal(int pontos) {
        return new Preferencias(tema, janela, pontos, opcaoComoMeta, copiarAoSelecionar,
            avisosAtivos, autoBloqueioMinutos, escalaTexto, donoExclusivo);
    }

    public Preferencias comOpcaoComoMeta(boolean ligada) {
        return new Preferencias(tema, janela, tamanhoFonteTerminal, ligada, copiarAoSelecionar,
            avisosAtivos, autoBloqueioMinutos, escalaTexto, donoExclusivo);
    }

    public Preferencias comCopiarAoSelecionar(boolean ligada) {
        return new Preferencias(tema, janela, tamanhoFonteTerminal, opcaoComoMeta, ligada,
            avisosAtivos, autoBloqueioMinutos, escalaTexto, donoExclusivo);
    }

    public Preferencias comAviso(Aviso aviso, boolean ativo) {
        Set<Aviso> novos = avisosAtivos.isEmpty() ? EnumSet.noneOf(Aviso.class) : EnumSet.copyOf(avisosAtivos);
        if (ativo) {
            novos.add(aviso);
        } else {
            novos.remove(aviso);
        }
        return new Preferencias(tema, janela, tamanhoFonteTerminal, opcaoComoMeta, copiarAoSelecionar,
            novos, autoBloqueioMinutos, escalaTexto, donoExclusivo);
    }

    public Preferencias comDonoExclusivo(boolean ligado) {
        return new Preferencias(tema, janela, tamanhoFonteTerminal, opcaoComoMeta, copiarAoSelecionar,
            avisosAtivos, autoBloqueioMinutos, escalaTexto, ligado);
    }

    public Preferencias comAutoBloqueioMinutos(int minutos) {
        return new Preferencias(tema, janela, tamanhoFonteTerminal, opcaoComoMeta, copiarAoSelecionar,
            avisosAtivos, minutos, escalaTexto, donoExclusivo);
    }

    // ---------------------------------------------------------------------------------------------
    // Disco
    // ---------------------------------------------------------------------------------------------

    /**
     * Lê {@code preferencias.json}. NUNCA lança por causa do conteúdo: sem arquivo, ou com arquivo ilegível,
     * devolve {@link #padrao()} (e registra o motivo, sem o conteúdo); campo inválido volta ao padrão sozinho.
     * Faz E/S: chame de thread de fundo.
     */
    public static Preferencias carregar(CaminhosApp caminhos) {
        var arquivo = caminhos.arquivoPreferencias();
        if (!Files.isRegularFile(arquivo)) {
            return padrao();
        }
        try {
            Dados d = Json.mapper().readValue(ArquivoPrivado.lerLimitado(arquivo, TAMANHO_MAXIMO), Dados.class);
            return d == null ? padrao() : d.paraPreferencias();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Preferências ilegíveis, usando os padrões ({}).", e.getClass().getSimpleName());
            return padrao();
        }
    }

    /** Grava {@code preferencias.json} de forma atômica, com 0600, criando a pasta de dados (0700) se faltar. */
    public void salvar(CaminhosApp caminhos) throws IOException {
        caminhos.prepararPastas();
        byte[] json = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Dados.de(this));
        ArquivoPrivado.gravarAtomico(caminhos.arquivoPreferencias(), json);
    }

    /**
     * O formato do arquivo: tudo anulável e em texto simples, para uma versão futura (ou uma edição à mão)
     * acrescentar ou errar um campo sem derrubar a leitura dos outros.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Dados {
        public Integer versao;
        public String tema;
        public JanelaDados janela;
        public Integer fonteTerminal;
        public Boolean opcaoComoMeta;
        public Boolean copiarAoSelecionar;
        public List<String> avisos;
        public Integer autoBloqueioMinutos;
        public Integer escalaTexto;
        public Boolean donoExclusivo;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static final class JanelaDados {
            public Integer x;
            public Integer y;
            public Integer largura;
            public Integer altura;
            public Boolean maximizada;
        }

        static Dados de(Preferencias p) {
            Dados d = new Dados();
            d.versao = 1;
            d.tema = p.tema.rotulo();
            if (p.janela != null) {
                d.janela = new JanelaDados();
                d.janela.x = p.janela.x();
                d.janela.y = p.janela.y();
                d.janela.largura = p.janela.largura();
                d.janela.altura = p.janela.altura();
                d.janela.maximizada = p.janela.maximizada();
            }
            d.fonteTerminal = p.tamanhoFonteTerminal;
            d.opcaoComoMeta = p.opcaoComoMeta;
            d.copiarAoSelecionar = p.copiarAoSelecionar;
            d.avisos = p.avisosAtivos.stream().map(Aviso::rotulo).sorted().toList();
            d.autoBloqueioMinutos = p.autoBloqueioMinutos;
            d.escalaTexto = p.escalaTexto;
            d.donoExclusivo = p.donoExclusivo;
            return d;
        }

        Preferencias paraPreferencias() {
            Preferencias base = padrao();
            Janela j = null;
            if (janela != null && janela.largura != null && janela.altura != null) {
                try {
                    j = new Janela(janela.x == null ? 0 : janela.x, janela.y == null ? 0 : janela.y,
                        janela.largura, janela.altura, Boolean.TRUE.equals(janela.maximizada));
                } catch (IllegalArgumentException e) {
                    j = null; // tamanho absurdo: volta a centralizar
                }
            }
            Set<Aviso> ativos = EnumSet.noneOf(Aviso.class);
            if (avisos == null) {
                ativos = EnumSet.copyOf(base.avisosAtivos);
            } else {
                for (String nome : avisos) {
                    for (Aviso a : Aviso.values()) {
                        if (nome != null && a.rotulo().equals(nome.strip().toLowerCase(Locale.ROOT))) {
                            ativos.add(a);
                        }
                    }
                }
            }
            return new Preferencias(
                Tema.deRotulo(tema, base.tema), j,
                fonteTerminal == null ? base.tamanhoFonteTerminal : fonteTerminal,
                opcaoComoMeta == null ? base.opcaoComoMeta : opcaoComoMeta,
                copiarAoSelecionar == null ? base.copiarAoSelecionar : copiarAoSelecionar,
                ativos,
                autoBloqueioMinutos == null ? base.autoBloqueioMinutos : autoBloqueioMinutos,
                escalaTexto == null ? base.escalaTexto : escalaTexto,
                donoExclusivo == null ? base.donoExclusivo : donoExclusivo);
        }
    }
}
