/*
 * Copyright 2014 JBoss Inc
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
package org.overlord.sramp.test.events.jms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.naming.Context;
import javax.naming.InitialContext;

import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;
import org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactEnum;
import org.oasis_open.docs.s_ramp.ns.s_ramp_v1.ExtendedArtifactType;
import org.overlord.sramp.client.SrampAtomApiClient;
import org.overlord.sramp.common.ArtifactType;
import org.overlord.sramp.events.ArtifactUpdateEvent;
import org.overlord.sramp.test.AbstractIntegrationTest;

/**
 * @author Brett Meyer
 *
 */
public class JMSEventProducerTest extends AbstractIntegrationTest {
    
    private static final String EAP_INITIAL_CONTEXT_FACTORY = "org.jboss.naming.remote.client.InitialContextFactory";
    private static final String EAP_PROVIDER_URL = "remote://localhost:4447";
    private static final String EAP_CONNECTIONFACTORY_JNDI = "jms/RemoteConnectionFactory";
    private static final String EAP_TOPIC_JNDI = "jms/sramp/events/topic";
    
    private static final String TOMCAT_INITIAL_CONTEXT_FACTORY = "org.apache.activemq.jndi.ActiveMQInitialContextFactory";
    private static final String TOMCAT_PROVIDER_URL = "tcp://localhost:61616";
    private static final String TOMCAT_CONNECTIONFACTORY_JNDI = "ConnectionFactory";
    private static final String TOMCAT_TOPIC_JNDI = "sramp/events/topic";
    
    private List<TextMessage> textMessages;
    
    @Test
    public void testBasicTopic() throws Exception {
        textMessages = new ArrayList<TextMessage>();
        
        // 3 == create, update, delete
        final CountDownLatch lock = new CountDownLatch(3);
        
        Connection connection = subscribe(lock);
        
        // create
        SrampAtomApiClient client = client();
        ExtendedArtifactType artifact = new ExtendedArtifactType();
        artifact.setArtifactType(BaseArtifactEnum.EXTENDED_ARTIFACT_TYPE);
        artifact.setExtendedType("FooArtifactType"); //$NON-NLS-1$
        artifact.setName("Foo"); //$NON-NLS-1$
        artifact.setDescription("created"); //$NON-NLS-1$
        ExtendedArtifactType persistedArtifact = (ExtendedArtifactType) client.createArtifact(artifact);
        
        // update
        persistedArtifact.setDescription("updated");
        client.updateArtifactMetaData(persistedArtifact);
        
        // delete
        client.deleteArtifact(persistedArtifact.getUuid(), ArtifactType.valueOf(artifact));
        
        lock.await(10000, TimeUnit.MILLISECONDS);
        
        assertEquals(3, textMessages.size());
        
        ObjectMapper mapper = new ObjectMapper();
        
        // sramp:artifactCreated
        TextMessage textMessage = textMessages.get(0);
        assertNotNull(textMessage);
        assertEquals("sramp:artifactCreated", textMessage.getJMSType());
        assertTrue(textMessage.getText() != null && textMessage.getText().length() > 0);
        ExtendedArtifactType eventArtifact = mapper.readValue(textMessage.getText(), ExtendedArtifactType.class);
        assertNotNull(eventArtifact);
        assertEquals(artifact.getExtendedType(), eventArtifact.getExtendedType());
        assertEquals(artifact.getName(), eventArtifact.getName());
        assertEquals(artifact.getDescription(), eventArtifact.getDescription());
        
        // sramp:artifactUpdated
        textMessage = textMessages.get(1);
        assertNotNull(textMessage);
        assertEquals("sramp:artifactUpdated", textMessage.getJMSType());
        assertTrue(textMessage.getText() != null && textMessage.getText().length() > 0);
        ArtifactUpdateEvent updateEvent = mapper.readValue(textMessage.getText(), ArtifactUpdateEvent.class);
        assertNotNull(updateEvent);
        assertNotNull(updateEvent.getOldArtifact());
        assertNotNull(updateEvent.getUpdatedArtifact());
        assertEquals(artifact.getExtendedType(), ((ExtendedArtifactType) updateEvent.getOldArtifact()).getExtendedType());
        assertEquals(artifact.getName(), updateEvent.getOldArtifact().getName());
        assertEquals(artifact.getDescription(), updateEvent.getOldArtifact().getDescription());
        assertEquals(artifact.getExtendedType(), ((ExtendedArtifactType) updateEvent.getUpdatedArtifact()).getExtendedType());
        assertEquals(artifact.getName(), updateEvent.getUpdatedArtifact().getName());
        assertEquals(persistedArtifact.getDescription(), updateEvent.getUpdatedArtifact().getDescription());
        
        // sramp:artifactDeleted
        textMessage = textMessages.get(2);
        assertNotNull(textMessage);
        assertEquals("sramp:artifactDeleted", textMessage.getJMSType());
        assertTrue(textMessage.getText() != null && textMessage.getText().length() > 0);
        eventArtifact = mapper.readValue(textMessage.getText(), ExtendedArtifactType.class);
        assertNotNull(eventArtifact);
        assertEquals(artifact.getExtendedType(), eventArtifact.getExtendedType());
        assertEquals(artifact.getName(), eventArtifact.getName());
        assertEquals(persistedArtifact.getDescription(), eventArtifact.getDescription());
        
        connection.close();
    }
    
    private Connection subscribe(final CountDownLatch lock) {
        try {
            return subscribe(EAP_INITIAL_CONTEXT_FACTORY, EAP_PROVIDER_URL, EAP_CONNECTIONFACTORY_JNDI, EAP_TOPIC_JNDI, lock);
        } catch (Exception e) {
//          e.printStackTrace();
        }
        try {
            return subscribe(TOMCAT_INITIAL_CONTEXT_FACTORY, TOMCAT_PROVIDER_URL, TOMCAT_CONNECTIONFACTORY_JNDI,
                    TOMCAT_TOPIC_JNDI, lock);
        } catch (Exception e) {
//            e.printStackTrace();
        }
        fail("Could not create a JMS client.");
        return null;
    }
    
    private Connection subscribe(String contextFactoryName, String providerUrl, String connectionFactoryName,
            String topicName, final CountDownLatch lock) throws Exception {
        final Properties env = new Properties();
        env.put(Context.INITIAL_CONTEXT_FACTORY, contextFactoryName);
        env.put(Context.PROVIDER_URL, providerUrl);
        env.put(Context.SECURITY_PRINCIPAL, USERNAME);
        env.put(Context.SECURITY_CREDENTIALS, PASSWORD);
        Context context = new InitialContext(env);
        
        ConnectionFactory connectionFactory = (ConnectionFactory) context.lookup(connectionFactoryName);
        Connection connection = connectionFactory.createConnection(USERNAME, PASSWORD);
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = (Topic) context.lookup(topicName);
        MessageConsumer topicSubscriber = session.createConsumer(topic);
        topicSubscriber.setMessageListener(new MessageListener() {
            @Override
            public void onMessage(Message message) {
                try {
                    textMessages.add((TextMessage) message);
                    lock.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        connection.start();
        return connection;
    }
}
