/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.test;

import org.apache.calcite.linq4j.function.Function1;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.io.PrintStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Unit test of the Calcite adapter for CSV.
 */
public class JDBCCloneAdapterTest {
	private static void close(Connection connection, Statement statement) {
		if (statement != null) {
			try {
				statement.close();
			} catch (SQLException e) {
				// ignore
			}
		}
		if (connection != null) {
			try {
				connection.close();
			} catch (SQLException e) {
				// ignore
			}
		}
	}

	public static String toLinux(String s) {
		return s.replaceAll("\r\n", "\n");
	}

	/**
	 * Tests the vanity driver.
	 */
	@Ignore
	public void testVanityDriver() throws SQLException {
		Properties info = new Properties();
		Connection connection = DriverManager.getConnection("jdbc:csv:", info);
		connection.close();
	}

	/**
	 * Tests the vanity driver with properties in the URL.
	 */
	@Ignore
	public void testVanityDriverArgsInUrl() throws SQLException {
		Connection connection = DriverManager.getConnection("jdbc:csv:"
				+ "directory='foo'");
		connection.close();
	}

	/** Tests an inline schema with a non-existent directory. */

	public void testBadDirectory() throws SQLException {
		Properties info = new Properties();
		info.put(
				"model",
				"inline:"
						+ "{\n"
						+ "  version: '1.0',\n"
						+ "   schemas: [\n"
						+ "     {\n"
						+ "       type: 'custom',\n"
						+ "       name: 'bad',\n"
						+ "       factory: 'org.apache.calcite.adapter.csv.CsvSchemaFactory',\n"
						+ "       operand: {\n"
						+ "         directory: '/does/not/exist'\n"
						+ "       }\n" + "     }\n" + "   ]\n" + "}");

		Connection connection = DriverManager.getConnection("jdbc:calcite:",
				info);
		// must print "directory ... not found" to stdout, but not fail
		ResultSet tables = connection.getMetaData().getTables(null, null, null,
				null);
		tables.next();
		tables.close();
		connection.close();
	}

	public void testSelectSingleProjectGz() throws SQLException {
		checkSql("smart", "select name from EMPS");
	}

	public void testSelectSingleProject() throws SQLException {
		checkSql("smart", "select name from DEPTS");
	}

	public void testCustomTable() throws SQLException {
		checkSql("model-with-custom-table", "select * from CUSTOM_TABLE.EMPS");
	}

	public void testPushDownProjectDumb() throws SQLException {
		// rule does not fire, because we're using 'dumb' tables in simple model
		checkSql("model", "explain plan for select * from EMPS",
				"PLAN=EnumerableInterpreter\n"
						+ "  BindableTableScan(table=[[SALES, EMPS]])\n");
	}

	public void testPushDownProject() throws SQLException {
		checkSql(
				"smart",
				"explain plan for select * from EMPS",
				"PLAN=CsvTableScan(table=[[SALES, EMPS]], fields=[[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]])\n");
	}

	public void testPushDownProject2() throws SQLException {
		checkSql("smart", "explain plan for select name, empno from EMPS",
				"PLAN=CsvTableScan(table=[[SALES, EMPS]], fields=[[1, 0]])\n");
		// make sure that it works...
		checkSql("smart", "select name, empno from EMPS",
				"NAME=Fred; EMPNO=100", "NAME=Eric; EMPNO=110",
				"NAME=John; EMPNO=110", "NAME=Wilma; EMPNO=120",
				"NAME=Alice; EMPNO=130");
	}

	public void testFilterableSelect() throws SQLException {
		checkSql("filterable-model", "select name from EMPS");
	}

	public void testFilterableSelectStar() throws SQLException {
		checkSql("filterable-model", "select * from EMPS");
	}

	/** Filter that can be fully handled by CsvFilterableTable. */

	public void testFilterableWhere() throws SQLException {
		checkSql("filterable-model",
				"select empno, gender, name from EMPS where name = 'John'",
				"EMPNO=110; GENDER=M; NAME=John");
	}

	/** Filter that can be partly handled by CsvFilterableTable. */

	public void testFilterableWhere2() throws SQLException {
		checkSql(
				"filterable-model",
				"select empno, gender, name from EMPS where gender = 'F' and empno > 125",
				"EMPNO=130; GENDER=F; NAME=Alice");
	}

	public void testJson() throws SQLException {
		checkSql("bug", "select _MAP['id'] as id,\n"
				+ " _MAP['title'] as title,\n"
				+ " CHAR_LENGTH(CAST(_MAP['title'] AS VARCHAR(30))) as len\n"
				+ " from \"archers\"",
				"ID=19990101; TITLE=Washday blues.; LEN=14",
				"ID=19990103; TITLE=Daniel creates a drama.; LEN=23");
	}

	private void checkSql(String model, String sql) throws SQLException {
		checkSql(sql, model, output());
	}

	private static Function1<ResultSet, Void> output() {
		return new Function1<ResultSet, Void>() {
			public Void apply(ResultSet resultSet) {
				try {
					output(resultSet, System.out);
				} catch (SQLException e) {
					throw new RuntimeException(e);
				}
				return null;
			}
		};
	}

	private void checkSql(String model, String sql, final String... expected)
			throws SQLException {
		checkSql(sql, model, expect(expected));
	}

	/**
	 * Returns a function that checks the contents of a result set against an
	 * expected string.
	 */
	private static Function1<ResultSet, Void> expect(final String... expected) {
		return new Function1<ResultSet, Void>() {
			public Void apply(ResultSet resultSet) {
				try {
					final List<String> lines = new ArrayList<String>();
					JDBCCloneAdapterTest.collect(lines, resultSet);
					Assert.assertEquals(Arrays.asList(expected), lines);
				} catch (SQLException e) {
					throw new RuntimeException(e);
				}
				return null;
			}
		};
	}

	private void checkSql(String sql, String model,
			Function1<ResultSet, Void> fn) throws SQLException {
		Connection connection = null;
		Statement statement = null;
		try {
			Properties info = new Properties();
			info.put("model", jsonPath(model));
			connection = DriverManager.getConnection("jdbc:calcite:", info);
			statement = connection.createStatement();
			final ResultSet resultSet = statement.executeQuery(sql);
			fn.apply(resultSet);
		} finally {
			close(connection, statement);
		}
	}

	private static String jsonPath(String model) {
		final URL url = JDBCCloneAdapterTest.class.getResource("/" + model + ".json");
		String s = url.toString();
		if (s.startsWith("file:")) {
			s = s.substring("file:".length());
		}
		return s;
	}

	private static void collect(List<String> result, ResultSet resultSet)
			throws SQLException {
		final StringBuilder buf = new StringBuilder();
		while (resultSet.next()) {
			buf.setLength(0);
			int n = resultSet.getMetaData().getColumnCount();
			String sep = "";
			for (int i = 1; i <= n; i++) {
				buf.append(sep)
						.append(resultSet.getMetaData().getColumnLabel(i))
						.append("=").append(resultSet.getString(i));
				sep = "; ";
			}
			result.add(toLinux(buf.toString()));
		}
	}

	private static void output(ResultSet resultSet, PrintStream out)
			throws SQLException {
		final ResultSetMetaData metaData = resultSet.getMetaData();
		final int columnCount = metaData.getColumnCount();
		while (resultSet.next()) {
			for (int i = 1;; i++) {
				out.print(resultSet.getString(i));
				if (i < columnCount) {
					out.print(", ");
				} else {
					out.println();
					break;
				}
			}
		}
	}

	public void testJoinOnString() throws SQLException {
		checkSql("smart",
				"select * from emps join depts on emps.name = depts.name");
	}

	public void testWackyColumns() throws SQLException {
		checkSql("select * from wacky_column_names where false", "bug",
				expect());
		checkSql(
				"select \"joined at\", \"naME\" from wacky_column_names where \"2gender\" = 'F'",
				"bug",
				expect("joined at=2005-09-07; naME=Wilma",
						"joined at=2007-01-01; naME=Alice"));
	}

	public void testBoolean() throws SQLException {
		checkSql("smart", "select empno, slacker from emps where slacker",
				"EMPNO=100; SLACKER=true");
	}

	public void testReadme() throws SQLException {
		checkSql("SELECT d.name, COUNT(*) cnt" + " FROM emps AS e"
				+ " JOIN depts AS d ON e.deptno = d.deptno"
				+ " GROUP BY d.name", "smart",
				expect("NAME=Sales; CNT=1", "NAME=Marketing; CNT=2"));
	}

	
	/**
	 * Reads from a table.
	 */
	@Test
	public void testSelect() throws SQLException {
		checkSql("mysql_model", "select count(*) from PRODUCT");
	}
	public static void main(String[] args) throws SQLException {
		Connection connection = null;
		Statement statement = null;
		try {
			Properties info = new Properties();
			info.put("model", jsonPath("mysql_model"));
			connection = DriverManager.getConnection("jdbc:calcite:", info);
			statement = connection.createStatement();
			final ResultSet resultSet = statement.executeQuery("select * from agg_c_10_sales_fact_1997");
			Function1<ResultSet, Void> fn = output();
			fn.apply(resultSet);
		} finally {
			close(connection, statement);
		}
	}
}

// End CsvTest.java
