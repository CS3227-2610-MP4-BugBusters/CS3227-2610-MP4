-- Read-only snapshot for checklist_share_http_contract_test.py.
SELECT jsonb_build_object(
    'draftSteps', checklist.draft_steps,
    'draftRevision', checklist.draft_revision,
    'sharedSteps', checklist.shared_steps,
    'report', jsonb_build_object(
        'id', report.id,
        'reporterId', report.reporter_id,
        'assigneeId', report.assignee_id,
        'status', report.status,
        'version', report.version,
        'currentProposalId', report.current_proposal_id
    ),
    'proposal', jsonb_build_object(
        'id', proposal.id,
        'reportId', proposal.report_id,
        'status', proposal.status,
        'version', proposal.version,
        'contents', proposal.contents
    )
)
FROM reports report
JOIN investigation_checklists checklist ON checklist.report_id = report.id
JOIN resolution_proposals proposal ON proposal.id = report.current_proposal_id
WHERE report.id = :'report_id'::uuid;
