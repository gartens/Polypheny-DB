/*
 * This file is based on code taken from the Apache Calcite project, which was released under the Apache License.
 * The changes are released under the MIT license.
 *
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
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
 */

package ch.unibas.dmi.dbis.polyphenydb.sql.validate;


import ch.unibas.dmi.dbis.polyphenydb.rel.type.RelDataType;
import ch.unibas.dmi.dbis.polyphenydb.sql.SqlCall;
import ch.unibas.dmi.dbis.polyphenydb.sql.SqlNode;
import ch.unibas.dmi.dbis.polyphenydb.sql.SqlNodeList;
import ch.unibas.dmi.dbis.polyphenydb.sql.SqlSelect;
import ch.unibas.dmi.dbis.polyphenydb.sql.SqlUtil;
import ch.unibas.dmi.dbis.polyphenydb.util.ImmutableBitSet;
import ch.unibas.dmi.dbis.polyphenydb.util.Litmus;
import ch.unibas.dmi.dbis.polyphenydb.util.Pair;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.Ord;


/**
 * Scope for resolving identifiers within a SELECT statement that has a GROUP BY clause.
 *
 * The same set of identifiers are in scope, but it won't allow access to identifiers or expressions which are not group-expressions.
 */
public class AggregatingSelectScope extends DelegatingScope implements AggregatingScope {

    private final SqlSelect select;
    private final boolean distinct;

    /**
     * Use while under construction.
     */
    private List<SqlNode> temporaryGroupExprList;

    public final Supplier<Resolved> resolved =
            Suppliers.memoize( () -> {
                assert temporaryGroupExprList == null;
                temporaryGroupExprList = new ArrayList<>();
                try {
                    return resolve();
                } finally {
                    temporaryGroupExprList = null;
                }
            } )::get;


    /**
     * Creates an AggregatingSelectScope
     *
     * @param selectScope Parent scope
     * @param select Enclosing SELECT node
     * @param distinct Whether SELECT is DISTINCT
     */
    AggregatingSelectScope( SqlValidatorScope selectScope, SqlSelect select, boolean distinct ) {
        // The select scope is the parent in the sense that all columns which are available in the select scope are available.
        // Whether they are valid as aggregation expressions... now that's a different matter.
        super( selectScope );
        this.select = select;
        this.distinct = distinct;
    }


    private Resolved resolve() {
        final ImmutableList.Builder<ImmutableList<ImmutableBitSet>> builder = ImmutableList.builder();
        List<SqlNode> extraExprs = ImmutableList.of();
        Map<Integer, Integer> groupExprProjection = ImmutableMap.of();
        if ( select.getGroup() != null ) {
            final SqlNodeList groupList = select.getGroup();
            final SqlValidatorUtil.GroupAnalyzer groupAnalyzer = new SqlValidatorUtil.GroupAnalyzer( temporaryGroupExprList );
            for ( SqlNode groupExpr : groupList ) {
                SqlValidatorUtil.analyzeGroupItem( this, groupAnalyzer, builder, groupExpr );
            }
            extraExprs = groupAnalyzer.extraExprs;
            groupExprProjection = groupAnalyzer.groupExprProjection;
        }

        final Set<ImmutableBitSet> flatGroupSets = Sets.newTreeSet( ImmutableBitSet.COMPARATOR );
        for ( List<ImmutableBitSet> groupSet : Linq4j.product( builder.build() ) ) {
            flatGroupSets.add( ImmutableBitSet.union( groupSet ) );
        }

        // For GROUP BY (), we need a singleton grouping set.
        if ( flatGroupSets.isEmpty() ) {
            flatGroupSets.add( ImmutableBitSet.of() );
        }

        return new Resolved( extraExprs, temporaryGroupExprList, flatGroupSets, groupExprProjection );
    }


    /**
     * Returns the expressions that are in the GROUP BY clause (or the SELECT DISTINCT clause, if distinct) and that can therefore be referenced without being wrapped in aggregate functions.
     *
     * The expressions are fully-qualified, and any "*" in select clauses are expanded.
     *
     * @return list of grouping expressions
     */
    private Pair<ImmutableList<SqlNode>, ImmutableList<SqlNode>> getGroupExprs() {
        if ( distinct ) {
            // Cannot compute this in the constructor: select list has not been expanded yet.
            assert select.isDistinct();

            // Remove the AS operator so the expressions are consistent with OrderExpressionExpander.
            ImmutableList.Builder<SqlNode> groupExprs = ImmutableList.builder();
            final SelectScope selectScope = (SelectScope) parent;
            for ( SqlNode selectItem : selectScope.getExpandedSelectList() ) {
                groupExprs.add( SqlUtil.stripAs( selectItem ) );
            }
            return Pair.of( ImmutableList.of(), groupExprs.build() );
        } else if ( select.getGroup() != null ) {
            if ( temporaryGroupExprList != null ) {
                // we are in the middle of resolving
                return Pair.of( ImmutableList.of(), ImmutableList.copyOf( temporaryGroupExprList ) );
            } else {
                final Resolved resolved = this.resolved.get();
                return Pair.of( resolved.extraExprList, resolved.groupExprList );
            }
        } else {
            return Pair.of( ImmutableList.of(), ImmutableList.of() );
        }
    }


    public SqlNode getNode() {
        return select;
    }


    private static boolean allContain( List<ImmutableBitSet> bitSets, int bit ) {
        for ( ImmutableBitSet bitSet : bitSets ) {
            if ( !bitSet.get( bit ) ) {
                return false;
            }
        }
        return true;
    }


    @Override
    public RelDataType nullifyType( SqlNode node, RelDataType type ) {
        final Resolved r = this.resolved.get();
        for ( Ord<SqlNode> groupExpr : Ord.zip( r.groupExprList ) ) {
            if ( groupExpr.e.equalsDeep( node, Litmus.IGNORE ) ) {
                if ( r.isNullable( groupExpr.i ) ) {
                    return validator.getTypeFactory().createTypeWithNullability( type, true );
                }
            }
        }
        return type;
    }


    public SqlValidatorScope getOperandScope( SqlCall call ) {
        if ( call.getOperator().isAggregator() ) {
            // If we're the 'SUM' node in 'select a + sum(b + c) from t group by a', then we should validate our arguments in the non-aggregating scope, where 'b' and 'c' are valid
            // column references.
            return parent;
        } else {
            // Check whether expression is constant within the group.
            //
            // If not, throws. Example, 'empno' in
            //    SELECT empno FROM emp GROUP BY deptno
            //
            // If it perfectly matches an expression in the GROUP BY clause, we validate its arguments in the non-aggregating scope. Example, 'empno + 1' in
            //
            //   SELECT empno + 1 FROM emp GROUP BY empno + 1

            final boolean matches = checkAggregateExpr( call, false );
            if ( matches ) {
                return parent;
            }
        }
        return super.getOperandScope( call );
    }


    public boolean checkAggregateExpr( SqlNode expr, boolean deep ) {
        // Fully-qualify any identifiers in expr.
        if ( deep ) {
            expr = validator.expand( expr, this );
        }

        // Make sure expression is valid, throws if not.
        Pair<ImmutableList<SqlNode>, ImmutableList<SqlNode>> pair = getGroupExprs();
        final AggChecker aggChecker = new AggChecker( validator, this, pair.left, pair.right, distinct );
        if ( deep ) {
            expr.accept( aggChecker );
        }

        // Return whether expression exactly matches one of the group expressions.
        return aggChecker.isGroupExpr( expr );
    }


    public void validateExpr( SqlNode expr ) {
        checkAggregateExpr( expr, true );
    }


    /**
     * Information about an aggregating scope that can only be determined after validation has occurred. Therefore it cannot be populated when the scope is created.
     */
    public class Resolved {

        public final ImmutableList<SqlNode> extraExprList;
        public final ImmutableList<SqlNode> groupExprList;
        public final ImmutableBitSet groupSet;
        public final ImmutableList<ImmutableBitSet> groupSets;
        public final Map<Integer, Integer> groupExprProjection;


        Resolved( List<SqlNode> extraExprList, List<SqlNode> groupExprList, Iterable<ImmutableBitSet> groupSets, Map<Integer, Integer> groupExprProjection ) {
            this.extraExprList = ImmutableList.copyOf( extraExprList );
            this.groupExprList = ImmutableList.copyOf( groupExprList );
            this.groupSet = ImmutableBitSet.range( groupExprList.size() );
            this.groupSets = ImmutableList.copyOf( groupSets );
            this.groupExprProjection = ImmutableMap.copyOf( groupExprProjection );
        }


        /**
         * Returns whether a field should be nullable due to grouping sets.
         */
        public boolean isNullable( int i ) {
            return i < groupExprList.size() && !allContain( groupSets, i );
        }


        /**
         * Returns whether a given expression is equal to one of the grouping expressions. Determines whether it is valid as an operand to GROUPING.
         */
        public boolean isGroupingExpr( SqlNode operand ) {
            return lookupGroupingExpr( operand ) >= 0;
        }


        public int lookupGroupingExpr( SqlNode operand ) {
            for ( Ord<SqlNode> groupExpr : Ord.zip( groupExprList ) ) {
                if ( operand.equalsDeep( groupExpr.e, Litmus.IGNORE ) ) {
                    return groupExpr.i;
                }
            }
            return -1;
        }
    }
}
