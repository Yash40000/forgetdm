CREATE TABLE forge_users (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(120) NOT NULL UNIQUE,
  display_name  VARCHAR(160),
  password_hash VARCHAR(500) NOT NULL,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP
);

CREATE TABLE forge_groups (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(120) NOT NULL UNIQUE,
  description VARCHAR(500),
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE forge_user_groups (
  user_id  BIGINT NOT NULL REFERENCES forge_users(id) ON DELETE CASCADE,
  group_id BIGINT NOT NULL REFERENCES forge_groups(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, group_id)
);

CREATE TABLE forge_user_roles (
  user_id   BIGINT NOT NULL REFERENCES forge_users(id) ON DELETE CASCADE,
  role_name VARCHAR(80) NOT NULL,
  PRIMARY KEY (user_id, role_name)
);

CREATE TABLE forge_group_roles (
  group_id  BIGINT NOT NULL REFERENCES forge_groups(id) ON DELETE CASCADE,
  role_name VARCHAR(80) NOT NULL,
  PRIMARY KEY (group_id, role_name)
);

CREATE TABLE forge_sessions (
  token_hash   VARCHAR(128) PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES forge_users(id) ON DELETE CASCADE,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at   TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP
);

CREATE INDEX idx_forge_sessions_user ON forge_sessions(user_id);
CREATE INDEX idx_forge_sessions_expires ON forge_sessions(expires_at);
