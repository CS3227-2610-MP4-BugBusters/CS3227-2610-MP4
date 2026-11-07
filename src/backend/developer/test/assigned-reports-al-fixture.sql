-- Test-only fixture for assigned_reports_al_api_test.py.
DROP TABLE IF EXISTS reports CASCADE;
CREATE TABLE reports (
    id uuid PRIMARY KEY,
    title text NOT NULL,
    assignee_id uuid,
    status text NOT NULL,
    reporter_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    current_proposal_id uuid
);

INSERT INTO reports (id, title, assignee_id, status, reporter_id)
SELECT ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid,
       CASE WHEN n = 302 THEN 'Closed report' ELSE 'D1 report ' || n END,
       '00000000-0000-0000-0000-000000000401'::uuid,
       CASE WHEN n = 302 THEN 'CLOSED' ELSE 'ASSIGNED' END,
       '00000000-0000-0000-0000-000000000403'::uuid
FROM generate_series(301, 322) AS n;

INSERT INTO reports (id, title, assignee_id, status, reporter_id) VALUES
('00000000-0000-0000-0000-000000000330', 'D2 report', '00000000-0000-0000-0000-000000000402', 'IN_PROGRESS', '00000000-0000-0000-0000-000000000403'),
('00000000-0000-0000-0000-000000000331', 'Unassigned report', NULL, 'SUBMITTED', '00000000-0000-0000-0000-000000000403');
