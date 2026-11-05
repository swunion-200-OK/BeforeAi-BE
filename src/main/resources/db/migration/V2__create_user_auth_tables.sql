CREATE TABLE users
(
    id                       uuid PRIMARY KEY,
    employee_number          varchar(50)  NOT NULL,
    password_hash            varchar(255) NOT NULL,
    name                     varchar(100) NOT NULL,
    role                     varchar(20)  NOT NULL,
    department_id            uuid,
    active                   boolean      NOT NULL,
    password_change_required boolean      NOT NULL,
    otp_secret               varchar(64),
    otp_enabled              boolean      NOT NULL DEFAULT false,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT uk_users_employee_number UNIQUE (employee_number)
);

CREATE TABLE signing_keys
(
    id          uuid PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES users (id),
    algorithm   varchar(20)  NOT NULL,
    public_key  varchar(100) NOT NULL,
    device_name varchar(100),
    status      varchar(20)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT uk_signing_keys_public_key UNIQUE (public_key)
);

-- 내 공개키 목록은 최신순으로 조회
CREATE INDEX idx_signing_keys_user_id_created_at ON signing_keys (user_id, created_at DESC, id DESC);

-- 사용자당 폐기되지 않은 키는 1개만 허용
CREATE UNIQUE INDEX uk_signing_keys_user_active ON signing_keys (user_id) WHERE status <> 'REVOKED';

CREATE TABLE refresh_tokens
(
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id),
    token_hash varchar(64) NOT NULL,
    network    varchar(10) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
