# attune — Java SDK for Attune Actions & Sensors

A lightweight Java library providing boilerplate for writing [Attune](https://github.com/attune-system/attune) actions and sensors.

## Installation

### Maven

```xml
<dependency>
    <groupId>io.attune</groupId>
    <artifactId>attune-sdk</artifactId>
    <version>0.2.0</version>
</dependency>
```

Managed sensor lifecycle delivery uses the Java 17 built-in `java.net.http.WebSocket` client, so no extra transport dependency is required.

## Writing Actions

Actions receive parameters as JSON on stdin and output results as JSON on stdout.
This package handles all of that:

```java
import io.attune.Attune;
import java.util.Map;

public class MyAction {
    public static void main(String[] args) {
        Attune.runAction(params -> {
            String name = (String) params.get("name");
            int count = ((Number) params.getOrDefault("count", 1)).intValue();
            return Map.of("greeting", "Hello, " + name + "!".repeat(count));
        });
    }
}
```

### Typed Parameters and Results

Use records or POJOs for type-safe parameter deserialization and result serialization:

```java
import io.attune.Attune;

record MyParams(String name, int count) {}
record MyResult(String greeting) {}

public class MyAction {
    public static void main(String[] args) {
        Attune.runAction(MyParams.class, params -> {
            return new MyResult("Hello, " + params.name() + "!".repeat(params.count()));
        });
    }
}
```

Any Jackson-serializable class works — records, POJOs with getters, etc.

### Accessing Execution Context

The context is a singleton available anywhere:

```java
import io.attune.Attune;
import io.attune.ActionContext;
import io.attune.AttuneClient;

public class MyAction {
    public static void main(String[] args) {
        Attune.runAction(params -> {
            ActionContext ctx = Attune.context();
            if (ctx.hasApiToken()) {
                AttuneClient client = ctx.client();
                // Use the API with the execution-scoped token
            }
            return Map.of(
                "action", ctx.actionRef(),
                "exec_id", ctx.executionId()
            );
        });
    }
}
```

### Using the API Client

```java
import io.attune.AttuneClient;
import java.util.Map;

// Auto-reads ATTUNE_API_URL and ATTUNE_API_TOKEN from env
AttuneClient client = new AttuneClient();
Map<String, Object> data = client.get("/api/v1/artifacts", Map.of("execution", "42"));
client.post("/api/v1/artifacts/1/versions/file", Map.of("created_by", "my_action"));
```

The Keys API has a small typed wrapper. Creation takes a local ref and owner scope; Attune returns the server-generated canonical ref:

```java
import io.attune.AttuneClient;
import io.attune.CreateKeyRequest;
import io.attune.KeyResponse;
import io.attune.KeysApi;
import io.attune.UpdateKeyRequest;

AttuneClient client = new AttuneClient();
KeysApi keys = client.keys();

KeyResponse created = keys.create(CreateKeyRequest.pack(
    "api_token",
    "GitHub API token",
    "secret-value",
    "github"
).withEncryption(true));

System.out.println(created.ref());      // server-generated canonical ref
System.out.println(created.localRef()); // api_token

KeyResponse fetched = keys.get(created.ref());
keys.update(created.ref(), new UpdateKeyRequest(null, "Rotated token", "new-secret"));
keys.delete(created.ref());
```

`keys.list()` returns a `KeyPage` of redacted `KeySummary` values. Use the four-argument overload to filter by owner and set pagination.

Use `CreateKeyRequest.system`, `identity`, `pack`, `action`, or `sensor` to create a request with the matching owner selector. The constructor rejects missing, mismatched, or mixed owner selectors.

## Writing Sensors

Sensors are long-running processes that emit events. The SDK provides rule
lifecycle management, signal handling, and notifier WebSocket lifecycle delivery out of the box.

The sensor context is a singleton, accessible anywhere:

```java
import io.attune.Attune;
import io.attune.SensorContext;

SensorContext ctx = Attune.sensorContext();
System.out.println(ctx.sensorRef());
System.out.println(ctx.apiUrl());
System.out.println(ctx.config()); // ATTUNE_SENSOR_CONFIG_* vars
System.out.println(ctx.apiTokenExpiresAt().orElse("unknown"));
```

### Managed Sensor Token Rotation

For managed sensors, the SDK can read a runtime-rotated token state file on each API/WebSocket use:

- Set `ATTUNE_SENSOR_TOKEN_STATE_PATH` to a JSON file path.
- File shape: `{"token":"<jwt>","expires_at":"<iso-8601>"}`.
- `api_token` and `token_expires_at` are also accepted aliases.

When configured, event emission and notifier reconnects always use the latest token value read from that file.
If the file is unavailable and no fallback token exists, the SDK fails closed with a clear token-source error.
Cross-SDK runtime/platform expectations are documented in
[docs/managed-sensor-token-rotation-contract.md](docs/managed-sensor-token-rotation-contract.md).

### Polling Sensor (`PollingSensor`)

One scheduled task per active rule:

```java
import io.attune.*;
import java.util.Map;

public class TemperatureSensor extends PollingSensor {
    { interval = 5000; } // ms

    @Override
    public void poll(RuleState rule) {
        String device = (String) rule.triggerParams().getOrDefault("device", "/dev/temp0");
        double temp = readTemperature(device);
        if (temp > 100) {
            emit(Map.of("temperature", temp, "alert", true), EmitOptions.create().rule(rule));
        }
    }

    public static void main(String[] args) {
        Attune.runSensor(TemperatureSensor.class);
    }
}
```

#### Typed Payloads

Sensors can emit typed objects instead of maps using `emitTyped`:

```java
import io.attune.*;

record TempAlert(double temperature, boolean alert) {}

public class TemperatureSensor extends PollingSensor {
    { interval = 5000; }

    @Override
    public void poll(RuleState rule) {
        double temp = readTemperature();
        if (temp > 100) {
            emitTyped(new TempAlert(temp, true), EmitOptions.create().rule(rule));
        }
    }
}
```

### Async Polling Sensor (`AsyncPollingSensor`)

One thread per active rule (ideal for I/O-bound checks):

```java
import io.attune.*;
import java.util.Map;

public class ApiSensor extends AsyncPollingSensor {
    { interval = 10000; } // ms

    @Override
    public void poll(RuleState rule) throws Exception {
        String url = (String) rule.triggerParams().get("url");
        // Perform HTTP check...
        if (statusCode >= 500) {
            emit(Map.of("url", url, "status", statusCode), EmitOptions.create().rule(rule));
        }
    }

    public static void main(String[] args) {
        Attune.runSensor(ApiSensor.class);
    }
}
```

### Custom Event Loops (`Sensor` base class)

For non-polling sensors, override `run()`:

```java
import io.attune.*;
import java.util.Map;

public class FileTailSensor extends Sensor {
    @Override
    public void run() {
        String path = config().getOrDefault("watch_path", "/var/log/app.log");
        // Custom event loop
        while (!isShuttingDown()) {
            // Check for events...
            emit(Map.of("line", "something happened"));
            sleep(500);
        }
    }

    public static void main(String[] args) {
        Attune.runSensor(FileTailSensor.class);
    }
}
```

### Rule Lifecycle Hooks

All sensor classes support rule lifecycle hooks. Managed sensors bootstrap from `ATTUNE_SENSOR_TRIGGERS` and receive live updates from the notifier WebSocket using `ATTUNE_NOTIFIER_WS_URL` plus `ATTUNE_API_TOKEN`:

```java
public class StatefulSensor extends PollingSensor {
    @Override
    public void onRuleCreated(RuleState rule) {
        logger.info("Rule created: {}", rule.ruleRef());
    }

    @Override
    public void onRuleEnabled(RuleState rule) {
        // Previously disabled rule re-enabled
    }

    @Override
    public void onRuleDisabled(RuleState rule) {
        // Rule disabled — pause per-rule work
    }

    @Override
    public void onRuleDeleted(RuleState rule) {
        // Rule permanently removed — free resources
    }

    @Override
    public void onRuleUpdated(RuleState rule, Map<String, Object> oldParams) {
        logger.info("Rule updated: {} → {}", oldParams, rule.triggerParams());
    }
}
```

## Environment Variables

### Actions

| Variable | Description |
|----------|-------------|
| `ATTUNE_ACTION` | Action reference (e.g., `mypack.deploy`) |
| `ATTUNE_PACK_REF` | Pack reference |
| `ATTUNE_EXEC_ID` | Execution database ID |
| `ATTUNE_API_URL` | API base URL |
| `ATTUNE_API_TOKEN` | Execution-scoped API token (optional) |
| `ATTUNE_ARTIFACTS_DIR` | Shared artifact volume path |
| `ATTUNE_RULE` | Rule reference (if rule-triggered) |
| `ATTUNE_TRIGGER` | Trigger reference (if event-triggered) |

### Sensors

| Variable | Description |
|----------|-------------|
| `ATTUNE_SENSOR_REF` | Sensor reference |
| `ATTUNE_SENSOR_ID` | Sensor database ID |
| `ATTUNE_API_URL` | API base URL |
| `ATTUNE_API_TOKEN` | Sensor-scoped API token used for API calls and WebSocket auth |
| `ATTUNE_API_TOKEN_EXPIRES_AT` | Optional ISO-8601 expiry metadata for the fallback token |
| `ATTUNE_SENSOR_TOKEN_STATE_PATH` | Optional JSON file path for runtime-driven managed sensor token rotation |
| `ATTUNE_NOTIFIER_WS_URL` | Notifier WebSocket URL for managed sensor lifecycle updates |
| `ATTUNE_SENSOR_TRIGGERS` | JSON bootstrap payload of active managed rule instances |
| `ATTUNE_LOG_LEVEL` | Log verbosity |

## Development

```bash
cd packs.external/java-attune-sdk
mvn compile
mvn test
```

### Requirements

- Java 17+
- Maven 3.8+
