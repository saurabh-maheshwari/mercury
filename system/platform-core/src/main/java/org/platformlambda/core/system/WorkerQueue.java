/*

    Copyright 2018-2019 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.core.system;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import org.platformlambda.core.annotations.EventInterceptor;
import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkerQueue extends AbstractActor {
    private static final Logger log = LoggerFactory.getLogger(WorkerQueue.class);
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final ReadySignal READY = new ReadySignal();
    private static final Utility util = Utility.getInstance();
    private static final String ORIGIN = "origin";

    private PostOffice po = PostOffice.getInstance();
    private ServiceDef def;
    private boolean interceptor;
    private int instance;
    private ActorRef manager;
    private boolean stopped = false;

    public static Props props(ServiceDef def, ActorRef manager, int instance) {
        return Props.create(WorkerQueue.class, () -> new WorkerQueue(def, manager, instance));
    }

    public WorkerQueue(ServiceDef def, ActorRef manager, int instance) {
        this.def = def;
        this.instance = instance;
        this.manager = manager;
        this.interceptor = def.getFunction().getClass().getAnnotation(EventInterceptor.class) != null;
        // tell manager that this worker is ready to process a new event
        manager.tell(READY, getSelf());
        log.debug("{} started", getSelf().path().name());
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder().match(EventEnvelope.class, event -> {
            if (!stopped) {
                final ActorRef self = getSelf();
                executor.submit(()->{
                    // execute function as a future task
                    processEvent(event);
                    /*
                     * Send a ready signal to inform the system this worker is ready for next event.
                     * This guarantee that this future task is executed orderly
                     */
                    manager.tell(READY, self);
                });
            }

        }).match(StopSignal.class, signal -> {
            stopped = true;
            getSelf().tell(PoisonPill.getInstance(), ActorRef.noSender());

        }).build();
    }

    private void processEvent(EventEnvelope event) {
        LambdaFunction f = def.getFunction();
        try {
            boolean ping = event.getHeaders().isEmpty() && event.getBody() == null;
            long begin = ping? 0 : System.nanoTime();
            /*
             * If the service is an interceptor, we will pass the original event envelope instead of the message body.
             */
            Object result = ping? null : f.handleEvent(event.getHeaders(), interceptor ? event : event.getBody(), instance);
            float diff = ping? 0 : System.nanoTime() - begin;
            String replyTo = event.getReplyTo();
            if (replyTo != null) {
                boolean needResponse = true;
                EventEnvelope response = new EventEnvelope();
                response.setTo(replyTo);
                response.setFrom(def.getRoute());
                /*
                 * Preserve correlation ID and notes
                 *
                 * "Notes" is usually used by event interceptors. The system does not restrict the content of the notes.
                 * For example, to save some metadata from the original sender.
                 */
                if (event.getCorrelationId() != null) {
                    response.setCorrelationId(event.getCorrelationId());
                }
                if (event.getExtra() != null) {
                    response.setExtra(event.getExtra());
                }
                if (result instanceof EventEnvelope) {
                    EventEnvelope resultEnvelope = (EventEnvelope) result;
                    Map<String, String> headers = resultEnvelope.getHeaders();
                    if (headers.isEmpty() && resultEnvelope.getBody() == null) {
                        /*
                         * When an empty EventEnvelope is used as a return type.
                         * Post Office will skip sending response.
                         *
                         * This allows the lambda function to create conditional responses.
                         * One use case is the the ObjectStreamService that is using this behavior to
                         * simulate a READ timeout.
                         */
                        needResponse = false;
                    } else {
                        /*
                         * When EventEnvelope is used as a return type, the system will transport
                         * 1. payload
                         * 2. key-values (as headers)
                         * 3. optional parametric types for Java class that uses generic types
                         */
                        response.setBody(resultEnvelope.getBody());
                        for (String h : headers.keySet()) {
                            response.setHeader(h, headers.get(h));
                        }
                        response.setStatus(resultEnvelope.getStatus());
                        if (resultEnvelope.getParametricType() != null) {
                            response.setParametricType(resultEnvelope.getParametricType());
                        }
                    }
                } else {
                    response.setStatus(200).setBody(result);
                }
                if (ping) {
                    // execution time is not set because there is no need to execute the lambda function
                    response.setHeader(ORIGIN, Platform.getInstance().getOrigin());
                    po.send(response);
                } else {
                    if (!interceptor && needResponse) {
                        response.setExecutionTime(diff / PostOffice.ONE_MILLISECOND);
                        po.send(response);
                    }
                }
            }

        } catch (Exception e) {
            int status;
            Throwable ex = util.getRootCause(e);
            if (ex instanceof AppException) {
                status = ((AppException) ex).getStatus();
            } else if (ex instanceof IllegalArgumentException) {
                status = 400;
            } else if (ex instanceof IOException) {
                status = 400;
            } else {
                status = 500;
            }
            String replyTo = event.getReplyTo();
            if (!interceptor && replyTo != null) {
                EventEnvelope response = new EventEnvelope();
                response.setTo(replyTo).setStatus(status).setBody(ex.getMessage());
                response.setFrom(def.getRoute());
                if (event.getCorrelationId() != null) {
                    response.setCorrelationId(event.getCorrelationId());
                }
                if (event.getExtra() != null) {
                    response.setExtra(event.getExtra());
                }
                try {
                    po.send(response);
                } catch (Exception nested) {
                    log.warn("Unhandled exception for {} - {}", getSelf().path().name(), nested.getMessage());
                }
            } else {
                if (status >= 500) {
                    log.error("Unhandled exception for "+getSelf().path().name(), ex);
                } else {
                    log.warn("Unhandled exception for {} - {}", getSelf().path().name(), ex.getMessage());
                }
            }
        }
    }

    @Override
    public void postStop() {
        log.debug("{} stopped", getSelf().path().name());
    }

}
