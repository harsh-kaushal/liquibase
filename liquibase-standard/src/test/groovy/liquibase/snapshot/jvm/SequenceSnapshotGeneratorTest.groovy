package liquibase.snapshot.jvm

import liquibase.database.Database
import liquibase.database.core.OracleDatabase
import liquibase.database.core.PostgresDatabase
import liquibase.snapshot.DatabaseSnapshot
import liquibase.statement.SqlStatement
import liquibase.statement.core.RawParameterizedSqlStatement
import liquibase.structure.core.Column
import liquibase.structure.core.Schema
import liquibase.structure.core.Sequence
import liquibase.structure.core.Table
import spock.lang.Specification
import spock.lang.Unroll

class SequenceSnapshotGeneratorTest extends Specification {

    private final static DEFAULT_CATALOG_NAME = "DEFAULT_CATALOG_NAME"
    private final static DEFAULT_SCHEMA_NAME = "public"

    @Unroll
    def "When catalog on changeset is #changesetCatalog, the SEQUENCE_OWNER will be #expectedSequenceOwner"() {
        given:
        SequenceSnapshotGenerator sequenceSnapshotGenerator = new SequenceSnapshotGenerator()
        Database database = Mock(OracleDatabase)
        database.getDefaultCatalogName() >> DEFAULT_CATALOG_NAME
        Schema schema = Mock(Schema)
        schema.getCatalogName() >> changesetCatalog

        when:
        SqlStatement sqlStatement = sequenceSnapshotGenerator.getSelectSequenceStatement(schema, database)
        String sql = ((RawParameterizedSqlStatement) sqlStatement).getSql()

        then:
        String ownerEqualsClause = "SEQUENCE_OWNER = "
        String actualSequenceOwner = sql.substring(sql.indexOf(ownerEqualsClause) + ownerEqualsClause.size() + 1, sql.length() - 1)
        actualSequenceOwner == expectedSequenceOwner

        where:
        changesetCatalog | expectedSequenceOwner
        "ANY_STRING"     | "ANY_STRING"
        ""               | DEFAULT_CATALOG_NAME
        null             | DEFAULT_CATALOG_NAME
    }

    @Unroll
    def "When schema is '#schemaName', the return SQL schema name will be '#expectedSchemaName'"() {
        given:
        SequenceSnapshotGenerator sequenceSnapshotGenerator = new SequenceSnapshotGenerator()
        Database database = Mock(PostgresDatabase)
        database.getDefaultSchemaName() >> DEFAULT_SCHEMA_NAME

        when:
        Schema schema = Mock(Schema)
        schema.getName() >> schemaName

        then:
        SqlStatement sqlStatement = sequenceSnapshotGenerator.getSelectSequenceStatement(schema, database)
        String sql = sqlStatement.toString()
        sql.indexOf(expectedSchemaName) != -1

        where:
        schemaName | expectedSchemaName
        "mySchema"            | "mySchema"
        DEFAULT_SCHEMA_NAME   | DEFAULT_SCHEMA_NAME
        null                  | DEFAULT_SCHEMA_NAME
    }

    @Unroll
    def "isPurePostgresSerialSequence returns #expected for dependencyType #dependencyType"() {
        given:
        SequenceSnapshotGenerator sequenceSnapshotGenerator = new SequenceSnapshotGenerator()
        Database database = Mock(PostgresDatabase)
        DatabaseSnapshot snapshot = Mock(DatabaseSnapshot)

        Schema testSchema = new Schema(null, DEFAULT_SCHEMA_NAME)

        Sequence seq = new Sequence()
        seq.setName(sequenceName)
        seq.setSchema(testSchema)
        seq.setAttribute("dependencyType", dependencyType)
        seq.setAttribute("ownedByTable", ownedTable)
        seq.setAttribute("ownedByColumn", ownedColumn)

        Table table = new Table().setName(ownedTable).setSchema(testSchema)
        table.addColumn(new Column().setName(ownedColumn).setDefaultValue("nextval('" + sequenceName + "')"))

        when:
        boolean result = sequenceSnapshotGenerator.isPurePostgresSerialSequence(database, snapshot, seq)

        then:
        // snapshot.get() is only called when dependencyType is "s" AND the sequence name matches the SERIAL pattern
        if (dependencyType == "s" && sequenceName == "owner_a_id_seq") {
            1 * snapshot.get(_) >> table
        }

        expect:
        result == expected

        where:
        dependencyType | sequenceName         | ownedTable | ownedColumn | expected
        "s"            | "owner_a_id_seq"     | "owner_a"  | "id"        | true   // pure SERIAL sequence, safe to skip
        "n"            | "owner_a_id_seq"     | "owner_a"  | "id"        | false  // explicit ownership, must capture
        null           | "owner_a_id_seq"     | "owner_a"  | "id"        | false  // no dependency type, explicit sequence
        "a"            | "owner_a_id_seq"     | "owner_a"  | "id"        | false  // auto-generated, must capture
        "s"            | "custom_seq"         | "owner_a"  | "id"        | false  // doesn't match SERIAL naming
    }

    def "isPurePostgresSerialSequence returns false for non-Postgres databases"() {
        given:
        SequenceSnapshotGenerator sequenceSnapshotGenerator = new SequenceSnapshotGenerator()
        Database database = Mock(OracleDatabase)
        DatabaseSnapshot snapshot = Mock(DatabaseSnapshot)

        Schema testSchema = new Schema(null, "test")

        Sequence seq = new Sequence()
        seq.setName("test_seq")
        seq.setSchema(testSchema)

        when:
        boolean result = sequenceSnapshotGenerator.isPurePostgresSerialSequence(database, snapshot, seq)

        then:
        result == false
    }

}
