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

/**
 * @author Clinton Begin
 *
 * 用于存储<if>节点的内容
 *
 * IfSqlNode 对应的是<iftest='xxx'>节点
 */
public class IfSqlNode implements SqlNode {
    private final ExpressionEvaluator evaluator;
    private final String test;
    private final SqlNode contents;

    public IfSqlNode(SqlNode contents, String test) {
        this.test = test;
        this.contents = contents;
        this.evaluator = new ExpressionEvaluator();
    }

    @Override
    public boolean apply(DynamicContext context) {
        // 通过 OGNL 评估 test 表达式的结果
        if (evaluator.evaluateBoolean(test, context.getBindings())) {
            // 若 test 表达式中的条件成立，则调用其他节点的 apply 方法进行解析
            // 需要注意的是<if>节点中也可嵌套其他的动态节点，并非只有纯文本。
            // 因此 contents 变量遍历指向的是 MixedSqlNode，而非 StaticTextSqlNode。
            contents.apply(context);
            return true;
        }
        return false;
    }

}
