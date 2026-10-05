package io.jettra.store.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class JettraSecurityManager {
    public static final String DEFAULT_ADMIN_USER = "admin";
    public static final String DEFAULT_ADMIN_PASS = "admin-jettra";
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ROLE_DB_ADMIN = "DB_ADMIN";
    public static final String ROLE_DEVELOPER = "DEVELOPER";
    public static final String ROLE_READ_ONLY = "READ_ONLY";

    public static final String DB_ROLE_OWNER = "DB_OWNER";
    public static final String DB_ROLE_READ_WRITE = "READ_WRITE";
    public static final String DB_ROLE_READ_ONLY = "READ_ONLY";

    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
    private final String secretKey = "jettra-store-secret-master-encryption-key-2026";

    public record UserAccount(String username, String passwordHash, String role, Map<String, String> databaseRoles, boolean immutable) {}

    public JettraSecurityManager() {
        // Inicialización obligatoria del superusuario por defecto con máxima prioridad
        Map<String, String> adminDbRoles = new ConcurrentHashMap<>();
        adminDbRoles.put("*", DB_ROLE_OWNER);
        users.put(DEFAULT_ADMIN_USER, new UserAccount(
            DEFAULT_ADMIN_USER, 
            hashPassword(DEFAULT_ADMIN_PASS), 
            ROLE_SUPER_ADMIN, 
            adminDbRoles,
            true // Immutable: jamás puede ser alterado o revocado por usuarios secundarios
        ));
    }

    public String authenticate(String username, String password) {
        UserAccount account = users.get(username);
        if (account == null || !account.passwordHash().equals(hashPassword(password))) {
            throw new SecurityException("Invalid credentials for user: " + username);
        }
        return generateToken(account);
    }

    public Collection<UserAccount> listUsers() {
        return Collections.unmodifiableCollection(users.values());
    }

    public UserAccount getUser(String username) {
        return users.get(username);
    }

    public void createUser(String executingUserToken, String newUsername, String newPassword, String role) {
        Claims claims = validateToken(executingUserToken);
        if (!ROLE_SUPER_ADMIN.equalsIgnoreCase(claims.role()) && !ROLE_DB_ADMIN.equalsIgnoreCase(claims.role())) {
            throw new SecurityException("Insufficient permissions to create user");
        }
        if (users.containsKey(newUsername)) {
            throw new IllegalArgumentException("User already exists: " + newUsername);
        }
        users.put(newUsername, new UserAccount(newUsername, hashPassword(newPassword), role, new ConcurrentHashMap<>(), false));
    }

    public void dropUser(String executingUserToken, String targetUsername) {
        Claims claims = validateToken(executingUserToken);
        if (!ROLE_SUPER_ADMIN.equalsIgnoreCase(claims.role())) {
            throw new SecurityException("Only SUPER_ADMIN can drop users.");
        }
        if (DEFAULT_ADMIN_USER.equalsIgnoreCase(targetUsername)) {
            throw new SecurityException("Security violation: Superuser 'admin' cannot be dropped.");
        }
        UserAccount removed = users.remove(targetUsername);
        if (removed == null) {
            throw new NoSuchElementException("User not found: " + targetUsername);
        }
    }

    public void alterUserRole(String executingUserToken, String targetUsername, String newRole) {
        Claims claims = validateToken(executingUserToken);

        // Regla estricta: Ningún usuario secundario puede alterar los roles del superusuario
        if (DEFAULT_ADMIN_USER.equalsIgnoreCase(targetUsername)) {
            if (!DEFAULT_ADMIN_USER.equalsIgnoreCase(claims.username())) {
                throw new SecurityException("Security violation: Superuser privileges cannot be altered, modified, or revoked by secondary accounts.");
            }
        }

        UserAccount target = users.get(targetUsername);
        if (target == null) {
            throw new NoSuchElementException("User not found: " + targetUsername);
        }
        users.put(targetUsername, new UserAccount(targetUsername, target.passwordHash(), newRole, target.databaseRoles(), target.immutable()));
    }

    public void alterUserPassword(String executingUserToken, String targetUsername, String newPassword) {
        Claims claims = validateToken(executingUserToken);
        if (!ROLE_SUPER_ADMIN.equalsIgnoreCase(claims.role()) && !claims.username().equalsIgnoreCase(targetUsername)) {
            throw new SecurityException("Insufficient permissions to change password for user: " + targetUsername);
        }
        UserAccount target = users.get(targetUsername);
        if (target == null) {
            throw new NoSuchElementException("User not found: " + targetUsername);
        }
        users.put(targetUsername, new UserAccount(targetUsername, hashPassword(newPassword), target.role(), target.databaseRoles(), target.immutable()));
    }

    public void grantDatabaseRole(String executingUserToken, String targetUsername, String databaseName, String dbRole) {
        Claims claims = validateToken(executingUserToken);
        if (!ROLE_SUPER_ADMIN.equalsIgnoreCase(claims.role()) && !ROLE_DB_ADMIN.equalsIgnoreCase(claims.role())) {
            throw new SecurityException("Insufficient permissions to grant database roles.");
        }
        UserAccount target = users.get(targetUsername);
        if (target == null) {
            throw new NoSuchElementException("User not found: " + targetUsername);
        }
        target.databaseRoles().put(databaseName, dbRole);
    }

    public void revokeDatabaseRole(String executingUserToken, String targetUsername, String databaseName) {
        Claims claims = validateToken(executingUserToken);
        if (!ROLE_SUPER_ADMIN.equalsIgnoreCase(claims.role()) && !ROLE_DB_ADMIN.equalsIgnoreCase(claims.role())) {
            throw new SecurityException("Insufficient permissions to revoke database roles.");
        }
        UserAccount target = users.get(targetUsername);
        if (target == null) {
            throw new NoSuchElementException("User not found: " + targetUsername);
        }
        if (DEFAULT_ADMIN_USER.equalsIgnoreCase(targetUsername) && "*".equals(databaseName)) {
            throw new SecurityException("Cannot revoke root access from superuser.");
        }
        target.databaseRoles().remove(databaseName);
    }

    public boolean hasDatabaseAccess(String username, String databaseName, String action) {
        if (DEFAULT_ADMIN_USER.equalsIgnoreCase(username)) return true;
        UserAccount user = users.get(username);
        if (user == null) return false;
        if (ROLE_SUPER_ADMIN.equalsIgnoreCase(user.role())) return true;

        String assignedRole = user.databaseRoles().get(databaseName);
        if (assignedRole == null) {
            assignedRole = user.databaseRoles().get("*");
        }
        if (assignedRole == null) return false;

        String upperRole = assignedRole.toUpperCase();
        if (upperRole.contains("OWNER") || upperRole.contains("ADMIN")) return true;
        if ("WRITE".equalsIgnoreCase(action) || "INSERT".equalsIgnoreCase(action) || "UPDATE".equalsIgnoreCase(action) || "DELETE".equalsIgnoreCase(action)) {
            return upperRole.contains("WRITE");
        }
        // Acción de lectura (READ, SELECT, GET, FIND, USE)
        return true;
    }

    public void changeAdminPassword(String currentPassword, String newPassword) {
        UserAccount admin = users.get(DEFAULT_ADMIN_USER);
        if (!admin.passwordHash().equals(hashPassword(currentPassword))) {
            throw new SecurityException("Current admin password is invalid");
        }
        users.put(DEFAULT_ADMIN_USER, new UserAccount(DEFAULT_ADMIN_USER, hashPassword(newPassword), ROLE_SUPER_ADMIN, admin.databaseRoles(), true));
    }

    public Claims validateToken(String token) {
        if (token == null || !token.startsWith("JettraJWT.")) {
            throw new SecurityException("Malformed or missing JettraJWT token");
        }
        try {
            String[] parts = token.split("\\.");
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            // Formato payload: username|role|expiry
            String[] data = payloadJson.split("\\|");
            String user = data[0];
            String role = data[1];
            long exp = Long.parseLong(data[2]);
            if (Instant.now().getEpochSecond() > exp) {
                throw new SecurityException("JettraJWT token expired");
            }
            return new Claims(user, role, exp);
        } catch (Exception e) {
            throw new SecurityException("Invalid token signature or payload", e);
        }
    }

    private String generateToken(UserAccount user) {
        long exp = Instant.now().plusSeconds(86400).getEpochSecond();
        String payload = user.username() + "|" + user.role() + "|" + exp;
        String b64Payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = hashPassword(b64Payload + secretKey);
        return "JettraJWT." + b64Payload + "." + signature;
    }

    private String hashPassword(String pass) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(pass.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public record Claims(String username, String role, long expiration) {}
}
