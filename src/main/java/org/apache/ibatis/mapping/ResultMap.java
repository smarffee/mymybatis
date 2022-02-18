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
package org.apache.ibatis.mapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.Jdk;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 *
 *  封装了mapper映射文件中 resultMap 节点解析结果
 *  结果集的配置解析结果
 */
public class ResultMap {

  //Mybatis全局配置
  private Configuration configuration;

  //<ResultMap>配置的id，表示结果集的唯一标识
  private String id;

  //结果集对应的JavaBean
  private Class<?> type;

  //<ResultMap>子节点解析结果集合，表示每一个属性的配置。所有的属性配置
  private List<ResultMapping> resultMappings;

  //结果集主键字段配置。用于存储 <id> 和 <idArg> 节点对应的 ResultMapping 对象
  private List<ResultMapping> idResultMappings;

  //有参构造器实例化对象需要的字段。用于存储 <idArgs> 和 <arg> 节点对应的 ResultMapping 对象
  private List<ResultMapping> constructorResultMappings;

  //JavaBean属性配置（非有参构造器需要的字段）。用于存储 <id> 和 <result> 节点对应的 ResultMapping 对象
  private List<ResultMapping> propertyResultMappings;

  //查询结果集字段名。用于存储 <id>、<result>、<idArg>、<arg> 节点 column 属性
  private Set<String> mappedColumns;

  //JavaBean属性名。用于存储 <id> 和 <result> 节点的 property 属性，或 <idArgs> 和 <arg>节点的 name 属性
  private Set<String> mappedProperties;

  //<ResultMap>节点的子节点<discriminator> 节点解析结果
  private Discriminator discriminator;

  //是否有嵌套子查询结果集ResultMap
  private boolean hasNestedResultMaps;

  //是否有嵌套子查询
  private boolean hasNestedQueries;

  //<ResultMap>节点中autoMapping属性值
  private Boolean autoMapping;

  private ResultMap() {
  }

  public static class Builder {
    private static final Log log = LogFactory.getLog(Builder.class);

    private ResultMap resultMap = new ResultMap();

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
      this(configuration, id, type, resultMappings, null);
    }

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
      resultMap.configuration = configuration;
      resultMap.id = id;
      resultMap.type = type;
      resultMap.resultMappings = resultMappings;
      resultMap.autoMapping = autoMapping;
    }

    public Builder discriminator(Discriminator discriminator) {
      resultMap.discriminator = discriminator;
      return this;
    }

    public Class<?> type() {
      return resultMap.type;
    }

    public ResultMap build() {
      if (resultMap.id == null) {
        throw new IllegalArgumentException("ResultMaps must have an id");
      }

      resultMap.mappedColumns = new HashSet<String>();
      resultMap.mappedProperties = new HashSet<String>();
      resultMap.idResultMappings = new ArrayList<ResultMapping>();
      resultMap.constructorResultMappings = new ArrayList<ResultMapping>();
      resultMap.propertyResultMappings = new ArrayList<ResultMapping>();

      //有参构造器实例化对象时构造器字段名
      final List<String> constructorArgNames = new ArrayList<String>();

      for (ResultMapping resultMapping : resultMap.resultMappings) {
        // 检测 <association> 或 <collection> 节点
        // 是否包含 select 和 resultMap 属性
        resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
        resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps ||
                (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);

        final String column = resultMapping.getColumn();

        //添加结果集字段名
        if (column != null) {
          // 将 colum 转换成大写，并添加到 mappedColumns 集合中
          resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));

        } else if (resultMapping.isCompositeResult()) {
          /**
           * {@link ResultMapping#composites}
           */
          for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
            final String compositeColumn = compositeResultMapping.getColumn();
            if (compositeColumn != null) {
              //添加结果集字段名
              resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
            }
          }
        }

        //添加JavaBean属性名
        final String property = resultMapping.getProperty();
        if(property != null) {
          resultMap.mappedProperties.add(property);
        }

        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          //如果是有参构造器实例化对象
          resultMap.constructorResultMappings.add(resultMapping);
          if (resultMapping.getProperty() != null) {
            constructorArgNames.add(resultMapping.getProperty());
          }
        } else {
          //保存非有参构造器需要的字段
          resultMap.propertyResultMappings.add(resultMapping);
        }

        //保存结果集主键字段配置
        if (resultMapping.getFlags().contains(ResultFlag.ID)) {
          resultMap.idResultMappings.add(resultMapping);
        }
      }

      if (resultMap.idResultMappings.isEmpty()) {
        resultMap.idResultMappings.addAll(resultMap.resultMappings);
      }

      //如果是用有参构造器实例化对象
      if (!constructorArgNames.isEmpty()) {
        //获取构造方法参数列表，篇幅原因，这个方法不分析了
        final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);

        if (actualArgNames == null) {
          throw new BuilderException("Error in result map '" + resultMap.id
              + "'. Failed to find a constructor in '"
              + resultMap.getType().getName() + "' by arg names " + constructorArgNames
              + ". There might be more info in debug log.");
        }

        // 对 constructorResultMappings 按照构造方法参数列表的顺序进行排序
        Collections.sort(resultMap.constructorResultMappings, new Comparator<ResultMapping>() {
          @Override
          public int compare(ResultMapping o1, ResultMapping o2) {
            int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
            int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
            return paramIdx1 - paramIdx2;
          }
        });
      }

      // lock down collections
      // 将以下这些集合变为不可修改集合
      resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
      resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
      resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
      resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
      resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
      return resultMap;
    }

    private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
      Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
      for (Constructor<?> constructor : constructors) {
        Class<?>[] paramTypes = constructor.getParameterTypes();
        if (constructorArgNames.size() == paramTypes.length) {
          List<String> paramNames = getArgNames(constructor);
          if (constructorArgNames.containsAll(paramNames)
              && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
            return paramNames;
          }
        }
      }
      return null;
    }

    private boolean argTypesMatch(final List<String> constructorArgNames,
        Class<?>[] paramTypes, List<String> paramNames) {
      for (int i = 0; i < constructorArgNames.size(); i++) {
        Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
        Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
        if (!actualType.equals(specifiedType)) {
          if (log.isDebugEnabled()) {
            log.debug("While building result map '" + resultMap.id
                + "', found a constructor with arg names " + constructorArgNames
                + ", but the type of '" + constructorArgNames.get(i)
                + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                + actualType.getName() + "]");
          }
          return false;
        }
      }
      return true;
    }

    private List<String> getArgNames(Constructor<?> constructor) {
      List<String> paramNames = new ArrayList<String>();
      List<String> actualParamNames = null;
      final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
      int paramCount = paramAnnotations.length;
      for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
        String name = null;
        for (Annotation annotation : paramAnnotations[paramIndex]) {
          if (annotation instanceof Param) {
            name = ((Param) annotation).value();
            break;
          }
        }
        if (name == null && resultMap.configuration.isUseActualParamName() && Jdk.parameterExists) {
          if (actualParamNames == null) {
            actualParamNames = ParamNameUtil.getParamNames(constructor);
          }
          if (actualParamNames.size() > paramIndex) {
            name = actualParamNames.get(paramIndex);
          }
        }
        paramNames.add(name != null ? name : "arg" + paramIndex);
      }
      return paramNames;
    }
  }

  public String getId() {
    return id;
  }

  public boolean hasNestedResultMaps() {
    return hasNestedResultMaps;
  }

  public boolean hasNestedQueries() {
    return hasNestedQueries;
  }

  public Class<?> getType() {
    return type;
  }

  public List<ResultMapping> getResultMappings() {
    return resultMappings;
  }

  public List<ResultMapping> getConstructorResultMappings() {
    return constructorResultMappings;
  }

  public List<ResultMapping> getPropertyResultMappings() {
    return propertyResultMappings;
  }

  public List<ResultMapping> getIdResultMappings() {
    return idResultMappings;
  }

  public Set<String> getMappedColumns() {
    return mappedColumns;
  }

  public Set<String> getMappedProperties() {
    return mappedProperties;
  }

  public Discriminator getDiscriminator() {
    return discriminator;
  }

  public void forceNestedResultMaps() {
    hasNestedResultMaps = true;
  }
  
  public Boolean getAutoMapping() {
    return autoMapping;
  }

}
