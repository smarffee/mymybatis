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
package org.apache.ibatis.session;

/**
 * Specifies if and how MyBatis should automatically map columns to fields/properties.
 *
 * 在 MyBatis 中，结果集自动映射有三种等级。这三种等级官方文档上有所说明，这里直接引用一下。如下：
 * NONE - 禁用自动映射。仅设置手动映射属性
 * PARTIAL - 将自动映射结果除了那些有内部定义内嵌结果映射的(joins)
 * FULL - 自动映射所有
 *
 * 除了以上三种等级，我们还可以显示配置<resultMap>节点的 autoMapping 属性，以启用或者禁用指定 ResultMap 的自动映射设定。
 * 
 * @author Eduardo Macarron
 */
public enum AutoMappingBehavior {

  /**
   * Disables auto-mapping.
   */
  NONE,

  /**
   * Will only auto-map results with no nested result mappings defined inside.
   */
  PARTIAL,

  /**
   * Will auto-map result mappings of any complexity (containing nested or otherwise).
   */
  FULL
}
