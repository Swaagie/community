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

public class RelationshipGroupRecord extends Abstract64BitRecord
{
    private final int type;
    private long next = Record.NO_NEXT_RELATIONSHIP.intValue();
    private long nextOut = Record.NO_NEXT_RELATIONSHIP.intValue();
    private long nextIn = Record.NO_NEXT_RELATIONSHIP.intValue();
    private long nextLoop = Record.NO_NEXT_RELATIONSHIP.intValue();
    
    // Not stored, just kept in memory temporarily when loading the group chain
    private long prev = Record.NO_NEXT_RELATIONSHIP.intValue();
    
    public RelationshipGroupRecord( long id, int type )
    {
        super( id );
        this.type = type;
    }
    
    public int getType()
    {
        return type;
    }
    
    public long getNextOut()
    {
        return nextOut;
    }
    
    public void setNextOut( long nextOut )
    {
        this.nextOut = nextOut;
    }
    
    public long getNextIn()
    {
        return nextIn;
    }
    
    public void setNextIn( long nextIn )
    {
        this.nextIn = nextIn;
    }
    
    public long getNextLoop()
    {
        return nextLoop;
    }
    
    public void setNextLoop( long nextLoop )
    {
        this.nextLoop = nextLoop;
    }
    
    public long getNext()
    {
        return next;
    }
    
    public void setNext( long next )
    {
        this.next = next;
    }
    
    public void setPrev( long prev )
    {
        this.prev = prev;
    }
    
    public long getPrev()
    {
        return prev;
    }
    
    @Override
    public String toString()
    {
        return new StringBuilder( "RelationshipGroup[" + getId() )
                .append( ",type=" + type )
                .append( ",out=" + nextOut )
                .append( ",in=" + nextIn )
                .append( ",loop=" + nextLoop )
                .append( ",prev=" + prev )
                .append( ",next=" + next )
                .append( "]" ).toString();
    }
}