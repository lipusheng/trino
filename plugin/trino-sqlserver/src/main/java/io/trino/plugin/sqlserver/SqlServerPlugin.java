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
package io.trino.plugin.sqlserver;

import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.JdbcPlugin;
import io.trino.spi.function.ConnectorConfig;
import io.trino.spi.queryeditorui.ConnectorUtil;
import io.trino.spi.queryeditorui.ConnectorWithProperties;

import java.util.Arrays;
import java.util.Optional;

@ConnectorConfig(propertiesEnabled = true)
public class SqlServerPlugin
        extends JdbcPlugin
{
    public SqlServerPlugin()
    {
        super("sqlserver", new SqlServerClientModule());
    }

    @Override
    public Optional<ConnectorWithProperties> getConnectorWithProperties()
    {
        ConnectorConfig connectorConfig = SqlServerPlugin.class.getAnnotation(ConnectorConfig.class);
        Optional<ConnectorWithProperties> connectorWithProperties = ConnectorUtil.assembleConnectorProperties(connectorConfig,
                Arrays.asList(BaseJdbcConfig.class.getDeclaredMethods()));
        ConnectorUtil.addConnUrlProperty(connectorWithProperties, "jdbc:sqlserver://[serverName[\\instanceName][:portNumber]]");
        return connectorWithProperties;
    }
}
