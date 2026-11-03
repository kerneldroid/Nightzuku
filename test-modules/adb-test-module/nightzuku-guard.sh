#!/system/bin/sh
# ==============================================================================
# Nightzuku Genuine Host Guard (`nightzuku-guard.sh`)
# ==============================================================================
# Drop-in anti-fork verification header for Nightzuku ADB Modules.
#
# Why this exists:
# Unofficial forks (such as "Shizuku Next" or stripped clones) often copy the
# Nightzuku ADB Modules UI without porting the native dual-wire runtime
# (`kerneldroid.nightzuku.INightService`), module environment contract, or
# execution policies, causing modules to break and generating false bug reports
# ("Not working on Shizuku Next") for module authors.
#
# Usage in `action.sh` or `service.sh`:
#   . "${MODDIR:-.}/nightzuku-guard.sh"
#   require_nightzuku_host
#
# Optional: Pin your module to an expected APK signing certificate SHA-256:
#   NIGHTZUKU_EXPECTED_SHA256="your_expected_sha256_lowercase_hex"
#   require_nightzuku_host "$NIGHTZUKU_EXPECTED_SHA256"
# ==============================================================================

NIGHTZUKU_REQUIRED_PKG="kerneldroid.nightzuku"
NIGHTZUKU_REQUIRED_DESCRIPTOR="kerneldroid.nightzuku.INightService"
NIGHTZUKU_REQUIRED_AUTHORITY="kerneldroid.nightzuku.nightzuku"
NIGHTZUKU_ATTESTATION_PREFIX="nightzuku-host-attestation-v2:"

_nightzuku_abort() {
    echo "================================================================" >&2
    echo "[!] UNSUPPORTED HOST ENVIRONMENT DETECTED" >&2
    echo "----------------------------------------------------------------" >&2
    echo "Reason : $1" >&2
    echo "Host   : ${NIGHTZUKU_HOST_PKG:-unknown} (MODDIR=${MODDIR:-unset})" >&2
    echo "" >&2
    echo "This module is built exclusively for genuine Nightzuku" >&2
    echo "(package: kerneldroid.nightzuku)." >&2
    echo "Unofficial forks (such as Shizuku Next) have an incomplete/broken" >&2
    echo "module runner and are NOT supported. Do not report bugs to the" >&2
    echo "module developer when using third-party forks." >&2
    echo "================================================================" >&2
    exit 78
}

_nightzuku_sha256_hex() {
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "$1" | sha256sum 2>/dev/null | awk '{print $1}' | tr 'A-F' 'a-f'
    elif command -v openssl >/dev/null 2>&1; then
        printf '%s' "$1" | openssl dgst -sha256 2>/dev/null | awk '{print $NF}' | tr 'A-F' 'a-f'
    elif command -v toybox >/dev/null 2>&1; then
        printf '%s' "$1" | toybox sha256sum 2>/dev/null | awk '{print $1}' | tr 'A-F' 'a-f'
    else
        echo ""
    fi
}

_nightzuku_hmac_sha256_hex() {
    _hmac_key="$1"
    _hmac_msg="$2"
    if command -v openssl >/dev/null 2>&1; then
        printf '%s' "$_hmac_msg" | openssl dgst -sha256 -hmac "$_hmac_key" 2>/dev/null | awk '{print $NF}' | tr 'A-F' 'a-f'
    else
        echo ""
    fi
}

require_nightzuku_host() {
    _expected_sha256="$1"
    _min_api="${2:-2}"

    # 1. Load generated attestation file if present in $MODDIR
    if [ -n "$MODDIR" ] && [ -f "$MODDIR/.nightzuku_env" ]; then
        _file_token=$(grep '^NIGHTZUKU_SESSION_TOKEN=' "$MODDIR/.nightzuku_env" 2>/dev/null | cut -d= -f2-)
        _file_digest=$(grep '^NIGHTZUKU_ATTESTATION_SHA256=' "$MODDIR/.nightzuku_env" 2>/dev/null | cut -d= -f2-)
        _file_mod_id=$(grep '^NIGHTZUKU_MODULE_ID=' "$MODDIR/.nightzuku_env" 2>/dev/null | cut -d= -f2-)
        if [ -n "$_file_token" ] && [ -z "$NIGHTZUKU_SESSION_TOKEN" ]; then
            _nightzuku_abort "Runtime environment stripped by third-party fork (missing NIGHTZUKU_SESSION_TOKEN)."
        fi
        if [ "$_file_token" != "$NIGHTZUKU_SESSION_TOKEN" ]; then
            _nightzuku_abort "Session token mismatch against attestation file."
        fi
    else
        _nightzuku_abort "Missing Nightzuku host attestation file (.nightzuku_env)."
    fi

    # 2. Verify canonical module path ownership & runtime environment contract
    case "$MODDIR" in
        *"/$NIGHTZUKU_REQUIRED_PKG/"*) ;;
        *) _nightzuku_abort "Module is not running inside $NIGHTZUKU_REQUIRED_PKG storage ($MODDIR)." ;;
    esac

    if [ "$NIGHTZUKU_HOST" != "1" ] || [ "$NIGHTZUKU_HOST_PKG" != "$NIGHTZUKU_REQUIRED_PKG" ]; then
        _nightzuku_abort "Invalid NIGHTZUKU_HOST_PKG ($NIGHTZUKU_HOST_PKG != $NIGHTZUKU_REQUIRED_PKG)."
    fi

    if [ "$NIGHTZUKU_BINDER_DESCRIPTOR" != "$NIGHTZUKU_REQUIRED_DESCRIPTOR" ]; then
        _nightzuku_abort "Missing native Nightzuku dual-wire descriptor ($NIGHTZUKU_BINDER_DESCRIPTOR)."
    fi

    if [ -z "$NIGHTZUKU_SESSION_TOKEN" ] || [ "${#NIGHTZUKU_SESSION_TOKEN}" -ne 64 ]; then
        _nightzuku_abort "Missing or invalid HMAC-SHA256 session attestation token."
    fi

    # 3. Check minimum Nightzuku Modules API version
    if [ -n "$NIGHTZUKU_MODULES_API" ] && [ "$NIGHTZUKU_MODULES_API" -lt "$_min_api" ] 2>/dev/null; then
        _nightzuku_abort "Nightzuku Modules API v$_min_api required (current: v$NIGHTZUKU_MODULES_API)."
    fi

    # 4. Cryptographically verify runtime attestation token & digest against signer & payload
    _norm_signer=$(printf '%s' "$NIGHTZUKU_SIGNER_SHA256" | tr 'A-F' 'a-f' | tr -d ': ')
    _mod_id="${SHIZUKU_MODULE_ID:-$_file_mod_id}"
    _env_payload="$NIGHTZUKU_REQUIRED_PKG:${NIGHTZUKU_MODULES_API:-2}:$NIGHTZUKU_REQUIRED_DESCRIPTOR:$_norm_signer:$_mod_id:runtime-env"

    _calc_digest=$(_nightzuku_sha256_hex "${NIGHTZUKU_ATTESTATION_PREFIX}${_norm_signer}:${_env_payload}")
    if [ -n "$_calc_digest" ] && [ -n "${NIGHTZUKU_ATTESTATION_SHA256:-$_file_digest}" ]; then
        _actual_digest=$(printf '%s' "${NIGHTZUKU_ATTESTATION_SHA256:-$_file_digest}" | tr 'A-F' 'a-f')
        if [ "$_calc_digest" != "$_actual_digest" ]; then
            _nightzuku_abort "Cryptographic SHA-256 attestation digest verification failed."
        fi
    fi

    _calc_hmac=$(_nightzuku_hmac_sha256_hex "${NIGHTZUKU_ATTESTATION_PREFIX}${_norm_signer}" "$_env_payload")
    if [ -n "$_calc_hmac" ]; then
        _actual_hmac=$(printf '%s' "$NIGHTZUKU_SESSION_TOKEN" | tr 'A-F' 'a-f')
        if [ "$_calc_hmac" != "$_actual_hmac" ]; then
            _nightzuku_abort "Cryptographic HMAC-SHA256 session token verification failed."
        fi
    fi

    # 5. Verify live package installation in Android PackageManager
    if command -v pm >/dev/null 2>&1; then
        _pm_path=$(pm path "$NIGHTZUKU_REQUIRED_PKG" 2>/dev/null | head -n 1)
        if [ -z "$_pm_path" ]; then
            _nightzuku_abort "Package $NIGHTZUKU_REQUIRED_PKG is not installed on this device."
        fi
    fi

    # 6. Live challenge-response IPC verification against kerneldroid.nightzuku.nightzuku provider
    if command -v content >/dev/null 2>&1; then
        _nonce="guard-$$-$(date +%s 2>/dev/null || echo 1)"
        _ipc_out=$(content call --uri "content://$NIGHTZUKU_REQUIRED_AUTHORITY" --method verifyNightzukuHost --arg "$_nonce" --extra "moduleId:s:$_mod_id" 2>/dev/null || true)
        case "$_ipc_out" in
            *"genuine=true"*|*"binderDescriptor=$NIGHTZUKU_REQUIRED_DESCRIPTOR"*)
                _ipc_payload="$NIGHTZUKU_REQUIRED_PKG:${NIGHTZUKU_MODULES_API:-2}:$NIGHTZUKU_REQUIRED_DESCRIPTOR:$_norm_signer:$_mod_id:$_nonce"
                _expected_ipc_digest=$(_nightzuku_sha256_hex "${NIGHTZUKU_ATTESTATION_PREFIX}${_norm_signer}:${_ipc_payload}")
                if [ -n "$_expected_ipc_digest" ]; then
                    case "$_ipc_out" in
                        *"attestationSha256=$_expected_ipc_digest"*) ;;
                        *"attestationSha256="*)
                            _nightzuku_abort "Live IPC challenge cryptographic digest mismatch."
                            ;;
                    esac
                fi
                ;;
            "")
                # Restricted SELinux context blocked `content call` from shell; verified via digest/HMAC above
                ;;
            *)
                _nightzuku_abort "Live IPC verification against $NIGHTZUKU_REQUIRED_AUTHORITY failed."
                ;;
        esac
    fi

    # 7. Optional cryptographic APK signer SHA-256 pinning
    if [ -n "$_expected_sha256" ]; then
        _norm_expected=$(printf '%s' "$_expected_sha256" | tr 'A-F' 'a-f' | tr -d ': ')
        if [ "$_norm_signer" != "$_norm_expected" ]; then
            _nightzuku_abort "APK signature mismatch (expected $_norm_expected, got ${_norm_signer:-none})."
        fi
    fi

    return 0
}
