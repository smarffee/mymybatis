/**
 *    Copyright 2009-2022 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

  private final MapperBuilderAssistant builderAssistant;
  private final XNode context;
  private final String requiredDatabaseId;

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant;
    this.context = context;
    this.requiredDatabaseId = databaseId;
  }

  /**
   * 解析 Statement 节点select|insert|update|delete，
   * 并将解析结果存储到 configuration 的 mappedStatements 集合中
   *
   * 代码中起码有一半的代码是用来获取节点属性，以及解析部分属性等。抛去这部分代码，做的事情如下。
   * 1. 解析<include>节点
   * 2. 解析<selectKey>节点
   * 3. 解析 SQL，获取 SqlSource
   * 4. 构建 MappedStatement 实例
   */
  public void parseStatementNode() {
    //获取唯一标识id 和 databaseId 属性
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");

    // 根据 databaseId 进行检测
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }

    // 获取各种属性
    Integer fetchSize = context.getIntAttribute("fetchSize");
    Integer timeout = context.getIntAttribute("timeout");

    String parameterMap = context.getStringAttribute("parameterMap");

    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);

    String resultMap = context.getStringAttribute("resultMap");
    String resultType = context.getStringAttribute("resultType");

    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = getLanguageDriver(lang);

    // 通过别名解析 resultType 对应的类型
    Class<?> resultTypeClass = resolveClass(resultType);

    String resultSetType = context.getStringAttribute("resultSetType");

    // 解析 Statement 类型，默认为 PREPARED
    StatementType statementType =
            StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));

    // 解析 ResultSetType
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);

    // 获取节点的名称，比如 <select> 节点名称为 select
    String nodeName = context.getNode().getNodeName();

    // 根据节点名称解析 SqlCommandType
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));

    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    // Include Fragments before parsing
    // 解析 <include> 节点
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    // 解析 <selectKey> 节点
    // Parse selectKey after includes and remove them.
    // 对于一些不支持主键自增的数据库，插入数据时，需要明确指定主键数据
    processSelectKeyNodes(id, parameterTypeClass, langDriver);

    // 解析 SQL 语句，获取 SqlSource
    // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);

    String resultSets = context.getStringAttribute("resultSets");
    String keyProperty = context.getStringAttribute("keyProperty");
    String keyColumn = context.getStringAttribute("keyColumn");

    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);

    if (configuration.hasKeyGenerator(keyStatementId)) {
      // 获取 KeyGenerator 实例
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
      // 创建 KeyGenerator 实例
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }

    // 构建 MappedStatement 实例对象，并将该对象存储到 Configuration 的 mappedStatements 集合中
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered, 
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  /**
   * 解析 <selectKey> 节点
   *
   * 对于一些不支持主键自增的数据库，插入数据时，需要明确指定主键数据。
   * 以 Oracle 数据库为例，Oracle 数据库不支持自增主键，但它提供了自增序列工具。
   * 我们每次向数据库中插入数据时，可以先通过自增序列获取主键数据，然后再进行插入。
   * 这里涉及到两次数据库查询操作，但我们并不能在一个<select>节点中同时配置两个 select 语句，这会导致 SQL 语句出错。
   * 对于这个问题，MyBatis 提供的<selectKey>可以很好的解决。下面我们看一段配置：
   *
   * <insert id="saveAuthor">
   *     <selectKey keyProperty="id" resultType="int" order="BEFORE">
   *         select author_seq.nextval from dual
   *     </selectKey>
   *      insert into Author
   *         (id, name, password)
   *      values
   *         (#{id}, #{username}, #{password})
   * </insert>
   *
   * 在上面的配置中，查询语句会先于插入语句执行，这样我们就可以在插入时获取到主键的值。
   *
   * @param id
   * @param parameterTypeClass
   * @param langDriver
   */
  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    List<XNode> selectKeyNodes = context.evalNodes("selectKey");

    if (configuration.getDatabaseId() != null) {
      // 解析 <selectKey> 节点，databaseId 不为空
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }

    // 解析 <selectKey> 节点，databaseId 为空
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);

    // 将 <selectKey> 节点从 dom 树中移除
    // 这样后续可以更专注的解析<insert>或<update>节点中的 SQL，无需再额外处理<selectKey>节点。
    removeSelectKeyNodes(selectKeyNodes);
  }

  //解析 <selectKey> 节点
  private void parseSelectKeyNodes(
          String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    for (XNode nodeToHandle : list) {
      // id = parentId + !selectKey，比如 saveUser!selectKey
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      // 获取 <selectKey> 节点的 databaseId 属性
      String databaseId = nodeToHandle.getStringAttribute("databaseId");
      // 匹配 databaseId
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        // 解析 <selectKey> 节点
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  // 解析 <selectKey> 节点
  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    // 获取各种属性
    String resultType = nodeToHandle.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    //defaults
    // 设置默认值
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    // 1.创建 SqlSource
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);

    // <selectKey> 节点中只能配置 SELECT 查询语句，
    // 因此 sqlCommandType 为 SqlCommandType.SELECT
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    // 2.构建 MappedStatement，并将 MappedStatement 添加到 Configuration 的 mappedStatements map 中
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

    // id = namespace + "." + id
    id = builderAssistant.applyCurrentNamespace(id, false);

    MappedStatement keyStatement = configuration.getMappedStatement(id, false);

    // 3.创建 SelectKeyGenerator，并添加到 keyGenerators map 中
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
  }

  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      if (databaseId != null) {
        return false;
      }
      // skip this statement if there is a previous one with a not null databaseId
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (this.configuration.hasStatement(id, false)) {
        MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
        if (previous.getDatabaseId() != null) {
          return false;
        }
      }
    }
    return true;
  }

  private LanguageDriver getLanguageDriver(String lang) {
    Class<?> langClass = null;
    if (lang != null) {
      langClass = resolveClass(lang);
    }
    return builderAssistant.getLanguageDriver(langClass);
  }

}
