package br.com.labdesktop.util;

import br.com.labdesktop.Versao;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * O que a tela "Sobre" mostra do app e do computador, em texto e sem nada sensível: a versão (a mesma do {@code --versao}), o agente que vai dentro do app, o
 * Java, o sistema e a arquitetura e a pasta de dados (com a pasta do usuário trocada por {@code ~}). É também o texto que o botão "Copiar informações" põe na área de
 * transferência para o usuário colar num chamado: nenhuma senha, chave, token nem endereço de servidor entra aqui.
 *
 * @param agenteVersao versão do agente empacotado ({@code 0.1.0-abc1234}), ou "não incluído"
 * @param agenteCommit commit do repositório do agente de onde o agente foi montado (o {@code -PagenteCommit} de {@code empacotarAgente}), ou "não incluído"
 */
public record InformacoesDoApp(String nome, String versao, String agenteVersao, String agenteCommit, String versaoDoJava, String sistema, String arquitetura,
                               String pastaDeDados) {

    private static final String RECURSO_DO_AGENTE = "/agente/agente.json";
    private static final String NAO_INCLUIDO = "não incluído";

    /** Lê o que o app sabe dele mesmo (um recurso do jar e as propriedades do sistema); sem E/S de rede nem da pasta de dados. */
    public static InformacoesDoApp coletar(CaminhosApp caminhos) {
        return coletar(caminhos, System.getProperty("user.home"), InformacoesDoApp.class.getResourceAsStream(RECURSO_DO_AGENTE));
    }

    /** Com as entradas dadas (os testes passam um {@code agente.json} e uma pasta do usuário de mentira). */
    static InformacoesDoApp coletar(CaminhosApp caminhos, String pastaDoUsuario, InputStream agenteJson) {
        String versaoDoAgente = NAO_INCLUIDO;
        String commitDoAgente = NAO_INCLUIDO;
        if (agenteJson != null) {
            try (InputStream in = agenteJson) {
                JsonNode n = Json.mapper().readTree(in);
                versaoDoAgente = n.path("versao").asText(NAO_INCLUIDO);
                commitDoAgente = n.path("commit").asText(NAO_INCLUIDO);
            } catch (IOException | RuntimeException e) {
                // o agente não é o assunto aqui: sem o arquivo ou com ele ilegível a tela diz "não incluído"
            }
        }
        String versaoJava = System.getProperty("java.version", "?");
        String fornecedor = System.getProperty("java.vendor", "");
        return new InformacoesDoApp(Versao.NOME, Versao.atual(), versaoDoAgente, commitDoAgente,
            fornecedor.isBlank() ? versaoJava : versaoJava + " (" + fornecedor + ")",
            br.com.labdesktop.plataforma.DescricaoDoSistema.nomeEVersao(), br.com.labdesktop.plataforma.DescricaoDoSistema.arquitetura(),
            redigirPasta(caminhos.pastaDados(), pastaDoUsuario));
    }

    /** O caminho com a pasta do usuário trocada por {@code ~} (o nome da pessoa no disco não precisa sair numa cópia colada num chamado). */
    public static String redigirPasta(Path pasta, String pastaDoUsuario) {
        String texto = pasta.toString();
        if (pastaDoUsuario != null && !pastaDoUsuario.isBlank()) {
            Path casa = Path.of(pastaDoUsuario).toAbsolutePath().normalize();
            if (pasta.equals(casa)) {
                return "~";
            }
            if (pasta.startsWith(casa)) { // por pastas inteiras: /Users/mariana não começa por /Users/maria
                return "~" + pasta.getFileSystem().getSeparator() + casa.relativize(pasta);
            }
        }
        return texto;
    }

    /** O bloco que o botão "Copiar informações" copia: versão, sistema, Java e agente. */
    public String comoTexto() {
        return nome + " " + versao + "\n"
            + "Sistema: " + sistema.strip() + " (" + arquitetura + ")\n"
            + "Java: " + versaoDoJava + "\n"
            + (NAO_INCLUIDO.equals(agenteVersao) ? "Agente empacotado: " + NAO_INCLUIDO
                : "Agente empacotado: " + agenteVersao + " (commit " + agenteCommit + ")");
    }
}
