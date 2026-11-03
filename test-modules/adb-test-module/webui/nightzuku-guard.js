/**
 * Nightzuku Genuine Host Guard for Module WebUI (`nightzuku-guard.js`)
 *
 * Drop-in verification helper for Nightzuku ADB Module WebUIs.
 * Performs real RFC 2104 HMAC-SHA256 and SHA-256 cryptographic verification of
 * `window.Nightzuku.verifyHost(challenge)` and renders a clear unsupported-fork screen
 * when a module is opened inside an unofficial/broken fork (such as "Shizuku Next").
 *
 * Usage in `webui/index.html`:
 *   <script src="nightzuku-guard.js"></script>
 *   <script>
 *     NightzukuGuard.enforce({
 *       minApiVersion: 2,
 *       expectedSignerSha256: null // optional hex string
 *     });
 *   </script>
 */
(function (global) {
  'use strict';

  const REQUIRED_PKG = 'kerneldroid.nightzuku';
  const REQUIRED_DESCRIPTOR = 'kerneldroid.nightzuku.INightService';
  const ATTESTATION_KEY_PREFIX = 'nightzuku-host-attestation-v2:';

  const SHA256_K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];

  function utf8ToBytes(str) {
    if (typeof TextEncoder !== 'undefined') {
      return Array.from(new TextEncoder().encode(String(str)));
    }
    const utf8 = unescape(encodeURIComponent(String(str)));
    const out = new Array(utf8.length);
    for (let i = 0; i < utf8.length; i++) {
      out[i] = utf8.charCodeAt(i) & 0xff;
    }
    return out;
  }

  function rotr(n, x) {
    return (x >>> n) | (x << (32 - n));
  }

  function sha256Bytes(msgBytes) {
    const H = [
      0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    ];

    const l = msgBytes.length;
    const bitLenHi = Math.floor((l * 8) / 0x100000000);
    const bitLenLo = (l * 8) >>> 0;

    const padded = msgBytes.slice();
    padded.push(0x80);
    while ((padded.length % 64) !== 56) {
      padded.push(0);
    }
    padded.push(
      (bitLenHi >>> 24) & 0xff, (bitLenHi >>> 16) & 0xff, (bitLenHi >>> 8) & 0xff, bitLenHi & 0xff,
      (bitLenLo >>> 24) & 0xff, (bitLenLo >>> 16) & 0xff, (bitLenLo >>> 8) & 0xff, bitLenLo & 0xff
    );

    const W = new Array(64);
    for (let offset = 0; offset < padded.length; offset += 64) {
      for (let i = 0; i < 16; i++) {
        const j = offset + i * 4;
        W[i] = ((padded[j] << 24) | (padded[j + 1] << 16) | (padded[j + 2] << 8) | padded[j + 3]) >>> 0;
      }
      for (let i = 16; i < 64; i++) {
        const s0 = (rotr(7, W[i - 15]) ^ rotr(18, W[i - 15]) ^ (W[i - 15] >>> 3)) >>> 0;
        const s1 = (rotr(17, W[i - 2]) ^ rotr(19, W[i - 2]) ^ (W[i - 2] >>> 10)) >>> 0;
        W[i] = (W[i - 16] + s0 + W[i - 7] + s1) >>> 0;
      }

      let a = H[0], b = H[1], c = H[2], d = H[3];
      let e = H[4], f = H[5], g = H[6], h = H[7];

      for (let i = 0; i < 64; i++) {
        const S1 = (rotr(6, e) ^ rotr(11, e) ^ rotr(25, e)) >>> 0;
        const ch = ((e & f) ^ (~e & g)) >>> 0;
        const temp1 = (h + S1 + ch + SHA256_K[i] + W[i]) >>> 0;
        const S0 = (rotr(2, a) ^ rotr(13, a) ^ rotr(22, a)) >>> 0;
        const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
        const temp2 = (S0 + maj) >>> 0;

        h = g;
        g = f;
        f = e;
        e = (d + temp1) >>> 0;
        d = c;
        c = b;
        b = a;
        a = (temp1 + temp2) >>> 0;
      }

      H[0] = (H[0] + a) >>> 0;
      H[1] = (H[1] + b) >>> 0;
      H[2] = (H[2] + c) >>> 0;
      H[3] = (H[3] + d) >>> 0;
      H[4] = (H[4] + e) >>> 0;
      H[5] = (H[5] + f) >>> 0;
      H[6] = (H[6] + g) >>> 0;
      H[7] = (H[7] + h) >>> 0;
    }

    const out = new Array(32);
    for (let i = 0; i < 8; i++) {
      out[i * 4] = (H[i] >>> 24) & 0xff;
      out[i * 4 + 1] = (H[i] >>> 16) & 0xff;
      out[i * 4 + 2] = (H[i] >>> 8) & 0xff;
      out[i * 4 + 3] = H[i] & 0xff;
    }
    return out;
  }

  function bytesToHex(bytes) {
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
      const b = bytes[i] & 0xff;
      hex += (b < 16 ? '0' : '') + b.toString(16);
    }
    return hex;
  }

  function sha256Hex(str) {
    return bytesToHex(sha256Bytes(utf8ToBytes(str)));
  }

  function hmacSha256Hex(keyStr, messageStr) {
    let keyBytes = utf8ToBytes(keyStr);
    if (keyBytes.length > 64) {
      keyBytes = sha256Bytes(keyBytes);
    }
    while (keyBytes.length < 64) {
      keyBytes.push(0);
    }
    const oKeyPad = new Array(64);
    const iKeyPad = new Array(64);
    for (let i = 0; i < 64; i++) {
      oKeyPad[i] = keyBytes[i] ^ 0x5c;
      iKeyPad[i] = keyBytes[i] ^ 0x36;
    }
    const innerHash = sha256Bytes(iKeyPad.concat(utf8ToBytes(messageStr)));
    return bytesToHex(sha256Bytes(oKeyPad.concat(innerHash)));
  }

  function normalizeHex(str) {
    return String(str || '').trim().toLowerCase().replace(/[:\s]/g, '');
  }

  function verify(options) {
    const opts = options || {};
    const minApi = opts.minApiVersion || 2;
    const expectedSha = opts.expectedSignerSha256
      ? normalizeHex(opts.expectedSignerSha256)
      : null;

    const bridge = global.Nightzuku || global.Shizuku;
    if (!bridge || typeof bridge.getModuleInfo !== 'function') {
      return {
        ok: false,
        reason: 'Missing window.Nightzuku WebUI bridge.'
      };
    }

    if (typeof bridge.verifyHost !== 'function') {
      return {
        ok: false,
        reason: 'Host does not implement Nightzuku cryptographic host attestation (verifyHost).'
      };
    }

    try {
      const challenge = 'webui-' + Date.now() + '-' + Math.random().toString(36).slice(2);
      const rawAttest = bridge.verifyHost(challenge);
      const attest = JSON.parse(rawAttest);

      if (!attest || !attest.ok || attest.genuineNightzuku !== true) {
        return { ok: false, reason: 'Host failed genuineNightzuku attestation.' };
      }
      if (attest.hostPackage !== REQUIRED_PKG) {
        return {
          ok: false,
          reason: 'Invalid host package: ' + attest.hostPackage + ' (expected ' + REQUIRED_PKG + ').'
        };
      }
      if (attest.binderDescriptor !== REQUIRED_DESCRIPTOR) {
        return {
          ok: false,
          reason: 'Missing native Nightzuku dual-wire binder descriptor.'
        };
      }
      if ((attest.modulesApiVersion || 0) < minApi) {
        return {
          ok: false,
          reason: 'Nightzuku Modules API v' + minApi + '+ required (found v' + attest.modulesApiVersion + ').'
        };
      }
      if (attest.challenge !== challenge) {
        return {
          ok: false,
          reason: 'Attestation challenge nonce mismatch (replay or static stub detected).'
        };
      }

      const normSigner = normalizeHex(attest.signerSha256);
      const moduleId = String(attest.moduleId || '');
      const payload =
        attest.hostPackage + ':' +
        attest.modulesApiVersion + ':' +
        attest.binderDescriptor + ':' +
        normSigner + ':' +
        moduleId + ':' +
        challenge;

      const expectedToken = hmacSha256Hex(ATTESTATION_KEY_PREFIX + normSigner, payload);
      const actualToken = normalizeHex(attest.attestationToken);
      if (!actualToken || actualToken.length !== 64 || actualToken !== expectedToken) {
        return {
          ok: false,
          reason: 'Cryptographic HMAC-SHA256 attestation signature verification failed.'
        };
      }

      if (attest.attestationSha256) {
        const expectedDigest = sha256Hex(ATTESTATION_KEY_PREFIX + normSigner + ':' + payload);
        if (normalizeHex(attest.attestationSha256) !== expectedDigest) {
          return {
            ok: false,
            reason: 'Cryptographic SHA-256 attestation digest verification failed.'
          };
        }
      }

      if (expectedSha && normSigner !== expectedSha) {
        return {
          ok: false,
          reason: 'APK signing certificate mismatch.'
        };
      }

      return {
        ok: true,
        hostPackage: attest.hostPackage,
        hostVersion: attest.hostVersion,
        modulesApiVersion: attest.modulesApiVersion,
        signerSha256: attest.signerSha256,
        attestationToken: actualToken
      };
    } catch (e) {
      return {
        ok: false,
        reason: 'Attestation exception: ' + (e && e.message ? e.message : String(e))
      };
    }
  }

  function enforce(options) {
    const res = verify(options);
    if (res.ok) return res;

    const renderBlock = function () {
      if (!document.body) return;
      document.body.innerHTML =
        '<div style="font-family:system-ui,-apple-system,sans-serif;padding:24px;max-width:520px;margin:40px auto;background:#2b1114;color:#ffdad6;border-radius:20px;border:1px solid #93000a;">' +
        '<h2 style="margin-top:0;color:#ffb4ab;">Unsupported Host Environment</h2>' +
        '<p style="line-height:1.5;">This module is built exclusively for <strong>Nightzuku</strong> (<code>kerneldroid.nightzuku</code>).</p>' +
        '<p style="line-height:1.5;font-family:monospace;background:#1a0a0c;padding:12px;border-radius:10px;">' +
        String(res.reason).replace(/</g, '&lt;') +
        '</p>' +
        '<p style="line-height:1.5;font-size:13px;opacity:0.85;">Unofficial forks (such as Shizuku Next) have an incomplete module implementation and are not supported. Please install official Nightzuku instead of reporting bugs to the module author.</p>' +
        '</div>';
    };

    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', renderBlock);
    } else {
      renderBlock();
    }
    throw new Error('[NightzukuGuard] ' + res.reason);
  }

  global.NightzukuGuard = {
    verify: verify,
    enforce: enforce,
    hmacSha256Hex: hmacSha256Hex,
    sha256Hex: sha256Hex
  };
})(window);
