/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
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
 * </p>
 */

package io.shardingsphere.dbtest.env.dataset;

import com.google.common.base.Joiner;
import io.shardingsphere.core.rule.DataNode;
import io.shardingsphere.core.util.InlineExpressionParser;
import io.shardingsphere.dbtest.cases.assertion.root.SQLValue;
import io.shardingsphere.dbtest.cases.assertion.root.SQLValueGroup;
import io.shardingsphere.dbtest.cases.dataset.metadata.DataSetColumn;
import io.shardingsphere.dbtest.cases.dataset.metadata.DataSetMetadata;
import io.shardingsphere.dbtest.cases.dataset.row.DataSetRow;
import io.shardingsphere.dbtest.cases.dataset.DataSet;

import javax.sql.DataSource;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Data set environment manager.
 *
 * @author zhangliang
 */
public final class DataSetEnvironmentManager {
    
    private final DataSet dataSet;
    
    private final Map<String, DataSource> dataSourceMap;
    
    public DataSetEnvironmentManager(final String path, final Map<String, DataSource> dataSourceMap) throws IOException, JAXBException {
        try (FileReader reader = new FileReader(path)) {
            dataSet = (DataSet) JAXBContext.newInstance(DataSet.class).createUnmarshaller().unmarshal(reader);
        }
        this.dataSourceMap = dataSourceMap;
    }
    
    /**
     * Initialize data.
     * 
     * @throws SQLException SQL exception
     * @throws ParseException parse exception
     */
    public void initialize() throws SQLException, ParseException {
        clear();
        Map<DataNode, List<DataSetRow>> dataNodeListMap = getDataSetRowMap();
        for (Entry<DataNode, List<DataSetRow>> entry : dataNodeListMap.entrySet()) {
            DataNode dataNode = entry.getKey();
            List<DataSetRow> dataSetRows = entry.getValue();
            DataSetMetadata dataSetMetadata = dataSet.findMetadata(dataNode);
            String insertSQL = generateInsertSQL(dataNode.getTableName(), dataSetMetadata.getColumns());
            List<SQLValueGroup> sqlValueGroups = new LinkedList<>();
            for (DataSetRow row : dataSetRows) {
                sqlValueGroups.add(new SQLValueGroup(dataSetMetadata, row.getValues()));
            }
            try (Connection connection = dataSourceMap.get(dataNode.getDataSourceName()).getConnection()) {
                executeBatch(connection, insertSQL, sqlValueGroups);
            }
        }
    }
    
    private Map<DataNode, List<DataSetRow>> getDataSetRowMap() {
        Map<DataNode, List<DataSetRow>> result = new LinkedHashMap<>();
        for (DataSetRow each : dataSet.getRows()) {
            DataNode dataNode = new DataNode(each.getDataNode());
            if (!result.containsKey(dataNode)) {
                result.put(dataNode, new LinkedList<DataSetRow>());
            }
            result.get(dataNode).add(each);
        }
        return result;
    }
    
    private String generateInsertSQL(final String tableName, final List<DataSetColumn> columnMetadata) {
        List<String> columnNames = new LinkedList<>();
        List<String> placeholders = new LinkedList<>();
        for (DataSetColumn each : columnMetadata) {
            columnNames.add(each.getName());
            placeholders.add("?");
        }
        return String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, Joiner.on(",").join(columnNames), Joiner.on(",").join(placeholders));
    }
    
    private void executeBatch(final Connection connection, final String sql, final List<SQLValueGroup> sqlValueGroups) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            for (SQLValueGroup each : sqlValueGroups) {
                setParameters(preparedStatement, each);
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        }
    }
    
    private void setParameters(final PreparedStatement preparedStatement, final SQLValueGroup sqlValueGroup) throws SQLException {
        for (SQLValue each : sqlValueGroup.getSqlValues()) {
            preparedStatement.setObject(each.getIndex(), each.getValue());
        }
    }
    
    /**
     * Clear data.
     * 
     * @throws SQLException SQL exception
     */
    public void clear() throws SQLException {
        for (Entry<String, Collection<String>> entry : getDataNodeMap().entrySet()) {
            clear(entry.getKey(), entry.getValue());
        }
    }
    
    private void clear(final String dataSourceName, final Collection<String> tableNames) throws SQLException {
        try (Connection connection = dataSourceMap.get(dataSourceName).getConnection()) {
            for (String each : tableNames) {
                try (PreparedStatement preparedStatement = connection.prepareStatement(String.format("TRUNCATE TABLE %s", each))) {
                    preparedStatement.executeUpdate();
                    // CHECKSTYLE:OFF
                } catch (final SQLException ex) {
                    // CHECKSTYLE:ON
                }
            }
        }
    }
    
    private Map<String, Collection<String>> getDataNodeMap() {
        Map<String, Collection<String>> result = new LinkedHashMap<>();
        for (DataSetMetadata each : dataSet.getMetadataList()) {
            for (Entry<String, Collection<String>> entry : getDataNodeMap(each).entrySet()) {
                if (!result.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), new LinkedList<String>());
                }
                result.get(entry.getKey()).addAll(entry.getValue());
            }
        }
        return result;
    }
    
    private Map<String, Collection<String>> getDataNodeMap(final DataSetMetadata dataSetMetadata) {
        Map<String, Collection<String>> result = new LinkedHashMap<>();
        for (String each : new InlineExpressionParser(dataSetMetadata.getDataNodes()).splitAndEvaluate()) {
            DataNode dataNode = new DataNode(each);
            if (!result.containsKey(dataNode.getDataSourceName())) {
                result.put(dataNode.getDataSourceName(), new LinkedList<String>());
            }
            result.get(dataNode.getDataSourceName()).add(dataNode.getTableName());
            
        }
        return result;
    }
}
