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
package org.neo4j.helpers;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Format
{
    public static String date()
    {
        return date( new Date() );
    }

    public static String date( long millis )
    {
        return date( new Date( millis ) );
    }

    public static String date( Date date )
    {
        return DATE.format( date );
    }

    public static String time()
    {
        return time( new Date() );
    }

    public static String time( long millis )
    {
        return time( new Date( millis ) );
    }

    public static String time( Date date )
    {
        return TIME.format( date );
    }

    public static String bytes( long bytes )
    {
        double size = bytes;
        for ( String suffix : BYTE_SIZES )
        {
            if ( size < 1024 ) return String.format( "%.2f %s", Double.valueOf( size ), suffix );
            size /= 1024;
        }
        return String.format( "%.2f TB", Double.valueOf( size ) );
    }

    private Format()
    {
        // No instances
    }

    private static final String[] BYTE_SIZES = { "B", "kB", "MB", "GB" };

    private static final ThreadLocalFormat DATE = new ThreadLocalFormat( "yyyy-MM-dd HH:mm:ss.SSSZ" ),
            TIME = new ThreadLocalFormat( "HH:mm:ss.SSS" );

    private static class ThreadLocalFormat extends ThreadLocal<DateFormat>
    {
        private final String format;

        ThreadLocalFormat( String format )
        {
            this.format = format;
        }

        String format( Date date )
        {
            return get().format( date );
        }

        @Override
        protected DateFormat initialValue()
        {
            return new SimpleDateFormat( format );
        }
    }
}
