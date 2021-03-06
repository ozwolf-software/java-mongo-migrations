package net.ozwolf.mongo.migrations;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.github.fakemongo.junit.FongoRule;
import net.ozwolf.mongo.migrations.exception.MongoMigrationsFailureException;
import net.ozwolf.mongo.migrations.internal.domain.Migration;
import net.ozwolf.mongo.migrations.internal.domain.MigrationStatus;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.jongo.Jongo;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static net.ozwolf.mongo.migrations.matchers.LoggingMatchers.loggedMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

public class MongoMigrationsIntegrationTest {
    @Rule
    public FongoRule FONGO = new FongoRule("migration_test", false);

    private Jongo jongo;

    private final static Logger LOGGER = (Logger) LoggerFactory.getLogger(MongoMigrations.class);
    private final static String SCHEMA_VERSION_COLLECTION = "_schema_version";

    @SuppressWarnings("unchecked")
    private final Appender<ILoggingEvent> appender = mock(Appender.class);
    private final ArgumentCaptor<ILoggingEvent> captor = ArgumentCaptor.forClass(ILoggingEvent.class);

    @Before
    public void setUp() throws UnknownHostException {
        this.jongo = new Jongo(FONGO.getDB("migration_test"));
        jongo.getCollection(SCHEMA_VERSION_COLLECTION).drop();
        jongo.getCollection("first_migrations").drop();
        jongo.getCollection("second_migrations").drop();
        Migration migration100 = new Migration("1.0.0", "Applied migration", DateTime.parse("2014-12-05T09:00:00.000+1100"), DateTime.parse("2014-12-05T09:00:02.000+1100"), MigrationStatus.Successful, null);
        Migration migration101 = new Migration("1.0.1", "Another applied migration", DateTime.parse("2014-12-05T09:10:00.000+1100"), DateTime.parse("2014-12-05T09:11:00.000+1100"), MigrationStatus.Successful, null);
        Migration migration102 = new Migration("1.0.2", "Failed last time migration", DateTime.parse("2014-12-05T09:11:01.000+1100"), null, MigrationStatus.Failed, "Something went horribly wrong!");

        jongo.getCollection(SCHEMA_VERSION_COLLECTION).save(migration100);
        jongo.getCollection(SCHEMA_VERSION_COLLECTION).save(migration101);
        jongo.getCollection(SCHEMA_VERSION_COLLECTION).save(migration102);

        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        LOGGER.setLevel(Level.INFO);
        LOGGER.addAppender(appender);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRetryFailedMigrationsAndApplyNewOnesAndCompleteSuccessfullyUsingDBFactory() throws MongoMigrationsFailureException {
        testApplyingMigrations(() -> new MongoMigrations(dbFactory()));
    }

    @Test
    public void shouldRetryFailedMigrationsAndApplyNewOnesAndCompleteSuccessfullyAndLeaveConnectionOpen() throws MongoMigrationsFailureException {
        testApplyingMigrations(() -> new MongoMigrations(this.jongo));

        assertThat(this.jongo.getCollection(SCHEMA_VERSION_COLLECTION).count(), greaterThan(0L));
    }

    @Test
    public void shouldHandleZeroPendingMigrations() throws MongoMigrationsFailureException {
        MongoMigrations migrations = new MongoMigrations(dbFactory());
        migrations.setSchemaVersionCollection(SCHEMA_VERSION_COLLECTION);
        migrations.migrate(commands(new V1_0_0__AppliedMigration()));

        verify(appender, atLeastOnce()).doAppend(captor.capture());

        List<ILoggingEvent> events = captor.getAllValues();

        assertThat(events, hasItem(loggedMessage("DATABASE MIGRATIONS")));
        assertThat(events, hasItem(loggedMessage("   No migrations to apply.")));
    }

    @Test
    public void shouldHandleZeroCommandsProvided() throws MongoMigrationsFailureException {
        MongoMigrations migrations = new MongoMigrations(dbFactory());
        migrations.setSchemaVersionCollection(SCHEMA_VERSION_COLLECTION);
        migrations.migrate(commands());

        verify(appender, atLeastOnce()).doAppend(captor.capture());

        List<ILoggingEvent> events = captor.getAllValues();

        assertThat(events, hasItem(loggedMessage("DATABASE MIGRATIONS")));
        assertThat(events, hasItem(loggedMessage("   No migrations to apply.")));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFailMigrationsOnLastMigration() {
        List<MigrationCommand> commands = commands(
                new V1_0_0__AppliedMigration(),
                new V2_0_0__BrandNewMigration(),
                new V2_0_0_1__IWillAlwaysFail(),
                new V1_0_2__FailedLastTimeMigration(),
                new V1_0_1__AnotherAppliedMigration()
        );

        try {
            MongoMigrations migrations = new MongoMigrations(dbFactory());
            migrations.setSchemaVersionCollection(SCHEMA_VERSION_COLLECTION);
            migrations.migrate(commands);

            fail(String.format("Expected exception of [ %s ], but got [ none ]", MongoMigrationsFailureException.class.getSimpleName()));
        } catch (Exception e) {
            if (!(e instanceof MongoMigrationsFailureException))
                fail(String.format("Expected exception of [ %s ], but got [ %s ]", MongoMigrationsFailureException.class.getSimpleName(), e.getClass().getSimpleName()));

            assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
            assertThat(e.getMessage(), is("Mongo migrations failed: This is an exception that never ends!"));

            validateMigrations(
                    migrationOf("1.0.0", MigrationStatus.Successful),
                    migrationOf("1.0.1", MigrationStatus.Successful),
                    migrationOf("1.0.2", MigrationStatus.Successful),
                    migrationOf("2.0.0", MigrationStatus.Successful),
                    migrationOf("2.0.0.1", MigrationStatus.Failed)
            );

            assertThat(jongo.getCollection("first_migrations").findOne("{'name':'Homer Simpson'}").map(r -> (Integer) r.get("age")), is(37));
            assertThat(jongo.getCollection("second_migrations").findOne("{'town':'Shelbyville'}").map(r -> (String) r.get("country")), is("United States"));

            verify(appender, atLeastOnce()).doAppend(captor.capture());

            List<ILoggingEvent> events = captor.getAllValues();
            assertThat(events, hasItem(loggedMessage("DATABASE MIGRATIONS")));
            assertThat(events, hasItem(loggedMessage("       Database : [ migration_test ]")));
            assertThat(events, hasItem(loggedMessage(" Schema Version : [ _schema_version ]")));
            assertThat(events, hasItem(loggedMessage("         Action : [ migrate ]")));
            assertThat(events, hasItem(loggedMessage("Current Version : [ 1.0.1 ]")));
            assertThat(events, hasItem(loggedMessage("       Applying : [ 1.0.2 ] -> [ 2.0.0.1 ]")));
            assertThat(events, hasItem(loggedMessage("     Migrations :")));
            assertThat(events, hasItem(loggedMessage("       1.0.2 : Failed last time migration")));
            assertThat(events, hasItem(loggedMessage("       2.0.0 : Brand new migration")));
            assertThat(events, hasItem(loggedMessage("       2.0.0.1 : I will always fail")));
            assertThat(events, hasItem(loggedMessage("Error applying migration(s)")));
            assertThat(events, hasItem(loggedMessage(">>> [ 2 ] migrations applied in [ 0 seconds ] <<<")));
        }
    }

    @Test
    public void shouldReportOnMigrations() throws MongoMigrationsFailureException {
        List<MigrationCommand> commands = commands(
                new V1_0_0__AppliedMigration(),
                new V2_0_0__BrandNewMigration(),
                new V2_0_0_1__IWillAlwaysFail(),
                new V1_0_2__FailedLastTimeMigration(),
                new V1_0_1__AnotherAppliedMigration()
        );

        MongoMigrations migrations = new MongoMigrations(dbFactory());
        migrations.setSchemaVersionCollection(SCHEMA_VERSION_COLLECTION);
        migrations.status(commands);

        verify(appender, atLeastOnce()).doAppend(captor.capture());
        List<ILoggingEvent> events = captor.getAllValues();
        assertThat(events, hasItem(loggedMessage("DATABASE MIGRATIONS")));
        assertThat(events, hasItem(loggedMessage("       Database : [ migration_test ]")));
        assertThat(events, hasItem(loggedMessage(" Schema Version : [ _schema_version ]")));
        assertThat(events, hasItem(loggedMessage("         Action : [ status ]")));
        assertThat(events, hasItem(loggedMessage("Current Version : [ 1.0.1 ]")));
        assertThat(events, hasItem(loggedMessage("     Migrations :")));
        assertThat(events, hasItem(loggedMessage("       1.0.0 : Applied migration")));
        assertThat(events, hasItem(loggedMessage(String.format("          Tags: [ Successful ] [ %s ] [ 2 seconds ]", toTimeStamp("2014-12-05T09:00:00+1100")))));
        assertThat(events, hasItem(loggedMessage("       1.0.1 : Another applied migration")));
        assertThat(events, hasItem(loggedMessage(String.format("          Tags: [ Successful ] [ %s ] [ 60 seconds ]", toTimeStamp("2014-12-05T09:10:00+1100")))));
        assertThat(events, hasItem(loggedMessage("       1.0.2 : Failed last time migration")));
        assertThat(events, hasItem(loggedMessage(String.format("          Tags: [ Failed ] [ %s ] [ ERROR: Something went horribly wrong! ]", toTimeStamp("2014-12-05T09:11:01+1100")))));
        assertThat(events, hasItem(loggedMessage("       2.0.0 : Brand new migration")));
        assertThat(events, hasItem(loggedMessage("          Tags: [ Pending ]")));
        assertThat(events, hasItem(loggedMessage("       2.0.0.1 : I will always fail")));
        assertThat(events, hasItem(loggedMessage("          Tags: [ Pending ]")));
    }

    private void testApplyingMigrations(Supplier<MongoMigrations> migrationsProvider) throws MongoMigrationsFailureException {
        List<MigrationCommand> commands = commands(
                new V1_0_0__AppliedMigration(),
                new V2_0_0__BrandNewMigration(),
                new V1_0_2__FailedLastTimeMigration(),
                new V1_0_1__AnotherAppliedMigration()
        );

        MongoMigrations migrations = migrationsProvider.get();
        migrations.setSchemaVersionCollection(SCHEMA_VERSION_COLLECTION);
        migrations.migrate(commands);

        validateMigrations(
                migrationOf("1.0.0", MigrationStatus.Successful),
                migrationOf("1.0.1", MigrationStatus.Successful),
                migrationOf("1.0.2", MigrationStatus.Successful),
                migrationOf("2.0.0", MigrationStatus.Successful)
        );

        assertThat(jongo.getCollection("first_migrations").findOne("{'name':'Homer Simpson'}").map(r -> (Integer) r.get("age")), is(37));
        assertThat(jongo.getCollection("second_migrations").findOne("{'town':'Shelbyville'}").map(r -> (String) r.get("country")), is("United States"));

        verify(appender, atLeastOnce()).doAppend(captor.capture());

        List<ILoggingEvent> events = captor.getAllValues();
        assertThat(events, hasItem(loggedMessage("DATABASE MIGRATIONS")));
        assertThat(events, hasItem(loggedMessage("       Database : [ migration_test ]")));
        assertThat(events, hasItem(loggedMessage(" Schema Version : [ _schema_version ]")));
        assertThat(events, hasItem(loggedMessage("         Action : [ migrate ]")));
        assertThat(events, hasItem(loggedMessage("Current Version : [ 1.0.1 ]")));
        assertThat(events, hasItem(loggedMessage("       Applying : [ 1.0.2 ] -> [ 2.0.0 ]")));
        assertThat(events, hasItem(loggedMessage("     Migrations :")));
        assertThat(events, hasItem(loggedMessage("       1.0.2 : Failed last time migration")));
        assertThat(events, hasItem(loggedMessage("       2.0.0 : Brand new migration")));
        assertThat(events, hasItem(loggedMessage(">>> [ 2 ] migrations applied in [ 0 seconds ] <<<")));
    }

    private String toTimeStamp(String timeStamp) {
        return DateTime.parse(timeStamp).toDateTime(DateTimeZone.getDefault()).toString("yyyy-MM-dd HH:mm:ss");
    }

    @SafeVarargs
    private final void validateMigrations(TypeSafeMatcher<Migration>... migrations) {
        List<Migration> records = new ArrayList<>();
        jongo.getCollection(SCHEMA_VERSION_COLLECTION).find().as(Migration.class).forEach(records::add);
        assertThat(records.size(), is(migrations.length));
        for (TypeSafeMatcher<Migration> checker : migrations)
            assertThat(records, hasItem(checker));
    }

    private static List<MigrationCommand> commands(MigrationCommand... commands) {
        return Arrays.asList(commands);
    }


    @SuppressWarnings("deprecation")
    private MongoMigrations.DBFactory dbFactory() {
        return () -> FONGO.getMongo().getDB("migration_test");
    }

    @SuppressWarnings("WeakerAccess")
    public static class V1_0_0__AppliedMigration extends MigrationCommand {
        @Override
        public void migrate(Jongo jongo) {
            throw new UnsupportedOperationException("This should never be called!");
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static class V1_0_1__AnotherAppliedMigration extends MigrationCommand {
        @Override
        public void migrate(Jongo jongo) {
            throw new UnsupportedOperationException("This should never be called!");
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static class V1_0_2__FailedLastTimeMigration extends MigrationCommand {
        @Override
        public void migrate(Jongo jongo) {
            jongo.getCollection("first_migrations").insert("{'name': 'Homer Simpson', 'age': 37}");
            jongo.getCollection("first_migrations").insert("{'name': 'Marge Simpson', 'age': 36}");
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static class V2_0_0__BrandNewMigration extends MigrationCommand {
        @Override
        public void migrate(Jongo jongo) {
            jongo.getCollection("second_migrations").insert("{'town': 'Springfield', 'country': 'United States'}");
            jongo.getCollection("second_migrations").insert("{'town': 'Shelbyville', 'country': 'United States'}");
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static class V2_0_0_1__IWillAlwaysFail extends MigrationCommand {
        @Override
        public void migrate(Jongo jongo) {
            throw new IllegalArgumentException("This is an exception that never ends!");
        }
    }

    private static TypeSafeMatcher<Migration> migrationOf(final String version, final MigrationStatus status) {
        return new TypeSafeMatcher<Migration>() {
            @Override
            protected boolean matchesSafely(Migration record) {
                return record.getVersion().equals(version) && record.getStatus() == status;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText(String.format("version = <%s>, status = <%s>", version, status));
            }
        };
    }
}