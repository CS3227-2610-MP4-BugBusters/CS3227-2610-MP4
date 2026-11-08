-- Minimal stand-in for the shared reports migration in an isolated test database.
CREATE TABLE reports (
    id uuid PRIMARY KEY,
    reporter_id uuid NOT NULL,
    assignee_id uuid,
    status text NOT NULL,
    version bigint NOT NULL,
    current_proposal_id uuid
);
