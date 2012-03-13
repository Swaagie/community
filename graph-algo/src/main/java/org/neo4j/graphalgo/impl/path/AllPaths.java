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
package org.neo4j.graphalgo.impl.path;

import static org.neo4j.graphdb.traversal.Evaluators.includeWhereEndNodeIs;
import static org.neo4j.graphdb.traversal.Evaluators.toDepth;
import static org.neo4j.kernel.Traversal.traversal;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipExpander;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.kernel.Uniqueness;

public class AllPaths extends TraversalPathFinder
{
    private final RelationshipExpander expander;
    private final int maxDepth;

    public AllPaths( int maxDepth, RelationshipExpander expander )
    {
        this.maxDepth = maxDepth;
        this.expander = expander;
    }

    protected Uniqueness uniqueness()
    {
        return Uniqueness.RELATIONSHIP_PATH;
    }

    @Override
    protected Traverser instantiateTraverser( Node start, Node end )
    {
        return traversal().expand( expander ).depthFirst().uniqueness( uniqueness() )
                .evaluator( toDepth( maxDepth ) ).evaluator( includeWhereEndNodeIs( end ) )
                .traverse( start );   
    }
}
