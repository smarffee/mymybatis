/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.ResultHandler;

/**
 * @author Clinton Begin
 *
 * 在 MyBatis 的源码中，StatementHandler 是一个非常核心接口。
 * 之所以说它核心，是因为从代码分层的角度来说，StatementHandler 是 MyBatis 源码的边界，再往下层就是 JDBC 层面的接口了。
 * StatementHandler 需要和 JDBC 层面的接口打交道，它要做的事情有很多。
 * 在执行 SQL 之前，StatementHandler 需要创建合适的 Statement 对象，然后填充参数值到Statement 对象中，最后通过 Statement 对象执行 SQL。
 * 这还不算完，待 SQL 执行完毕，还要去处理查询结果等。这
 */
public interface StatementHandler {

  //创建java.sql.Statement
  Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException;

  // 为 Statement 设置 IN 参数
  void parameterize(Statement statement) throws SQLException;

  void batch(Statement statement) throws SQLException;

  int update(Statement statement) throws SQLException;

  <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException;

  <E> Cursor<E> queryCursor(Statement statement) throws SQLException;

  BoundSql getBoundSql();

  ParameterHandler getParameterHandler();

}
