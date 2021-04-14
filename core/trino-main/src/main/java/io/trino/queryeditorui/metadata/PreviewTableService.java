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
package io.trino.queryeditorui.metadata;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import io.trino.client.QueryData;
import io.trino.client.StatementClient;
import io.trino.queryeditorui.QueryEditorUIModule;
import io.trino.queryeditorui.execution.QueryClient;
import io.trino.queryeditorui.execution.QueryRunner;
import org.joda.time.Duration;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class PreviewTableService
{
    private static final Logger log = Logger.get(PreviewTableService.class);
    private static final Joiner FQN_JOINER = Joiner.on('.').skipNulls();
    private static final int PREVIEW_LIMIT = 100;
    private final QueryRunner.QueryRunnerFactory queryRunnerFactory;

    @Inject
    public PreviewTableService(final QueryRunner.QueryRunnerFactory queryRunnerFactory)
    {
        this.queryRunnerFactory = requireNonNull(queryRunnerFactory, "queryRunnerFactory session was null!");
    }

    public List<List<Object>> getPreview(String catalogName, String schemaName,
            String tableName, String user) throws ExecutionException
    {
        return queryRows(FQN_JOINER.join(catalogName, schemaName, tableName), user);
    }

    private List<List<Object>> queryRows(String fqnTableName, String user)
    {
        String statement = format("SELECT * FROM %s LIMIT %d", fqnTableName, PREVIEW_LIMIT);
        QueryRunner queryRunner = queryRunnerFactory.create(QueryEditorUIModule.UI_QUERY_SOURCE, user);
        QueryClient queryClient = new QueryClient(queryRunner, Duration.standardSeconds(60), statement);

        final ImmutableList.Builder<List<Object>> cache = ImmutableList.builder();
        try {
            queryClient.executeWith(new Function<StatementClient, Void>() {
                @Nullable
                @Override
                public Void apply(StatementClient client)
                {
                    QueryData results = client.currentData();
                    if (results.getData() != null) {
                        cache.addAll(results.getData());
                    }

                    return null;
                }
            });
        }
        catch (QueryClient.QueryTimeOutException e) {
            log.error("Caught timeout loading columns", e);
        }

        return cache.build();
    }
}
