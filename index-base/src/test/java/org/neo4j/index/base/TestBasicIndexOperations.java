/**
 * Copyright (c) 2002-2012 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.index.base;

import static org.junit.Assert.assertEquals;

import org.junit.Ignore;
import org.junit.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.index.IndexManager;
import org.neo4j.helpers.collection.MapUtil;

@Ignore
public class TestBasicIndexOperations extends Neo4jTestCase
{
    private static final String PROVIDER = "";
    
    @Test
    public void testIt() throws Exception
    {
        Index<Node> index = graphDb().index().forNodes( "my-index",
                MapUtil.stringMap( IndexManager.PROVIDER, PROVIDER ) );
        Node node1 = graphDb().createNode();
        Node node2 = graphDb().createNode();
        index.add( node1, "name", "Mattias" );
        restartTx();
        index.add( node2, "name", "Mattias" );
        assertCollection( index.get( "name", "Mattias" ), node1, node2 );
        restartTx();
        assertCollection( index.get( "name", "Mattias" ), node1, node2 );
        
        index.remove( node1, "name", "Mattias" );
        assertCollection( index.get( "name", "Mattias" ), node2 );
        restartTx();
        assertCollection( index.get( "name", "Mattias" ), node2 );
        index.remove( node2, "name", "Mattias" );
        assertCollection( index.get( "name", "Mattias" ) );
        node1.delete();
        node2.delete();
    }
    
    @Test
    public void testInsertSome()
    {
        Index<Node> index = graphDb().index().forNodes( "my-index2",
                MapUtil.stringMap( IndexManager.PROVIDER, PROVIDER ) );
        for ( int i = 0; i < 10000; i++ )
        {
            Node node = graphDb().createNode();
            index.add( node, "yeah", "some long value " + (i%500) );
        }
        finishTx( true );
        
        for ( int i = 0; i < 500; i++ )
        {
            IndexHits<Node> hits = index.get( "yeah", "some long value " + i );
            assertEquals( 20, hits.size() );
        }
    }

    @Ignore
    @Test
    public void testInsertionSpeed()
    {
        Index<Node> index = graphDb().index().forNodes( "speed", MapUtil.stringMap( IndexManager.PROVIDER, PROVIDER ) );
        long t = System.currentTimeMillis();
        for ( int i = 0; i < 1000000; i++ )
        {
            Node entity = graphDb().createNode();
            index.get( "name", "The name " + i );
            index.add( entity, "name", "The name " + i );
            index.add( entity, "title", "Some title " + i );
            index.add( entity, "something", i + "Nothing" );
            index.add( entity, "else", i + "kdfjkdjf" + i );
            if ( i % 10000 == 0 )
            {
                restartTx();
                System.out.println( i );
            }
        }
        System.out.println( "insert:" + ( System.currentTimeMillis() - t ) );

        t = System.currentTimeMillis();
        int count = 1000;
        int resultCount = 0;
        for ( int i = 0; i < count; i++ )
        {
            for ( Node entity : index.get( "name", "The name " + i*900 ) )
            {
                resultCount++;
            }
        }
        System.out.println( "get(" + resultCount + "):" + (double)( System.currentTimeMillis() - t ) / (double)count );

        t = System.currentTimeMillis();
        resultCount = 0;
        for ( int i = 0; i < count; i++ )
        {
            for ( Node entity : index.get( "something", i*900 + "Nothing" ) )
            {
                resultCount++;
            }
        }
        System.out.println( "get(" + resultCount + "):" + (double)( System.currentTimeMillis() - t ) / (double)count );
    }
}
