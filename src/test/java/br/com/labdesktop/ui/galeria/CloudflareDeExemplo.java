package br.com.labdesktop.ui.galeria;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cloudflare.LabSimulado;
import br.com.labdesktop.cloudflare.ObservadorDaEtapa;
import br.com.labdesktop.cloudflare.PublicadorNaInternet;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.CenarioDeCloudflare;

/**
 * A Cloudflare de exemplo da galeria: a Cloudflare FALSA do repositório com a conta conectada ("Conta de Exemplo", o domínio example.com, o túnel "servidor-demo") e dois endereços
 * que o app criou de verdade contra ela (loja.example.com e painel.example.com). Nada fala com a Cloudflare nem com um servidor reais.
 */
final class CloudflareDeExemplo {

    private static final String COMPOSE = "name: \"%s\"\nservices:\n  app:\n    image: node:22-alpine\n    ports:\n      - \"127.0.0.1:3000:3000\"\n    networks:\n      - default\n    restart: unless-stopped\n";
    private static final String CONFIG = "tunnel: " + LabSimulado.UUID + "\ncredentials-file: /etc/cloudflared/" + LabSimulado.UUID + ".json\n\ningress:\n"
        + "  - hostname: sistema.example.com\n    service: http://app:3000\n  - service: http_status:404\n";

    private CloudflareDeExemplo() { }

    static void preparar(CenarioDeCloudflare cf) throws Exception {
        cf.lab.escrever(LabSimulado.CONFIG, CONFIG);
        for (String projeto : new String[] {"loja-web", "painel-vendas"}) {
            cf.lab.escrever(LabSimulado.PROJETOS + "/" + projeto + "/docker-compose.yml", COMPOSE.formatted(projeto));
        }
        cf.conectar(new ConfiguracaoCloudflare(CloudflareFalsa.CONTA, "Conta de Exemplo", CloudflareFalsa.ZONA, "example.com", LabSimulado.UUID, "servidor-demo",
            ConfiguracaoCloudflare.Tipo.ARQUIVO, "lab", LabSimulado.CONFIG, "cloudflared", null));
        publicar(cf, "loja-web", "loja");
        publicar(cf, "painel-vendas", "painel");
    }

    private static void publicar(CenarioDeCloudflare cf, String projeto, String rotulo) throws Exception {
        try (PublicadorNaInternet p = cf.servico.publicador(x -> { })) {
            PublicadorNaInternet.Resultado r = p.executar(p.preparar(new PublicadorNaInternet.Pedido(projeto, "app", projeto + "-app", 3000, rotulo, false)), ObservadorDaEtapa.NENHUM,
                Cancelamento.NUNCA);
            if (r.desfecho() != PublicadorNaInternet.Desfecho.PUBLICADO) {
                throw new AssertionError("o endereço de exemplo não foi publicado: " + r.falha());
            }
        }
    }
}
