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
package org.neo4j.bolt.v43;

import java.io.IOException;

import org.neo4j.bolt.dbapi.CustomBookmarkFormatParser;
import org.neo4j.bolt.messaging.BoltRequestMessageReader;
import org.neo4j.bolt.messaging.BoltRequestMessageWriter;
import org.neo4j.bolt.messaging.BoltResponseMessageWriter;
import org.neo4j.bolt.messaging.RecordingByteChannel;
import org.neo4j.bolt.messaging.RequestMessage;
import org.neo4j.bolt.packstream.BufferedChannelOutput;
import org.neo4j.bolt.packstream.Neo4jPack;
import org.neo4j.bolt.packstream.Neo4jPackV2;
import org.neo4j.bolt.runtime.SynchronousBoltConnection;
import org.neo4j.bolt.runtime.statemachine.BoltStateMachine;
import org.neo4j.bolt.transport.pipeline.ChannelProtector;
import org.neo4j.bolt.v4.runtime.bookmarking.BookmarksParserV4;
import org.neo4j.bolt.v43.messaging.BoltRequestMessageReaderV43;
import org.neo4j.kernel.database.DatabaseIdRepository;
import org.neo4j.logging.internal.NullLogService;

import static org.mockito.Mockito.mock;

/**
 * A helper factory to generate boltV43 component in tests
 */
public class BoltProtocolV43ComponentFactory
{
    public static Neo4jPack newNeo4jPack()
    {
        return new Neo4jPackV2();
    }

    public static BoltRequestMessageWriter requestMessageWriter( Neo4jPack.Packer packer )
    {
        return new BoltRequestMessageWriterV43( packer );
    }

    public static BoltRequestMessageReader requestMessageReader( BoltStateMachine stateMachine )
    {
        return new BoltRequestMessageReaderV43( new SynchronousBoltConnection( stateMachine ), mock( BoltResponseMessageWriter.class ),
                                                new BookmarksParserV4( mock( DatabaseIdRepository.class ), CustomBookmarkFormatParser.DEFAULT ),
                                                mock( ChannelProtector.class ), NullLogService.getInstance() );
    }

    public static byte[] encode( Neo4jPack neo4jPack, RequestMessage... messages ) throws IOException
    {
        RecordingByteChannel rawData = new RecordingByteChannel();
        Neo4jPack.Packer packer = neo4jPack.newPacker( new BufferedChannelOutput( rawData ) );
        BoltRequestMessageWriter writer = requestMessageWriter( packer );

        for ( RequestMessage message : messages )
        {
            writer.write( message );
        }
        writer.flush();

        return rawData.getBytes();
    }
}
