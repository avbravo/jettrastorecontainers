//! Gestor de Seguridad, Autenticación `JettraJWT` (SHA-256 + Base64URL) y Control de Acceso RBAC.
//! Incluye el superusuario `admin` inmutable por defecto (`SUPER_ADMIN`).

use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

pub const DEFAULT_ADMIN_USER: &str = "admin";
pub const DEFAULT_ADMIN_PASS: &str = "admin-jettra";
pub const ROLE_SUPER_ADMIN: &str = "SUPER_ADMIN";
pub const ROLE_DB_ADMIN: &str = "DB_ADMIN";
pub const ROLE_DEVELOPER: &str = "DEVELOPER";
pub const ROLE_READ_ONLY: &str = "READ_ONLY";

pub const DB_ROLE_OWNER: &str = "DB_OWNER";
pub const DB_ROLE_READ_WRITE: &str = "READ_WRITE";
pub const DB_ROLE_READ_ONLY: &str = "READ_ONLY";

const SECRET_KEY: &str = "jettra-store-secret-master-encryption-key-2026";

#[derive(Debug, Clone)]
pub struct UserAccount {
    pub username: String,
    pub password_hash: String,
    pub role: String,
    pub database_roles: HashMap<String, String>,
    pub immutable: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Claims {
    pub username: String,
    pub role: String,
    pub expiration: u64,
}

#[derive(Debug, Clone)]
pub struct JettraSecurityManager {
    users: Arc<RwLock<HashMap<String, UserAccount>>>,
}

impl Default for JettraSecurityManager {
    fn default() -> Self {
        Self::new()
    }
}

impl JettraSecurityManager {
    pub fn new() -> Self {
        let mut users = HashMap::new();
        let mut admin_db_roles = HashMap::new();
        admin_db_roles.insert("*".to_string(), DB_ROLE_OWNER.to_string());
        users.insert(
            DEFAULT_ADMIN_USER.to_string(),
            UserAccount {
                username: DEFAULT_ADMIN_USER.to_string(),
                password_hash: Self::sha256_hex(DEFAULT_ADMIN_PASS.as_bytes()),
                role: ROLE_SUPER_ADMIN.to_string(),
                database_roles: admin_db_roles,
                immutable: true,
            },
        );
        Self {
            users: Arc::new(RwLock::new(users)),
        }
    }

    pub fn authenticate(&self, username: &str, password: &str) -> Result<String, String> {
        let guard = self.users.read().unwrap();
        let account = guard
            .get(username)
            .ok_or_else(|| format!("Invalid credentials for user: {}", username))?;
        let hash = Self::sha256_hex(password.as_bytes());
        if account.password_hash != hash {
            return Err(format!("Invalid credentials for user: {}", username));
        }
        Ok(Self::generate_token(account))
    }

    pub fn validate_token(&self, token: &str) -> Result<Claims, String> {
        if !token.starts_with("JettraJWT.") {
            return Err("Malformed or missing JettraJWT token".to_string());
        }
        let parts: Vec<&str> = token.split('.').collect();
        if parts.len() != 3 {
            return Err("Invalid JettraJWT structure".to_string());
        }
        let b64_payload = parts[1];
        let signature = parts[2];

        let expected_sig = Self::sha256_hex(format!("{}{}", b64_payload, SECRET_KEY).as_bytes());
        if signature != expected_sig {
            return Err("Invalid JettraJWT token signature".to_string());
        }

        let payload_bytes = Self::base64url_decode(b64_payload)?;
        let payload_str = String::from_utf8(payload_bytes).map_err(|e| e.to_string())?;
        let fields: Vec<&str> = payload_str.split('|').collect();
        if fields.len() != 3 {
            return Err("Invalid JettraJWT payload".to_string());
        }
        let exp = fields[2].parse::<u64>().map_err(|e| e.to_string())?;
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        if now > exp {
            return Err("JettraJWT token expired".to_string());
        }

        Ok(Claims {
            username: fields[0].to_string(),
            role: fields[1].to_string(),
            expiration: exp,
        })
    }

    pub fn create_user(
        &self,
        executing_token: &str,
        new_username: &str,
        new_password: &str,
        role: &str,
    ) -> Result<(), String> {
        let claims = self.validate_token(executing_token)?;
        if !claims.role.eq_ignore_ascii_case(ROLE_SUPER_ADMIN)
            && !claims.role.eq_ignore_ascii_case(ROLE_DB_ADMIN)
        {
            return Err("Insufficient permissions to create user".to_string());
        }
        let mut guard = self.users.write().unwrap();
        if guard.contains_key(new_username) {
            return Err(format!("User already exists: {}", new_username));
        }
        guard.insert(
            new_username.to_string(),
            UserAccount {
                username: new_username.to_string(),
                password_hash: Self::sha256_hex(new_password.as_bytes()),
                role: role.to_uppercase(),
                database_roles: HashMap::new(),
                immutable: false,
            },
        );
        Ok(())
    }

    pub fn drop_user(&self, executing_token: &str, target_username: &str) -> Result<(), String> {
        let claims = self.validate_token(executing_token)?;
        if !claims.role.eq_ignore_ascii_case(ROLE_SUPER_ADMIN) {
            return Err("Only SUPER_ADMIN can drop users".to_string());
        }
        if target_username.eq_ignore_ascii_case(DEFAULT_ADMIN_USER) {
            return Err("Security violation: Superuser 'admin' cannot be dropped".to_string());
        }
        let mut guard = self.users.write().unwrap();
        if guard.remove(target_username).is_none() {
            return Err(format!("User not found: {}", target_username));
        }
        Ok(())
    }

    pub fn grant_database_role(
        &self,
        executing_token: &str,
        target_username: &str,
        database_name: &str,
        db_role: &str,
    ) -> Result<(), String> {
        let claims = self.validate_token(executing_token)?;
        if !claims.role.eq_ignore_ascii_case(ROLE_SUPER_ADMIN)
            && !claims.role.eq_ignore_ascii_case(ROLE_DB_ADMIN)
        {
            return Err("Insufficient permissions to grant database roles".to_string());
        }
        let mut guard = self.users.write().unwrap();
        let user = guard
            .get_mut(target_username)
            .ok_or_else(|| format!("User not found: {}", target_username))?;
        user.database_roles
            .insert(database_name.to_string(), db_role.to_uppercase());
        Ok(())
    }

    pub fn has_database_access(&self, username: &str, database_name: &str, action: &str) -> bool {
        if username.eq_ignore_ascii_case(DEFAULT_ADMIN_USER) {
            return true;
        }
        let guard = self.users.read().unwrap();
        let Some(user) = guard.get(username) else {
            return false;
        };
        if user.role.eq_ignore_ascii_case(ROLE_SUPER_ADMIN) {
            return true;
        }
        let assigned = user
            .database_roles
            .get(database_name)
            .or_else(|| user.database_roles.get("*"));
        let Some(role_str) = assigned else {
            return false;
        };
        let upper = role_str.to_uppercase();
        if upper.contains("OWNER") || upper.contains("ADMIN") {
            return true;
        }
        let act = action.to_uppercase();
        if act == "WRITE" || act == "INSERT" || act == "UPDATE" || act == "DELETE" {
            return upper.contains("WRITE");
        }
        true
    }

    pub fn list_users(&self) -> Vec<UserAccount> {
        self.users.read().unwrap().values().cloned().collect()
    }

    fn generate_token(user: &UserAccount) -> String {
        let exp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() + 86400)
            .unwrap_or(86400);
        let payload = format!("{}|{}|{}", user.username, user.role, exp);
        let b64 = Self::base64url_encode(payload.as_bytes());
        let sig = Self::sha256_hex(format!("{}{}", b64, SECRET_KEY).as_bytes());
        format!("JettraJWT.{}.{}", b64, sig)
    }

    /// Implementación pura en Rust de FIPS 180-4 SHA-256 (sin dependencias externas).
    pub fn sha256_hex(data: &[u8]) -> String {
        const K: [u32; 64] = [
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ];
        let mut h: [u32; 8] = [
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
        ];

        let bit_len = (data.len() as u64) * 8;
        let mut msg = data.to_vec();
        msg.push(0x80);
        while (msg.len() % 64) != 56 {
            msg.push(0x00);
        }
        msg.extend_from_slice(&bit_len.to_be_bytes());

        for chunk in msg.chunks_exact(64) {
            let mut w = [0u32; 64];
            for i in 0..16 {
                w[i] = u32::from_be_bytes(chunk[i * 4..i * 4 + 4].try_into().unwrap());
            }
            for i in 16..64 {
                let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
                let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
                w[i] = w[i - 16]
                    .wrapping_add(s0)
                    .wrapping_add(w[i - 7])
                    .wrapping_add(s1);
            }

            let mut a = h[0];
            let mut b = h[1];
            let mut c = h[2];
            let mut d = h[3];
            let mut e = h[4];
            let mut f = h[5];
            let mut g = h[6];
            let mut hh = h[7];

            for i in 0..64 {
                let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
                let ch = (e & f) ^ ((!e) & g);
                let temp1 = hh
                    .wrapping_add(s1)
                    .wrapping_add(ch)
                    .wrapping_add(K[i])
                    .wrapping_add(w[i]);
                let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
                let maj = (a & b) ^ (a & c) ^ (b & c);
                let temp2 = s0.wrapping_add(maj);

                hh = g;
                g = f;
                f = e;
                e = d.wrapping_add(temp1);
                d = c;
                c = b;
                b = a;
                a = temp1.wrapping_add(temp2);
            }

            h[0] = h[0].wrapping_add(a);
            h[1] = h[1].wrapping_add(b);
            h[2] = h[2].wrapping_add(c);
            h[3] = h[3].wrapping_add(d);
            h[4] = h[4].wrapping_add(e);
            h[5] = h[5].wrapping_add(f);
            h[6] = h[6].wrapping_add(g);
            h[7] = h[7].wrapping_add(hh);
        }

        let mut hex = String::with_capacity(64);
        for word in h {
            hex.push_str(&format!("{:08x}", word));
        }
        hex
    }

    pub fn base64url_encode(input: &[u8]) -> String {
        const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        let mut out = String::new();
        let mut i = 0;
        while i < input.len() {
            let b0 = input[i] as u32;
            let b1 = if i + 1 < input.len() { input[i + 1] as u32 } else { 0 };
            let b2 = if i + 2 < input.len() { input[i + 2] as u32 } else { 0 };
            let triple = (b0 << 16) | (b1 << 8) | b2;

            out.push(TABLE[((triple >> 18) & 0x3F) as usize] as char);
            out.push(TABLE[((triple >> 12) & 0x3F) as usize] as char);
            if i + 1 < input.len() {
                out.push(TABLE[((triple >> 6) & 0x3F) as usize] as char);
            }
            if i + 2 < input.len() {
                out.push(TABLE[(triple & 0x3F) as usize] as char);
            }
            i += 3;
        }
        out
    }

    pub fn base64url_decode(input: &str) -> Result<Vec<u8>, String> {
        fn val(c: u8) -> Result<u32, String> {
            match c {
                b'A'..=b'Z' => Ok((c - b'A') as u32),
                b'a'..=b'z' => Ok((c - b'a' + 26) as u32),
                b'0'..=b'9' => Ok((c - b'0' + 52) as u32),
                b'-' | b'+' => Ok(62),
                b'_' | b'/' => Ok(63),
                _ => Err(format!("Invalid base64url char: {}", c as char)),
            }
        }
        let bytes: Vec<u8> = input.bytes().filter(|&b| b != b'=').collect();
        let mut out = Vec::new();
        let mut i = 0;
        while i < bytes.len() {
            let rem = bytes.len() - i;
            if rem < 2 {
                break;
            }
            let v0 = val(bytes[i])?;
            let v1 = val(bytes[i + 1])?;
            let v2 = if rem > 2 { val(bytes[i + 2])? } else { 0 };
            let v3 = if rem > 3 { val(bytes[i + 3])? } else { 0 };
            let triple = (v0 << 18) | (v1 << 12) | (v2 << 6) | v3;
            out.push(((triple >> 16) & 0xFF) as u8);
            if rem > 2 {
                out.push(((triple >> 8) & 0xFF) as u8);
            }
            if rem > 3 {
                out.push((triple & 0xFF) as u8);
            }
            i += 4;
        }
        Ok(out)
    }
}

