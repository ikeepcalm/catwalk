# CatWalk Hub-Backend Communication Testing Guide

This guide explains how to test the hub-backend communication system in CatWalk.

## Prerequisites

1. **Database Setup**: Create a MariaDB/MySQL database named `catwalk_network`
2. **Build the Plugin**: Run `./gradlew shadowJar` to build the plugin
3. **Two Minecraft Servers**: One for hub, one for backend

## Database Setup

```sql
CREATE DATABASE catwalk_network;
CREATE USER 'catwalk'@'localhost' IDENTIFIED BY 'your_secure_password';
GRANT ALL PRIVILEGES ON catwalk_network.* TO 'catwalk'@'localhost';
FLUSH PRIVILEGES;
```

## Server Setup

### Hub Server Setup

1. Copy the built plugin to `plugins/` directory
2. Start the server once to generate default config
3. Replace `plugins/CatWalk/config.yml` with the hub configuration:
   - `hub.enabled: true`
   - `hub.server-id: "hub"`
   - `port: 4567`
4. Restart the server

### Backend Server Setup

1. Copy the built plugin to `plugins/` directory  
2. Start the server once to generate default config
3. Replace `plugins/CatWalk/config.yml` with the backend configuration:
   - `hub.enabled: false`
   - `hub.server-id: "backend-01"`
   - `port: 4568`
4. Restart the server

## Testing the Communication Flow

### 1. Verify Server Registration

Check that both servers are registered in the database:

```sql
SELECT * FROM servers;
```

You should see both "hub" and "backend-01" servers with status "online".

### 2. Verify Addon Registration

Check that built-in addons are registered:

```sql
SELECT * FROM server_addons;
```

You should see "catwalk-stats" addon registered for both servers.

### 3. Test Hub Network Endpoints

Access the hub server's network management endpoints:

- `http://localhost:4567/v1/network/status` - Network status
- `http://localhost:4567/v1/network/servers` - List all servers
- `http://localhost:4567/v1/network/addons` - List all addons
- `http://localhost:4567/swagger` - Swagger UI with proxy routes

### 4. Test Proxy Communication

The hub should automatically register proxy routes for backend servers.

Try accessing backend endpoints through the hub:

- `http://localhost:4567/v1/servers/backend-01/v1/stats/summary` - Backend stats via hub proxy

### 5. Monitor Request Flow

Check the database tables to monitor the request flow:

```sql
-- Check pending requests
SELECT * FROM request_queue WHERE status = 'pending';

-- Check processed requests
SELECT * FROM request_queue WHERE status = 'completed' ORDER BY created_at DESC LIMIT 10;

-- Check responses
SELECT * FROM response_queue ORDER BY created_at DESC LIMIT 10;
```

## Expected Behavior

### Hub Server
1. Registers itself as a hub server
2. Starts NetworkGateway for proxy routing
3. Polls for backend server addons and creates proxy routes
4. Receives requests and queues them in the database
5. Polls for responses and returns them to clients

### Backend Server  
1. Registers itself as a backend server
2. Registers built-in addons (catwalk-stats) in the database
3. Starts RequestProcessor to poll for incoming requests
4. Executes requests locally and stores responses
5. Maintains heartbeat to stay "online"

### Communication Flow
1. Client sends request to hub: `GET /v1/servers/backend-01/v1/stats/summary`
2. Hub checks if backend-01 is online
3. Hub creates NetworkRequest and stores it in `request_queue`
4. Backend polls `request_queue`, finds the request
5. Backend executes `GET /v1/stats/summary` locally
6. Backend stores response in `response_queue`
7. Hub polls `response_queue`, finds the response
8. Hub returns the response to the client

## Troubleshooting

### Common Issues

1. **Servers not appearing as online**
   - Check database connectivity
   - Verify heartbeat task is running
   - Check server status in database

2. **Addons not registering**
   - Check if registerBuiltInAddons() is being called
   - Verify database permissions
   - Check logs for errors

3. **Proxy routes not working**
   - Ensure backend server is online
   - Check if hub gateway is initialized
   - Verify proxy route registration in logs

4. **Requests timing out**
   - Check request_queue for pending requests
   - Verify backend RequestProcessor is running
   - Check response_queue for responses

### Debug Endpoints

- `/v1/network/debug` - Basic network debug information
- `/v1/network/status` - Network status with server counts
- `/swagger` - API documentation with all routes

## Performance Considerations

- Hub polls for responses every 2 seconds
- Backend polls for requests every 2 seconds  
- Server heartbeat every 30 seconds
- Cache refresh every 2 minutes
- Requests timeout after 30 seconds by default