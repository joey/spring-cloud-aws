/*
 * Copyright 2013-2022 the original author or authors.
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
package io.awspring.cloud.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration;
import io.awspring.cloud.sqs.config.SqsListenerCustomizer;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.ContainerOptions;
import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.QueueAttributes;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.SqsMessageListenerContainer;
import io.awspring.cloud.sqs.listener.StandardSqsComponentFactory;
import io.awspring.cloud.sqs.listener.Visibility;
import io.awspring.cloud.sqs.listener.acknowledgement.AsyncAcknowledgement;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementHandler;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler;
import io.awspring.cloud.sqs.listener.interceptor.MessageInterceptor;
import io.awspring.cloud.sqs.listener.sink.MessageSink;
import io.awspring.cloud.sqs.listener.source.MessageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Tomaz Fernandes
 * @since 3.0
 */
@SpringBootTest
class SqsIntegrationTests extends BaseSqsIntegrationTest {

	private static final Logger logger = LoggerFactory.getLogger(SqsIntegrationTests.class);

	private static final String TEST_SQS_ASYNC_CLIENT_BEAN_NAME = "testSqsAsyncClient";

	private static final String LOW_RESOURCE_FACTORY_NAME = "lowResourceFactory";

	static final String RECEIVES_MESSAGE_QUEUE_NAME = "receives_message_test_queue";
	static final String DOES_NOT_ACK_ON_ERROR_QUEUE_NAME = "does_not_ack_test_queue";
	static final String RESOLVES_PARAMETER_TYPES_QUEUE_NAME = "resolves_parameter_type_test_queue";
	static final String MANUALLY_START_CONTAINER = "manually_start_container_test_queue";
	static final String MANUALLY_CREATE_CONTAINER_QUEUE_NAME = "manually_create_container_test_queue";
	static final String MANUALLY_CREATE_FACTORY_QUEUE_NAME = "manually_create_factory_test_queue";

	@BeforeAll
	static void beforeTests() {
		SqsAsyncClient client = createAsyncClient();
		CompletableFuture.allOf(
			createQueue(client, RECEIVES_MESSAGE_QUEUE_NAME),
			createQueue(client, DOES_NOT_ACK_ON_ERROR_QUEUE_NAME, singletonMap(QueueAttributeName.VISIBILITY_TIMEOUT, "1")),
			createQueue(client, RESOLVES_PARAMETER_TYPES_QUEUE_NAME, singletonMap(QueueAttributeName.VISIBILITY_TIMEOUT, "20")),
			createQueue(client, MANUALLY_CREATE_CONTAINER_QUEUE_NAME),
			createQueue(client, MANUALLY_START_CONTAINER),
			createQueue(client, MANUALLY_CREATE_FACTORY_QUEUE_NAME)
		).join();
	}

	@Autowired
	LatchContainer latchContainer;

	@Autowired
	@Qualifier(TEST_SQS_ASYNC_CLIENT_BEAN_NAME)
	SqsAsyncClient sqsAsyncClient;

	@Autowired
	ObjectMapper objectMapper;

	private static final String TEST_PAYLOAD = "My test";

	@Test
	void receivesMessage() throws Exception {
		sendMessageTo(RECEIVES_MESSAGE_QUEUE_NAME);
		assertThat(latchContainer.receivesMessageLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.invocableHandlerMethodLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void doesNotAckOnError() throws Exception {
		sendMessageTo(DOES_NOT_ACK_ON_ERROR_QUEUE_NAME);
		assertThat(latchContainer.doesNotAckLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.errorHandlerLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void resolvesManyParameterTypes() throws Exception {
		sendMessageTo(RESOLVES_PARAMETER_TYPES_QUEUE_NAME);
		assertThat(latchContainer.manyParameterTypesLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.manyParameterTypesSecondLatch.await(1, TimeUnit.SECONDS)).isFalse();
	}

	@Test
	void manuallyCreatesContainer() throws Exception {
		sendMessageTo(MANUALLY_CREATE_CONTAINER_QUEUE_NAME, "Testing manually creates container");
		assertThat(latchContainer.manuallyCreatedContainerLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void manuallyStartsContainerAndChangesComponent() throws Exception {
		SqsMessageListenerContainer<String> container = new SqsMessageListenerContainer<>(createAsyncClient(),
				ContainerOptions.create().permitAcquireTimeout(Duration.ofSeconds(1))
						.pollTimeout(Duration.ofSeconds(3)));
		container.setQueueNames(MANUALLY_START_CONTAINER);
		container.setMessageListener(msg -> latchContainer.manuallyStartedContainerLatch.countDown());
		container.start();
		sendMessageTo(MANUALLY_START_CONTAINER, "MyTest");
		assertThat(latchContainer.manuallyStartedContainerLatch.await(2, TimeUnit.SECONDS)).isTrue();
		container.stop();
		container.setMessageListener(msg -> latchContainer.manuallyStartedContainerLatch2.countDown());
		container.start();
		sendMessageTo(MANUALLY_START_CONTAINER, "MyTest2");
		assertThat(latchContainer.manuallyStartedContainerLatch2.await(10, TimeUnit.SECONDS)).isTrue();
		container.stop();
	}

	@Test
	void manuallyCreatesFactory() throws Exception {
		sendMessageTo(MANUALLY_CREATE_FACTORY_QUEUE_NAME, "Testing manually creates factory");
		assertThat(latchContainer.manuallyCreatedFactoryLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.manuallyCreatedFactorySourceFactoryLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.manuallyCreatedFactorySinkLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	private void sendMessageTo(String receivesMessageQueueName) throws InterruptedException, ExecutionException {
		String queueUrl = fetchQueueUrl(receivesMessageQueueName);
		logger.trace("Sending message {} to {}", receivesMessageQueueName, queueUrl);
		sqsAsyncClient.sendMessage(req -> req.messageBody(TEST_PAYLOAD).queueUrl(queueUrl).build()).get();
	}

	private void sendMessageTo(String queueName, String messageBody) throws InterruptedException, ExecutionException {
		String queueUrl = fetchQueueUrl(queueName);
		sqsAsyncClient.sendMessage(req -> req.messageBody(messageBody).queueUrl(queueUrl).build()).get();
		logger.debug("Sent message to queue {} with messageBody {}", queueName, messageBody);
	}

	private String fetchQueueUrl(String receivesMessageQueueName) throws InterruptedException, ExecutionException {
		return sqsAsyncClient.getQueueUrl(req -> req.queueName(receivesMessageQueueName)).get().queueUrl();
	}

	static class ReceivesMessageListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = RECEIVES_MESSAGE_QUEUE_NAME, id = "receivesMessageContainer")
		void listen(String message) {
			logger.debug("Received message in Listener Method: " + message);
			latchContainer.receivesMessageLatch.countDown();
		}
	}

	static class DoesNotAckOnErrorListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = DOES_NOT_ACK_ON_ERROR_QUEUE_NAME, factory = LOW_RESOURCE_FACTORY_NAME, id = "does-not-ack")
		void listen(String message, @Header(SqsHeaders.SQS_QUEUE_NAME_HEADER) String queueName) {
			logger.debug("Received message {} from queue {}", message, queueName);
			latchContainer.doesNotAckLatch.countDown();
			throw new RuntimeException("Expected exception");
		}
	}

	static class ResolvesParameterTypesListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = RESOLVES_PARAMETER_TYPES_QUEUE_NAME, messageVisibilitySeconds = "1", id = "resolves-parameter")
		void listen(Message<String> message, MessageHeaders headers, Acknowledgement ack, Visibility visibility,
					QueueAttributes queueAttributes, AsyncAcknowledgement asyncAck,
					software.amazon.awssdk.services.sqs.model.Message originalMessage) throws Exception {
			Assert.notNull(headers, "Received null MessageHeaders");
			Assert.notNull(ack, "Received null Acknowledgement");
			Assert.notNull(asyncAck, "Received null AsyncAcknowledgement");
			Assert.notNull(visibility, "Received null Visibility");
			Assert.notNull(queueAttributes, "Received null QueueAttributes");
			Assert.notNull(originalMessage, "Received null software.amazon.awssdk.services.sqs.model.Message");
			Assert.notNull(message, "Received null message");
			logger.debug("Received message in Listener Method: " + message);
			Assert.notNull(queueAttributes.getQueueAttribute(QueueAttributeName.QUEUE_ARN), "QueueArn attribute not found");

			// Verify VisibilityTimeout extension
			Thread.sleep(1000);
			latchContainer.manyParameterTypesLatch.countDown();
			latchContainer.manyParameterTypesSecondLatch.countDown();
		}
	}

	static class LatchContainer {

		final CountDownLatch receivesMessageLatch = new CountDownLatch(1);
		final CountDownLatch doesNotAckLatch = new CountDownLatch(2);
		final CountDownLatch errorHandlerLatch = new CountDownLatch(2);
		final CountDownLatch interceptorLatch = new CountDownLatch(2);
		final CountDownLatch manyParameterTypesLatch = new CountDownLatch(1);
		final CountDownLatch manyParameterTypesSecondLatch = new CountDownLatch(2);
		final CountDownLatch manuallyCreatedContainerLatch = new CountDownLatch(1);
		final CountDownLatch manuallyStartedContainerLatch = new CountDownLatch(1);
		final CountDownLatch manuallyStartedContainerLatch2 = new CountDownLatch(1);
		final CountDownLatch manuallyCreatedFactorySourceFactoryLatch = new CountDownLatch(1);
		final CountDownLatch manuallyCreatedFactorySinkLatch = new CountDownLatch(1);
		final CountDownLatch manuallyCreatedFactoryLatch = new CountDownLatch(1);
		final CountDownLatch invocableHandlerMethodLatch = new CountDownLatch(1);

	}

	@Import(SqsBootstrapConfiguration.class)
	@Configuration
	static class SQSConfiguration {

		@Bean
		public SqsMessageListenerContainerFactory<String> defaultSqsListenerContainerFactory() {
			SqsMessageListenerContainerFactory<String> factory = new SqsMessageListenerContainerFactory<>();
			factory.getContainerOptions()
				.permitAcquireTimeout(Duration.ofSeconds(1))
				.queueAttributes(Collections.singletonList(QueueAttributeName.QUEUE_ARN))
				.pollTimeout(Duration.ofSeconds(3));
			factory.setSqsAsyncClientSupplier(BaseSqsIntegrationTest::createAsyncClient);
			return factory;
		}

		@Bean
		public SqsMessageListenerContainerFactory<String> lowResourceFactory() {
			SqsMessageListenerContainerFactory<String> factory = new SqsMessageListenerContainerFactory<>();
			factory.getContainerOptions()
				.maxInflightMessagesPerQueue(1)
				.pollTimeout(Duration.ofSeconds(3))
				.messagesPerPoll(1)
				.permitAcquireTimeout(Duration.ofSeconds(1));
			factory.setErrorHandler(testErrorHandler());
			factory.addMessageInterceptor(testInterceptor());
			factory.addMessageInterceptor(testInterceptor());
			factory.setSqsAsyncClientSupplier(BaseSqsIntegrationTest::createAsyncClient);
			return factory;
		}

		@Bean
		public MessageListenerContainer<String> manuallyCreatedContainer(SqsAsyncClient client) throws Exception {
			String queueUrl = client.getQueueUrl(req -> req.queueName(MANUALLY_CREATE_CONTAINER_QUEUE_NAME)).get().queueUrl();
			ContainerOptions options = ContainerOptions.create()
				.permitAcquireTimeout(Duration.ofSeconds(1))
				.pollTimeout(Duration.ofSeconds(3));
			SqsMessageListenerContainer<String> container = new SqsMessageListenerContainer<>(createAsyncClient(),
				options);
			container.setQueueNames(queueUrl);
			container.setMessageListener(msg -> latchContainer.manuallyCreatedContainerLatch.countDown());
			return container;
		}

		@Bean
		public SqsMessageListenerContainer<String> manuallyCreatedFactory() {
			SqsMessageListenerContainerFactory<String> factory = new SqsMessageListenerContainerFactory<>();
			factory.getContainerOptions()
				.maxInflightMessagesPerQueue(1)
				.pollTimeout(Duration.ofSeconds(3))
				.messagesPerPoll(1)
				.permitAcquireTimeout(Duration.ofSeconds(1));
			factory.setContainerComponentFactory(new StandardSqsComponentFactory<String>() {
				@Override
				public MessageSource<String> createMessageSource(ContainerOptions options) {
					latchContainer.manuallyCreatedFactorySourceFactoryLatch.countDown();
					return super.createMessageSource(options);
				}

				@Override
				public MessageSink<String> createMessageSink(ContainerOptions options) {
					latchContainer.manuallyCreatedFactorySinkLatch.countDown();
					return super.createMessageSink(options);
				}
			});
			factory.setSqsAsyncClient(BaseSqsIntegrationTest.createAsyncClient());
			factory.setMessageListener(msg -> latchContainer.manuallyCreatedFactoryLatch.countDown());
			return factory.createContainer(MANUALLY_CREATE_FACTORY_QUEUE_NAME);
		}

		LatchContainer latchContainer = new LatchContainer();

		@Bean
		ReceivesMessageListener receivesMessageListener() {
			return new ReceivesMessageListener();
		}

		@Bean
		DoesNotAckOnErrorListener doesNotAckOnErrorListener() {
			return new DoesNotAckOnErrorListener();
		}

		@Bean
		ResolvesParameterTypesListener resolvesParameterTypesListener() {
			return new ResolvesParameterTypesListener();
		}

		@Bean
		SqsListenerCustomizer customizer() {
			return registrar -> registrar.setMessageHandlerMethodFactory(new DefaultMessageHandlerMethodFactory() {
				@Override
				public InvocableHandlerMethod createInvocableHandlerMethod(Object bean, Method method) {
					latchContainer.invocableHandlerMethodLatch.countDown();
					return super.createInvocableHandlerMethod(bean, method);
				}
			});
		}

		@Bean
		LatchContainer latchContainer() {
			return this.latchContainer;
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean(name = TEST_SQS_ASYNC_CLIENT_BEAN_NAME)
		SqsAsyncClient sqsAsyncClientProducer() {
			return BaseSqsIntegrationTest.createHighThroughputAsyncClient();
		}

		private MessageInterceptor<String> testInterceptor() {
			return msg -> {
				latchContainer.interceptorLatch.countDown();
				return msg;
			};
		}

		private ErrorHandler<String> testErrorHandler() {
			return (msg, t) -> {
				latchContainer.errorHandlerLatch.countDown();
				// Eventually ack to not interfere with other tests.
				if (latchContainer.errorHandlerLatch.getCount() == 0) {
					Objects.requireNonNull(
							(Acknowledgement) msg.getHeaders().get(SqsHeaders.SQS_ACKNOWLEDGMENT_HEADER),
							"No acknowledgement present").acknowledge();
				}
				throw (RuntimeException) t;
			};
		}
	}

}