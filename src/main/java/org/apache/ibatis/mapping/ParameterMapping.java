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

import java.sql.ResultSet;

import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 *
 * 假设我们有这样一条 SQL 语句：
 *
 * SELECT * FROM author WHERE name = #{name} AND age = #{age}
 *
 * 这个 SQL 语句中包含两个#{}占位符，在运行时这两个占位符会被解析成两个 ParameterMapping 对象。如下：
 * ParameterMapping{property='name', mode=IN, javaType=class java.lang.String,  jdbcType=null, ...}
 * ParameterMapping{property='age',  mode=IN, javaType=class java.lang.Integer, jdbcType=null, ...}
 *
 * #{xxx}占位符解析完毕后，得到的 SQL 如下：
 *
 * SELECT * FROM Author WHERE name = ? AND age = ?
 *
 * 这里假设下面这个方法与上面的 SQL 对应：
 *
 * Author findByNameAndAge(@Param("name")String name, @Param("age")Integer　age)
 *
 * 该方法的参数列表会被 ParamNameResolver 解析成一个 map，如下：
 * {
 *    0: "name",
 *    1: "age"
 * }
 *
 * 假设该方法在运行时有如下的调用：
 *
 * findByNameAndAge("zhangsan", 20)
 *
 * 此时，需要再次借助 {@link ParamNameResolver} 的力量。这次我们将参数名和运行时的参数值绑定起来，得到如下的映射关系。
 * {
 *    "name": "zhangsan",
 *    "age": 20,
 *    "param1": "zhangsan",
 *    "param2": 20
 * }
 *
 * 下一步，我们要将运行时参数设置到 SQL 中。由于原 SQL 经过解析后，占位符信息已经被擦除掉了，我们无法直接将运行时参数绑定到 SQL 中。
 * 不过好在，这些占位符信息被记录在了 ParameterMapping 中了。MyBatis 会将 ParameterMapping 会按照#{}占位符的解析顺序存入到 List 中。
 * 这样我们通过 ParameterMapping 在列表中的位置确定它与 SQL 中的哪一个 ? 占位符相关联。
 * 同时通过 ParameterMapping 中的 property 字段，我们可以到“参数名与参数值”映射表中查找具体的参数值。
 * 这样，我们就可以将参数值准确的设置到 SQL 中了，此时SQL 如下：
 *
 * SELECT * FROM Author WHERE name = "zhangsan" AND age = 20
 *
 */
public class ParameterMapping {

  private Configuration configuration;

  //#{xxx}占位符中的属性名
  private String property;

  private ParameterMode mode;

  //#{xxx}占位符中的属性对应的Java类型
  private Class<?> javaType = Object.class;

  //#{xxx}占位符中的属性对应的数据库字段类型
  private JdbcType jdbcType;

  private Integer numericScale;
  private TypeHandler<?> typeHandler;
  private String resultMapId;
  private String jdbcTypeName;
  private String expression;

  private ParameterMapping() {
  }

  public static class Builder {
    private ParameterMapping parameterMapping = new ParameterMapping();

    public Builder(Configuration configuration, String property, TypeHandler<?> typeHandler) {
      parameterMapping.configuration = configuration;
      parameterMapping.property = property;
      parameterMapping.typeHandler = typeHandler;
      parameterMapping.mode = ParameterMode.IN;
    }

    public Builder(Configuration configuration, String property, Class<?> javaType) {
      parameterMapping.configuration = configuration;
      parameterMapping.property = property;
      parameterMapping.javaType = javaType;
      parameterMapping.mode = ParameterMode.IN;
    }

    public Builder mode(ParameterMode mode) {
      parameterMapping.mode = mode;
      return this;
    }

    public Builder javaType(Class<?> javaType) {
      parameterMapping.javaType = javaType;
      return this;
    }

    public Builder jdbcType(JdbcType jdbcType) {
      parameterMapping.jdbcType = jdbcType;
      return this;
    }

    public Builder numericScale(Integer numericScale) {
      parameterMapping.numericScale = numericScale;
      return this;
    }

    public Builder resultMapId(String resultMapId) {
      parameterMapping.resultMapId = resultMapId;
      return this;
    }

    public Builder typeHandler(TypeHandler<?> typeHandler) {
      parameterMapping.typeHandler = typeHandler;
      return this;
    }

    public Builder jdbcTypeName(String jdbcTypeName) {
      parameterMapping.jdbcTypeName = jdbcTypeName;
      return this;
    }

    public Builder expression(String expression) {
      parameterMapping.expression = expression;
      return this;
    }

    public ParameterMapping build() {
      resolveTypeHandler();
      validate();
      return parameterMapping;
    }

    private void validate() {
      if (ResultSet.class.equals(parameterMapping.javaType)) {
        if (parameterMapping.resultMapId == null) { 
          throw new IllegalStateException("Missing resultmap in property '"  
              + parameterMapping.property + "'.  " 
              + "Parameters of type java.sql.ResultSet require a resultmap.");
        }            
      } else {
        if (parameterMapping.typeHandler == null) { 
          throw new IllegalStateException("Type handler was null on parameter mapping for property '"
            + parameterMapping.property + "'. It was either not specified and/or could not be found for the javaType ("
            + parameterMapping.javaType.getName() + ") : jdbcType (" + parameterMapping.jdbcType + ") combination.");
        }
      }
    }

    private void resolveTypeHandler() {
      if (parameterMapping.typeHandler == null && parameterMapping.javaType != null) {
        Configuration configuration = parameterMapping.configuration;
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        parameterMapping.typeHandler = typeHandlerRegistry.getTypeHandler(parameterMapping.javaType, parameterMapping.jdbcType);
      }
    }

  }

  public String getProperty() {
    return property;
  }

  /**
   * Used for handling output of callable statements
   * @return
   */
  public ParameterMode getMode() {
    return mode;
  }

  /**
   * Used for handling output of callable statements
   * @return
   */
  public Class<?> getJavaType() {
    return javaType;
  }

  /**
   * Used in the UnknownTypeHandler in case there is no handler for the property type
   * @return
   */
  public JdbcType getJdbcType() {
    return jdbcType;
  }

  /**
   * Used for handling output of callable statements
   * @return
   */
  public Integer getNumericScale() {
    return numericScale;
  }

  /**
   * Used when setting parameters to the PreparedStatement
   * @return
   */
  public TypeHandler<?> getTypeHandler() {
    return typeHandler;
  }

  /**
   * Used for handling output of callable statements
   * @return
   */
  public String getResultMapId() {
    return resultMapId;
  }

  /**
   * Used for handling output of callable statements
   * @return
   */
  public String getJdbcTypeName() {
    return jdbcTypeName;
  }

  /**
   * Not used
   * @return
   */
  public String getExpression() {
    return expression;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ParameterMapping{");
    //sb.append("configuration=").append(configuration); // configuration doesn't have a useful .toString()
    sb.append("property='").append(property).append('\'');
    sb.append(", mode=").append(mode);
    sb.append(", javaType=").append(javaType);
    sb.append(", jdbcType=").append(jdbcType);
    sb.append(", numericScale=").append(numericScale);
    //sb.append(", typeHandler=").append(typeHandler); // typeHandler also doesn't have a useful .toString()
    sb.append(", resultMapId='").append(resultMapId).append('\'');
    sb.append(", jdbcTypeName='").append(jdbcTypeName).append('\'');
    sb.append(", expression='").append(expression).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
