package io.jettra.fx.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConnectionProfile {
    private final String profileName;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String environment;

    public ConnectionProfile(String profileName, String host, int port, String username, String password, String environment) {
        this.profileName = profileName;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.environment = environment;
    }

    public static ConnectionProfile createDefault() {
        return new ConnectionProfile("Local Cluster", "127.0.0.1", 9091, "admin", "admin-jettra", "PRODUCTION");
    }

    public String getProfileName() { return profileName; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getEnvironment() { return environment; }

    public static class ProfileManager {
        private final List<ConnectionProfile> profiles = new CopyOnWriteArrayList<>();
        private ConnectionProfile activeProfile;

        public ProfileManager() {
            ConnectionProfile def = ConnectionProfile.createDefault();
            profiles.add(def);
            this.activeProfile = def;
        }

        public void addProfile(ConnectionProfile profile) {
            profiles.add(profile);
        }

        public void setActiveProfile(ConnectionProfile profile) {
            this.activeProfile = profile;
        }

        public List<ConnectionProfile> getProfiles() {
            return new ArrayList<>(profiles);
        }

        public ConnectionProfile getActiveProfile() {
            return activeProfile;
        }
    }
}
