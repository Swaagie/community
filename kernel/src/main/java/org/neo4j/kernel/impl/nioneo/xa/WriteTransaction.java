/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.kernel.impl.nioneo.xa;

import static org.neo4j.kernel.impl.nioneo.store.PropertyStore.encodeString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.kernel.impl.core.LockReleaser;
import org.neo4j.kernel.impl.core.PropertyIndex;
import org.neo4j.kernel.impl.core.RelationshipLoadingPosition;
import org.neo4j.kernel.impl.nioneo.store.DynamicRecord;
import org.neo4j.kernel.impl.nioneo.store.InvalidRecordException;
import org.neo4j.kernel.impl.nioneo.store.NameData;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.NeoStoreRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeStore;
import org.neo4j.kernel.impl.nioneo.store.PrimitiveRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyBlock;
import org.neo4j.kernel.impl.nioneo.store.PropertyData;
import org.neo4j.kernel.impl.nioneo.store.PropertyIndexRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyIndexStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyType;
import org.neo4j.kernel.impl.nioneo.store.Record;
import org.neo4j.kernel.impl.nioneo.store.RelationshipGroupRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipGroupStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeStore;
import org.neo4j.kernel.impl.nioneo.xa.Command.PropertyCommand;
import org.neo4j.kernel.impl.persistence.NeoStoreTransaction;
import org.neo4j.kernel.impl.transaction.LockManager;
import org.neo4j.kernel.impl.transaction.LockType;
import org.neo4j.kernel.impl.transaction.xaframework.XaCommand;
import org.neo4j.kernel.impl.transaction.xaframework.XaConnection;
import org.neo4j.kernel.impl.transaction.xaframework.XaLogicalLog;
import org.neo4j.kernel.impl.transaction.xaframework.XaTransaction;
import org.neo4j.kernel.impl.util.ArrayMap;
import org.neo4j.kernel.impl.util.DirectionWrapper;
import org.neo4j.kernel.impl.util.RelIdArray;

/**
 * Transaction containing {@link Command commands} reflecting the operations
 * performed in the transaction.
 */
public class WriteTransaction extends XaTransaction implements NeoStoreTransaction
{
    private final Map<Long,NodeRecord> nodeRecords = new HashMap<Long,NodeRecord>();
    private final Map<Long,PropertyRecord> propertyRecords = new HashMap<Long,PropertyRecord>();
    private final Map<Long,RelationshipRecord> relRecords = new HashMap<Long,RelationshipRecord>();
    private Map<Integer,RelationshipTypeRecord> relTypeRecords;
    private Map<Integer,PropertyIndexRecord> propIndexRecords;
    private final Map<Long, RelationshipGroupRecord> relGroupRecords = new HashMap<Long, RelationshipGroupRecord>();
    private final Map<Long, Map<Integer, RelationshipGroupRecord>> relGroupCache = new HashMap<Long, Map<Integer,RelationshipGroupRecord>>();
    private NeoStoreRecord neoStoreRecord;

    private final ArrayList<Command.NodeCommand> nodeCommands = new ArrayList<Command.NodeCommand>();
    private final ArrayList<Command.PropertyCommand> propCommands = new ArrayList<Command.PropertyCommand>();
    private final ArrayList<Command.RelationshipCommand> relCommands = new ArrayList<Command.RelationshipCommand>();
    private ArrayList<Command.RelationshipTypeCommand> relTypeCommands;
    private ArrayList<Command.PropertyIndexCommand> propIndexCommands;
    private final ArrayList<Command.RelationshipGroupCommand> relGroupCommands = new ArrayList<Command.RelationshipGroupCommand>();
    private Command.NeoStoreCommand neoStoreCommand;
    private ArrayList<NodeRecord> upgradedSuperNodes;

    private final NeoStore neoStore;
    private boolean committed = false;
    private boolean prepared = false;

    private final LockReleaser lockReleaser;
    private final LockManager lockManager;
    private XaConnection xaConnection;

    WriteTransaction( int identifier, XaLogicalLog log, NeoStore neoStore,
            LockReleaser lockReleaser, LockManager lockManager )
    {
        super( identifier, log );
        this.neoStore = neoStore;
        this.lockReleaser = lockReleaser;
        this.lockManager = lockManager;
    }

    @Override
    public boolean isReadOnly()
    {
        if ( isRecovered() )
        {
            return nodeCommands.size() == 0 && propCommands.size() == 0 &&
                relCommands.size() == 0 && relTypeCommands == null &&
                propIndexCommands == null && relGroupCommands.size() == 0;
        }
        return nodeRecords.size() == 0 && relRecords.size() == 0 &&
            propertyRecords.size() == 0 && relTypeRecords == null &&
            propIndexRecords == null && relGroupRecords.size() == 0;
    }

    @Override
    public void doAddCommand( XaCommand command )
    {
        // override
    }

    @Override
    protected void doPrepare() throws XAException
    {
        int noOfCommands = nodeRecords.size() +
                           relRecords.size() +
                           propertyRecords.size() +
                           (propIndexRecords != null ? propIndexRecords.size() : 0) +
                           (relTypeRecords != null ? relTypeRecords.size() : 0) +
                           relGroupRecords.size();
        
        List<Command> commands = new ArrayList<Command>( noOfCommands );
        if ( committed )
        {
            throw new XAException( "Cannot prepare committed transaction["
                + getIdentifier() + "]" );
        }
        if ( prepared )
        {
            throw new XAException( "Cannot prepare prepared transaction["
                + getIdentifier() + "]" );
        }
        /*
         * Generate records first, then write all together to logical log via
         * addCommand method but before give the option to intercept.
         */
        prepared = true;
        if ( relTypeRecords != null )
        {
            relTypeCommands = new ArrayList<Command.RelationshipTypeCommand>();
            for ( RelationshipTypeRecord record : relTypeRecords.values() )
            {
                Command.RelationshipTypeCommand command =
                    new Command.RelationshipTypeCommand(
                        neoStore.getRelationshipTypeStore(), record );
                relTypeCommands.add( command );
                commands.add( command );
            }
        }
        for ( NodeRecord record : nodeRecords.values() )
        {
            if ( !record.inUse() && record.getNextRel() !=
                Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                throw new InvalidRecordException( "Node record " + record
                    + " still has relationships" );
            }
            Command.NodeCommand command = new Command.NodeCommand(
                neoStore.getNodeStore(), record );
            nodeCommands.add( command );
            if ( !record.inUse() )
            {
                removeNodeFromCache( record.getId() );
            }
            commands.add( command );
        }
        if ( upgradedSuperNodes != null ) for ( NodeRecord node : upgradedSuperNodes )
        {
            removeNodeFromCache( node.getId() );
        }
        for ( RelationshipRecord record : relRecords.values() )
        {
            Command.RelationshipCommand command =
                new Command.RelationshipCommand(
                    neoStore.getRelationshipStore(), record );
            relCommands.add( command );
            if ( !record.inUse() )
            {
                removeRelationshipFromCache( record.getId() );
            }
            commands.add( command );
        }
        if ( neoStoreRecord != null )
        {
            neoStoreCommand = new Command.NeoStoreCommand( neoStore, neoStoreRecord );
            addCommand( neoStoreCommand );
        }
        if ( propIndexRecords != null )
        {
            propIndexCommands = new ArrayList<Command.PropertyIndexCommand>();
            for ( PropertyIndexRecord record : propIndexRecords.values() )
            {
                Command.PropertyIndexCommand command =
                    new Command.PropertyIndexCommand(
                        neoStore.getPropertyStore().getIndexStore(), record );
                propIndexCommands.add( command );
                commands.add( command );
            }
        }
        for ( PropertyRecord record : propertyRecords.values() )
        {
            Command.PropertyCommand command = new Command.PropertyCommand(
                    neoStore.getPropertyStore(), record );
            propCommands.add( command );
            commands.add( command );
        }
        for ( RelationshipGroupRecord record : relGroupRecords.values() )
        {
            Command.RelationshipGroupCommand command = new Command.RelationshipGroupCommand( neoStore.getRelationshipGroupStore(), record );
            relGroupCommands.add( command );
            commands.add( command );
            // addCommand( command );
        }
        assert commands.size() == noOfCommands : "Expected " + noOfCommands
                                                 + " final commands, got "
                                                 + commands.size() + " instead";
        intercept( commands );

        for ( Command command : commands )
        {
            addCommand(command);
        }
    }

    protected void intercept( List<Command> commands )
    {
        // default no op
    }

    @Override
    protected void injectCommand( XaCommand xaCommand )
    {
        if ( xaCommand instanceof Command.NodeCommand )
        {
            nodeCommands.add( (Command.NodeCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.RelationshipCommand )
        {
            relCommands.add( (Command.RelationshipCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.PropertyCommand )
        {
            propCommands.add( (Command.PropertyCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.PropertyIndexCommand )
        {
            if ( propIndexCommands == null ) propIndexCommands = new ArrayList<Command.PropertyIndexCommand>();
            propIndexCommands.add( (Command.PropertyIndexCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.RelationshipTypeCommand )
        {
            if ( relTypeCommands == null ) relTypeCommands = new ArrayList<Command.RelationshipTypeCommand>();
            relTypeCommands.add( (Command.RelationshipTypeCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.RelationshipGroupCommand )
        {
            relGroupCommands.add( (Command.RelationshipGroupCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.NeoStoreCommand )
        {
            assert neoStoreCommand == null;
            neoStoreCommand = (Command.NeoStoreCommand) xaCommand;
        }
        else
        {
            throw new IllegalArgumentException( "Unknown command " + xaCommand );
        }
    }

    @Override
    public void doRollback() throws XAException
    {
        if ( committed )
        {
            throw new XAException( "Cannot rollback partialy commited "
                + "transaction[" + getIdentifier() + "]. Recover and "
                + "commit" );
        }
        try
        {
            boolean freeIds = neoStore.getTxHook().freeIdsDuringRollback();
            if ( relTypeRecords != null ) for ( RelationshipTypeRecord record : relTypeRecords.values() )
            {
                if ( record.isCreated() )
                {
                    if ( freeIds ) getRelationshipTypeStore().freeId( record.getId() );
                    for ( DynamicRecord dynamicRecord : record.getNameRecords() )
                    {
                        if ( dynamicRecord.isCreated() )
                        {
                            getRelationshipTypeStore().freeBlockId(
                                (int) dynamicRecord.getId() );
                        }
                    }
                }
                removeRelationshipTypeFromCache( record.getId() );
            }
            for ( NodeRecord record : nodeRecords.values() )
            {
                if ( freeIds && record.isCreated() )
                {
                    getNodeStore().freeId( record.getId() );
                }
                removeNodeFromCache( record.getId() );
            }
            for ( RelationshipRecord record : relRecords.values() )
            {
                if ( freeIds && record.isCreated() )
                {
                    getRelationshipStore().freeId( record.getId() );
                }
                removeRelationshipFromCache( record.getId() );
            }
            for ( RelationshipGroupRecord record : relGroupRecords.values() )
            {
                if ( freeIds == record.isCreated() )
                {
                    getRelationshipGroupStore().freeId( record.getId() );
                }
            }
            if ( neoStoreRecord != null )
            {
                removeGraphPropertiesFromCache();
            }
            if ( propIndexRecords != null ) for ( PropertyIndexRecord record : propIndexRecords.values() )
            {
                if ( record.isCreated() )
                {
                    if ( freeIds ) getPropertyStore().getIndexStore().freeId( record.getId() );
                    for ( DynamicRecord dynamicRecord : record.getNameRecords() )
                    {
                        if ( dynamicRecord.isCreated() )
                        {
                            getPropertyStore().getIndexStore().freeBlockId(
                                (int) dynamicRecord.getId() );
                        }
                    }
                }
            }
            for ( PropertyRecord record : propertyRecords.values() )
            {
                if ( record.getNodeId() != -1 )
                {
                    removeNodeFromCache( record.getNodeId() );
                }
                else if ( record.getRelId() != -1 )
                {
                    removeRelationshipFromCache( record.getRelId() );
                }
                if ( record.isCreated() )
                {
                    if ( freeIds ) getPropertyStore().freeId( record.getId() );
                    for ( PropertyBlock block : record.getPropertyBlocks() )
                    {
                        for ( DynamicRecord dynamicRecord : block.getValueRecords() )
                        {
                            if ( dynamicRecord.isCreated() )
                            {
                                if ( dynamicRecord.getType() == PropertyType.STRING.intValue() )
                                {
                                    getPropertyStore().freeStringBlockId(
                                            dynamicRecord.getId() );
                                }
                                else if ( dynamicRecord.getType() == PropertyType.ARRAY.intValue() )
                                {
                                    getPropertyStore().freeArrayBlockId(
                                            dynamicRecord.getId() );
                                }
                                else
                                {
                                    throw new InvalidRecordException(
                                            "Unknown type on " + dynamicRecord );
                                }
                            }
                        }
                    }
                }
            }
        }
        finally
        {
            clear();
        }
    }

    private void removeRelationshipTypeFromCache( int id )
    {
        lockReleaser.removeRelationshipTypeFromCache( id );
    }

    private void removeRelationshipFromCache( long id )
    {
        lockReleaser.removeRelationshipFromCache( id );
    }

    private void removeNodeFromCache( long id )
    {
        lockReleaser.removeNodeFromCache( id );
    }

    private void removeGraphPropertiesFromCache()
    {
        lockReleaser.removeGraphPropertiesFromCache();
    }
    
    private void addRelationshipType( int id )
    {
        setRecovered();
        NameData type = isRecovered() ?
                neoStore.getRelationshipTypeStore().getName( id, true ) :
                neoStore.getRelationshipTypeStore().getName( id );
        lockReleaser.addRelationshipType( type );
    }

    private void addPropertyIndexCommand( int id )
    {
        NameData index = isRecovered() ?
                neoStore.getPropertyStore().getIndexStore().getName( id, true ) :
                neoStore.getPropertyStore().getIndexStore().getName( id );
        lockReleaser.addPropertyIndex( index );
    }

    @Override
    public void doCommit() throws XAException
    {
        if ( !isRecovered() && !prepared )
        {
            throw new XAException( "Cannot commit non prepared transaction["
                + getIdentifier() + "]" );
        }
        if ( isRecovered() )
        {
            commitRecovered();
            return;
        }
        if ( !isRecovered() && getCommitTxId() != neoStore.getLastCommittedTx() + 1 )
        {
            throw new RuntimeException( "Tx id: " + getCommitTxId() +
                    " not next transaction (" + neoStore.getLastCommittedTx() + ")" );
        }
        try
        {
            committed = true;
            CommandSorter sorter = new CommandSorter();
            // reltypes
            if ( relTypeCommands != null )
            {
                java.util.Collections.sort( relTypeCommands, sorter );
                for ( Command.RelationshipTypeCommand command : relTypeCommands )
                {
                    command.execute();
                }
            }
            // property keys
            if ( propIndexCommands != null )
            {
                java.util.Collections.sort( propIndexCommands, sorter );
                for ( Command.PropertyIndexCommand command : propIndexCommands )
                {
                    command.execute();
                }
            }

            // primitives
            java.util.Collections.sort( nodeCommands, sorter );
            java.util.Collections.sort( relCommands, sorter );
            java.util.Collections.sort( propCommands, sorter );
            executeCreated( propCommands, relCommands, nodeCommands, relGroupCommands );
            executeModified( propCommands, relCommands, nodeCommands, relGroupCommands );
            if ( neoStoreCommand != null ) neoStoreCommand.execute();
            executeDeleted( propCommands, relCommands, nodeCommands, relGroupCommands );
            updateFirstRelationships();
            lockReleaser.commitCows();
            neoStore.setLastCommittedTx( getCommitTxId() );
        }
        finally
        {
            clear();
        }
    }

    private void updateFirstRelationships()
    {
        for ( NodeRecord record : nodeRecords.values() )
            lockReleaser.setFirstIds( record.getId(), record.getNextRel(), record.getNextProp() );
    }

    private static void executeCreated(
            ArrayList<? extends Command>... commands )
    {
        for ( ArrayList<? extends Command> c : commands ) for ( Command command : c )
        {
            if ( command.isCreated() && !command.isDeleted() )
            {
                command.execute();
            }
        }
    }

    private static void executeModified(
            ArrayList<? extends Command>... commands )
    {
        for ( ArrayList<? extends Command> c : commands ) for ( Command command : c )
        {
            if ( !command.isCreated() && !command.isDeleted() )
            {
                command.execute();
            }
        }
    }

    private static void executeDeleted(
            ArrayList<? extends Command>... commands )
    {
        for ( ArrayList<? extends Command> c : commands ) for ( Command command : c )
        {
            if ( command.isDeleted() )
            {
                command.execute();
            }
        }
    }

    private void commitRecovered()
    {
        try
        {
            committed = true;
            CommandSorter sorter = new CommandSorter();
            // property index
            if ( propIndexCommands != null )
            {
                java.util.Collections.sort( propIndexCommands, sorter );
                for ( Command.PropertyIndexCommand command : propIndexCommands )
                {
                    command.execute();
                    addPropertyIndexCommand( (int) command.getKey() );
                }
            }
            // properties
            java.util.Collections.sort( propCommands, sorter );
            for ( Command.PropertyCommand command : propCommands )
            {
                command.execute();
                removePropertyFromCache( command );
            }
            // reltypes
            if ( relTypeCommands != null )
            {
                java.util.Collections.sort( relTypeCommands, sorter );
                for ( Command.RelationshipTypeCommand command : relTypeCommands )
                {
                    command.execute();
                    addRelationshipType( (int) command.getKey() );
                }
            }
            // relationships
            java.util.Collections.sort( relCommands, sorter );
            for ( Command.RelationshipCommand command : relCommands )
            {
                command.execute();
                removeRelationshipFromCache( command.getKey() );
                if ( true /* doesn't work: command.isRemove(), the log doesn't contain the nodes */)
                {
                    removeNodeFromCache( command.getFirstNode() );
                    removeNodeFromCache( command.getSecondNode() );
                }
            }
            // nodes
            java.util.Collections.sort( nodeCommands, sorter );
            for ( Command.NodeCommand command : nodeCommands )
            {
                command.execute();
                removeNodeFromCache( command.getKey() );
            }
            for ( Command.RelationshipGroupCommand command : relGroupCommands )
            {
                command.execute();
            }
            neoStore.setRecoveredStatus( true );
            try
            {
                if ( neoStoreCommand != null )
                {
                    neoStoreCommand.execute();
                    removeGraphPropertiesFromCache();
                }
                neoStore.setLastCommittedTx( getCommitTxId() );
            }
            finally
            {
                neoStore.setRecoveredStatus( false );
            }
            neoStore.getIdGeneratorFactory().updateIdGenerators( neoStore );
        }
        finally
        {
            clear();
        }
    }

    private void clear()
    {
        nodeRecords.clear();
        propertyRecords.clear();
        relRecords.clear();
        relTypeRecords = null;
        propIndexRecords = null;
        relGroupRecords.clear();
        relGroupCache.clear();
        upgradedSuperNodes = null;
        neoStoreRecord = null;

        nodeCommands.clear();
        propCommands.clear();
        propIndexCommands = null;
        relCommands.clear();
        relTypeCommands = null;
        relGroupCommands.clear();
        neoStoreCommand = null;
    }


    private void removePropertyFromCache( PropertyCommand command )
    {
        long nodeId = command.getNodeId();
        long relId = command.getRelId();
        if ( nodeId != -1 )
        {
            removeNodeFromCache( nodeId );
        }
        else if ( relId != -1 )
        {
            removeRelationshipFromCache( relId );
        }
        // else means record value did not change
    }

    private RelationshipTypeStore getRelationshipTypeStore()
    {
        return neoStore.getRelationshipTypeStore();
    }

    private int getRelGrabSize()
    {
        return neoStore.getRelationshipGrabSize();
    }

    private NodeStore getNodeStore()
    {
        return neoStore.getNodeStore();
    }

    private RelationshipStore getRelationshipStore()
    {
        return neoStore.getRelationshipStore();
    }

    private RelationshipGroupStore getRelationshipGroupStore()
    {
        return neoStore.getRelationshipGroupStore();
    }
    
    private PropertyStore getPropertyStore()
    {
        return neoStore.getPropertyStore();
    }

    @Override
    public NodeRecord nodeLoadLight( long nodeId )
    {
        NodeRecord nodeRecord = getCachedNodeRecord( nodeId );
        if ( nodeRecord != null ) return nodeRecord;
        return getNodeStore().loadLightNode( nodeId );
    }

    @Override
    public RelationshipRecord relLoadLight( long id )
    {
        RelationshipRecord relRecord = getCachedRelationshipRecord( id );
        if ( relRecord != null )
        {
            // if deleted in this tx still return it
//            if ( !relRecord.inUse() )
//            {
//                return null;
//            }
            return relRecord;
        }
        relRecord = getRelationshipStore().getLightRel( id );
        if ( relRecord != null )
        {
            return relRecord;
        }
        return null;
    }

    @Override
    public ArrayMap<Integer,PropertyData> nodeDelete( long nodeId )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId, true );
        nodeRecord.setInUse( false );
        long nextProp = nodeRecord.getNextProp();
        ArrayMap<Integer, PropertyData> propertyMap = getAndDeletePropertyChain( nextProp );
        return propertyMap;
    }

    @Override
    public ArrayMap<Integer,PropertyData> relDelete( long id )
    {
        RelationshipRecord record = getRelationshipRecord( id, true );
        long nextProp = record.getNextProp();
        ArrayMap<Integer, PropertyData> propertyMap = getAndDeletePropertyChain( nextProp );
        disconnectRelationship( record );
        updateNodesForDeletedRelationship( record );
        record.setInUse( false );
        return propertyMap;
    }

    private ArrayMap<Integer, PropertyData> getAndDeletePropertyChain(
            long startingAt )
    {
        ArrayMap<Integer, PropertyData> result = new ArrayMap<Integer, PropertyData>(
                9, false, true );
        long nextProp = startingAt;
        while ( nextProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord propRecord = getPropertyRecord( nextProp, false, true );
            if ( !propRecord.isCreated() && propRecord.isChanged() )
            {
                // Being here means a new value could be on disk. Re-read
                propRecord = getPropertyStore().getRecord( propRecord.getId() );
            }
            for ( PropertyBlock block : propRecord.getPropertyBlocks() )
            {
                if ( block.isLight() )
                {
                    getPropertyStore().makeHeavy( block );
                }
                if ( !block.isCreated() && !propRecord.isChanged() )
                {
                    result.put( block.getKeyIndexId(),
                            block.newPropertyData( propRecord,
                                    propertyGetValueOrNull( block ) ) );
                }
                // TODO: update count on property index record
                for ( DynamicRecord valueRecord : block.getValueRecords() )
                {
                    assert valueRecord.inUse();
                    valueRecord.setInUse( false );
                    propRecord.addDeletedRecord( valueRecord );
                }
            }
            nextProp = propRecord.getNextProp();
            propRecord.setInUse( false );
            propRecord.setChanged();
            // We do not remove them individually, but all together here
            propRecord.getPropertyBlocks().clear();
        }
        return result;
    }

    private void disconnectRelationship( RelationshipRecord rel )
    {
        // update first node prev rel
        //                       ----------
        // [prevrel.firstNext]--/   [rel]  \-->[nextrel]
        if ( rel.getFirstPrevRel() != Record.NO_NEXT_RELATIONSHIP.intValue() && !rel.isFirstInFirstChain() )
        {   // This rel isn't the first in the first chain
            Relationship lockableRel = new LockableRelationship(
                rel.getFirstPrevRel() );
            getWriteLock( lockableRel );
            RelationshipRecord prevRel = getRelationshipRecord( rel.getFirstPrevRel(), false );
            boolean changed = false;
            long next = rel.getFirstNextRel();
            if ( prevRel.getFirstNode() == rel.getFirstNode() )
            {
                prevRel.setFirstNextRel( next );
                changed = true;
            }
            if ( prevRel.getSecondNode() == rel.getFirstNode() )
            {
                prevRel.setSecondNextRel( next );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( prevRel + " don't match " + rel );
            }
        }
        // update first node next rel
        //              ----------
        // [prevrel]<--/   [rel]  \--[nextrel.firstPrev]
        if ( rel.getFirstNextRel() != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {   // This rel isn't the last in the first chain
            Relationship lockableRel = new LockableRelationship(
                rel.getFirstNextRel() );
            getWriteLock( lockableRel );
            RelationshipRecord nextRel = getRelationshipRecord( rel.getFirstNextRel(), false );
            boolean changed = false;
            if ( nextRel.getFirstNode() == rel.getFirstNode() )
            {
                nextRel.setFirstPrevRel( rel.getFirstPrevRel() );
                nextRel.setFirstInFirstChain( rel.isFirstInFirstChain() );
                changed = true;
            }
            if ( nextRel.getSecondNode() == rel.getFirstNode() )
            {
                nextRel.setSecondPrevRel( rel.getFirstPrevRel() );
                nextRel.setFirstInSecondChain( rel.isFirstInFirstChain() );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( nextRel + " don't match " + rel );
            }
        }
        // update second node prev rel
        //                        ----------
        // [prevrel.secondNext]--/   [rel]  \-->[nextrel]
        if ( rel.getSecondPrevRel() != Record.NO_NEXT_RELATIONSHIP.intValue() && !rel.isFirstInSecondChain() )
        {   // This rel isn't the first in the second chain
            Relationship lockableRel = new LockableRelationship(
                rel.getSecondPrevRel() );
            getWriteLock( lockableRel );
            RelationshipRecord prevRel = getRelationshipRecord( rel.getSecondPrevRel(), false );
            boolean changed = false;
            if ( prevRel.getFirstNode() == rel.getSecondNode() )
            {
                prevRel.setFirstNextRel( rel.getSecondNextRel() );
                changed = true;
            }
            if ( prevRel.getSecondNode() == rel.getSecondNode() )
            {
                prevRel.setSecondNextRel( rel.getSecondNextRel() );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( prevRel + " don't match " + rel );
            }
        }
        // update second node next rel
        //              ----------
        // [prevrel]<--/   [rel]  \--[nextrel.secondPrev]
        if ( rel.getSecondNextRel() != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {   // This rel isn't the last in the second chain
            Relationship lockableRel = new LockableRelationship(
                rel.getSecondNextRel() );
            getWriteLock( lockableRel );
            RelationshipRecord nextRel = getRelationshipRecord( rel.getSecondNextRel(), false );
            boolean changed = false;
            if ( nextRel.getFirstNode() == rel.getSecondNode() )
            {
                nextRel.setFirstPrevRel( rel.getSecondPrevRel() );
                nextRel.setFirstInFirstChain( rel.isFirstInSecondChain() );
                changed = true;
            }
            if ( nextRel.getSecondNode() == rel.getSecondNode() )
            {
                nextRel.setSecondPrevRel( rel.getSecondPrevRel() );
                nextRel.setFirstInSecondChain( rel.isFirstInSecondChain() );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( nextRel + " don't match " + rel );
            }
        }
    }

    private void getWriteLock( Relationship lockableRel )
    {
        lockManager.getWriteLock( lockableRel );
        lockReleaser.addLockToTransaction( lockableRel, LockType.WRITE );
    }

//    public RelationshipLoadingPosition getRelationshipChainPosition( long nodeId )
//    {
//        NodeRecord nodeRecord = getCachedNodeRecord( nodeId );
//        if ( nodeRecord != null && nodeRecord.isCreated() )
//        {
//            return new SingleChainPosition( Record.NO_NEXT_RELATIONSHIP.intValue() );
//        }
//        return ReadTransaction.getRelationshipChainPosition( nodeId, neoStore );
//    }

    public Map<DirectionWrapper, Iterable<RelationshipRecord>> getMoreRelationships( long nodeId,
        RelationshipLoadingPosition position, DirectionWrapper direction, RelationshipType[] types )
    {
        return ReadTransaction.getMoreRelationships( nodeId, position, getRelGrabSize(), getRelationshipStore(),
                direction, types );
    }

    private void updateNodesForDeletedRelationship( RelationshipRecord rel )
    {
        NodeRecord firstNode = getNodeRecord( rel.getFirstNode(), false );
        NodeRecord secondNode = getNodeRecord( rel.getSecondNode(), false );
        
        boolean loop = firstNode.getId() == secondNode.getId();
        if ( !firstNode.isSuperNode() )
        {
            if ( rel.isFirstInFirstChain() ) firstNode.setNextRel( rel.getFirstNextRel() );
            decrementRelationshipCount( firstNode.getId(), rel, firstNode.getNextRel() );
        }
        else
        {
            Map<Integer, RelationshipGroupRecord> groups = getRelationshipGroups( firstNode );
            RelationshipGroupRecord group = groups.get( rel.getType() );
            assert group != null;
            DirectionWrapper dir = wrapDirection( rel, firstNode );
            if ( rel.isFirstInFirstChain() )
            {
                dir.setNextRel( group, rel.getFirstNextRel() );
                if ( groupIsEmpty( group ) ) deleteGroup( firstNode, group, groups );
            }
            decrementRelationshipCount( firstNode.getId(), rel, dir.getNextRel( group ) );
        }
        
        if ( !secondNode.isSuperNode() )
        {
            if ( rel.isFirstInSecondChain() ) secondNode.setNextRel( rel.getSecondNextRel() );
            if ( !loop ) decrementRelationshipCount( secondNode.getId(), rel, secondNode.getNextRel() );
        }
        else
        {
            Map<Integer, RelationshipGroupRecord> groups = getRelationshipGroups( secondNode );
            RelationshipGroupRecord group = groups.get( rel.getType() );
            DirectionWrapper dir = wrapDirection( rel, secondNode );
            assert group != null || loop : "Group has been deleted";
            if ( group != null )
            {
                if ( rel.isFirstInSecondChain() )
                {
                    dir.setNextRel( group, rel.getSecondNextRel() );
                    if ( groupIsEmpty( group ) ) deleteGroup( secondNode, group, groups );
                }
            } // Else this is a loop-rel and the group was deleted when dealing with the first node
            if ( !loop ) decrementRelationshipCount( secondNode.getId(), rel, dir.getNextRel( group ) );
        }
    }

    private void deleteGroup( NodeRecord node, RelationshipGroupRecord group, Map<Integer, RelationshipGroupRecord> groups )
    {
        long previous = group.getPrev();
        long next = group.getNext();
        if ( previous == Record.NO_NEXT_RELATIONSHIP.intValue() )
        {   // This is the first one, just point the node to the next group
            node.setNextRel( next );
        }
        else
        {   // There are others before it, point the previous to the next group
            RelationshipGroupRecord previousRecord = getRelationshipGroupRecord( previous, false );
            previousRecord.setNext( next );
        }
        
        if ( next != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            RelationshipGroupRecord nextRecord = getRelationshipGroupRecord( next, false );
            nextRecord.setPrev( Record.NO_NEXT_RELATIONSHIP.intValue() );
        }
        group.setInUse( false );
        groups.remove( group.getType() );
    }

    private boolean groupIsEmpty( RelationshipGroupRecord group )
    {
        return group.getNextOut() == Record.NO_NEXT_RELATIONSHIP.intValue() &&
               group.getNextIn() == Record.NO_NEXT_RELATIONSHIP.intValue() &&
               group.getNextLoop() == Record.NO_NEXT_RELATIONSHIP.intValue();
    }

    private DirectionWrapper wrapDirection( RelationshipRecord rel, NodeRecord firstNode )
    {
        boolean isOut = rel.getFirstNode() == firstNode.getId();
        boolean isIn = rel.getSecondNode() == firstNode.getId();
        assert isOut|isIn;
        if ( isOut&isIn ) return DirectionWrapper.BOTH;
        return isOut ? DirectionWrapper.OUTGOING : DirectionWrapper.INCOMING;
    }

    /**
     * @return true if last relationship
     */
    private boolean decrementRelationshipCount( long nodeId, RelationshipRecord rel, long firstRelId )
    {
        if ( firstRelId == Record.NO_PREV_RELATIONSHIP.intValue() ) return true;
        RelationshipRecord firstRel = getRelationshipRecord( firstRelId, false );
        if ( nodeId == firstRel.getFirstNode() )
        {
            firstRel.setFirstPrevRel( relIsFirstInChain( nodeId, rel ) ?
                    relCount( nodeId, rel )-1 : firstRel.getFirstPrevRel()-1 );
            firstRel.setFirstInFirstChain( true );
        }
        if ( nodeId == firstRel.getSecondNode() )
        {
            firstRel.setSecondPrevRel( relIsFirstInChain( nodeId, rel ) ?
                    relCount( nodeId, rel )-1 : firstRel.getSecondPrevRel()-1 );
            firstRel.setFirstInSecondChain( true );
        }
        return false;
    }

    @Override
    public void relRemoveProperty( long relId, PropertyData propertyData )
    {
        RelationshipRecord relRecord = getRelationshipRecord( relId, true );
        assert assertPropertyChain( relRecord );
        removeProperty( relRecord, propertyData, RecordAdder.RELATIONSHIP );
    }

    @Override
    public ArrayMap<Integer,PropertyData> relLoadProperties( long relId,
            boolean light )
    {
        RelationshipRecord relRecord = getRelationshipRecord( relId, true );
        if ( relRecord != null && relRecord.isCreated() ) return null;
        return ReadTransaction.loadProperties( getPropertyStore(), relRecord.getNextProp() );
    }

    @Override
    public ArrayMap<Integer,PropertyData> nodeLoadProperties( long nodeId, boolean light )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId, light );
        return ReadTransaction.loadProperties( getPropertyStore(), nodeRecord.getNextProp() );
    }

    public Object propertyGetValueOrNull( PropertyBlock block )
    {
        return block.getType().getValue( block,
                block.isLight() ? null : getPropertyStore() );
    }

    @Override
    public Object loadPropertyValue( PropertyData propertyData )
    {
        PropertyRecord propertyRecord = propertyRecords.get( propertyData.getId() );
        if ( propertyRecord == null )
        {
            propertyRecord = getPropertyStore().getRecord( propertyData.getId() );
        }
        PropertyBlock block = propertyRecord.getPropertyBlock( propertyData.getIndex() );
        if ( block == null )
        {
            throw new IllegalStateException( "Property with index["
                                             + propertyData.getIndex()
                                             + "] is not present in property["
                                             + propertyData.getId() + "]" );
        }
        if ( block.isLight() )
        {
            getPropertyStore().makeHeavy( block );
        }
        return block.getType().getValue( block, getPropertyStore() );
    }

    @Override
    public void nodeRemoveProperty( long nodeId, PropertyData propertyData )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId, true );
        assert assertPropertyChain( nodeRecord );
        removeProperty( nodeRecord, propertyData, RecordAdder.NODE );
        // propRecord.removeBlock( propertyData.getIndex() );
    }
    
    private void removeProperty( PrimitiveRecord hostRecord, PropertyData propertyData, RecordAdder adder )
    {
        long propertyId = propertyData.getId();
        PropertyRecord propRecord = getPropertyRecord( propertyId, false, true );
        adder.setId( propRecord, hostRecord.getId() );

        PropertyBlock block = propRecord.removePropertyBlock( propertyData.getIndex() );
        if ( block == null )
        {
            throw new IllegalStateException( "Property with index["
                                             + propertyData.getIndex()
                                             + "] is not present in property["
                                             + propertyId + "]" );
        }

        if ( block.isLight() )
        {
            getPropertyStore().makeHeavy( block );
        }
        for ( DynamicRecord valueRecord : block.getValueRecords() )
        {
            assert valueRecord.inUse();
            valueRecord.setInUse( false, block.getType().intValue() );
            propRecord.addDeletedRecord( valueRecord );
        }
        if ( propRecord.size() > 0 )
        {
            /*
             * There are remaining blocks in the record. We do not unlink yet.
             */
            propRecord.setChanged();
            assert assertPropertyChain( hostRecord );
        }
        else
        {
            if ( unlinkPropertyRecord( propRecord, hostRecord ) )
            {
                adder.add( this, hostRecord );
            }
        }
    }

    private boolean unlinkPropertyRecord( PropertyRecord propRecord,
            PrimitiveRecord primitive )
    {
        assert assertPropertyChain( primitive );
        assert propRecord.size() == 0;
        boolean primitiveChanged = false;
        long prevProp = propRecord.getPrevProp();
        long nextProp = propRecord.getNextProp();
        if ( primitive.getNextProp() == propRecord.getId() )
        {
            assert propRecord.getPrevProp() == Record.NO_PREVIOUS_PROPERTY.intValue() : propRecord
                                                                                        + " for "
                                                                                        + primitive;
            primitive.setNextProp( nextProp );
            primitiveChanged = true;
        }
        if ( prevProp != Record.NO_PREVIOUS_PROPERTY.intValue() )
        {
            PropertyRecord prevPropRecord = getPropertyRecord( prevProp, true, true );
            assert prevPropRecord.inUse() : prevPropRecord + "->" + propRecord
                                            + " for " + primitive;
            prevPropRecord.setNextProp( nextProp );
            prevPropRecord.setChanged();
        }
        if ( nextProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord nextPropRecord = getPropertyRecord( nextProp, true, true );
            assert nextPropRecord.inUse() : propRecord + "->" + nextPropRecord
                                            + " for " + primitive;
            nextPropRecord.setPrevProp( prevProp );
            nextPropRecord.setChanged();
        }
        propRecord.setInUse( false );
        /*
         *  The following two are not needed - the above line does all the work (PropertyStore
         *  does not write out the prev/next for !inUse records). It is nice to set this
         *  however to check for consistency when assertPropertyChain().
         */
        propRecord.setPrevProp( Record.NO_PREVIOUS_PROPERTY.intValue() );
        propRecord.setNextProp( Record.NO_NEXT_PROPERTY.intValue() );
        assert assertPropertyChain( primitive );
        return primitiveChanged;
    }

    @Override
    public PropertyData relChangeProperty( long relId,
            PropertyData propertyData, Object value )
    {
        RelationshipRecord relRecord = getRelationshipRecord( relId, true );
        return primitiveChangeProperty( relRecord, propertyData, value, RecordAdder.RELATIONSHIP );
    }

    @Override
    public PropertyData nodeChangeProperty( long nodeId,
            PropertyData propertyData, Object value )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId, true );
        return primitiveChangeProperty( nodeRecord, propertyData, value, RecordAdder.NODE );
    }

    private PropertyData primitiveChangeProperty( PrimitiveRecord primitive,
            PropertyData propertyData, Object value, RecordAdder adder )
    {
        assert assertPropertyChain( primitive );
        long propertyId = propertyData.getId();
        PropertyRecord propertyRecord = getPropertyRecord( propertyId, true, true );
        if ( !propertyRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to change property["
                                             + propertyId
                                             + "] since it has been deleted." );
        }
        adder.setId( propertyRecord, primitive.getId() );
        PropertyBlock block = propertyRecord.getPropertyBlock( propertyData.getIndex() );
        if ( block == null )
        {
            throw new IllegalStateException( "Property with index["
                                             + propertyData.getIndex()
                                             + "] is not present in property["
                                             + propertyId + "]" );
        }
        if ( block.isLight() )
        {
            getPropertyStore().makeHeavy( block );
        }
        propertyRecord.setChanged();
        for ( DynamicRecord record : block.getValueRecords() )
        {
            assert record.inUse();
            record.setInUse( false, block.getType().intValue() );
            propertyRecord.addDeletedRecord( record );
        }
        getPropertyStore().encodeValue( block, propertyData.getIndex(),
                value );
        if ( propertyRecord.size() > PropertyType.getPayloadSize() )
        {
            propertyRecord.removePropertyBlock( propertyData.getIndex() );
            /*
             * The record should never, ever be above max size. Less obviously, it should
             * never remain empty. If removing a property because it won't fit when changing
             * it leaves the record empty it means that this block was the last one which
             * means that it doesn't fit in an empty record. Where i come from, we call this
             * weird.
             *
             assert propertyRecord.size() <= PropertyType.getPayloadSize() : propertyRecord;
             assert propertyRecord.size() > 0 : propertyRecord;
             */
            propertyRecord = addPropertyBlockToPrimitive( block, primitive, adder );
        }
        assert assertPropertyChain( primitive );
        return block.newPropertyData( propertyRecord, value );
    }

    @Override
    public PropertyData relAddProperty( long relId,
            PropertyIndex index, Object value )
    {
        RelationshipRecord relRecord = getRelationshipRecord( relId, true );
        assert assertPropertyChain( relRecord );
        PropertyBlock block = new PropertyBlock();
        block.setCreated();
        getPropertyStore().encodeValue( block, index.getKeyId(), value );
        PropertyRecord host = addPropertyBlockToPrimitive( block, relRecord, RecordAdder.RELATIONSHIP );
        assert assertPropertyChain( relRecord );
        return block.newPropertyData( host, value );
    }

    @Override
    public PropertyData nodeAddProperty( long nodeId, PropertyIndex index,
        Object value )
    {
        NodeRecord nodeRecord = getNodeRecord( nodeId, true );
        assert assertPropertyChain( nodeRecord );
        PropertyBlock block = new PropertyBlock();
        block.setCreated();
        /*
         * Encoding has to be set here before anything is changed,
         * since an exception could be thrown in encodeValue now and tx not marked
         * rollback only.
         */
        getPropertyStore().encodeValue( block, index.getKeyId(), value );
        PropertyRecord host = addPropertyBlockToPrimitive( block, nodeRecord, RecordAdder.NODE );
        assert assertPropertyChain( nodeRecord );
        return block.newPropertyData( host, value );
    }

    private PropertyRecord addPropertyBlockToPrimitive( PropertyBlock block,
            PrimitiveRecord primitive, RecordAdder adder )
    {
        assert assertPropertyChain( primitive );
        int newBlockSizeInBytes = block.getSize();
        /*
         * Here we could either iterate over the whole chain or just go for the first record
         * which is the most likely to be the less full one. Currently we opt for the second
         * to perform better.
         */
        PropertyRecord host = null;
        long firstProp = primitive.getNextProp();
        if ( firstProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            // We do not store in map - might not have enough space
            PropertyRecord propRecord = getPropertyRecord( firstProp, false, false );
            assert propRecord.getPrevProp() == Record.NO_PREVIOUS_PROPERTY.intValue() : propRecord
                                                                                        + " for "
                                                                                        + primitive;
            assert propRecord.inUse() : propRecord;
            int propSize = propRecord.size();
            assert propSize > 0 : propRecord;
            if ( propSize + newBlockSizeInBytes <= PropertyType.getPayloadSize() )
            {
                host = propRecord;
                host.addPropertyBlock( block );
                host.setChanged();
            }
        }
        if ( host == null )
        {
            // First record in chain didn't fit, make new one
            host = new PropertyRecord( getPropertyStore().nextId() );
            host.setCreated();
            if ( primitive.getNextProp() != Record.NO_NEXT_PROPERTY.intValue() )
            {
                PropertyRecord prevProp = getPropertyRecord( primitive.getNextProp(), true, true );
                adder.add( this, primitive );
                assert prevProp.getPrevProp() == Record.NO_PREVIOUS_PROPERTY.intValue();
                prevProp.setPrevProp( host.getId() );
                host.setNextProp( prevProp.getId() );
                prevProp.setChanged();
            }
            primitive.setNextProp( host.getId() );
            host.addPropertyBlock( block );
            host.setInUse( true );
        }
        // Ok, here host does for the job. Use it
        adder.setId( host, primitive.getId() );
        cachePropertyRecord( host );
        assert assertPropertyChain( primitive );
        return host;
    }

    @Override
    public void relationshipCreate( long id, int type, long firstNodeId, long secondNodeId )
    {
        NodeRecord firstNode = getNodeRecord( firstNodeId, true );
        NodeRecord secondNode = getNodeRecord( secondNodeId, true );
        convertToSuperNodeIfNecessary( firstNode );
        convertToSuperNodeIfNecessary( secondNode );
        RelationshipRecord record = new RelationshipRecord( id, firstNodeId, secondNodeId, type );
        record.setInUse( true );
        record.setCreated();
        cacheRelationshipRecord( record );
        connectRelationship( firstNode, secondNode, record );
    }

    private void convertToSuperNodeIfNecessary( NodeRecord node )
    {
        if ( node.isSuperNode() ) return;
        long relId = node.getNextRel();
        if ( relId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            RelationshipRecord rel = getRelationshipRecord( relId, true );
            if ( relCount( node.getId(), rel ) >= neoStore.getSuperNodeThreshold() ) convertToSuperNode( node, rel );
        }
    }

    private void convertToSuperNode( NodeRecord node, RelationshipRecord firstRel )
    {
        node.setSuperNode( true );
        node.setNextRel( Record.NO_NEXT_RELATIONSHIP.intValue() );
        long relId = firstRel.getId();
        RelationshipRecord relRecord = firstRel;
        while ( relId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            getWriteLock( new LockableRelationship( relId ) );
            relId = relChain( relRecord, node.getId() ).getNext( relRecord );
            connectRelationshipToSuperNode( node, relRecord );
            if ( relId == Record.NO_NEXT_RELATIONSHIP.intValue() ) break;
            relRecord = getRelationshipRecord( relId, true );
        }
        if ( upgradedSuperNodes == null ) upgradedSuperNodes = new ArrayList<NodeRecord>();
        upgradedSuperNodes.add( node );
    }

    private void connectRelationship( NodeRecord firstNode,
        NodeRecord secondNode, RelationshipRecord rel )
    {
        // Assertion interpreted: if node is a normal node and we're trying to create a
        // relationship that we already have as first rel for that node --> error
        assert firstNode.getNextRel() != rel.getId() || firstNode.isSuperNode();
        assert secondNode.getNextRel() != rel.getId() || secondNode.isSuperNode();
        
        if ( !firstNode.isSuperNode() )
        {
            rel.setFirstNextRel( firstNode.getNextRel() );
            connect( firstNode.getId(), firstNode.getNextRel(), rel );
            firstNode.setNextRel( rel.getId() );
        }
        else
        {
            connectRelationshipToSuperNode( firstNode, rel );
        }
        
        if ( !secondNode.isSuperNode() )
        {
            rel.setSecondNextRel( secondNode.getNextRel() );
            if ( firstNode.getId() != secondNode.getId() )
            {
                connect( secondNode.getId(), secondNode.getNextRel(), rel );
            }
            else
            {
                rel.setFirstInSecondChain( true );
                rel.setSecondPrevRel( rel.getFirstPrevRel() );
            }
            secondNode.setNextRel( rel.getId() );
        }
        else if ( firstNode.getId() != secondNode.getId() )
        {
            connectRelationshipToSuperNode( secondNode, rel );
        }
    }

    private void connectRelationshipToSuperNode( NodeRecord node, RelationshipRecord rel )
    {
        RelationshipGroupRecord group = getOrCreateRelationshipGroup( node, rel.getType() );
        DirectionWrapper dir = wrapDirection( rel, node );
        long nextRel = dir.getNextRel( group );
        setCorrectNextRel( node, rel, nextRel );
        connect( node.getId(), nextRel, rel );
        dir.setNextRel( group, rel.getId() );
    }
    
    private void setCorrectNextRel( NodeRecord node, RelationshipRecord rel, long nextRel )
    {
        if ( node.getId() == rel.getFirstNode() ) rel.setFirstNextRel( nextRel );
        if ( node.getId() == rel.getSecondNode() ) rel.setSecondNextRel( nextRel );
    }

    private RelationshipGroupRecord getOrCreateRelationshipGroup( NodeRecord node, int type )
    {
        Map<Integer, RelationshipGroupRecord> groups = getRelationshipGroups( node );
        RelationshipGroupRecord record = groups.get( type );
        if ( record == null )
        {
            record = createRelationshipGroupRecord( node, type );
            groups.put( type, record );
        }
        return record;
    }

    private RelationshipGroupRecord createRelationshipGroupRecord( NodeRecord node, int type )
    {
        assert node.isSuperNode();
        long id = neoStore.getRelationshipGroupStore().nextId();
        long firstGroupId = node.getNextRel();
        RelationshipGroupRecord record = new RelationshipGroupRecord( id, type );
        record.setInUse( true );
        cacheRelationshipGroupRecord( id, record );
        if ( firstGroupId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {   // There are others, make way for this new group
            RelationshipGroupRecord previousFirstRecord = getRelationshipGroupRecord( firstGroupId, true );
            record.setNext( previousFirstRecord.getId() );
        }
        node.setNextRel( id );
        return record;
    }

    private Map<Integer, RelationshipGroupRecord> getRelationshipGroups( NodeRecord node )
    {
        Map<Integer, RelationshipGroupRecord> result = relGroupCache.get( node.getId() );
        if ( result == null )
        {
            assert node.isSuperNode();
            result = loadBlaRelationshipGroups( node.getId(), node.getNextRel() );
            relGroupCache.put( node.getId(), result );
        }
        return result;
    }
    
    private Map<Integer, RelationshipGroupRecord> loadBlaRelationshipGroups( long id, long firstGroup )
    {
        long groupId = firstGroup;
        long previousGroupId = Record.NO_NEXT_RELATIONSHIP.intValue();
        Map<Integer, RelationshipGroupRecord> result = new HashMap<Integer, RelationshipGroupRecord>();
        while ( groupId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            RelationshipGroupRecord record = getRelationshipGroupRecord( groupId, true );
            record.setPrev( previousGroupId );
            result.put( record.getType(), record );
            previousGroupId = groupId;
            groupId = record.getNext();
        }
        return result;
    }

    @Override
    public Map<Integer, RelationshipGroupRecord> loadRelationshipGroups( long node, long firstGroup )
    {
        return ReadTransaction.loadRelationshipGroups( node, firstGroup, getRelationshipGroupStore() );
    }

    private RelationshipGroupRecord getCachedRelationshipGroupRecord( long groupId )
    {
        return relGroupRecords.get( groupId );
    }
    
    private RelationshipGroupRecord getRelationshipGroupRecord( long groupId, boolean checkInUse )
    {
        RelationshipGroupRecord record = getCachedRelationshipGroupRecord( groupId );
        if ( record == null )
        {
            record = getRelationshipGroupStore().getRecord( groupId );
            cacheRelationshipGroupRecord( groupId, record );
        }
        if ( checkInUse && !record.inUse() ) throw new IllegalStateException( "RelationshipGroup[" + groupId +
                "] is deleted" );
        return record;
    }

    private void cacheRelationshipGroupRecord( long groupId, RelationshipGroupRecord record )
    {
        relGroupRecords.put( groupId, record );
    }

    private void connect( long nodeId, long nodeFirstRelId, RelationshipRecord rel )
    {
        long newCount = 1;
        if ( nodeFirstRelId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            Relationship lockableRel = new LockableRelationship( nodeFirstRelId );
            getWriteLock( lockableRel );
            RelationshipRecord firstRel = getRelationshipRecord( nodeFirstRelId, false );
            boolean changed = false;
            if ( firstRel.getFirstNode() == nodeId )
            {
                newCount = firstRel.getFirstPrevRel()+1;
                firstRel.setFirstPrevRel( rel.getId() );
                firstRel.setFirstInFirstChain( false );
                changed = true;
            }
            if ( firstRel.getSecondNode() == nodeId )
            {
                newCount = firstRel.getSecondPrevRel()+1;
                firstRel.setSecondPrevRel( rel.getId() );
                firstRel.setFirstInSecondChain( false );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( nodeId + " dont match " + firstRel );
            }
        }
        setRelationshipCount( nodeId, rel, newCount );
    }

    private void setRelationshipCount( long nodeId, RelationshipRecord rel, long count )
    {
        if ( rel.getFirstNode() == nodeId )
        {
            rel.setFirstPrevRel( count );
            rel.setFirstInFirstChain( true );
        }
        if ( rel.getSecondNode() == nodeId )
        {
            rel.setSecondPrevRel( count );
            rel.setFirstInSecondChain( true );
        }
    }

    @Override
    public void nodeCreate( long nodeId )
    {
        NodeRecord nodeRecord = new NodeRecord( nodeId, false,
                Record.NO_NEXT_RELATIONSHIP.intValue(), Record.NO_NEXT_PROPERTY.intValue() );
        nodeRecord.setInUse( true );
        nodeRecord.setCreated();
        cacheNodeRecord( nodeRecord );
    }

    @Override
    public String loadIndex( int id )
    {
        PropertyIndexStore indexStore = getPropertyStore().getIndexStore();
        PropertyIndexRecord index = getPropertyIndexRecord( id );
        if ( index == null )
        {
            index = indexStore.getRecord( id );
        }
        if ( index.isLight() )
        {
            indexStore.makeHeavy( index );
        }
        return indexStore.getStringFor( index );
    }

    @Override
    public NameData[] loadPropertyIndexes( int count )
    {
        PropertyIndexStore indexStore = getPropertyStore().getIndexStore();
        return indexStore.getNames( count );
    }

    @Override
    public void createPropertyIndex( String key, int id )
    {
        PropertyIndexRecord record = new PropertyIndexRecord( id );
        record.setInUse( true );
        record.setCreated();
        PropertyIndexStore propIndexStore = getPropertyStore().getIndexStore();
        int nameId = propIndexStore.nextNameId();
        record.setNameId( nameId );
        Collection<DynamicRecord> nameRecords =
            propIndexStore.allocateNameRecords( nameId, encodeString( key ) );
        for ( DynamicRecord keyRecord : nameRecords )
        {
            record.addNameRecord( keyRecord );
        }
        addPropertyIndexRecord( record );
    }

    @Override
    public void createRelationshipType( int id, String name )
    {
        RelationshipTypeRecord record = new RelationshipTypeRecord( id );
        record.setInUse( true );
        record.setCreated();
        int nameId = (int) getRelationshipTypeStore().nextNameId();
        record.setNameId( nameId );
//        int length = name.length();
//        char[] chars = new char[length];
//        name.getChars( 0, length, chars, 0 );
        Collection<DynamicRecord> typeNameRecords =
            getRelationshipTypeStore().allocateNameRecords( nameId, encodeString( name ) );
        for ( DynamicRecord typeRecord : typeNameRecords )
        {
            record.addNameRecord( typeRecord );
        }
        addRelationshipTypeRecord( record );
    }

    static class CommandSorter implements Comparator<Command>, Serializable
    {
        public int compare( Command o1, Command o2 )
        {
            long id1 = o1.getKey();
            long id2 = o2.getKey();
            long diff = id1 - id2;
            if ( diff > Integer.MAX_VALUE )
            {
                return Integer.MAX_VALUE;
            }
            else if ( diff < Integer.MIN_VALUE )
            {
                return Integer.MIN_VALUE;
            }
            else
            {
                return (int) diff;
            }
        }

        @Override
        public boolean equals( Object o )
        {
            if ( o instanceof CommandSorter )
            {
                return true;
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return 3217;
        }
    }

    void cacheNodeRecord( NodeRecord record )
    {
        nodeRecords.put( record.getId(), record );
    }

    NodeRecord getCachedNodeRecord( long nodeId )
    {
        return nodeRecords.get( nodeId );
    }

    NodeRecord getNodeRecord( long id, boolean checkInUse )
    {
        NodeRecord node = getCachedNodeRecord( id );
        if ( node == null )
        {
            node = getNodeStore().getRecord( id );
            cacheNodeRecord( node );
        }
        if ( checkInUse && !node.inUse() ) throw new IllegalStateException( "Node[" + id + "] is deleted" );
        return node;
    }
    
    void cacheRelationshipRecord( RelationshipRecord record )
    {
        relRecords.put( record.getId(), record );
    }

    RelationshipRecord getCachedRelationshipRecord( long relId )
    {
        return relRecords.get( relId );
    }
    
    RelationshipRecord getRelationshipRecord( long id, boolean checkInUse )
    {
        RelationshipRecord record = getCachedRelationshipRecord( id );
        if ( record == null )
        {
            record = getRelationshipStore().getRecord( id );
            cacheRelationshipRecord( record );
        }
        if ( checkInUse && !record.inUse() ) throw new IllegalStateException( "Relationship[" + id + "] has been deleted" );
        return record;
    }

    void cachePropertyRecord( PropertyRecord record )
    {
        propertyRecords.put( record.getId(), record );
    }

    PropertyRecord getPropertyRecord( long propertyId, boolean light, boolean store )
    {
        PropertyRecord result = propertyRecords.get( propertyId );
        if ( result == null )
        {
            if ( light )
            {
                result = getPropertyStore().getLightRecord( propertyId );
            }
            else
            {
                result = getPropertyStore().getRecord( propertyId );
            }
            if ( store )
            {
                cachePropertyRecord( result );
            }
        }
        return result;
    }

    void addRelationshipTypeRecord( RelationshipTypeRecord record )
    {
        if ( relTypeRecords == null ) relTypeRecords = new HashMap<Integer, RelationshipTypeRecord>();
        relTypeRecords.put( record.getId(), record );
    }

    void addPropertyIndexRecord( PropertyIndexRecord record )
    {
        if ( propIndexRecords == null ) propIndexRecords = new HashMap<Integer, PropertyIndexRecord>();
        propIndexRecords.put( record.getId(), record );
    }

    PropertyIndexRecord getPropertyIndexRecord( int id )
    {
        return propIndexRecords.get( id );
    }

    private static class LockableRelationship implements Relationship
    {
        private final long id;

        LockableRelationship( long id )
        {
            this.id = id;
        }

        @Override
        public void delete()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Node getEndNode()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public long getId()
        {
            return this.id;
        }

        @Override
        public GraphDatabaseService getGraphDatabase()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Node[] getNodes()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Node getOtherNode( Node node )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Object getProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Object getProperty( String key, Object defaultValue )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Iterable<String> getPropertyKeys()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Iterable<Object> getPropertyValues()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Node getStartNode()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public RelationshipType getType()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public boolean isType( RelationshipType type )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public boolean hasProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Object removeProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public void setProperty( String key, Object value )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public boolean equals( Object o )
        {
            if ( !(o instanceof Relationship) )
            {
                return false;
            }
            return this.getId() == ((Relationship) o).getId();
        }

        @Override
        public int hashCode()
        {
            return (int) (( id >>> 32 ) ^ id );
        }

        @Override
        public String toString()
        {
            return "Lockable relationship #" + this.getId();
        }
    }

    @Override
    public RelIdArray getCreatedNodes()
    {
        RelIdArray createdNodes = new RelIdArray( null );
        for ( NodeRecord record : nodeRecords.values() )
        {
            if ( record.isCreated() )
            {
                // TODO Direction doesn't matter... misuse of RelIdArray?
                createdNodes.add( record.getId(), DirectionWrapper.OUTGOING );
            }
        }
        return createdNodes;
    }

    @Override
    public boolean isNodeCreated( long nodeId )
    {
        NodeRecord record = nodeRecords.get( nodeId );
        if ( record != null )
        {
            return record.isCreated();
        }
        return false;
    }

    @Override
    public boolean isRelationshipCreated( long relId )
    {
        RelationshipRecord record = relRecords.get( relId );
        if ( record != null )
        {
            return record.isCreated();
        }
        return false;
    }

    @Override
    public int getKeyIdForProperty( PropertyData property )
    {
        return ReadTransaction.getKeyIdForProperty( property,
                getPropertyStore() );
    }

    @Override
    public XAResource getXAResource()
    {
        return xaConnection.getXaResource();
    }

    @Override
    public void destroy()
    {
        xaConnection.destroy();
    }

    @Override
    public void setXaConnection( XaConnection connection )
    {
        this.xaConnection = connection;
    }

    @Override
    public NameData[] loadRelationshipTypes()
    {
        NameData relTypeData[] = neoStore.getRelationshipTypeStore().getNames( Integer.MAX_VALUE );
        NameData rawRelTypeData[] = new NameData[relTypeData.length];
        for ( int i = 0; i < relTypeData.length; i++ )
        {
            rawRelTypeData[i] = new NameData( relTypeData[i].getId(), relTypeData[i].getName() );
        }
        return rawRelTypeData;
    }

    private boolean assertPropertyChain( PrimitiveRecord primitive )
    {
        List<PropertyRecord> toCheck = new LinkedList<PropertyRecord>();
        long nextIdToFetch = primitive.getNextProp();
        while ( nextIdToFetch != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord toAdd = getPropertyRecord( nextIdToFetch, true, false );
            toCheck.add( toAdd );
            assert toAdd.inUse() : primitive + "->"
                                   + Arrays.toString( toCheck.toArray() );
            nextIdToFetch = toAdd.getNextProp();
        }
        if ( toCheck.isEmpty() )
        {
            assert primitive.getNextProp() == Record.NO_NEXT_PROPERTY.intValue() : primitive;
            return true;
        }
        PropertyRecord first = toCheck.get( 0 );
        PropertyRecord last = toCheck.get( toCheck.size() - 1 );
        assert first.getPrevProp() == Record.NO_PREVIOUS_PROPERTY.intValue() : primitive
                                                                               + "->"
                                                                               + Arrays.toString( toCheck.toArray() );
        assert last.getNextProp() == Record.NO_NEXT_PROPERTY.intValue() : primitive
                                                                          + "->"
                                                                          + Arrays.toString( toCheck.toArray() );
        PropertyRecord current, previous = first;
        for ( int i = 1; i < toCheck.size(); i++ )
        {
            current = toCheck.get( i );
            assert current.getPrevProp() == previous.getId() : primitive
                                                               + "->"
                                                               + Arrays.toString( toCheck.toArray() );
            assert previous.getNextProp() == current.getId() : primitive
                                                               + "->"
                                                               + Arrays.toString( toCheck.toArray() );
            previous = current;
        }
        return true;
    }
    
    @Override
    public int getRelationshipCount( long id, int type, DirectionWrapper direction )
    {
        NodeRecord node = getNodeRecord( id, true );
        long nextRel = node.getNextRel();
        if ( nextRel == Record.NO_NEXT_RELATIONSHIP.intValue() ) return 0;
        if ( !node.isSuperNode() )
        {
            assert type == -1;
            assert direction == DirectionWrapper.BOTH;
            return relCount( id, nextRel );
        }
        else
        {
            Map<Integer, RelationshipGroupRecord> groups = getRelationshipGroups( node );
            if ( type == -1 && direction == null )
            {   // Count for all types/directions
                int count = 0;
                for ( RelationshipGroupRecord group : groups.values() )
                {
                    count += relCount( id, group.getNextOut() );
                    count += relCount( id, group.getNextIn() );
                    count += relCount( id, group.getNextLoop() );
                }
                return count;
            }
            else if ( type == -1 )
            {   // Count for all types with a given direction
                int count = 0;
                for ( RelationshipGroupRecord group : groups.values() )
                {
                    count += relCount( id, group, direction );
                }
                return count;
            }
            else if ( direction == null )
            {   // Count for a type
                RelationshipGroupRecord group = groups.get( type );
                if ( group == null ) return 0;
                int count = 0;
                count += relCount( id, group.getNextOut() );
                count += relCount( id, group.getNextIn() );
                count += relCount( id, group.getNextLoop() );
                return count;
            }
            else
            {   // Count for one type and direction
                RelationshipGroupRecord group = groups.get( type );
                if ( group == null ) return 0;
                return relCount( id, group, direction );
            }
        }
    }
    
    private int relCount( long nodeId, RelationshipGroupRecord group, DirectionWrapper direction )
    {
        if ( direction == DirectionWrapper.BOTH )
        {
            return relCount( nodeId, DirectionWrapper.OUTGOING.getNextRel( group ) ) +
                    relCount( nodeId, DirectionWrapper.INCOMING.getNextRel( group ) ) +
                    relCount( nodeId, DirectionWrapper.BOTH.getNextRel( group ) );
        }
        else
        {
            return relCount( nodeId, direction.getNextRel( group ) ) +
                    relCount( nodeId, DirectionWrapper.BOTH.getNextRel( group ) );
        }
    }

    private int relCount( long nodeId, long relId )
    {
        if ( relId == Record.NO_NEXT_RELATIONSHIP.intValue() ) return 0;
        return relCount( nodeId, getRelationshipRecord( relId, true ) );
    }
    
    private int relCount( long nodeId, RelationshipRecord rel )
    {
        return (int) (nodeId == rel.getFirstNode() ? rel.getFirstPrevRel() : rel.getSecondPrevRel());
    }
    
    private boolean relIsFirstInChain( long nodeId, RelationshipRecord rel )
    {
        return (nodeId == rel.getFirstNode() && rel.isFirstInFirstChain()) ||
               (nodeId == rel.getSecondNode() && rel.isFirstInSecondChain());
    }
    
    private static enum Chain
    {
        FIRST
        {
            @Override
            long getPrev( RelationshipRecord rel )
            {
                return rel.getFirstPrevRel();
            }

            @Override
            void setPrev( RelationshipRecord rel, long id )
            {
                rel.setFirstPrevRel( id );
            }

            @Override
            long getNext( RelationshipRecord rel )
            {
                return rel.getFirstNextRel();
            }

            @Override
            void setNext( RelationshipRecord rel, long id )
            {
                rel.setFirstNextRel( id );
            }
        },
        SECOND
        {
            @Override
            long getPrev( RelationshipRecord rel )
            {
                return rel.getSecondPrevRel();
            }

            @Override
            void setPrev( RelationshipRecord rel, long id )
            {
                rel.setSecondPrevRel( id );
            }

            @Override
            long getNext( RelationshipRecord rel )
            {
                return rel.getSecondNextRel();
            }

            @Override
            void setNext( RelationshipRecord rel, long id )
            {
                rel.setSecondNextRel( id );
            }
        };
        
        abstract long getPrev( RelationshipRecord rel );
        
        abstract void setPrev( RelationshipRecord rel, long id );
        
        abstract long getNext( RelationshipRecord rel );
        
        abstract void setNext( RelationshipRecord rel, long id );
    }
    
    private static Chain relChain( RelationshipRecord rel, long nodeId )
    {
        if ( rel.getFirstNode() == nodeId ) return Chain.FIRST;
        if ( rel.getSecondNode() == nodeId ) return Chain.SECOND;
        throw new RuntimeException( nodeId + " neither first not second in " + rel );
    }

    @Override
    public Integer[] getRelationshipTypes( long id )
    {
        Map<Integer, RelationshipGroupRecord> groups = getRelationshipGroups( getNodeRecord( id, true ) );
        Integer[] types = new Integer[groups.size()];
        int i = 0;
        for ( Integer type : groups.keySet() ) types[i++] = type;
        return types;
    }
    
    private NeoStoreRecord getOrLoadNeoStoreRecord()
    {
        if ( neoStoreRecord == null )
        {
            neoStoreRecord = neoStore.asRecord();
        }
        return neoStoreRecord;
    }
    
    @Override
    public PropertyData graphAddProperty( PropertyIndex index, Object value )
    {
        PropertyBlock block = new PropertyBlock();
        block.setCreated();
        /*
         * Encoding has to be set here before anything is changed,
         * since an exception could be thrown in encodeValue now and tx not marked
         * rollback only.
         */
        getPropertyStore().encodeValue( block, index.getKeyId(), value );
        NeoStoreRecord record = getOrLoadNeoStoreRecord();
        PropertyRecord host = addPropertyBlockToPrimitive( block, record, RecordAdder.GRAPH );
        assert assertPropertyChain( record );
        return block.newPropertyData( host, value );
    }

    @Override
    public PropertyData graphChangeProperty( PropertyData propertyData, Object value )
    {
        return primitiveChangeProperty( getOrLoadNeoStoreRecord(), propertyData, value, RecordAdder.GRAPH );
    }

    @Override
    public void graphRemoveProperty( PropertyData propertyData )
    {
        removeProperty( getOrLoadNeoStoreRecord(), propertyData, RecordAdder.GRAPH );
    }
    
    @Override
    public ArrayMap<Integer, PropertyData> graphLoadProperties( boolean light )
    {
        return ReadTransaction.loadProperties( getPropertyStore(), getOrLoadNeoStoreRecord().getNextProp() );
    }

    private static enum RecordAdder
    {
        NODE
        {
            @Override
            void add( WriteTransaction tx, PrimitiveRecord record )
            {
                tx.cacheNodeRecord( (NodeRecord) record );
            }

            @Override
            void setId( PropertyRecord record, long id )
            {
                record.setNodeId( id );
            }
        },
        RELATIONSHIP
        {
            @Override
            void add( WriteTransaction tx, PrimitiveRecord record )
            {
                tx.cacheRelationshipRecord( (RelationshipRecord) record );
            }

            @Override
            void setId( PropertyRecord record, long id )
            {
                record.setRelId( id );
            }
        },
        GRAPH
        {
            @Override
            void add( WriteTransaction tx, PrimitiveRecord record )
            {
                tx.neoStoreRecord = (NeoStoreRecord) record;
            }

            @Override
            void setId( PropertyRecord record, long id )
            {
                record.setNodeId( -1 );
            }
        };
        
        abstract void add( WriteTransaction tx, PrimitiveRecord record );
        
        abstract void setId( PropertyRecord record, long id );
    }
}
