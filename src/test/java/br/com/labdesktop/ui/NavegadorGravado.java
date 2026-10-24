package br.com.labdesktop.ui;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Um {@link Navegador} que não abre nada: anota as URLs que passaram pela lista de permitidos. */
public final class NavegadorGravado extends Navegador {

    public final List<URI> abertas = new ArrayList<>();

    public NavegadorGravado(br.com.labdesktop.ui.componentes.Notificacoes avisos) {
        super(avisos);
    }

    @Override
    protected void entregarAoSistema(URI uri) {
        abertas.add(uri);
    }
}
