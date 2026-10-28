package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.instalador.InstaladorDeGit;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;

/**
 * "Instalar o Git" num servidor: lê o que há (só leitura) e MOSTRA o sistema, o gerenciador de pacotes e o comando EXATO que será executado; só roda depois do clique em "Instalar o Git". Se o usuário
 * não é root nem tem {@code sudo} sem senha, o app não executa nada: mostra o comando para o usuário rodar no Terminal (com Copiar) e o botão "Já instalei, conferir de novo". No fim confere
 * {@code git --version} e mostra o resultado. Não mexe na configuração do git. Nunca fica preso: Esc, o X e o botão primeiro PEDEM para parar de esperar; o segundo clique fecha e a instalação termina
 * em segundo plano, com o resultado nos avisos. Nada de rede na EDT.
 */
public final class DialogoInstalarGit extends Dialogo {

    /** O que o diálogo precisa do domínio. Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {
        InstaladorDeGit.Previa conferir() throws Exception;

        InstaladorDeGit.Resultado rodar(InstaladorDeGit.Previa previa, InstaladorDeGit.Observador observador, Cancelamento cancelamento) throws InterruptedException;

        InstaladorDeGit.Estado conferirDeNovo() throws Exception;
    }

    private static final int LARGURA = 520;

    private final Motor motor;
    private final String servidor;
    private final Notificacoes avisos;
    private final Runnable aoMudar;
    private final Rotulo situacao = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido explicacao = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Rotulo tituloDosComandos = new Rotulo("").negrito();
    private final AreaMonoespacada areaDosComandos = new AreaMonoespacada("Os comandos", false);
    private final JScrollPane rolagemDosComandos = AreaMonoespacada.emRolagem(areaDosComandos);
    private final Botao copiar;
    private final JProgressBar progresso = new JProgressBar();
    private final Rotulo textoDoProgresso = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Selo selo = new Selo("", Gravidade.NEUTRO);
    private final TextoCorrido textoDoResultado = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JComponent blocoDoResultado;
    private final Botao instalar = Botoes.primario("Instalar o Git", "baixar");
    private final Botao conferirDeNovo = Botoes.secundario("Já instalei, conferir de novo", "atualizar");
    private final Botao fechar = Botoes.secundario("Cancelar", null);
    private volatile Cancelamento cancelamento = new Cancelamento();
    private InstaladorDeGit.Previa previa;
    private InstaladorDeGit.Resultado resultado;
    private boolean ocupado;
    private boolean pediuParar;
    private boolean mudou;

    /**
     * @param servidor o nome do servidor, para a frase
     * @param aoMudar  roda (na EDT) quando algo mudou: o git agora está lá (a tela que abriu relê o estado)
     */
    public DialogoInstalarGit(String servidor, Motor motor, CopiadorDeTexto copiador, Notificacoes avisos, Runnable aoMudar) {
        super("Instalar o Git");
        this.motor = motor;
        this.servidor = servidor;
        this.avisos = avisos;
        this.aoMudar = aoMudar == null ? () -> { } : aoMudar;
        this.copiar = BotaoCopiar.criar("Copiar os comandos", areaDosComandos::getText, copiador, avisos);
        instalar.setName("instalar-git");
        conferirDeNovo.setName("conferir-git");
        fechar.setName("fechar");
        for (JComponent c : List.of(explicacao, tituloDosComandos, rolagemDosComandos, copiar, progresso, textoDoProgresso, instalar, conferirDeNovo)) {
            c.setVisible(false);
        }
        rolagemDosComandos.setPreferredSize(new java.awt.Dimension(LARGURA, 74));
        progresso.setIndeterminate(true);
        blocoDoResultado = Caixa.coluna(6, Caixa.linha(8, selo, Caixa.mola()), textoDoResultado);
        blocoDoResultado.setVisible(false);
        JComponent corpo = Caixa.coluna(10, cabecalho("terminal", "Instalar o Git em " + servidor), situacao, explicacao, tituloDosComandos, rolagemDosComandos,
            Caixa.linha(8, copiar, Caixa.mola()), progresso, textoDoProgresso, blocoDoResultado);
        conteudoComRodape(corpo, rodape(null, fechar, conferirDeNovo, instalar));
        botaoPadrao(fechar); // o Enter aciona o seguro
        fechar.addActionListener(e -> aoCancelar());
        instalar.addActionListener(e -> instalar());
        conferirDeNovo.addActionListener(e -> conferirOutraVez());
        carregar();
    }

    // =====================================================================================================
    // Ler (só leitura)
    // =====================================================================================================

    private void carregar() {
        situacao.setText("Conferindo o git neste servidor (só leitura)…");
        Tarefas.rodar(motor::conferir, p -> {
            if (jaFechou()) {
                return;
            }
            previa = p;
            situacao.setVisible(false);
            mostrar(p);
            reajustar();
        }, falha -> {
            if (jaFechou()) {
                return;
            }
            situacao.setText("Não consegui conferir: " + MensagensDeErro.de(falha));
            fechar.setText("Fechar");
            reajustar();
        });
    }

    private void mostrar(InstaladorDeGit.Previa p) {
        switch (p.situacao()) {
            case JA_INSTALADO -> {
                explicacao.setText("O git já está instalado em " + servidor + ": " + p.estado().versao() + ". Não há nada a instalar.");
                explicacao.setVisible(true);
                fechar.setText("Fechar");
            }
            case PODE_INSTALAR -> {
                explicacao.setText("O git não está instalado em " + servidor + (p.sistema().isEmpty() ? "" : " (" + p.sistema() + ")") + ". O app usa o " + p.gerenciador().programa()
                    + " e roda os comandos abaixo, na ordem, " + (p.root() ? "como administrador (você entrou como root)" : "com sudo sem senha (o app nunca digita senha)")
                    + ". Nada muda na configuração do git.");
                explicacao.setVisible(true);
                mostrarComandos("O app vai rodar", p.comandos());
                instalar.setVisible(true);
            }
            case PRECISA_DO_DONO -> {
                explicacao.setText("O git não está instalado em " + servidor + (p.sistema().isEmpty() ? "" : " (" + p.sistema() + ")") + ", e o seu usuário não é root nem tem sudo sem senha. "
                    + "O app não digita senha de administrador: rode os comandos abaixo no Terminal (aba Terminal do servidor), onde você mesmo responde, e depois clique em "
                    + "\"Já instalei, conferir de novo\".");
                explicacao.setVisible(true);
                mostrarComandos("Rode no Terminal", p.comandosParaODono());
                conferirDeNovo.setVisible(true);
                fechar.setText("Fechar");
            }
            case GERENCIADOR_NAO_ACHADO -> {
                explicacao.setText("Não achei o apt-get, dnf, yum, apk, pacman nem zypper em " + servidor + ", então não sei como instalar por aqui. Instale o git do jeito da sua distribuição "
                    + "(pelo Terminal) e clique em \"Já instalei, conferir de novo\".");
                explicacao.setVisible(true);
                conferirDeNovo.setVisible(true);
                fechar.setText("Fechar");
            }
        }
    }

    private void mostrarComandos(String titulo, List<String> comandos) {
        tituloDosComandos.setText(titulo);
        tituloDosComandos.setVisible(true);
        areaDosComandos.setText(String.join("\n", comandos));
        areaDosComandos.setCaretPosition(0);
        rolagemDosComandos.setVisible(true);
        copiar.setVisible(true);
    }

    // =====================================================================================================
    // Conferir de novo (o usuário instalou no Terminal)
    // =====================================================================================================

    private void conferirOutraVez() {
        if (ocupado || jaFechou()) {
            return;
        }
        ocupado = true;
        conferirDeNovo.definirOcupado(true);
        Tarefas.rodar(motor::conferirDeNovo, estado -> {
            ocupado = false;
            conferirDeNovo.definirOcupado(false);
            if (jaFechou()) {
                return;
            }
            if (estado.instalado()) {
                mudou = true;
                blocoDoResultado.setVisible(true);
                selo.atualizar("INSTALADO", Gravidade.OK, null);
                textoDoResultado.setText("O git responde em " + servidor + ": " + estado.versao() + ".");
                conferirDeNovo.setVisible(false);
                tituloDosComandos.setVisible(false);
                rolagemDosComandos.setVisible(false);
                copiar.setVisible(false);
                explicacao.setVisible(false);
                fechar.setText("Fechar");
            } else {
                blocoDoResultado.setVisible(true);
                selo.atualizar("AINDA NÃO", Gravidade.ATENCAO, null);
                textoDoResultado.setText("O git ainda não responde em " + servidor + " (git --version). Confira se o comando terminou sem erro no Terminal e tente de novo.");
            }
            reajustar();
        }, falha -> {
            ocupado = false;
            conferirDeNovo.definirOcupado(false);
            if (jaFechou()) {
                return;
            }
            blocoDoResultado.setVisible(true);
            selo.atualizar("NÃO CONFERI", Gravidade.ATENCAO, null);
            textoDoResultado.setText("Não consegui conferir agora: " + MensagensDeErro.de(falha));
            reajustar();
        });
    }

    // =====================================================================================================
    // Instalar (só depois do clique)
    // =====================================================================================================

    private void instalar() {
        if (ocupado || previa == null || !previa.podeExecutar() || jaFechou()) {
            return;
        }
        InstaladorDeGit.Previa p = previa;
        ocupado = true;
        pediuParar = false;
        cancelamento = new Cancelamento();
        Cancelamento meu = cancelamento;
        instalar.definirOcupado(true);
        fechar.setText("Parar de esperar");
        blocoDoResultado.setVisible(false);
        progresso.setVisible(true);
        textoDoProgresso.setText("Começando…");
        textoDoProgresso.setVisible(true);
        reajustar();
        InstaladorDeGit.Observador obs = frase -> Fundo.naEdt(() -> {
            textoDoProgresso.setText(frase);
            reajustar();
        });
        Tarefas.rodar(() -> motor.rodar(p, obs, meu), this::aoTerminar,
            falha -> aoTerminar(new InstaladorDeGit.Resultado(false, p.estado(), MensagensDeErro.de(falha), "")));
    }

    private void aoTerminar(InstaladorDeGit.Resultado r) {
        ocupado = false;
        resultado = r;
        instalar.definirOcupado(false);
        if (r.instalou()) {
            mudou = true;
        }
        if (jaFechou()) {
            if (r.instalou()) {
                avisos.ok(r.mensagem());
            } else {
                avisos.atencao("Instalar o git em " + servidor + " não terminou: " + r.mensagem());
            }
            aoMudar.run();
            return;
        }
        progresso.setVisible(false);
        textoDoProgresso.setVisible(false);
        blocoDoResultado.setVisible(true);
        selo.atualizar(r.instalou() ? "INSTALADO" : "NÃO INSTALOU", r.instalou() ? Gravidade.OK : Gravidade.PERIGO, null);
        textoDoResultado.setText(r.mensagem() + (r.passoQueFalhou().isEmpty() ? "" : "\nO passo que falhou: " + r.passoQueFalhou()));
        fechar.setText("Fechar");
        if (r.instalou()) {
            instalar.setVisible(false);
            tituloDosComandos.setVisible(false);
            rolagemDosComandos.setVisible(false);
            copiar.setVisible(false);
            explicacao.setVisible(false);
        } else {
            conferirDeNovo.setVisible(true);
        }
        reajustar();
    }

    // =====================================================================================================
    // Fechar (nunca preso)
    // =====================================================================================================

    @Override
    protected void aoCancelar() {
        if (!ocupado) {
            fechar();
            return;
        }
        if (!pediuParar) {
            pediuParar = true;
            cancelamento.cancelar();
            fechar.setText("Fechar mesmo assim");
            textoDoProgresso.setText("Parando de esperar… o comando que já está rodando no servidor termina sozinho.");
            reajustar();
            return;
        }
        avisos.info("O app parou de esperar. A instalação termina em segundo plano e o resultado aparece nos avisos.");
        fechar();
    }

    @Override
    protected void aoFechar() {
        if (mudou) {
            aoMudar.run();
        }
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    public Botao botaoInstalar() {
        return instalar;
    }

    public Botao botaoConferirDeNovo() {
        return conferirDeNovo;
    }

    public Botao botaoFechar() {
        return fechar;
    }

    public Botao botaoCopiar() {
        return copiar;
    }

    public boolean ocupado() {
        return ocupado;
    }

    public boolean fechou() {
        return jaFechou();
    }

    public String textoDaExplicacao() {
        return explicacao.isVisible() ? explicacao.getText() : "";
    }

    public String textoDosComandos() {
        return rolagemDosComandos.isVisible() ? areaDosComandos.getText() : "";
    }

    public String tituloDosComandos() {
        return tituloDosComandos.isVisible() ? tituloDosComandos.getText() : "";
    }

    public String textoDaSituacao() {
        return situacao.isVisible() ? situacao.getText() : "";
    }

    public String seloDoResultado() {
        return blocoDoResultado.isVisible() ? selo.texto() : "";
    }

    public String textoDoResultado() {
        return blocoDoResultado.isVisible() ? textoDoResultado.getText() : "";
    }

    public String textoDoProgresso() {
        return textoDoProgresso.isVisible() ? textoDoProgresso.getText() : "";
    }

    public InstaladorDeGit.Resultado resultado() {
        return resultado;
    }
}
