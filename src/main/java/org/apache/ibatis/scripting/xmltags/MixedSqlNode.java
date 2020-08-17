/**
 * Copyright 2009-2017 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.scripting.xmltags;

import java.util.List;

/**
 * @author Clinton Begin
 *
 *
 * 内部维护了一个 SqlNode 集合，用于存储各种各样的 SqlNode
 *
 * MixedSqlNode 可以看做是 SqlNode 实现类对象的容器，凡是实现了 SqlNode 接口的类都可以存储到 MixedSqlNode 中，包括它自己。
 */
public class MixedSqlNode implements SqlNode {

    private final List<SqlNode> contents;

    public MixedSqlNode(List<SqlNode> contents) {
        this.contents = contents;
    }

    // 解析 SQL ⽚段
    @Override
    public boolean apply(DynamicContext context) {
        // 遍历 SqlNode 集合
        for (SqlNode sqlNode : contents) {
          // 调用 sqlNode 对象本身的 apply 方法解析 sql
          sqlNode.apply(context);
        }

        return true;
    }
}
