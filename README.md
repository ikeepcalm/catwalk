# ServerTap

[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/servertap-io/servertap/build.yml?branch=main)](https://github.com/servertap-io/servertap/actions/workflows/build.yml)
![Bukkit Version](https://img.shields.io/badge/bukkit%20version-%3E%3D1.16-brightgreen)
![GitHub All Releases](https://img.shields.io/github/downloads/servertap-io/servertap/total?color=brightgreen)
[![Discord](https://img.shields.io/discord/919982507271802890?logo=discord&label=Discord&color=brightgreen)](https://discord.gg/nSWRYzBMfp)

ServerTap is a REST API for Bukkit, Spigot, and PaperMC Minecraft servers. It allows server admins to query and interact with their servers using simple REST semantics.

Download the latest release: [Releases](https://github.com/servertap-io/servertap/releases/latest)

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Commands](#commands)
- [Endpoints](#endpoints)
- [Configuration](#configuration)
  - [TLS](#tls)
  - [Authentication](#authentication)
  - [CORS](#cors)
  - [Webhooks](#webhooks)
  - [Websockets](#websockets)
- [Developer API](#developer-api)
- [Contributing](#contributing)
- [Community and Support](#community-and-support)

---

## Features

- REST API for managing players, worlds, and plugins
- Webhooks for server events like player join/quit
- Real-time server logs through Websockets
- Extendable with custom endpoints via the Developer API

---

## Installation

1. Download the latest `ServerTap` plugin JAR from the [Releases page](https://github.com/servertap-io/servertap/releases/latest).
2. Place the JAR in your server's `plugins/` directory.
3. Restart your server.

To test the API, use tools like `curl`, Postman, or any HTTP client.

---

## Commands

ServerTap supports the following commands:

- `/servertap reload`: Reloads the plugin configuration.
- `/servertap info`: Displays plugin information.

**Permission:** `servertap.admin`

---

## Endpoints

View the full API documentation at [http://your-server.net:4567/swagger](http://your-server.net:4567/swagger).

Example API usage:

```bash
curl http://localhost:4567/v1/server
```

Response:
```json
{
  "name": "Paper",
  "version": "git-Paper-89 (MC: 1.15.2)",
  "health": {
    "cpus": 4,
    "uptime": 744,
    "freeMemory": 1332389360
  }
}
```

---

## Configuration

### TLS

Enable encrypted communication by configuring TLS in `config.yml`:

```yaml
tls:
  enabled: true
  keystore: selfsigned.jks
  keystorePassword: change_me
```

Generate a keystore with:
```bash
keytool -genkey -keyalg RSA -alias servertap -keystore selfsigned.jks -validity 365 -keysize 2048
```

### Authentication

Add key-based authentication by updating `config.yml`:

```yaml
useKeyAuth: true
key: some-long-super-random-string
```

Include the `key` header in API requests.

### CORS

Limit cross-origin requests:

```yaml
corsOrigins:
  - https://mysite.com
```

---

## Webhooks

Trigger external actions on server events. Example configuration:

```yaml
webhooks:
  default:
    listener: "https://webhook.example.com"
    events:
    - PlayerJoin
    - PlayerQuit
```

Supported events:
- `PlayerJoin`
- `PlayerQuit`
- `PlayerDeath`
- `PlayerChat`
- `PlayerKick`

---

## Websockets

Stream server logs and send commands via Websockets:

```js
const ws = new WebSocket("ws://localhost:4567/v1/ws/console");
ws.onmessage = (event) => console.log(event.data);
```

### Authentication

Set the cookie `x-servertap-key` to authenticate Websocket connections.

---

## Developer API

Extend ServerTap by registering custom endpoints or Websockets:

1. Add ServerTap as a dependency via Jitpack:
   ```xml
   <repository>
     <id>jitpack.io</id>
     <url>https://jitpack.io</url>
   </repository>
   <dependency>
     <groupId>com.github.servertap-io</groupId>
     <artifactId>servertap</artifactId>
     <version>vX.X.X</version>
   </dependency>
   ```

2. Example usage:
   ```java
   ServerTapWebserverService webserver = getServer().getServicesManager().load(ServerTapWebserverService.class);
   webserver.get("/example", ctx -> ctx.result("Hello, ServerTap!"));
   ```

---

## Contributing

Contributions are welcome! To get started:

1. Clone the repository.
2. Use JDK 19 and Maven to build the project.
3. Submit pull requests with your changes.

---

## Community and Support

Join our [Discord server](https://discord.gg/nSWRYzBMfp) for help and discussions.

**Note:** Please use the support forum in Discord for technical questions.