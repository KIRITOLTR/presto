/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.rewrite;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.analyzer.AnalyzerOptions;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.sql.analyzer.BuiltInQueryPreparer;
import com.facebook.presto.sql.analyzer.BuiltInQueryPreparer.BuiltInPreparedQuery;
import com.facebook.presto.sql.analyzer.QueryExplainer;
import com.facebook.presto.sql.analyzer.SemanticException;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.Explain;
import com.facebook.presto.sql.tree.ExplainFormat;
import com.facebook.presto.sql.tree.ExplainOption;
import com.facebook.presto.sql.tree.ExplainType;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Node;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.Parameter;
import com.facebook.presto.sql.tree.Statement;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.sql.QueryUtil.singleValueQuery;
import static com.facebook.presto.sql.tree.ExplainFormat.Type.JSON;
import static com.facebook.presto.sql.tree.ExplainFormat.Type.TEXT;
import static com.facebook.presto.sql.tree.ExplainType.Type.IO;
import static com.facebook.presto.sql.tree.ExplainType.Type.LOGICAL;
import static com.facebook.presto.sql.tree.ExplainType.Type.VALIDATE;
import static com.facebook.presto.util.AnalyzerUtil.createAnalyzerOptions;
import static java.util.Objects.requireNonNull;

final class ExplainRewrite
        implements StatementRewrite.Rewrite
{
    @Override
    public Statement rewrite(
            Session session,
            Metadata metadata,
            SqlParser parser,
            Optional<QueryExplainer> queryExplainer,
            Statement node,
            List<Expression> parameter,
            Map<NodeRef<Parameter>, Expression> parameterLookup,
            AccessControl accessControl,
            WarningCollector warningCollector,
            String query)
    {
        return (Statement) new Visitor(session, parser, queryExplainer, warningCollector, query).process(node, null);
    }

    private static final class Visitor
            extends AstVisitor<Node, Void>
    {
        private final Session session;
        private final BuiltInQueryPreparer queryPreparer;
        private final Optional<QueryExplainer> queryExplainer;
        private final WarningCollector warningCollector;
        private final String query;

        public Visitor(
                Session session,
                SqlParser parser,
                Optional<QueryExplainer> queryExplainer,
                WarningCollector warningCollector,
                String query)
        {
            this.session = requireNonNull(session, "session is null");
            this.queryPreparer = new BuiltInQueryPreparer(requireNonNull(parser, "queryPreparer is null"));
            this.queryExplainer = requireNonNull(queryExplainer, "queryExplainer is null");
            this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
            this.query = requireNonNull(query, "query is null");
        }

        @Override
        protected Node visitExplain(Explain node, Void context)
                throws SemanticException
        {
            if (isTypeValidate(node)) {
                return process(node.getStatement());
            }

            if (node.isAnalyze()) {
                Statement statement = (Statement) process(node.getStatement(), context);
                return new Explain(statement, node.isAnalyze(), node.isVerbose(), node.getOptions());
            }

            ExplainType.Type planType = LOGICAL;
            ExplainFormat.Type planFormat = TEXT;
            List<ExplainOption> options = node.getOptions();

            for (ExplainOption option : options) {
                if (option instanceof ExplainType) {
                    planType = ((ExplainType) option).getType();
                    // Use JSON as the default format for EXPLAIN (TYPE IO).
                    if (planType == IO) {
                        planFormat = JSON;
                    }
                    break;
                }
            }

            for (ExplainOption option : options) {
                if (option instanceof ExplainFormat) {
                    planFormat = ((ExplainFormat) option).getType();
                    break;
                }
            }

            return getQueryPlan(node, planType, planFormat);
        }

        private Node getQueryPlan(Explain node, ExplainType.Type planType, ExplainFormat.Type planFormat)
                throws IllegalArgumentException
        {
            AnalyzerOptions analyzerOptions = createAnalyzerOptions(session, warningCollector);
            BuiltInPreparedQuery preparedQuery = queryPreparer.prepareQuery(analyzerOptions, node.getStatement(), session.getPreparedStatements());
            String plan;
            switch (planFormat) {
                case GRAPHVIZ:
                    plan = queryExplainer.get().getGraphvizPlan(session, preparedQuery.getStatement(), planType, preparedQuery.getParameters(), warningCollector, query);
                    break;
                case JSON:
                    plan = queryExplainer.get().getJsonPlan(session, preparedQuery.getStatement(), planType, preparedQuery.getParameters(), warningCollector, query);
                    break;
                case TEXT:
                    plan = queryExplainer.get().getPlan(session, preparedQuery.getStatement(), planType, preparedQuery.getParameters(), node.isVerbose(), warningCollector, query);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid Explain Format: " + planFormat.toString());
            }
            return singleValueQuery("Query Plan", plan);
        }

        @Override
        protected Node visitNode(Node node, Void context)
        {
            return node;
        }

        private boolean isTypeValidate(Statement statement)
        {
            if (!(statement instanceof Explain)) {
                return false;
            }

            return ((Explain) statement).getOptions()
                    .stream()
                    .filter(option -> option instanceof ExplainType)
                    .map(option -> (ExplainType) option)
                    .anyMatch(explainType -> explainType.getType() == VALIDATE);
        }
    }
}
