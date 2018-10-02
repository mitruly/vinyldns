/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.sqs.queue

import cats.data.NonEmptyList
import cats.effect.IO
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.{AmazonWebServiceRequest, AmazonWebServiceResult}
import com.amazonaws.services.sqs.{AmazonSQSAsync, AmazonSQSAsyncClientBuilder}
import com.amazonaws.services.sqs.model._
import com.typesafe.config.{Config, ConfigFactory}
import vinyldns.core.domain.zone.ZoneCommand
import vinyldns.core.protobuf.ProtobufConversions
import vinyldns.core.queue._
import vinyldns.core.route.Monitor

import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._
import vinyldns.sqs.queue.SqsConverters._

final case class ReceiptHandle(value: String) extends MessageHandle

class SqsMessageQueue(queueUrl: String, client: AmazonSQSAsync)
    extends MessageQueue
    with ProtobufConversions {

  private def monitored[A](name: String)(f: => IO[A]): IO[A] = {
    val monitor = Monitor(name)
    val startTime = System.currentTimeMillis

    def timeAndRecord: Boolean => Unit = monitor.capture(monitor.duration(startTime), _)

    def failed(): Unit = timeAndRecord(false)
    def succeeded(): Unit = timeAndRecord(true)

    f.attempt.flatMap {
      case Left(error) =>
        failed()
        IO.raiseError(error)
      case Right(ok) =>
        succeeded()
        IO.pure(ok)
    }
  }

  private def sqsAsync[A <: AmazonWebServiceRequest, B <: AmazonWebServiceResult[_]](
      request: A,
      f: (A, AsyncHandler[A, B]) => java.util.concurrent.Future[B]): IO[B] =
    IO.async[B] { complete: (Either[Throwable, B] => Unit) =>
      val asyncHandler = new AsyncHandler[A, B] {
        def onError(exception: Exception): Unit = complete(Left(exception))

        def onSuccess(request: A, result: B): Unit = complete(Right(result))
      }

      f(request, asyncHandler)
    }

  def receive(count: MessageCount): IO[List[CommandMessage]] =
    monitored("sqs.receiveMessageBatch") {
      sqsAsync[ReceiveMessageRequest, ReceiveMessageResult](
        new ReceiveMessageRequest()
          .withMaxNumberOfMessages(count.value)
          .withMessageAttributeNames(".*")
          .withWaitTimeSeconds(1)
          .withQueueUrl(queueUrl),
        client.receiveMessageAsync
      ).map(_.getMessages.asScala.toList.map(m =>
        CommandMessage(ReceiptHandle(m.getReceiptHandle), fromMessage(m))))
    }

  def remove(message: CommandMessage): IO[Unit] =
    monitored("sqs.removeMessage")(
      sqsAsync[DeleteMessageRequest, DeleteMessageResult](
        new DeleteMessageRequest(queueUrl, message.handle.asInstanceOf[ReceiptHandle].value),
        client.deleteMessageAsync)).map(_ => ())

  // AWS SQS has no explicit requeue mechanism; use changeMessageTimeout instead
  def requeue(message: CommandMessage): IO[Unit] = IO.unit

  def send[A <: ZoneCommand](command: A): IO[Unit] =
    monitored("sqs.sendMessage")(
      sqsAsync[SendMessageRequest, SendMessageResult](
        toSendMessageRequest(command)
          .withQueueUrl(queueUrl),
        client.sendMessageAsync)).map(_ => ())

  def send[A <: ZoneCommand](messages: NonEmptyList[A]): IO[SendBatchResult] =
    IO.pure(SendBatchResult(List(), List()))

  /*
  def send[A <: ZoneCommand](messages: NonEmptyList[A]): IO[SendBatchResult] =
    monitored("sqs.sendMessageBatch")(
      sqsAsync[SendMessageBatchRequest, SendMessageBatchResult](
        toSendMessageRequest(messages)
          .withQueueUrl(queueUrl),
        client.sendMessageBatchAsync))
      .map{
      batchResult =>
        val successes = batchResult.getSuccessful.asScala.map(fromMessage).toList
        val failures = batchResult.getFailed.asScala.map(fromMessage).toList
        SendBatchResult(successes, failures)
      }
   */

  def changeMessageTimeout(message: CommandMessage, duration: FiniteDuration): IO[Unit] =
    monitored("sqs.changeMessageTimeout")(
      sqsAsync[ChangeMessageVisibilityRequest, ChangeMessageVisibilityResult](
        new ChangeMessageVisibilityRequest()
          .withReceiptHandle(message.handle.asInstanceOf[ReceiptHandle].value)
          .withVisibilityTimeout(1800) // 1800 seconds == 30 minutes
          .withQueueUrl(queueUrl),
        client.changeMessageVisibilityAsync
      )).map(_ => ())

  def shutdown(): Unit = client.shutdown()
}

object SqsMessageQueue {
  def apply(config: Config = ConfigFactory.load().getConfig("sqs")): SqsMessageQueue = {
    val accessKey = config.getString("access-key")
    val secretKey = config.getString("secret-key")
    val serviceEndpoint = config.getString("service-endpoint")
    val signingRegion = config.getString("signing-region")
    val queueUrl = config.getString("queue-url")

    val client =
      AmazonSQSAsyncClientBuilder
        .standard()
        .withEndpointConfiguration(new EndpointConfiguration(serviceEndpoint, signingRegion))
        .withCredentials(
          new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
        .build()

    new SqsMessageQueue(queueUrl, client)
  }
}
