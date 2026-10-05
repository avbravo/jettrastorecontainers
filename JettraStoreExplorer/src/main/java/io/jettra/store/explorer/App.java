package io.jettra.store.explorer;

import io.jettra.ee.core.IO;
import io.jettra.rest.server.JettraRestServer;
import io.jettra.server.JettraServer;
import io.jettra.server.config.ConfigInjector;
import io.jettra.server.config.JettraConfigProperty;
import io.jettra.server.discoverer.DiscoveredLoad;
import io.jettra.server.openapi.OpenApiHandler;
import io.jettra.server.openapi.SwaggerUIHandler;
import io.jettra.store.explorer.pages.backup.BackupPage;
import io.jettra.store.explorer.pages.cluster.ClusterMonitorPage;
import io.jettra.store.explorer.pages.connections.ConnectionManagerPage;
import io.jettra.store.explorer.pages.explorer.EngineExplorerPage;
import io.jettra.store.explorer.pages.login.LoginPage;
import io.jettra.store.explorer.pages.police.PoliceMonitorPage;
import io.jettra.store.explorer.pages.query.QueryConsolePage;
import io.jettra.store.explorer.pages.security.UserManagerPage;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.util.List;

@DiscoveredLoad
public class App {

    @JettraConfigProperty(name = "app.title")
    private String appTitle;

    @JettraConfigProperty(name = "server.port")
    private String port;

    @JettraConfigProperty(name = "server.contextpath")
    private String contextpath;

    public static JettraServer serverInstance;

    public void initUI() {
        ConfigInjector.inject(this);
        IO.println("Iniciando aplicación: " + (appTitle != null ? appTitle : "JettraStore Explorer"));
    }

    public static void main(String[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--server.port=")) {
                    io.jettra.server.config.JettraConfig.setProperty("server.port", arg.substring("--server.port=".length()).trim());
                } else if (arg.startsWith("--port=") || arg.startsWith("-port=")) {
                    io.jettra.server.config.JettraConfig.setProperty("server.port", arg.substring(arg.indexOf('=') + 1).trim());
                } else if ((arg.equals("-p") || arg.equals("-port") || arg.equals("--port")) && i + 1 < args.length) {
                    io.jettra.server.config.JettraConfig.setProperty("server.port", args[++i].trim());
                } else if (arg.startsWith("--server.contextpath=")) {
                    io.jettra.server.config.JettraConfig.setProperty("server.contextpath", arg.substring("--server.contextpath=".length()).trim());
                } else if (arg.startsWith("--contextpath=") || arg.startsWith("-contextpath=")) {
                    io.jettra.server.config.JettraConfig.setProperty("server.contextpath", arg.substring(arg.indexOf('=') + 1).trim());
                } else if ((arg.equals("-c") || arg.equals("-contextpath") || arg.equals("--contextpath")) && i + 1 < args.length) {
                    io.jettra.server.config.JettraConfig.setProperty("server.contextpath", args[++i].trim());
                }
            }
        }

        App app = new App();
        app.initUI();

        IO.println("Levantando servidor JettraServer empotrado para JettraStore Explorer...");
        JettraServer server = new JettraServer();
        if (app.port != null && !app.port.isBlank()) {
            try {
                server.setPort(Integer.parseInt(app.port.trim()));
            } catch (Exception ignored) {}
        } else {
            server.setPort(8085);
        }

        if (app.contextpath != null && !app.contextpath.isBlank()) {
            JettraServer.setContextPath(app.contextpath.trim());
        } else {
            JettraServer.setContextPath("/jettraexplorer");
        }
        serverInstance = server;

        // Inicializar Plugin de JettraStore
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        IO.success("JettraStorePlugin inicializado. Estado: " + plugin.getMultinodeDisplay() + " | Conexión: " + plugin.getActiveConnection().getName());

        // Registro de Páginas JettraFlux
        server.addHandler("/", LoginPage.class);
        server.addHandler("/login", LoginPage.class);
        server.addHandler("/cluster", ClusterMonitorPage.class);
        server.addHandler("/dashboard", ClusterMonitorPage.class);
        server.addHandler("/explorer", EngineExplorerPage.class);
        server.addHandler("/connections", ConnectionManagerPage.class);
        server.addHandler("/users", UserManagerPage.class);
        server.addHandler("/police", PoliceMonitorPage.class);
        server.addHandler("/backups", BackupPage.class);
        server.addHandler("/query", QueryConsolePage.class);

        // Controladores descubiertos
        List<Class<?>> controllers = new java.util.ArrayList<>(io.jettra.server.discoverer.DiscoveredRegistry.getDiscoveredClasses(App.class));
        server.addHandler("/openapi.json", new OpenApiHandler(controllers));
        server.addHandler("/swagger-ui", new SwaggerUIHandler("/openapi.json"));

        JettraRestServer.registerDiscovered(server, App.class);
        server.start();
    }
}
