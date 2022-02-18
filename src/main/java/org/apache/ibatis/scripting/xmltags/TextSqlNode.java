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
package org.apache.ibatis.scripting.xmltags;

import java.util.regex.Pattern;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

/**
 * @author Clinton Begin
 *
 * 用于存储带有${}占位符的文本
 */
public class TextSqlNode implements SqlNode {

    private final String text;

    private final Pattern injectionFilter;

    public TextSqlNode(String text) {
        this(text, null);
    }

    public TextSqlNode(String text, Pattern injectionFilter) {
        this.text = text;
        this.injectionFilter = injectionFilter;
    }

    public boolean isDynamic() {
        DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
        GenericTokenParser parser = createParser(checker);
        parser.parse(text);
        return checker.isDynamic();
    }

    @Override
    public boolean apply(DynamicContext context) {
        // 创建 ${} 占位符解析器
        GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
        // 解析 ${} 占位符，并将解析结果添加到 DynamicContext 中
        context.appendSql(parser.parse(text));
        return true;
    }

    // 创建 ${} 占位符解析器
    private GenericTokenParser createParser(TokenHandler handler) {
          // 创建占位符解析器，GenericTokenParser 是一个通用解析器，并非只能解析 ${} 占位符
        return new GenericTokenParser("${", "}", handler);
    }

    /**
     * BindingTokenParser 负责解析标记内容，并将解析结果返回给 GenericTokenParser，用于替换${xxx}标记。
     *
     * 我们有这样一个 SQL 语句，用于从 article 表中查询某个作者所写的文章。如下：
     *
     * SELECT * FROM article WHERE author = '${author}'
     *
     * 假设我们我们传入的 author 值为 zhangsan，那么该 SQL 最终会被解析成如下的结果：
     *
     * SELECT * FROM article WHERE author = 'zhangsan'
     *
     * 一般情况下，使用${author}接受参数都没什么问题。但是怕就怕在有人不怀好意，
     * 构建了一些恶意的参数。当用这些恶意的参数替换${author}时就会出现灾难性问题——SQL 注入。
     */
    private static class BindingTokenParser implements TokenHandler {

        private DynamicContext context;
        private Pattern injectionFilter;

        public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
            this.context = context;
            this.injectionFilter = injectionFilter;
        }

        @Override
        public String handleToken(String content) {
            Object parameter = context.getBindings().get("_parameter");

            if (parameter == null) {
                context.getBindings().put("value", null);
            } else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
                context.getBindings().put("value", parameter);
            }

            // 通过 OGNL 从用户传入的参数中获取结果
            Object value = OgnlCache.getValue(content, context.getBindings());

            String srtValue = (value == null ? "" : String.valueOf(value)); // issue #274 return "" instead of "null"

            // 通过正则表达式检测 srtValue 有效性
            checkInjection(srtValue);
            return srtValue;
        }

        private void checkInjection(String value) {
            if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
                throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
            }
        }
    }

    private static class DynamicCheckerTokenParser implements TokenHandler {

        private boolean isDynamic;

        public DynamicCheckerTokenParser() {
            // Prevent Synthetic Access
        }

        public boolean isDynamic() {
            return isDynamic;
        }

        @Override
        public String handleToken(String content) {
            this.isDynamic = true;
            return null;
        }
    }

}