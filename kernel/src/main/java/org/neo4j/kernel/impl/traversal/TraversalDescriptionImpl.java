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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Expander;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.RelationshipExpander;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.traversal.BranchOrderingPolicy;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.PathCollisionDetector;
import org.neo4j.graphdb.traversal.SelectorOrderingPolicy;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.graphdb.traversal.UniquenessFactory;
import org.neo4j.kernel.StandardExpander;
import org.neo4j.kernel.Traversal;
import org.neo4j.kernel.Uniqueness;

public final class TraversalDescriptionImpl implements TraversalDescription
{
    public TraversalDescriptionImpl()
    {
        this( StandardExpander.DEFAULT, Uniqueness.NODE_GLOBAL, null,
                Evaluators.all(), Traversal.preorderDepthFirst(), null, null, null, null );
    }

    final Expander expander;
    final UniquenessFactory uniqueness;
    final Object uniquenessParameter;
    final Evaluator evaluator;
    final BranchOrderingPolicy branchSelector;
    final SelectorOrderingPolicy selectorOrdering;
    final PathCollisionDetector collisionDetector;
    final Comparator<? super Path> sorting;
    final Collection<Node> endNodes;

    private TraversalDescriptionImpl( Expander expander,
            UniquenessFactory uniqueness, Object uniquenessParameter,
            Evaluator evaluator, BranchOrderingPolicy branchSelector,
            SelectorOrderingPolicy selectorOrdering, PathCollisionDetector collisionDetector,
            Comparator<? super Path> sorting, Collection<Node> endNodes )
    {
        this.expander = expander;
        this.uniqueness = uniqueness;
        this.uniquenessParameter = uniquenessParameter;
        this.evaluator = evaluator;
        this.branchSelector = branchSelector;
        this.selectorOrdering = selectorOrdering;
        this.collisionDetector = collisionDetector;
        this.sorting = sorting;
        this.endNodes = endNodes;
    }

    /* (non-Javadoc)
     * @see org.neo4j.graphdb.traversal.TraversalDescription#traverse(org.neo4j.graphdb.Node)
     */
    public Traverser traverse( Node startNode, Node... additionalStartNodes )
    {
        return new TraverserImpl( this, collectionOf( startNode, additionalStartNodes ) );
    }

    private <T> Collection<T> collectionOf( T first, T... rest )
    {
        Collection<T> result = new ArrayList<T>();
        result.add( first );
        result.addAll( Arrays.asList( rest ) );
        return result;
    }

    /* (non-Javadoc)
     * @see org.neo4j.graphdb.traversal.TraversalDescription#uniqueness(org.neo4j.graphdb.traversal.Uniqueness)
     */
    public TraversalDescription uniqueness( UniquenessFactory uniqueness )
    {
        return new TraversalDescriptionImpl( expander, uniqueness, null,
                evaluator, branchSelector, selectorOrdering, collisionDetector, sorting, endNodes );
    }

    /* (non-Javadoc)
     * @see org.neo4j.graphdb.traversal.TraversalDescription#uniqueness(org.neo4j.graphdb.traversal.Uniqueness, java.lang.Object)
     */
    public TraversalDescription uniqueness( UniquenessFactory uniqueness,
            Object parameter )
    {
        if ( this.uniqueness == uniqueness )
        {
            if ( uniquenessParameter == null ? parameter == null
                    : uniquenessParameter.equals( parameter ) )
            {
                return this;
            }
        }

        return new TraversalDescriptionImpl( expander, uniqueness, parameter,
                evaluator, branchSelector, selectorOrdering, collisionDetector, sorting, endNodes );
    }
    
    public TraversalDescription evaluator( Evaluator evaluator )
    {
        if ( this.evaluator == evaluator )
        {
            return this;
        }
        nullCheck( evaluator, Evaluator.class, "RETURN_ALL" );
        return new TraversalDescriptionImpl( expander, uniqueness, uniquenessParameter,
                addEvaluator( evaluator ), branchSelector, selectorOrdering, collisionDetector,
                sorting, endNodes );
    }
    
    private Evaluator addEvaluator( Evaluator evaluator )
    {
        if ( this.evaluator instanceof MultiEvaluator )
        {
            return ((MultiEvaluator) this.evaluator).add( evaluator );
        }
        else
        {
            if ( this.evaluator == Evaluators.all() )
            {
                return evaluator;
            }
            else
            {
                return new MultiEvaluator( new Evaluator[] { this.evaluator, evaluator } );
            }
        }
    }
    
    private static <T> void nullCheck( T parameter, Class<T> parameterType,
            String defaultName )
    {
        if ( parameter == null )
        {
            String typeName = parameterType.getSimpleName();
            throw new IllegalArgumentException( typeName
                                                + " may not be null, use "
                                                + typeName + "." + defaultName
                                                + " instead." );
        }
    }

    /* (non-Javadoc)
     * @see org.neo4j.graphdb.traversal.TraversalDescription#order(org.neo4j.graphdb.traversal.Order)
     */
    public TraversalDescription order( BranchOrderingPolicy selector )
    {
        if ( this.branchSelector == selector )
        {
            return this;
        }
        return new TraversalDescriptionImpl( expander, uniqueness, uniquenessParameter,
                evaluator, selector, selectorOrdering, collisionDetector, sorting, endNodes );
    }

    public TraversalDescription depthFirst()
    {
        return order( Traversal.preorderDepthFirst() );
    }

    public TraversalDescription breadthFirst()
    {
        return order( Traversal.preorderBreadthFirst() );
    }

    /* (non-Javadoc)
     * @see org.neo4j.graphdb.traversal.TraversalDescription#relationships(org.neo4j.graphdb.RelationshipType)
     */
    public TraversalDescription relationships( RelationshipType type )
    {
        return relationships( type, Direction.BOTH );
    }

    /* (non-Javadoc)
     * @see org.neo4j.graphdb.traversal.TraversalDescription#relationships(org.neo4j.graphdb.RelationshipType, org.neo4j.graphdb.Direction)
     */
    public TraversalDescription relationships( RelationshipType type,
            Direction direction )
    {
        return expand( expander.add( type, direction ) );
    }

    /* (non-Javadoc)
     * @see org.neo4j.graphdb.traversal.TraversalDescription#expand(org.neo4j.graphdb.RelationshipExpander)
     */
    public TraversalDescription expand( RelationshipExpander expander )
    {
        if ( expander.equals( this.expander ) )
        {
            return this;
        }
        return new TraversalDescriptionImpl( Traversal.expander( expander ), uniqueness,
                uniquenessParameter, evaluator, branchSelector, selectorOrdering, collisionDetector,
                sorting, endNodes );
    }
    
    @Override
    public TraversalDescription bidirectional( SelectorOrderingPolicy selectorOrdering,
            PathCollisionDetector collisionDetector, Node endNode, Node... additionalEndNodes )
    {
        return new TraversalDescriptionImpl( expander, uniqueness, uniquenessParameter, evaluator,
                branchSelector, selectorOrdering, collisionDetector, sorting, collectionOf( endNode, additionalEndNodes ) );
    }
    
    @Override
    public TraversalDescription sort( Comparator<? super Path> sorting )
    {
        return new TraversalDescriptionImpl( expander, uniqueness, uniquenessParameter, evaluator,
                branchSelector, selectorOrdering, collisionDetector, sorting, endNodes );
    }
}
