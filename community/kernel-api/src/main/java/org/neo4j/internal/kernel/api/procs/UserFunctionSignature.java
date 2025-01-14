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
package org.neo4j.internal.kernel.api.procs;

import static java.util.Collections.unmodifiableList;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.neo4j.internal.helpers.collection.Iterables;

/**
 * This describes the signature of a function, made up of its namespace, name, and input/output description.
 * Function uniqueness is currently *only* on the namespace/name level - no function overloading allowed (yet).
 */
public final class UserFunctionSignature
{
    private final QualifiedName name;
    private final List<FieldSignature> inputSignature;
    private final Neo4jTypes.AnyType type;
    private final String[] allowed;
    private final String deprecated;
    private final String description;
    private final String category;
    private final boolean caseInsensitive;
    private final boolean isBuiltIn;
    private final boolean internal;

    public UserFunctionSignature( QualifiedName name,
            List<FieldSignature> inputSignature,
            Neo4jTypes.AnyType type,
            String deprecated,
            String[] allowed,
            String description,
            String category,
            boolean caseInsensitive )
    {
        this( name, inputSignature, type, deprecated, allowed, description, category, caseInsensitive, false, false );
    }

    public UserFunctionSignature( QualifiedName name,
            List<FieldSignature> inputSignature,
            Neo4jTypes.AnyType type,
            String deprecated,
            String[] allowed,
            String description,
            String category,
            boolean caseInsensitive,
            boolean isBuiltIn )
    {
        this( name, inputSignature, type, deprecated, allowed, description, category, caseInsensitive, isBuiltIn, false );
    }

    public UserFunctionSignature( QualifiedName name,
            List<FieldSignature> inputSignature,
            Neo4jTypes.AnyType type,
            String deprecated,
            String[] allowed,
            String description,
            String category,
            boolean caseInsensitive,
            boolean isBuiltIn,
            boolean internal )
    {
        this.name = name;
        this.inputSignature = unmodifiableList( inputSignature );
        this.type = type;
        this.deprecated = deprecated;
        this.description = description;
        this.category = category;
        this.allowed = allowed;
        this.caseInsensitive = caseInsensitive;
        this.isBuiltIn = isBuiltIn;
        this.internal = internal;
    }

    public QualifiedName name()
    {
        return name;
    }

    public Optional<String> deprecated()
    {
        return Optional.ofNullable( deprecated );
    }

    public List<FieldSignature> inputSignature()
    {
        return inputSignature;
    }

    public Neo4jTypes.AnyType outputType()
    {
        return type;
    }

    public Optional<String> description()
    {
        return Optional.ofNullable( description );
    }

    public Optional<String> category()
    {
        return Optional.ofNullable( category );
    }

    public String[] allowed()
    {
        return allowed;
    }

    public boolean caseInsensitive()
    {
        return caseInsensitive;
    }

    public boolean isBuiltIn()
    {
        return isBuiltIn;
    }

    public boolean internal()
    {
        return internal;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }

        UserFunctionSignature that = (UserFunctionSignature) o;
        return name.equals( that.name ) && inputSignature.equals( that.inputSignature ) && type.equals( that.type );
    }

    @Override
    public int hashCode()
    {
        return name.hashCode();
    }

    @Override
    public String toString()
    {
        String strInSig = inputSignature == null ? "..." : Iterables.toString( inputSignature, ", " );
            String strOutSig = type == null ? "..." : type.toString();
            return String.format( "%s(%s) :: (%s)", name, strInSig, strOutSig );
    }

    public static class Builder
    {
        private final QualifiedName name;
        private final List<FieldSignature> inputSignature = new LinkedList<>();
        private Neo4jTypes.AnyType outputType;
        private String[] allowed = new String[0];
        private String deprecated;
        private String description;
        private String category;

        public Builder( String[] namespace, String name )
        {
            this.name = new QualifiedName( namespace, name );
        }

        public Builder description( String description )
        {
            this.description = description;
            return this;
        }

        public Builder category( String category )
        {
            this.category = category;
            return this;
        }

        public Builder deprecatedBy( String deprecated )
        {
            this.deprecated = deprecated;
            return this;
        }

        /** Define an input field */
        public Builder in( String name, Neo4jTypes.AnyType type )
        {
            inputSignature.add( FieldSignature.inputField( name, type ) );
            return this;
        }

        public Builder in( String name, Neo4jTypes.AnyType type, DefaultParameterValue defaultValue )
        {
            inputSignature.add( FieldSignature.inputField( name, type, defaultValue ) );
            return this;
        }

        /** Define an output field */
        public Builder out( Neo4jTypes.AnyType type )
        {
            outputType = type;
            return this;
        }

        public Builder allowed( String[] allowed )
        {
            this.allowed = allowed;
            return this;
        }

        public UserFunctionSignature build()
        {
            if ( outputType == null )
            {
                throw new IllegalStateException( "output type must be set" );
            }
            return new UserFunctionSignature( name, inputSignature, outputType, deprecated, allowed, description, category, false );
        }
    }

    public static Builder functionSignature( String... namespaceAndName )
    {
        String[] namespace = namespaceAndName.length > 1 ?
                             Arrays.copyOf( namespaceAndName, namespaceAndName.length - 1 ) :
                             new String[0];
        String name = namespaceAndName[namespaceAndName.length - 1];
        return functionSignature( namespace, name );
    }

    public static Builder functionSignature( QualifiedName name )
    {
        return new Builder( name.namespace(), name.name() );
    }

    public static Builder functionSignature( String[] namespace, String name )
    {
        return new Builder( namespace, name );
    }
}
