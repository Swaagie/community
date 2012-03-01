/**
 * Copyright (c) 2002-2012 "Neo Technology,"
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
package org.neo4j.kernel.impl.nioneo.store;

import java.util.ArrayList;
import java.util.List;

import org.neo4j.kernel.IdGeneratorFactory;
import org.neo4j.kernel.IdType;
import org.neo4j.kernel.impl.util.StringLogger;

/**
 * Implementation of the relationship store.
 */
public class RelationshipStore extends AbstractStore implements Store, RecordStore<RelationshipRecord>
{
    public interface Configuration
        extends AbstractStore.Configuration
    {
        
    }
    
    public static final String TYPE_DESCRIPTOR = "RelationshipStore";
    public static final String FILE_NAME = ".relationshipstore.db";

    /*
     * 1: inUse, high order bits from first node and next prop
     * 4: start node low order bits
     * 4: end node low order bits
     * 4: 1:st in start node chain, rest of high order bits, 1:st in end node chain, type
     * 4: start node prev rel low order bits
     * 4: start node next rel low order bits
     * 4: end node prev rel low order bits
     * 4: end node next rel low order bits
     * 4: first prop low order bits
     * --
     * 33
     */
    public static final int RECORD_SIZE = 33;

    public RelationshipStore(String fileName, Configuration configuration, IdGeneratorFactory idGeneratorFactory, FileSystemAbstraction fileSystemAbstraction, StringLogger stringLogger)
    {
        super(fileName, configuration, IdType.RELATIONSHIP, idGeneratorFactory, fileSystemAbstraction, stringLogger);
    }

    @Override
    public void accept( RecordStore.Processor processor, RelationshipRecord record )
    {
        processor.processRelationship( this, record );
    }

    @Override
    public String getTypeDescriptor()
    {
        return TYPE_DESCRIPTOR;
    }

    @Override
    public int getRecordSize()
    {
        return RECORD_SIZE;
    }

    @Override
    public int getRecordHeaderSize()
    {
        return getRecordSize();
    }

    @Override
    public void close()
    {
        super.close();
    }

    public RelationshipRecord getRecord( long id )
    {
        PersistenceWindow window = acquireWindow( id, OperationType.READ );
        try
        {
            return getRecord( id, window, RecordLoad.NORMAL );
        }
        finally
        {
            releaseWindow( window );
        }
    }

    @Override
    public RelationshipRecord forceGetRecord( long id )
    {
        PersistenceWindow window = null;
        try
        {
            window = acquireWindow( id, OperationType.READ );
        }
        catch ( InvalidRecordException e )
        {
            return new RelationshipRecord( id, -1, -1, -1 );
        }

        try
        {
            return getRecord( id, window, RecordLoad.FORCE );
        }
        finally
        {
            releaseWindow( window );
        }
    }

    @Override
    public RelationshipRecord forceGetRaw( long id )
    {
        return forceGetRecord( id );
    }

    public RelationshipRecord getLightRel( long id )
    {
        PersistenceWindow window = null;
        try
        {
            window = acquireWindow( id, OperationType.READ );
        }
        catch ( InvalidRecordException e )
        {
            // ok to high id
            return null;
        }
        try
        {
            RelationshipRecord record = getRecord( id, window, RecordLoad.CHECK );
            return record;
        }
        finally
        {
            releaseWindow( window );
        }
    }

    public void updateRecord( RelationshipRecord record, boolean recovered )
    {
        assert recovered;
        setRecovered();
        try
        {
            updateRecord( record );
            registerIdFromUpdateRecord( record.getId() );
        }
        finally
        {
            unsetRecovered();
        }
    }

    public void updateRecord( RelationshipRecord record )
    {
        PersistenceWindow window = acquireWindow( record.getId(),
            OperationType.WRITE );
        try
        {
            updateRecord( record, window, false );
        }
        finally
        {
            releaseWindow( window );
        }
    }

    @Override
    public void forceUpdateRecord( RelationshipRecord record )
    {
        PersistenceWindow window = acquireWindow( record.getId(),
                OperationType.WRITE );
        try
        {
            updateRecord( record, window, true );
        }
        finally
        {
            releaseWindow( window );
        }
    }

    private void updateRecord( RelationshipRecord record,
        PersistenceWindow window, boolean force )
    {
        long id = record.getId();
        Buffer buffer = window.getOffsettedBuffer( id );
        if ( record.inUse() || force )
        {
            long firstNode = record.getStartNode();
            short firstNodeMod = (short)((firstNode & 0x700000000L) >> 31);

            long endNode = record.getEndNode();
            long endNodeMod = (endNode & 0x700000000L) >> 4;

            long startNodePrevRel = record.getStartNodePrevRel();
            long startNodePrevRelMod = startNodePrevRel == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (startNodePrevRel & 0x700000000L) >> 7;

            long startNodeNextRel = record.getStartNodeNextRel();
            long startNodeNextRelMod = startNodeNextRel == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (startNodeNextRel & 0x700000000L) >> 10;

            long endNodePrevRel = record.getEndNodePrevRel();
            long endNodePrevRelMod = endNodePrevRel == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (endNodePrevRel & 0x700000000L) >> 13;

            long endNodeNextRel = record.getEndNodeNextRel();
            long endNodeNextRelMod = endNodeNextRel == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (endNodeNextRel & 0x700000000L) >> 16;

            long firstProp = record.getFirstProp();
            long firstPropMod = firstProp == Record.NO_NEXT_PROPERTY.intValue() ? 0 : (firstProp & 0xF00000000L) >> 28;
            
            long firstInStartNodeChain = record.isFirstInStartNodeChain() ? 0x80000000 : 0;
            long firstInEndNodeChain = record.isFirstInEndNodeChain() ? 0x8000 : 0;

            // [    ,   x] in use flag
            // [    ,xxx ] start node high order bits
            // [xxxx,    ] next prop high order bits
            short inUseUnsignedByte = (short)((record.inUse() ? Record.IN_USE : Record.NOT_IN_USE).byteValue() | firstNodeMod | firstPropMod);

            // [x   ,    ][    ,    ][    ,    ][    ,    ] 1: first in start node chain, 0: not first
            // [ xxx,    ][    ,    ][    ,    ][    ,    ] end node high order bits,     0x70000000
            // [    ,xxx ][    ,    ][    ,    ][    ,    ] start node prev rel high order bits,  0xE000000
            // [    ,   x][xx  ,    ][    ,    ][    ,    ] start node next rel high order bits,  0x1C00000
            // [    ,    ][  xx,x   ][    ,    ][    ,    ] end node prev rel high order bits, 0x380000
            // [    ,    ][    , xxx][    ,    ][    ,    ] end node next rel high order bits, 0x70000
            // [    ,    ][    ,    ][x   ,    ][    ,    ] 1: first in end node chain, 0: not first
            // [    ,    ][    ,    ][ xxx,xxxx][xxxx,xxxx] type
            int typeInt = (int)(firstInStartNodeChain | endNodeMod | startNodePrevRelMod | startNodeNextRelMod | endNodePrevRelMod | endNodeNextRelMod | firstInEndNodeChain | record.getType());
            
            buffer.put( (byte)inUseUnsignedByte ).putInt( (int) firstNode ).putInt( (int) endNode )
                .putInt( typeInt ).putInt( (int) startNodePrevRel ).putInt( (int) startNodeNextRel )
                .putInt( (int) endNodePrevRel ).putInt( (int) endNodeNextRel ).putInt( (int) firstProp );
        }
        else
        {
            buffer.put( Record.NOT_IN_USE.byteValue() );
            if ( !isInRecoveryMode() )
            {
                freeId( id );
            }
        }
    }

    private RelationshipRecord getRecord( long id, PersistenceWindow window,
        RecordLoad load )
    {
        Buffer buffer = window.getOffsettedBuffer( id );

        // [    ,   x] in use flag
        // [    ,xxx ] first node high order bits
        // [xxxx,    ] next prop high order bits
        long inUseByte = buffer.get();

        boolean inUse = (inUseByte & 0x1) == Record.IN_USE.intValue();
        if ( !inUse )
        {
            switch ( load )
            {
            case NORMAL:
                throw new InvalidRecordException( "RelationshipRecord[" + id + "] not in use" );
            case CHECK:
                return null;
            }
        }

        long startNode = buffer.getUnsignedInt();
        long startNodeMod = (inUseByte & 0xEL) << 31;

        long endNode = buffer.getUnsignedInt();

        // [x   ,    ][    ,    ][    ,    ][    ,    ] 1: first in start node chain, 0: not first
        // [ xxx,    ][    ,    ][    ,    ][    ,    ] end node high order bits,     0x70000000
        // [    ,xxx ][    ,    ][    ,    ][    ,    ] start node prev rel high order bits,  0xE000000
        // [    ,   x][xx  ,    ][    ,    ][    ,    ] start node next rel high order bits,  0x1C00000
        // [    ,    ][  xx,x   ][    ,    ][    ,    ] end node prev rel high order bits, 0x380000
        // [    ,    ][    , xxx][    ,    ][    ,    ] end node next rel high order bits, 0x70000
        // [    ,    ][    ,    ][x   ,    ][    ,    ] 1: first in end node chain, 0: not first
        // [    ,    ][    ,    ][ xxx,xxxx][xxxx,xxxx] type
        long typeInt = buffer.getInt();
        long endNodeMod = (typeInt & 0x70000000L) << 4;
        int type = (int)(typeInt & 0x7FFF);

        RelationshipRecord record = new RelationshipRecord( id,
            longFromIntAndMod( startNode, startNodeMod ),
            longFromIntAndMod( endNode, endNodeMod ), type );
        record.setInUse( inUse );
        
        record.setFirstInStartNodeChain( (typeInt & 0x80000000) != 0 );
        record.setFirstInEndNodeChain( (typeInt & 0x8000) != 0 );

        long startNodePrevRel = buffer.getUnsignedInt();
        long startNodeNextRel = buffer.getUnsignedInt();
        long endNodePrevRel = buffer.getUnsignedInt();
        long endNodeNextRel = buffer.getUnsignedInt();
        long firstProp = buffer.getUnsignedInt();
        
        long startNodePrevRelMod = (typeInt & 0xE000000L) << 7;
        record.setStartNodePrevRel( longFromIntAndMod( startNodePrevRel, startNodePrevRelMod ) );

        long startNodeNextRelMod = (typeInt & 0x1C00000L) << 10;
        record.setStartNodeNextRel( longFromIntAndMod( startNodeNextRel, startNodeNextRelMod ) );

        long endNodePrevRelMod = (typeInt & 0x380000L) << 13;
        record.setEndNodePrevRel( longFromIntAndMod( endNodePrevRel, endNodePrevRelMod ) );

        long endNodeNextRelMod = (typeInt & 0x70000L) << 16;
        record.setEndNodeNextRel( longFromIntAndMod( endNodeNextRel, endNodeNextRelMod ) );

        long firstPropMod = (inUseByte & 0xF0L) << 28;
        record.setFirstProp( longFromIntAndMod( firstProp, firstPropMod ) );
        
        return record;
    }

    public RelationshipRecord getChainRecord( long relId )
    {
        PersistenceWindow window = null;
        try
        {
            window = acquireWindow( relId, OperationType.READ );
        }
        catch ( InvalidRecordException e )
        {
            // ok to high id
            return null;
        }
        try
        {
//            return getFullRecord( relId, window );
            return getRecord( relId, window, RecordLoad.NORMAL );
        }
        finally
        {
            releaseWindow( window );
        }
    }

    @Override
    public List<WindowPoolStats> getAllWindowPoolStats()
    {
        List<WindowPoolStats> list = new ArrayList<WindowPoolStats>();
        list.add( getWindowPoolStats() );
        return list;
    }

}