# CatWalk

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Paper](https://img.shields.io/badge/Paper-1.21.4-blue.svg)](https://papermc.io/)
[![Gradle](https://img.shields.io/badge/Gradle-8.x-green.svg)](https://gradle.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**CatWalk** is a sophisticated REST API plugin for Minecraft servers that provides network gateway capabilities, cross-server communication, and comprehensive server management through a unified API interface.

## 🌟 Features

### Network Gateway Architecture
- **Hub Gateway Mode**: Acts as a central proxy for multiple Minecraft servers
- **Backend Server Mode**: Individual server with local API endpoints
- **Database-driven Server Discovery**: Automatic server registration and heartbeat monitoring
- **Cross-server Request Routing**: Intelligent request forwarding and load balancing

### REST API & Documentation
- **Statistics API**: Player activity, server metrics, TPS monitoring
- **OpenAPI 3.0 Specification**: Auto-generated Swagger UI documentation
- **WebSocket Support**: Real-time console streaming
- **Custom Addon System**: Dynamic endpoint registration for third-party plugins

### Security & Performance
- **API Key Authentication**: Secure access control with whitelisted paths
- **TLS/SSL Support**: Custom keystore configuration
- **CORS Configuration**: Web application integration
- **Connection Pooling**: HikariCP for optimal database performance

## 🚀 Quick Start

### Prerequisites
- Java 21 or higher
- PaperMC 1.21.4+ server
- MariaDB/MySQL database
- Gradle 8.x (for building)

### Installation

1. **Download the latest release** or build from source:
   ```bash
   git clone https://github.com/ikeepcalm/catwalk
   cd catwalk
   ./gradlew shadowJar
   ```

2. **Install the plugin**:
   - Copy `build/libs/CatWalk-*.jar` to your server's `plugins/` directory
   - Start your server to generate the configuration

3. **Configure the database**:
   ```yaml
   # plugins/CatWalk/config.yml
   database:
     host: localhost
     port: 3306
     database: catwalk
     username: your_username
     password: your_password
   ```

4. **Start your server** - CatWalk will automatically:
   - Create database tables
   - Register your server in the network
   - Start the web server (default port: 4567)

## 🏗️ Architecture

### Dual Mode Operation

#### Hub Gateway Mode
```
Client Request → Hub Gateway → Database Lookup → Target Server → Response
```
- Centralized entry point for all API requests
- Automatic server discovery and routing
- Load balancing across multiple servers
- Cross-server communication coordination

#### Backend Server Mode  
```
Client Request → Local Server → Local Processing → Response
```
- Direct API access to individual servers
- Local endpoint registration
- Addon-specific functionality
- Real-time server metrics

### Database Schema

CatWalk uses a comprehensive database schema:

- **`servers`** - Network server registry with heartbeat monitoring
- **`server_addons`** - Addon registration and endpoint definitions  
- **`network_requests`** - Cross-server request queue and processing
- **`network_responses`** - Response data and metrics
- **`request_processors`** - Custom request handling logic

## 📡 API Reference

### Base URL
```
http://your-server:4567/
```

## 🔧 Development

### Building from Source

```bash
git clone https://github.com/ikeepcalm/catwalk
cd catwalk
./gradlew shadowJar
```

### Running Tests

```bash
./gradlew test
```

### Development Server

```bash
./gradlew runServer
```

This will start a test server in the `run/` directory with CatWalk pre-installed.

## 📈 Roadmap

- [ ] **Redis Integration** - Caching and session management
- [ ] **Metrics Dashboard** - Web-based monitoring interface
- [ ] **Plugin Marketplace** - Addon discovery and installation
- [ ] **GraphQL API** - Advanced query capabilities
- [ ] **Rate Limiting** - Request throttling and abuse prevention
- [ ] **Kubernetes Support** - Container orchestration integration

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Guidelines

- Follow Java code conventions
- Write comprehensive tests
- Update documentation for new features
- Ensure backward compatibility
- Use meaningful commit messages

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **PaperMC Team** - For the excellent Minecraft server platform
- **Javalin** - For the lightweight web framework
- **HikariCP** - For high-performance connection pooling
- **OpenAPI** - For API documentation standards
- **ServerTap** - For the base of the project, yet not maintained

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/your-org/catwalk/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-org/catwalk/discussions)
- **Documentation**: [Wiki](https://github.com/ikeepcalm/catwalk/wiki)

---

**CatWalk** - Bridging Minecraft servers through intelligent API networking.