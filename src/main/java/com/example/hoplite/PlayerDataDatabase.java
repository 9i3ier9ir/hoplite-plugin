package com.example.hoplite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerDataDatabase implements AutoCloseable {
    public static final class PlayerStats {
        public final UUID playerId;
        public final long coins;
        public final int elo;

        private PlayerStats(UUID playerId, long coins, int elo) {
            this.playerId = playerId;
            this.coins = coins;
            this.elo = elo;
        }
    }

    public static final class KitDefinition {
        public final String name;
        public final int price;
        public final String icon;
        public final String itemsYaml;
        public final boolean custom;

        private KitDefinition(String name, int price, String icon, String itemsYaml, boolean custom) {
            this.name = name;
            this.price = price;
            this.icon = icon;
            this.itemsYaml = itemsYaml;
            this.custom = custom;
        }
    }

    private final HikariDataSource dataSource;
    private final String statsTable;
    private final String kitsTable;
    private final String definitionsTable;

    public PlayerDataDatabase(String host, int port, String database, String username, String password,
                              String tablePrefix) throws SQLException {
        if (tablePrefix == null || !tablePrefix.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Database table prefix may only contain letters, numbers, and underscores.");
        }
        statsTable = tablePrefix + "player_data";
        kitsTable = tablePrefix + "player_kits";
        definitionsTable = tablePrefix + "kit_definitions";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?connectTimeout=5000&socketTimeout=5000");
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("Hoplite-PlayerData");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setInitializationFailTimeout(5000);
        dataSource = new HikariDataSource(config);
        try {
            createTables();
        } catch (SQLException exception) {
            dataSource.close();
            throw exception;
        }
    }

    private void createTables() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + statsTable + " ("
                    + "player_uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + "coins BIGINT NOT NULL DEFAULT 0,"
                    + "elo INT NOT NULL DEFAULT 1000"
                    + ") ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + kitsTable + " ("
                    + "player_uuid CHAR(36) NOT NULL,"
                    + "kit_name VARCHAR(64) NOT NULL,"
                    + "PRIMARY KEY (player_uuid, kit_name)"
                    + ") ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + definitionsTable + " ("
                    + "kit_name VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "price INT NOT NULL,"
                    + "icon VARCHAR(64) NOT NULL,"
                    + "items_yaml LONGTEXT NOT NULL,"
                    + "is_custom BOOLEAN NOT NULL"
                    + ") ENGINE=InnoDB");
        }
    }

    public void importLegacyKitDefinitions(List<KitData.Kit> kits, Set<String> customNames,
                                           Map<String, String> serializedItems) throws SQLException {
        String insert = "INSERT IGNORE INTO " + definitionsTable
                + " (kit_name, price, icon, items_yaml, is_custom) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(insert)) {
            for (KitData.Kit kit : kits) {
                statement.setString(1, kit.name);
                statement.setInt(2, kit.price);
                statement.setString(3, kit.icon.name());
                statement.setString(4, serializedItems.getOrDefault(kit.name, ""));
                statement.setBoolean(5, customNames.contains(kit.name));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void saveKitDefinition(KitData.Kit kit, boolean custom, String itemsYaml) throws SQLException {
        String sql = "INSERT INTO " + definitionsTable
                + " (kit_name, price, icon, items_yaml, is_custom) VALUES (?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE price = VALUES(price), icon = VALUES(icon),"
                + " items_yaml = VALUES(items_yaml), is_custom = VALUES(is_custom)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kit.name);
            statement.setInt(2, kit.price);
            statement.setString(3, kit.icon.name());
            statement.setString(4, itemsYaml);
            statement.setBoolean(5, custom);
            statement.executeUpdate();
        }
    }

    public List<KitDefinition> getKitDefinitions() throws SQLException {
        List<KitDefinition> definitions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT kit_name, price, icon, items_yaml, is_custom FROM " + definitionsTable);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                definitions.add(new KitDefinition(result.getString("kit_name"), result.getInt("price"),
                        result.getString("icon"), result.getString("items_yaml"),
                        result.getBoolean("is_custom")));
            }
        }
        return definitions;
    }

    public void importLegacyData(Map<UUID, Long> coins, Map<UUID, Integer> elo,
                                 Map<UUID, Set<String>> ownedKits) throws SQLException {
        String insertStats = "INSERT IGNORE INTO " + statsTable
                + " (player_uuid, coins, elo) VALUES (?, ?, ? )";
        String insertKit = "INSERT IGNORE INTO " + kitsTable
                + " (player_uuid, kit_name) VALUES (?, ?)";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statsStatement = connection.prepareStatement(insertStats);
                 PreparedStatement kitStatement = connection.prepareStatement(insertKit)) {
                Set<UUID> playerIds = new HashSet<>();
                playerIds.addAll(coins.keySet());
                playerIds.addAll(elo.keySet());
                playerIds.addAll(ownedKits.keySet());
                for (UUID playerId : playerIds) {
                    statsStatement.setString(1, playerId.toString());
                    statsStatement.setLong(2, coins.getOrDefault(playerId, 0L));
                    statsStatement.setInt(3, elo.getOrDefault(playerId, 1000));
                    statsStatement.addBatch();
                }
                statsStatement.executeBatch();

                for (Map.Entry<UUID, Set<String>> entry : ownedKits.entrySet()) {
                    for (String kit : entry.getValue()) {
                        kitStatement.setString(1, entry.getKey().toString());
                        kitStatement.setString(2, kit);
                        kitStatement.addBatch();
                    }
                }
                kitStatement.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public PlayerStats getStats(UUID playerId) throws SQLException {
        ensurePlayer(playerId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT coins, elo FROM " + statsTable + " WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return new PlayerStats(playerId, 0, 1000);
                return new PlayerStats(playerId, result.getLong("coins"), result.getInt("elo"));
            }
        }
    }

    public long addCoins(UUID playerId, long amount) throws SQLException {
        ensurePlayer(playerId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + statsTable + " SET coins = coins + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
        return getStats(playerId).coins;
    }

    public boolean removeCoins(UUID playerId, long amount) throws SQLException {
        ensurePlayer(playerId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + statsTable + " SET coins = coins - ? WHERE player_uuid = ? AND coins >= ?")) {
            statement.setLong(1, amount);
            statement.setString(2, playerId.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate() == 1;
        }
    }

    public int changeElo(UUID playerId, int amount) throws SQLException {
        ensurePlayer(playerId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + statsTable + " SET elo = elo + ? WHERE player_uuid = ?")) {
            statement.setInt(1, amount);
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
        return getStats(playerId).elo;
    }

    public boolean ownsKit(UUID playerId, String kitName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM " + kitsTable + " WHERE player_uuid = ? AND kit_name = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, kitName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public boolean purchaseKit(UUID playerId, String kitName, long price) throws SQLException {
        ensurePlayer(playerId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement check = connection.prepareStatement(
                        "SELECT 1 FROM " + kitsTable + " WHERE player_uuid = ? AND kit_name = ? FOR UPDATE")) {
                    check.setString(1, playerId.toString());
                    check.setString(2, kitName);
                    try (ResultSet result = check.executeQuery()) {
                        if (result.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }

                try (PreparedStatement debit = connection.prepareStatement(
                        "UPDATE " + statsTable + " SET coins = coins - ? WHERE player_uuid = ? AND coins >= ?")) {
                    debit.setLong(1, price);
                    debit.setString(2, playerId.toString());
                    debit.setLong(3, price);
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }

                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + kitsTable + " (player_uuid, kit_name) VALUES (?, ?)")) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, kitName);
                    insert.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<PlayerStats> getLeaderboard(String column, int limit) throws SQLException {
        if (!"coins".equals(column) && !"elo".equals(column)) {
            throw new IllegalArgumentException("Unsupported leaderboard column.");
        }
        List<PlayerStats> entries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT player_uuid, coins, elo FROM " + statsTable
                             + " ORDER BY " + column + " DESC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(new PlayerStats(UUID.fromString(result.getString("player_uuid")),
                            result.getLong("coins"), result.getInt("elo")));
                }
            }
        }
        return entries;
    }

    private void ensurePlayer(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT IGNORE INTO " + statsTable
                             + " (player_uuid, coins, elo) VALUES (?, 0, 1000)")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
