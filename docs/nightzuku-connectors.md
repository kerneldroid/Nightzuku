# Nightzuku Connectors API

Nightzuku Connectors is an experimental feature (available in **Lab Features**) that allows third-party apps, referred to as "Activators", to securely query the command required to start the Nightzuku server locally.

## Why Nightzuku Connectors?

Often, enthusiasts discover Local Privilege Escalation (LPE) exploits (e.g., Dirty Pipe, FOTA exploits, mktimer) that can be used to gain temporary elevated privileges without a PC or full root access. Nightzuku Connectors provides a standardized interface for these "Activators" (usually small 1MB APKs) to retrieve the internal Nightzuku startup command, apply their specific exploit, and bootstrap the Nightzuku server directly on the device.

## Prerequisites

For safety, this feature is **disabled by default**. 
To use it, the user must navigate to the Nightzuku Settings -> **Lab Features**, enable **Nightzuku Connectors**, and accept the safety warning.

## How to use

When Nightzuku Connectors is enabled, Nightzuku exposes a local, exported `ContentProvider` at the following URI:

```
content://kerneldroid.nightzuku.connector
```

### Querying the Provider

You can query this URI to retrieve a `Cursor` containing exactly one row and one column named `command`.

#### Android Java/Kotlin Example:

```kotlin
val uri = Uri.parse("content://kerneldroid.nightzuku.connector")
contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) {
        val commandIndex = cursor.getColumnIndex("command")
        if (commandIndex != -1) {
            val nightzukuCommand = cursor.getString(commandIndex)
            // Execute the command using your local exploit payload
            Log.d("Activator", "Got command: $nightzukuCommand")
        }
    }
}
```

#### Shell Script Example:

```bash
OUTPUT=$(content query --uri content://kerneldroid.nightzuku.connector)
if [[ $OUTPUT == *"command="* ]]; then
    CMD=$(echo "$OUTPUT" | grep -o 'command=.*' | cut -d= -f2-)
    # Run the command with elevated privileges
    eval "$CMD"
else
    echo "Nightzuku Connectors is not enabled or Nightzuku is not installed."
fi
```

### Return Values

- If **Nightzuku Connectors** is **enabled**, the provider will return the full shell command string required to start the internal Nightzuku server.
- If **Nightzuku Connectors** is **disabled** (or the user has not accepted the warning), the provider will return `null` or an empty result set, depending on the client's query mechanism.
