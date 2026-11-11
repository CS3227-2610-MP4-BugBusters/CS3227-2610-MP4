# Developer lifecycle integration contract

The active assigned Developer may start an Assigned report or submit a resolution for an In progress report using the expected report version. For each write, lock and recheck the session actor account (active Developer role and authentication generation), then lock the report and recheck assignment, state and version. Keep account-before-report ordering consistent with the Maintainer and private-notes paths.

In the same transaction, append a shared report-history event with the actor and new report version. Start changes status to In progress. Resolution creates a pending proposal and changes status to Resolution proposed. If the history insertion fails, roll back all report and proposal changes. Do not place private checklist drafts, private notes or resolution text in history. This local integration decision remains subject to the shared lifecycle integration and release review.
