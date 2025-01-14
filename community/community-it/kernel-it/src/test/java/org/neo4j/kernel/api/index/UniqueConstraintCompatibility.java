/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.index;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.impl.index.schema.NameOverridingStoreMigrationParticipant;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.lock.Lock;
import org.neo4j.lock.LockService;
import org.neo4j.lock.LockType;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.storageengine.migration.StoreMigrationParticipant;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.default_schema_provider;

abstract class UniqueConstraintCompatibility extends PropertyIndexProviderCompatibilityTestSuite.Compatibility
{
    private DatabaseManagementService managementService;

    UniqueConstraintCompatibility( PropertyIndexProviderCompatibilityTestSuite testSuite )
    {
        super( testSuite, testSuite.uniqueIndexPrototype() );
    }

    /*
     * There are a quite a number of permutations to consider, when it comes to unique
     * constraints.
     *
     * We have two supported providers:
     *  - InMemoryIndexProvider
     *  - LuceneIndexProvider
     *
     * An index can be in a number of states, two of which are interesting:
     *  - ONLINE: the index is in active duty
     *  - POPULATING: the index is in the process of being created and filled with data
     *
     * Further more, indexes that are POPULATING have two ways of ingesting data:
     *  - Through add()'ing existing data
     *  - Through NodePropertyUpdates sent to a "populating updater"
     *
     * Then, when we add data to an index, two outcomes are possible, depending on the
     * data:
     *  - The index does not contain an equivalent value, and the entity id is added to
     *    the index.
     *  - The index already contains an equivalent value, and the addition is rejected.
     *
     * And when it comes to observing these outcomes, there are a whole bunch of
     * interesting transaction states that are worth exploring:
     *  - Adding a label to a node
     *  - Removing a label from a node
     *  - Combinations of adding and removing a label, ultimately adding it
     *  - Combinations of adding and removing a label, ultimately removing it
     *  - Adding a property
     *  - Removing a property
     *  - Changing an existing property
     *  - Combinations of adding and removing a property, ultimately adding it
     *  - Combinations of adding and removing a property, ultimately removing it
     *  - Likewise combinations of adding, removing and changing a property
     *
     * To make matters worse, we index a number of different types, some of which may or
     * may not collide in the index because of coercion. We need to make sure that the
     * indexes deal with these values correctly. And we also have the ways in which these
     * operations can be performed in any number of transactions, for instance, if all
     * the conflicting nodes were added in the same transaction or not.
     *
     * All in all, we have many cases to test for!
     *
     * Still, it is possible to boil things down a little bit, because there are fewer
     * outcomes than there are scenarios that lead to those outcomes. With a bit of
     * luck, we can abstract over the scenarios that lead to those outcomes, and then
     * only write a test per outcome. These are the outcomes I see:
     *  - Populating an index succeeds
     *  - Populating an index fails because of the existing data
     *  - Populating an index fails because of updates to data
     *  - Adding to an online index succeeds
     *  - Adding to an online index fails because of existing data
     *  - Adding to an online index fails because of data in the same transaction
     *
     * There's a lot of work to be done here.
     */

    @BeforeEach
    void setUp()
    {
        var originalDescriptor = indexProvider.getProviderDescriptor();
        var descriptorOverride = new IndexProviderDescriptor( "compatibility-test-" + originalDescriptor.getKey(), originalDescriptor.getVersion() );
        Config.Builder config = Config.newBuilder();
        config.set( default_schema_provider, descriptorOverride.name() );
        testSuite.additionalConfig( config );
        managementService = new TestDatabaseManagementServiceBuilder( homePath )
                .addExtension( new PredefinedIndexProviderFactory( indexProvider, descriptorOverride ) )
                .noOpSystemGraphInitializer()
                .impermanent()
                .setConfig( config.build() )
                .build();
        db = managementService.database( DEFAULT_DATABASE_NAME );
    }

    @AfterEach
    void tearDown()
    {
        managementService.shutdown();
    }

    // -- Tests:

    @Test
    void onlineConstraintShouldAcceptDistinctValuesInDifferentTransactions()
    {
        // Given
        givenOnlineConstraint();

        // When
        Node n;
        try ( Transaction tx = db.beginTx() )
        {
            n = tx.createNode( label );
            n.setProperty( property, "n" );
            tx.commit();
        }

        // Then
        transaction(
                assertLookupNode( "a", a ),
                assertLookupNode( "n" , n ) );
    }

    @Test
    void onlineConstraintShouldAcceptDistinctValuesInSameTransaction()
    {
        // Given
        givenOnlineConstraint();

        // When
        Node n;
        Node m;
        try ( Transaction tx = db.beginTx() )
        {
            n = tx.createNode( label );
            n.setProperty( property, "n" );

            m = tx.createNode( label );
            m.setProperty( property, "m" );
            tx.commit();
        }

        // Then
        transaction(
                assertLookupNode( "n", n ),
                assertLookupNode( "m", m ) );
    }

    @Test
    void onlineConstraintShouldNotFalselyCollideOnFindNodesByLabelAndProperty()
    {
        // Given
        givenOnlineConstraint();
        Node n;
        Node m;
        try ( Transaction tx = db.beginTx() )
        {
            n = tx.createNode( label );
            n.setProperty( property, COLLISION_X );
            tx.commit();
        }

        // When
        try ( Transaction tx = db.beginTx() )
        {
            m = tx.createNode( label );
            m.setProperty( property, COLLISION_Y );
            tx.commit();
        }

        // Then
        transaction(
                assertLookupNode( COLLISION_X, n ),
                assertLookupNode( COLLISION_Y, m ) );
    }

    @Test
    void onlineConstraintShouldNotConflictOnIntermediateStatesInSameTransaction()
    {
        // Given
        givenOnlineConstraint();

        // When
        transaction(
                setProperty( a, "b" ),
                setProperty( b, "a" ),
                success );

        // Then
        transaction(
                assertLookupNode( "a", b ),
                assertLookupNode( "b", a ) );
    }

    @Test
    void onlineConstraintShouldRejectChangingEntryToAlreadyIndexedValue()
    {
        // Given
        givenOnlineConstraint();
        transaction(
                setProperty( b, "b" ),
                success );

        // When
        assertThrows( ConstraintViolationException.class, () ->
                transaction(
                setProperty( b, "a" ),
                success,
                fail( "Changing a property to an already indexed value should have thrown" ) ) );
    }

    @Test
    void onlineConstraintShouldRejectConflictsInTheSameTransaction()
    {
        // Given
        givenOnlineConstraint();

        // Then
        assertThrows( ConstraintViolationException.class, () -> transaction(
                setProperty( a, "x" ),
                setProperty( b, "x" ),
                success,
                fail( "Should have rejected changes of two node/properties to the same index value" ) ) );
    }

    @Test
    void onlineConstraintShouldRejectChangingEntryToAlreadyIndexedValueThatOtherTransactionsAreRemoving()
    {
        // Given
        givenOnlineConstraint();
        transaction(
                setProperty( b, "b" ),
                success );

        Transaction otherTx = db.beginTx();
        otherTx.getNodeById( a.getId() ).removeLabel( label );

        // When
        try
        {
            transaction(
                    setProperty( b, "a" ),
                    success,
                    fail( "Changing a property to an already indexed value should have thrown" ) );
        }
        catch ( ConstraintViolationException ignore )
        {
            // we're happy
        }
        finally
        {
            otherTx.rollback();
        }
    }

    // Replaces UniqueIAC: shouldRemoveAndAddEntries
    @Test
    void onlineConstraintShouldAddAndRemoveFromIndexAsPropertiesAndLabelsChange()
    {
        // Given
        givenOnlineConstraint();

        // When
        transaction( setProperty( b, "b" ), success );
        transaction( setProperty( c, "c" ), addLabel( c, label ), success );
        transaction( setProperty( d, "d" ), addLabel( d, label ), success );
        transaction( removeProperty( a ), success );
        transaction( removeProperty( b ), success );
        transaction( removeProperty( c ), success );
        transaction( setProperty( a, "a" ), success );
        transaction( setProperty( c, "c2" ), success );

        // Then
        transaction(
                assertLookupNode( "a", a ),
                assertLookupNode( "b", null ),
                assertLookupNode( "c", null ),
                assertLookupNode( "d", d ),
                assertLookupNode( "c2", c ) );
    }

    // Replaces UniqueIAC: shouldRejectEntryWithAlreadyIndexedValue
    @Test
    void onlineConstraintShouldRejectConflictingPropertyChange()
    {
        // Given
        givenOnlineConstraint();

        // Then
        assertThrows( ConstraintViolationException.class, () -> transaction(
                setProperty( b, "a" ),
                success,
                fail( "Setting b.name = \"a\" should have caused a conflict" ) ) );
    }

    @Test
    void onlineConstraintShouldRejectConflictingLabelChange()
    {
        // Given
        givenOnlineConstraint();

        // Then
        assertThrows( ConstraintViolationException.class, () -> transaction(
                addLabel( c, label ),
                success,
                fail( "Setting c:Cybermen should have caused a conflict" ) ) );
    }

    // Replaces UniqueIAC: shouldRejectAddingEntryToValueAlreadyIndexedByPriorChange
    @Test
    void onlineConstraintShouldRejectAddingEntryForValueAlreadyIndexedByPriorChange()
    {
        // Given
        givenOnlineConstraint();

        // When
        transaction( setProperty( a, "a1" ), success ); // This is a CHANGE update

        // Then
        assertThrows( ConstraintViolationException.class, () -> transaction(
                setProperty( b, "a1" ),
                success,
                fail( "Setting b.name = \"a1\" should have caused a conflict" ) ) );
    }

    // Replaces UniqueIAC: shouldAddUniqueEntries
    // Replaces UniqueIPC: should*EnforceUniqueConstraintsAgainstDataAddedOnline
    @Test
    void onlineConstraintShouldAcceptUniqueEntries()
    {
        // Given
        givenOnlineConstraint();

        // When
        transaction( setProperty( b, "b" ), addLabel( d, label ), success );
        transaction( setProperty( c, "c" ), addLabel( c, label ), success );

        // Then
        transaction(
                assertLookupNode( "a", a ),
                assertLookupNode( "b", b ),
                assertLookupNode( "c", c ),
                assertLookupNode( "d", d ) );
    }

    // Replaces UniqueIAC: shouldUpdateUniqueEntries
    @Test
    void onlineConstraintShouldAcceptUniqueEntryChanges()
    {
        // Given
        givenOnlineConstraint();

        // When
        transaction( setProperty( a, "a1" ), success ); // This is a CHANGE update

        // Then
        transaction( assertLookupNode( "a1", a ) );
    }

    // Replaces UniqueIAC: shouldRejectEntriesInSameTransactionWithDuplicateIndexedValue\
    @Test
    void onlineConstraintShouldRejectDuplicateEntriesAddedInSameTransaction()
    {
        // Given
        givenOnlineConstraint();

        // Then
        assertThrows( ConstraintViolationException.class, () -> transaction(
                setProperty( b, "d" ),
                addLabel( d, label ),
                success,
                fail( "Setting b.name = \"d\" and d:Cybermen should have caused a conflict" ) ) );
    }

    // Replaces UniqueIPC: should*EnforceUniqueConstraints
    // Replaces UniqueIPC: should*EnforceUniqueConstraintsAgainstDataAddedThroughPopulator
    @Test
    void populatingConstraintMustAcceptDatasetOfUniqueEntries()
    {
        // Given
        givenUniqueDataset();

        // Then this does not throw:
        createUniqueConstraint();
    }

    @Test
    void populatingConstraintMustRejectDatasetWithDuplicateEntries()
    {
        // Given
        givenUniqueDataset();
        transaction(
                setProperty( c, "b" ), // same property value as 'b' has
                success );

        // Then this must throw:
        assertThrows( ConstraintViolationException.class, this::createUniqueConstraint );
    }

    @Test
    void populatingConstraintMustAcceptDatasetWithDalseIndexCollisions()
    {
        // Given
        givenUniqueDataset();
        transaction(
                setProperty( b, COLLISION_X ),
                setProperty( c, COLLISION_Y ),
                success );

        // Then this does not throw:
        createUniqueConstraint();
    }

    @Test
    void populatingConstraintMustAcceptDatasetThatGetsUpdatedWithUniqueEntries() throws Exception
    {
        // Given
        givenUniqueDataset();

        // When
        Future<?> createConstraintTransaction = applyChangesToPopulatingUpdater(
                d.getId(), a.getId(), setProperty( d, "d1" ) );

        // Then observe that our constraint was created successfully:
        createConstraintTransaction.get();
        // Future.get() will throw an ExecutionException, if the Runnable threw an exception.
    }

    // Replaces UniqueLucIAT: shouldRejectEntryWithAlreadyIndexedValue
    @Test
    void populatingConstraintMustRejectDatasetThatGetsUpdatedWithDuplicateAddition() throws Exception
    {
        // Given
        givenUniqueDataset();

        // When
        Future<?> createConstraintTransaction = applyChangesToPopulatingUpdater(
                d.getId(), a.getId(), createNode( "b" ) );

        // Then observe that our constraint creation failed:
        try
        {
            createConstraintTransaction.get();
            Assertions.fail( "expected to throw when PopulatingUpdater got duplicates" );
        }
        catch ( ExecutionException ee )
        {
            Throwable cause = ee.getCause();
            assertThat( cause ).isInstanceOf( ConstraintViolationException.class );
        }
    }

    // Replaces UniqueLucIAT: shouldRejectChangingEntryToAlreadyIndexedValue
    @Test
    void populatingConstraintMustRejectDatasetThatGetsUpdatedWithDuplicates() throws Exception
    {
        // Given
        givenUniqueDataset();

        // When
        Future<?> createConstraintTransaction = applyChangesToPopulatingUpdater(
                d.getId(), a.getId(), setProperty( d, "b" ) );

        // Then observe that our constraint creation failed:
        try
        {
            createConstraintTransaction.get();
            Assertions.fail( "expected to throw when PopulatingUpdater got duplicates" );
        }
        catch ( ExecutionException ee )
        {
            Throwable cause = ee.getCause();
            assertThat( cause ).isInstanceOf( ConstraintViolationException.class );
        }
    }

    @Test
    void populatingConstraintMustAcceptDatasetThatGestUpdatedWithFalseIndexCollisions() throws Exception
    {
        // Given
        givenUniqueDataset();
        transaction( setProperty( a, COLLISION_X ), success );

        // When
        Future<?> createConstraintTransaction = applyChangesToPopulatingUpdater(
                d.getId(), a.getId(), setProperty( d, COLLISION_Y ) );

        // Then observe that our constraint was created successfully:
        createConstraintTransaction.get();
        // Future.get() will throw an ExecutionException, if the Runnable threw an exception.
    }

    // Replaces UniqueLucIAT: shouldRejectEntriesInSameTransactionWithDuplicatedIndexedValues
    @Test
    void populatingConstraintMustRejectDatasetThatGetsUpdatedWithDuplicatesInSameTransaction() throws Exception
    {
        // Given
        givenUniqueDataset();

        // When
        Future<?> createConstraintTransaction = applyChangesToPopulatingUpdater(
                d.getId(), a.getId(), setProperty( d, "x" ), setProperty( c, "x" ) );

        // Then observe that our constraint creation failed:
        try
        {
            createConstraintTransaction.get();
            Assertions.fail( "expected to throw when PopulatingUpdater got duplicates" );
        }
        catch ( ExecutionException ee )
        {
            Throwable cause = ee.getCause();
            assertThat( cause ).isInstanceOf( ConstraintViolationException.class );
        }
    }

    @Test
    void populatingConstraintMustAcceptDatasetThatGetsUpdatedWithDuplicatesThatAreLaterResolved() throws Exception
    {
        // Given
        givenUniqueDataset();

        // When
        Future<?> createConstraintTransaction = applyChangesToPopulatingUpdater(
                d.getId(),
                a.getId(),
                setProperty( d, "b" ), // Cannot touch node 'a' because that one is locked
                setProperty( b, "c" ),
                setProperty( c, "d" ) );

        // Then observe that our constraint was created successfully:
        createConstraintTransaction.get();
        // Future.get() will throw an ExecutionException, if the Runnable threw an exception.
    }

    // Replaces UniqueLucIAT: shouldRejectAddingEntryToValueAlreadyIndexedByPriorChange
    @Test
    void populatingUpdaterMustRejectDatasetWhereAdditionsConflictsWithPriorChanges() throws Exception
    {
        // Given
        givenUniqueDataset();

        // When
        Future<?> createConstraintTransaction = applyChangesToPopulatingUpdater(
                d.getId(), a.getId(), setProperty( d, "x" ), createNode( "x" ) );

        // Then observe that our constraint creation failed:
        try
        {
            createConstraintTransaction.get();
            Assertions.fail( "expected to throw when PopulatingUpdater got duplicates" );
        }
        catch ( ExecutionException ee )
        {
            Throwable cause = ee.getCause();
            assertThat( cause ).isInstanceOf( ConstraintViolationException.class );
        }
    }

    /**
     * NOTE the tests using this will currently succeed for the wrong reasons,
     * because the data-changing transaction does not actually release the
     * schema read lock early enough for the PopulatingUpdater to come into
     * play.
     */
    private Future<?> applyChangesToPopulatingUpdater(
            long blockDataChangeTransactionOnLockOnId,
            long blockPopulatorOnLockOnId,
            final Action... actions ) throws InterruptedException, ExecutionException
    {
        // We want to issue an update to an index populator for a constraint.
        // However, creating a constraint takes a schema write lock, while
        // creating nodes and setting their properties takes a schema read
        // lock. We need to sneak past these locks.
        final CountDownLatch createNodeReadyLatch = new CountDownLatch( 1 );
        final CountDownLatch createNodeCommitLatch = new CountDownLatch( 1 );
        Future<?> updatingTransaction = executor.submit( () ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                for ( Action action : actions )
                {
                    action.accept( tx );
                }
                tx.commit();
                createNodeReadyLatch.countDown();
                awaitUninterruptibly( createNodeCommitLatch );
            }
        } );
        createNodeReadyLatch.await();

        // The above transaction now contain the changes we want to expose to
        // the IndexUpdater as updates. This will happen when we commit the
        // transaction. The transaction now also holds the schema read lock,
        // so we can't begin creating our constraint just yet.
        // We first have to unlock the schema, and then block just before we
        // send off our updates. We can do that by making another thread take a
        // read lock on the node we just created, and then initiate our commit.
        Lock lockBlockingDataChangeTransaction = getLockService().acquireNodeLock(
                blockDataChangeTransactionOnLockOnId,
                LockType.EXCLUSIVE );

        // Before we begin creating the constraint, we take a write lock on an
        // "earlier" node, to hold up the populator for the constraint index.
        Lock lockBlockingIndexPopulator = getLockService().acquireNodeLock(
                blockPopulatorOnLockOnId,
                LockType.EXCLUSIVE );

        // This thread tries to create a constraint. It should block, waiting for it's
        // population job to finish, and it's population job should in turn be blocked
        // on the lockBlockingIndexPopulator above:
        final CountDownLatch createConstraintTransactionStarted = new CountDownLatch( 1 );
        Future<?> createConstraintTransaction = executor.submit(
                () -> createUniqueConstraint( createConstraintTransactionStarted ) );
        createConstraintTransactionStarted.await();

        // Now we can initiate the data-changing commit. It should then
        // release the schema read lock, and block on the
        // lockBlockingDataChangeTransaction.
        createNodeCommitLatch.countDown();

        // Now we can issue updates to the populator in the still ongoing population job.
        // We do that by releasing the lock that is currently preventing our
        // data-changing transaction from committing.
        lockBlockingDataChangeTransaction.release();

        // And we observe that our updating transaction has completed as well:
        updatingTransaction.get();

        // Now we can release the lock blocking the populator, allowing it to finish:
        lockBlockingIndexPopulator.release();

        // And return the future for examination:
        return createConstraintTransaction;
    }

    // -- Set Up: Data parts

    // These two values coalesce to the same double value, and therefor collides in our current index implementation:
    private static final long COLLISION_X = 4611686018427387905L;
    private static final long COLLISION_Y = 4611686018427387907L;
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private final Label label = Label.label( "Cybermen" );
    private final String property = "name";
    private Node a;
    private Node b;
    private Node c;
    private Node d;

    private GraphDatabaseService db;

    /**
     * Effectively:
     *
     * <pre><code>
     *     CREATE CONSTRAINT FOR (n:Cybermen) require n.name is unique
     *     ;
     *
     *     CREATE (a:Cybermen {name: "a"}),
     *            (b:Cybermen),
     *            (c: {name: "a"}),
     *            (d: {name: "d"})
     *     ;
     * </code></pre>
     */
    private void givenOnlineConstraint()
    {
        createUniqueConstraint();
        try ( Transaction tx = db.beginTx() )
        {
            a = tx.createNode( label );
            a.setProperty( property, "a" );
            b = tx.createNode( label );
            c = tx.createNode();
            c.setProperty( property, "a" );
            d = tx.createNode();
            d.setProperty( property, "d" );
            tx.commit();
        }
    }

    /**
     * Effectively:
     *
     * <pre><code>
     *     CREATE (a:Cybermen {name: "a"}),
     *            (b:Cybermen {name: "b"}),
     *            (c:Cybermen {name: "c"}),
     *            (d:Cybermen {name: "d"})
     *     ;
     * </code></pre>
     */
    private void givenUniqueDataset()
    {
        try ( Transaction tx = db.beginTx() )
        {
            a = tx.createNode( label );
            a.setProperty( property, "a" );
            b = tx.createNode( label );
            b.setProperty( property, "b" );
            c = tx.createNode( label );
            c.setProperty( property, "c" );
            d = tx.createNode( label );
            d.setProperty( property, "d" );
            tx.commit();
        }
    }

    /**
     * Effectively:
     *
     * <pre><code>
     *     CREATE CONSTRAINT FOR (n:Cybermen) require n.name is unique
     *     ;
     * </code></pre>
     */
    private void createUniqueConstraint()
    {
        createUniqueConstraint( null );
    }

    /**
     * Effectively:
     *
     * <pre><code>
     *     CREATE CONSTRAINT FOR (n:Cybermen) require n.name is unique
     *     ;
     * </code></pre>
     *
     * Also counts down the given latch prior to creating the constraint.
     */
    private void createUniqueConstraint( CountDownLatch preCreateLatch )
    {
        try ( Transaction tx = db.beginTx() )
        {
            if ( preCreateLatch != null )
            {
                preCreateLatch.countDown();
            }
            tx.schema().constraintFor( label ).assertPropertyIsUnique( property ).withIndexType( testSuite.indexType().toPublicApi() ).create();
            tx.commit();
        }
    }

    /**
     * Effectively:
     *
     * <pre><code>
     *     return single( db.findNodesByLabelAndProperty( label, property, value ), null );
     * </code></pre>
     */
    private Node lookUpNode( Transaction tx, Object value )
    {
        return tx.findNode( label, property, value );
    }

    // -- Set Up: Transaction handling

    void transaction( Action... actions )
    {
        int progress = 0;
        try ( Transaction tx = db.beginTx() )
        {
            for ( Action action : actions )
            {
                action.accept( tx );
                progress++;
            }
        }
        catch ( Throwable ex )
        {
            StringBuilder sb = new StringBuilder( "Transaction failed:\n\n" );
            for ( int i = 0; i < actions.length; i++ )
            {
                String mark = progress == i ? " failed --> " : "            ";
                sb.append( mark ).append( actions[i] ).append( '\n' );
            }
            ex.addSuppressed( new AssertionError( sb.toString() ) );
            throw ex;
        }
    }

    private abstract static class Action implements Consumer<Transaction>
    {
        private final String name;

        Action( String name )
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    private final Action success = new Action( "tx.success();" )
    {
        @Override
        public void accept( Transaction transaction )
        {
            transaction.commit();
        }
    };

    private Action createNode( final Object propertyValue )
    {
        return new Action( "Node node = tx.createNode( label ); " +
                "node.setProperty( property, " + reprValue( propertyValue ) + " );" )
        {
            @Override
            public void accept( Transaction transaction )
            {
                Node node = transaction.createNode( label );
                node.setProperty( property, propertyValue );
            }
        };
    }

    private Action setProperty( final Node node, final Object value )
    {
        return new Action( reprNode( node ) + ".setProperty( property, " + reprValue( value ) + " );" )
        {
            @Override
            public void accept( Transaction transaction )
            {
                transaction.getNodeById( node.getId() ).setProperty( property, value );
            }
        };
    }

    private Action removeProperty( final Node node )
    {
        return new Action( reprNode( node ) + ".removeProperty( property );" )
        {
            @Override
            public void accept( Transaction transaction )
            {
                transaction.getNodeById( node.getId() ).removeProperty( property );
            }
        };
    }

    private Action addLabel( final Node node, final Label label )
    {
        return new Action( reprNode( node ) + ".addLabel( " + label + " );" )
        {
            @Override
            public void accept( Transaction transaction )
            {
                transaction.getNodeById( node.getId() ).addLabel( label );
            }
        };
    }

    private static Action fail( final String message )
    {
        return new Action( "fail( \"" + message + "\" );" )
        {
            @Override
            public void accept( Transaction transaction )
            {
                Assertions.fail( message );
            }
        };
    }

    private Action assertLookupNode( final Object propertyValue, Object value )
    {
        return new Action( "assertThat( lookUpNode( " + reprValue( propertyValue ) + " ), " + value + " );" )
        {
            @Override
            public void accept( Transaction transaction )
            {
                assertThat( lookUpNode( transaction, propertyValue ) ).isEqualTo( value );
            }
        };
    }

    private static String reprValue( Object value )
    {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf( value );
    }

    private String reprNode( Node node )
    {
        return node == a ? "a" : node == b ? "b" : node == c ? "c" : node == d ? "d" : "n";
    }

    // -- Set Up: Misc. sharp tools

    /**
     * Locks controlling concurrent access to the store files.
     */
    private LockService getLockService()
    {
        return resolveInternalDependency( LockService.class );
    }

    private <T> T resolveInternalDependency( Class<T> type )
    {
        GraphDatabaseAPI api = (GraphDatabaseAPI) db;
        DependencyResolver resolver = api.getDependencyResolver();
        return resolver.resolveDependency( type );
    }

    private static void awaitUninterruptibly( CountDownLatch latch )
    {
        try
        {
            latch.await();
        }
        catch ( InterruptedException e )
        {
            throw new AssertionError( "Interrupted", e );
        }
    }

    private static class PredefinedIndexProviderFactory extends ExtensionFactory<PredefinedIndexProviderFactory.NoDeps>
    {
        private final IndexProvider indexProvider;
        private final IndexProviderDescriptor descriptorOverride;

        @Override
        public Lifecycle newInstance( ExtensionContext context, NoDeps noDeps )
        {
            return new IndexProvider.Delegating( indexProvider )
            {
                @Override
                public IndexProviderDescriptor getProviderDescriptor()
                {
                    return descriptorOverride;
                }

                @Override
                public StoreMigrationParticipant storeMigrationParticipant( FileSystemAbstraction fs, PageCache pageCache,
                                                                            StorageEngineFactory storageEngineFactory )
                {
                    return new NameOverridingStoreMigrationParticipant( super.storeMigrationParticipant( fs, pageCache, storageEngineFactory ),
                                                                        descriptorOverride.name() );
                }
            };
        }

        interface NoDeps
        {
        }

        PredefinedIndexProviderFactory( IndexProvider indexProvider, IndexProviderDescriptor descriptorOverride )
        {
            super( ExtensionType.DATABASE, indexProvider.getClass().getSimpleName() );
            this.indexProvider = indexProvider;
            this.descriptorOverride = descriptorOverride;
        }
    }
}
