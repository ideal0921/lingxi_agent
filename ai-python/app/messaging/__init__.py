"""Kafka 事件总线封装（M3-3.8）：生产者与通用消费循环。"""

from app.messaging.kafka import KafkaProducer, MessageHandler, consume_forever

__all__ = ["KafkaProducer", "MessageHandler", "consume_forever"]
