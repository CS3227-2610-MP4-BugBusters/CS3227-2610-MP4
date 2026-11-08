-- Test-only fixture for private-notes HTTP tests. Use a disposable database.
-- Pass report_id, reporter_id, developer_id, and proposal_id as psql variables.
BEGIN;

CREATE TABLE reports (
    id uuid PRIMARY KEY,
    reporter_id uuid NOT NULL,
    assignee_id uuid,
    status text NOT NULL,
    version bigint NOT NULL,
    current_proposal_id uuid
);

CREATE TABLE resolution_proposals (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    status text NOT NULL,
    version bigint NOT NULL,
    contents text NOT NULL
);

ALTER TABLE reports ADD FOREIGN KEY (current_proposal_id) REFERENCES resolution_proposals(id);

INSERT INTO reports (id, reporter_id, assignee_id, status, version)
VALUES (:'report_id'::uuid, :'reporter_id'::uuid, :'developer_id'::uuid,
        'RESOLUTION_PROPOSED', 7);

INSERT INTO resolution_proposals (id, report_id, status, version, contents)
VALUES (:'proposal_id'::uuid, :'report_id'::uuid, 'PENDING', 1,
        'Retry export after restoring the connection.');

UPDATE reports SET current_proposal_id = :'proposal_id'::uuid
WHERE id = :'report_id'::uuid;

COMMIT;
