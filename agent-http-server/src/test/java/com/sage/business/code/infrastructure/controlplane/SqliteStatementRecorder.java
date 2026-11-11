package com.sage.business.code.infrastructure.controlplane;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * MA00 observation seam: a {@link ControlPlaneConnections.ConnectionDecorator}
 * that wraps every opened control-plane connection in a dynamic proxy and
 * records the real SQL text of every {@code createStatement}/{@code
 * prepareStatement}/{@code prepareCall} plus each {@code Statement.execute*}
 * call. The underlying Xerial driver still executes everything — the recorder
 * only watches, so a green run under the recorder is proof the production
 * path itself touched (or never touched) a table.
 *
 * <p>Not a veto hook and not a "was the store constructed" proxy: entries are
 * SQL strings the real driver received.</p>
 */
public final class SqliteStatementRecorder
        implements ControlPlaneConnections.ConnectionDecorator {

    /** One observed statement — the SQL the real driver was handed. */
    public static final class Entry {
        /** PREPARE for prepareStatement/prepareCall, EXECUTE for raw Statement execution. */
        public final String operation;
        public final String sql;
        public final long atMillis;

        Entry(String operation, String sql, long atMillis) {
            this.operation = operation;
            this.sql = sql;
            this.atMillis = atMillis;
        }

        @Override public String toString() {
            return operation + " " + sql;
        }
    }

    /** Personal model-catalog tables whose touch is the MA00 discriminator. */
    public static final Pattern MODEL_TABLES = Pattern.compile(
            "\\bmodel_provider(_model)?\\b|\\bsettings_import\\b");

    private final CopyOnWriteArrayList<Entry> entries =
            new CopyOnWriteArrayList<Entry>();

    @Override
    public Connection decorate(Connection connection) {
        InvocationHandler handler = new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method,
                                           Object[] args) throws Throwable {
                String name = method.getName();
                if (("prepareStatement".equals(name)
                        || "prepareCall".equals(name))
                        && args != null && args.length > 0
                        && args[0] instanceof String) {
                    record("PREPARE", (String) args[0]);
                }
                Object result;
                try {
                    result = method.invoke(connection, args);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                }
                if (result instanceof java.sql.Statement) {
                    return proxyStatement((java.sql.Statement) result);
                }
                return result;
            }
        };
        return (Connection) Proxy.newProxyInstance(
                SqliteStatementRecorder.class.getClassLoader(),
                new Class<?>[] { Connection.class }, handler);
    }

    private java.sql.Statement proxyStatement(final java.sql.Statement statement) {
        InvocationHandler handler = new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method,
                                           Object[] args) throws Throwable {
                String name = method.getName();
                if (name != null && name.startsWith("execute")
                        && args != null && args.length > 0
                        && args[0] instanceof String) {
                    record("EXECUTE", (String) args[0]);
                }
                try {
                    return method.invoke(statement, args);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
        };
        Class<?> type = statement instanceof java.sql.PreparedStatement
                ? java.sql.PreparedStatement.class
                : statement instanceof java.sql.CallableStatement
                        ? java.sql.CallableStatement.class
                        : java.sql.Statement.class;
        return (java.sql.Statement) Proxy.newProxyInstance(
                SqliteStatementRecorder.class.getClassLoader(),
                new Class<?>[] { type }, handler);
    }

    private void record(String operation, String sql) {
        entries.add(new Entry(operation, sql, System.currentTimeMillis()));
    }

    /** Every recorded statement in arrival order. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    /** Statements whose SQL names a personal model-catalog table. */
    public List<Entry> modelTableStatements() {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (entry.sql != null
                    && MODEL_TABLES.matcher(
                            entry.sql.toLowerCase(Locale.ROOT)).find()) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Statements whose SQL names {@code table} (case-insensitive word). */
    public List<Entry> statementsOn(String table) {
        Pattern needle = Pattern.compile("\\b"
                + Pattern.quote(table.toLowerCase(Locale.ROOT)) + "\\b");
        List<Entry> out = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (entry.sql != null
                    && needle.matcher(
                            entry.sql.toLowerCase(Locale.ROOT)).find()) {
                out.add(entry);
            }
        }
        return out;
    }
}
