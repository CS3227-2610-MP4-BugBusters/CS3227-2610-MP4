-- Spring Session JDBC PostgreSQL schema, isolated under the auth-owned table names.
CREATE TABLE public.mp4_auth_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT mp4_auth_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX mp4_auth_session_session_id_idx ON public.mp4_auth_session(session_id);
CREATE INDEX mp4_auth_session_expiry_idx ON public.mp4_auth_session(expiry_time);
CREATE INDEX mp4_auth_session_principal_idx ON public.mp4_auth_session(principal_name);
CREATE TABLE public.mp4_auth_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT mp4_auth_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT mp4_auth_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES public.mp4_auth_session(primary_id) ON DELETE CASCADE
);
