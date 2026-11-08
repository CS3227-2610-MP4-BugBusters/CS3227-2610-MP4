package mp4.auth;

import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;

/** Silences only this operator command's Flyway instance to preserve its JSON-line output. */
public final class SilentFlywayLogCreator implements LogCreator {
    @Override public Log createLogger(Class<?> ignored) { return SilentLog.INSTANCE; }

    private enum SilentLog implements Log {
        INSTANCE;
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message) { }
        @Override public void error(String message, Exception exception) { }
        @Override public void notice(String message) { }
    }
}
