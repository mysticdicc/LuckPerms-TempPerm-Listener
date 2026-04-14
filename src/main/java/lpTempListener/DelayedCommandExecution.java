package lpTempListener;

import java.time.Instant;

public final class DelayedCommandExecution {
    public Long id;
    public String permission;
    public String username;
    public Instant expiry;
    public String command;
    public boolean executed;
}
