/*
 * Copyright 2016 Netflix, Inc.
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations under the License.
 */
package io.reactivesocket.client;

import io.reactivesocket.ReactiveSocket;
import io.reactivesocket.ReactiveSocketConnector;
import io.reactivesocket.ReactiveSocketFactory;
import io.reactivesocket.client.filter.*;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ClientBuilder<T> {
    private final ScheduledExecutorService executor;

    private final long requestTimeout;
    private final TimeUnit requestTimeoutUnit;

    private final long connectTimeout;
    private final TimeUnit connectTimeoutUnit;

    private final ReactiveSocketConnector<T> connector;

    private final Publisher<? extends Collection<T>> source;

    private ClientBuilder(
        ScheduledExecutorService executor,
        long requestTimeout, TimeUnit requestTimeoutUnit,
        long connectTimeout, TimeUnit connectTimeoutUnit,
        ReactiveSocketConnector<T> connector,
        Publisher<? extends Collection<T>> source
    ) {
        this.executor = executor;
        this.requestTimeout = requestTimeout;
        this.requestTimeoutUnit = requestTimeoutUnit;
        this.connectTimeout = connectTimeout;
        this.connectTimeoutUnit = connectTimeoutUnit;
        this.connector = connector;
        this.source = source;
    }

    public ClientBuilder<T> withRequestTimeout(long timeout, TimeUnit unit) {
        return new ClientBuilder<>(
            executor,
            timeout, unit,
            connectTimeout, connectTimeoutUnit,
            connector,
            source
        );
    }

    public ClientBuilder<T> withConnectTimeout(long timeout, TimeUnit unit) {
        return new ClientBuilder<>(
            executor,
            requestTimeout, requestTimeoutUnit,
            timeout, unit,
            connector,
            source
        );
    }

    public ClientBuilder<T> withExecutor(ScheduledExecutorService executor) {
        return new ClientBuilder<>(
            executor,
            requestTimeout, requestTimeoutUnit,
            connectTimeout, connectTimeoutUnit,
            connector,
            source
        );
    }

    public ClientBuilder<T> withConnector(ReactiveSocketConnector<T> connector) {
        return new ClientBuilder<>(
            executor,
            requestTimeout, requestTimeoutUnit,
            connectTimeout, connectTimeoutUnit,
            connector,
            source
        );
    }

    public ClientBuilder<T> withSource(Publisher<? extends Collection<T>> source) {
        return new ClientBuilder<>(
            executor,
            requestTimeout, requestTimeoutUnit,
            connectTimeout, connectTimeoutUnit,
            connector,
            source
        );
    }

    public ReactiveSocket build() {
        if (source == null) {
            throw new IllegalStateException("Please configure the source!");
        }
        if (connector == null) {
            throw new IllegalStateException("Please configure the connector!");
        }


        ReactiveSocketConnector<T> filterConnector = connector;
        if (requestTimeout > 0) {
            filterConnector = filterConnector
                .chain(socket -> new TimeoutSocket(socket, requestTimeout, requestTimeoutUnit, executor));
        }
        filterConnector = filterConnector.chain(DrainingSocket::new);

        Publisher<? extends Collection<ReactiveSocketFactory<T>>> factories =
            sourceToFactory(source, filterConnector);

        return new LoadBalancer<>(factories);
    }

    private Publisher<? extends Collection<ReactiveSocketFactory<T>>> sourceToFactory(
        Publisher<? extends Collection<T>> source,
        ReactiveSocketConnector<T> connector
    ) {
        return subscriber ->
            source.subscribe(new Subscriber<Collection<T>>() {
                private Map<T, ReactiveSocketFactory<T>> current;

                @Override
                public void onSubscribe(Subscription s) {
                    subscriber.onSubscribe(s);
                    current = Collections.emptyMap();
                }

                @Override
                public void onNext(Collection<T> socketAddresses) {
                    Map<T, ReactiveSocketFactory<T>> next = new HashMap<>(socketAddresses.size());
                    for (T sa: socketAddresses) {
                        ReactiveSocketFactory<T> factory = current.get(sa);
                        if (factory == null) {
                            ReactiveSocketFactory<T> newFactory = connector.toFactory(sa);
                            if (connectTimeout > 0) {
                                newFactory = new TimeoutFactory<>(newFactory, connectTimeout, connectTimeoutUnit, executor);
                            }
                            newFactory = new FailureAwareFactory<>(newFactory);
                            next.put(sa, newFactory);
                        } else {
                            next.put(sa, factory);
                        }
                    }

                    current = next;
                    List<ReactiveSocketFactory<T>> factories = new ArrayList<>(current.values());
                    subscriber.onNext(factories);
                }

                @Override
                public void onError(Throwable t) { subscriber.onError(t); }

                @Override
                public void onComplete() { subscriber.onComplete(); }
            });
    }

    public static <T> ClientBuilder<T> instance() {
        return new ClientBuilder<>(
            Executors.newScheduledThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("reactivesocket-scheduler-thread");
                thread.setDaemon(true);
                return thread;
            }),
            -1, TimeUnit.SECONDS,
            -1, TimeUnit.SECONDS,
            null,
            null
        );
    }

    @Override
    public String toString() {
        return "ClientBuilder("
            + "source=" + source
            + ", connector=" + connector
            + ", requestTimeout=" + requestTimeout + ' ' + requestTimeoutUnit
            + ", connectTimeout=" + connectTimeout + ' ' + connectTimeoutUnit
            + ')';
    }
}
