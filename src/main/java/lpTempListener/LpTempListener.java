package lpTempListener;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Hashtable;
import java.util.Objects;
import java.util.logging.Level;

public final class LpTempListener extends JavaPlugin {
    private LuckPerms luckPerms;
    private final Hashtable<String, String> permHash = new Hashtable<>();
    private DbManager databaseManager;

    @Override
    public void onEnable() {
        this.luckPerms = LuckPermsProvider.get();
        this.permHash.put("essentials.fly", "fly <username> disable");
        this.permHash.put("essentials.god", "god <username> disable");

        this.databaseManager = new DbManager(this);
        try {
            this.databaseManager.open();

            for (DelayedCommandExecution execution : this.databaseManager.fetchDelayedCommandExecutions()) {
                if (!execution.executed) {
                    scheduleDelayedExecution(execution);
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to open SQLite database", exception);
        }

        this.luckPerms.getEventBus().subscribe(this, NodeAddEvent.class, this::onUserTempPermissionAdded);
        getLogger().info("DankCraft permissions listener has started.");
    }

    private void scheduleDelayedExecution(DelayedCommandExecution execution) {
        long delayMillis = execution.expiry.toEpochMilli() - System.currentTimeMillis();
        long delayTicks = Math.max(0L, delayMillis / 50L);

        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                getServer().dispatchCommand(
                        getServer().getConsoleSender(),
                        execution.command
                );
                getLogger().info("DankCraft permission listener disabled command: " + execution.command);
                execution.executed = true;
                this.databaseManager.updateDelayedCommandExecution(execution);
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Failed to execute delayed command " + execution.id, exception);
            }
        }, delayTicks);
    }

    private void onUserTempPermissionAdded(NodeAddEvent event) {
        if (!event.isUser()) {
            return;
        }

        Node node = event.getNode();
        if (!node.hasExpiry() || node.getType() != NodeType.PERMISSION) {
            return;
        }

        PermissionNode permissionNode = (PermissionNode) node;
        String permission = permissionNode.getPermission();
        if (!this.permHash.containsKey(permission)) {
            return;
        }

        User user = (User) event.getTarget();
        String username = user.getUsername();
        if (username == null || username.isBlank()) {
            getLogger().warning("Skipping delayed command because no username is available for the matched LuckPerms user.");
            return;
        }

        Instant expiry = Objects.requireNonNull(permissionNode.getExpiry(), "Temporary node expiry cannot be null");

        DelayedCommandExecution delayedExecution = new DelayedCommandExecution();
        delayedExecution.command = this.permHash.get(permission).replace("<username>", username);
        delayedExecution.username = username;
        delayedExecution.expiry = expiry;
        delayedExecution.permission = permission;
        delayedExecution.executed = false;

        try {
            DelayedCommandExecution saved = this.databaseManager.addDelayedCommandExecution(delayedExecution);
            scheduleDelayedExecution(saved);
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Failed to persist delayed command execution", exception);
        }
    }

    @Override
    public void onDisable() {
        if (this.databaseManager != null) {
            try {
                this.databaseManager.close();
            } catch (SQLException exception) {
                getLogger().log(Level.SEVERE, "Failed to close SQLite database", exception);
            }
        }
    }
}
