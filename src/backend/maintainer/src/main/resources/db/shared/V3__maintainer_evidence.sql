ALTER TABLE reports
    ADD COLUMN priority text NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN reproduction_steps text NOT NULL DEFAULT '',
    ADD COLUMN expected_behavior text NOT NULL DEFAULT '',
    ADD COLUMN actual_behavior text NOT NULL DEFAULT '',
    ADD COLUMN environment text NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS resolution_proposals (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    status text NOT NULL CHECK (status IN ('PENDING', 'SUPERSEDED', 'CLOSED')),
    version bigint NOT NULL CHECK (version >= 0),
    contents text NOT NULL
);

CREATE TABLE resolution_proposal_metadata (
    proposal_id uuid PRIMARY KEY REFERENCES resolution_proposals(id),
    author_id uuid,
    created_at timestamptz
);

CREATE FUNCTION mp4_capture_proposal_author() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO resolution_proposal_metadata(proposal_id, author_id, created_at)
    SELECT NEW.id, assignee_id, now() FROM reports WHERE id = NEW.report_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER resolution_proposal_capture_author
    AFTER INSERT ON resolution_proposals
    FOR EACH ROW EXECUTE FUNCTION mp4_capture_proposal_author();

CREATE TABLE report_history (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    type text NOT NULL,
    text text NOT NULL,
    actor_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    report_version bigint NOT NULL CHECK (report_version >= 0)
);
CREATE TABLE report_comments (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    author_id uuid NOT NULL,
    text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE report_shared_notes (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    author_id uuid NOT NULL,
    text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE report_responses (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    author_id uuid NOT NULL,
    text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION mp4_report_version_timestamp() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.version IS DISTINCT FROM OLD.version THEN
        NEW.updated_at := now();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER reports_version_updated_at
    BEFORE UPDATE ON reports
    FOR EACH ROW EXECUTE FUNCTION mp4_report_version_timestamp();
