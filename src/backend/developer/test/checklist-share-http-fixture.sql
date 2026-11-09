-- Test only. Seed a synthetic report with a changed saved draft and prior shared copy.
-- Pass report_id, developer_id, unassigned_id, reporter_id, and proposal_id.
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
        ARRAY['Compare timestamps', 'Review the first trace'], 2,
        ARRAY['Read the incident report', 'Check the logs']);

COMMIT;
