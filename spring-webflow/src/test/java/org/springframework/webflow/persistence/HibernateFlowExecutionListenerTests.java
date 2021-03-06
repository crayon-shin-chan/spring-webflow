/*
 * Copyright 2004-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.webflow.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.webflow.engine.EndState;
import org.springframework.webflow.execution.FlowExecutionException;
import org.springframework.webflow.test.MockFlowSession;
import org.springframework.webflow.test.MockRequestContext;

/**
 * Tests for {@link HibernateFlowExecutionListener}
 *
 * @author Ben Hale
 */
public class HibernateFlowExecutionListenerTests {

	private HibernateHandler hibernate;

	private JdbcTemplate jdbcTemplate;

	private HibernateFlowExecutionListener hibernateListener;

	@BeforeEach
	public void setUp() throws Exception {
		DataSource dataSource = getDataSource();
		populateDataBase(dataSource);
		jdbcTemplate = new JdbcTemplate(dataSource);
		hibernate = HibernateHandlerFactory.create(dataSource);
		hibernateListener = new HibernateFlowExecutionListener(hibernate.getSessionFactory(), hibernate.getTransactionManager());
	}

	@Test
	public void testSameSession() {
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		hibernateListener.sessionStarting(context, flowSession, null);
		context.setActiveSession(flowSession);
		assertSessionBound();

		// Session created and bound to conversation
		final Session hibSession = (Session) flowSession.getScope().get("persistenceContext");
		assertNotNull(hibSession, "Should have been populated");
		hibernateListener.paused(context);
		assertSessionNotBound();

		// Session bound to thread local variable
		hibernateListener.resuming(context);
		assertSessionBound();

		hibernate.templateExecuteWithNativeSession(session -> assertSame(hibSession, session, "Should have been original instance"));
		hibernateListener.paused(context);
		assertSessionNotBound();
	}

	@Test
	public void testFlowNotAPersistenceContext() {
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		hibernateListener.sessionStarting(context, flowSession, null);
		assertSessionNotBound();
	}

	@Test
	public void testFlowCommitsInSingleRequest() {
		assertEquals(1, getCount(), "Table should only have one row");
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		hibernateListener.sessionStarting(context, flowSession, null);
		context.setActiveSession(flowSession);
		assertSessionBound();

		TestBean bean = new TestBean("Keith Donald");
		hibernate.templateSave(bean);
		assertEquals(1, getCount(), "Table should still only have one row");

		EndState endState = new EndState(flowSession.getDefinitionInternal(), "success");
		endState.getAttributes().put("commit", true);
		flowSession.setState(endState);

		hibernateListener.sessionEnding(context, flowSession, "success", null);
		hibernateListener.sessionEnded(context, flowSession, "success", null);
		assertEquals(2, getCount(), "Table should only have two rows");
		assertSessionNotBound();
	}

	@SuppressWarnings("ConstantConditions")
	private int getCount() {
		return jdbcTemplate.queryForObject("select count(*) from T_BEAN", Integer.class);
	}

	@Test
	public void testFlowCommitsAfterMultipleRequests() {
		assertEquals(1, getCount(), "Table should only have one row");
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		hibernateListener.sessionStarting(context, flowSession, null);
		context.setActiveSession(flowSession);
		assertSessionBound();

		TestBean bean1 = new TestBean("Keith Donald");
		hibernate.templateSave(bean1);
		assertEquals(1, getCount(), "Table should still only have one row");
		hibernateListener.paused(context);
		assertSessionNotBound();

		hibernateListener.resuming(context);
		TestBean bean2 = new TestBean("Keith Donald");
		hibernate.templateSave(bean2);
		assertEquals(1, getCount(), "Table should still only have one row");
		assertSessionBound();

		EndState endState = new EndState(flowSession.getDefinitionInternal(), "success");
		endState.getAttributes().put("commit", true);
		flowSession.setState(endState);

		hibernateListener.sessionEnding(context, flowSession, "success", null);
		hibernateListener.sessionEnded(context, flowSession, "success", null);
		assertEquals(3, getCount(), "Table should only have three rows");

		assertSessionNotBound();
	}

	@Test
	public void testCancelEndState() {
		assertEquals(1, getCount(), "Table should only have one row");
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		hibernateListener.sessionStarting(context, flowSession, null);
		context.setActiveSession(flowSession);
		assertSessionBound();

		TestBean bean = new TestBean("Keith Donald");
		hibernate.templateSave(bean);
		assertEquals(1, getCount(), "Table should still only have one row");

		EndState endState = new EndState(flowSession.getDefinitionInternal(), "cancel");
		endState.getAttributes().put("commit", false);
		flowSession.setState(endState);
		hibernateListener.sessionEnding(context, flowSession, "success", null);
		hibernateListener.sessionEnded(context, flowSession, "cancel", null);
		assertEquals(1, getCount(), "Table should only have two rows");
		assertSessionNotBound();
	}

	@Test
	public void testNoCommitAttributeSetOnEndState() {
		assertEquals(1, getCount(), "Table should only have one row");
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		hibernateListener.sessionStarting(context, flowSession, null);
		context.setActiveSession(flowSession);
		assertSessionBound();

		EndState endState = new EndState(flowSession.getDefinitionInternal(), "cancel");
		flowSession.setState(endState);

		hibernateListener.sessionEnding(context, flowSession, "success", null);
		hibernateListener.sessionEnded(context, flowSession, "cancel", null);
		assertEquals(1, getCount(), "Table should only have three rows");

		assertSessionNotBound();
	}

	@Test
	public void testExceptionThrown() {
		assertEquals(1, getCount(), "Table should only have one row");
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		hibernateListener.sessionStarting(context, flowSession, null);
		context.setActiveSession(flowSession);
		assertSessionBound();

		TestBean bean1 = new TestBean("Keith Donald");
		hibernate.templateSave(bean1);
		assertEquals(1, getCount(), "Table should still only have one row");
		hibernateListener.exceptionThrown(context, new FlowExecutionException("bla", "bla", "bla"));
		assertEquals(1, getCount(), "Table should still only have one row");
		assertSessionNotBound();

	}

	@Test
	public void testExceptionThrownWithNothingBound() {
		assertEquals(1, getCount(), "Table should only have one row");
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		assertSessionNotBound();
		hibernateListener.exceptionThrown(context, new FlowExecutionException("foo", "bar", "test"));
		assertSessionNotBound();
	}

	@Test
	public void testLazyInitializedCollection() {
		MockRequestContext context = new MockRequestContext();
		MockFlowSession flowSession = new MockFlowSession();
		flowSession.getDefinition().getAttributes().put("persistenceContext", "true");
		hibernateListener.sessionStarting(context, flowSession, null);
		context.setActiveSession(flowSession);
		assertSessionBound();

		TestBean bean = hibernate.templateGet(TestBean.class, 0L);
		assertFalse(Hibernate.isInitialized(bean.getAddresses()), "addresses should not be initialized");
		hibernateListener.paused(context);
		assertFalse(Hibernate.isInitialized(bean.getAddresses()), "addresses should not be initialized");
		Hibernate.initialize(bean.getAddresses());
		assertTrue(Hibernate.isInitialized(bean.getAddresses()), "addresses should be initialized");
	}

	private DataSource getDataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.hsqldb.jdbcDriver");
		dataSource.setUrl("jdbc:hsqldb:mem:hspcl");
		dataSource.setUsername("sa");
		dataSource.setPassword("");
		return dataSource;
	}

	private void populateDataBase(DataSource dataSource) {
		ResourceDatabasePopulator databasePopulator = new ResourceDatabasePopulator();
		databasePopulator.addScript(new ClassPathResource("test-data.sql", getClass()));
		DataSourceInitializer initializer = new DataSourceInitializer();
		initializer.setDataSource(dataSource);
		initializer.setDatabasePopulator(databasePopulator);
		initializer.afterPropertiesSet();
	}

	private void assertSessionNotBound() {
		assertNull(TransactionSynchronizationManager.getResource(hibernate.getSessionFactory()));
	}

	private void assertSessionBound() {
		assertNotNull(TransactionSynchronizationManager.getResource(hibernate.getSessionFactory()));
	}

}
