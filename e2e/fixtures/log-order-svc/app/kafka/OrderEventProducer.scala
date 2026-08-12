package kafka

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

class OrderEventProducer(producer: KafkaProducer[String, String]) {

  // FR-3.6: producer to a topic
  def publish(orderId: String, payload: String): Unit =
    producer.send(new ProducerRecord[String, String]("order-events", orderId, payload))
}
