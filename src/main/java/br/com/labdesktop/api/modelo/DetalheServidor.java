package br.com.labdesktop.api.modelo;

import br.com.labdesktop.api.modelo.InfoServidor.Cpu;
import br.com.labdesktop.api.modelo.InfoServidor.Disco;
import br.com.labdesktop.api.modelo.InfoServidor.DockerInfo;
import br.com.labdesktop.api.modelo.InfoServidor.Historico;
import br.com.labdesktop.api.modelo.InfoServidor.Memoria;
import br.com.labdesktop.api.modelo.InfoServidor.NodeInfo;
import br.com.labdesktop.api.modelo.InfoServidor.Processo;
import br.com.labdesktop.api.modelo.InfoServidor.Rede;
import br.com.labdesktop.api.modelo.InfoServidor.SessaoServidor;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/servidor/detalhe} (CONTRATO-API 3.3): tudo de {@link InfoServidor} mais sensores, energia, a máquina
 * (hardware) e o consumo por projeto. Mais pesado no servidor que {@code /api/servidor}: só é consultado enquanto a
 * Visão geral do servidor está na tela. Os blocos em comum reaproveitam os tipos de {@link InfoServidor}.
 *
 * <p>Tolerante: campo novo é ignorado (o mapper do contrato não falha com desconhecidos) e todo campo pode ser
 * {@code null}, em especial {@code max} e {@code crit} dos sensores e {@code cpu} dos processos na primeira varredura.
 */
public record DetalheServidor(
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
    @JsonProperty("sensores") List<Sensor> sensores,
    @JsonProperty("energia") Energia energia,
    @JsonProperty("maquina") Maquina maquina,
    @JsonProperty("consumo") List<GrupoDeConsumo> consumo) {

    public DetalheServidor {
        hostname = Textos.linha(hostname);
        sistema = Textos.linha(sistema);
        kernel = Textos.linha(kernel);
        carga = Textos.lista(carga);
        discos = Textos.lista(discos);
        sessoes = Textos.lista(sessoes);
        processos = Textos.lista(processos);
        sensores = Textos.lista(sensores);
        consumo = Textos.lista(consumo);
    }

    /** Um sensor de temperatura em °C. {@code max} e {@code crit} podem ser {@code null} (acpitz não os informa). */
    public record Sensor(
        @JsonProperty("chip") String chip,
        @JsonProperty("rotulo") String rotulo,
        @JsonProperty("c") Double c,
        @JsonProperty("max") Double max,
        @JsonProperty("crit") Double crit) {

        public Sensor {
            chip = Textos.linha(chip);
            rotulo = Textos.linha(rotulo);
        }
    }

    /** Energia: {@code tomada} é {@code true}, {@code false} ou {@code null} (desconhecida). */
    public record Energia(
        @JsonProperty("tomada") Boolean tomada,
        @JsonProperty("baterias") List<Bateria> baterias) {

        public Energia {
            baterias = Textos.lista(baterias);
        }
    }

    /** Uma bateria: {@code carga} em %, {@code saude} em fração de 0 a 1. */
    public record Bateria(
        @JsonProperty("nome") String nome,
        @JsonProperty("carga") Double carga,
        @JsonProperty("estado") String estado,
        @JsonProperty("saude") Double saude) {

        public Bateria {
            nome = Textos.linha(nome);
            estado = Textos.linha(estado);
        }
    }

    /** O hardware, lido do DMI. Qualquer campo pode faltar (máquina virtual, por exemplo). */
    public record Maquina(
        @JsonProperty("fabricante") String fabricante,
        @JsonProperty("modelo") String modelo,
        @JsonProperty("versao") String versao,
        @JsonProperty("placa") String placa,
        @JsonProperty("bios") String bios,
        @JsonProperty("biosData") String biosData,
        @JsonProperty("cpuModelo") String cpuModelo,
        @JsonProperty("nucleos") Integer nucleos,
        @JsonProperty("memoriaTotal") Long memoriaTotal,
        @JsonProperty("arquitetura") String arquitetura) {

        public Maquina {
            fabricante = Textos.linha(fabricante);
            modelo = Textos.linha(modelo);
            versao = Textos.linha(versao);
            placa = Textos.linha(placa);
            bios = Textos.linha(bios);
            biosData = Textos.linha(biosData);
            cpuModelo = Textos.linha(cpuModelo);
            arquitetura = Textos.linha(arquitetura);
        }
    }

    /** O consumo de um projeto ({@code projeto} {@code null} = containers sem dono, {@code "(lab)"} = infra). */
    public record GrupoDeConsumo(
        @JsonProperty("projeto") String projeto,
        @JsonProperty("cpu") Double cpu,
        @JsonProperty("mem") Long mem,
        @JsonProperty("containers") List<ContainerDeConsumo> containers) {

        public GrupoDeConsumo {
            projeto = Textos.linha(projeto);
            containers = Textos.lista(containers);
        }
    }

    public record ContainerDeConsumo(
        @JsonProperty("nome") String nome,
        @JsonProperty("tipo") String tipo,
        @JsonProperty("imagem") String imagem,
        @JsonProperty("ha") String ha,
        @JsonProperty("estado") String estado,
        @JsonProperty("cpu") Double cpu,
        @JsonProperty("mem") Long mem) {

        public ContainerDeConsumo {
            nome = Textos.linha(nome);
            tipo = Textos.linha(tipo);
            imagem = Textos.linha(imagem);
            ha = Textos.linha(ha);
            estado = Textos.linha(estado);
        }
    }

    /** Temperatura da zona mais quente do processador em °C (para a guarda de calor do Sondador), ou {@code null}. */
    public Double temperatura() {
        return cpu == null ? null : cpu.temperatura();
    }
}
