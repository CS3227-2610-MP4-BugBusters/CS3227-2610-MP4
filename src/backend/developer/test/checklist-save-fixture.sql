-- Test only. Apply report-fixture.sql and the checklist migration first.
-- Pass report_id and developer_id as psql variables. Use a disposable database.
BEGIN;

CREATE TABLE resolution_proposals (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    status text NOT NULL,
    version bigint NOT NULL,
    contents text NOT NULL
);

ALTER TABLE reports ADD FOREIGN KEY (current_proposal_id) REFERENCES resolution_proposals(id);

INSERT INTO reports (id, reporter_id, assignee_id, status, version)
VALUES (:'report_id'::uuid, '00000000-0000-0000-0000-000000000101',
        :'developer_id'::uuid, 'RESOLUTION_PROPOSED', 7);

INSERT INTO resolution_proposals (id, report_id, status, version, contents)
VALUES ('00000000-0000-0000-0000-000000000201', :'report_id'::uuid,
        'PENDING', 1, 'Retry export after restoring the connection.');

UPDATE reports SET current_proposal_id = '00000000-0000-0000-0000-000000000201'
WHERE id = :'report_id'::uuid;

INSERT INTO investigation_checklists (report_id, draft_steps, draft_revision, shared_steps)
VALUES (:'report_id'::uuid, ARRAY['Compare timestamps', 'Review the first trace'], 2,
        ARRAY['Read the incident report', 'Check the logs']);

COMMIT;
