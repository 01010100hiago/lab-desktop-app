package br.com.labdesktop.ui;

import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.tema.Tokens;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * As ações de um servidor, na ordem em que aparecem no menu de contexto da barra de servidores (ARQUITETURA 8.3: na
 * o "Configurar 2FA" mora aqui e no menu do app) e no menu "Servidor" do app. A lista depende do estado:
 * "Conferir impressão" só quando falta o pin, "Digitar código" só quando o agente pede o autenticador, "Registrar token"
 * quando o token falta ou foi recusado; "Editar" e "Remover" sempre. Puro: devolve descrições; quem monta o menu é o Swing.
 */
public final class MenuDoServidor {

    /** Uma ação do menu. */
    public enum Item {
        ATUALIZAR("Atualizar agora", "atualizar"),
        CONECTAR("Conectar agora", "rede"),
        CONFERIR_IMPRESSAO("Conferir a impressão do servidor…", "chave"),
        DIGITAR_CODIGO("Digitar o código do autenticador…", "chave"),
        REGISTRAR_TOKEN("Registrar o token deste computador…", "chave"),
        CONFIGURAR_2FA("Configurar 2FA…", "chave"),
        DESATIVAR_2FA("Desativar 2FA…", "chave"),
        /** O servidor responde por SSH e não tem o agente: abre a confirmação (nada se instala sem o clique e a confirmação). */
        INSTALAR_AGENTE("Instalar agente…", "servidor"),
        /** Só com o agente do perfil monitor que o app instalou: instala a versão nova do pacote, mantendo dados e token. */
        ATUALIZAR_AGENTE("Atualizar agente…", "atualizar"),
        /** Só com o agente do perfil monitor que o app instalou: remove o que o app criou (o Docker fica). */
        REMOVER_AGENTE("Remover agente…", "lixeira"),
        /** "Este computador": confere o que falta (Docker, disco, porta) e cria o laboratório local; só pergunta, nada se instala sem confirmação. */
        PREPARAR_COMPUTADOR("Preparar este computador…", "sistema"),
        /** "Este computador": tira o agente, os dados dele e a rede; nunca os projetos. */
        REMOVER_LABORATORIO("Remover laboratório local…", "lixeira"),
        /** Nome, endereço, porta e usuário do servidor. Sempre disponível. */
        EDITAR("Editar…", "config"),
        /**
         * "Chave recusada": o app usa a senha do usuário UMA vez para instalar a chave do cofre no servidor e prova que ela entra. Só servidor SSH (nunca "Este computador"),
         * em qualquer estado da conexão (o usuário pode querer refazer a chave), e sem o agente: é só SSH.
         */
        INSTALAR_CHAVE("Instalar a chave com a senha…", "chave"),
        /** Tira o servidor do app (com as opções de apagar a chave e revogar o acesso). Sempre disponível. */
        REMOVER("Remover…", "lixeira");

        private final String rotulo;
        private final String icone;

        Item(String rotulo, String icone) {
            this.rotulo = rotulo;
            this.icone = icone;
        }

        public String rotulo() {
            return rotulo;
        }

        public String icone() {
            return icone;
        }
    }

    private MenuDoServidor() { }

    /** Os itens que fazem sentido para o estado dado. */
    public static List<Item> itensPara(EstadoServidor e) {
        List<Item> itens = new ArrayList<>();
        itens.add(Item.ATUALIZAR);
        EstadoConexao c = e.conexao();
        if (c == EstadoConexao.DESCONECTADO || c == EstadoConexao.RECONECTANDO || c == EstadoConexao.ERRO_AUTENTICACAO
            || c == EstadoConexao.SSH_OK_SEM_AGENTE) {
            itens.add(Item.CONECTAR);
        }
        if (c == EstadoConexao.ERRO_CHAVE_HOST && e.falha() != null && e.falha().tipo() == Falha.Tipo.PIN_AUSENTE) {
            itens.add(Item.CONFERIR_IMPRESSAO);
        }
        if (c == EstadoConexao.AGUARDANDO_2FA) {
            itens.add(Item.DIGITAR_CODIGO);
        }
        // Token e 2FA são do agente (docker exec no container dele): sem o agente instalado não há onde registrar nem configurar, e o botão
        // só levaria a um erro de container que não existe. O primeiro passo é Instalar agente.
        boolean semAgente = c == EstadoConexao.SSH_OK_SEM_AGENTE && e.situacaoDoAgente().oferecerInstalar();
        if (!semAgente && c.permiteExec() && (e.semToken() || c == EstadoConexao.TOKEN_RECUSADO)) {
            itens.add(Item.REGISTRAR_TOKEN);
        }
        if (!semAgente && (c.permiteExec() || c == EstadoConexao.CONECTADO)) {
            itens.add(e.estado2fa() == Estado2fa.ATIVO ? Item.DESATIVAR_2FA : Item.CONFIGURAR_2FA);
        }
        if (e.local()) {
            // Este computador: não há SSH nem agente instalado por SSH. "Preparar" confere e cria o laboratório (vale sempre: é onde se vê o que
            // falta); "Remover laboratório" só existe com o agente criado. Sem "Editar": não há endereço, porta de SSH nem usuário.
            itens.add(Item.PREPARAR_COMPUTADOR);
            if ((c == EstadoConexao.CONECTADO && e.agenteDoMonitor()) || e.situacaoDoAgente() == SituacaoDoAgente.INSTALADO_SEM_RESPOSTA) {
                itens.add(Item.REMOVER_LABORATORIO);
            }
            itens.add(Item.REMOVER);
            return itens;
        }
        if (c == EstadoConexao.SSH_OK_SEM_AGENTE && e.situacaoDoAgente().oferecerInstalar()) {
            itens.add(Item.INSTALAR_AGENTE);
        }
        if (c == EstadoConexao.CONECTADO && e.agenteDoMonitor()) {
            // Só o agente do PERFIL MONITOR (o que o app instala) se atualiza e se remove daqui: o agente do lab, de produção, nunca.
            itens.add(Item.ATUALIZAR_AGENTE);
            itens.add(Item.REMOVER_AGENTE);
        }
        // Chave recusada: o botão que resolve vem junto das ações do dia a dia (na tela de servidores é o destaque do cartão); nos outros estados fica ao lado de Editar.
        boolean chaveRecusada = c == EstadoConexao.ERRO_AUTENTICACAO && e.falha() != null && e.falha().tipo() == Falha.Tipo.CHAVE_RECUSADA;
        if (chaveRecusada) {
            itens.add(Item.INSTALAR_CHAVE);
        }
        // Editar e remover valem em QUALQUER estado: é justamente quando a conexão está quebrada (endereço errado, host
        // trocado) que o usuário precisa deles.
        itens.add(Item.EDITAR);
        if (!chaveRecusada && c != EstadoConexao.BLOQUEADO_COFRE) {
            itens.add(Item.INSTALAR_CHAVE);
        }
        itens.add(Item.REMOVER);
        return itens;
    }

    /** Executa o item sobre o servidor. */
    public static void executar(Item item, ServidorLab lab, AcoesDaInterface acoes) {
        switch (item) {
            case ATUALIZAR -> acoes.atualizar(lab);
            case CONECTAR -> acoes.tentarAgora(lab);
            case CONFERIR_IMPRESSAO -> acoes.conferirImpressao(lab);
            case DIGITAR_CODIGO -> acoes.digitarCodigo(lab);
            case REGISTRAR_TOKEN -> acoes.registrarToken(lab);
            case CONFIGURAR_2FA, DESATIVAR_2FA -> acoes.configurar2fa(lab);
            case INSTALAR_AGENTE -> acoes.instalarAgente(lab);
            case PREPARAR_COMPUTADOR -> acoes.prepararEsteComputador(lab);
            case REMOVER_LABORATORIO -> acoes.removerLaboratorioLocal(lab);
            case ATUALIZAR_AGENTE -> acoes.atualizarAgente(lab);
            case REMOVER_AGENTE -> acoes.removerAgente(lab);
            case EDITAR -> acoes.editar(lab);
            case INSTALAR_CHAVE -> acoes.instalarChaveComSenha(lab);
            case REMOVER -> acoes.remover(lab);
        }
    }

    /** O menu de contexto do servidor (monta na hora, com o estado de agora). */
    public static JPopupMenu popup(ServidorLab lab, AcoesDaInterface acoes) {
        JPopupMenu menu = new JPopupMenu();
        for (Item i : itensPara(lab.estado())) {
            if (i == Item.EDITAR) {
                menu.addSeparator(); // o que mexe nos dados do servidor fica separado das ações do dia a dia
            }
            menu.add(itemSwing(i, () -> executar(i, lab, acoes)));
        }
        return menu;
    }

    private static JMenuItem itemSwing(Item i, Runnable acao) {
        JMenuItem m = new br.com.labdesktop.ui.componentes.ItemDeMenuPerigo(i.rotulo(),
            i == Item.REMOVER || i == Item.REMOVER_AGENTE || i == Item.REMOVER_LABORATORIO ? Tokens.PERIGO : Tokens.TEXTO);
        m.putClientProperty("html.disable", Boolean.TRUE);
        m.setIcon(Icones.de(i.icone(), 14));
        m.addActionListener(e -> acao.run());
        return m;
    }
}
