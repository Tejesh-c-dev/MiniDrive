package com.minidrive.storage;

import com.minidrive.exception.StorageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

import java.util.function.Supplier;

/** Shared process and PostgreSQL advisory lock for mutations of one object key. */
final class StorageKeyLock {
    private static final Object[] LOCKS = new Object[256];

    static { java.util.Arrays.setAll(LOCKS, ignored -> new Object()); }

    private StorageKeyLock() { }

    static <T> T withKeyLock(JdbcTemplate jdbc, String key, Supplier<T> action) {
        synchronized (LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)]) {
            var dataSource = jdbc.getDataSource();
            if (dataSource == null) return action.get();
            var connection = DataSourceUtils.getConnection(dataSource);
            boolean postgres = false;
            boolean locked = false;
            try {
                postgres = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
                if (postgres) {
                    try (var statement = connection.prepareStatement("select pg_advisory_lock(hashtextextended(?, 0))")) {
                        statement.setString(1, key);
                        statement.execute();
                        locked = true;
                    }
                }
                return action.get();
            } catch (java.sql.SQLException e) {
                throw new StorageException("Unable to coordinate storage operation", e);
            } finally {
                if (locked) {
                    try (var statement = connection.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
                        statement.setString(1, key);
                        statement.execute();
                    } catch (java.sql.SQLException e) {
                        throw new StorageException("Unable to release storage operation lock", e);
                    }
                }
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }
}
