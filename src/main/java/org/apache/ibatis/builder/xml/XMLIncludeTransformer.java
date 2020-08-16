/**
 *    Copyright 2009-2017 the original author or authors.
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

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  //解析 <include> 节点
  public void applyIncludes(Node source) {
    //创建了一个新的 Properties 对象，并将全局 Properties 添加到其中。
    //这样做的原因是 applyIncludes 的重载方法会向 Properties 中添加新的元素，
    //如果直接将全局 Properties 传给重载方法，会造成全局 Properties 被污染。
    Properties variablesContext = new Properties();

    Properties configurationVariables = configuration.getVariables();
    if (configurationVariables != null) {
      // 将 configurationVariables 中的数据添加到 variablesContext 中
      variablesContext.putAll(configurationVariables);
    }

    // 调用重载方法处理 <include> 节点
    applyIncludes(source, variablesContext, false);
  }

  /**
   * 解析 <include> 节点
   *
   * <mapper namespace="xyz.coolblog.dao.ArticleDao">
   *     <sql id="table">
   *         ${table_name}
   *     </sql>
   *     <select id="findOne" resultType="xyz.coolblog.dao.Article">
   *          <!-- 子节点1：TEXT_NODE 文本节点-->
   *          SELECT id, title FROM
   *
   *          <!-- 子节点2：ELEMENT_NODE 普通节点 -->
   *             <include refid="table">
   *                 <property name="table_name" value="article"/>
   *             </include>
   *
   *          <!-- 子节点3：TEXT_NODE 文本节点 -->
   *          WHERE id = #{id}
   *     </select>
   * </mapper>
   *
   * 第一次调用：进入条件分支2
   * source = <select> 节点
   * 节点类型：ELEMENT_NODE
   * variablesContext = [ ] // 无内容
   * included = false
   *
   * 节点2第一次调用同上
   * 第二次调用：进入条件分支3
   * source = [#text SELECT id, title FROM]
   * 节点类型：TEXT_NODE
   * variablesContext = [ ] // 无内容
   * included = false
   *
   * 节点3第一次调用同上：
   * 第二次调用：进图条件分支1
   * source = <include> 节点
   * 节点类型：ELEMENT_NODE
   * variablesContext = [ ] // 无内容
   * included = false
   *
   * 第三次调用：进入条件分支2
   * source = <sql> 节点
   * 节点类型：ELEMENT_NODE
   * variablesContext = [{"table_name":"article"}] // 无内容
   * included = true
   *
   * 第四次调用：进入条件分支3
   * source = [#text ${table_name}]
   * 节点类型：TEXT_NODE
   * variablesContext = [{"table_name":"article"}] // 无内容
   * included = true
   *
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  private void applyIncludes(
          Node source, // <select> 节点 ；节点类型：ELEMENT_NODE
          final Properties variablesContext, // [ ] // 无内容
          boolean included) { //false

    // ⭐ 第一个条件分支
    if (source.getNodeName().equals("include")) {
      // 获取 <sql> 节点。
      // 若 refid 中包含属性占位符 ${}，则需先将属性占位符替换为对应的属性值
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);

      // 解析<include>的子节点<property>，并将解析结果与 variablesContext 融合，然后返回融合后的 Properties。
      // 若 <property> 节点的 value 属性中存在占位符 ${}，则将占位符替换为对应的属性值
      Properties toIncludeContext = getVariablesContext(source, variablesContext);

      /*
       * 这里是一个递归调用，用于将 <sql> 节点内容中出现的属性占位符 ${}
       * 替换为对应的属性值。这里要注意一下递归调用的参数：
       *
       * - toInclude：<sql> 节点对象
       * - toIncludeContext：<include> 子节点 <property> 的解析结果与全局变量融合后的结果
       */
      applyIncludes(toInclude, toIncludeContext, true);

      // 如果 <sql> 和 <include> 节点不在一个文档中，则从其他文档中将 <sql> 节点引入到 <include> 所在文档中
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }

      // 将 <include> 节点替换为 <sql> 节点
      source.getParentNode().replaceChild(toInclude, source);
      while (toInclude.hasChildNodes()) {
        // 将 <sql> 中的内容插入到 <sql> 节点之前
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }

      // 前面已经将 <sql> 节点的内容插入到 dom 中了，现在不需要 <sql> 节点了，这里将该节点从 dom 中移除
      toInclude.getParentNode().removeChild(toInclude);

    } else if (source.getNodeType() == Node.ELEMENT_NODE) {

      // ⭐ 第二个条件分支

      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          // 将 source 节点属性中的占位符 ${} 替换成具体的属性值
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }

      // 获取 <select> 子节点列表
      NodeList children = source.getChildNodes();
      //  遍历子节点列表，将子节点作为参数，进行递归调用
      for (int i = 0; i < children.getLength(); i++) {
        // 递归调用
        applyIncludes(children.item(i), variablesContext, included);
      }

    } else if (included && source.getNodeType() == Node.TEXT_NODE && !variablesContext.isEmpty()) {

      // ⭐ 第三个条件分支

      // replace variables in text node
      // 将文本（text）节点中的属性占位符 ${} 替换成具体的属性值
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  private Node findSqlFragment(String refid, Properties variables) {
    refid = PropertyParser.parse(refid, variables);
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * Read placeholders and their values from include node definition. 
   * @param node Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    Map<String, String> declaredProperties = null;
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        String name = getStringAttribute(n, "name");
        // Replace variables inside
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          declaredProperties = new HashMap<String, String>();
        }
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
