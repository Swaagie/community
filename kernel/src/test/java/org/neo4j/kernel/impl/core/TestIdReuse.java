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

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.kernel.Config;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.kernel.impl.util.FileUtils;

public class TestIdReuse
{
    @Test
    public void makeSureIdsGetsReusedForPropertyStore() throws Exception
    {
        makeSureIdsGetsReused( "neostore.propertystore.db", 10, 200 );
    }
    
    @Test
    public void makeSureIdsGetsReusedForArrayStore() throws Exception
    {
        long[] array = new long[500];
        for ( int i = 0; i < array.length; i++ )
        {
            array[i] = 0xFFFFFFFFFFFFL + i;
        }
        makeSureIdsGetsReused( "neostore.propertystore.db.arrays", array, 20 );
    }
    
    @Test
    public void makeSureIdsGetsReusedForStringStore() throws Exception
    {
        String string = "something";
        for ( int i = 0; i < 100; i++ )
        {
            string += "something else " + i;
        }
        makeSureIdsGetsReused( "neostore.propertystore.db.strings", string, 20 );
    }
    
    private void makeSureIdsGetsReused( String fileName, Object value, int iterations ) throws Exception
    {
        File storeDir = new File( "target/var/idreuse" );
        FileUtils.deleteRecursively( storeDir );
        File file = new File( storeDir, fileName );
        GraphDatabaseService db = new EmbeddedGraphDatabase( storeDir.getAbsolutePath(), MapUtil.stringMap( Config.USE_MEMORY_MAPPED_BUFFERS, "false" ) );
        for ( int i = 0; i < 5; i++ )
        {
            setSomeAndRemoveSome( db.getReferenceNode(), value );
        }
        db.shutdown();
        long sizeBefore = file.length();
        db = new EmbeddedGraphDatabase( storeDir.getAbsolutePath() );
        for ( int i = 0; i < iterations; i++ )
        {
            setSomeAndRemoveSome( db.getReferenceNode(), value );
        }
        db.shutdown();
        assertEquals( sizeBefore, file.length() );
    }
    
    private void setSomeAndRemoveSome( Node node, Object value )
    {
        Transaction tx = node.getGraphDatabase().beginTx();
        try
        {
            for ( int i = 0; i < 10; i++ )
            {
                node.setProperty( "key" + i, value );
            }
            tx.success();
        }
        finally
        {
            tx.finish();
        }
        tx = node.getGraphDatabase().beginTx();
        try
        {
            for ( int i = 0; i < 10; i++ )
            {
                node.removeProperty( "key" + i );
            }
            tx.success();
        }
        finally
        {
            tx.finish();
        }
    }
}
