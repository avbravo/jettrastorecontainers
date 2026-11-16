package io.jettra.store.explorer.pages.template;

import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Button;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Dropdown;
import io.jettra.flux.widgets.InputHidden;
import io.jettra.flux.widgets.Label;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.PasswordField;
import io.jettra.flux.widgets.Span;
import io.jettra.flux.widgets.Text;
import io.jettra.flux.widgets.TextArea;
import io.jettra.flux.widgets.TextInput;
import java.util.List;

/**
 * Fábrica compartida de widgets nativos JettraFlux sin uso de RawHtml.
 */
public final class FluxUi {

    private FluxUi() {}

    public static Widget flashBanner(String message) {
        if (message == null || message.isBlank()) return null;
        String lower = message.toLowerCase();
        boolean isError = lower.contains("denegado") || lower.contains("permiso") || lower.contains("error");
        String bg = isError ? "rgba(239, 68, 68, 0.15)" : "rgba(0, 212, 255, 0.15)";
        String border = isError ? "#ef4444" : "#00d4ff";
        String color = isError ? "#ef4444" : "#00d4ff";
        Div flash = Div.of(Text.of((isError ? "⛔ " : "ℹ️ ") + message));
        flash.modifier(new Modifier().style(
                "background: " + bg + "; border: 1px solid " + border + "; color: " + color
                + "; padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.25rem; font-weight: 600; font-size: 0.9rem;"));
        return flash;
    }

    public static Div kpiCard(String title, String value, String sub, String accentColor) {
        Div t = Div.of(Text.of(title));
        t.modifier(new Modifier().cssClass("kpi-title"));
        Div v = Div.of(Text.of(value));
        v.modifier(new Modifier().cssClass("kpi-value").style("color:" + accentColor + ";"));
        Div s = Div.of(Text.of(sub));
        s.modifier(new Modifier().cssClass("kpi-sub"));
        Div card = Div.of(t, v, s);
        card.modifier(new Modifier().cssClass("ui-card").style("border-top: 3px solid " + accentColor + ";"));
        return card;
    }

    public static Label fieldLabel(String text) {
        Label lbl = Label.of(text);
        lbl.modifier(new Modifier().style(
                "font-size: 0.75rem; color: var(--text-muted); display: block; margin-bottom: 0.25rem; font-weight: 600;"));
        return lbl;
    }

    public static TextInput textInput(String name, String value, String placeholder, boolean required) {
        Modifier mod = new Modifier().cssClass("app-input").attribute("type", "text");
        if (required) mod.attribute("required", "required");
        TextInput input = TextInput.of(name, placeholder != null ? placeholder : "");
        if (value != null) input.value(value);
        input.modifier(mod);
        return input;
    }

    public static TextInput disabledInput(String value) {
        TextInput input = TextInput.of("", "");
        input.value(value != null ? value : "");
        input.modifier(new Modifier().cssClass("app-input").attribute("disabled", "disabled").style("opacity: 0.7;"));
        return input;
    }

    public static PasswordField passwordInput(String name, String placeholder, boolean required) {
        Modifier mod = new Modifier().cssClass("app-input");
        if (required) mod.attribute("required", "required");
        PasswordField pf = PasswordField.of(name, placeholder != null ? placeholder : "");
        pf.modifier(mod);
        return pf;
    }

    public static TextArea textArea(String name, String value, int rows, boolean required, String extraStyle) {
        TextArea ta = TextArea.of().name(name).rows(rows);
        if (value != null) ta.value(value);
        Modifier mod = new Modifier().cssClass("app-input")
                .style("font-family: monospace; font-size: 0.85rem;" + (extraStyle != null ? " " + extraStyle : ""));
        if (required) mod.attribute("required", "required");
        ta.modifier(mod);
        return ta;
    }

    public static Dropdown dropdown(String name, List<String> options, String selectedValue) {
        Dropdown dd = Dropdown.of(options).selected(selectedValue != null ? selectedValue : "");
        dd.modifier(new Modifier().cssClass("app-input").attribute("name", name));
        return dd;
    }

    public static InputHidden hidden(String name, String value) {
        return InputHidden.of(name, value != null ? value : "");
    }

    public static Div fieldColumn(String labelText, Widget control, String flexStyle) {
        Div col = Div.of(fieldLabel(labelText), control);
        if (flexStyle != null && !flexStyle.isBlank()) {
            col.modifier(new Modifier().style(flexStyle));
        }
        return col;
    }

    public static Button submitButton(String label, String cssClass, String extraStyle) {
        Button btn = Button.of(label);
        Modifier mod = new Modifier().cssClass(cssClass).attribute("type", "submit");
        if (extraStyle != null && !extraStyle.isBlank()) {
            mod.style(extraStyle);
        }
        btn.modifier(mod);
        return btn;
    }

    public static Link linkButton(String href, String label, String cssClass, String extraStyle) {
        Link link = Link.of(href, label);
        Modifier mod = new Modifier().cssClass(cssClass);
        if (extraStyle != null && !extraStyle.isBlank()) {
            mod.style(extraStyle);
        }
        link.modifier(mod);
        return link;
    }

    public static Span pillBadge(String text, String color) {
        Span s = Span.of(text);
        s.modifier(new Modifier().style(
                "background:rgba(255,255,255,0.08); border:1px solid " + color + "; color:" + color
                + "; padding:2px 8px; border-radius:9999px; font-weight:700; font-size:0.75rem; display:inline-block;"));
        return s;
    }

    public static Span coloredSpan(String text, String style) {
        Span s = Span.of(text);
        if (style != null && !style.isBlank()) {
            s.modifier(new Modifier().style(style));
        }
        return s;
    }

    public static io.jettra.flux.core.Widget styleSheet(String css) {
        return new io.jettra.flux.core.Widget() {
            @Override
            public String render(io.jettra.flux.theme.ThemeData themeData) {
                if (css == null || css.isBlank()) return "";
                return "<style>\n" + css + "\n</style>\n";
            }
        };
    }

    public static Paragraph preBlock(String content, String extraStyle) {
        Paragraph p = Paragraph.of(content != null ? content : "");
        p.modifier(new Modifier().style(
                "margin: 0; font-family: 'JetBrains Mono', Consolas, monospace; font-size: 0.85rem; white-space: pre-wrap; word-break: break-word;"
                + (extraStyle != null ? " " + extraStyle : "")));
        return p;
    }
}

