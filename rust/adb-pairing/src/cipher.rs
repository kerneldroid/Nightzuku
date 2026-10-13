use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes128Gcm, KeyInit, Nonce};
use hkdf::Hkdf;
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::PairingError;

const AES_INFO: &[u8] = b"adb pairing_auth aes-128-gcm key";
const KEY_SIZE: usize = 16;
const NONCE_SIZE: usize = 12;

pub(crate) struct PairingCipher {
    cipher: Aes128Gcm,
    encrypt_sequence: u64,
    decrypt_sequence: u64,
}

impl PairingCipher {
    pub(crate) fn new(key_material: &[u8]) -> Result<Self, PairingError> {
        let hkdf = Hkdf::<Sha256>::new(None, key_material);
        let mut key = Zeroizing::new([0_u8; KEY_SIZE]);
        hkdf.expand(AES_INFO, &mut *key).map_err(|_| {
            PairingError::Protocol("unable to derive pairing cipher key".to_owned())
        })?;
        Ok(Self {
            cipher: Aes128Gcm::new((&*key).into()),
            encrypt_sequence: 0,
            decrypt_sequence: 0,
        })
    }

    pub(crate) fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, PairingError> {
        let nonce = sequence_nonce(self.encrypt_sequence);
        let encrypted = self
            .cipher
            .encrypt(Nonce::from_slice(&nonce), Payload::from(plaintext))
            .map_err(|_| PairingError::Protocol("unable to encrypt".to_owned()))?;
        self.encrypt_sequence = self
            .encrypt_sequence
            .checked_add(1)
            .ok_or_else(|| PairingError::Protocol("pairing nonce exhausted".to_owned()))?;
        Ok(encrypted)
    }

    pub(crate) fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>, PairingError> {
        let nonce = sequence_nonce(self.decrypt_sequence);
        let plaintext = self
            .cipher
            .decrypt(Nonce::from_slice(&nonce), Payload::from(ciphertext))
            .map_err(|_| PairingError::Authentication)?;
        self.decrypt_sequence = self
            .decrypt_sequence
            .checked_add(1)
            .ok_or_else(|| PairingError::Protocol("pairing nonce exhausted".to_owned()))?;
        Ok(plaintext)
    }
}

fn sequence_nonce(sequence: u64) -> [u8; NONCE_SIZE] {
    let mut nonce = [0_u8; NONCE_SIZE];
    nonce[..8].copy_from_slice(&sequence.to_le_bytes());
    nonce
}
