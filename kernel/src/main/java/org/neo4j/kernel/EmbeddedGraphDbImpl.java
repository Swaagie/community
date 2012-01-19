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
package org.neo4j.kernel;

import org.neo4j.graphdb.*;
import org.neo4j.graphdb.event.KernelEventHandler;
import org.neo4j.graphdb.event.TransactionEventHandler;
import org.neo4j.helpers.Service;
import org.neo4j.kernel.impl.core.KernelPanicEventGenerator;
import org.neo4j.kernel.impl.core.LockReleaser;
import org.neo4j.kernel.impl.core.NodeManager;
import org.neo4j.kernel.impl.transaction.AbstractTransactionManager;
import org.neo4j.kernel.impl.transaction.LockManager;
import org.neo4j.kernel.impl.transaction.xaframework.ForceMode;
import org.neo4j.kernel.impl.util.StringLogger;

import javax.transaction.TransactionManager;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;

class EmbeddedGraphDbImpl
{
    private static final long MAX_NODE_ID = IdType.NODE.getMaxValue();
    private static final long MAX_RELATIONSHIP_ID = IdType.RELATIONSHIP.getMaxValue();

    private static Logger log =
        Logger.getLogger( EmbeddedGraphDbImpl.class.getName() );
    private Transaction placeboTransaction = null;
    private final GraphDbInstance graphDbInstance;
    private final GraphDatabaseService graphDbService;
    private final NodeManager nodeManager;
    private Config config;
    private final String storeDir;

    private List<KernelEventHandler> kernelEventHandlers;
    private final Collection<TransactionEventHandler<?>> transactionEventHandlers;
    private KernelPanicEventGenerator kernelPanicEventGenerator;

    private final KernelData extensions;

    private final StringLogger msgLog;
    private final TransactionBuilder defaultTxBuilder = new TransactionBuilderImpl( this, ForceMode.forced );
    private final LockManager lockManager;
    private AbstractTransactionManager txManager;
    private final LockReleaser lockReleaser;

    /**
     * A non-standard way of creating an embedded {@link GraphDatabaseService}
     * with a set of configuration parameters. Will most likely be removed in
     * future releases.
     *
     * @param storeDir the store directory for the db files
     *
     */
    public EmbeddedGraphDbImpl(String storeDir,
                               AbstractGraphDatabase graphDbService,
                               LockManager lockManager,
                               AbstractTransactionManager txManager,
                               LockReleaser lockReleaser,
                               List<KernelEventHandler> kernelEventHandlers,
                               Collection<TransactionEventHandler<?>> transactionEventHandlers,
                               KernelPanicEventGenerator kernelPanicEventGenerator,
                               KernelData extensions,
                               GraphDbInstance graphDbInstance,
                               NodeManager nodeManager)
    {
        this.storeDir = storeDir;
        this.lockManager = lockManager;
        this.txManager = txManager;
        this.lockReleaser = lockReleaser;
        this.kernelEventHandlers = kernelEventHandlers;
        this.transactionEventHandlers = transactionEventHandlers;
        this.kernelPanicEventGenerator = kernelPanicEventGenerator;
        this.graphDbInstance = graphDbInstance;
        this.msgLog = graphDbService.getMessageLog();
        this.graphDbService = graphDbService;
        this.nodeManager = nodeManager;

        this.extensions = extensions;
    }

    <T> Collection<T> getManagementBeans( Class<T> beanClass )
    {
        KernelExtension<?> jmx = Service.load( KernelExtension.class, "kernel jmx" );
        if ( jmx != null )
        {
            Method getManagementBeans = null;
            Object state = jmx.getState( extensions );
            if ( state != null )
            {
                try
                {
                    getManagementBeans = state.getClass().getMethod( "getManagementBeans", Class.class );
                }
                catch ( Exception e )
                {
                    // getManagementBean will be null
                }
            }
            if ( getManagementBeans != null )
            {
                try
                {
                    @SuppressWarnings( "unchecked" ) Collection<T> result =
                            (Collection<T>) getManagementBeans.invoke( state, beanClass );
                    if ( result == null ) return Collections.emptySet();
                    return result;
                }
                catch ( InvocationTargetException ex )
                {
                    Throwable cause = ex.getTargetException();
                    if ( cause instanceof Error )
                    {
                        throw (Error) cause;
                    }
                    if ( cause instanceof RuntimeException )
                    {
                        throw (RuntimeException) cause;
                    }
                }
                catch ( Exception ignored )
                {
                    // exception thrown below
                }
            }
        }
        throw new UnsupportedOperationException( "Neo4j JMX support not enabled" );
    }

    /**
     * A non-standard Convenience method that loads a standard property file and
     * converts it into a generic <Code>Map<String,String></CODE>. Will most
     * likely be removed in future releases.
     *
     * @param file the property file to load
     * @return a map containing the properties from the file
     * @throws IllegalArgumentException if file does not exist
     */
    public static Map<String,String> loadConfigurations( String file )
    {
        Properties props = new Properties();
        try
        {
            FileInputStream stream = new FileInputStream( new File( file ) );
            try
            {
                props.load( stream );
            }
            finally
            {
                stream.close();
            }
        }
        catch ( Exception e )
        {
            throw new IllegalArgumentException( "Unable to load " + file, e );
        }
        Set<Entry<Object,Object>> entries = props.entrySet();
        Map<String,String> stringProps = new HashMap<String,String>();
        for ( Entry<Object,Object> entry : entries )
        {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            stringProps.put( key, value );
        }
        return stringProps;
    }

    public Node createNode()
    {
        return nodeManager.createNode();
    }

    public Node getNodeById( long id )
    {
        if ( id < 0 || id > MAX_NODE_ID )
        {
            throw new NotFoundException( "Node[" + id + "]" );
        }
        return nodeManager.getNodeById( id );
    }

    public Relationship getRelationshipById( long id )
    {
        if ( id < 0 || id > MAX_RELATIONSHIP_ID )
        {
            throw new NotFoundException( "Relationship[" + id + "]" );
        }
        return nodeManager.getRelationshipById( id );
    }

    public Node getReferenceNode()
    {
        return nodeManager.getReferenceNode();
    }

    private boolean inShutdown = false;
    public synchronized void shutdown()
    {
        if ( inShutdown ) return;
        inShutdown = true;
        try
        {
            if ( graphDbInstance.started() )
            {
                try
                {
                    sendShutdownEvent();
                }
                finally
                {
                    extensions.shutdown( msgLog );
                }
            }
            graphDbInstance.shutdown();
        }
        finally
        {
            inShutdown = false;
        }
    }

    private void sendShutdownEvent()
    {
        for ( KernelEventHandler handler : this.kernelEventHandlers )
        {
            handler.beforeShutdown();
        }
    }

    public TransactionBuilder tx()
    {
        return defaultTxBuilder;
    }
    
    Transaction beginTx( ForceMode forceMode )
    {
        if ( graphDbInstance.transactionRunning() )
        {
            if ( placeboTransaction == null )
            {
                placeboTransaction = new PlaceboTransaction(
                        txManager );
            }
            return placeboTransaction;
        }
        Transaction result = null;
        try
        {
            txManager.begin( forceMode );
            result = new TopLevelTransaction( txManager, lockManager, lockReleaser );
        }
        catch ( Exception e )
        {
            throw new TransactionFailureException(
                "Unable to begin transaction", e );
        }
        return result;
    }
    
    @Override
    public String toString()
    {
        return super.toString() + " [" + storeDir + "]";
    }

    public String getStoreDir()
    {
        return storeDir;
    }

    <T> TransactionEventHandler<T> registerTransactionEventHandler(
            TransactionEventHandler<T> handler )
    {
        this.transactionEventHandlers.add( handler );
        return handler;
    }

    <T> TransactionEventHandler<T> unregisterTransactionEventHandler(
            TransactionEventHandler<T> handler )
    {
        return unregisterHandler( this.transactionEventHandlers, handler );
    }

    KernelEventHandler registerKernelEventHandler(
            KernelEventHandler handler )
    {
        if ( this.kernelEventHandlers.contains( handler ) )
        {
            return handler;
        }

        // Some algo for putting it in the right place
        for ( KernelEventHandler registeredHandler : this.kernelEventHandlers )
        {
            KernelEventHandler.ExecutionOrder order =
                    handler.orderComparedTo( registeredHandler );
            int index = this.kernelEventHandlers.indexOf( registeredHandler );
            if ( order == KernelEventHandler.ExecutionOrder.BEFORE )
            {
                this.kernelEventHandlers.add( index, handler );
                return handler;
            }
            else if ( order == KernelEventHandler.ExecutionOrder.AFTER )
            {
                this.kernelEventHandlers.add( index + 1, handler );
                return handler;
            }
        }

        this.kernelEventHandlers.add( handler );
        return handler;
    }

    KernelEventHandler unregisterKernelEventHandler(
            KernelEventHandler handler )
    {
        return unregisterHandler( this.kernelEventHandlers, handler );
    }

    private <T> T unregisterHandler( Collection<?> setOfHandlers, T handler )
    {
        if ( !setOfHandlers.remove( handler ) )
        {
            throw new IllegalStateException( handler + " isn't registered" );
        }
        return handler;
    }

    KernelData getKernelData()
    {
        return extensions;
    }
}
