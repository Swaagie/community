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
package org.neo4j.kernel.impl.traversal;

import static org.neo4j.kernel.Traversal.traversal;

import org.junit.Test;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.traversal.Evaluators;

public class TestPathDescription extends AbstractTestBase
{
    private static final RelationshipType KNOWS = DynamicRelationshipType.withName( "KNOWS" );
    private static final RelationshipType MARRIED_TO = DynamicRelationshipType.withName( "MARRIED_TO" );
    
    @Test
    public void foaf() throws Exception
    {
        createGraph( "a KNOWS b", "b KNOWS c", "b KNOWS d", "c KNOWS d", "a KNOWS e", "e KNOWS b",
                "e KNOWS f", "f KNOWS c", "f MARRIED_TO g", "b MARRIED_TO d" );
        
        Node a = getNodeWithName( "a" );
        for ( Path path : traversal().relationships( KNOWS ).relationships( MARRIED_TO )
                .evaluator( Evaluators.includeWhereLastRelationshipTypeIs( MARRIED_TO ) ).traverse( a ) )
        {
            System.out.println( path );
        }
    }
}
