package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.util.InformacoesDoApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.function.UnaryOperator;

/**
 * Tudo o que a tela Ajustes precisa do resto do app, num lugar só (a tela não conhece a janela, o gerenciador de tema nem o arquivo de preferências): ler e mudar as
 * preferências, trocar o tema e o tamanho do texto na hora, avisar as barras de abas, e os dados da tela Sobre. Os testes implementam esta interface com um
 * app de mentira.
 */
public interface ControleDeAjustes {

    /** As preferências de agora. */
    Preferencias preferencias();

    /** Muda as preferências (na memória na hora e no disco em segundo plano). */
    void atualizarPreferencias(UnaryOperator<Preferencias> mudanca);

    /**
     * Troca o tema na hora, sem reiniciar, e lembra a escolha. Claro e escuro valem já; "Sistema" lê o computador antes (um instante, fora da EDT) para o tema nascer certo.
     * {@code depois} roda na EDT quando a escolha já está na tela e gravada.
     */
    void trocarTema(Tema tema, Runnable depois);

    /** O sistema está no modo escuro agora? (o que "Sistema" segue) */
    boolean sistemaEstaEscuro();

    /** O tema que está na tela agora é o escuro? */
    boolean temaEscuroAgora();

    /** Pede para ser avisado depois de cada troca de tema (para a linha "o sistema está usando agora..." acompanhar). */
    void aoMudarOTema(Runnable ouvinte);

    /** Muda o tamanho do texto (100, 115 ou 130 %) na hora e lembra. */
    void mudarTamanhoDoTexto(int porcento);

    /** O tamanho do texto de agora, em %. */
    int tamanhoDoTexto();

    /** As preferências das barras de abas mudaram: aplica nas páginas já abertas (na hora). */
    void aoMudarAsAbas();

    /** O id da aba aberta agora na página do servidor (ela não pode ser escondida), ou {@code null}. */
    String abaAbertaDoServidor();

    /** O id da aba aberta agora na página do projeto, ou {@code null} se nenhum projeto está aberto. */
    String abaAbertaDoProjeto();

    /** Os atalhos do menu como estão agora (a Ajuda os mostra e cita; saem do próprio menu). */
    java.util.List<br.com.labdesktop.ajuda.AtalhoDoMenu> atalhosDoMenu();

    /** Abre a tela que um link da Ajuda cita (só abre; nunca escreve). Devolve {@code false} se não deu (por exemplo, não há servidor para abrir uma aba dele). */
    boolean irPara(br.com.labdesktop.ajuda.Destino destino);

    /** O nome de um servidor pelo id (a Segurança mostra o nome, não o id); sem servidor com esse id, o próprio id. */
    default String nomeDoServidor(String id) {
        return id;
    }

    /** Os dados do Sobre. */
    InformacoesDoApp informacoes();

    CopiadorDeTexto copiador();

    Notificacoes avisos();
}
