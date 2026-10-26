package br.com.labdesktop.ui;

import br.com.labdesktop.seguranca.ListaPermitidaDeUrls;
import br.com.labdesktop.seguranca.ListaPermitidaDeUrls.Tipo;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.util.Fundo;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * Abre um link no navegador do SISTEMA, e só se ele passar pela lista de permitidos (ARQUITETURA 8.2): o servidor
 * manda URLs (login do Claude, pull request, hostname de projeto) e uma URL hostil abriria no navegador do usuário com
 * um clique. O navegador nunca é um componente do app: não há WebView.
 */
public class Navegador {

    private final Notificacoes avisos;

    public Navegador(Notificacoes avisos) {
        this.avisos = avisos;
    }

    /** A página de login do Claude ({@code https://claude.ai/...} ou {@code https://claude.com/...}). */
    public void abrirLogin(String url) {
        abrir(Tipo.LOGIN, url);
    }

    /** O endereço público de um projeto: um hostname {@code *.example.com}, que vira {@code https://<host>}. */
    public void abrirHostDeProjeto(String hostname) {
        abrir(Tipo.HOST_DE_PROJETO, "https://" + hostname);
    }

    /** Um pull request ({@code https://github.com/<repo>/pull/<n>}). */
    public void abrirPullRequest(String url) {
        abrir(Tipo.PULL_REQUEST, url);
    }

    /** Um link impresso no terminal ({@code http} ou {@code https}; {@link ListaPermitidaDeUrls#validarLinkDoTerminal}). */
    public void abrirLinkDoTerminal(String url) {
        Optional<URI> uri = ListaPermitidaDeUrls.validarLinkDoTerminal(url);
        if (uri.isEmpty()) {
            avisos.atencao("Não abri o endereço: só links http e https abrem a partir do terminal.");
            return;
        }
        entregarAoSistema(uri.get());
    }

    private void abrir(Tipo tipo, String url) {
        Optional<URI> uri = ListaPermitidaDeUrls.validar(tipo, url);
        if (uri.isEmpty()) {
            avisos.atencao("Não abri o endereço: ele não está na lista de endereços permitidos do app.");
            return;
        }
        entregarAoSistema(uri.get());
    }

    /** O único ponto que toca o {@code Desktop}: fora da EDT (o macOS pode demorar para responder). */
    protected void entregarAoSistema(URI uri) {
        Fundo.emFundo(() -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Fundo.naEdt(() -> avisos.atencao("Este sistema não abre endereços pelo app."));
                    return;
                }
                Desktop.getDesktop().browse(uri);
            } catch (IOException | RuntimeException e) {
                Fundo.naEdt(() -> avisos.erro("Não consegui abrir o navegador."));
            }
        });
    }
}
