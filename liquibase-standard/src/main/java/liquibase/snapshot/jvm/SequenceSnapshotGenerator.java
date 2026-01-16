package liquibase.snapshot.jvm;

import liquibase.CatalogAndSchema;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.database.core.*;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.executor.ExecutorService;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.InvalidExampleException;
import liquibase.snapshot.SnapshotIdService;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.RawParameterizedSqlStatement;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Sequence;
import liquibase.structure.core.Table;
import liquibase.structure.core.Column;

import java.util.Locale;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Snapshot generator for a SEQUENCE object in a JDBC-accessible database
 */
public class SequenceSnapshotGenerator extends JdbcSnapshotGenerator {

    public SequenceSnapshotGenerator() {
        super(Sequence.class, new Class[]{Schema.class});
    }

    @Override
    protected void addTo(DatabaseObject foundObject, DatabaseSnapshot snapshot) throws DatabaseException, InvalidExampleException {
        if (!(foundObject instanceof Schema) || !snapshot.getDatabase().supports(Sequence.class)) {
            return;
        }
        Schema schema = (Schema) foundObject;
        Database database = snapshot.getDatabase();

        //noinspection unchecked
        List<Map<String, ?>> sequences = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database).queryForList(getSelectSequenceStatement(schema, database));

        if (sequences != null) {
            for (Map<String, ?> sequence : sequences) {
                Sequence seq = mapToSequence(sequence, schema, database);

                if (isPurePostgresSerialSequence(database, snapshot, seq)) {
                    continue; // ignore pure SERIAL sequences
                }
                schema.addDatabaseObject(seq);
            }
        }
    }

    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot) throws DatabaseException {
        if (example.getSnapshotId() != null) {
            return example;
        }
        Database database = snapshot.getDatabase();
        List<Map<String, ?>> sequences;
        if (database instanceof Db2zDatabase) {
            sequences = Scope.getCurrentScope().getSingleton(ExecutorService.class)
                    .getExecutor("jdbc", database)
                    .queryForList(getSelectSequenceStatement(example.getSchema(), database));
            return getSequences(example, database, sequences);
        } else {
            if (example.getAttribute("liquibase-complete", false)) {
                example.setSnapshotId(SnapshotIdService.getInstance().generateId());
                example.setAttribute("liquibase-complete", null);
                return example;
            }

            if (!database.supports(Sequence.class)) {
                return null;
            }
            sequences = Scope.getCurrentScope().getSingleton(ExecutorService.class)
                    .getExecutor("jdbc", database)
                    .queryForList(getSelectSequenceStatement(example.getSchema(), database));
            return getSequences(example, database, sequences);
        }
    }

    private DatabaseObject getSequences(DatabaseObject example, Database database, List<Map<String, ?>> sequences) {
        for (Map<String, ?> sequenceRow : sequences) {
            String name = cleanNameFromDatabase((String) sequenceRow.get("SEQUENCE_NAME"), database);
            if (((database.isCaseSensitive() && name.equals(example.getName())) || (!database.isCaseSensitive() &&
                name.equalsIgnoreCase(example.getName())))) {
                return mapToSequence(sequenceRow, example.getSchema(), database);
            }
        }
        return null;
    }

    private Sequence mapToSequence(Map<String, ?> sequenceRow, Schema schema, Database database) {
        String name = cleanNameFromDatabase((String) sequenceRow.get("SEQUENCE_NAME"), database);
        String ownedTable = (String) sequenceRow.get("OWNED_TABLE");
        String ownedColumn = (String) sequenceRow.get("OWNED_COLUMN");
        Sequence seq = new Sequence();
        seq.setName(name);
        seq.setSchema(schema);
        seq.setStartValue(toBigInteger(sequenceRow.get("START_VALUE"), database));
        seq.setMinValue(toBigInteger(sequenceRow.get("MIN_VALUE"), database));
        seq.setMaxValue(toBigInteger(sequenceRow.get("MAX_VALUE"), database));
        seq.setCacheSize(toBigInteger(sequenceRow.get("CACHE_SIZE"), database));
        seq.setIncrementBy(toBigInteger(sequenceRow.get("INCREMENT_BY"), database));
        seq.setWillCycle(toBoolean(sequenceRow.get("WILL_CYCLE"), database));
        seq.setOrdered(toBoolean(sequenceRow.get("IS_ORDERED"), database));
        if (! (database instanceof CockroachDatabase)) {
            seq.setDataType((String) sequenceRow.get("SEQ_TYPE"));
        }
        seq.setAttribute("liquibase-complete", true);

        if (ownedTable != null && ownedColumn != null) {
            seq.setAttribute("ownedByTable", ownedTable);
            seq.setAttribute("ownedByColumn", ownedColumn);
        }

        String dependencyType = (String) sequenceRow.get("DEPENDENCY_TYPE");
        if (dependencyType != null) {
            seq.setAttribute("dependencyType", dependencyType);
        }

        return seq;
    }

    protected Boolean toBoolean(Object value, Database database) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;

        String valueAsString = value.toString().replace("'", "");
        return "true".equalsIgnoreCase(valueAsString)
                || "y".equalsIgnoreCase(valueAsString)
                || "1".equalsIgnoreCase(valueAsString)
                || "t".equalsIgnoreCase(valueAsString);
    }

    protected BigInteger toBigInteger(Object value, Database database) {
        if (value == null) return null;
        if (value instanceof BigInteger) return (BigInteger) value;
        return new BigInteger(value.toString());
    }

    protected SqlStatement getSelectSequenceStatement (Schema schema, Database database) {
        if (database instanceof DB2Database) {
            if (database.getDatabaseProductName().startsWith("DB2 UDB for AS/400")) {
                return new RawParameterizedSqlStatement("SELECT SEQNAME AS SEQUENCE_NAME FROM QSYS2.SYSSEQUENCES WHERE SEQSCHEMA = ?", schema.getCatalogName());
            }
            return new RawParameterizedSqlStatement("SELECT SEQNAME AS SEQUENCE_NAME FROM SYSCAT.SEQUENCES WHERE SEQTYPE='S' AND SEQSCHEMA = ?", schema.getCatalogName());
        } else if (database instanceof Db2zDatabase) {
            StringBuilder sql = new StringBuilder("SELECT NAME AS SEQUENCE_NAME, START AS START_VALUE, MINVALUE AS MIN_VALUE, MAXVALUE AS MAX_VALUE, ")
                    .append("CACHE AS CACHE_SIZE, INCREMENT AS INCREMENT_BY, CYCLE AS WILL_CYCLE, ORDER AS IS_ORDERED FROM SYSIBM.SYSSEQUENCES WHERE SEQTYPE = 'S' AND SCHEMA = ?");
            return new RawParameterizedSqlStatement ( sql.toString(), schema.getCatalogName());
        } else if (database instanceof DerbyDatabase) {
            StringBuilder sql = new StringBuilder("SELECT seq.SEQUENCENAME AS SEQUENCE_NAME FROM SYS.SYSSEQUENCES seq, SYS.SYSSCHEMAS sch WHERE sch.SCHEMANAME = ? AND sch.SCHEMAID = seq.SCHEMAID");
            return new RawParameterizedSqlStatement( sql.toString(), new CatalogAndSchema(null, schema.getName()).customize(database).getSchemaName());
        } else if (database instanceof FirebirdDatabase) {
            return new RawParameterizedSqlStatement("SELECT TRIM(RDB$GENERATOR_NAME) AS SEQUENCE_NAME FROM RDB$GENERATORS WHERE RDB$SYSTEM_FLAG IS NULL OR RDB$SYSTEM_FLAG = 0");
        } else if (database instanceof H2Database) {
            try {
                if (database.getDatabaseMajorVersion() <= 1) {
                    return new RawParameterizedSqlStatement("SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ? AND IS_GENERATED=FALSE", schema.getName());
                }
            } catch (DatabaseException e) {
                Scope.getCurrentScope().getLog(getClass()).fine("Cannot determine h2 version in order to generate sequence snapshot query");
            }
            return new RawParameterizedSqlStatement("SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ?", schema.getName());
        } else if (database instanceof HsqlDatabase) {
            return new RawParameterizedSqlStatement("SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SYSTEM_SEQUENCES WHERE SEQUENCE_SCHEMA = ?", schema.getName());
        } else if (database instanceof InformixDatabase) {
            return new RawParameterizedSqlStatement("SELECT tabname AS SEQUENCE_NAME FROM systables t, syssequences s WHERE s.tabid = t.tabid AND t.owner = ?", schema.getName());
        } else if (database instanceof OracleDatabase) {
            String catalogName = schema.getCatalogName();
            if (catalogName == null || catalogName.isEmpty()) {
                catalogName = database.getDefaultCatalogName();
            }
            StringBuilder sql = new StringBuilder("SELECT SEQUENCE_NAME, MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG AS WILL_CYCLE, ORDER_FLAG AS IS_ORDERED, LAST_NUMBER as START_VALUE, CACHE_SIZE ")
                    .append(String.format("FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = '%s'", catalogName));
            return new RawParameterizedSqlStatement(sql.toString());
        } else if (database instanceof PostgresDatabase) {
            int version = 9;
            try { version = database.getDatabaseMajorVersion(); } catch (Exception ignore) {}
            String schemaName = schema.getName() == null ? database.getDefaultSchemaName() : schema.getName();

            if (version < 10) {
                return new RawParameterizedSqlStatement("SELECT c.relname AS \"SEQUENCE_NAME\" FROM pg_class c JOIN pg_namespace ns ON c.relnamespace = ns.oid WHERE c.relkind = 'S' AND ns.nspname = ?", schemaName);
            } else {
                String sql = "SELECT c.relname AS \"SEQUENCE_NAME\", s.seqmin AS \"MIN_VALUE\", s.seqmax AS \"MAX_VALUE\", " +
                        "s.seqincrement AS \"INCREMENT_BY\", s.seqcycle AS \"WILL_CYCLE\", s.seqstart AS \"START_VALUE\", " +
                        "s.seqcache AS \"CACHE_SIZE\", pg_catalog.format_type(s.seqtypid, NULL) AS \"SEQ_TYPE\", " +
                        "ref_c.relname AS \"OWNED_TABLE\", a.attname AS \"OWNED_COLUMN\", d.deptype AS \"DEPENDENCY_TYPE\" " +
                        "FROM pg_class c JOIN pg_namespace ns ON c.relnamespace = ns.oid JOIN pg_sequence s ON c.oid = s.seqrelid " +
                        "LEFT JOIN pg_depend d ON c.oid = d.objid AND d.deptype IN ('i', 'a', 'n') " +
                        "LEFT JOIN pg_class ref_c ON d.refobjid = ref_c.oid AND ref_c.relkind = 'r' " +
                        "LEFT JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid " +
                        "WHERE c.relkind = 'S' AND ns.nspname = ? " +
                        "AND (ref_c.relkind = 'r' OR d.objid IS NULL OR d.deptype IS NULL)";
                return new RawParameterizedSqlStatement(sql, schemaName);
            }
        } else if (database instanceof MSSQLDatabase) {
            return getMSSQLQuery(schema);
        } else if (database instanceof MariaDBDatabase) {
            StringJoiner j = new StringJoiner(" \n UNION\n");
            try {
                String sql = "select table_name AS SEQUENCE_NAME from information_schema.TABLES where TABLE_SCHEMA = ? and TABLE_TYPE = 'SEQUENCE' order by table_name;";
                List<Map<String, ?>> res = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database).queryForList(new RawParameterizedSqlStatement(sql, schema.getName()));
                if (res.isEmpty()) return new RawParameterizedSqlStatement("SELECT 'name' AS SEQUENCE_NAME from dual WHERE 1=0");
                for (Map<String, ?> e : res) {
                    String seqName = (String) e.get("SEQUENCE_NAME");
                    j.add(String.format("SELECT '%s' AS SEQUENCE_NAME, START_VALUE, MINIMUM_VALUE AS MIN_VALUE, MAXIMUM_VALUE AS MAX_VALUE, INCREMENT AS INCREMENT_BY, CYCLE_OPTION AS WILL_CYCLE FROM %s ", seqName, seqName));
                }
            } catch (DatabaseException e) {
                throw new UnexpectedLiquibaseException("Could not get list of sequences", e);
            }
            return new RawParameterizedSqlStatement(j.toString());
        } else if (database instanceof SybaseASADatabase) {
            String sql = "SELECT SEQUENCE_NAME, START_WITH AS START_VALUE, MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE AS WILL_CYCLE FROM SYS.SYSSEQUENCE s JOIN SYS.SYSUSER u ON s.OWNER = u.USER_ID WHERE u.USER_NAME = ?";
            return new RawParameterizedSqlStatement(sql, schema.getName());
        } else if (database.getClass().getName().contains("MaxDB")) {
            return new RawParameterizedSqlStatement("SELECT SEQUENCE_NAME, MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG AS WILL_CYCLE FROM sequences WHERE SCHEMANAME = ?", schema.getName());
        } else {
            throw new UnexpectedLiquibaseException("Don't know how to query for sequences on " + database);
        }
    }

    private static RawParameterizedSqlStatement getMSSQLQuery(Schema schema) {
        String sql = "SELECT SEQUENCE_NAME, START_VALUE, MINIMUM_VALUE AS MIN_VALUE, MAXIMUM_VALUE AS MAX_VALUE, INCREMENT AS INCREMENT_BY, CYCLE_OPTION AS WILL_CYCLE, IIF(DATA_TYPE = 'decimal', DATA_TYPE + '(' + CAST(NUMERIC_PRECISION AS VARCHAR) + ')', NULL) AS SEQ_TYPE FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ?";
        return new RawParameterizedSqlStatement(sql, schema.getName());
    }

    private boolean isPurePostgresSerialSequence(Database database, DatabaseSnapshot snapshot, Sequence seq) {
        if (!(database instanceof PostgresDatabase)) return false;

        String ownedTable = seq.getAttribute("ownedByTable", String.class);
        String ownedColumn = seq.getAttribute("ownedByColumn", String.class);

        // If it has no owner, it's an explicit sequence (keep it)
        if (ownedTable == null || ownedColumn == null) return false;

        // Check naming convention
        String standardName = (ownedTable + "_" + ownedColumn + "_seq").toLowerCase(Locale.ROOT);
        boolean matchesStandardName = seq.getName().toLowerCase(Locale.ROOT).equals(standardName);

        // If it's owned by a DIFFERENT table than its name suggests (your fix!), keep it
        if (!matchesStandardName) {
            return false;
        }

        // Now, for sequences that DO match the name (like serial_default_id_seq)
        // We check if the column actually uses it as a DEFAULT nextval(...)
        Table table = (Table) snapshot.get(new Table().setName(ownedTable).setSchema(seq.getSchema()));
        if (table == null) return false;

        Column column = table.getColumn(ownedColumn);
        if (column == null) return false;

        Object defaultValue = column.getDefaultValue();
        if (defaultValue == null) return false;

        // Use toString() which handles DatabaseFunction or String values
        String defaultExpr = defaultValue.toString().toLowerCase(Locale.ROOT);
        
        // If the column default looks like: nextval('serial_default_id_seq'::regclass)
        // then it's a "Pure" serial and we should return TRUE to ignore it.
        return defaultExpr.contains("nextval") && defaultExpr.contains(seq.getName().toLowerCase(Locale.ROOT));
    }
}
