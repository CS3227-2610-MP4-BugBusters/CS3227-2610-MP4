CREATE TABLE public.developer_notifications (
    id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL,
    report_id uuid NOT NULL REFERENCES public.reports(id),
    event_type text NOT NULL CHECK (event_type IN ('ASSIGNED','UNASSIGNED','RESOLUTION_RETURNED','REOPENED')),
    report_version bigint NOT NULL CHECK (report_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz,
    UNIQUE (recipient_id, report_id, report_version, event_type)
);
CREATE INDEX developer_notifications_recipient_recent
    ON public.developer_notifications (recipient_id, created_at DESC, id DESC);
