package io.jettra.store.explorer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionProfile implements Serializable {
    private String id;
    private String name;
    private String url;
    private String username;
    private String password;
    private boolean isDefault;

    public ConnectionProfile() {}

    public ConnectionProfile(String id, String name, String url, String username, String password, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.isDefault = isDefault;
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
    public void setDefault(boolean aDefault) { isDefault = aDefault; }
}
