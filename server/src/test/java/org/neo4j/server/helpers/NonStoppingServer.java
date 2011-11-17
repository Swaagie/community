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
package org.neo4j.server.helpers;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import org.apache.commons.configuration.Configuration;
import org.neo4j.server.NeoServer;
import org.neo4j.server.configuration.Configurator;
import org.neo4j.server.database.Database;
import org.neo4j.server.plugins.Injectable;
import org.neo4j.server.plugins.PluginManager;

public class NonStoppingServer implements NeoServer
{
    private final NeoServer actual;
    
    public static NeoServer unstoppable( NeoServer server )
    {
        return new NonStoppingServer( server );
    }

    private NonStoppingServer( NeoServer actual )
    {
        this.actual = actual;
    }
    
    @Override
    public void start()
    {
        actual.start();
    }

    @Override
    public Configuration getConfiguration()
    {
        return actual.getConfiguration();
    }

    @Override
    public void stop()
    {
    }

    @Override
    public Database getDatabase()
    {
        return actual.getDatabase();
    }

    @Override
    public Configurator getConfigurator()
    {
        return actual.getConfigurator();
    }

    @Override
    public PluginManager getExtensionManager()
    {
        return actual.getExtensionManager();
    }

    @Override
    public Collection<Injectable<?>> getInjectables( List<String> packageNames )
    {
        return actual.getInjectables( packageNames );
    }

    @Override
    public URI baseUri()
    {
        return actual.baseUri();
    }
}
