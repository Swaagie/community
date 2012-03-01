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
package org.neo4j.kernel.impl.core;

import static org.neo4j.kernel.impl.util.DirectionWrapper.wrapDirection;
import static org.neo4j.kernel.impl.util.RelIdArray.empty;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.helpers.Pair;
import org.neo4j.helpers.collection.IterableWrapper;
import org.neo4j.kernel.impl.core.LockReleaser.CowEntityElement;
import org.neo4j.kernel.impl.core.LockReleaser.PrimitiveElement;
import org.neo4j.kernel.impl.core.LockReleaser.SetAndDirectionCounter;
import org.neo4j.kernel.impl.nioneo.store.PropertyData;
import org.neo4j.kernel.impl.transaction.LockType;
import org.neo4j.kernel.impl.util.ArrayMap;
import org.neo4j.kernel.impl.util.CombinedRelIdIterator;
import org.neo4j.kernel.impl.util.DirectionWrapper;
import org.neo4j.kernel.impl.util.RelIdArray;
import org.neo4j.kernel.impl.util.RelIdIterator;

public class NodeImpl extends ArrayBasedPrimitive
{
    private static final RelIdArray[] NO_RELATIONSHIPS = new RelIdArray[0];
    static final RelationshipType[] NO_RELATIONSHIP_TYPES = new RelationshipType[0];

    private volatile RelIdArray[] relationships;
    private RelationshipLoadingPosition relChainPosition;
    private long id;
    
    NodeImpl( long id, long firstRel, long firstProp )
    {
        this( id, firstRel, firstProp, false );
    }

    // newNode will only be true for NodeManager.createNode
    NodeImpl( long id, long firstRel, long firstProp, boolean newNode )
    {
        /* TODO firstRel/firstProp isn't used yet due to some unresolved issue with clearing
         * of cache and keeping those first ids in the node instead of loading on demand.
         */
        super( newNode );
        this.id = id;
        if ( newNode )
        {
            relationships = NO_RELATIONSHIPS;
            relChainPosition = RelationshipLoadingPosition.EMPTY;
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
        long id = getId();
        return (int) (( id >>> 32 ) ^ id );
    }

    @Override
    public boolean equals( Object obj )
    {
        return this == obj || ( obj instanceof NodeImpl && ( (NodeImpl) obj ).getId() == getId() );
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
            SetAndDirectionCounter remove = null;
            RelIdArray add = null;
            RelIdIterator iterator = null;
            if ( hasModifications )
            {
                remove = nodeManager.getCowRelationshipRemoveMap( this, type );
                if ( addMap != null )
                {
                    add = addMap.get( type );
                }
                iterator = new CombinedRelIdIterator( type, direction, src, add,
                        remove != null ? remove.set : null );
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
                    SetAndDirectionCounter remove = nodeManager.getCowRelationshipRemoveMap( this, type );
                    RelIdArray add = addMap.get( type );
                    relTypeList.add( new CombinedRelIdIterator( type, direction, null, add,
                            remove != null ? remove.set : null ) );
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
            SetAndDirectionCounter remove = null;
            RelIdArray add = null;
            RelIdIterator iterator = null;
            if ( hasModifications )
            {
                remove = nodeManager.getCowRelationshipRemoveMap( this, typeName );
                add = nodeManager.getCowRelationshipAddMap( this, typeName );
                iterator = new CombinedRelIdIterator( typeName, direction, src, add,
                        remove != null ? remove.set : null );
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
            DirectionWrapper.BOTH, nodeManager, new RelationshipType[0], !hasMoreRelationshipsToLoad() );
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

    public void delete( NodeManager nodeManager, Node proxy )
    {
        nodeManager.acquireLock( proxy, LockType.WRITE );
        boolean success = false;
        try
        {
            ArrayMap<Integer,PropertyData> skipMap =
                nodeManager.getOrCreateCowPropertyRemoveMap( this );
            ArrayMap<Integer,PropertyData> removedProps =
                nodeManager.deleteNode( this );
            if ( removedProps.size() > 0 )
            {
                for ( Integer index : removedProps.keySet() )
                {
                    skipMap.put( index, removedProps.get( index ) );
                }
            }
            success = true;
        }
        finally
        {
            nodeManager.releaseLock( proxy, LockType.WRITE );
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
        return getClass().getSimpleName() + "#" + this.getId();
    }

    // caller is responsible for acquiring lock
    // this method is only called when a relationship is created or
    // a relationship delete is undone or when the full node is loaded
    void addRelationship( NodeManager nodeManager, RelationshipType type, long relId,
            DirectionWrapper dir )
    {
        RelIdArray relationshipSet = nodeManager.getOrCreateCowRelationshipAddMap(
            this, type.name() );
        relationshipSet.add( relId, dir );
    }

    // caller is responsible for acquiring lock
    // this method is only called when a undo create relationship or
    // a relationship delete is invoked.
    void removeRelationship( NodeManager nodeManager, RelationshipType type, long relId, Direction direction )
    {
        SetAndDirectionCounter relationshipSet = nodeManager.getOrCreateCowRelationshipRemoveMap( this, type.name() );
        relationshipSet.add( relId, direction );
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
        if ( !hasMoreRelationshipsToLoad() )
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

    boolean hasMoreRelationshipsToLoad()
    {
        return hasMoreRelationshipsToLoad( DirectionWrapper.BOTH, NO_RELATIONSHIP_TYPES );
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
        // precondition: called under synchronization

        // make a local reference to the array to avoid multiple read barrier hits
        RelIdArray[] array = relationships;
        // Try to overwrite it if it's already set
        String expectedType = addRels.getType();
        for ( int i = 0; i < array.length; i++ )
        {
            if ( array[i].getType().equals( expectedType ) )
            {
                array[i] = addRels;
                return;
            }
        }
        // no previous entry of the given type - extend the array
        array = Arrays.copyOf( array, array.length + 1 );
        array[array.length - 1] = addRels;
        relationships = array;
    }

    public Relationship createRelationshipTo( NodeManager nodeManager, Node thisProxy,
        Node otherNode, RelationshipType type )
    {
        return nodeManager.createRelationship( thisProxy, this, otherNode, type );
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
        ArrayMap<String,SetAndDirectionCounter> cowRelationshipRemoveMap, long firstRel )
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
                    SetAndDirectionCounter remove = null;
                    if ( cowRelationshipRemoveMap != null )
                    {
                        remove = cowRelationshipRemoveMap.get( type );
                    }
                    RelIdArray src = getRelIdArray( type );
                    putRelIdArray( RelIdArray.from( src, add, remove != null ? remove.set : null ) );
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
                        SetAndDirectionCounter remove = cowRelationshipRemoveMap.get( type );
                        putRelIdArray( RelIdArray.from( src, null, remove != null ? remove.set : null ) );
                    }
                }
            }
        }
    }

    RelationshipLoadingPosition getRelChainPosition()
    {
        return this.relChainPosition;
    }

    private void moreRelationshipsLoaded()
    {
        if ( relationships != null && !hasMoreRelationshipsToLoad() )
        {   // precondition: must be called under synchronization
            // use local reference to avoid multiple read barriers
            RelIdArray[] array = relationships;
            // Done loading - Shrink arrays
            for ( int i = 0; i < array.length; i++ )
            {
                array[i] = array[i].shrink();
            }
            relChainPosition = RelationshipLoadingPosition.EMPTY;
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

    @Override
    public CowEntityElement getEntityElement( PrimitiveElement element, boolean create )
    {
        return element.nodeElement( getId(), create );
    }

    @Override
    PropertyContainer asProxy( NodeManager nm )
    {
        return nm.newNodeProxyById(getId());
    }

    private void ensureAllRelationshipsAreLoaded( NodeManager nm )
    {
        ensureRelationshipMapNotNull( nm );
        while ( hasMoreRelationshipsToLoad() )
        {
            getMoreRelationships( nm, DirectionWrapper.BOTH, NO_RELATIONSHIP_TYPES );
        }
    }
    
    public int getDegree( NodeManager nm )
    {
        return hasMoreRelationshipsToLoad() ?
                nm.getRelationshipCount( this, null, DirectionWrapper.BOTH ) :
                getDegreeByDirection( nm, DirectionWrapper.BOTH );
    }

    public int getDegree( NodeManager nm, RelationshipType type )
    {
        return getDegree( nm, type, Direction.BOTH );
    }

    public int getDegree( NodeManager nm, Direction direction )
    {
        if ( direction == Direction.BOTH ) return getDegree( nm );
        ensureAllRelationshipsAreLoaded( nm );
        return getDegreeByDirection( nm, wrapDirection( direction ) );
    }

    private int getDegreeByDirection( NodeManager nm, DirectionWrapper direction )
    {
        int count = 0;
        for ( RelIdArray ids : relationships ) count += ids.size( direction );
        
        if ( nm.getLockReleaser().hasRelationshipModifications( this ) )
        {
            ArrayMap<String, RelIdArray> add = nm.getCowRelationshipAddMap( this );
            if ( add != null )
            {
                for ( RelIdArray addedIds : add.values() ) count += addedIds.size( direction );
            }
            
            ArrayMap<String, SetAndDirectionCounter> remove = nm.getCowRelationshipRemoveMap( this );
            if ( remove != null )
            {
                for ( SetAndDirectionCounter removedIds : remove.values() ) count -= removedIds.getCount( direction.direction() );
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
        SetAndDirectionCounter remove = nm.getCowRelationshipRemoveMap( this, type.name() );
        if ( add != null ) count += add.size( dir );
        if ( remove != null ) count -= remove.getCount( direction );
        return count;
    }
    
    public Iterable<RelationshipType> getRelationshipTypes( final NodeManager nm )
    {
        ensureAllRelationshipsAreLoaded( nm );
        Set<String> types = new HashSet<String>();
        ArrayMap<String, SetAndDirectionCounter> allRemoved = nm.getCowRelationshipRemoveMap( this );
        for ( RelIdArray ids : relationships )
        {
            SetAndDirectionCounter removed = allRemoved != null ? allRemoved.get( ids.getType() ) : null;
            if ( removed == null || removed.totalCount.get() < ids.size( DirectionWrapper.BOTH ) )
            {   // If not all of these removed add it to the list
                types.add( ids.getType() );
            }
        }
        
        ArrayMap<String, RelIdArray> add = nm.getCowRelationshipAddMap( this );
        if ( add != null )
        {
            for ( Entry<String, RelIdArray> addedType : add.entrySet() )
            {
                RelIdArray ids = addedType.getValue();
                SetAndDirectionCounter removed = allRemoved != null ? allRemoved.get( ids.getType() ) : null;
                if ( removed == null || removed.totalCount.get() < ids.size( DirectionWrapper.BOTH ) )
                {
                    types.add( addedType.getKey() );
                }
            }
        }
        
        return new IterableWrapper<RelationshipType, String>( types )
        {
            @Override
            protected RelationshipType underlyingObjectToObject( String type )
            {
                return nm.getRelationshipTypeHolder().getTypeFor( type );
            }
        };
    }
}
