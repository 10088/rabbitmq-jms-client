// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2018-2020 VMware, Inc. or its affiliates. All rights reserved.
package com.rabbitmq.integration.tests;

import com.rabbitmq.jms.admin.RMQDestination;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 */
public class RpcWithAmqpDirectReplyIT {

    private static final String QUEUE_NAME = "test.queue." + RpcWithAmqpDirectReplyIT.class.getCanonicalName();
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcWithAmqpDirectReplyIT.class);

    Connection serverConnection, clientConnection;

    RpcServer rpcServer;

    protected static void drainQueue(Session session, Queue queue) throws Exception {
        MessageConsumer receiver = session.createConsumer(queue);
        Message msg = receiver.receiveNoWait();
        while (msg != null) {
            msg = receiver.receiveNoWait();
        }
    }

    @BeforeEach
    public void init() throws Exception {
        ConnectionFactory connectionFactory = AbstractTestConnectionFactory.getTestConnectionFactory()
            .getConnectionFactory();
        clientConnection = connectionFactory.createConnection();
        clientConnection.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        rpcServer.close();
        if (clientConnection != null) {
            clientConnection.close();
        }
        if (serverConnection != null) {
            serverConnection.close();
        }
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        try (com.rabbitmq.client.Connection c = cf.newConnection()) {
            c.createChannel().queueDelete(QUEUE_NAME);
        }
    }

    @Test
    public void responseOkWhenServerDoesNotRecreateTemporaryResponseQueue() throws Exception {
        setupRpcServer();

        String messageContent = UUID.randomUUID().toString();
        Message response = doRpc(messageContent);
        assertThat(response).isNotNull().isInstanceOf(TextMessage.class);
        assertThat(response.getJMSCorrelationID()).isEqualTo(messageContent);
        assertThat(((TextMessage) response).getText()).isEqualTo("*** " + messageContent + " ***");
    }

    Message doRpc(String messageContent) throws Exception {
        Session session = clientConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        TextMessage message = session.createTextMessage(messageContent);
        message.setJMSCorrelationID(messageContent);

        RMQDestination replyQueue = new RMQDestination(
            "amq.rabbitmq.reply-to", "", "amq.rabbitmq.reply-to", "amq.rabbitmq.reply-to"
        );
        replyQueue.setDeclared(true);
        MessageProducer producer = session.createProducer(session.createQueue(QUEUE_NAME));

        MessageConsumer responseConsumer = session.createConsumer(replyQueue);
        BlockingQueue<Message> queue = new ArrayBlockingQueue<>(1);
        responseConsumer.setMessageListener(msg -> queue.add(msg));

        message.setJMSReplyTo(replyQueue);
        producer.send(message);
        Message response = queue.poll(2, TimeUnit.SECONDS);
        responseConsumer.close();
        return response;
    }

    void setupRpcServer() throws Exception {
        ConnectionFactory connectionFactory = AbstractTestConnectionFactory.getTestConnectionFactory()
            .getConnectionFactory();
        serverConnection = connectionFactory.createConnection();
        serverConnection.start();
        Session session = serverConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue(QUEUE_NAME);
        drainQueue(session, queue);
        session.close();
        rpcServer = new RpcServer(serverConnection);
    }

    private static class RpcServer {

        Session session;

        public RpcServer(Connection connection) throws JMSException {
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createQueue(QUEUE_NAME);
            MessageProducer replyProducer = session.createProducer(null);
            MessageConsumer consumer = session.createConsumer(destination);
            consumer.setMessageListener(msg -> {
                TextMessage message = (TextMessage) msg;
                try {
                    String text = message.getText();
                    Destination replyQueue = message.getJMSReplyTo();
                    if (replyQueue != null) {
                        TextMessage replyMessage = session.createTextMessage("*** " + text + " ***");
                        replyMessage.setJMSCorrelationID(message.getJMSCorrelationID());
                        replyMessage.setStringProperty("JMSType", "TextMessage");
                        replyProducer.send(replyQueue, replyMessage);
                    }
                } catch (JMSException e) {
                    LOGGER.warn("Error in RPC server", e);
                }
            });
        }

        void close() {
            try {
                session.close();
            } catch (Exception e) {

            }
        }
    }
}
