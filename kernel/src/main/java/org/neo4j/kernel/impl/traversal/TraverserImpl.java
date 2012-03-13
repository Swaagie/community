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
package org.neo4j.kernel.impl.traversal;

import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipExpander;
import org.neo4j.graphdb.traversal.BranchSelector;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.PathCollisionDetector;
import org.neo4j.graphdb.traversal.SelectorOrderer;
import org.neo4j.graphdb.traversal.TraversalBranch;
import org.neo4j.graphdb.traversal.TraversalBranchCreator;
import org.neo4j.graphdb.traversal.TraversalContext;
import org.neo4j.graphdb.traversal.TraversalMetadata;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.graphdb.traversal.UniquenessFilter;
import org.neo4j.helpers.collection.CombiningIterator;
import org.neo4j.helpers.collection.IterableWrapper;
import org.neo4j.helpers.collection.PrefetchingIterator;

class TraverserImpl implements Traverser
{
    private final TraversalDescriptionImpl description;
    private TraversalMetadata lastIterator;
    final Collection<Node> startNodes;

    TraverserImpl( TraversalDescriptionImpl description, Collection<Node> startNodes )
    {
        this.description = description;
        this.startNodes = startNodes;
    }

    public Iterator<Path> iterator()
    {
        TraverserIterator iterator = description.selectorOrdering != null ?
                new BidirectionalTraverserIterator() : new TraverserIterator();
        Iterator<Path> result = description.sorting != null ? new SortingTraverserIterator( iterator ) : iterator;
        lastIterator = (TraversalMetadata) result;
        return result;
    }

    public Iterable<Node> nodes()
    {
        return new IterableWrapper<Node, Path>( this )
        {
            @Override
            protected Node underlyingObjectToObject( Path position )
            {
                return position.endNode();
            }
        };
    }

    public Iterable<Relationship> relationships()
    {
        return new IterableWrapper<Relationship, Path>( this )
        {
            @Override
            public Iterator<Relationship> iterator()
            {
                Iterator<Relationship> iter = super.iterator();
                if ( iter.hasNext() )
                {
                    Relationship first = iter.next();
                    // If the first position represents the start node, the
                    // first relationship will be null, in that case skip it.
                    if ( first == null ) return iter;
                    // Otherwise re-include it.
                    return new CombiningIterator<Relationship>( first, iter );
                }
                else
                {
                    return iter;
                }
            }

            @Override
            protected Relationship underlyingObjectToObject( Path position )
            {
                return position.lastRelationship();
            }
        };
    }
    
    @Override
    public TraversalMetadata metadata()
    {
        return lastIterator;
    }

    class TraverserIterator extends PrefetchingIterator<Path>
            implements TraversalBranchCreator, TraversalContext
    {
        final UniquenessFilter uniqueness;
        final BranchSelector selector;
        final TraversalDescriptionImpl description;
        int numberOfPathsReturned;
        int numberOfRelationshipsTraversed;

        TraverserIterator()
        {
            this.description = TraverserImpl.this.description;
            this.uniqueness = description.uniqueness.create( description.uniquenessParameter );
            this.selector = instantiateSelector( description );
        }
        
        @Override
        public int getNumberOfPathsReturned()
        {
            return numberOfPathsReturned;
        }
        
        @Override
        public int getNumberOfRelationshipsTraversed()
        {
            return 0;
        }
        
        @Override
        public void relationshipTraversed()
        {
            numberOfRelationshipsTraversed++;
        }
        
        @Override
        public void unnecessaryRelationshipTraversed()
        {
            numberOfRelationshipsTraversed++;
        }
        
        protected BranchSelector selector()
        {
            return selector;
        }

        protected BranchSelector instantiateSelector( TraversalDescriptionImpl description )
        {
            return description.branchSelector.create(
                    new AsOneStartBranch( this, startNodes ), this, description.expander );
        }

        public boolean isUniqueFirst( TraversalBranch branch )
        {
            return this.uniqueness.checkFirst( branch );
        }

        public boolean isUnique( TraversalBranch branch )
        {
            return this.uniqueness.check( branch );
        }
        
        @Override
        public Evaluation evaluate( TraversalBranch branch )
        {
            return description.evaluator.evaluate( branch );
        }
        
        @Override
        public TraversalBranch create( Node node, Node... additionalNodes )
        {
            Collection<Node> nodes = new ArrayList<Node>( additionalNodes.length+1 );
            nodes.add( node );
            nodes.addAll( asList( additionalNodes ) );
            return new AsOneStartBranch( this, nodes );
        }

        @Override
        protected Path fetchNextOrNull()
        {
            TraversalBranch result = null;
            while ( true )
            {
                result = selector.next( this );
                if ( result == null )
                {
                    return null;
                }
                if ( result.evaluation().includes() )
                {
                    numberOfPathsReturned++;
                    return result;
                }
            }
        }
    }
    
    class BidirectionalTraverserIterator extends TraverserIterator
    {
        private PathCollisionDetector collisionDetector;
        private Iterator<Path> foundPaths;
        
        @Override
        protected BranchSelector instantiateSelector( TraversalDescriptionImpl description )
        {
            BranchSelector startSelector = description.branchSelector.create(
                    new AsOneStartBranch( this, startNodes ), this, description.expander );
            BranchSelector endSelector = description.branchSelector.create(
                    new AsOneStartBranch( this, description.endNodes ), this, description.expander.reversed() );
            this.collisionDetector = description.collisionDetector;
            return description.selectorOrdering.create( startSelector, endSelector );
        }
        
        @Override
        protected SelectorOrderer selector()
        {
            return (SelectorOrderer) super.selector();
        }

        @Override
        protected Path fetchNextOrNull()
        {
            if ( foundPaths != null )
            {
                if ( foundPaths.hasNext() )
                {
                    numberOfPathsReturned++;
                    return foundPaths.next();
                }
                foundPaths = null;
            }
            
            TraversalBranch result = null;
            SelectorOrderer selector = selector();
            while ( true )
            {
                result = selector.next( this );
                if ( result == null )
                {
                    return null;
                }
                Iterable<Path> pathCollisions = collisionDetector.evaluate( result, selector.currentSelector() );
                if ( pathCollisions != null )
                {
                    foundPaths = pathCollisions.iterator();
                    numberOfPathsReturned++;
                    return foundPaths.next();
                }
            }
        }
    }
    
    private static class AsOneStartBranch implements TraversalBranch
    {
        private final TraverserIterator traverser;
        private Iterator<TraversalBranch> branches;
        private int expanded;

        AsOneStartBranch( TraverserIterator traverser, Collection<Node> nodes )
        {
            this.traverser = traverser;
            this.branches = toBranches( nodes );
        }
        
        private Iterator<TraversalBranch> toBranches( Collection<Node> nodes )
        {
            Collection<TraversalBranch> branches = new ArrayList<TraversalBranch>();
            for ( Node node : nodes )
            {
                TraversalBranch branch = new StartNodeTraversalBranch( traverser, this, node,
                        traverser.description.expander );
                branches.add( branch );
            }
            return branches.iterator();
        }

        @Override
        public TraversalBranch parent()
        {
            return null;
        }

        @Override
        public int length()
        {
            return -1;
        }

        @Override
        public Node endNode()
        {
            return null;
        }

        @Override
        public Relationship lastRelationship()
        {
            return null;
        }

        @Override
        public TraversalBranch next( RelationshipExpander expander, TraversalContext metadata )
        {
            if ( branches.hasNext() )
            {
                expanded++;
                return branches.next().next( expander, metadata );
            }
            return null;
        }

        @Override
        public int expanded()
        {
            return expanded;
        }

        @Override
        public Evaluation evaluation()
        {
            return Evaluation.EXCLUDE_AND_CONTINUE;
        }

        @Override
        public void initialize( RelationshipExpander expander, TraversalContext metadata )
        {
        }

        @Override
        public Node startNode()
        {
            return null;
        }

        @Override
        public Iterable<Relationship> relationships()
        {
            return null;
        }

        @Override
        public Iterable<Node> nodes()
        {
            return null;
        }

        @Override
        public Iterator<PropertyContainer> iterator()
        {
            return null;
        }
        
        @Override
        public void prune()
        {
            branches = Collections.<TraversalBranch>emptyList().iterator();
        }
    }
    
    class SortingTraverserIterator extends PrefetchingIterator<Path>
            implements TraversalBranchCreator, TraversalContext
    {
        private final TraverserIterator source;
        private Iterator<Path> sortedResultIterator;

        SortingTraverserIterator( TraverserIterator source )
        {
            this.source = source;
        }

        @Override
        public int getNumberOfPathsReturned()
        {
            return source.getNumberOfPathsReturned();
        }

        @Override
        public int getNumberOfRelationshipsTraversed()
        {
            return source.getNumberOfRelationshipsTraversed();
        }

        @Override
        public void relationshipTraversed()
        {
            source.relationshipTraversed();
        }

        @Override
        public void unnecessaryRelationshipTraversed()
        {
            source.unnecessaryRelationshipTraversed();
        }

        @Override
        public boolean isUniqueFirst( TraversalBranch branch )
        {
            return source.isUniqueFirst( branch );
        }

        @Override
        public boolean isUnique( TraversalBranch branch )
        {
            return source.isUnique( branch );
        }

        @Override
        public Evaluation evaluate( TraversalBranch branch )
        {
            return source.evaluate( branch );
        }

        @Override
        public TraversalBranch create( Node node, Node... additionalNodes )
        {
            return source.create( node, additionalNodes );
        }

        @Override
        protected Path fetchNextOrNull()
        {
            if ( sortedResultIterator == null )
            {
                sortedResultIterator = fetchAndSortResult();
            }
            return sortedResultIterator.hasNext() ? sortedResultIterator.next() : null;
        }

        private Iterator<Path> fetchAndSortResult()
        {
            List<Path> result = new ArrayList<Path>();
            while ( source.hasNext() )
            {
                result.add( source.next() );
            }
            Collections.sort( result, TraverserImpl.this.description.sorting );
            return result.iterator();
        }
    }
}
