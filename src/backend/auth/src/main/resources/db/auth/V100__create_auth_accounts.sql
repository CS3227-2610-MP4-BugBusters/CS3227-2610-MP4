CREATE TABLE public.app_accounts (
    id UUID NOT NULL,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    auth_generation BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT app_accounts_pkey PRIMARY KEY (id),
    CONSTRAINT app_accounts_username_key UNIQUE (username),
    CONSTRAINT app_accounts_role_check CHECK (role IN ('REPORTER', 'DEVELOPER', 'MAINTAINER')),
    CONSTRAINT app_accounts_auth_generation_check CHECK (auth_generation >= 0)
);
