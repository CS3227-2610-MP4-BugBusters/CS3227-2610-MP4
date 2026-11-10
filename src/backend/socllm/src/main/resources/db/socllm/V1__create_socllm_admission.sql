CREATE SCHEMA IF NOT EXISTS mp4_socllm;
CREATE TABLE mp4_socllm.quota_lock(id smallint PRIMARY KEY CHECK(id=1));
INSERT INTO mp4_socllm.quota_lock(id) VALUES(1);
CREATE TABLE mp4_socllm.provider_budget(
 key_fingerprint char(64) PRIMARY KEY,
 requests_per_minute integer NOT NULL CHECK(requests_per_minute>0),
 reserved_tokens_per_minute integer NOT NULL CHECK(reserved_tokens_per_minute>0),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE mp4_socllm.attempt(
 request_id uuid PRIMARY KEY, actor_id uuid NOT NULL,
 role text NOT NULL CHECK(role IN ('REPORTER','DEVELOPER','MAINTAINER')),
 task text NOT NULL, key_fingerprint char(64) NOT NULL,
 admitted_at timestamptz NOT NULL, reserved_tokens integer NOT NULL CHECK(reserved_tokens>0),
 in_flight_until timestamptz
);
CREATE INDEX attempt_actor_window ON mp4_socllm.attempt(actor_id,role,admitted_at);
CREATE INDEX attempt_key_window ON mp4_socllm.attempt(key_fingerprint,admitted_at);
CREATE INDEX attempt_inflight ON mp4_socllm.attempt(actor_id,role,in_flight_until) WHERE in_flight_until IS NOT NULL;
