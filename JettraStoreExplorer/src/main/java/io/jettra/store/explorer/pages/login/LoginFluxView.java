package io.jettra.store.explorer.pages.login;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Text;

/**
 * Página de Inicio de Sesión y Autenticación en JettraFlux.
 */
public class LoginFluxView extends FluxView {

    private final String error;

    public LoginFluxView() {
        this(null);
    }

    public LoginFluxView(String error) {
        this.error = error;
    }

    @Override
    public Widget build() {
        String errorMsg = null;
        if ("invalid_credentials".equals(error)) {
            errorMsg = "Credenciales incorrectas. Verifique su usuario y contraseña.";
        } else if ("empty_fields".equals(error)) {
            errorMsg = "Por favor ingrese tanto el usuario como la contraseña.";
        } else if (error != null) {
            errorMsg = error;
        }

        RawHtml loginCard = RawHtml.of(String.format("""
            <div style="min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #050b14; font-family: 'Inter', -apple-system, sans-serif; padding: 1rem;">
              <div style="width: 100%%; max-width: 420px; background: #0c1626; border: 1px solid #1e3a5f; border-radius: 14px; padding: 2.25rem; box-shadow: 0 10px 30px rgba(0,0,0,0.6);">
                <div style="text-align: center; margin-bottom: 1.75rem;">
                  <div style="display: inline-flex; width: 48px; height: 48px; border-radius: 12px; background: linear-gradient(135deg, #00d4ff, #2563eb); align-items: center; justify-content: center; font-weight: 900; color: #ffffff; font-size: 1.3rem; margin-bottom: 0.75rem;">JS</div>
                  <h1 style="color: #ffffff; font-size: 1.4rem; font-weight: 800; margin: 0 0 0.35rem 0;">JettraStore Explorer</h1>
                  <p style="color: #94a3b8; font-size: 0.85rem; margin: 0;">Panel de Administración y Gestión Multimodelo</p>
                </div>

                %s

                <form method="POST" action="/login">
                  <div style="margin-bottom: 1.25rem;">
                    <label style="display: block; font-size: 0.8rem; font-weight: 600; color: #94a3b8; margin-bottom: 0.4rem;">Usuario</label>
                    <input type="text" name="username" value="admin" style="width: 100%%; box-sizing: border-box; padding: 0.65rem 0.85rem; background: #050b14; border: 1px solid #1e3a5f; border-radius: 8px; color: #ffffff; font-size: 0.9rem; outline: none;" required />
                  </div>

                  <div style="margin-bottom: 1.5rem;">
                    <label style="display: block; font-size: 0.8rem; font-weight: 600; color: #94a3b8; margin-bottom: 0.4rem;">Contraseña</label>
                    <input type="password" name="password" value="admin123" style="width: 100%%; box-sizing: border-box; padding: 0.65rem 0.85rem; background: #050b14; border: 1px solid #1e3a5f; border-radius: 8px; color: #ffffff; font-size: 0.9rem; outline: none;" required />
                  </div>

                  <button type="submit" style="width: 100%%; padding: 0.75rem; background: #00d4ff; color: #050b14; font-weight: 800; border: none; border-radius: 8px; font-size: 0.95rem; cursor: pointer; transition: opacity 0.2s;">
                    ⚡ Iniciar Sesión en JettraStore
                  </button>
                </form>

                <div style="margin-top: 1.5rem; text-align: center; border-top: 1px solid #1e3a5f; padding-top: 1rem; font-size: 0.78rem; color: #64748b;">
                  Credenciales por defecto: <strong>admin / admin123</strong><br/>
                  Powered by JettraServer & Project Loom Virtual Threads
                </div>
              </div>
            </div>
        """, errorMsg != null ? "<div style='background:rgba(239,68,68,0.15); border:1px solid #ef4444; color:#ef4444; padding:0.65rem 0.85rem; border-radius:8px; font-size:0.85rem; font-weight:600; margin-bottom:1.25rem;'>⚠️ " + errorMsg + "</div>" : ""));

        return loginCard;
    }
}

