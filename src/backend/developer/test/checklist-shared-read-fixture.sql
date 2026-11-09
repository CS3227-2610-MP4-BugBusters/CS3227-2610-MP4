-- Test only. Seed a private draft A with no published checklist copy.
-- Pass report_id, developer_id, reporter_id, and proposal_id.
BEGIN;

INSERT INTO reports (id, reporter_id, assignee_id, status, version)
VALUES (:'report_id'::uuid, :'reporter_id'::uuid, :'developer_id'::uuid,
        'RESOLUTION_PROPOSED', 7);

INSERT INTO resolution_proposals (id, report_id, status, version, contents)
VALUES (:'proposal_id'::uuid, :'report_id'::uuid, 'PENDING', 1,
        'Retry export after restoring the connection.');

UPDATE reports SET current_proposal_id = :'proposal_id'::uuid
WHERE id = :'report_id'::uuid;

INSERT INTO investigation_checklists (report_id, draft_steps, draft_revision, shared_steps)
VALUES (:'report_id'::uuid,
        ARRAY['Investigate the first trace', 'Compare initial timestamps'], 2,
        ARRAY[]::text[]);

COMMIT;
