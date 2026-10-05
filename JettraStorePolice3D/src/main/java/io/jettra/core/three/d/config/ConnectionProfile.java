package io.jettra.core.three.d.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Perfil de conexión a una instancia o clúster de JettraStore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionProfile {
    private String id;
    private String name;
    private String url;
    private String username;
    private String password;
    private boolean isDefault;

    public ConnectionProfile() {
        this.id = UUID.randomUUID().toString();
    }

    public ConnectionProfile(String name, String url, String username, String password, boolean isDefault) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.isDefault = isDefault;
    }

    public ConnectionProfile(String id, String name, String url, String username, String password, boolean isDefault) {
        this.id = (id != null && !id.isEmpty()) ? id : UUID.randomUUID().toString();
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.isDefault = isDefault;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getHost() {
        if (url == null || url.isEmpty()) return "127.0.0.1";
        String clean = url.replaceFirst("^[a-zA-Z0-9_+.-]+://", "");
        if (clean.contains(":")) {
            return clean.split(":")[0];
        }
        if (clean.contains("/")) {
            return clean.split("/")[0];
        }
        return clean;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public int getPort() {
        if (url == null || url.isEmpty()) return 9091;
        String clean = url.replaceFirst("^[a-zA-Z0-9_+.-]+://", "");
        if (clean.contains(":")) {
            try {
                String pStr = clean.split(":")[1];
                if (pStr.contains("/")) pStr = pStr.split("/")[0];
                return Integer.parseInt(pStr);
            } catch (Exception ignored) {}
        }
        return 9091;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
}
