# Nightzuku Connectors API

Experimental local IPC interface (`ShizukuConnectorProvider`) allowing third-party local privilege-escalation ("Activator") apps to query the shell command required to start `shizuku_server`.

## Security Gating

Disabled by default. The user must explicitly enable **Nightzuku Connectors** under **Settings -> Lab Features** and confirm the warning dialog before the provider returns data.

## ContentProvider Contract

| Property | Value |
| :--- | :--- |
| URI | `content://kerneldroid.nightzuku.connector` |
| Return type | `Cursor` (1 row, 1 column) when enabled; `null` or empty cursor when disabled |
| Column name | `command` (`String` — full shell command to bootstrap `shizuku_server`) |

## Client Examples

### Kotlin (Android)

```kotlin
val uri = Uri.parse("content://kerneldroid.nightzuku.connector")
contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) {
        val idx = cursor.getColumnIndex("command")
        if (idx != -1) {
            val command = cursor.getString(idx)
            // Execute command under elevated privileges
        }
    }
}
```

### Shell

```sh
OUTPUT=$(content query --uri content://kerneldroid.nightzuku.connector)
case "$OUTPUT" in
  *command=*)
    CMD=$(printf '%s
' "$OUTPUT" | sed -n 's/.*command=//p')
    eval "$CMD"
    ;;
  *)
    echo "Nightzuku Connectors disabled or Nightzuku not installed." >&2
    exit 1
    ;;
esac
```
