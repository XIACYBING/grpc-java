/*
 * Copyright 2021 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.XdsServerWrapper.ATTR_SERVER_ROUTING_CONFIG;
import static io.grpc.xds.internal.sds.SdsProtocolNegotiators.ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.XdsServerWrapper.ServerRoutingConfig;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class FilterChainMatchingProtocolNegotiatorsTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final GrpcHttp2ConnectionHandler grpcHandler =
          FakeGrpcHttp2ConnectionHandler.newHandler();
  @Mock private TlsContextManager tlsContextManager;
  private ProtocolNegotiationEvent event = InternalProtocolNegotiationEvent.getDefault();
  private ChannelPipeline pipeline;
  private EmbeddedChannel channel;
  private ChannelHandlerContext channelHandlerCtx;
  @Mock
  private ProtocolNegotiator mockDelegate;
  private static final HttpConnectionManager HTTP_CONNECTION_MANAGER = createRds("routing-config");
  private static final String LOCAL_IP = "10.1.2.3";  // dest
  private static final String REMOTE_IP = "10.4.2.3"; // source
  private static final int PORT = 7000;

  @Test
  public void nofilterChainMatch_defaultSslContext() throws Exception {
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);

    SslContextProviderSupplier defaultSsl = new SslContextProviderSupplier(createTls(),
            tlsContextManager);
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            new HashMap<FilterChain, ServerRoutingConfig>(), defaultSsl, noopConfig);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    setupChannel("172.168.1.1", "172.168.1.2", 80, filterChainMatchingHandler);
    ChannelHandlerContext channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull();

    pipeline.fireUserEventTriggered(event);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNull();

    channel.runPendingTasks();
    assertThat(sslSet.isDone()).isTrue();
    assertThat(sslSet.get()).isEqualTo(defaultSsl);
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    channelHandlerCtx = pipeline.context(next);
    assertThat(channelHandlerCtx).isNotNull();
  }

  @Test
  public void noFilterChainMatch_noDefaultSslContext() {
    FilterChainSelector selector = new FilterChainSelector(
            new HashMap<FilterChain, ServerRoutingConfig>(), null, null);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    setupChannel("172.168.1.1", "172.168.2.2", 90, filterChainMatchingHandler);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull();

    assertThat(channel.closeFuture().isDone()).isFalse();
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(channel.closeFuture().isDone()).isTrue();
  }

  @Test
  public void singleFilterChainWithoutAlpn() throws Exception {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatch, HTTP_CONNECTION_MANAGER, tlsContext,
            tlsContextManager);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(ImmutableMap.of(filterChain, noopConfig),
            null, null);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.isDone()).isTrue();
    assertThat(sslSet.get()).isEqualTo(filterChain.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContext);
  }

  @Test
  public void singleFilterChainWithAlpn() throws Exception {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.asList("managed-mtls"),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatch, HTTP_CONNECTION_MANAGER, tlsContext,
            tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext defaultTlsContext =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", null, HTTP_CONNECTION_MANAGER, defaultTlsContext,
            tlsContextManager);
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChain, randomConfig("no-match")),
            defaultFilterChain.getSslContextProviderSupplier(), noopConfig);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(defaultFilterChain.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(defaultTlsContext);
  }

  @Test
  public void destPortFails_returnDefaultFilterChain() throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextWithDestPort =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithDestPort =
            new EnvoyServerProtoData.FilterChainMatch(
                    PORT,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.asList("managed-mtls"),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainWithDestPort =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchWithDestPort, HTTP_CONNECTION_MANAGER,
                    tlsContextWithDestPort, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,
                    tlsContextForDefaultFilterChain, tlsContextManager);

    ServerRoutingConfig routingConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), Arrays.asList(createVirtualHost("virtual")));
    ServerRoutingConfig defaultRoutingConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainWithDestPort, routingConfig),
            defaultFilterChain.getSslContextProviderSupplier(), defaultRoutingConfig);

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(defaultFilterChain.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(defaultRoutingConfig);
    assertThat(sslSet.get().getTlsContext())
            .isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void destPrefixRangeMatch() throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMatch =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMatch =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 24)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainWithMatch = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchWithMatch, HTTP_CONNECTION_MANAGER,
            tlsContextMatch, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,
            tlsContextForDefaultFilterChain, tlsContextManager);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainWithMatch, noopConfig),
            defaultFilterChain.getSslContextProviderSupplier(), randomConfig("no-match"));

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(filterChainWithMatch.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextMatch);
  }

  @Test
  public void destPrefixRangeMismatch_returnDefaultFilterChain()
          throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMismatch =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    // 10.2.2.0/24 doesn't match LOCAL_IP
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMismatch =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.2.2.0", 24)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainWithMismatch =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchWithMismatch, HTTP_CONNECTION_MANAGER,
                    tlsContextMismatch, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,
            tlsContextForDefaultFilterChain, tlsContextManager);
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainWithMismatch, randomConfig("no-match")),
            defaultFilterChain.getSslContextProviderSupplier(), noopConfig);

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.isDone()).isTrue();
    assertThat(sslSet.get()).isEqualTo(defaultFilterChain.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void dest0LengthPrefixRange()
          throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContext0Length =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    // 10.2.2.0/24 doesn't match LOCAL_IP
    EnvoyServerProtoData.FilterChainMatch filterChainMatch0Length =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.2.2.0", 0)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain0Length = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatch0Length, HTTP_CONNECTION_MANAGER,
            tlsContext0Length, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,
            tlsContextForDefaultFilterChain, tlsContextManager);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChain0Length, noopConfig),
            defaultFilterChain.getSslContextProviderSupplier(), null);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(filterChain0Length.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContext0Length);
  }

  @Test
  public void destPrefixRange_moreSpecificWins()
          throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 24)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
                    tlsContextLessSpecific, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecific =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.2", 31)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-bar", filterChainMatchMoreSpecific, HTTP_CONNECTION_MANAGER,
                    tlsContextMoreSpecific,
                    tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainLessSpecific, randomConfig("no-match"),
                    filterChainMoreSpecific, noopConfig),
            defaultFilterChain.getSslContextProviderSupplier(), randomConfig("default"));

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(filterChainMoreSpecific.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRange_emptyListLessSpecific()
          throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
                    tlsContextLessSpecific, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecific =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("8.0.0.0", 5)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-bar", filterChainMatchMoreSpecific, HTTP_CONNECTION_MANAGER,
                    tlsContextMoreSpecific,
                    tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainLessSpecific, randomConfig("no-match"),
                    filterChainMoreSpecific, noopConfig),
            defaultFilterChain.getSslContextProviderSupplier(), randomConfig("default"));
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(filterChainMoreSpecific.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRangeIpv6_moreSpecificWins()
          throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("FE80:0:0:0:0:0:0:0", 60)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
                    tlsContextLessSpecific, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecific =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("FE80:0000:0000:0000:0202:0:0:0", 80)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-bar", filterChainMatchMoreSpecific, HTTP_CONNECTION_MANAGER,
                    tlsContextMoreSpecific, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainLessSpecific, randomConfig("no-match"),
                    filterChainMoreSpecific, noopConfig),
            defaultFilterChain.getSslContextProviderSupplier(), randomConfig("default"));

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);

    setupChannel("FE80:0000:0000:0000:0202:B3FF:FE1E:8329", "2001:DB8::8:800:200C:417A",
            15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(filterChainMoreSpecific.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRange_moreSpecificWith2Wins()
          throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecificWith2 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecificWith2 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(
                            new EnvoyServerProtoData.CidrRange("10.1.2.0", 24),
                            new EnvoyServerProtoData.CidrRange(LOCAL_IP, 32)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecificWith2 =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchMoreSpecificWith2, HTTP_CONNECTION_MANAGER,
                    tlsContextMoreSpecificWith2, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.2", 31)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-bar", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
                    tlsContextLessSpecific, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainMoreSpecificWith2, noopConfig,
                    filterChainLessSpecific, randomConfig("no-match")),
            defaultFilterChain.getSslContextProviderSupplier(), randomConfig("default"));
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(
            filterChainMoreSpecificWith2.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextMoreSpecificWith2);
  }

  @Test
  public void sourceTypeMismatch_returnDefaultFilterChain() throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMismatch =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMismatch =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainWithMismatch =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchWithMismatch, HTTP_CONNECTION_MANAGER,
                    tlsContextMismatch, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,tlsContextForDefaultFilterChain,
            tlsContextManager);
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainWithMismatch, randomConfig("no-match")),
            defaultFilterChain.getSslContextProviderSupplier(), noopConfig);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(defaultFilterChain.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void sourceTypeLocal() throws Exception {
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMatch =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMatch =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainWithMatch = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchWithMatch, HTTP_CONNECTION_MANAGER, tlsContextMatch,
            tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", null, HTTP_CONNECTION_MANAGER, tlsContextForDefaultFilterChain,
            tlsContextManager);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainWithMatch, noopConfig),
            defaultFilterChain.getSslContextProviderSupplier(), randomConfig("default"));
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    setupChannel(LOCAL_IP, LOCAL_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(filterChainWithMatch.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextMatch);
  }

  @Test
  public void sourcePrefixRange_moreSpecificWith2Wins()
          throws Exception {
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecificWith2 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecificWith2 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.asList(
                            new EnvoyServerProtoData.CidrRange("10.4.2.0", 24),
                            new EnvoyServerProtoData.CidrRange(REMOTE_IP, 32)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecificWith2 =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchMoreSpecificWith2, HTTP_CONNECTION_MANAGER,
                    tlsContextMoreSpecificWith2, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.2", 31)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-bar", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
                    tlsContextLessSpecific, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainMoreSpecificWith2, noopConfig,
                    filterChainLessSpecific, randomConfig("no-match")),
            defaultFilterChain.getSslContextProviderSupplier(), randomConfig("default"));

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(
            filterChainMoreSpecificWith2.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextMoreSpecificWith2);
  }

  @Test
  public void sourcePrefixRange_2Matchers_expectException()
          throws UnknownHostException {
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    ChannelHandler next = new ChannelInboundHandlerAdapter() {
      @Override
      public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        ProtocolNegotiationEvent e = (ProtocolNegotiationEvent)evt;
        sslSet.set(InternalProtocolNegotiationEvent.getAttributes(e)
                .get(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER));
      }
    };
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);

    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.asList(
                            new EnvoyServerProtoData.CidrRange("10.4.2.0", 24),
                            new EnvoyServerProtoData.CidrRange("192.168.10.2", 32)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain1 = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatch1, HTTP_CONNECTION_MANAGER, tlsContext1,
            tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContext2 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.0", 24)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain2 = new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", filterChainMatch2, HTTP_CONNECTION_MANAGER, tlsContext2,
            tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, null);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChain1, noopConfig, filterChain2, noopConfig),
            defaultFilterChain.getSslContextProviderSupplier(), noopConfig);

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    try {
      channel.checkException();
      fail("expect exception!");
    } catch (IllegalStateException ise) {
      assertThat(ise).hasMessageThat().isEqualTo("Found more than one matching filter chains. This "
          + "should not be possible as ClientXdsClient validated the chains for uniqueness.");
      assertThat(sslSet.isDone()).isFalse();
      channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
      assertThat(channelHandlerCtx).isNotNull();
    }
  }

  @Test
  public void sourcePortMatch_exactMatchWinsOverEmptyList() throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContextEmptySourcePorts =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchEmptySourcePorts =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.asList(
                            new EnvoyServerProtoData.CidrRange("10.4.2.0", 24),
                            new EnvoyServerProtoData.CidrRange("10.4.2.2", 31)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainEmptySourcePorts =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-foo", filterChainMatchEmptySourcePorts, HTTP_CONNECTION_MANAGER,
                    tlsContextEmptySourcePorts, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextSourcePortMatch =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchSourcePortMatch =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.2", 31)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.asList(7000, 15000),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChainSourcePortMatch =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-bar", filterChainMatchSourcePortMatch, HTTP_CONNECTION_MANAGER,
                    tlsContextSourcePortMatch, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChainEmptySourcePorts, randomConfig("no-match"),
                    filterChainSourcePortMatch, noopConfig),
            defaultFilterChain.getSslContextProviderSupplier(), randomConfig("default"));

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(filterChainSourcePortMatch.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContextSourcePortMatch);
  }

  /**
   * Create 6 filterChains: - 1st filter chain has dest port & specific prefix range but is
   * eliminated due to dest port - 5 advance to next step: 1 is eliminated due to being less
   * specific than the remaining 4. - 4 advance to 3rd step: source type external eliminates one
   * with local source_type. - 3 advance to 4th step: more specific 2 get picked based on
   * source-prefix range. - 5th step: out of 2 one with matching source port gets picked
   */
  @Test
  public void filterChain_5stepMatch() throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext2 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext3 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT3", "VA3");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext4 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT4", "VA4");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext5 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT5", "VA5");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext6 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT6", "VA6");

    // has dest port and specific prefix ranges: gets eliminated in step 1
    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
            new EnvoyServerProtoData.FilterChainMatch(
                    PORT,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.asList(new EnvoyServerProtoData.CidrRange(REMOTE_IP, 32)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain1 = new EnvoyServerProtoData.FilterChain(
            "filter-chain-1", filterChainMatch1, HTTP_CONNECTION_MANAGER, tlsContext1,
            tlsContextManager);

    // next 5 use prefix range: 4 with prefixLen of 30 and last one with 29

    // has single prefix range: and less specific source prefix range: gets eliminated in step 4
    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
                    Arrays.<String>asList(),
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.0.0", 16)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain2 = new EnvoyServerProtoData.FilterChain(
            "filter-chain-2", filterChainMatch2, HTTP_CONNECTION_MANAGER, tlsContext2,
            tlsContextManager);

    // has prefix ranges with one not matching and source type local: gets eliminated in step 3
    EnvoyServerProtoData.FilterChainMatch filterChainMatch3 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(
                            new EnvoyServerProtoData.CidrRange("192.168.2.0", 24),
                            new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain3 = new EnvoyServerProtoData.FilterChain(
            "filter-chain-3", filterChainMatch3, HTTP_CONNECTION_MANAGER, tlsContext3,
            tlsContextManager);

    // has prefix ranges with both matching and source type external but non matching source port:
    // gets eliminated in step 5
    EnvoyServerProtoData.FilterChainMatch filterChainMatch4 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(
                            new EnvoyServerProtoData.CidrRange("10.1.0.0", 16),
                            new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
                    Arrays.<String>asList(),
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.0", 24)),
                    EnvoyServerProtoData.ConnectionSourceType.EXTERNAL,
                    Arrays.asList(16000, 9000),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain4 =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-4", filterChainMatch4, HTTP_CONNECTION_MANAGER, tlsContext4,
                    tlsContextManager);

    // has prefix ranges with both matching and source type external and matching source port: this
    // gets selected
    EnvoyServerProtoData.FilterChainMatch filterChainMatch5 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(
                            new EnvoyServerProtoData.CidrRange("10.1.0.0", 16),
                            new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
                    Arrays.<String>asList(),
                    Arrays.asList(
                            new EnvoyServerProtoData.CidrRange("10.4.2.0", 24),
                            new EnvoyServerProtoData.CidrRange("192.168.2.0", 24)),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.asList(15000, 8000),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain5 =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-5", filterChainMatch5, HTTP_CONNECTION_MANAGER, tlsContext5,
                    tlsContextManager);

    // has prefix range with prefixLen of 29: gets eliminated in step 2
    EnvoyServerProtoData.FilterChainMatch filterChainMatch6 =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 29)),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.FilterChain filterChain6 =
            new EnvoyServerProtoData.FilterChain(
                    "filter-chain-6", filterChainMatch6, HTTP_CONNECTION_MANAGER, tlsContext6,
                    tlsContextManager);

    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-7", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    Map<FilterChain, ServerRoutingConfig> map = new HashMap<>();
    map.put(filterChain1, randomConfig("1"));
    map.put(filterChain2, randomConfig("2"));
    map.put(filterChain3, randomConfig("3"));
    map.put(filterChain4, randomConfig("4"));
    map.put(filterChain5, noopConfig);
    map.put(filterChain6, randomConfig("6"));
    FilterChainSelector selector = new FilterChainSelector(
            map, defaultFilterChain.getSslContextProviderSupplier(), randomConfig("default"));

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);

    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(filterChain5.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext()).isSameInstanceAs(tlsContext5);
  }

  @Test
  public void filterChainMatch_unsupportedMatchers() throws Exception {
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "ROOTCA");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext2 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "ROOTCA");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext3 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT3", "ROOTCA");

    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
        new EnvoyServerProtoData.FilterChainMatch(
            0 /* destinationPort */,
            Collections.singletonList(
                    new EnvoyServerProtoData.CidrRange("10.1.0.0", 16)) /* prefixRange */,
            Arrays.asList("managed-mtls", "h2") /* applicationProtocol */,
            Collections.<EnvoyServerProtoData.CidrRange>emptyList() /* sourcePrefixRanges */,
            EnvoyServerProtoData.ConnectionSourceType.ANY /* sourceType */,
            Collections.<Integer>emptyList() /* sourcePorts */,
            Arrays.asList("server1", "server2") /* serverNames */,
            "tls" /* transportProtocol */);

    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
        new EnvoyServerProtoData.FilterChainMatch(
            0 /* destinationPort */,
            Collections.singletonList(
                    new EnvoyServerProtoData.CidrRange("10.0.0.0", 8)) /* prefixRange */,
            Collections.<String>emptyList() /* applicationProtocol */,
            Collections.<EnvoyServerProtoData.CidrRange>emptyList() /* sourcePrefixRanges */,
            EnvoyServerProtoData.ConnectionSourceType.ANY /* sourceType */,
            Collections.<Integer>emptyList() /* sourcePorts */,
            Collections.<String>emptyList() /* serverNames */,
            "" /* transportProtocol */);

    EnvoyServerProtoData.FilterChainMatch defaultFilterChainMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0 /* destinationPort */,
            Collections.<EnvoyServerProtoData.CidrRange>emptyList() /* prefixRange */,
            Collections.<String>emptyList() /* applicationProtocol */,
            Collections.<EnvoyServerProtoData.CidrRange>emptyList() /* sourcePrefixRanges */,
            EnvoyServerProtoData.ConnectionSourceType.ANY /* sourceType */,
            Collections.<Integer>emptyList() /* sourcePorts */,
            Collections.<String>emptyList() /* serverNames */,
            "" /* transportProtocol */);

    EnvoyServerProtoData.FilterChain filterChain1 = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatch1, HTTP_CONNECTION_MANAGER, tlsContext1,
            mock(TlsContextManager.class));
    EnvoyServerProtoData.FilterChain filterChain2 = new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", filterChainMatch2, HTTP_CONNECTION_MANAGER, tlsContext2,
            mock(TlsContextManager.class));

    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-baz", defaultFilterChainMatch, HTTP_CONNECTION_MANAGER, tlsContext3,
            mock(TlsContextManager.class));

    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(filterChain1, randomConfig("1"), filterChain2, randomConfig("2")),
            defaultFilterChain.getSslContextProviderSupplier(), noopConfig);

    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    final SettableFuture<ServerRoutingConfig> routingSettable = SettableFuture.create();
    ChannelHandler next = captureAttrHandler(sslSet, routingSettable);
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    setupChannel(LOCAL_IP, REMOTE_IP, 15000, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    assertThat(sslSet.get()).isEqualTo(defaultFilterChain.getSslContextProviderSupplier());
    assertThat(routingSettable.get()).isEqualTo(noopConfig);
    assertThat(sslSet.get().getTlsContext().getCommonTlsContext()
            .getTlsCertificateCertificateProviderInstance()
            .getCertificateName()).isEqualTo("CERT3");
  }

  private static HttpConnectionManager createRds(String name) {
    return HttpConnectionManager.forRdsName(0L, name,
            new ArrayList<NamedFilterConfig>());
  }

  private static VirtualHost createVirtualHost(String name) {
    return VirtualHost.create(
            name, Collections.singletonList("auth"), new ArrayList<Route>(),
            ImmutableMap.<String, FilterConfig>of());
  }

  private static ServerRoutingConfig randomConfig(String domain) {
    return ServerRoutingConfig.create(
          new ArrayList<NamedFilterConfig>(), Arrays.asList(createVirtualHost(domain)));
  }

  private EnvoyServerProtoData.DownstreamTlsContext createTls() {
    return DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
            io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
                    .getDefaultInstance());
  }

  private void setupChannel(final String localIp, final String remoteIp, final int remotePort,
                            FilterChainMatchingHandler matchingHandler) {
    channel =
        new EmbeddedChannel() {
          @Override
          public SocketAddress localAddress() {
            return new InetSocketAddress(localIp, 80);
          }

          @Override
          public SocketAddress remoteAddress() {
            return new InetSocketAddress(remoteIp, remotePort);
          }
        };
    pipeline = channel.pipeline();
    pipeline.addLast(matchingHandler);
  }

  private static ChannelHandler captureAttrHandler(
          final SettableFuture<SslContextProviderSupplier> sslSet,
          final SettableFuture<ServerRoutingConfig> routingSettable) {
    return new ChannelInboundHandlerAdapter() {
      @Override
      public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        ProtocolNegotiationEvent e = (ProtocolNegotiationEvent)evt;
        sslSet.set(InternalProtocolNegotiationEvent.getAttributes(e)
                .get(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER));
        routingSettable.set(InternalProtocolNegotiationEvent.getAttributes(e)
                .get(ATTR_SERVER_ROUTING_CONFIG));
      }
    };
  }

  private static final class FakeGrpcHttp2ConnectionHandler extends GrpcHttp2ConnectionHandler {
    FakeGrpcHttp2ConnectionHandler(
            ChannelPromise channelUnused,
            Http2ConnectionDecoder decoder,
            Http2ConnectionEncoder encoder,
            Http2Settings initialSettings) {
      super(channelUnused, decoder, encoder, initialSettings, new NoopChannelLogger());
    }

    static FakeGrpcHttp2ConnectionHandler newHandler() {
      DefaultHttp2Connection conn = new DefaultHttp2Connection(/*server=*/ false);
      DefaultHttp2ConnectionEncoder encoder =
              new DefaultHttp2ConnectionEncoder(conn, new DefaultHttp2FrameWriter());
      DefaultHttp2ConnectionDecoder decoder =
              new DefaultHttp2ConnectionDecoder(conn, encoder, new DefaultHttp2FrameReader());
      Http2Settings settings = new Http2Settings();
      return new FakeGrpcHttp2ConnectionHandler(
              /*channelUnused=*/ null, decoder, encoder, settings);
    }

    @Override
    public String getAuthority() {
      return "authority";
    }
  }
}
