package mp4.developer;

/** Placeholder until the shared report transaction and authenticated routing are available. */
public final class UnimplementedChecklistShareService implements ChecklistShareService {
    @Override
    public ShareResult share(ShareRequest request) {
        throw new UnsupportedOperationException("Checklist Share is not implemented");
    }
}
