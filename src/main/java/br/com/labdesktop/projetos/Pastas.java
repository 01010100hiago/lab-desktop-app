package br.com.labdesktop.projetos;

import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ErroSftp;

/** Apoio pequeno a pastas do ambiente (servidor ou computador local). */
final class Pastas {

    private Pastas() { }

    /** Garante que a pasta existe, criando os trechos que faltam, de cima para baixo (nunca mexe no que já há). */
    static void garantir(ClienteSftp c, String caminho) throws ErroSftp {
        String atual = "";
        for (String parte : CaminhoRemoto.partes(CaminhoRemoto.normalizar(caminho))) {
            atual = atual.isEmpty() ? "/" + parte : atual + "/" + parte;
            if (!c.existe(atual)) {
                c.criarPasta(atual);
            }
        }
    }
}
