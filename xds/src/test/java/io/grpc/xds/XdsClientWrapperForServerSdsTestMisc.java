/*
 * Copyright 2020 The gRPC Authors
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
import static io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector.NO_FILTER_CHAIN;
import static io.grpc.xds.internal.sds.SdsProtocolNegotiators.ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClient;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClientPoolFactory;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProvider;
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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Migration test XdsServerWrapper from previous XdsClientWrapperForServerSds. */
@RunWith(JUnit4.class)
public class XdsClientWrapperForServerSdsTestMisc {

  private static final int PORT = 7000;

  private EmbeddedChannel channel;
  private ChannelPipeline pipeline;
  @Mock private TlsContextManager tlsContextManager;
  private InetSocketAddress localAddress;
  private DownstreamTlsContext tlsContext1;
  private DownstreamTlsContext tlsContext2;
  private DownstreamTlsContext tlsContext3;

  @Mock
  private ServerBuilder<?> mockBuilder;
  @Mock
  Server mockServer;
  @Mock
  private XdsServingStatusListener listener;
  private FakeXdsClient xdsClient = new FakeXdsClient();
  private AtomicReference<FilterChainSelector> selectorRef = new AtomicReference<>();
  private XdsServerWrapper xdsServerWrapper;


  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    tlsContext1 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    tlsContext2 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    tlsContext3 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT3", "VA3");
    when(mockBuilder.build()).thenReturn(mockServer);
    when(mockServer.isShutdown()).thenReturn(false);
    xdsServerWrapper = new XdsServerWrapper("0.0.0.0:" + PORT, mockBuilder, listener,
            selectorRef, new FakeXdsClientPoolFactory(xdsClient), FilterRegistry.newRegistry());
  }

  @Test
  public void nonInetSocketAddress_expectNull() throws Exception {
    sendListenerUpdate(new InProcessSocketAddress("test1"), null, null, tlsContextManager);
    assertThat(getSslContextProviderSupplier(selectorRef.get())).isNull();
  }

  @Test
  public void emptyFilterChain_expectNull() throws Exception {
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    final InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    InetAddress ipRemoteAddress = InetAddress.getByName("10.4.5.6");
    final InetSocketAddress remoteAddress = new InetSocketAddress(ipRemoteAddress, 1234);
    channel = new EmbeddedChannel() {
        @Override
        public SocketAddress localAddress() {
          return localAddress;
        }

        @Override
        public SocketAddress remoteAddress() {
          return remoteAddress;
        }
    };
    pipeline = channel.pipeline();

    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    String ldsWatched = xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    assertThat(ldsWatched).isEqualTo("grpc/server?udpa.resource.listening_address=0.0.0.0:" + PORT);

    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            "10.1.2.3",
            Collections.<EnvoyServerProtoData.FilterChain>emptyList(),
            null);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    xdsClient.ldsWatcher.onChanged(listenerUpdate);
    start.get(5, TimeUnit.SECONDS);
    FilterChainSelector selector = selectorRef.get();
    assertThat(getSslContextProviderSupplier(selector)).isNull();
  }

  @Test
  public void registerServerWatcher_notifyNotFound() throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    String ldsWatched = xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsWatched);
    try {
      start.get(5, TimeUnit.SECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
    }
    assertThat(selectorRef.get()).isSameInstanceAs(NO_FILTER_CHAIN);
  }

  @Test
  public void registerServerWatcher_notifyInternalError() throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    xdsClient.ldsWatcher.onError(Status.INTERNAL);
    try {
      start.get(5, TimeUnit.SECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
    }
    assertThat(selectorRef.get()).isSameInstanceAs(NO_FILTER_CHAIN);
  }

  @Test
  public void registerServerWatcher_notifyPermDeniedError() throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    xdsClient.ldsWatcher.onError(Status.PERMISSION_DENIED);
    try {
      start.get(5, TimeUnit.SECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
    }
    assertThat(selectorRef.get()).isSameInstanceAs(NO_FILTER_CHAIN);
  }

  @Test
  public void releaseOldSupplierOnChanged_noCloseDueToLazyLoading() throws Exception {
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    sendListenerUpdate(localAddress, tlsContext2, null,
            tlsContextManager);
    verify(tlsContextManager, never())
        .findOrCreateServerSslContextProvider(any(DownstreamTlsContext.class));
  }

  @Test
  public void releaseOldSupplierOnChangedOnShutdown_verifyClose() throws Exception {
    SslContextProvider sslContextProvider1 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext1)))
        .thenReturn(sslContextProvider1);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    sendListenerUpdate(localAddress, tlsContext1, null,
            tlsContextManager);
    SslContextProviderSupplier returnedSupplier = getSslContextProviderSupplier(selectorRef.get());
    assertThat(returnedSupplier.getTlsContext()).isSameInstanceAs(tlsContext1);
    callUpdateSslContext(returnedSupplier);
    XdsServerTestHelper
        .generateListenerUpdate(xdsClient, Arrays.<Integer>asList(1234), tlsContext2,
            tlsContext3, tlsContextManager);
    returnedSupplier = getSslContextProviderSupplier(selectorRef.get());
    assertThat(returnedSupplier.getTlsContext()).isSameInstanceAs(tlsContext2);
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider1));
    reset(tlsContextManager);
    SslContextProvider sslContextProvider2 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext2)))
            .thenReturn(sslContextProvider2);
    SslContextProvider sslContextProvider3 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext3)))
            .thenReturn(sslContextProvider3);
    callUpdateSslContext(returnedSupplier);
    InetAddress ipRemoteAddress = InetAddress.getByName("10.4.5.6");
    final InetSocketAddress remoteAddress = new InetSocketAddress(ipRemoteAddress, 1111);
    channel = new EmbeddedChannel() {
        @Override
        public SocketAddress localAddress() {
          return localAddress;
        }

        @Override
        public SocketAddress remoteAddress() {
          return remoteAddress;
        }
    };
    pipeline = channel.pipeline();
    returnedSupplier = getSslContextProviderSupplier(selectorRef.get());
    assertThat(returnedSupplier.getTlsContext()).isSameInstanceAs(tlsContext3);
    callUpdateSslContext(returnedSupplier);
    xdsServerWrapper.shutdown();
    assertThat(xdsClient.ldsResource).isNull();
    verify(tlsContextManager, never()).releaseServerSslContextProvider(eq(sslContextProvider1));
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider2));
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider3));
  }

  @Test
  public void releaseOldSupplierOnNotFound_verifyClose() throws Exception {
    SslContextProvider sslContextProvider1 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext1)))
            .thenReturn(sslContextProvider1);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    sendListenerUpdate(localAddress, tlsContext1, null,
            tlsContextManager);
    SslContextProviderSupplier returnedSupplier =
            getSslContextProviderSupplier(selectorRef.get());
    assertThat(returnedSupplier.getTlsContext()).isSameInstanceAs(tlsContext1);
    callUpdateSslContext(returnedSupplier);
    xdsClient.ldsWatcher.onResourceDoesNotExist("not-found Error");
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider1));
  }

  @Test
  public void releaseOldSupplierOnPermDeniedError_verifyClose() throws Exception {
    SslContextProvider sslContextProvider1 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext1)))
            .thenReturn(sslContextProvider1);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    sendListenerUpdate(localAddress, tlsContext1, null,
            tlsContextManager);
    SslContextProviderSupplier returnedSupplier =
            getSslContextProviderSupplier(selectorRef.get());
    assertThat(returnedSupplier.getTlsContext()).isSameInstanceAs(tlsContext1);
    callUpdateSslContext(returnedSupplier);
    xdsClient.ldsWatcher.onError(Status.PERMISSION_DENIED);
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider1));
  }

  @Test
  public void releaseOldSupplierOnTemporaryError_noClose() throws Exception {
    SslContextProvider sslContextProvider1 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext1)))
            .thenReturn(sslContextProvider1);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    sendListenerUpdate(localAddress, tlsContext1, null,
            tlsContextManager);
    SslContextProviderSupplier returnedSupplier =
            getSslContextProviderSupplier(selectorRef.get());
    assertThat(returnedSupplier.getTlsContext()).isSameInstanceAs(tlsContext1);
    callUpdateSslContext(returnedSupplier);
    xdsClient.ldsWatcher.onError(Status.CANCELLED);
    verify(tlsContextManager, never()).releaseServerSslContextProvider(eq(sslContextProvider1));
  }

  private void callUpdateSslContext(SslContextProviderSupplier sslContextProviderSupplier) {
    assertThat(sslContextProviderSupplier).isNotNull();
    SslContextProvider.Callback callback = mock(SslContextProvider.Callback.class);
    sslContextProviderSupplier.updateSslContext(callback);
  }

  private void sendListenerUpdate(
          final SocketAddress localAddress, DownstreamTlsContext tlsContext,
          DownstreamTlsContext tlsContextForDefaultFilterChain, TlsContextManager tlsContextManager)
      throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    XdsServerTestHelper
            .generateListenerUpdate(xdsClient, Arrays.<Integer>asList(), tlsContext,
                    tlsContextForDefaultFilterChain, tlsContextManager);
    start.get(5, TimeUnit.SECONDS);
    InetAddress ipRemoteAddress = InetAddress.getByName("10.4.5.6");
    final InetSocketAddress remoteAddress = new InetSocketAddress(ipRemoteAddress, 1234);
    channel = new EmbeddedChannel() {
      @Override
      public SocketAddress localAddress() {
        return localAddress;
      }

      @Override
      public SocketAddress remoteAddress() {
        return remoteAddress;
      }
    };
    pipeline = channel.pipeline();
  }

  private SslContextProviderSupplier getSslContextProviderSupplier(
          FilterChainSelector selector) throws Exception {
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    ChannelHandler next = new ChannelInboundHandlerAdapter() {
      @Override
      public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        ProtocolNegotiationEvent e = (ProtocolNegotiationEvent)evt;
        sslSet.set(InternalProtocolNegotiationEvent.getAttributes(e)
                .get(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER));
        ctx.pipeline().remove(this);
      }
    };
    ProtocolNegotiator mockDelegate = mock(ProtocolNegotiator.class);
    GrpcHttp2ConnectionHandler grpcHandler = FakeGrpcHttp2ConnectionHandler.newHandler();
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    pipeline.addLast(filterChainMatchingHandler);
    ProtocolNegotiationEvent event = InternalProtocolNegotiationEvent.getDefault();
    pipeline.fireUserEventTriggered(event);
    channel.runPendingTasks();
    sslSet.set(InternalProtocolNegotiationEvent.getAttributes(event)
            .get(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER));
    return sslSet.get();
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
