package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/servidor} (CONTRATO-API 3.2) e o bloco {@code servidor} de {@code /api/projetos} (3.7, sem
 * {@code historico} e {@code processos}, mais {@code limites_plano}). Um só tipo para os dois: o que não vem
 * fica {@code null} (ou lista vazia).
 *
 * <p>Unidades (CONTRATO-API 1.4): {@code cpu.uso} e {@code porNucleo} são FRAÇÃO de 0 a 1; já
 * {@code processos[].cpu} e afins são percentual. Bytes são número de bytes. {@code uptime} em segundos.
 * {@code temperatura} em °C da zona mais quente.
 */
public record InfoServidor(
    @JsonProperty("quando") Instant quando,
    @JsonProperty("hostname") String hostname,
    @JsonProperty("sistema") String sistema,
    @JsonProperty("kernel") String kernel,
    @JsonProperty("uptime") Double uptime,
    @JsonProperty("carga") List<Double> carga,
    @JsonProperty("cpu") Cpu cpu,
    @JsonProperty("memoria") Memoria memoria,
    @JsonProperty("rede") Rede rede,
    @JsonProperty("node") NodeInfo node,
    @JsonProperty("discos") List<Disco> discos,
    @JsonProperty("docker") DockerInfo docker,
    @JsonProperty("sessoes") List<SessaoServidor> sessoes,
    @JsonProperty("historico") Historico historico,
    @JsonProperty("processos") List<Processo> processos,
    @JsonProperty("limites_plano") LimitesConta limitesPlano) {

    public InfoServidor {
        hostname = Textos.linha(hostname);
        sistema = Textos.linha(sistema);
        kernel = Textos.linha(kernel);
        carga = Textos.lista(carga);
        discos = Textos.lista(discos);
        sessoes = Textos.lista(sessoes);
        processos = Textos.lista(processos);
    }

    /** CPU do servidor. {@code uso} e {@code porNucleo} são frações de 0 a 1 (podem ser {@code null}). */
    public record Cpu(
        @JsonProperty("uso") Double uso,
        @JsonProperty("usoInstantaneo") Double usoInstantaneo,
        @JsonProperty("porNucleo") List<Double> porNucleo,
        @JsonProperty("nucleos") Integer nucleos,
        @JsonProperty("modelo") String modelo,
        @JsonProperty("temperatura") Double temperatura) {

        public Cpu {
            porNucleo = porNucleo == null ? List.of() : porNucleo; // pode ter null no meio: nucleo sem leitura
            modelo = Textos.linha(modelo);
        }
    }

    /** Memória e swap em bytes. */
    public record Memoria(
        @JsonProperty("total") Long total,
        @JsonProperty("usada") Long usada,
        @JsonProperty("disponivel") Long disponivel,
        @JsonProperty("livre") Long livre,
        @JsonProperty("cache") Long cache,
        @JsonProperty("buffers") Long buffers,
        @JsonProperty("swap") Swap swap) {
    }

    public record Swap(
        @JsonProperty("total") Long total,
        @JsonProperty("usada") Long usada) {
    }

    /** A interface de rede mais ativa; bytes por segundo. */
    public record Rede(
        @JsonProperty("interface") String interfaceDeRede,
        @JsonProperty("rx_bps") Double rxBps,
        @JsonProperty("tx_bps") Double txBps) {

        public Rede {
            interfaceDeRede = Textos.linha(interfaceDeRede);
        }
    }

    /** Versões do Node do agente e da imagem da sessão (nulo até a primeira leitura, cache de 1 h). */
    public record NodeInfo(
        @JsonProperty("painel") String painel,
        @JsonProperty("imagemSessao") Map<String, String> imagemSessao) {

        public NodeInfo {
            painel = Textos.linha(painel);
            imagemSessao = Textos.mapa(imagemSessao);
        }
    }

    public record Disco(
        @JsonProperty("montagem") String montagem,
        @JsonProperty("total") Long total,
        @JsonProperty("usado") Long usado,
        @JsonProperty("livre") Long livre) {

        public Disco {
            montagem = Textos.linha(montagem);
        }
    }

    public record DockerInfo(
        @JsonProperty("versao") String versao,
        @JsonProperty("containers") Integer containers,
        @JsonProperty("rodando") Integer rodando,
        @JsonProperty("pausados") Integer pausados,
        @JsonProperty("parados") Integer parados,
        @JsonProperty("imagens") Integer imagens,
        @JsonProperty("volumes") Integer volumes,
        @JsonProperty("tamanhos") Map<String, Long> tamanhos) {

        public DockerInfo {
            versao = Textos.linha(versao);
            tamanhos = Textos.mapa(tamanhos);
        }
    }

    /** Uma sessão do Claude aberta no servidor. {@code cpu} em percentual, {@code mem} em bytes. */
    public record SessaoServidor(
        @JsonProperty("projeto") String projeto,
        @JsonProperty("container") String container,
        @JsonProperty("desde") Instant desde,
        @JsonProperty("cpu") Double cpu,
        @JsonProperty("mem") Long mem,
        @JsonProperty("redes") List<String> redes) {

        public SessaoServidor {
            projeto = Textos.linha(projeto);
            container = Textos.linha(container);
            redes = Textos.linhas(redes);
        }
    }

    /** Até 60 pontos de 10 s (10 min); valores podem ser {@code null}. {@code cpu} e {@code mem} em fração 0 a 1. */
    public record Historico(
        @JsonProperty("passo_s") Integer passoS,
        @JsonProperty("cpu") List<Double> cpu,
        @JsonProperty("mem") List<Double> mem,
        @JsonProperty("rx_bps") List<Double> rxBps,
        @JsonProperty("tx_bps") List<Double> txBps,
        @JsonProperty("temp") List<Double> temp) {

        public Historico {
            cpu = cpu == null ? List.of() : cpu;
            mem = mem == null ? List.of() : mem;
            rxBps = rxBps == null ? List.of() : rxBps;
            txBps = txBps == null ? List.of() : txBps;
            temp = temp == null ? List.of() : temp;
        }
    }

    /** Um dos 20 processos de maior CPU. {@code container} nulo = processo do próprio servidor. */
    public record Processo(
        @JsonProperty("pid") Integer pid,
        @JsonProperty("nome") String nome,
        @JsonProperty("usuario") String usuario,
        @JsonProperty("cpu") Double cpu,
        @JsonProperty("mem") Long mem,
        @JsonProperty("container") String container,
        @JsonProperty("projeto") String projeto) {

        public Processo {
            nome = Textos.linha(nome);
            usuario = Textos.linha(usuario);
            container = Textos.linha(container);
            projeto = Textos.linha(projeto);
        }
    }

    /** Temperatura da zona mais quente em °C, ou {@code null}. É o que a guarda de calor do Sondador lê. */
    public Double temperatura() {
        return cpu == null ? null : cpu.temperatura();
    }
}
