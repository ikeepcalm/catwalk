-- Track active servers in the network
CREATE TABLE IF NOT EXISTS servers (
                                       server_id VARCHAR(64) PRIMARY KEY,
    server_name VARCHAR(128) NOT NULL,
    server_type ENUM('hub', 'backend') NOT NULL,
    host VARCHAR(255),
    port INT,
    online_players INT DEFAULT 0,
    max_players INT DEFAULT 0,
    status ENUM('online', 'offline', 'maintenance') DEFAULT 'online',
    last_heartbeat TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    metadata JSON,
    INDEX idx_server_type (server_type),
    INDEX idx_status (status),
    INDEX idx_last_heartbeat (last_heartbeat)
    );

-- Track addons and their endpoints per server
CREATE TABLE IF NOT EXISTS server_addons (
                                             id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             server_id VARCHAR(64) NOT NULL,
    addon_name VARCHAR(128) NOT NULL,
    addon_version VARCHAR(32),
    enabled BOOLEAN DEFAULT TRUE,
    endpoints JSON NOT NULL,
    openapi_spec JSON,
    registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (server_id) REFERENCES servers(server_id) ON DELETE CASCADE,
    UNIQUE KEY unique_server_addon (server_id, addon_name),
    INDEX idx_addon_name (addon_name),
    INDEX idx_enabled (enabled)
    );

-- Queue for requests from hub to backend servers
CREATE TABLE IF NOT EXISTS request_queue (
                                             id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             request_id VARCHAR(64) UNIQUE NOT NULL,
    target_server_id VARCHAR(64) NOT NULL,
    endpoint_path VARCHAR(512) NOT NULL,
    http_method ENUM('GET', 'POST', 'PUT', 'DELETE', 'PATCH') NOT NULL,
    headers JSON,
    query_params JSON,
    body LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP NULL,
    status ENUM('pending', 'processing', 'completed', 'failed', 'timeout') DEFAULT 'pending',
    priority INT DEFAULT 0,
    timeout_seconds INT DEFAULT 30,
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    FOREIGN KEY (target_server_id) REFERENCES servers(server_id) ON DELETE CASCADE,
    INDEX idx_target_server (target_server_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_priority_created (priority DESC, created_at ASC)
    );

-- Store responses from backend servers
CREATE TABLE IF NOT EXISTS response_queue (
                                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                              request_id VARCHAR(64) UNIQUE NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    status_code INT NOT NULL,
    headers JSON,
    body LONGTEXT,
    content_type VARCHAR(128) DEFAULT 'application/json',
    processed_time_ms INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (server_id) REFERENCES servers(server_id) ON DELETE CASCADE,
    INDEX idx_request_id (request_id),
    INDEX idx_server_id (server_id),
    INDEX idx_created_at (created_at)
    );

-- Track network-wide statistics and metrics
CREATE TABLE IF NOT EXISTS network_metrics (
                                               id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                               server_id VARCHAR(64) NOT NULL,
    metric_type VARCHAR(64) NOT NULL,
    metric_value DECIMAL(15,4) NOT NULL,
    metadata JSON,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (server_id) REFERENCES servers(server_id) ON DELETE CASCADE,
    INDEX idx_server_metric (server_id, metric_type),
    INDEX idx_metric_type (metric_type),
    INDEX idx_recorded_at (recorded_at)
    );

-- Store addon configurations that can be shared across servers
CREATE TABLE IF NOT EXISTS addon_configs (
                                             id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             addon_name VARCHAR(128) NOT NULL,
    config_key VARCHAR(256) NOT NULL,
    config_value JSON NOT NULL,
    server_id VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY unique_addon_config (addon_name, config_key, server_id),
    INDEX idx_addon_name (addon_name),
    INDEX idx_server_id (server_id)
    );

-- Enable event scheduler if supported
SET @@global.event_scheduler = 1;

-- Cleanup procedures for old data
DELIMITER //

CREATE EVENT IF NOT EXISTS cleanup_old_requests
ON SCHEDULE EVERY 1 HOUR
STARTS CURRENT_TIMESTAMP
DO
BEGIN
    -- Remove completed/failed requests older than 24 hours
DELETE FROM request_queue
WHERE status IN ('completed', 'failed', 'timeout')
  AND created_at < DATE_SUB(NOW(), INTERVAL 24 HOUR);

-- Remove responses older than 24 hours
DELETE FROM response_queue
WHERE created_at < DATE_SUB(NOW(), INTERVAL 24 HOUR);

-- Remove metrics older than 30 days
DELETE FROM network_metrics
WHERE recorded_at < DATE_SUB(NOW(), INTERVAL 30 DAY);
END //

DELIMITER ;

-- Mark servers as offline if no heartbeat for 2 minutes
DELIMITER //

CREATE EVENT IF NOT EXISTS mark_offline_servers
ON SCHEDULE EVERY 30 SECOND
STARTS CURRENT_TIMESTAMP
DO
BEGIN
UPDATE servers
SET status = 'offline'
WHERE status = 'online'
  AND last_heartbeat < DATE_SUB(NOW(), INTERVAL 2 MINUTE);
END //

DELIMITER ;