package com.acme.logistics.audit

import org.apache.kafka.clients.consumer.KafkaConsumer
import scala.jdk.CollectionConverters._

class AuditConsumer(consumer: KafkaConsumer[String, String]) {
  private val Topic = "order-events"

  def start(): Unit = consumer.subscribe(List(Topic).asJava)
}
