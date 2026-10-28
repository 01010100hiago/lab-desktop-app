package br.com.labdesktop.projetos;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.Conflito;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.ErroApi.NaoAutorizado;
import br.com.labdesktop.api.ErroApi.NaoEncontrado;
import br.com.labdesktop.api.ErroApi.RespostaInvalida;
import br.com.labdesktop.api.ErroApi.ServidorErro;
import br.com.labdesktop.api.modelo.RespostaNovoProjeto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cria o projeto pelo agente do lab ({@code POST /api/projetos/novo}). Antes de enviar confere o nome pela regra do agente. A frase de um 400
 * (nome que já existe, nome reservado) é a que o AGENTE devolveu, como texto, sem inventar a lista de reservados. Falha de rede, prazo ou 5xx é
 * INCERTA (o projeto pode ter sido criado): nunca se repete sozinho, e {@link #existe} relê a lista.
 */
public final class CriadorPeloAgente implements CriadorDeProjeto {

    private final AgenteDeProjetos agente;

    public CriadorPeloAgente(AgenteDeProjetos agente) {
        this.agente = agente;
    }

    @Override
    public Resultado criar(String nome, Boolean guiaIa) throws FalhaDoProjeto {
        if (!NomeDoProjeto.valido(nome)) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, NomeDoProjeto.fraseDeRecusa(nome));
        }
        if (!agente.conectado()) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_CONEXAO, "Sem conexão com o servidor. Nada foi criado; espere a conexão voltar e tente de novo.");
        }
        try {
            RespostaNovoProjeto r = agente.criar(nome, guiaIa);
            List<String> avisos = new ArrayList<>();
            if (Boolean.FALSE.equals(guiaIa) && r.guiaIa() == null) {
                avisos.add("Este servidor ainda não entende a opção \"sem guia\": o arquivo CLAUDE.md foi criado do mesmo jeito. Pode apagá-lo.");
            }
            return new Resultado(nome, avisos);
        } catch (ErroApi e) {
            throw traduzir(e);
        }
    }

    static FalhaDoProjeto traduzir(ErroApi e) {
        return switch (e) {
            case NaoAutorizado n -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.NAO_AUTORIZADO,
                n.totpRequerido() ? "O servidor pede o código do autenticador. Digite-o e tente de novo."
                    : "O servidor recusou o acesso do app (veja o aviso no topo da janela).");
            case ErroRede r -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.INCERTO,
                "O servidor não respondeu a tempo. O projeto pode ter sido criado: confira a lista antes de tentar de novo.");
            case ServidorErro s when s.status() == 400 -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, s.mensagem());
            case ServidorErro s when s.status() >= 500 -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.INCERTO,
                "O servidor teve um problema (" + s.status() + ") e pode não ter terminado. O projeto pode ter sido criado: confira a lista antes de tentar de novo.");
            case ServidorErro s -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, s.mensagem());
            case Conflito c -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, c.mensagem());
            case NaoEncontrado n -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO,
                "O servidor não tem esta função (agente antigo ou sem projetos). " + n.mensagem());
            case RespostaInvalida r -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.INCERTO,
                "A resposta do servidor veio estranha. O projeto pode ter sido criado: confira a lista.");
        };
    }

    @Override
    public Optional<Boolean> existe(String nome) {
        try {
            return Optional.of(agente.nomes().contains(nome));
        } catch (ErroApi e) {
            return Optional.empty();
        }
    }
}
