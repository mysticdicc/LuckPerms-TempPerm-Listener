package lpTempListener;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DbManager {
    private final LpTempListener plugin;
    private Connection connection;

    public DbManager(LpTempListener plugin) {
        this.plugin = plugin;
    }

    public void open() throws SQLException {
        if (!this.plugin.getDataFolder().exists() && !this.plugin.getDataFolder().mkdirs()) {
            throw new SQLException("Failed to create plugin data folder");
        }

        File dbFile = new File(this.plugin.getDataFolder(), "delayed-commands.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        this.connection = DriverManager.getConnection(url);

        try (Statement statement = this.connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS delayed_commands (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    permission TEXT NOT NULL,
                    username TEXT NOT NULL,
                    expiry_epoch_millis INTEGER NOT NULL,
                    command TEXT NOT NULL,
                    executed INTEGER NOT NULL DEFAULT 0 CHECK (executed IN (0, 1))
                )
            """);
        }

        migrateUserIdColumn();
    }

    public DelayedCommandExecution addDelayedCommandExecution(DelayedCommandExecution execution) throws SQLException {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(execution.permission, "execution.permission");
        Objects.requireNonNull(execution.username, "execution.username");
        Objects.requireNonNull(execution.expiry, "execution.expiry");
        Objects.requireNonNull(execution.command, "execution.command");

        try (PreparedStatement statement = requireConnection().prepareStatement("""
            INSERT INTO delayed_commands (permission, username, expiry_epoch_millis, command, executed)
            VALUES (?, ?, ?, ?, ?)
        """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, execution.permission);
            statement.setString(2, execution.username);
            statement.setLong(3, execution.expiry.toEpochMilli());
            statement.setString(4, execution.command);
            statement.setInt(5, execution.executed ? 1 : 0);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("Failed to read generated row id");
                }

                execution.id = generatedKeys.getLong(1);
            }
        }

        return execution;
    }

    public List<DelayedCommandExecution> fetchDelayedCommandExecutions() throws SQLException {
        List<DelayedCommandExecution> executions = new ArrayList<>();

        try (PreparedStatement statement = requireConnection().prepareStatement("""
            SELECT id, permission, username, expiry_epoch_millis, command, executed
            FROM delayed_commands
            ORDER BY expiry_epoch_millis ASC
        """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                executions.add(mapDelayedCommandExecution(resultSet));
            }
        }

        return executions;
    }

    public void updateDelayedCommandExecution(DelayedCommandExecution execution) throws SQLException {
        Objects.requireNonNull(execution, "execution");

        if (execution.id == null) {
            throw new IllegalArgumentException("execution.id must be set before updating");
        }

        Objects.requireNonNull(execution.permission, "execution.permission");
        Objects.requireNonNull(execution.username, "execution.username");
        Objects.requireNonNull(execution.expiry, "execution.expiry");
        Objects.requireNonNull(execution.command, "execution.command");

        try (PreparedStatement statement = requireConnection().prepareStatement("""
            UPDATE delayed_commands
            SET permission = ?, username = ?, expiry_epoch_millis = ?, command = ?, executed = ?
            WHERE id = ?
        """)) {
            statement.setString(1, execution.permission);
            statement.setString(2, execution.username);
            statement.setLong(3, execution.expiry.toEpochMilli());
            statement.setString(4, execution.command);
            statement.setInt(5, execution.executed ? 1 : 0);
            statement.setLong(6, execution.id);

            int updatedRows = statement.executeUpdate();
            if (updatedRows == 0) {
                throw new SQLException("No delayed command row found for id " + execution.id);
            }
        }
    }

    public Connection getConnection() throws SQLException {
        return requireConnection();
    }

    public void close() throws SQLException {
        if (this.connection != null && !this.connection.isClosed()) {
            this.connection.close();
        }
    }

    private Connection requireConnection() throws SQLException {
        if (this.connection == null || this.connection.isClosed()) {
            throw new SQLException("Database connection is not open");
        }

        return this.connection;
    }

    private void migrateUserIdColumn() throws SQLException {
        boolean hasUsernameColumn = false;
        boolean hasUserIdColumn = false;

        try (PreparedStatement statement = requireConnection().prepareStatement("PRAGMA table_info(delayed_commands)");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("name");
                if ("username".equalsIgnoreCase(columnName)) {
                    hasUsernameColumn = true;
                } else if ("user_id".equalsIgnoreCase(columnName)) {
                    hasUserIdColumn = true;
                }
            }
        }

        if (!hasUsernameColumn && hasUserIdColumn) {
            try (Statement statement = requireConnection().createStatement()) {
                statement.executeUpdate("ALTER TABLE delayed_commands RENAME COLUMN user_id TO username");
            }
        }
    }

    private DelayedCommandExecution mapDelayedCommandExecution(ResultSet resultSet) throws SQLException {
        DelayedCommandExecution execution = new DelayedCommandExecution();
        execution.id = resultSet.getLong("id");
        execution.permission = resultSet.getString("permission");
        execution.username = resultSet.getString("username");
        execution.expiry = java.time.Instant.ofEpochMilli(resultSet.getLong("expiry_epoch_millis"));
        execution.command = resultSet.getString("command");
        execution.executed = resultSet.getInt("executed") == 1;
        return execution;
    }
}
