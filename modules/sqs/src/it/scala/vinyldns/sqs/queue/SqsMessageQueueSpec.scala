
package vinyldns.sqs.queue

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.scalatest.mockito.MockitoSugar
import vinyldns.core.queue.CommandMessage
import vinyldns.core.TestRecordSetData._

import org.scalatest.Matchers

class SqsMessageQueueSpec extends WordSpec with MockitoSugar with BeforeAndAfterAll with Matchers {
  private var queue: SqsMessageQueue = _

  "SqsMessageQueueSpec" should {
    "receive a message from the queue" in {
    }

    "remove a message from the queue" in {

    }

    "return unit when attempting to requeue" in {
      queue = SqsMessageQueue()
      noException should be thrownBy
        queue.requeue(CommandMessage(ReceiptHandle("test"), makeTestAddChange(rsOk))).unsafeRunSync()
    }

    "send single message request" in {
      queue = SqsMessageQueue()
      queue.send(makeTestAddChange(rsOk)).unsafeRunSync() shouldBe 1
    }

    "send batch message requests" in {

    }

    "change message visibility timeouts" in {

    }

    "shutdown" in {

    }
  }
}
