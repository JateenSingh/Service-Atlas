package pubsub

import com.google.cloud.pubsub.v1.Publisher
import com.google.protobuf.ByteString
import com.google.pubsub.v1.{PubsubMessage, TopicName}

/** Publishes order lifecycle events to Google Pub/Sub. */
class OrderEventPublisher(projectId: String) {

  private val topic = TopicName.of(projectId, "order-events-v2")
  private val publisher = Publisher.newBuilder(topic).build()

  def publish(orderId: String, payload: String): Unit = {
    val message = PubsubMessage.newBuilder()
      .setData(ByteString.copyFromUtf8(payload))
      .putAttributes("orderId", orderId)
      .build()
    publisher.publish(message)
  }
}
