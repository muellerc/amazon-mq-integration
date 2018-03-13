package com.aws.sample.amazonmqintegration.lambda;

import java.util.HashMap;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQSslConnectionFactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathResult;
import com.amazonaws.services.simplesystemsmanagement.model.Parameter;

public class PublishSubscribeRequestResponseCloudNative implements RequestHandler<SNSEvent, Void> {

    private static final String SERVICE_CONFIGURATION_PREFIX = "/PROD/INTEGRATION-APP";

    private static final String AMAZON_MQ_CONFIGURATION = SERVICE_CONFIGURATION_PREFIX + "/BROKER";
    private static final String BROKER_USER = AMAZON_MQ_CONFIGURATION + "/USER";
    private static final String BROKER_PASSWORD = AMAZON_MQ_CONFIGURATION + "/PASSWORD";
    private static final String BROKER_ENDPOINT = AMAZON_MQ_CONFIGURATION + "/ENDPOINT/OPEN-WIRE";
    private static final String BROKER_TOPIC = AMAZON_MQ_CONFIGURATION + "/TOPIC/PUBLISH-SUBSCRIBE-REQUEST-RESPONSE-CLOUD-NATIVE";

    private MessageProducer messageProducer;
    private Session session;

    public PublishSubscribeRequestResponseCloudNative() throws JMSException {
        // we are using AWS Simple Systems Management Parameter Store to store our configuration in a central and secure place
        final Map<String, String> conf = lookupServiceConfiguration();

        ActiveMQSslConnectionFactory connFact = new ActiveMQSslConnectionFactory(conf.get(BROKER_ENDPOINT));
        connFact.setConnectResponseTimeout(10000);
        Connection conn = connFact.createConnection(conf.get(BROKER_USER), conf.get(BROKER_PASSWORD));
        conn.setClientID("PublishSubscribeRequestResponseCloudNativeRequestProxy");
        conn.start();
        Session session = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE);

        messageProducer = session.createProducer(session.createTopic(conf.get(BROKER_TOPIC)));
    }

    @Override
    public Void handleRequest(SNSEvent request, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log(String.format("received request: %s\n", request));

        try {
            for (SNSEvent.SNSRecord record: request.getRecords()) {
                SNS sns = record.getSNS();
                TextMessage message = session.createTextMessage(sns.getMessage());
                message.setJMSMessageID(sns.getMessageId());
                message.setJMSCorrelationID(sns.getMessageAttributes().get("JMSCorrelationID").getValue());
                message.setJMSReplyTo(session.createQueue(sns.getMessageAttributes().get("JMSReplyTo").getValue()));
                messageProducer.send(message);
    
                logger.log("forwarded message with correlation id: " + sns.getMessageAttributes().get("JMSCorrelationID").getValue());
            }
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    private static Map<String, String> lookupServiceConfiguration() {
        Map<String, String> serviceConfiguration = new HashMap<>();
        lookupServiceConfiguration(AMAZON_MQ_CONFIGURATION, serviceConfiguration);
        lookupServiceConfiguration(AMAZON_MQ_CONFIGURATION + "/ENDPOINT", serviceConfiguration);
        lookupServiceConfiguration(AMAZON_MQ_CONFIGURATION + "/TOPIC", serviceConfiguration);

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