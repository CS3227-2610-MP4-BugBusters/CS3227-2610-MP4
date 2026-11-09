DROP TABLE IF EXISTS investigation_checklists;
DROP TABLE IF EXISTS reports;
DROP TABLE IF EXISTS resolution_proposals;

CREATE TABLE resolution_proposals (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL,
    status text NOT NULL,
    version bigint NOT NULL,
    contents text NOT NULL
);
CREATE TABLE reports (
    id uuid PRIMARY KEY,
    reporter_id uuid NOT NULL,
    assignee_id uuid,
    status text NOT NULL,
    version bigint NOT NULL,
    current_proposal_id uuid REFERENCES resolution_proposals(id)
);
CREATE TABLE investigation_checklists (
    report_id uuid PRIMARY KEY REFERENCES reports(id),
    draft_steps text[] NOT NULL DEFAULT '{}',
    draft_revision bigint NOT NULL DEFAULT 0 CHECK (draft_revision >= 0),
    shared_steps text[] NOT NULL DEFAULT '{}'
);
