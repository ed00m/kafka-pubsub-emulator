/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.partners.pubsub.kafka;

import static com.google.cloud.partners.pubsub.kafka.Configuration.getApplicationProperties;
import static com.google.cloud.partners.pubsub.kafka.enums.MetricProperty.AVG_LATENCY;
import static com.google.cloud.partners.pubsub.kafka.enums.MetricProperty.ERROR_RATE;
import static com.google.cloud.partners.pubsub.kafka.enums.MetricProperty.MESSAGE_COUNT;
import static com.google.cloud.partners.pubsub.kafka.enums.MetricProperty.QPS;
import static com.google.cloud.partners.pubsub.kafka.enums.MetricProperty.THROUGHPUT;
import static java.lang.String.format;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.cloud.partners.pubsub.kafka.common.AdminGrpc.AdminImplBase;
import com.google.cloud.partners.pubsub.kafka.common.ConfigurationRequest;
import com.google.cloud.partners.pubsub.kafka.common.ConfigurationResponse;
import com.google.cloud.partners.pubsub.kafka.common.ConfigurationResponse.Extension;
import com.google.cloud.partners.pubsub.kafka.common.Metric;
import com.google.cloud.partners.pubsub.kafka.common.StatisticsConsolidation;
import com.google.cloud.partners.pubsub.kafka.common.StatisticsRequest;
import com.google.cloud.partners.pubsub.kafka.common.StatisticsResponse;
import com.google.cloud.partners.pubsub.kafka.enums.MetricProperty;
import com.google.cloud.partners.pubsub.kafka.properties.KafkaProperties;
import com.google.common.collect.Lists;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

class AdminImpl extends AdminImplBase {

  private static final String DECIMAL_FORMAT = "%19.2f";

  private static final String FORMAT = "%d";

  private final StatisticsManager statisticsManager;

  private final Instant startedAt;

  AdminImpl(StatisticsManager statisticsManager) {
    this.startedAt = Instant.now();
    this.statisticsManager = statisticsManager;
  }

  @Override
  public void configuration(
      ConfigurationRequest request, StreamObserver<ConfigurationResponse> responseObserver) {
    try {
      responseObserver.onNext(
          ConfigurationResponse.newBuilder()
              .setContent(Configuration.getCurrentConfiguration())
              .setExtension(Extension.YAML)
              .build());
      responseObserver.onCompleted();
    } catch (JsonProcessingException e) {
      responseObserver.onError(Status.INTERNAL.withCause(e).asException());
    }
  }

  @Override
  public void statistics(
      StatisticsRequest request, StreamObserver<StatisticsResponse> responseObserver) {

    KafkaProperties kafkaProperties = getApplicationProperties().getKafkaProperties();

    long durationSeconds = java.time.Duration.between(startedAt, Instant.now()).getSeconds();

    Map<String, StatisticsInformation> publishInformationByTopic =
        statisticsManager.getPublishInformationByTopic();

    Map<String, StatisticsInformation> subscriberInformationByTopic =
        statisticsManager.getSubscriberInformationByTopic();

    Map<String, StatisticsConsolidation> publishResultByTopic =
        processResult(
            topic ->
                StatisticsConsolidation.newBuilder()
                    .addAllMetrics(
                        calculatePublisherInformation(
                            durationSeconds, publishInformationByTopic.get(topic)))
                    .build());

    Map<String, StatisticsConsolidation> subscriberResultByTopic =
        processResult(
            topic ->
                StatisticsConsolidation.newBuilder()
                    .addAllMetrics(
                        calculateInformation(
                            durationSeconds, subscriberInformationByTopic.get(topic)))
                    .build());

    StatisticsResponse response =
        StatisticsResponse.newBuilder()
            .setPublisherExecutors(kafkaProperties.getProducerProperties().getExecutors())
            .setSubscriberExecutors(kafkaProperties.getConsumerProperties().getExecutors())
            .putAllPublisherByTopic(publishResultByTopic)
            .putAllSubscriberByTopic(subscriberResultByTopic)
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private List<Metric> calculateInformation(
      long durationSeconds, StatisticsInformation information) {
    Metric count = buildMetric(MESSAGE_COUNT, information.getCount().intValue(), FORMAT);
    Metric throughput =
        buildMetric(THROUGHPUT, information.getThroughput(durationSeconds), DECIMAL_FORMAT);
    Metric averageLatency =
        buildMetric(AVG_LATENCY, information.getAverageLatency(), DECIMAL_FORMAT);
    Metric qps = buildMetric(QPS, information.getQPS(durationSeconds), DECIMAL_FORMAT);
    return Lists.newArrayList(count, throughput, averageLatency, qps);
  }

  private List<Metric> calculatePublisherInformation(
      long durationSeconds, StatisticsInformation information) {
    List<Metric> metrics = calculateInformation(durationSeconds, information);
    metrics.add(buildMetric(ERROR_RATE, information.getErrorRating(), DECIMAL_FORMAT));
    return metrics;
  }

  private Map<String, StatisticsConsolidation> processResult(
      Function<String, StatisticsConsolidation> function) {
    return Configuration.getApplicationProperties()
        .getKafkaProperties()
        .getTopics()
        .stream()
        .collect(Collectors.toMap(Function.identity(), function));
  }

  private Metric buildMetric(MetricProperty property, Object value, String format) {
    return Metric.newBuilder()
        .setDescription(property.getDescription())
        .setName(property.getName())
        .setValue(format(format, value).trim())
        .build();
  }
}
