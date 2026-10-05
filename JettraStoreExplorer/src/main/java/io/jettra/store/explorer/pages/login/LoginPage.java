package io.jettra.store.explorer.pages.login;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.core.server.Page;
import io.jettra.flux.core.Widget;
import io.jettra.flux.pages.FluxBaseHandler;
import io.jettra.flux.theme.PoliceTheme;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.store.explorer.model.JettraUser;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@NoLoginRequired
@Page(path = "/login")
public class LoginPage extends FluxBaseHandler {

    @Override
    protected String getTitle() {
        return "Acceso de Seguridad - JettraStore Explorer";
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        String user = params.get("username");
        String pass = params.get("password");

        if (user == null || user.trim().isEmpty() || pass == null || pass.trim().isEmpty()) {
            redirect(exchange, "/login?error=empty_fields");
            return true;
        }

        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        Optional<JettraUser> authenticated = plugin.authenticate(user.trim(), pass.trim());

        if (authenticated.isPresent()) {
            JettraUser u = authenticated.get();
            setSessionCookie(exchange, u.getUsername(), u.getGlobalRole().name(), "");
            plugin.logPoliceIncident("Security Patrol", "Ingreso exitoso al panel de comando para el usuario '" + u.getUsername() + "' (" + u.getGlobalRole().name() + ")", "OK", "Sesión WEB iniciada");
            redirect(exchange, "/cluster");
            return true;
        } else {
            plugin.logPoliceIncident("Security Patrol", "Intento de inicio de sesión fallido con usuario '" + user + "'", "WARN", "Acceso denegado");
            redirect(exchange, "/login?error=invalid_credentials");
            return true;
        }
    }

    @Override
    protected boolean onGet(HttpExchange exchange, Map<String, String> params) throws IOException {
        if ("true".equals(params.get("logout"))) {
            clearSessionCookie(exchange);
            redirect(exchange, "/login");
            return true;
        }
        return false;
    }

    @Override
    protected Widget buildUI(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        Widget policeCss = Paragraph.of(PoliceTheme.Template.CustomCSS + "\n" + PoliceTheme.Template.CustomJS);

        Widget loginWidget = Login.create()
            .action(JettraServer.resolvePath("/login"))
            .title("🛡️ JETTRASTORE POLICE 3D")
            .logo("https://primefaces.org/cdn/primeng/images/galleria/galleria1.jpg")
            .forgotPasswordUrl(JettraServer.resolvePath("/login?forgot=true"));

        Widget body = Center.of(
            Column.of(
                policeCss,
                loginWidget
            )
        );

        if (params.containsKey("error")) {
            String err = params.get("error");
            String msg = "empty_fields".equals(err) 
                ? "Error: Ingrese su usuario y contraseña requeridos." 
                : "Credenciales de seguridad incorrectas. Verifique usuario y contraseña en JettraStore.";

            Widget alert = Notification.of(
                Paragraph.of(msg).modifier(new io.jettra.flux.core.Modifier().style("margin:0; color:#ef4444; font-weight:700;"))
            ).modifier(new io.jettra.flux.core.Modifier().style("margin-bottom:15px; padding:12px 16px; background-color:rgba(239, 68, 68, 0.15); border:1px solid #ef4444; border-radius:8px; width:100%; max-width:400px; box-sizing:border-box;"));

            body = Center.of(
                Column.of(
                    policeCss,
                    alert,
                    loginWidget
                )
            );
        }

        return Scaffold.of().body(body);
    }
}
