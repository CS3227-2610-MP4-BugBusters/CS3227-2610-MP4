package mp4.auth;

import java.util.Locale;

/** One canonical ASCII username normalization rule for provisioning and login. */
public final class UsernamePolicy {
    private UsernamePolicy() { }

    public static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("invalid username");
        String trimmed = input.trim();
        if (!trimmed.chars().allMatch(ch -> ch < 128)) throw new IllegalArgumentException("invalid username");
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 64 || !normalized.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid username");
        }
        return normalized;
    }
}
