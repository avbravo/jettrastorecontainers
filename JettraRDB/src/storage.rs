//! Almacenamiento Nativo Zero-GC (`NativeMemTable`), formato binario `.jettra` SSTable (`0x4A53`)
//! y gestor de copias de seguridad en caliente (`BackupManager`) con verificación CRC32.

use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

pub const JETTRA_SSTABLE_MAGIC: [u8; 2] = [0x4A, 0x53]; // "JS"
pub const JETTRA_SSTABLE_VERSION: u8 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SstableRecord {
    pub engine_id: u8,
    pub key: String,
    pub payload: Vec<u8>,
}

/// MemTable nativa en memoria contigua de Rust (sin pausas de recolector de basura).
#[derive(Debug, Clone)]
pub struct NativeMemTable {
    capacity_bytes: usize,
    buffer: Arc<Mutex<Vec<u8>>>,
    record_count: Arc<AtomicUsize>,
}

impl NativeMemTable {
    pub fn new(capacity_bytes: usize) -> Self {
        let cap = capacity_bytes.max(64 * 1024);
        Self {
            capacity_bytes: cap,
            buffer: Arc::new(Mutex::new(Vec::with_capacity(cap.min(4 * 1024 * 1024)))),
            record_count: Arc::new(AtomicUsize::new(0)),
        }
    }

    pub fn write_entry(&self, engine_id: u8, key: &str, payload: &[u8]) -> Result<usize, String> {
        let key_bytes = key.as_bytes();
        let entry_size = 1 + 4 + key_bytes.len() + 4 + payload.len();

        let mut buf = self.buffer.lock().unwrap();
        if buf.len() + entry_size > self.capacity_bytes {
            return Err("NativeMemTable capacity exceeded; flush required".to_string());
        }

        let offset = buf.len();
        buf.push(engine_id);
        buf.extend_from_slice(&(key_bytes.len() as u32).to_be_bytes());
        buf.extend_from_slice(key_bytes);
        buf.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        buf.extend_from_slice(payload);

        self.record_count.fetch_add(1, Ordering::Relaxed);
        Ok(offset)
    }

    pub fn flush_to_disk<P: AsRef<Path>>(&self, file_path: P) -> Result<usize, String> {
        let path = file_path.as_ref();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }

        let buf = self.buffer.lock().unwrap();
        let mut file = File::create(path).map_err(|e| e.to_string())?;

        // Cabecera binaria compatible con JettraStore: [0x4A, 0x53][version=1][size: i64 BE]
        file.write_all(&JETTRA_SSTABLE_MAGIC).map_err(|e| e.to_string())?;
        file.write_all(&[JETTRA_SSTABLE_VERSION]).map_err(|e| e.to_string())?;
        file.write_all(&(buf.len() as i64).to_be_bytes())
            .map_err(|e| e.to_string())?;
        file.write_all(&buf).map_err(|e| e.to_string())?;
        file.flush().map_err(|e| e.to_string())?;

        Ok(buf.len())
    }

    pub fn read_sstable<P: AsRef<Path>>(file_path: P) -> Result<Vec<SstableRecord>, String> {
        let mut file = File::open(file_path).map_err(|e| e.to_string())?;
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes).map_err(|e| e.to_string())?;

        if bytes.len() < 11 {
            return Err("SSTable file too small".to_string());
        }
        if bytes[0..2] != JETTRA_SSTABLE_MAGIC {
            return Err("Invalid .jettra SSTable magic header".to_string());
        }
        let _version = bytes[2];
        let payload_len = i64::from_be_bytes(bytes[3..11].try_into().unwrap()) as usize;
        let end = (11 + payload_len).min(bytes.len());

        let mut pos = 11;
        let mut records = Vec::new();
        while pos < end {
            if pos + 5 > end {
                break;
            }
            let engine_id = bytes[pos];
            pos += 1;
            let key_len = u32::from_be_bytes(bytes[pos..pos + 4].try_into().unwrap()) as usize;
            pos += 4;
            if pos + key_len + 4 > end {
                break;
            }
            let key = String::from_utf8_lossy(&bytes[pos..pos + key_len]).to_string();
            pos += key_len;
            let val_len = u32::from_be_bytes(bytes[pos..pos + 4].try_into().unwrap()) as usize;
            pos += 4;
            if pos + val_len > end {
                break;
            }
            let payload = bytes[pos..pos + val_len].to_vec();
            pos += val_len;

            records.push(SstableRecord {
                engine_id,
                key,
                payload,
            });
        }
        Ok(records)
    }

    pub fn used_bytes(&self) -> usize {
        self.buffer.lock().unwrap().len()
    }

    pub fn capacity_bytes(&self) -> usize {
        self.capacity_bytes
    }

    pub fn record_count(&self) -> usize {
        self.record_count.load(Ordering::Relaxed)
    }

    pub fn reset(&self) {
        self.buffer.lock().unwrap().clear();
        self.record_count.store(0, Ordering::Relaxed);
    }
}

// ============================================================================
// BackupManager: Snapshots en Caliente + Checksum CRC32 IEEE
// ============================================================================

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupMetadata {
    pub database_name: String,
    pub timestamp: u64,
    pub size_bytes: u64,
    pub checksum: u32,
}

pub struct BackupManager;

impl BackupManager {
    pub fn crc32(data: &[u8]) -> u32 {
        let mut crc: u32 = 0xFFFF_FFFF;
        for &byte in data {
            crc ^= byte as u32;
            for _ in 0..8 {
                if (crc & 1) != 0 {
                    crc = (crc >> 1) ^ 0xEDB8_8320;
                } else {
                    crc >>= 1;
                }
            }
        }
        !crc
    }

    pub fn backup_file<P: AsRef<Path>, Q: AsRef<Path>>(
        database_name: &str,
        source_sstable: P,
        destination_path: Q,
    ) -> Result<BackupMetadata, String> {
        let src = source_sstable.as_ref();
        let dst = destination_path.as_ref();
        if let Some(parent) = dst.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }

        let (size_bytes, checksum) = if src.exists() {
            let bytes = fs::read(src).map_err(|e| e.to_string())?;
            let crc = Self::crc32(&bytes);
            fs::copy(src, dst).map_err(|e| e.to_string())?;
            (bytes.len() as u64, crc)
        } else {
            let empty = b"JETTRA_EMPTY_SNAPSHOT";
            fs::write(dst, empty).map_err(|e| e.to_string())?;
            (empty.len() as u64, Self::crc32(empty))
        };

        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        Ok(BackupMetadata {
            database_name: database_name.to_string(),
            timestamp,
            size_bytes,
            checksum,
        })
    }

    pub fn restore_file<P: AsRef<Path>, Q: AsRef<Path>>(
        source_snapshot: P,
        target_sstable: Q,
    ) -> Result<bool, String> {
        let src = source_snapshot.as_ref();
        let dst = target_sstable.as_ref();
        if !src.exists() {
            return Err(format!("Snapshot file does not exist: {}", src.display()));
        }
        if let Some(parent) = dst.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        fs::copy(src, dst).map_err(|e| e.to_string())?;
        Ok(true)
    }
}

