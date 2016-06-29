package org.rakam.postgresql.analysis;

import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.metadata.AbstractMetastore;
import org.rakam.collection.FieldDependencyBuilder;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.util.NotExistsException;
import org.rakam.util.ProjectCollection;

import javax.inject.Inject;
import javax.inject.Named;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static java.lang.String.format;
import static org.rakam.util.ValidationUtil.checkCollection;
import static org.rakam.util.ValidationUtil.checkProject;
import static org.rakam.util.ValidationUtil.checkTableColumn;

public class PostgresqlMetastore
        extends AbstractMetastore
{
    private final LoadingCache<ProjectCollection, List<SchemaField>> schemaCache;
    private final LoadingCache<String, Set<String>> collectionCache;
    private final JDBCPoolDataSource connectionPool;

    @Inject
    public PostgresqlMetastore(@Named("store.adapter.postgresql") JDBCPoolDataSource connectionPool, EventBus eventBus, FieldDependencyBuilder.FieldDependency fieldDependency)
    {
        super(fieldDependency, eventBus);
        this.connectionPool = connectionPool;

        schemaCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build(new CacheLoader<ProjectCollection, List<SchemaField>>()
        {
            @Override
            public List<SchemaField> load(ProjectCollection key)
                    throws Exception
            {
                try (Connection conn = connectionPool.getConnection()) {
                    List<SchemaField> schema = getSchema(conn, key.project, key.collection);
                    if (schema == null) {
                        return ImmutableList.of();
                    }
                    return schema;
                }
            }
        });

        collectionCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build(new CacheLoader<String, Set<String>>()
        {
            @Override
            public Set<String> load(String project)
                    throws Exception
            {
                try (Connection conn = connectionPool.getConnection()) {
                    HashSet<String> tables = new HashSet<>();

                    ResultSet tableRs = conn.getMetaData().getTables("", project, null, new String[] {"TABLE"});
                    while (tableRs.next()) {
                        String tableName = tableRs.getString("table_name");

                        if (!tableName.startsWith("_")) {
                            tables.add(tableName);
                        }
                    }

                    return tables;
                }
            }
        });

        super.checkExistingSchema();
    }

    @Override
    public Map<String, List<SchemaField>> getCollections(String project)
    {
        try {
            return collectionCache.get(project).stream()
                    .collect(Collectors.toMap(c -> c, collection ->
                            getCollection(project, collection)));
        }
        catch (ExecutionException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public Set<String> getCollectionNames(String project)
    {
        try {
            return collectionCache.get(project);
        }
        catch (ExecutionException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void createProject(String project)
    {
        checkProject(project);

        if (project.equals("information_schema")) {
            throw new IllegalArgumentException("information_schema is a reserved name for Postgresql backend.");
        }
        try (Connection connection = connectionPool.getConnection()) {
            final Statement statement = connection.createStatement();
            statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS " + project);
            statement.executeUpdate(String.format("CREATE OR REPLACE FUNCTION %s.to_unixtime(timestamp) RETURNS double precision AS 'select extract(epoch from $1)' LANGUAGE SQL IMMUTABLE RETURNS NULL ON NULL INPUT", project));
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }

        super.onCreateProject(project);
    }

    @Override
    public Set<String> getProjects()
    {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        try (Connection connection = connectionPool.getConnection()) {
            ResultSet schemas = connection.getMetaData().getSchemas();
            while (schemas.next()) {
                String tableSchem = schemas.getString("table_schem");
                if (!tableSchem.equals("information_schema") && !tableSchem.startsWith("pg_") && !tableSchem.equals("public")) {
                    builder.add(tableSchem);
                }
            }
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
        return builder.build();
    }

    @Override
    public List<SchemaField> getCollection(String project, String collection)
    {
        try {
            return schemaCache.get(new ProjectCollection(project, collection));
        }
        catch (ExecutionException e) {
            throw Throwables.propagate(e);
        }
    }

    private List<SchemaField> getSchema(Connection connection, String project, String collection)
            throws SQLException
    {
        List<SchemaField> schemaFields = Lists.newArrayList();
        ResultSet dbColumns = connection.getMetaData().getColumns("", project, collection.replaceAll("%", "\\\\%").replaceAll("_", "\\\\_"), null);
        while (dbColumns.next()) {
            String columnName = dbColumns.getString("COLUMN_NAME");
            FieldType fieldType;
            try {
                fieldType = fromSql(dbColumns.getInt("DATA_TYPE"), dbColumns.getString("TYPE_NAME"));
            }
            catch (IllegalStateException e) {
                continue;
            }
            schemaFields.add(new SchemaField(columnName, fieldType));
        }
        return schemaFields.isEmpty() ? null : schemaFields;
    }

    @Override
    public List<SchemaField> getOrCreateCollectionFields(String project, String collection, Set<SchemaField> fields)
            throws NotExistsException
    {
        List<SchemaField> currentFields = new ArrayList<>();
        String query;
        Runnable task;

        try (Connection connection = connectionPool.getConnection()) {
            connection.setAutoCommit(false);
            ResultSet columns = connection.getMetaData().getColumns("", project, collection.replaceAll("%", "\\\\%").replaceAll("_", "\\\\_"), null);
            HashSet<String> strings = new HashSet<>();
            while (columns.next()) {
                String colName = columns.getString("COLUMN_NAME");
                strings.add(colName);
                currentFields.add(new SchemaField(colName, fromSql(columns.getInt("DATA_TYPE"), columns.getString("TYPE_NAME"))));
            }

            List<SchemaField> schemaFields = fields.stream().filter(f -> !strings.contains(f.getName())).collect(Collectors.toList());
            if (currentFields.isEmpty()) {
                if (!getProjects().contains(project)) {
                    throw new NotExistsException("Project");
                }
                String queryEnd = schemaFields.stream()
                        .map(f -> {
                            currentFields.add(f);
                            return f;
                        })
                        .map(f -> format("%s %s NULL", checkCollection(f.getName()), toSql(f.getType())))
                        .collect(Collectors.joining(", "));
                if (queryEnd.isEmpty()) {
                    return currentFields;
                }
                query = format("CREATE TABLE \"%s\".%s (%s)", project, checkCollection(collection), queryEnd);
                task = () -> super.onCreateCollection(project, collection, schemaFields);
            }
            else {
                String queryEnd = schemaFields.stream()
                        .map(f -> {
                            currentFields.add(f);
                            return f;
                        })
                        .map(f -> format("ADD COLUMN %s %s NULL", checkTableColumn(f.getName()), toSql(f.getType())))
                        .collect(Collectors.joining(", "));
                if (queryEnd.isEmpty()) {
                    return currentFields;
                }
                query = format("ALTER TABLE \"%s\".\"%s\" %s", project, collection, queryEnd);
                task = () -> super.onCreateCollectionField(project, collection, schemaFields);
            }

            connection.createStatement().execute(query);
            connection.commit();
            connection.setAutoCommit(true);
            schemaCache.put(new ProjectCollection(project, collection), currentFields);
        }
        catch (SQLException e) {
            // syntax error exception
            if (e.getSQLState().equals("42601") || e.getSQLState().equals("42939")) {
                throw new IllegalStateException("One of the column names is not valid because it collides with reserved keywords in Postgresql. : " +
                        (currentFields.stream().map(SchemaField::getName).collect(Collectors.joining(", "))) +
                        "See http://www.postgresql.org/docs/devel/static/sql-keywords-appendix.html");
            }
            else
                // column or table already exists
                if (e.getMessage().contains("already exists")) {
                    // TODO: should we try again until this operation is done successfully, what about infinite loops?
                    return getOrCreateCollectionFieldList(project, collection, fields);
                }
                else {
                    throw new IllegalStateException(e.getMessage());
                }
        }

        task.run();
        return currentFields;
    }

    @Override
    public Map<String, Stats> getStats(Collection<String> projects)
    {
        if (projects.isEmpty()) {
            return ImmutableMap.of();
        }

        try (Connection conn = connectionPool.getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT\n" +
                    "        nspname, sum(reltuples)\n" +
                    "        FROM pg_class C\n" +
                    "        LEFT JOIN pg_namespace N ON (N.oid = C.relnamespace)\n" +
                    "        WHERE nspname = any(?) AND relkind='r' AND relname != '_users' GROUP BY 1");
            ps.setArray(1, conn.createArrayOf("text", projects.toArray()));
            ResultSet resultSet = ps.executeQuery();
            Map<String, Stats> map = new HashMap<>();

            while (resultSet.next()) {
                map.put(resultSet.getString(1), new Stats(resultSet.getLong(2), null, null));
            }

            for (String project : projects) {
                map.computeIfAbsent(project, (k) -> new Stats(0L, null, null));
            }

            return map;
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    public HashSet<String> getViews(String project)
    {
        try (Connection conn = connectionPool.getConnection()) {
            HashSet<String> tables = new HashSet<>();

            ResultSet tableRs = conn.getMetaData().getTables("", project, null, new String[] {"VIEW"});
            while (tableRs.next()) {
                String tableName = tableRs.getString("table_name");

                if (!tableName.startsWith("_")) {
                    tables.add(tableName);
                }
            }

            return tables;
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void deleteProject(String project)
    {
        checkProject(project);
        try (Connection conn = connectionPool.getConnection()) {
            conn.createStatement().execute("DROP SCHEMA " + project + " CASCADE");
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
        super.onDeleteProject(project);
    }

    public static String toSql(FieldType type)
    {
        switch (type) {
            case INTEGER:
                return "INT";
            case DECIMAL:
                return "DECIMAL";
            case LONG:
                return "BIGINT";
            case STRING:
                return "TEXT";
            case BOOLEAN:
            case DATE:
            case TIME:
            case TIMESTAMP:
                return type.name();
            case DOUBLE:
                return "DOUBLE PRECISION";
            default:
                if (type.isArray()) {
                    return toSql(type.getArrayElementType()) + "[]";
                }
                if (type.isMap()) {
//                    if(type == FieldType.MAP_STRING_STRING) {
//                        return "hstore";
//                    }
                    return "jsonb";
                }
                throw new IllegalStateException("sql type couldn't converted to fieldtype");
        }
    }

    public static FieldType fromSql(int sqlType, String typeName)
    {
        return fromSql(sqlType, typeName, name -> {
            if (name.startsWith("_")) {
                if (name.startsWith("_int")) {
                    return FieldType.ARRAY_LONG;
                }
                if (name.equals("_text") || name.equals("_varchar")) {
                    return FieldType.ARRAY_STRING;
                }
                if (name.startsWith("_float")) {
                    return FieldType.ARRAY_DOUBLE;
                }
            }
            if (name.equals("jsonb")) {
                return FieldType.MAP_STRING;
            }
            if (name.equals("json")) {
                return FieldType.STRING;
            }
            if (name.equals("unknown")) {
                return FieldType.STRING;
            }

            throw new UnsupportedOperationException(String.format("type '%s' is not supported.", typeName));
        });
    }

    public static FieldType fromSql(int sqlType, String typeName, Function<String, FieldType> arrayTypeNameMapper)
    {
        switch (sqlType) {
            case Types.BIGINT:
            case Types.NUMERIC:
            case Types.REAL:
                return FieldType.LONG;
            case Types.TINYINT:
            case Types.INTEGER:
            case Types.SMALLINT:
                return FieldType.INTEGER;
            case Types.DECIMAL:
                return FieldType.DECIMAL;
            case Types.BOOLEAN:
            case Types.BIT:
                return FieldType.BOOLEAN;
            case Types.DATE:
                return FieldType.DATE;
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return FieldType.TIMESTAMP;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return FieldType.TIME;
            case Types.DOUBLE:
            case Types.FLOAT:
                return FieldType.DOUBLE;
            case Types.LONGVARCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.VARCHAR:
                return FieldType.STRING;
            default:
                return arrayTypeNameMapper.apply(typeName);
        }
    }
}
