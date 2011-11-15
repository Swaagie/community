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
package org.neo4j.kernel.impl.core;

import static org.neo4j.kernel.impl.util.DirectionWrapper.wrapDirection;
import static org.neo4j.kernel.impl.util.RelIdArray.empty;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ReturnableEvaluator;
import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.Traverser;
import org.neo4j.graphdb.Traverser.Order;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.impl.nioneo.store.PropertyData;
import org.neo4j.kernel.impl.nioneo.store.Record;
import org.neo4j.kernel.impl.transaction.LockType;
import org.neo4j.kernel.impl.traversal.OldTraverserWrapper;
import org.neo4j.kernel.impl.util.ArrayMap;
import org.neo4j.kernel.impl.util.CombinedRelIdIterator;
import org.neo4j.kernel.impl.util.DirectionWrapper;
import org.neo4j.kernel.impl.util.RelIdArray;
import org.neo4j.kernel.impl.util.RelIdIterator;

class NodeImpl extends Primitive
{
    private static final RelIdArray[] NO_RELATIONSHIPS = new RelIdArray[0];
    static final RelationshipType[] NO_RELATIONSHIP_TYPES = new RelationshipType[0];
    static final RelationshipLoadingPosition EMPTY_LOADING_POSITION = new SingleChainPosition( Record.NO_NEXT_RELATIONSHIP.intValue() );

    private volatile RelIdArray[] relationships;
    private RelationshipLoadingPosition relChainPosition;
    private long id;

    NodeImpl( long id )
    {
        this( id, false );
    }

    // newNode will only be true for NodeManager.createNode
    NodeImpl( long id, boolean newNode )
    {
        super( newNode );
        this.id = id;
        if ( newNode )
        {
            relationships = NO_RELATIONSHIPS;
            relChainPosition = EMPTY_LOADING_POSITION;
        }
    }

    @Override
    public long getId()
    {
        return id;
    }

    @Override
    public int hashCode()
    {
        return (int) (( id >>> 32 ) ^ id );
    }

    @Override
    public boolean equals( Object obj )
    {
        return this == obj || ( obj instanceof NodeImpl && ( (NodeImpl) obj ).id == id );
    }

    @Override
    protected PropertyData changeProperty( NodeManager nodeManager,
            PropertyData property, Object value )
    {
        return nodeManager.nodeChangeProperty( this, property, value );
    }

    @Override
    protected PropertyData addProperty( NodeManager nodeManager, PropertyIndex index, Object value )
    {
        return nodeManager.nodeAddProperty( this, index, value );
    }

    @Override
    protected void removeProperty( NodeManager nodeManager,
            PropertyData property )
    {
        nodeManager.nodeRemoveProperty( this, property );
    }

    @Override
    protected ArrayMap<Integer, PropertyData> loadProperties(
            NodeManager nodeManager, boolean light )
    {
        return nodeManager.loadProperties( this, light );
    }

    List<RelIdIterator> getAllRelationships( NodeManager nodeManager, DirectionWrapper direction )
    {
        ensureRelationshipMapNotNull( nodeManager );
        List<RelIdIterator> relTypeList = new LinkedList<RelIdIterator>();
        boolean hasModifications = nodeManager.getLockReleaser().hasRelationshipModifications( this );
        ArrayMap<String,RelIdArray> addMap = null;
        if ( hasModifications )
        {
            addMap = nodeManager.getCowRelationshipAddMap( this );
        }

        for ( RelIdArray src : relationships )
        {
            String type = src.getType();
            Collection<Long> remove = null;
            RelIdArray add = null;
            RelIdIterator iterator = null;
            if ( hasModifications )
            {
                remove = nodeManager.getCowRelationshipRemoveMap( this, type );
                if ( addMap != null )
                {
                    add = addMap.get( type );
                }
                iterator = new CombinedRelIdIterator( type, direction, src, add, remove );
            }
            else
            {
                iterator = src.iterator( direction );
            }
            relTypeList.add( iterator );
        }
        if ( addMap != null )
        {
            for ( String type : addMap.keySet() )
            {
                if ( getRelIdArray( type ) == null )
                {
                    Collection<Long> remove = nodeManager.getCowRelationshipRemoveMap( this, type );
                    RelIdArray add = addMap.get( type );
                    relTypeList.add( new CombinedRelIdIterator( type, direction, null, add, remove ) );
                }
            }
        }
        return relTypeList;
    }

    List<RelIdIterator> getAllRelationshipsOfType( NodeManager nodeManager,
        DirectionWrapper direction, RelationshipType... types)
    {
        ensureRelationshipMapNotNull( nodeManager );
        List<RelIdIterator> relTypeList = new LinkedList<RelIdIterator>();
        boolean hasModifications = nodeManager.getLockReleaser().hasRelationshipModifications( this );
        for ( RelationshipType type : types )
        {
            String typeName = type.name();
            RelIdArray src = getRelIdArray( typeName );
            Collection<Long> remove = null;
            RelIdArray add = null;
            RelIdIterator iterator = null;
            if ( hasModifications )
            {
                remove = nodeManager.getCowRelationshipRemoveMap( this, typeName );
                add = nodeManager.getCowRelationshipAddMap( this, typeName );
                iterator = new CombinedRelIdIterator( typeName, direction, src, add, remove );
            }
            else
            {
                iterator = src != null ? src.iterator( direction ) : empty( typeName ).iterator( direction );
            }
            relTypeList.add( iterator );
        }
        return relTypeList;
    }

    public Iterable<Relationship> getRelationships( NodeManager nodeManager )
    {
        return new IntArrayIterator( getAllRelationships( nodeManager, DirectionWrapper.BOTH ), this,
            DirectionWrapper.BOTH, nodeManager, new RelationshipType[0], !hasMoreRelationshipsToLoad(
                    DirectionWrapper.BOTH, NO_RELATIONSHIP_TYPES ) );
    }

    public Iterable<Relationship> getRelationships( NodeManager nodeManager, Direction dir )
    {
        DirectionWrapper direction = wrapDirection( dir );
        return new IntArrayIterator( getAllRelationships( nodeManager, direction ), this, direction,
            nodeManager, new RelationshipType[0], !hasMoreRelationshipsToLoad( direction, NO_RELATIONSHIP_TYPES ) );
    }

    public Iterable<Relationship> getRelationships( NodeManager nodeManager, RelationshipType type )
    {
        RelationshipType types[] = new RelationshipType[] { type };
        return new IntArrayIterator( getAllRelationshipsOfType( nodeManager, DirectionWrapper.BOTH, types ),
            this, DirectionWrapper.BOTH, nodeManager, types, !hasMoreRelationshipsToLoad(
                    DirectionWrapper.BOTH, new RelationshipType[] {type} ) );
    }

    public Iterable<Relationship> getRelationships( NodeManager nodeManager,
            RelationshipType... types )
    {
        return new IntArrayIterator( getAllRelationshipsOfType( nodeManager, DirectionWrapper.BOTH, types ),
            this, DirectionWrapper.BOTH, nodeManager, types, !hasMoreRelationshipsToLoad( DirectionWrapper.BOTH, types ) );
    }

    public Iterable<Relationship> getRelationships( NodeManager nodeManager,
            Direction direction, RelationshipType... types )
    {
        DirectionWrapper dir = wrapDirection( direction );
        return new IntArrayIterator( getAllRelationshipsOfType( nodeManager, dir, types ),
            this, dir, nodeManager, types, !hasMoreRelationshipsToLoad( dir, types ) );
    }

    public Relationship getSingleRelationship( NodeManager nodeManager, RelationshipType type,
        Direction dir )
    {
        DirectionWrapper direction = wrapDirection( dir );
        RelationshipType types[] = new RelationshipType[] { type };
        Iterator<Relationship> rels = new IntArrayIterator( getAllRelationshipsOfType( nodeManager,
                direction, types ), this, direction, nodeManager, types, !hasMoreRelationshipsToLoad(
                        direction, new RelationshipType[] {type} ) );
        if ( !rels.hasNext() )
        {
            return null;
        }
        Relationship rel = rels.next();
        if ( rels.hasNext() )
        {
            throw new NotFoundException( "More than one relationship[" +
                type + ", " + dir + "] found for " + this );
        }
        return rel;
    }

    public Iterable<Relationship> getRelationships( NodeManager nodeManager, RelationshipType type,
        Direction dir )
    {
        RelationshipType types[] = new RelationshipType[] { type };
        DirectionWrapper direction = wrapDirection( dir );
        return new IntArrayIterator( getAllRelationshipsOfType( nodeManager, direction, types ),
            this, direction, nodeManager, types, !hasMoreRelationshipsToLoad( direction, new RelationshipType[] {type} ) );
    }

    public void delete( NodeManager nodeManager )
    {
        nodeManager.acquireLock( this, LockType.WRITE );
        boolean success = false;
        try
        {
            ArrayMap<Integer,PropertyData> skipMap =
                nodeManager.getCowPropertyRemoveMap( this, true );
            ArrayMap<Integer,PropertyData> removedProps =
                nodeManager.deleteNode( this );
            if ( removedProps.size() > 0 )
            {
                for ( int index : removedProps.keySet() )
                {
                    skipMap.put( index, removedProps.get( index ) );
                }
            }
            success = true;
        }
        finally
        {
            nodeManager.releaseLock( this, LockType.WRITE );
            if ( !success )
            {
                nodeManager.setRollbackOnly();
            }
        }
    }

    /**
     * Returns this node's string representation.
     *
     * @return the string representation of this node
     */
    @Override
    public String toString()
    {
        return "NodeImpl#" + this.getId();
    }

    // caller is responsible for acquiring lock
    // this method is only called when a relationship is created or
    // a relationship delete is undone or when the full node is loaded
    void addRelationship( NodeManager nodeManager, RelationshipType type, long relId,
            DirectionWrapper dir )
    {
        RelIdArray relationshipSet = nodeManager.getCowRelationshipAddMap(
            this, type.name(), true );
        relationshipSet.add( relId, dir );
    }

    // caller is responsible for acquiring lock
    // this method is only called when a undo create relationship or
    // a relationship delete is invoked.
    void removeRelationship( NodeManager nodeManager, RelationshipType type, long relId )
    {
        Collection<Long> relationshipSet = nodeManager.getCowRelationshipRemoveMap(
            this, type.name(), true );
        relationshipSet.add( relId );
    }

    private void ensureRelationshipMapNotNull( NodeManager nodeManager )
    {
        if ( relationships == null )
        {
            loadInitialRelationships( nodeManager );
        }
    }

    private void loadInitialRelationships( NodeManager nodeManager )
    {
        Pair<ArrayMap<String, RelIdArray>, Map<Long, RelationshipImpl>> rels = null;
        synchronized ( this )
        {
            if ( relationships == null )
            {
                this.relChainPosition = nodeManager.getRelationshipChainPosition( this );
                ArrayMap<String,RelIdArray> tmpRelMap = new ArrayMap<String,RelIdArray>();
                rels = getInitialRelationships( nodeManager, tmpRelMap );
                this.relationships = toRelIdArray( tmpRelMap );
            }
        }
        if ( rels != null )
        {
            nodeManager.putAllInRelCache( rels.other() );
        }
    }

    private RelIdArray[] toRelIdArray( ArrayMap<String, RelIdArray> tmpRelMap )
    {
        if ( tmpRelMap == null || tmpRelMap.size() == 0 )
        {
            return NO_RELATIONSHIPS;
        }

        RelIdArray[] result = new RelIdArray[tmpRelMap.size()];
        int i = 0;
        for ( RelIdArray array : tmpRelMap.values() )
        {
            result[i++] = array;
        }
        return result;
    }

    protected Pair<ArrayMap<String,RelIdArray>,Map<Long,RelationshipImpl>> getInitialRelationships(
            NodeManager nodeManager, ArrayMap<String,RelIdArray> tmpRelMap )
    {
        if ( !hasMoreRelationshipsToLoad( DirectionWrapper.BOTH, NO_RELATIONSHIP_TYPES ) )
        {
            return null;
        }
        Pair<ArrayMap<String,RelIdArray>,Map<Long,RelationshipImpl>> rels =
            nodeManager.getMoreRelationships( this, DirectionWrapper.BOTH, NO_RELATIONSHIP_TYPES );
        ArrayMap<String,RelIdArray> addMap = rels.first();
        if ( addMap.size() == 0 )
        {
            return null;
        }
        for ( String type : addMap.keySet() )
        {
            RelIdArray addRels = addMap.get( type );
            RelIdArray srcRels = tmpRelMap.get( type );
            if ( srcRels == null )
            {
                tmpRelMap.put( type, addRels );
            }
            else
            {
                RelIdArray newSrcRels = srcRels.addAll( addRels );
                // This can happen if srcRels gets upgraded to a RelIdArrayWithLoops
                if ( newSrcRels != srcRels )
                {
                    tmpRelMap.put( type, newSrcRels );
                }
            }
        }
        moreRelationshipsLoaded();
        return rels;
        // nodeManager.putAllInRelCache( pair.other() );
    }

    boolean hasMoreRelationshipsToLoad( DirectionWrapper direction, RelationshipType[] types )
    {
        return relChainPosition == null || relChainPosition.hasMore( direction, types );
    }

    boolean getMoreRelationships( NodeManager nodeManager, DirectionWrapper direction, RelationshipType[] types )
    {
        Pair<ArrayMap<String,RelIdArray>,Map<Long,RelationshipImpl>> rels;
        if ( !hasMoreRelationshipsToLoad( direction, types ) ) return false;
        synchronized ( this )
        {
            if ( !hasMoreRelationshipsToLoad( direction, types ) ) return false;

            rels = nodeManager.getMoreRelationships( this, direction, types );
            ArrayMap<String,RelIdArray> addMap = rels.first();
            if ( addMap.size() == 0 )
            {
                return false;
            }
            for ( String type : addMap.keySet() )
            {
                RelIdArray addRels = addMap.get( type );
                // IntArray srcRels = tmpRelMap.get( type );
                RelIdArray srcRels = getRelIdArray( type );
                if ( srcRels == null )
                {
                    putRelIdArray( addRels );
                }
                else
                {
                    RelIdArray newSrcRels = srcRels.addAll( addRels );
                    // This can happen if srcRels gets upgraded to a RelIdArrayWithLoops
                    if ( newSrcRels != srcRels )
                    {
                        putRelIdArray( newSrcRels );
                    }
                }
            }
            moreRelationshipsLoaded();
        }
        nodeManager.putAllInRelCache( rels.other() );
        return true;
    }

    private RelIdArray getRelIdArray( String type )
    {
        // Concurrency-wise it's ok even if the relationships variable
        // gets rebound to something else (in putRelIdArray) since for-each
        // stashes the reference away and uses that
        for ( RelIdArray array : relationships )
        {
            if ( array.getType().equals( type ) )
            {
                return array;
            }
        }
        return null;
    }

    private void putRelIdArray( RelIdArray addRels )
    {
        // Try to overwrite it if it's already set
        //
        // Make sure the same array is looped all the way through, that's why
        // a safe reference is kept to it. If the real array has changed when
        // we're about to set it then redo the loop. A kind of lock-free synchronization

        String expectedType = addRels.getType();
        for ( int i = 0; i < relationships.length; i++ )
        {
            if ( relationships[i].getType().equals( expectedType ) )
            {
                relationships[i] = addRels;
                return;
            }
        }

        RelIdArray[] newArray = new RelIdArray[relationships.length+1];
        System.arraycopy( relationships, 0, newArray, 0, relationships.length );
        newArray[relationships.length] = addRels;
        relationships = newArray;
    }

    public Relationship createRelationshipTo( NodeManager nodeManager, Node otherNode,
        RelationshipType type )
    {
        return nodeManager.createRelationship( this, otherNode, type );
    }

    /* Tentative expansion API
    public Expansion<Relationship> expandAll()
    {
        return Traversal.expanderForAllTypes().expand( this );
    }

    public Expansion<Relationship> expand( RelationshipType type )
    {
        return expand( type, Direction.BOTH );
    }

    public Expansion<Relationship> expand( RelationshipType type,
            Direction direction )
    {
        return Traversal.expanderForTypes( type, direction ).expand(
                this );
    }

    public Expansion<Relationship> expand( Direction direction )
    {
        return Traversal.expanderForAllTypes( direction ).expand( this );
    }

    public Expansion<Relationship> expand( RelationshipExpander expander )
    {
        return Traversal.expander( expander ).expand( this );
    }
    */

    public Traverser traverse( NodeManager nodeManager, Order traversalOrder,
        StopEvaluator stopEvaluator, ReturnableEvaluator returnableEvaluator,
        RelationshipType relationshipType, Direction direction )
    {
        return OldTraverserWrapper.traverse( new NodeProxy( id, nodeManager ),
                traversalOrder, stopEvaluator,
                returnableEvaluator, relationshipType, direction );
    }

    public Traverser traverse( NodeManager nodeManager, Order traversalOrder,
        StopEvaluator stopEvaluator, ReturnableEvaluator returnableEvaluator,
        RelationshipType firstRelationshipType, Direction firstDirection,
        RelationshipType secondRelationshipType, Direction secondDirection )
    {
        return OldTraverserWrapper.traverse( new NodeProxy( id, nodeManager ),
                traversalOrder, stopEvaluator,
                returnableEvaluator, firstRelationshipType, firstDirection,
                secondRelationshipType, secondDirection );
    }

    public Traverser traverse( NodeManager nodeManager, Order traversalOrder,
        StopEvaluator stopEvaluator, ReturnableEvaluator returnableEvaluator,
        Object... relationshipTypesAndDirections )
    {
        return OldTraverserWrapper.traverse( new NodeProxy( id, nodeManager ),
                traversalOrder, stopEvaluator,
                returnableEvaluator, relationshipTypesAndDirections );
    }

    public boolean hasRelationship( NodeManager nodeManager )
    {
        return getRelationships( nodeManager ).iterator().hasNext();
    }

    public boolean hasRelationship( NodeManager nodeManager, RelationshipType... types )
    {
        return getRelationships( nodeManager, types ).iterator().hasNext();
    }

    public boolean hasRelationship( NodeManager nodeManager, Direction direction,
            RelationshipType... types )
    {
        return getRelationships( nodeManager, direction, types ).iterator().hasNext();
    }

    public boolean hasRelationship( NodeManager nodeManager, Direction dir )
    {
        return getRelationships( nodeManager, dir ).iterator().hasNext();
    }

    public boolean hasRelationship( NodeManager nodeManager, RelationshipType type, Direction dir )
    {
        return getRelationships( nodeManager, type, dir ).iterator().hasNext();
    }

    protected void commitRelationshipMaps(
        ArrayMap<String,RelIdArray> cowRelationshipAddMap,
        ArrayMap<String,Collection<Long>> cowRelationshipRemoveMap )
    {
        if ( relationships == null )
        {
            // we will load full in some other tx
            return;
        }

        synchronized ( this )
        {
            if ( cowRelationshipAddMap != null )
            {
                for ( String type : cowRelationshipAddMap.keySet() )
                {
                    RelIdArray add = cowRelationshipAddMap.get( type );
                    Collection<Long> remove = null;
                    if ( cowRelationshipRemoveMap != null )
                    {
                        remove = cowRelationshipRemoveMap.get( type );
                    }
                    RelIdArray src = getRelIdArray( type );
                    putRelIdArray( RelIdArray.from( src, add, remove ) );
                }
            }
            if ( cowRelationshipRemoveMap != null )
            {
                for ( String type : cowRelationshipRemoveMap.keySet() )
                {
                    if ( cowRelationshipAddMap != null &&
                        cowRelationshipAddMap.get( type ) != null )
                    {
                        continue;
                    }
                    RelIdArray src = getRelIdArray( type );
                    if ( src != null )
                    {
                        Collection<Long> remove = cowRelationshipRemoveMap.get( type );
                        putRelIdArray( RelIdArray.from( src, null, remove ) );
                    }
                }
            }
        }
    }

    RelationshipLoadingPosition getRelChainPosition()
    {
        return relChainPosition;
    }

    private void moreRelationshipsLoaded()
    {
        if ( relationships != null && !hasMoreRelationshipsToLoad( DirectionWrapper.BOTH, NO_RELATIONSHIP_TYPES ) )
        {
            // Shrink arrays
            for ( int i = 0; i < relationships.length; i++ )
            {
                relationships[i] = relationships[i].shrink();
            }
        }
    }

    RelIdArray getRelationshipIds( String type )
    {
        return getRelIdArray( type );
    }

    RelIdArray[] getRelationshipIds()
    {
        return relationships;
    }

    private void ensureAllRelationshipsAreLoaded( NodeManager nm )
    {
        while ( hasMoreRelationshipsToLoad( DirectionWrapper.BOTH, NO_RELATIONSHIP_TYPES ) )
        {
            getMoreRelationships( nm, DirectionWrapper.BOTH, NO_RELATIONSHIP_TYPES );
        }
    }
    
    public int getDegree( NodeManager nm )
    {
        return nm.getRelationshipCount( this, null, DirectionWrapper.BOTH );
    }

    public int getDegree( NodeManager nm, RelationshipType type )
    {
        return getDegree( nm, type, Direction.BOTH );
    }

    public int getDegree( NodeManager nm, Direction direction )
    {
        ensureAllRelationshipsAreLoaded( nm );
        int count = 0;
        DirectionWrapper dir = wrapDirection( direction );
        for ( RelIdArray ids : relationships ) count += ids.size( dir );
        
        if ( nm.getLockReleaser().hasRelationshipModifications( this ) )
        {
            ArrayMap<String, RelIdArray> add = nm.getCowRelationshipAddMap( this );
            if ( add != null )
            {
                for ( RelIdArray addedIds : add.values() ) count += addedIds.size( dir );
            }
            
            ArrayMap<String, Collection<Long>> remove = nm.getCowRelationshipRemoveMap( this );
            if ( remove != null )
            {
                // TODO this isn't right, removeIds must be per direction also
                for ( Collection<Long> removedIds : remove.values() ) count -= removedIds.size();
            }
        }
        return count;
    }
    
    public int getDegree( NodeManager nm, RelationshipType type, Direction direction )
    {
        ensureAllRelationshipsAreLoaded( nm );
        RelIdArray ids = getRelationshipIds( type.name() );
        DirectionWrapper dir = wrapDirection( direction );
        int count = ids != null ? ids.size( dir ) : 0;
        RelIdArray add = nm.getCowRelationshipAddMap( this, type.name() );
        Collection<Long> remove = nm.getCowRelationshipRemoveMap( this, type.name() );
        if ( add != null ) count += add.size( dir );
        // TODO this isn't right, removeIds must be per direction also
        if ( remove != null ) count -= remove.size();
        return count;
    }
}
