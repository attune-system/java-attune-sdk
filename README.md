# attune — Java SDK for Attune Actions & Sensors

A lightweight Java library providing boilerplate for writing [Attune](https://github.com/attune-system/attune) actions and sensors.

## Installation

### Maven

```xml
<dependency>
    <groupId>io.attune</groupId>
    <artifactId>attune-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

For sensor MQ rule lifecycle support, also add:

```xml
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
    <version>5.21.0</version>
</dependency>
```

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

## Writing Sensors

Sensors are long-running processes that emit events. The SDK provides rule
lifecycle management, signal handling, and MQ integration out of the box.

The sensor context is a singleton, accessible anywhere:

```java
import io.attune.Attune;
import io.attune.SensorContext;

SensorContext ctx = Attune.sensorContext();
System.out.println(ctx.sensorRef());
System.out.println(ctx.apiUrl());
System.out.println(ctx.config()); // ATTUNE_SENSOR_CONFIG_* vars
```

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

All sensor classes support rule lifecycle hooks:

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
| `ATTUNE_API_TOKEN` | Sensor-scoped API token |
| `ATTUNE_MQ_URL` | RabbitMQ connection URL |
| `ATTUNE_MQ_EXCHANGE` | RabbitMQ exchange name |
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
