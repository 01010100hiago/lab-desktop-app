package br.com.labdesktop.cloudflare;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Remover endereço": tira a regra do túnel e o registro de DNS que o APP criou. Só o que é dele: o registro de DNS precisa levar o comentário {@code lab-desktop:} (e apontar
 * para este túnel) e, no config.yml, a regra precisa levar a marca {@code # lab-desktop:} do mesmo endereço. Uma regra ou um registro sem a marca NUNCA é apagado, mesmo
 * com o nome igual.
 *
 * <p>Duas fases, como em tudo: {@link #preparar} só lê e diz o que seria removido; {@link #executar} só depois do clique (e da confirmação digitando o endereço, que é da
 * tela). Ordem: primeiro a regra (no arquivo, com cópia datada, conferência e reinício), depois o DNS. Se o DNS não sai, a regra volta (a mesma reversão da publicação), para
 * nada ficar pela metade.
 */
public final class RemovedorDeEndereco implements AutoCloseable {

    /** O que seria removido. */
    public record Previa(String hostname, String projeto, String servico, String dnsId, String diferencaDoTunel, List<String> passos, List<String> avisos, String bloqueio,
                         Tunel.Mudanca mudancaDoTunel) {
        public Previa {
            passos = List.copyOf(passos);
            avisos = List.copyOf(avisos);
        }

        public boolean podeRemover() {
            return bloqueio == null;
        }
    }

    /** O resultado. */
    public record Resultado(boolean removido, String falha, List<String> desfeito, List<String> naoConsegui, List<String> copias) {
        public Resultado {
            desfeito = List.copyOf(desfeito);
            naoConsegui = List.copyOf(naoConsegui);
            copias = List.copyOf(copias);
        }
    }

    private final ApiCloudflare api;
    private final ConfiguracaoCloudflare cfg;
    private final Tunel tunel;

    public RemovedorDeEndereco(ApiCloudflare api, ConfiguracaoCloudflare cfg, Tunel tunel) {
        this.api = api;
        this.cfg = cfg;
        this.tunel = tunel;
    }

    @Override
    public void close() {
        api.close();
    }

    /** Lê e diz o que seria removido. Não escreve nada. */
    public Previa preparar(String hostname) throws FalhaDoProjeto {
        String h = hostname == null ? "" : hostname.toLowerCase(java.util.Locale.ROOT);
        String sufixo = "." + cfg.zonaNome();
        if (!h.endsWith(sufixo) || !NomeDoEndereco.valido(h.substring(0, h.length() - sufixo.length()))) {
            return bloqueada(h, "Esse não é um endereço de " + cfg.zonaNome() + " com um nível só.");
        }
        String alvo = NomeDoEndereco.alvoDoTunel(cfg.tunelId());
        RegistroDnsCf dns = null;
        String projeto = "";
        List<String> bloqueios = new ArrayList<>();
        try {
            for (RegistroDnsCf r : api.registrosDnsComONome(cfg.zonaId(), h)) {
                if (!List.of("A", "AAAA", "CNAME").contains(r.tipo())) {
                    continue;
                }
                Optional<String> doProjeto = NomeDoEndereco.projetoDoComentario(r.comentario());
                if (r.tipo().equals("CNAME") && r.conteudo().equalsIgnoreCase(alvo) && doProjeto.isPresent()) {
                    dns = r;
                    projeto = doProjeto.get();
                } else {
                    bloqueios.add("O registro de DNS " + h + " não foi criado por este app (ou aponta para outro lugar): o app não o apaga.");
                }
            }
        } catch (ErroCloudflare e) {
            throw FalhasDaCloudflare.de(e);
        }
        Tunel.Mudanca m = tunel.prepararRemocao(h, null);
        if (m.bloqueio() != null) {
            bloqueios.add(m.bloqueio());
        }
        if (!tunel.regraTemMarca() && dns == null && !m.semEfeito() && m.bloqueio() == null) {
            // a regra de um túnel gerido pela API não leva marca: sem o registro de DNS do app, nada diz que ela é do app
            bloqueios.add("A regra de " + h + " está no túnel, mas não há registro de DNS do app para ele, e a configuração do túnel pela API não guarda marca na regra: o app não sabe se ela "
                + "é sua e não a tira. Tire-a à mão se quiser.");
        }
        if (dns == null && m.semEfeito() && bloqueios.isEmpty()) {
            bloqueios.add("Não achei nada deste endereço (nem o registro de DNS do app, nem a regra no túnel).");
        }
        List<String> passos = new ArrayList<>();
        if (!m.semEfeito() && m.bloqueio() == null) {
            passos.add("Túnel: tirar " + tunel.deOnde() + " a regra de " + h + (tunel.reiniciaOCloudflared() ? "; guardar uma cópia datada, conferir com o cloudflared e reiniciar SÓ o container " + cfg.containerDoCloudflared() : "") + ".");
        } else if (m.bloqueio() == null) {
            passos.add("Túnel: a regra de " + h + " já não está " + tunel.emOnde() + ".");
        }
        if (dns != null) {
            passos.add("DNS: apagar o registro CNAME " + h + " (marcado como do app, projeto " + projeto + ").");
        }
        List<String> avisos = new ArrayList<>(m.avisos());
        avisos.add("O container e o compose do projeto NÃO mudam: só o endereço público sai. (A rede edge e o container_name continuam no compose.)");
        return new Previa(h, projeto, "", dns == null ? null : dns.id(), m.diferenca(), passos, avisos, bloqueios.isEmpty() ? null : String.join(" ", bloqueios), m);
    }

    private static Previa bloqueada(String h, String frase) {
        return new Previa(h, "", "", null, "", List.of(), List.of(), frase, null);
    }

    /** Faz o que {@link #preparar} mostrou. Bloqueia: thread de fundo. Devolve o que aconteceu (e o que desfez, se algo falhou no meio). */
    public Resultado executar(Previa previa, ObservadorDaEtapa obs, Cancelamento cancelamento) {
        if (!previa.podeRemover()) {
            return new Resultado(false, previa.bloqueio(), List.of(), List.of(), List.of());
        }
        List<String> copias = new ArrayList<>();
        Tunel.Aplicada aplicada = null;
        Etapa atual = Etapa.REMOVER_REGRA;
        // o passo da regra conta o que falhou sozinho; se ele lançar sem ter dito nada (cancelou antes, o arquivo mudou), a falha ainda é DELE, nunca do DNS
        boolean[] regraJaDisseQueFalhou = {false};
        ObservadorDaEtapa vigia = (e, st, f) -> {
            if (st == ObservadorDaEtapa.Estado.FALHOU) {
                regraJaDisseQueFalhou[0] = true;
            }
            obs.etapa(e, st, f);
        };
        try {
            if (cancelamento != null && cancelamento.cancelado()) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.");
            }
            aplicada = previa.mudancaDoTunel().aplicar(vigia, cancelamento);
            if (aplicada.copia() != null) {
                copias.add(aplicada.copia());
            }
            if (cancelamento != null && cancelamento.cancelado()) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.");
            }
            atual = Etapa.REMOVER_DNS;
            if (previa.dnsId() != null) {
                obs.etapa(Etapa.REMOVER_DNS, ObservadorDaEtapa.Estado.ANDANDO, "Apagando o registro de DNS…");
                apagarDns(previa);
                obs.etapa(Etapa.REMOVER_DNS, ObservadorDaEtapa.Estado.FEITO, "Registro apagado.");
            } else {
                obs.etapa(Etapa.REMOVER_DNS, ObservadorDaEtapa.Estado.PULADO, "Não havia registro de DNS do app.");
            }
            return new Resultado(true, null, List.of(), List.of(), copias);
        } catch (FalhaDoProjeto f) {
            if (atual == Etapa.REMOVER_DNS) {
                obs.etapa(Etapa.REMOVER_DNS, ObservadorDaEtapa.Estado.FALHOU, f.mensagem());
            } else if (!regraJaDisseQueFalhou[0]) {
                obs.etapa(Etapa.REMOVER_REGRA, ObservadorDaEtapa.Estado.FALHOU, f.mensagem());
            }
            List<String> desfeito = new ArrayList<>();
            List<String> naoConsegui = new ArrayList<>();
            if (aplicada != null) {
                List<String> sobrou = aplicada.desfazer();
                if (sobrou.isEmpty()) {
                    desfeito.add("Devolvi a regra do túnel como estava.");
                    obs.etapa(Etapa.REMOVER_REGRA, ObservadorDaEtapa.Estado.DESFEITO, "A regra do túnel voltou.");
                } else {
                    naoConsegui.addAll(sobrou);
                }
            }
            return new Resultado(false, f.mensagem(), desfeito, naoConsegui, copias);
        }
    }

    /**
     * Apaga o registro. Se o DELETE termina sem resposta (prazo, conexão), o registro pode ter sido apagado mesmo assim: o app RELÊ o nome antes de dizer que falhou. Se o
     * registro já sumiu, deu certo (e a regra do túnel NÃO é restaurada: o endereço estava sendo removido de verdade); se continua lá, é falha e a regra volta.
     */
    private void apagarDns(Previa previa) throws FalhaDoProjeto {
        try {
            api.removerDns(cfg.zonaId(), previa.dnsId());
        } catch (ErroCloudflare e) {
            if (e.tipo() == ErroCloudflare.Tipo.NAO_ENCONTRADO) {
                return;
            }
            if (e.incerto()) {
                try {
                    boolean aindaLa = api.registrosDnsComONome(cfg.zonaId(), previa.hostname()).stream().anyMatch(r -> r.id().equals(previa.dnsId()));
                    if (!aindaLa) {
                        return;
                    }
                } catch (ErroCloudflare conferencia) {
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.INCERTO, e.mensagem() + " E também não consegui conferir se o registro foi apagado: " + conferencia.mensagem(), null, e);
                }
            }
            throw FalhasDaCloudflare.de(e);
        }
    }
}
