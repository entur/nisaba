/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 */

package no.entur.nisaba.kafka;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TimeoutException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a real producer so that a test can make every send fail, as during a broker outage, or make every
 * send land while its acknowledgement is reported lost, as when the producer gives up on a request in flight.
 */
final class FailableProducer {

    private final AtomicBoolean failing = new AtomicBoolean();
    private final AtomicBoolean losingAcknowledgements = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger failedSends = new java.util.concurrent.atomic.AtomicInteger();

    void failSends(boolean fail) {
        failing.set(fail);
    }

    /**
     * The record is written, but the callback reports a timeout, as the producer does when it gives up on a
     * request the broker then completes.
     */
    void loseAcknowledgements(boolean lose) {
        losingAcknowledgements.set(lose);
    }

    int failedSends() {
        return failedSends.get();
    }

    @SuppressWarnings("unchecked")
    Producer<String, String> wrap(Map<String, Object> config, KafkaClaimRepository.Timeouts timeouts) {
        Producer<String, String> delegate = KafkaClaimRepository.createProducer(config, timeouts);
        return (Producer<String, String>) Proxy.newProxyInstance(Producer.class.getClassLoader(),
                new Class<?>[]{Producer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("send") && failing.get()) {
                        failedSends.incrementAndGet();
                        TimeoutException failure = new TimeoutException("simulated broker outage");
                        if (args.length == 2 && args[1] instanceof Callback callback) {
                            callback.onCompletion(null, failure);
                        }
                        Future<RecordMetadata> future = CompletableFuture.failedFuture(failure);
                        return future;
                    }
                    if (method.getName().equals("send") && losingAcknowledgements.get()
                            && args.length == 2 && args[1] instanceof Callback callback) {
                        failedSends.incrementAndGet();
                        Callback lost = (metadata, exception) -> callback.onCompletion(null,
                                exception != null ? exception : new TimeoutException("simulated lost acknowledgement"));
                        try {
                            return method.invoke(delegate, args[0], lost);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
