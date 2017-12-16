/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tephra.distributed;

import org.apache.hadoop.conf.Configuration;
import org.apache.tephra.TxConstants;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An abstract tx client provider that implements common functionality.
 */
public abstract class AbstractClientProvider implements ThriftClientProvider {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractClientProvider.class);

  // Discovery service. If null, no service discovery.
  private final DiscoveryServiceClient discoveryServiceClient;
  protected final AtomicBoolean initialized = new AtomicBoolean(false);

  // the configuration
  final Configuration configuration;

  // the endpoint strategy for service discovery.
  EndpointStrategy endpointStrategy;

  protected AbstractClientProvider(Configuration configuration, DiscoveryServiceClient discoveryServiceClient) {
    this.configuration = configuration;
    this.discoveryServiceClient = discoveryServiceClient;
  }

  public void initialize() throws TException {
    if (initialized.compareAndSet(false, true)) {
      this.initDiscovery();
    }
  }

  /**
   * Initialize the service discovery client, we will reuse that
   * every time we need to create a new client.
   */
  private void initDiscovery() {
    if (discoveryServiceClient == null) {
      LOG.info("No DiscoveryServiceClient provided. Skipping service discovery.");
      return;
    }

    endpointStrategy = new TimeLimitEndpointStrategy(
      new RandomEndpointStrategy(
        discoveryServiceClient.discover(
          configuration.get(TxConstants.Service.CFG_DATA_TX_DISCOVERY_SERVICE_NAME,
                            TxConstants.Service.DEFAULT_DATA_TX_DISCOVERY_SERVICE_NAME))),
      2, TimeUnit.SECONDS);
  }

  protected TransactionServiceThriftClient newClient() throws TException {
    return newClient(-1);
  }

  protected TransactionServiceThriftClient newClient(int timeout) throws TException {
    initialize();
    String address;
    int port;

    if (endpointStrategy == null) {
      // if there is no discovery service, try to read host and port directly
      // from the configuration
      LOG.debug("Reading transaction service address and port from configuration.");
      address = configuration.get(TxConstants.Service.CFG_DATA_TX_BIND_ADDRESS,
                                  TxConstants.Service.DEFAULT_DATA_TX_BIND_ADDRESS);
      port = configuration.getInt(TxConstants.Service.CFG_DATA_TX_BIND_PORT,
                                  TxConstants.Service.DEFAULT_DATA_TX_BIND_PORT);
      LOG.debug("Transaction service configured at {}:{}.", address, port);
    } else {
      Discoverable endpoint = endpointStrategy.pick();
      if (endpoint == null) {
        throw new TException("Unable to discover transaction service.");
      }
      address = endpoint.getSocketAddress().getHostName();
      port = endpoint.getSocketAddress().getPort();
      LOG.debug("Transaction service discovered at {}:{}.", address, port);
    }

    // now we have an address and port, try to connect a client
    if (timeout < 0) {
      timeout = configuration.getInt(TxConstants.Service.CFG_DATA_TX_CLIENT_TIMEOUT,
          TxConstants.Service.DEFAULT_DATA_TX_CLIENT_TIMEOUT_MS);
    }
    LOG.debug("Attempting to connect to transaction service at {}:{} with RPC timeout of {} ms." +
               address, port, timeout);
    // thrift transport layer
    TTransport transport = new TFramedTransport(new TSocket(address, port, timeout));
    transport.open();
    // and create a thrift client
    TransactionServiceThriftClient newClient = new TransactionServiceThriftClient(transport);

    LOG.debug("Connected to transaction service at {}:{}.", address, port);
    return newClient;
  }

  /**
   * This class helps picking up an endpoint from a list of Discoverable.
   */
  public interface EndpointStrategy {

    /**
     * Picks a {@link Discoverable} using its strategy.
     * @return A {@link Discoverable} based on the stragegy or {@code null} if no endpoint can be found.
     */
    Discoverable pick();
  }

  /**
   * An {@link EndpointStrategy} that make sure it picks an endpoint within the given
   * timeout limit.
   */
  public final class TimeLimitEndpointStrategy implements EndpointStrategy {

    private final EndpointStrategy delegate;
    private final long timeout;
    private final TimeUnit timeoutUnit;

    public TimeLimitEndpointStrategy(EndpointStrategy delegate, long timeout, TimeUnit timeoutUnit) {
      this.delegate = delegate;
      this.timeout = timeout;
      this.timeoutUnit = timeoutUnit;
    }

    @Override
    public Discoverable pick() {
      Discoverable pick = delegate.pick();
      try {
        long count = 0;
        while (pick == null && count++ < timeout) {
          timeoutUnit.sleep(1);
          pick = delegate.pick();
        }
      } catch (InterruptedException e) {
        // Simply propagate the interrupt.
        Thread.currentThread().interrupt();
      }
      return pick;
    }
  }

  /**
   * Randomly picks endpoint from the list of available endpoints.
   */
  public final class RandomEndpointStrategy implements EndpointStrategy {

    private final Iterable<Discoverable> endpoints;

    /**
     * Constructs a random endpoint strategy.
     * @param endpoints Endpoints for the strategy to use. Note that this strategy will
     *                  invoke {@link Iterable#iterator()} and traverse through it on
     *                  every call to the {@link #pick()} method. One could leverage this
     *                  behavior with the live {@link Iterable} as provided by
     *                  {@link org.apache.twill.discovery.DiscoveryServiceClient#discover(String)} method.
     */
    public RandomEndpointStrategy(Iterable<Discoverable> endpoints) {
      this.endpoints = endpoints;
    }

    @Override
    public Discoverable pick() {
      // Reservoir sampling
      Discoverable result = null;
      Iterator<Discoverable> itor = endpoints.iterator();
      Random random = new Random();
      int count = 0;
      while (itor.hasNext()) {
        Discoverable next = itor.next();
        if (random.nextInt(++count) == 0) {
          result = next;
        }
      }
      return result;
    }
  }
}

