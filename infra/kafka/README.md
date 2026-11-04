# Kafka

There's a single KRaft broker, configured in `infra/compose/docker-compose.yml`.
Topic auto-creation is disabled. The control plane declares every topic at
startup (`backend/.../config/KafkaTopicsConfig.java`); see
`docs/kafka-events.md` for the catalogue.

To list the topics while the stack is running:

    docker compose -f infra/compose/docker-compose.yml --env-file .env exec kafka \
      /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
