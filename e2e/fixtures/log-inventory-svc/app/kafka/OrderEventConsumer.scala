package kafka

import org.apache.kafka.clients.consumer.KafkaConsumer
import scala.jdk.CollectionConverters._

class OrderEventConsumer(consumer: KafkaConsumer[String, String]) {

  // FR-3.6: consumer of a topic
  def start(): Unit = consumer.subscribe(List("order-events").asJava)
}
