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

package org.platformlambda.node;

import org.platformlambda.core.annotations.MainApplication;
import org.platformlambda.core.models.EntryPoint;
import org.platformlambda.core.system.ServiceDiscovery;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.ServerPersonality;
import org.platformlambda.node.services.EventNodeHealth;
import org.platformlambda.rest.RestServer;
import org.platformlambda.node.services.ServiceQuery;
import org.platformlambda.node.services.ServiceRegistry;
import org.platformlambda.node.system.ConnectionMonitor;
import org.platformlambda.node.system.LambdaRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@MainApplication
public class EventNode implements EntryPoint {
    private static final Logger log = LoggerFactory.getLogger(EventNode.class);

    private static final String CONNECTOR_HEALTH = "cloud.connector.health";

    public static void main(String[] args) {
        RestServer.main(args);
    }

    @Override
    public void start(String[] args) {
        // begin platform and set personality
        ServerPersonality.getInstance().setType(ServerPersonality.Type.PLATFORM);
        try {
            // start connection monitor
            ConnectionMonitor monitor = new ConnectionMonitor();
            monitor.start();
            // start event node
            Platform platform = Platform.getInstance();
            platform.register(ServiceDiscovery.SERVICE_REGISTRY, new ServiceRegistry(), 1);
            platform.register(ServiceDiscovery.SERVICE_QUERY, new ServiceQuery(), 10);
            platform.register(CONNECTOR_HEALTH, new EventNodeHealth(), 1);
            LambdaRouter.begin();
        } catch (IOException e) {
            log.error("Unable to begin service discovery - {}", e.getMessage());
        }
    }

}
