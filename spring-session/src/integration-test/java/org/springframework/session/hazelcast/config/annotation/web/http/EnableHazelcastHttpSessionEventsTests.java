/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.session.hazelcast.config.annotation.web.http;

import static org.assertj.core.api.Assertions.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.ExpiringSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.SessionEventRegistry;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.session.hazelcast.HazelcastITestUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import com.hazelcast.core.HazelcastInstance;

/**
 * Ensure that the appropriate SessionEvents are fired at the expected times.
 * Additionally ensure that the interactions with the {@link SessionRepository}
 * abstraction behave as expected after each SessionEvent.
 *
 * @author Tommy Ludwig
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
@WebAppConfiguration
public class EnableHazelcastHttpSessionEventsTests<S extends ExpiringSession> {

	private final static int MAX_INACTIVE_INTERVAL_IN_SECONDS = 1;

	@Autowired
	private SessionRepository<S> repository;

	@Autowired
	private SessionEventRegistry registry;

	@Before
	public void setup() {
		registry.clear();
	}

	@Test
	public void saveSessionTest() throws InterruptedException {
		String username = "saves-"+System.currentTimeMillis();

		S sessionToSave = repository.createSession();

		String expectedAttributeName = "a";
		String expectedAttributeValue = "b";
		sessionToSave.setAttribute(expectedAttributeName, expectedAttributeValue);
		Authentication toSaveToken = new UsernamePasswordAuthenticationToken(username,"password", AuthorityUtils.createAuthorityList("ROLE_USER"));
		SecurityContext toSaveContext = SecurityContextHolder.createEmptyContext();
		toSaveContext.setAuthentication(toSaveToken);
		sessionToSave.setAttribute("SPRING_SECURITY_CONTEXT", toSaveContext);
		sessionToSave.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, username);

		repository.save(sessionToSave);

		assertThat(registry.receivedEvent(sessionToSave.getId())).isTrue();
		assertThat(registry.getEvent(sessionToSave.getId())).isInstanceOf(SessionCreatedEvent.class);

		Session session = repository.getSession(sessionToSave.getId());

		assertThat(session.getId()).isEqualTo(sessionToSave.getId());
		assertThat(session.getAttributeNames()).isEqualTo(sessionToSave.getAttributeNames());
		assertThat(session.getAttribute(expectedAttributeName)).isEqualTo(sessionToSave.getAttribute(expectedAttributeName));
	}

	@Test
	public void expiredSessionTest() throws InterruptedException {
		S sessionToSave = repository.createSession();

		repository.save(sessionToSave);

		assertThat(registry.receivedEvent(sessionToSave.getId())).isTrue();
		assertThat(registry.getEvent(sessionToSave.getId())).isInstanceOf(SessionCreatedEvent.class);
		registry.clear();

		assertThat(sessionToSave.getMaxInactiveIntervalInSeconds()).isEqualTo(MAX_INACTIVE_INTERVAL_IN_SECONDS);

		assertThat(registry.receivedEvent(sessionToSave.getId())).isTrue();
		assertThat(registry.getEvent(sessionToSave.getId())).isInstanceOf(SessionExpiredEvent.class);

		assertThat(repository.getSession(sessionToSave.getId())).isNull();
	}

	@Test
	public void deletedSessionTest() throws InterruptedException {
		S sessionToSave = repository.createSession();

		repository.save(sessionToSave);

		assertThat(registry.receivedEvent(sessionToSave.getId())).isTrue();
		assertThat(registry.getEvent(sessionToSave.getId())).isInstanceOf(SessionCreatedEvent.class);
		registry.clear();

		repository.delete(sessionToSave.getId());

		assertThat(registry.receivedEvent(sessionToSave.getId())).isTrue();
		assertThat(registry.getEvent(sessionToSave.getId())).isInstanceOf(SessionDeletedEvent.class);

		assertThat(repository.getSession(sessionToSave.getId())).isNull();
	}

	@Test
	public void saveUpdatesTimeToLiveTest() throws InterruptedException {
		Object lock = new Object();

		S sessionToSave = repository.createSession();

		repository.save(sessionToSave);

		synchronized (lock) {
			lock.wait((sessionToSave.getMaxInactiveIntervalInSeconds() * 1000) - 500);
		}

		// Get and save the session like SessionRepositoryFilter would.
		S sessionToUpdate = repository.getSession(sessionToSave.getId());
		sessionToUpdate.setLastAccessedTime(System.currentTimeMillis());
		repository.save(sessionToUpdate);

		synchronized (lock) {
			lock.wait((sessionToUpdate.getMaxInactiveIntervalInSeconds() * 1000) - 100);
		}

		assertThat(repository.getSession(sessionToUpdate.getId())).isNotNull();
	}

	@Configuration
	@EnableHazelcastHttpSession(maxInactiveIntervalInSeconds = MAX_INACTIVE_INTERVAL_IN_SECONDS)
	static class HazelcastSessionConfig {

		@Bean
		public HazelcastInstance embeddedHazelcast() {
			return HazelcastITestUtils.embeddedHazelcastServer();
		}

		@Bean
		public SessionEventRegistry sessionEventRegistry() {
			return new SessionEventRegistry();
		}
	}

}
