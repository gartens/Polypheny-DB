/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Databases and Information Systems Research Group, University of Basel, Switzerland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package ch.unibas.dmi.dbis.polyphenydb.schema;


import ch.unibas.dmi.dbis.polyphenydb.rel.type.RelProtoDataType;
import ch.unibas.dmi.dbis.polyphenydb.util.NameMap;
import ch.unibas.dmi.dbis.polyphenydb.util.NameMultimap;
import ch.unibas.dmi.dbis.polyphenydb.util.NameSet;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;


/**
 * Concrete implementation of {@link AbstractPolyphenyDbSchema} that caches tables, functions and sub-schemas.
 */
class CachingPolyphenyDbSchema extends AbstractPolyphenyDbSchema {

    private final Cached<SubSchemaCache> implicitSubSchemaCache;
    private final Cached<NameSet> implicitTableCache;
    private final Cached<NameSet> implicitFunctionCache;
    private final Cached<NameSet> implicitTypeCache;

    private boolean cache = true;


    /**
     * Creates a CachingPolyphenyDbSchema.
     */
    CachingPolyphenyDbSchema( AbstractPolyphenyDbSchema parent, Schema schema, String name ) {
        this( parent, schema, name, null, null, null, null, null, null, null, null );
    }


    private CachingPolyphenyDbSchema( AbstractPolyphenyDbSchema parent, Schema schema, String name, NameMap<PolyphenyDbSchema> subSchemaMap, NameMap<TableEntry> tableMap, NameMap<LatticeEntry> latticeMap, NameMap<TypeEntry> typeMap,
            NameMultimap<FunctionEntry> functionMap, NameSet functionNames, NameMap<FunctionEntry> nullaryFunctionMap, List<? extends List<String>> path ) {
        super( parent, schema, name, subSchemaMap, tableMap, latticeMap, typeMap, functionMap, functionNames, nullaryFunctionMap, path );
        this.implicitSubSchemaCache =
                new AbstractCached<SubSchemaCache>() {
                    public SubSchemaCache build() {
                        return new SubSchemaCache( CachingPolyphenyDbSchema.this, CachingPolyphenyDbSchema.this.schema.getSubSchemaNames() );
                    }
                };
        this.implicitTableCache =
                new AbstractCached<NameSet>() {
                    public NameSet build() {
                        return NameSet.immutableCopyOf( CachingPolyphenyDbSchema.this.schema.getTableNames() );
                    }
                };
        this.implicitFunctionCache =
                new AbstractCached<NameSet>() {
                    public NameSet build() {
                        return NameSet.immutableCopyOf( CachingPolyphenyDbSchema.this.schema.getFunctionNames() );
                    }
                };
        this.implicitTypeCache =
                new AbstractCached<NameSet>() {
                    public NameSet build() {
                        return NameSet.immutableCopyOf( CachingPolyphenyDbSchema.this.schema.getTypeNames() );
                    }
                };
    }


    @Override
    public void setCache( boolean cache ) {
        if ( cache == this.cache ) {
            return;
        }
        final long now = System.currentTimeMillis();
        implicitSubSchemaCache.enable( now, cache );
        implicitTableCache.enable( now, cache );
        implicitFunctionCache.enable( now, cache );
        this.cache = cache;
    }


    protected boolean isCacheEnabled() {
        return this.cache;
    }


    @Override
    protected PolyphenyDbSchema getImplicitSubSchema( String schemaName, boolean caseSensitive ) {
        final long now = System.currentTimeMillis();
        final SubSchemaCache subSchemaCache = implicitSubSchemaCache.get( now );
        //noinspection LoopStatementThatDoesntLoop
        for ( String schemaName2 : subSchemaCache.names.range( schemaName, caseSensitive ) ) {
            return subSchemaCache.cache.getUnchecked( schemaName2 );
        }
        return null;
    }


    /**
     * Adds a child schema of this schema.
     */
    public PolyphenyDbSchema add( String name, Schema schema ) {
        final PolyphenyDbSchema polyphenyDbSchema = new CachingPolyphenyDbSchema( this, schema, name );
        subSchemaMap.put( name, polyphenyDbSchema );
        return polyphenyDbSchema;
    }


    @Override
    protected TableEntry getImplicitTable( String tableName, boolean caseSensitive ) {
        final long now = System.currentTimeMillis();
        final NameSet implicitTableNames = implicitTableCache.get( now );
        for ( String tableName2 : implicitTableNames.range( tableName, caseSensitive ) ) {
            final Table table = schema.getTable( tableName2 );
            if ( table != null ) {
                return tableEntry( tableName2, table );
            }
        }
        return null;
    }


    @Override
    protected TypeEntry getImplicitType( String name, boolean caseSensitive ) {
        final long now = System.currentTimeMillis();
        final NameSet implicitTypeNames = implicitTypeCache.get( now );
        for ( String typeName : implicitTypeNames.range( name, caseSensitive ) ) {
            final RelProtoDataType type = schema.getType( typeName );
            if ( type != null ) {
                return typeEntry( name, type );
            }
        }
        return null;
    }


    @Override
    protected void addImplicitSubSchemaToBuilder( ImmutableSortedMap.Builder<String, PolyphenyDbSchema> builder ) {
        ImmutableSortedMap<String, PolyphenyDbSchema> explicitSubSchemas = builder.build();
        final long now = System.currentTimeMillis();
        final SubSchemaCache subSchemaCache = implicitSubSchemaCache.get( now );
        for ( String name : subSchemaCache.names.iterable() ) {
            if ( explicitSubSchemas.containsKey( name ) ) {
                // explicit sub-schema wins.
                continue;
            }
            builder.put( name, subSchemaCache.cache.getUnchecked( name ) );
        }
    }


    @Override
    protected void addImplicitTableToBuilder( ImmutableSortedSet.Builder<String> builder ) {
        // Add implicit tables, case-sensitive.
        final long now = System.currentTimeMillis();
        final NameSet set = implicitTableCache.get( now );
        builder.addAll( set.iterable() );
    }


    @Override
    protected void addImplicitFunctionsToBuilder( ImmutableList.Builder<Function> builder, String name, boolean caseSensitive ) {
        // Add implicit functions, case-insensitive.
        final long now = System.currentTimeMillis();
        final NameSet set = implicitFunctionCache.get( now );
        for ( String name2 : set.range( name, caseSensitive ) ) {
            final Collection<Function> functions = schema.getFunctions( name2 );
            if ( functions != null ) {
                builder.addAll( functions );
            }
        }
    }


    @Override
    protected void addImplicitFuncNamesToBuilder( ImmutableSortedSet.Builder<String> builder ) {
        // Add implicit functions, case-sensitive.
        final long now = System.currentTimeMillis();
        final NameSet set = implicitFunctionCache.get( now );
        builder.addAll( set.iterable() );
    }


    @Override
    protected void addImplicitTypeNamesToBuilder( ImmutableSortedSet.Builder<String> builder ) {
        // Add implicit types, case-sensitive.
        final long now = System.currentTimeMillis();
        final NameSet set = implicitTypeCache.get( now );
        builder.addAll( set.iterable() );
    }


    @Override
    protected void addImplicitTablesBasedOnNullaryFunctionsToBuilder( ImmutableSortedMap.Builder<String, Table> builder ) {
        ImmutableSortedMap<String, Table> explicitTables = builder.build();

        final long now = System.currentTimeMillis();
        final NameSet set = implicitFunctionCache.get( now );
        for ( String s : set.iterable() ) {
            // explicit table wins.
            if ( explicitTables.containsKey( s ) ) {
                continue;
            }
            for ( Function function : schema.getFunctions( s ) ) {
                if ( function instanceof TableMacro && function.getParameters().isEmpty() ) {
                    final Table table = ((TableMacro) function).apply( ImmutableList.of() );
                    builder.put( s, table );
                }
            }
        }
    }


    @Override
    protected TableEntry getImplicitTableBasedOnNullaryFunction( String tableName, boolean caseSensitive ) {
        final long now = System.currentTimeMillis();
        final NameSet set = implicitFunctionCache.get( now );
        for ( String s : set.range( tableName, caseSensitive ) ) {
            for ( Function function : schema.getFunctions( s ) ) {
                if ( function instanceof TableMacro && function.getParameters().isEmpty() ) {
                    final Table table = ((TableMacro) function).apply( ImmutableList.of() );
                    return tableEntry( tableName, table );
                }
            }
        }
        return null;
    }


    protected PolyphenyDbSchema snapshot( AbstractPolyphenyDbSchema parent, SchemaVersion version ) {
        AbstractPolyphenyDbSchema snapshot = new CachingPolyphenyDbSchema( parent, schema.snapshot( version ), name, null, tableMap, latticeMap, typeMap, functionMap, functionNames, nullaryFunctionMap, getPath() );
        for ( PolyphenyDbSchema subSchema : subSchemaMap.map().values() ) {
            PolyphenyDbSchema subSchemaSnapshot = ((AbstractPolyphenyDbSchema) subSchema).snapshot( snapshot, version );
            snapshot.subSchemaMap.put( subSchema.getName(), subSchemaSnapshot );
        }
        return snapshot;
    }


    @Override
    public boolean removeTable( String name ) {
        if ( cache ) {
            final long now = System.nanoTime();
            implicitTableCache.enable( now, false );
            implicitTableCache.enable( now, true );
        }
        return super.removeTable( name );
    }


    @Override
    public boolean removeFunction( String name ) {
        if ( cache ) {
            final long now = System.nanoTime();
            implicitFunctionCache.enable( now, false );
            implicitFunctionCache.enable( now, true );
        }
        return super.removeFunction( name );
    }


    /**
     * Strategy for caching the value of an object and re-creating it if its value is out of date as of a given timestamp.
     *
     * @param <T> Type of cached object
     */
    private interface Cached<T> {

        /**
         * Returns the value; uses cached value if valid.
         */
        T get( long now );

        /**
         * Creates a new value.
         */
        T build();

        /**
         * Called when PolyphenyDbSchema caching is enabled or disabled.
         */
        void enable( long now, boolean enabled );
    }


    /**
     * Implementation of {@link CachingPolyphenyDbSchema.Cached} that drives from {@link CachingPolyphenyDbSchema#cache}.
     *
     * @param <T> element type
     */
    private abstract class AbstractCached<T> implements Cached<T> {

        T t;
        boolean built = false;


        public T get( long now ) {
            if ( !CachingPolyphenyDbSchema.this.cache ) {
                return build();
            }
            if ( !built ) {
                t = build();
            }
            built = true;
            return t;
        }


        public void enable( long now, boolean enabled ) {
            if ( !enabled ) {
                t = null;
            }
            built = false;
        }
    }


    /**
     * Information about the implicit sub-schemas of an {@link AbstractPolyphenyDbSchema}.
     */
    private static class SubSchemaCache {

        /**
         * The names of sub-schemas returned from the {@link Schema} SPI.
         */
        final NameSet names;
        /**
         * Cached {@link AbstractPolyphenyDbSchema} wrappers. It is worth caching them because they contain maps of their own sub-objects.
         */
        final LoadingCache<String, PolyphenyDbSchema> cache;


        private SubSchemaCache( final AbstractPolyphenyDbSchema polyphenyDbSchema, Set<String> names ) {
            this.names = NameSet.immutableCopyOf( names );
            this.cache = CacheBuilder.newBuilder().build(
                    new CacheLoader<String, PolyphenyDbSchema>() {
                        @SuppressWarnings("NullableProblems")
                        @Override
                        public PolyphenyDbSchema load( String schemaName ) {
                            final Schema subSchema = polyphenyDbSchema.schema.getSubSchema( schemaName );
                            if ( subSchema == null ) {
                                throw new RuntimeException( "sub-schema " + schemaName + " not found" );
                            }
                            return new CachingPolyphenyDbSchema( polyphenyDbSchema, subSchema, schemaName );
                        }
                    } );
        }
    }
}
