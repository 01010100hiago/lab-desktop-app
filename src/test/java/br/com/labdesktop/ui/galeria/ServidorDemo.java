package br.com.labdesktop.ui.galeria;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.ui.CenarioDeUi;
import java.time.Duration;

/**
 * O servidor de exemplo das imagens da galeria que vêm de testes de Ajustes e da Ajuda: o do {@link CenarioDeUi} com o nome {@code servidor-demo} e o consumo do plano em níveis calmos
 * (a barra lateral mostra o percentual da semana). O {@link CenarioDeUi} deve ter sido criado SEM conectar; o gerenciador novo nasce depois da edição do nome e já o lê.
 */
public record ServidorDemo(GerenciadorServidores gerenciador, ServidorLab lab) {

    public static ServidorDemo preparar(CenarioDeUi cenario) throws Exception {
        var cfg = cenario.cofre.servidor(CofreFalso.ID).orElseThrow();
        cenario.cofre.alterarServidor(CofreFalso.ID, c -> c.editar("servidor-demo", Transporte.DIRETO, cfg.host(), cfg.porta(), cfg.usuario(), cfg.portaAgente())
            .comPin(cenario.ssh.pin()));
        String limites = DadosDaGaleria.limites(MocksDoFront.ler("limites.json"));
        cenario.agente.excecoes = p -> "GET".equals(p.metodo()) && "/api/limites".equals(p.caminho()) ? Resposta.json(200, limites) : null;
        GerenciadorServidores gerenciador = new GerenciadorServidores(cenario.cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        ServidorLab lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        lab.iniciar();
        Espera.ate(Duration.ofSeconds(20), () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO && lab.estado().limites() != null);
        return new ServidorDemo(gerenciador, lab);
    }

    public void fechar() {
        gerenciador.fechar().join();
    }
}
