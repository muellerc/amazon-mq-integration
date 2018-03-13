package com.aws.sample.amazonmqintegration.plainjava;

import java.util.HashMap;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQSslConnectionFactory;

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathResult;
import com.amazonaws.services.simplesystemsmanagement.model.Parameter;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;

public class PointToPointOneWayTraditional {

    private static final String SERVICE_CONFIGURATION_PREFIX = "/PROD/INTEGRATION-APP";

    private static final String AMAZON_MQ_CONFIGURATION = SERVICE_CONFIGURATION_PREFIX + "/BROKER";
    private static final String BROKER_USER = AMAZON_MQ_CONFIGURATION + "/USER";
    private static final String BROKER_PASSWORD = AMAZON_MQ_CONFIGURATION + "/PASSWORD";
    private static final String BROKER_ENDPOINT = AMAZON_MQ_CONFIGURATION + "/ENDPOINT/OPEN-WIRE";
    private static final String BROKER_QUEUE = AMAZON_MQ_CONFIGURATION + "/QUEUE/POINT-TO-POINT-ONE-WAY-TRADITIONAL";

    private static final String SQS_CONFIGURATION = SERVICE_CONFIGURATION_PREFIX + "/SQS";
    private static final String SQS_ENDPOINT = SQS_CONFIGURATION + "/ENDPOINT/POINT-TO-POINT-ONE-WAY-TRADITIONAL";

    public static void main(String... args) throws Exception {
        // we are using AWS Simple Systems Management Parameter Store to store our configuration in a central and secure place
        final Map<String, String> conf = lookupServiceConfiguration();

        final AmazonSQS sqsClient = AmazonSQSClientBuilder.standard().build();

        ActiveMQSslConnectionFactory connFact = new ActiveMQSslConnectionFactory(conf.get(BROKER_ENDPOINT));
        connFact.setConnectResponseTimeout(10000);
        Connection conn = connFact.createConnection(conf.get(BROKER_USER), conf.get(BROKER_PASSWORD));
        conn.setClientID("PointToPointOneWayTraditionalProxy");
        conn.start();
        Session session = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE);

        MessageConsumer consumer = session.createConsumer(session.createQueue(conf.get(BROKER_QUEUE)));
        consumer.setMessageListener(new MessageListener() {
            public void onMessage(Message message) {
                try {
                    if (message instanceof TextMessage) {
                        TextMessage msg = (TextMessage) message;

                        System.out.println("received message with correlation id: " + msg.getJMSCorrelationID());

                        sqsClient.sendMessage(
                            new SendMessageRequest()
                                .withQueueUrl(conf.get(SQS_ENDPOINT))
                                .withMessageBody(msg.getText())
                                .addMessageAttributesEntry("JMSCorrelationID", new MessageAttributeValue().withDataType("String").withStringValue(msg.getJMSCorrelationID())));

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
    }

    private static Map<String, String> lookupServiceConfiguration() {
        Map<String, String> serviceConfiguration = new HashMap<>();
        lookupServiceConfiguration(AMAZON_MQ_CONFIGURATION, serviceConfiguration);
        lookupServiceConfiguration(AMAZON_MQ_CONFIGURATION + "/ENDPOINT", serviceConfiguration);
        lookupServiceConfiguration(AMAZON_MQ_CONFIGURATION + "/QUEUE", serviceConfiguration);
        lookupServiceConfiguration(SQS_CONFIGURATION + "/ENDPOINT", serviceConfiguration);

        return serviceConfiguration;
    }

    private static Map<String, String> lookupServiceConfiguration(String configurationPrefix, Map<String, String> serviceConfiguration) {
        // using automatic region detection as described here: https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/java-dg-region-selection.html
        AWSSimpleSystemsManagement ssmClient = AWSSimpleSystemsManagementClientBuilder.standard().build();
        GetParametersByPathResult result = ssmClient.getParametersByPath(
            new GetParametersByPathRequest()
                .withMaxResults(Integer.valueOf(10))
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