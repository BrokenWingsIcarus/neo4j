/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.server.http.cypher.format.output.json;

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;

import org.neo4j.server.http.cypher.format.api.RecordEvent;
import org.neo4j.server.http.cypher.format.common.Neo4jJsonCodec;

class RowWriter implements ResultDataContentWriter
{
    @Override
    public void write( JsonGenerator out, RecordEvent recordEvent )
            throws IOException
    {
        out.writeArrayFieldStart( "row" );
        try
        {
            for ( String key : recordEvent.getColumns() )
            {
                out.writeObject( recordEvent.getValue( key ) );
            }
        }
        finally
        {
            out.writeEndArray();
            writeMeta( out, recordEvent );
        }
    }

    private static void writeMeta( JsonGenerator out, RecordEvent recordEvent ) throws IOException
    {
        out.writeArrayFieldStart( "meta" );
        try
        {
            /*
             * The way we've designed this JSON serialization is by injecting a custom codec
             * to write the entities. Unfortunately, there seems to be no way to control state
             * inside the JsonGenerator, and so we need to make a second call to write out the
             * meta information, directly to the injected codec. This is not very pretty,
             * but time is expensive, and redesigning one of three server serialization
             * formats is not a priority.
             */
            Neo4jJsonCodec codec = (Neo4jJsonCodec) out.getCodec();
            for ( String key : recordEvent.getColumns() )
            {
                codec.writeMeta( out, recordEvent.getValue( key ) );
            }
        }
        finally
        {
            out.writeEndArray();
        }
    }
}
