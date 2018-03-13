package com.aws.sample.amazonmqintegration.plainjava;

import java.util.HashMap;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.TopicSubscriber;

import org.apache.activemq.ActiveMQSslConnectionFactory;

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathResult;
import com.amazonaws.services.simplesystemsmanagement.model.Parameter;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;

public class PublishSubscribeRequestResponseTraditional {

    private static final String SERVICE_CONFIGURATION_PREFIX = "/PROD/INTEGRATION-APP";

    private static final String AMAZON_MQ_CONFIGURATION = SERVICE_CONFIGURATION_PREFIX + "/BROKER";
    private static final String BROKER_USER = AMAZON_MQ_CONFIGURATION + "/USER";
    private static final String BROKER_PASSWORD = AMAZON_MQ_CONFIGURATION + "/PASSWORD";
    private static final String BROKER_ENDPOINT = AMAZON_MQ_CONFIGURATION + "/ENDPOINT/OPEN-WIRE";
    private static final String BROKER_TOPIC = AMAZON_MQ_CONFIGURATION + "/TOPIC/PUBLISH-SUBSCRIBE-REQUEST-RESPONSE-TRADITIONAL";

    private static final String SNS_CONFIGURATION = SERVICE_CONFIGURATION_PREFIX + "/SNS";
    private static final String SNS_ENDPOINT = SNS_CONFIGURATION + "/ENDPOINT/PUBLISH-SUBSCRIBE-REQUEST-RESPONSE-TRADITIONAL";

    private static final String SQS_CONFIGURATION = SERVICE_CONFIGURATION_PREFIX + "/SQS";
    private static final String SQS_ENDPOINT_RESPONSE = SQS_CONFIGURATION + "/ENDPOINT/PUBLISH-SUBSCRIBE-REQUEST-RESPONSE-TRADITIONAL-RESPONSE";

    public static void main(String... args) throws Exception {
        // we are using AWS Simple Systems Management Parameter Store to store our configuration in a central and secure place
        final Map<String, String> conf = lookupServiceConfiguration();

        final AmazonSQS sqsClient = AmazonSQSClientBuilder.standard().build();

        final AmazonSNS snsClient = AmazonSNSClientBuilder.standard().build();

        ActiveMQSslConnectionFactory connFact = new ActiveMQSslConnectionFactory(conf.get(BROKER_ENDPOINT));
        connFact.setConnectResponseTimeout(10000);
        Connection conn = connFact.createConnection(conf.get(BROKER_USER), conf.get(BROKER_PASSWORD));
        conn.setClientID("PublishSubscribeRequestResponseTraditionalProxy");
        conn.start();
        Session session = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE);

        TopicSubscriber consumer = session.createDurableSubscriber(session.createTopic(conf.get(BROKER_TOPIC)), "PublishSubscribeRequestResponseTraditionalDurableSubscriber");
        consumer.setMessageListener(new MessageListener() {
            public void onMessage(Message message) {
                try {
                    if (message instanceof TextMessage) {
                        TextMessage msg = (TextMessage) message;

                        System.out.println("received message with correlation id: " + msg.getJMSCorrelationID());

                        snsClient.publish(new PublishRequest()
                            .withTopicArn(conf.get(SNS_ENDPOINT))
                            .withMessage(msg.getText())
                            .addMessageAttributesEntry("JMSCorrelationID", new com.amazonaws.services.sns.model.MessageAttributeValue().withDataType("String").withStringValue(msg.getJMSCorrelationID()))
                            .addMessageAttributesEntry("JMSReplyTo", new com.amazonaws.services.sns.model.MessageAttributeValue().withDataType("String").withStringValue(msg.getJMSReplyTo().toString())));

                        msg.acknowledge();
                        System.out.println("forwarded message with correlation id: " + msg.getJMSCorrelationID());
                    } else {
                        throw new RuntimeException(String.format("Unknown message type '%s'", message));
                    }
                } catch (JMSException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        while (true) {
            ReceiveMessageResult receiveMessageResult = sqsClient.receiveMessage(new ReceiveMessageRequest()
                .withQueueUrl(conf.get(SQS_ENDPOINT_RESPONSE))
                .withAttributeNames("All")
                .withMessageAttributeNames("All")
                .withWaitTimeSeconds(20));

            for (com.amazonaws.services.sqs.model.Message msg : receiveMessageResult.getMessages()) {
                System.out.println("received message with message id: " + msg.getMessageId());

                TextMessage message = session.createTextMessage(msg.getBody());
                message.setJMSMessageID(msg.getMessageId());
                if (msg.getMessageAttributes().get("JMSCorrelationID") != null) {
                    message.setJMSCorrelationID(msg.getMessageAttributes().get("JMSCorrelationID").getStringValue());
                }

                MessageProducer messageProducer = session.createProducer(session.createQueue(msg.getMessageAttributes().get("JMSReplyTo").getStringValue()));
                messageProducer.send(message);
                messageProducer.close();
    
                System.out.println("forwarded message with message id: " + msg.getMessageId());

                sqsClient.deleteMessage(new DeleteMessageRequest()
                    .withQueueUrl(conf.get(SQS_ENDPOINT_RESPONSE))
                    .withReceiptHandle(msg.getReceiptHandle()));
            }
        }
    }

    private static Map<String, String> lookupServiceConfiguration() {
        Map<String, String> serviceConfiguration = new HashMap<>();
        lookupServiceConfiguration(AMAZON_MQ_CONFIGURATION, serviceConfiguration);
        lookupServiceConfiguration(SNS_CONFIGURATION, serviceConfiguration);
        lookupServiceConfiguration(SQS_CONFIGURATION, serviceConfiguration);

        return serviceConfiguration;
    }

    private static Map<String, String> lookupServiceConfiguration(String configurationPrefix, Map<String, String> serviceConfiguration) {
        // using automatic region detection as described here: https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/java-dg-region-selection.html
        AWSSimpleSystemsManagement ssmClient = AWSSimpleSystemsManagementClientBuilder.standard().build();
        GetParametersByPathResult result = ssmClient.getParametersByPath(
            new GetParametersByPathRequest()
                .withMaxResults(Integer.valueOf(10))
                .withRecursive(Boolean.TRUE)
                .withPath(configurationPrefix));

        for (Parameter parameter : result.getParameters()) {
            String key = parameter.getName();
            String value = parameter.getValue();
            if (parameter.getType().equals("SecureString")) {
                value = decrypt(ssmClient, key, value);
            }
            serviceConfiguration.put(key, value);
        }

        return serviceConfiguration;
    }

    private static String decrypt(AWSSimpleSystemsManagement ssmClient, String key, String value) {
        return ssmClient.getParameter(
            new GetParameterRequest()
                .withName(key)
                .withWithDecryption(Boolean.TRUE))
            .getParameter().getValue();
    }
}