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

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * 创建mapper.xml映射文件解析器
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

  private final XPathParser parser;

  private final MapperBuilderAssistant builderAssistant;

  //用来保存<sql>节点
  //key: currentNamespace + "." + <sql.id>； <sql>结点
  private final Map<String, XNode> sqlFragments;

  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 解析xml映射文件
   */
  public void parse() {
    //如果当前mapper没有被解析
    if (!configuration.isResourceLoaded(resource)) {

      //解析mapper节点
      configurationElement(parser.evalNode("/mapper"));

      //将resource保存在已经解析过的集合中
      configuration.addLoadedResource(resource);

      //通过命名空间绑定mapper接口
      //要通过命名空间绑定 mapper 接口，这样才能将映射文件中的 SQL 语句和 mapper 接口中的方法绑定在一起，
      //后续可直接通过调用 mapper 接口方法执行与之对应的 SQL 语句。
      bindMapperForNamespace();
    }

    /*
     * 处理未完成的解析的节点。
     * 在解析某些节点的过程中，如果这些节点引用了其他一些未被解析的配置，会导致当前节点解析工作无法进行下去。
     * 对于这种情况，MyBatis 的做法是抛出 IncompleteElementException异常。
     * 外部逻辑会捕捉这个异常，并将节点对应的解析器放入 incomplet*集合中。
     */
    parsePendingResultMaps();
    parsePendingCacheRefs();
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   * 解析映射文件
   * 一级节点：<mapper>
   * 二级节点：<cache-ref>、<cache>、<ref>、<resultMap>、<insert>|<select>|<update>|<delete>等
   * 三级节点：<if>、<when>、<where>、<include>等
   *
   * <mapper namespace="xyz.coolblog.dao.AuthorDao">
   * 	<cache/>
   *
   * 	<resultMap id="authorResult" type="Author">
   * 		<id property="id" column="id"/>
   * 		<result property="name" column="name"/>
   * 		<!-- ... -->
   * 	</resultMap>
   *
   * 	<sql id="table">
   * 	 author
   * 	</sql>
   *
   * 	<select id="findOne" resultMap="authorResult">
   * 	 SELECT
   * 	 id, name, age, sex, email
   * 	 FROM
   * 	<include refid="table"/>
   * 	 WHERE
   * 	 id = #{id}
   * 	</select>
   *
   * 	<!-- <insert|update|delete/> -->
   * </mapper>
   *
   * @param context
   */
  private void configurationElement(XNode context) {
    try {
      //获取当前mapper映射文件的命名空间
      String namespace = context.getStringAttribute("namespace");
      //如果没有配置命名空间，则抛出异常
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      //设置命名空间名称到 builderAssistant 中
      builderAssistant.setCurrentNamespace(namespace);
      //解析<cache-ref>节点
      cacheRefElement(context.evalNode("cache-ref"));
      //解析<cache>节点
      cacheElement(context.evalNode("cache"));
      //解析<parameterMap>节点
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      //解析<resultMap>节点
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      //解析<sql>节点
      sqlElement(context.evalNodes("/mapper/sql"));
      //解析select .... delete 等节点
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  //解析select .... delete 等节点
  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      // 调用重载方法构建 Statement
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    // 调用重载方法构建 Statement，requiredDatabaseId 参数为空
    buildStatementFromContext(list, null);
  }

  //解析select .... delete 等节点，构建Statement
  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      //创建 Statement 建造类
      final XMLStatementBuilder statementParser =
              new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        // 解析 Statement 节点，并将解析结果存储到 configuration 的 mappedStatements 集合中
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        // 解析失败，将解析器放入 Configuration 的 incompleteStatements 集合中
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    // 1.获取 CacheRefResolver 列表
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      // 通过迭代器遍历列表
      while (iter.hasNext()) {
        try {
          // 2.尝试解析 <cache-ref> 节点，若解析失败，则抛出 IncompleteElementException，此时下面的删除操作不会被执行
          iter.next().resolveCacheRef();
          // 3.移除 CacheRefResolver 对象。如果代码能执行到此处，表明已成功解析了 <cache-ref> 节点
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
          // 如果再次发生 IncompleteElementException 异常，表明当前映射文件中并没有<cache-ref>所引用的缓存。
          // 有可能所引用的缓存在后面的映射文件中，所以这里不能将解析失败的 CacheRefResolver从集合中删除。
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  /**
   * 解析<cache-ref>节点
   *
   * 在 MyBatis 中，二级缓存是可以共用的。这需要通过<cache-ref>节点为命名空间配置参
   * 照缓存，比如像下面这样。
   *
   * <!-- Mapper1.xml -->
   * <mapper namespace="xyz.coolblog.dao.Mapper1">
   * 	<!-- Mapper1 与 Mapper2 共用一个二级缓存 -->
   * 	<cache-ref namespace="xyz.coolblog.dao.Mapper2"/>
   * </mapper>
   *
   * <!-- Mapper2.xml -->
   * <mapper namespace="xyz.coolblog.dao.Mapper2">
   * 	<cache/>
   * </mapper>
   * @param context
   */
  private void cacheRefElement(XNode context) {
    if (context != null) {
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      //创建CacheRefResolver实例
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        //解析参照缓存
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  /**
   * 解析mapper映射文件中的cache节点。
   * 配置了Mybatis的二级缓存，如果无特殊要求，二级缓存的配置很简单，如下：
   * <cache/>
   *
   * 如果我们想修改缓存的一些属性，可以像下面这样配置。
   * <cache
   *    eviction="FIFO"
   *    flushInterval="60000"
   *    size="512"
   *    readOnly="true"/>
   *
   * 根据上面的配置创建出的缓存有以下特点：
   * 1. 按先进先出的策略淘汰缓存项
   * 2. 缓存的容量为 512 个对象引用
   * 3. 缓存每隔 60 秒刷新一次
   * 4. 缓存返回的对象是写安全的，即在外部修改对象不会影响到缓存内部存储对象
   *
   * 除了上面两种配置方式，我们还可以给 MyBatis 配置第三方缓存或者自己实现的缓存等。
   * 比如，我们将 Ehcache 缓存整合到 MyBatis 中，可以这样配置。
   * <cache type="org.mybatis.caches.ehcache.EhcacheCache"/>
   * 	<property name="timeToIdleSeconds" value="3600"/>
   * 	<property name="timeToLiveSeconds" value="3600"/>
   * 	<property name="maxEntriesLocalHeap" value="1000"/>
   * 	<property name="maxEntriesLocalDisk" value="10000000"/>
   * 	<property name="memoryStoreEvictionPolicy" value="LRU"/>
   * </cache>
   *
   * @param context
   * @throws Exception
   */
  private void cacheElement(XNode context) throws Exception {
    if (context != null) {
      //获取type属性值
      String type = context.getStringAttribute("type", "PERPETUAL");
      //根据别名利用反射实例化type
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      //获取缓存淘汰策略
      String eviction = context.getStringAttribute("eviction", "LRU");
      //根据别名利用反射实例化
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      //获取刷新策略
      Long flushInterval = context.getLongAttribute("flushInterval");
      //获取缓存大小
      Integer size = context.getIntAttribute("size");
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      boolean blocking = context.getBooleanAttribute("blocking", false);
      //获取子节点配置
      Properties props = context.getChildrenAsProperties();
      //构建缓存对象
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) throws Exception {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  /**
   * 解析<resultMap>节点
   * resultMap 是 MyBatis 框架中常用的特性，主要用于映射结果。
   * resultMap 元素是 MyBatis 中最重要最强大的元素，它可以把大家从 JDBC ResultSets 数据提取的工作中解放出来。
   * 通过 resultMap 和自动映射，可以让 MyBatis 帮助我们完成ResultSet -> Object 的映射
   *
   * @param list
   * @throws Exception
   */
  private void resultMapElements(List<XNode> list) throws Exception {
    //遍历 <resultMap>节点
    for (XNode resultMapNode : list) {
      try {
        //解析 resultMap 节点
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  //解析 resultMap 节点
  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    // 调用重载方法
    return resultMapElement(resultMapNode, Collections.<ResultMapping> emptyList());
  }

  //解析 resultMap 节点
  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());

    //1. 获取 resultMap 节点各个属性
    //获取id属性，resultMap唯一标识
    String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());

    //获取type属性
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));

    // 获取 extends 和 autoMapping
    String extend = resultMapNode.getStringAttribute("extends");
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");

    //获取type的Class对象
    Class<?> typeClass = resolveClass(type);

    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
    resultMappings.addAll(additionalResultMappings);

    //2.获取并遍历 <resultMap> 的子节点列表
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {

      //3.根据子节点名称执行相应的解析逻辑
      if ("constructor".equals(resultChild.getName())) {

        // 解析 constructor 节点，并生成相应的 ResultMapping
        processConstructorElement(resultChild, typeClass, resultMappings);

      } else if ("discriminator".equals(resultChild.getName())) {

        // 解析 discriminator 节点
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);

      } else {

        List<ResultFlag> flags = new ArrayList<ResultFlag>();
        if ("id".equals(resultChild.getName())) {
          // 添加 ID 到 flags 集合中
          flags.add(ResultFlag.ID);
        }

        // 解析 id 和 property 节点，并生成相应的 ResultMapping
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }

    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);

    try {

      //4.根据前面获取到的信息构建 ResultMap 对象
      return resultMapResolver.resolve();

    } catch (IncompleteElementException  e) {
      /*
       * 5.如果发生 IncompleteElementException 异常，
       * 这里将 resultMapResolver 添加到 incompleteResultMaps 集合中
       */
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  /**
   * 解析<constructor>节点
   * <constructor>
   *     <idArg column="id" name="id"/>
   *     <arg column="title" name="title"/>
   *     <arg column="content" name="content"/>
   * </constructor>
   */
  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    // 获取子节点列表
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<ResultFlag>();

      // 向 flags 中添加 CONSTRUCTOR 标志
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        // 向 flags 中添加 ID 标志
        flags.add(ResultFlag.ID);
      }

      // 构建 ResultMapping，
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<String, String>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  /**
   * 解析<sql>节点。
   *
   * <sql>节点用来定义一些可重用的 SQL 语句片段，比如表名，或表的列名等。在映射文
   * 件中，我们可以通过<include>节点引用<sql>节点定义的内容。下面我来演示一下<sql>节点
   * 的使用方式，如下：
   *
   * <sql id="table" databaseId="">
   *  article
   * </sql>
   * <sql id="table">
   *  ${table_prefix}_article
   * </sql>
   *
   * <select id="findOne" resultType="Article">
   *  SELECT id, title FROM <include refid="table"/> WHERE id = #{id}
   * </select>
   * <update id="update" parameterType="Article">
   *  UPDATE <include refid="table"/> SET title = #{title} WHERE id = #{id}
   * </update>
   *
   * @param list
   * @throws Exception
   */
  private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) {
      //调用 sqlElement 解析 <sql> 节点
      //传入具体的 databaseId，用于解析带有 databaseId 属性，且属性值与此相等的<sql>节点
      sqlElement(list, configuration.getDatabaseId());
    }
    //再次调用 sqlElement，不同的是，这次调用，该方法的第二个参数为 null
    //次传入的 databaseId 为空，用于解析未配置 databaseId 属性的<sql>节点
    sqlElement(list, null);
  }

  /**
   * 关于 databaseId 的用途，简单介绍一下。
   * databaseId用于标明数据库厂商的身份，不同厂商有自己的 SQL 方言，MyBatis 可以根据 databaseId 执行不同 SQL 语句。
   *
   * databaseId 在<sql>节点中有什么用呢？这个问题也不难回答。
   * <sql>节点用于保存 SQL 语句片段，如果 SQL 语句片段中包含方言的话，
   * 那么该<sql>节点只能被同一databaseId 的查询语句或更新语句引用。
   *
   * @param list
   * @param requiredDatabaseId
   * @throws Exception
   */
  private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
    for (XNode context : list) {
      // 1.获取 id 和 databaseId 属性
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");

      // 2.为 id 属性值拼接命名空间
      // id = currentNamespace + "." + id
      id = builderAssistant.applyCurrentNamespace(id, false);

      // 3. 通过检测当前 databaseId 和 requiredDatabaseId 是否一致，来决定保存还是忽略当前的<sql>节点
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        // 将 <id, XNode> 键值对缓存到 sqlFragments 中
        sqlFragments.put(id, context);
      }
    }
  }

  /**
   * 检测当前 databaseId 和 requiredDatabaseId 是否一致
   * 1. databaseId 与 requiredDatabaseId 不一致，即失配，返回 false
   * 2. 当前节点与之前的节点出现 id 重复的情况，若之前的<sql>节点 databaseId 属性。不为空，返回 false
   * 3. 若以上两条规则均匹配失败，此时返回 true
   * @param id
   * @param databaseId
   * @param requiredDatabaseId
   * @return
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      // 当前 databaseId 和目标 databaseId 不一致时，返回 false
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      // 如果目标 databaseId 为空，但当前 databaseId 不为空。两者不一致，返回 false
      if (databaseId != null) {
        return false;
      }

      // skip this fragment if there is a previous one with a not null databaseId
      // 如果当前 <sql> 节点的 id 与之前的 <sql> 节点重复，且先前节点 databaseId 不为空。则忽略当前节点，并返回 false
      if (this.sqlFragments.containsKey(id)) {
        XNode context = this.sqlFragments.get(id);
        if (context.getStringAttribute("databaseId") != null) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * 解析 id 和 property 节点，并生成相应的 ResultMapping
   *
   * 主要用于获取<id>和<result>节点的属性，其中，resultMap 属性的解析过程要相对复杂一些。
   * 该属性存在于<association>和<collection>节点中。
   *
   * 第一种配置方式是通过 resultMap 属性引用其他的<resultMap>节点，配置如下：
   * <resultMap id="articleResult" type="Article">
   * 	<id property="id" column="id"/>
   * 	<result property="title" column="article_title"/>
   * 	<association property="article_author" column="article_author_id" resultMap="authorResult"/>
   * </resultMap>
   * <resultMap id="authorResult" type="Author">
   * 	<id property="id" column="author_id"/>
   * 	<result property="name" column="author_name"/>
   * </resultMap>
   *
   *
   * 第二种配置方式是采取 resultMap 嵌套的方式进行配置，如下：
   * <resultMap id="articleResult" type="Article">
   *     <id property="id" column="id"/>
   *     <result property="title" column="article_title"/>
   *     <association property="article_author" javaType="Author">
   *         <id property="id" column="author_id"/>
   *         <result property="name" column="author_name"/>
   *     </association>
   * </resultMap
   *
   * 如上配置，<association>的子节点是一些结果映射配置，这些结果配置最终也会被解析成 ResultMap。
   *
   * @param context resultMap的子节点
   * @param resultType
   * @param flags
   * @return
   * @throws Exception
   */
  private ResultMapping buildResultMappingFromContext(
          XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {

    String property;

    // 根据节点类型获取 name 或 property 属性
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      //java对象中的属性
      property = context.getStringAttribute("property");
    }

    // 获取其他各种属性
    String column = context.getStringAttribute("column"); //查询结果集返回的字段名
    String javaType = context.getStringAttribute("javaType"); //Java对象中的类型
    String jdbcType = context.getStringAttribute("jdbcType"); //结果集字段的数据库类型
    String nestedSelect = context.getStringAttribute("select"); //嵌套子查询

    // 解析 resultMap 属性，该属性出现在 <association> 和 <collection> 节点中。
    // 若这两个节点不包含 resultMap 属性，则调用 processNestedResultMappings 方法
    // 解析嵌套 resultMap。
    String nestedResultMap = context.getStringAttribute("resultMap",
            processNestedResultMappings(context, Collections.<ResultMapping>emptyList()));

    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");

    boolean lazy = "lazy".equals(
            context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));

    // 解析 javaType、typeHandler 的类型以及枚举类型 JdbcType
    Class<?> javaTypeClass = resolveClass(javaType);

    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);

    // 构建 ResultMapping 对象
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect,
            nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  /**
   * 嵌套子查询结果集映射
   *
   * @param context
   * @param resultMappings
   * @return
   * @throws Exception
   */
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
    // 判断节点名称
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      if (context.getStringAttribute("select") == null) {
        // resultMapElement 是解析 ResultMap 入口方法
        ResultMap resultMap = resultMapElement(context, resultMappings);
        // 返回 resultMap id
        return resultMap.getId();
      }
    }
    return null;
  }

  //通过命名空间绑定mapper接口
  //要通过命名空间绑定 mapper 接口，这样才能将映射文件中的 SQL 语句和 mapper 接口中的方法绑定在一起，
  //后续可直接通过调用 mapper 接口方法执行与之对应的 SQL 语句。
  private void bindMapperForNamespace() {
    // 获取映射文件的命名空间
    String namespace = builderAssistant.getCurrentNamespace();

    if (namespace != null) {
      Class<?> boundType = null;

      try {
        // 根据命名空间解析 mapper 类型
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //ignore, bound type is not required
      }

      if (boundType != null) {
        // 检测当前 mapper 类是否被绑定过
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
          configuration.addLoadedResource("namespace:" + namespace);
          // 绑定 mapper 类
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
