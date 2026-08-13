package pubsub

import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver, Subscriber}
import com.google.pubsub.v1.{ProjectSubscriptionName, PubsubMessage}

/** Consumes order events from Google Pub/Sub and adjusts stock levels. */
class OrderEventSubscriber(projectId: String, receiver: MessageReceiver) {

  private val subscription =
    ProjectSubscriptionName.of(projectId, "order-events-inventory-sub")

  def start(): Subscriber = Subscriber.newBuilder(subscription, receiver).build()
}
