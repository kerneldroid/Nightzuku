use curve25519_dalek::constants::ED25519_BASEPOINT_POINT;
use curve25519_dalek::edwards::{CompressedEdwardsY, EdwardsPoint};
use curve25519_dalek::scalar::Scalar;
use curve25519_dalek::traits::Identity;
use sha2::{Digest, Sha512};
use subtle::{Choice, ConditionallySelectable};
use zeroize::Zeroizing;

use crate::PairingError;

pub(crate) const MESSAGE_SIZE: usize = 32;

const CLIENT_NAME: &[u8] = b"adb pair client\0";
const SERVER_NAME: &[u8] = b"adb pair server\0";

const SPAKE_M: [u8; 32] = [
    0x5a, 0xda, 0x7e, 0x4b, 0xf6, 0xdd, 0xd9, 0xad, 0xb6, 0x62, 0x6d, 0x32, 0x13, 0x1c, 0x6b, 0x5c,
    0x51, 0xa1, 0xe3, 0x47, 0xa3, 0x47, 0x8f, 0x53, 0xcf, 0xcf, 0x44, 0x1b, 0x88, 0xee, 0xd1, 0x2e,
];
const SPAKE_N: [u8; 32] = [
    0x10, 0xe3, 0xdf, 0x0a, 0xe3, 0x7d, 0x8e, 0x7a, 0x99, 0xb5, 0xfe, 0x74, 0xb4, 0x46, 0x72, 0x10,
    0x3d, 0xbd, 0xdc, 0xbd, 0x06, 0xaf, 0x68, 0x0d, 0x71, 0x32, 0x9a, 0x11, 0x69, 0x3b, 0xc7, 0x78,
];
const GROUP_ORDER: [u8; 32] = [
    0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10,
];

#[derive(Clone, Copy)]
pub(crate) enum SpakeRole {
    Client,
    Server,
}

pub(crate) struct Spake2State {
    role: SpakeRole,
    private_scalar: Zeroizing<[u8; 32]>,
    password_scalar: Zeroizing<[u8; 32]>,
    password_hash: Zeroizing<[u8; 64]>,
    our_message: [u8; MESSAGE_SIZE],
}

impl Spake2State {
    pub(crate) fn start(
        role: SpakeRole,
        password: &[u8],
        random: [u8; 64],
    ) -> Result<(Self, [u8; MESSAGE_SIZE]), PairingError> {
        if password.is_empty() {
            return Err(PairingError::Protocol(
                "SPAKE2 password cannot be empty".to_owned(),
            ));
        }

        let mut private_scalar = Scalar::from_bytes_mod_order_wide(&random).to_bytes();
        shift_left_three(&mut private_scalar);

        let password_hash: [u8; 64] = Sha512::digest(password).into();
        let mut password_scalar = Scalar::from_bytes_mod_order_wide(&password_hash).to_bytes();
        clear_cofactor_bits(&mut password_scalar);

        let mask = decode_point(match role {
            SpakeRole::Client => SPAKE_M,
            SpakeRole::Server => SPAKE_N,
        })?;
        let public = multiply_raw(&ED25519_BASEPOINT_POINT, &private_scalar);
        let masked = public + multiply_raw(&mask, &password_scalar);
        let our_message = masked.compress().to_bytes();

        let state = Self {
            role,
            private_scalar: Zeroizing::new(private_scalar),
            password_scalar: Zeroizing::new(password_scalar),
            password_hash: Zeroizing::new(password_hash),
            our_message,
        };
        Ok((state, our_message))
    }

    pub(crate) fn finish(self, peer_message: &[u8]) -> Result<Zeroizing<[u8; 64]>, PairingError> {
        let peer_encoded: [u8; MESSAGE_SIZE] = peer_message.try_into().map_err(|_| {
            PairingError::Protocol(format!(
                "SPAKE2 message must be {MESSAGE_SIZE} bytes, received {}",
                peer_message.len()
            ))
        })?;
        let peer_masked = decode_point(peer_encoded)?;
        let peer_mask = decode_point(match self.role {
            SpakeRole::Client => SPAKE_N,
            SpakeRole::Server => SPAKE_M,
        })?;
        let peer_public = peer_masked - multiply_raw(&peer_mask, &self.password_scalar);
        let shared = multiply_raw(&peer_public, &self.private_scalar)
            .compress()
            .to_bytes();

        let (client_message, server_message) = match self.role {
            SpakeRole::Client => (self.our_message.as_slice(), peer_message),
            SpakeRole::Server => (peer_message, self.our_message.as_slice()),
        };
        let mut transcript = Sha512::new();
        update_length_prefixed(&mut transcript, CLIENT_NAME);
        update_length_prefixed(&mut transcript, SERVER_NAME);
        update_length_prefixed(&mut transcript, client_message);
        update_length_prefixed(&mut transcript, server_message);
        update_length_prefixed(&mut transcript, &shared);
        update_length_prefixed(&mut transcript, &*self.password_hash);
        Ok(Zeroizing::new(transcript.finalize().into()))
    }
}

fn decode_point(encoded: [u8; 32]) -> Result<EdwardsPoint, PairingError> {
    CompressedEdwardsY(encoded)
        .decompress()
        .ok_or_else(|| PairingError::Protocol("SPAKE2 point is not on Ed25519".to_owned()))
}

fn multiply_raw(point: &EdwardsPoint, scalar: &[u8; 32]) -> EdwardsPoint {
    let mut result = EdwardsPoint::identity();
    let mut multiple = *point;
    for byte in scalar {
        for bit in 0..8 {
            let candidate = result + multiple;
            let selected = Choice::from((byte >> bit) & 1);
            result = EdwardsPoint::conditional_select(&result, &candidate, selected);
            multiple = multiple + multiple;
        }
    }
    result
}

fn shift_left_three(value: &mut [u8; 32]) {
    let mut carry = 0_u8;
    for byte in value {
        let next_carry = *byte >> 5;
        *byte = (*byte << 3) | carry;
        carry = next_carry;
    }
}

fn clear_cofactor_bits(value: &mut [u8; 32]) {
    let mut order = GROUP_ORDER;
    for bit in 0..3 {
        let sum = add_le(value, &order);
        let choice = Choice::from((value[0] >> bit) & 1);
        for (destination, candidate) in value.iter_mut().zip(sum) {
            *destination = u8::conditional_select(destination, &candidate, choice);
        }
        order = add_le(&order, &order);
    }
    debug_assert_eq!(value[0] & 7, 0);
}

fn add_le(left: &[u8; 32], right: &[u8; 32]) -> [u8; 32] {
    let mut output = [0_u8; 32];
    let mut carry = 0_u16;
    for index in 0..32 {
        let sum = u16::from(left[index]) + u16::from(right[index]) + carry;
        output[index] = sum.to_le_bytes()[0];
        carry = sum >> 8;
    }
    output
}

fn update_length_prefixed(digest: &mut Sha512, value: &[u8]) {
    digest.update(u64::try_from(value.len()).unwrap_or(u64::MAX).to_le_bytes());
    digest.update(value);
}
