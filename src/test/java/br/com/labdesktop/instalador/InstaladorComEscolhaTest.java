package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.ResultadoDaInstalacao.Desfecho;
import br.com.labdesktop.instalador.ResultadoDaVerificacao.Item;
import br.com.labdesktop.teste.ServidorSimulado;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * O instalador roda SÓ os passos do que foi escolhido (servidor simulado, sem SSH). O que não foi escolhido não roda, não aparece como
 * passo e não é conferido a ponto de impedir a instalação; uma escolha que deixaria o servidor pela metade é recusada antes de qualquer escrita.
 */
class InstaladorComEscolhaTest {

    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1});

    private final ParametrosDaInstalacao p = ParametrosDaInstalacao.padrao(7000);
    private final ServidorSimulado servidor = new ServidorSimulado(p, PACOTE);
    private final GanchosFalsos ganchos = new GanchosFalsos();
    private final ObservadorGravado obs = new ObservadorGravado();
    private final InstaladorDeAgente instalador = new InstaladorDeAgente(servidor, p, PACOTE, ganchos).comSonda(Duration.ofMillis(150), Duration.ofMillis(10));

    private RelatorioDeVerificacao verificar() throws Exception {
        servidor.limparRegistro();
        return instalador.verificar(ModoDaInstalacao.INSTALAR);
    }

    private EscolhaDeInstalacao escolher(Set<String> ids) throws Exception {
        return EscolhaDeInstalacao.escolher(CatalogoDeInstalacao.itens(), verificar(), ids);
    }

    @Test
    void soODockerInstalaSoODockerENaoTocaNoAgenteNemNosGanchos() throws Exception {
        servidor.ubuntu().semDocker();
        EscolhaDeInstalacao e = escolher(Set.of("docker"));
        instalador.escolher(e);
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertEquals(ComandosRemotos.instalarODocker(false, "ubuntu"), servidor.escritas(), "só os comandos do Docker, nada de pasta, pacote, imagem ou container");
        assertTrue(servidor.envios.isEmpty(), "nenhum arquivo foi enviado");
        assertNull(servidor.containerNome);
        assertEquals(List.of(), ganchos.chamadas, "nem a sonda nem o token: o agente não foi escolhido");
        assertEquals(List.of(PassoDeInstalacao.DOCKER), instalador.passosAExecutar());
        assertEquals(Set.of(PassoDeInstalacao.DOCKER), obs.ultimo.keySet(), "só o passo do Docker foi mostrado");
        assertEquals(EstadoDoPasso.FEITO, obs.ultimo.get(PassoDeInstalacao.DOCKER));
    }

    @Test
    void soOAgenteComODockerPresenteNaoTemOPassoDoDocker() throws Exception {
        servidor.noGrupoDocker = true;
        instalador.escolher(escolher(Set.of("agente")));
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertFalse(obs.ultimo.containsKey(PassoDeInstalacao.DOCKER), "o Docker não foi escolhido: nem aparece como passo");
        assertEquals(5, obs.ultimo.size());
        assertEquals(List.of(ComandosRemotos.criarPasta(p), ComandosRemotos.extrairPacote(p, PACOTE), ComandosRemotos.construirImagem("docker", p, PACOTE),
            ComandosRemotos.subirContainer("docker", p, PACOTE)), servidor.escritas());
        assertEquals(List.of("sondar", "registrarToken"), ganchos.chamadas);
    }

    @Test
    void completoSemDockerFazOsDoisNaOrdemOrdemDoInstalador() throws Exception {
        servidor.ubuntu().semDocker();
        instalador.escolher(EscolhaDeInstalacao.completo(CatalogoDeInstalacao.itens(), verificar()));
        servidor.limparRegistro();
        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);
        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertEquals(List.of(PassoDeInstalacao.values()), instalador.passosAExecutar());
        assertEquals(ComandosRemotos.instalarODocker(false, "ubuntu"), servidor.escritas().subList(0, 8));
        assertEquals("sudo -n docker", ganchos.dockerVisto);
        assertEquals(6, obs.ultimo.size());
    }

    @Test
    void escolhaQueDeixariaOServidorPelaMetadeEhRecusadaSemEscreverNada() throws Exception {
        servidor.ubuntu().semDocker();
        EscolhaDeInstalacao soOAgente = escolher(Set.of("agente")); // o agente precisa do Docker, que falta
        assertFalse(soOAgente.valida());

        IllegalArgumentException falha = assertThrows(IllegalArgumentException.class, () -> instalador.escolher(soOAgente));
        assertTrue(falha.getMessage().contains("precisa do Docker, que falta neste servidor"), falha.getMessage());
        assertTrue(servidor.escritas().isEmpty(), "recusar não escreve nada: " + servidor.escritas());
        assertThrows(IllegalArgumentException.class, () -> instalador.escolher(EscolhaDeInstalacao.soSsh()), "Só SSH não é uma instalação");
        assertThrows(IllegalArgumentException.class, () -> instalador.escolher(escolher(Set.of())), "nada marcado também não");
    }

    @Test
    void escolherAntesDeConferirOServidorEhErro() {
        assertThrows(IllegalStateException.class, () -> instalador.escolher(EscolhaDeInstalacao.soSsh()));
    }

    @Test
    void oQueNaoFoiEscolhidoNaoEConferidoAPontoDeBarrarAInstalacao() throws Exception {
        servidor.ubuntu().semDocker();
        servidor.discoLivreKib = 100 * 1024; // 100 MB: problema do agente (imagem e build), não do Docker
        servidor.internet = "000";
        servidor.portaOcupada = true;
        RelatorioDeVerificacao completo = verificar();
        assertTrue(completo.bloqueado(), "para o agente há problemas");

        RelatorioDeVerificacao soDocker = instalador.escolher(EscolhaDeInstalacao.escolher(CatalogoDeInstalacao.itens(), completo, Set.of("docker")));
        assertFalse(soDocker.bloqueado(), soDocker.problemas().toString());
        assertEquals(Set.of(Item.SISTEMA, Item.PERMISSAO, Item.DOCKER), soDocker.resultados().stream().map(ResultadoDaVerificacao::item).collect(java.util.stream.Collectors.toSet()));
        servidor.limparRegistro();
        assertEquals(Desfecho.CONCLUIDA, instalador.instalar(obs, Cancelamento.NUNCA).desfecho());

        // com o agente na escolha os mesmos problemas voltam a barrar, e nada roda
        InstaladorDeAgente outro = new InstaladorDeAgente(servidor, p, PACOTE, ganchos);
        servidor.dockerInstalado = true;
        servidor.noGrupoDocker = true;
        RelatorioDeVerificacao comDocker = outro.verificar(ModoDaInstalacao.INSTALAR);
        outro.escolher(EscolhaDeInstalacao.completo(CatalogoDeInstalacao.itens(), comDocker));
        servidor.limparRegistro();
        ResultadoDaInstalacao recusada = outro.instalar(new ObservadorGravado(), Cancelamento.NUNCA);
        assertEquals(Desfecho.RECUSADA, recusada.desfecho());
        assertTrue(servidor.escritas().isEmpty());
    }

    @Test
    void falhaAoInstalarSoODockerNaoDesfazNada() throws Exception {
        servidor.ubuntu().semDocker();
        servidor.falharEm(c -> c.contains("apt-get install -y docker-ce"), 100, "E: Unable to locate package docker-ce");
        instalador.escolher(escolher(Set.of("docker")));
        servidor.limparRegistro();

        ResultadoDaInstalacao r = instalador.instalar(obs, Cancelamento.NUNCA);

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDeInstalacao.DOCKER, r.passo());
        assertTrue(r.erro().contains("Unable to locate package"), r.erro());
        assertEquals(List.of(), r.desfeito(), "não havia container, volume, imagem nem pasta desta execução");
        assertNull(servidor.containerNome);
    }

    @Test
    void semEscolherNadaOInstaladorFazTudoComoSempreFez() throws Exception {
        servidor.noGrupoDocker = true;
        verificar();
        assertEquals(List.of(PassoDeInstalacao.values()), instalador.passosAExecutar());
        assertEquals(Desfecho.CONCLUIDA, instalador.instalar(obs, Cancelamento.NUNCA).desfecho());
        assertEquals(6, obs.ultimo.size());
    }
}
