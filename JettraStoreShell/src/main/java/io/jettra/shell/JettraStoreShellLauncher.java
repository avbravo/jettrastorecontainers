package io.jettra.shell;

/**
 * Launcher de arranque independiente para JettraStoreShell.
 * Evita la detección errónea de JavaFX Runtime en HotSpot JVM cuando se ejecuta
 * directamente desde la línea de comandos con java -jar.
 */
public final class JettraStoreShellLauncher {
    public static void main(String[] args) {
        JettraStoreShellApp.main(args);
    }
}
