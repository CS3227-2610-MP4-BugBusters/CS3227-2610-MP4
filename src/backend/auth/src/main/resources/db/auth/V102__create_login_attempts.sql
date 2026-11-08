CREATE TABLE public.mp4_auth_login_attempt (
    key_digest CHAR(64) NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX mp4_auth_login_attempt_key_time_idx ON public.mp4_auth_login_attempt (key_digest, attempted_at);
CREATE INDEX mp4_auth_login_attempt_time_idx ON public.mp4_auth_login_attempt (attempted_at);
