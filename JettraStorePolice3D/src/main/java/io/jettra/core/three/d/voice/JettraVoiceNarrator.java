package io.jettra.core.three.d.voice;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sistema de narración por voz para eventos y alertas de JettraStore y JettraPolice.
 */
public class JettraVoiceNarrator {
    private static final JettraVoiceNarrator INSTANCE = new JettraVoiceNarrator();
    private volatile boolean enabled = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "JettraVoiceNarrator-Worker");
        t.setDaemon(true);
        return t;
    });

    public static JettraVoiceNarrator getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            speak("Sistema de voz JettraStore activado.");
        }
    }

    public void toggle() {
        setEnabled(!this.enabled);
    }

    public void speak(String text) {
        if (!enabled || text == null || text.trim().isEmpty()) {
            return;
        }
        executor.submit(() -> {
            try {
                // Eliminar caracteres especiales para spd-say
                String clean = text.replaceAll("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ .,:;!?-]", " ").trim();
                if (clean.isEmpty()) return;
                ProcessBuilder pb = new ProcessBuilder("spd-say", "-l", "es", "-r", "10", clean);
                Process p = pb.start();
                p.waitFor();
            } catch (Exception ignored) {
            }
        });
    }
}
