CREATE TABLE reports (
    id uuid PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL,
    reporter_id uuid NOT NULL,
    assignee_id uuid,
    status text NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLUTION_PROPOSED', 'CLOSED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    current_proposal_id uuid
);
