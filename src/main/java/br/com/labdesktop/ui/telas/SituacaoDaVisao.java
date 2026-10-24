package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ui.ConteudoDosAvisos;
import br.com.labdesktop.ui.Gravidade;
import java.time.Instant;
import java.time.ZoneId;

/**
 * O que a Visão geral diz sobre o estado dos DADOS que mostra: carregando, normal, sem conexão (com os últimos dados,
 * esmaecidos, ou sem nada ainda) ou agente fora (o programa do painel no servidor não responde ou é antigo). Puro:
 * estado e instante entram, texto sai. Quem desenha é a {@link VisaoGeralDoServidor}.
 *
 * @param modo      a situação
 * @param gravidade o peso do aviso
 * @param titulo    a frase de cima; vazia se não há aviso
 * @param texto     a explicação, em palavras simples
 * @param esmaecer  os dados na tela são antigos: mostrar apagados, com o aviso dizendo de quando são
 */
public record SituacaoDaVisao(Modo modo, Gravidade gravidade, String icone, String titulo, String texto, boolean esmaecer) {

    /** As situações. */
    public enum Modo { CARREGANDO, NORMAL, SEM_CONEXAO_COM_DADOS, SEM_CONEXAO_SEM_DADOS, SEM_AGENTE_COM_DADOS, SEM_AGENTE_SEM_DADOS }

    private static final SituacaoDaVisao NORMAL = new SituacaoDaVisao(Modo.NORMAL, Gravidade.NEUTRO, null, "", "", false);
    private static final SituacaoDaVisao CARREGANDO = new SituacaoDaVisao(Modo.CARREGANDO, Gravidade.NEUTRO, null, "", "", false);

    /** Há aviso a mostrar. */
    public boolean temAviso() {
        return !titulo.isEmpty();
    }

    /**
     * @param estado        a conexão ({@code null} antes da primeira leitura do estado)
     * @param temDados      já chegou ao menos uma leitura de {@code /api/servidor}
     * @param ultimaLeitura quando foi a última leitura (a hora que o aviso cita); pode ser {@code null}
     */
    public static SituacaoDaVisao de(EstadoServidor estado, boolean temDados, Instant ultimaLeitura, Instant agora, ZoneId fuso) {
        if (estado == null) {
            return temDados ? NORMAL : CARREGANDO;
        }
        String hora = ultimaLeitura == null ? null : FormatosDoServidor.horaMinuto(ultimaLeitura, fuso);
        EstadoConexao c = estado.conexao();
        if (c == EstadoConexao.CONECTADO) {
            if (!temDados) {
                return CARREGANDO;
            }
            if (estado.agenteIncompativel()) {
                var aviso = ConteudoDosAvisos.deConexao(estado, agora, fuso);
                return new SituacaoDaVisao(Modo.NORMAL, Gravidade.ATENCAO, "alerta", aviso.titulo(), aviso.texto(), false);
            }
            return NORMAL;
        }
        if (c == EstadoConexao.CONECTANDO && !temDados) {
            return CARREGANDO;
        }
        if (c == EstadoConexao.SSH_OK_SEM_AGENTE) {
            Falha.Tipo tipo = estado.falha() == null ? null : estado.falha().tipo();
            String explicacao = tipo == Falha.Tipo.AGENTE_DESATUALIZADO
                ? "O servidor responde, mas o programa do painel (o agente) que roda nele é de uma versão antiga e não tem as "
                    + "informações que este app precisa. Falta publicar o painel no servidor."
                : "O servidor responde, mas o programa do painel (o agente) que roda nele não está respondendo. Ele pode estar "
                    + "reiniciando; o app tenta de novo sozinho.";
            return temDados
                ? new SituacaoDaVisao(Modo.SEM_AGENTE_COM_DADOS, Gravidade.ATENCAO, "alerta",
                    "O agente do painel não responde: mostrando os últimos dados de " + (hora == null ? "antes" : hora),
                    explicacao, true)
                : new SituacaoDaVisao(Modo.SEM_AGENTE_SEM_DADOS, Gravidade.ATENCAO, "alerta",
                    "O agente do painel não responde neste servidor", explicacao, false);
        }
        var aviso = ConteudoDosAvisos.deConexao(estado, agora, fuso);
        String causa = aviso == null ? "Conectando ao servidor…" : aviso.titulo() + ". " + aviso.texto();
        if (temDados) {
            return new SituacaoDaVisao(Modo.SEM_CONEXAO_COM_DADOS, Gravidade.ATENCAO, "rede",
                "Sem conexão com o servidor: mostrando os últimos dados de " + (hora == null ? "antes" : hora),
                causa, true);
        }
        return new SituacaoDaVisao(Modo.SEM_CONEXAO_SEM_DADOS, Gravidade.ATENCAO, "rede",
            "Sem conexão com o servidor", causa + " Assim que a conexão voltar, as informações aparecem aqui.", false);
    }
}
