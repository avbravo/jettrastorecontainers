package jcf;

/**
 * Roles de aplicación según microprofile-config.properties.
 */
public enum AppRole {
    ADMIN,
    MANAGER,
    USER;

    public String getValue() {
        return name();
    }
}
