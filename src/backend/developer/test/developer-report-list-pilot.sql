-- Synthetic DR-01 pilot data only; intentionally separate from shared reports schema.
DROP TABLE IF EXISTS dr01_pilot_reports;
CREATE TABLE dr01_pilot_reports (
    id uuid PRIMARY KEY,
    title text NOT NULL,
    status text NOT NULL,
    assignee_id uuid
);

INSERT INTO dr01_pilot_reports (id, title, status, assignee_id) VALUES
('00000000-0000-0000-0000-000000000301', 'D1 open report', 'ASSIGNED', '00000000-0000-0000-0000-000000000401'),
('00000000-0000-0000-0000-000000000302', 'D1 closed report', 'CLOSED', '00000000-0000-0000-0000-000000000401'),
('00000000-0000-0000-0000-000000000303', 'D2 report', 'IN_PROGRESS', '00000000-0000-0000-0000-000000000402'),
('00000000-0000-0000-0000-000000000304', 'Unassigned report', 'ASSIGNED', NULL);

