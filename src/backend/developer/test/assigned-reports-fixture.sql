-- Disposable integration fixture for assigned_reports_api_test.py only.
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

INSERT INTO reports (id, title, assignee_id, status, reporter_id) VALUES
('00000000-0000-0000-0000-000000000301', 'Login fails', '00000000-0000-0000-0000-000000000401', 'ASSIGNED', '00000000-0000-0000-0000-000000000403'),
('00000000-0000-0000-0000-000000000302', 'Closed report', '00000000-0000-0000-0000-000000000401', 'CLOSED', '00000000-0000-0000-0000-000000000403'),
('00000000-0000-0000-0000-000000000303', 'Login fails for another developer', '00000000-0000-0000-0000-000000000402', 'IN_PROGRESS', '00000000-0000-0000-0000-000000000403'),
('00000000-0000-0000-0000-000000000304', 'Unassigned login issue', NULL, 'SUBMITTED', '00000000-0000-0000-0000-000000000403');
