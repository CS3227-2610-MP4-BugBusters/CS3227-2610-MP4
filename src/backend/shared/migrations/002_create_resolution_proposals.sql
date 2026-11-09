CREATE TABLE resolution_proposals (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    status text NOT NULL CHECK (status IN ('PENDING', 'SUPERSEDED', 'CLOSED')),
    version bigint NOT NULL CHECK (version >= 0),
    contents text NOT NULL
);
