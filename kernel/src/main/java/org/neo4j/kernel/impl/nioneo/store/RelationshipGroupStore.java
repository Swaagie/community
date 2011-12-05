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
package org.neo4j.kernel.impl.nioneo.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.neo4j.kernel.IdGeneratorFactory;
import org.neo4j.kernel.IdType;
import org.neo4j.kernel.impl.util.StringLogger;

public class RelationshipGroupStore extends AbstractStore implements Store, RecordStore<RelationshipGroupRecord>
{
    /* Record layout
     * 
     * [next,type,firstOut,firstIn,firstLoop] = 20B
     */
    public static final int RECORD_SIZE = 20;
    public static final String TYPE_DESCRIPTOR = "RelationshipGroupStore";
    public static final String FILE_NAME = ".relationshipgroupstore.db";
    
    public RelationshipGroupStore( String fileName, Map<?, ?> config, IdType idType )
    {
        super( fileName, config, idType );
    }

    public static void createStore( String fileName, IdGeneratorFactory idGeneratorFactory, FileSystemAbstraction fileSystem )
    {
        createEmptyStore( fileName, buildTypeDescriptorAndVersion( TYPE_DESCRIPTOR ), idGeneratorFactory, fileSystem );
    }
    
    @Override
    public RelationshipGroupRecord getRecord( long id )
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

    private RelationshipGroupRecord getRecord( long id, PersistenceWindow window, RecordLoad load )
    {
        Buffer buffer = window.getOffsettedBuffer( id );
        
        // [    ,   x] in use
        // [    ,xxx ] high next id bits
        // [ xxx,    ] high firstOut bits
        long inUseByte = buffer.get();
        boolean inUse = (inUseByte&0x1) > 0;
        if ( !inUse )
        {
            switch ( load )
            {
            case NORMAL: throw new InvalidRecordException( "Record[" + id + "] not in use" );
            case CHECK: return null;
            }
        }
        
        // [    ,xxx ] high firstIn bits
        // [ xxx,    ] high firstLoop bits
        long highByte = buffer.get();
        
        int type = buffer.getShort();
        long nextLowBits = buffer.getUnsignedInt();
        long nextOutLowBits = buffer.getUnsignedInt();
        long nextInLowBits = buffer.getUnsignedInt();
        long nextLoopLowBits = buffer.getUnsignedInt();
        
        long nextMod = (inUseByte & 0xE) << 31;
        long nextOutMod = (inUseByte & 0x70) << 28; 
        long nextInMod = (highByte & 0xE) << 31;
        long nextLoopMod = (highByte & 0x70) << 28;
        
        RelationshipGroupRecord record = new RelationshipGroupRecord( id, type );
        record.setInUse( true );
        record.setNext( longFromIntAndMod( nextLowBits, nextMod ) );
        record.setNextOut( longFromIntAndMod( nextOutLowBits, nextOutMod ) );
        record.setNextIn( longFromIntAndMod( nextInLowBits, nextInMod ) );
        record.setNextLoop( longFromIntAndMod( nextLoopLowBits, nextLoopMod ) );
        return record;
    }

    @Override
    public void updateRecord( RelationshipGroupRecord record )
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

    public void updateRecord( RelationshipGroupRecord record, boolean recovered )
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
    
    private void updateRecord( RelationshipGroupRecord record, PersistenceWindow window, boolean force )
    {
        long id = record.getId();
        Buffer buffer = window.getOffsettedBuffer( id );
        if ( record.inUse() || force )
        {
            long nextMod = record.getNext() == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (record.getNext() & 0x700000000L) >> 31;
            long nextOutMod = record.getNextOut() == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (record.getNextOut() & 0x700000000L) >> 28;
            long nextInMod = record.getNextIn() == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (record.getNextIn() & 0x700000000L) >> 31;
            long nextLoopMod = record.getNextLoop() == Record.NO_NEXT_RELATIONSHIP.intValue() ? 0 : (record.getNextLoop() & 0x700000000L) >> 28;
        
            buffer
                // [    ,   x] in use
                // [    ,xxx ] high next id bits
                // [ xxx,    ] high firstOut bits
                .put( (byte) (nextOutMod | nextMod | 1) )
                
                // [    ,xxx ] high firstIn bits
                // [ xxx,    ] high firstLoop bits
                .put( (byte) (nextLoopMod | nextInMod) )
                
                .putShort( (short) record.getType() )
                .putInt( (int) record.getNext() )
                .putInt( (int) record.getNextOut() )
                .putInt( (int) record.getNextIn() )
                .putInt( (int) record.getNextLoop() );
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

    @Override
    public RelationshipGroupRecord forceGetRecord( long id )
    {
        PersistenceWindow window = null;
        try
        {
            window = acquireWindow( id, OperationType.READ );
        }
        catch ( InvalidRecordException e )
        {
            return new RelationshipGroupRecord( id, -1 );
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
    public RelationshipGroupRecord forceGetRaw( long id )
    {
        return forceGetRecord( id );
    }

    @Override
    public void forceUpdateRecord( RelationshipGroupRecord record )
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

    @Override
    public void accept( RecordStore.Processor processor, RelationshipGroupRecord record )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getRecordHeaderSize()
    {
        return getRecordSize();
    }

    @Override
    public void logIdUsage( StringLogger logger )
    {
        NeoStore.logIdUsage( logger, this );
    }

    @Override
    public int getRecordSize()
    {
        return RECORD_SIZE;
    }

    @Override
    public List<WindowPoolStats> getAllWindowPoolStats()
    {
        List<WindowPoolStats> list = new ArrayList<WindowPoolStats>();
        list.add( getWindowPoolStats() );
        return list;
    }

    @Override
    public String getTypeDescriptor()
    {
        return TYPE_DESCRIPTOR;
    }
}
