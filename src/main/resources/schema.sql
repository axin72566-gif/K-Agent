CREATE TABLE IF NOT EXISTS agent_session (
    session_id VARCHAR(255) PRIMARY KEY,
    data TEXT NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS user_profile (
    user_id VARCHAR(255) PRIMARY KEY,
    role VARCHAR(500),
    tech_stack VARCHAR(500),
    preferences VARCHAR(500),
    current_project VARCHAR(500),
    extra TEXT,
    updated_at DATETIME
);
