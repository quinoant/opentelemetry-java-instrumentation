/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter.http;

import static io.opentelemetry.instrumentation.api.instrumenter.http.ForwardedHeaderParser.extractClientIpFromForwardedForHeader;
import static io.opentelemetry.instrumentation.api.instrumenter.http.ForwardedHeaderParser.extractClientIpFromForwardedHeader;
import static io.opentelemetry.instrumentation.api.instrumenter.http.ForwardedHeaderParser.extractProtoFromForwardedHeader;
import static io.opentelemetry.instrumentation.api.instrumenter.http.ForwardedHeaderParser.extractProtoFromForwardedProtoHeader;
import static io.opentelemetry.instrumentation.api.internal.AttributesExtractorUtil.internalSet;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.net.NetServerAttributesGetter;
import io.opentelemetry.instrumentation.api.instrumenter.net.internal.InternalNetServerAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.network.internal.FallbackAddressPortExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.network.internal.InternalClientAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.network.internal.InternalNetworkAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.network.internal.InternalServerAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.url.internal.InternalUrlAttributesExtractor;
import io.opentelemetry.instrumentation.api.internal.ConfigPropertiesUtil;
import io.opentelemetry.instrumentation.api.internal.SemconvStability;
import io.opentelemetry.instrumentation.api.internal.SpanKey;
import io.opentelemetry.instrumentation.api.internal.SpanKeyProvider;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Extractor of <a
 * href="https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#http-server">HTTP
 * server attributes</a>. Instrumentation of HTTP server frameworks should extend this class,
 * defining {@link REQUEST} and {@link RESPONSE} with the actual request / response types of the
 * instrumented library. If an attribute is not available in this library, it is appropriate to
 * return {@code null} from the protected attribute methods, but implement as many as possible for
 * best compliance with the OpenTelemetry specification.
 */
public final class HttpServerAttributesExtractor<REQUEST, RESPONSE>
    extends HttpCommonAttributesExtractor<
        REQUEST, RESPONSE, HttpServerAttributesGetter<REQUEST, RESPONSE>>
    implements SpanKeyProvider {

  /** Creates the HTTP server attributes extractor with default configuration. */
  public static <REQUEST, RESPONSE> AttributesExtractor<REQUEST, RESPONSE> create(
      HttpServerAttributesGetter<REQUEST, RESPONSE> httpAttributesGetter,
      NetServerAttributesGetter<REQUEST, RESPONSE> netAttributesGetter) {
    return builder(httpAttributesGetter, netAttributesGetter).build();
  }

  /**
   * Returns a new {@link HttpServerAttributesExtractorBuilder} that can be used to configure the
   * HTTP client attributes extractor.
   */
  public static <REQUEST, RESPONSE> HttpServerAttributesExtractorBuilder<REQUEST, RESPONSE> builder(
      HttpServerAttributesGetter<REQUEST, RESPONSE> httpAttributesGetter,
      NetServerAttributesGetter<REQUEST, RESPONSE> netAttributesGetter) {
    return new HttpServerAttributesExtractorBuilder<>(httpAttributesGetter, netAttributesGetter);
  }

  // if set to true, the instrumentation will prefer the scheme from Forwarded/X-Forwarded-Proto
  // headers over the one extracted from the URL
  private static final boolean PREFER_FORWARDED_URL_SCHEME =
      ConfigPropertiesUtil.getBoolean(
          "otel.instrumentation.http.prefer-forwarded-url-scheme", false);

  private final InternalUrlAttributesExtractor<REQUEST> internalUrlExtractor;
  private final InternalNetServerAttributesExtractor<REQUEST, RESPONSE> internalNetExtractor;
  private final InternalNetworkAttributesExtractor<REQUEST, RESPONSE> internalNetworkExtractor;
  private final InternalServerAttributesExtractor<REQUEST, RESPONSE> internalServerExtractor;
  private final InternalClientAttributesExtractor<REQUEST, RESPONSE> internalClientExtractor;
  private final Function<Context, String> httpRouteHolderGetter;

  HttpServerAttributesExtractor(
      HttpServerAttributesGetter<REQUEST, RESPONSE> httpAttributesGetter,
      NetServerAttributesGetter<REQUEST, RESPONSE> netAttributesGetter,
      List<String> capturedRequestHeaders,
      List<String> capturedResponseHeaders,
      Set<String> knownMethods) {
    this(
        httpAttributesGetter,
        netAttributesGetter,
        capturedRequestHeaders,
        capturedResponseHeaders,
        knownMethods,
        HttpRouteHolder::getRoute);
  }

  // visible for tests
  HttpServerAttributesExtractor(
      HttpServerAttributesGetter<REQUEST, RESPONSE> httpAttributesGetter,
      NetServerAttributesGetter<REQUEST, RESPONSE> netAttributesGetter,
      List<String> capturedRequestHeaders,
      List<String> capturedResponseHeaders,
      Set<String> knownMethods,
      Function<Context, String> httpRouteHolderGetter) {
    super(httpAttributesGetter, capturedRequestHeaders, capturedResponseHeaders, knownMethods);
    HttpNetAddressPortExtractor<REQUEST> addressPortExtractor =
        new HttpNetAddressPortExtractor<>(httpAttributesGetter);
    internalUrlExtractor =
        new InternalUrlAttributesExtractor<>(
            httpAttributesGetter,
            /* alternateSchemeProvider= */ this::forwardedProto,
            SemconvStability.emitStableHttpSemconv(),
            SemconvStability.emitOldHttpSemconv());
    internalNetExtractor =
        new InternalNetServerAttributesExtractor<>(
            netAttributesGetter, addressPortExtractor, SemconvStability.emitOldHttpSemconv());
    internalNetworkExtractor =
        new InternalNetworkAttributesExtractor<>(
            netAttributesGetter,
            HttpNetworkTransportFilter.INSTANCE,
            SemconvStability.emitStableHttpSemconv(),
            SemconvStability.emitOldHttpSemconv());
    internalServerExtractor =
        new InternalServerAttributesExtractor<>(
            netAttributesGetter,
            this::shouldCaptureServerPort,
            addressPortExtractor,
            SemconvStability.emitStableHttpSemconv(),
            SemconvStability.emitOldHttpSemconv(),
            InternalServerAttributesExtractor.Mode.HOST);
    internalClientExtractor =
        new InternalClientAttributesExtractor<>(
            netAttributesGetter,
            new ClientAddressAndPortExtractor<>(httpAttributesGetter),
            SemconvStability.emitStableHttpSemconv(),
            SemconvStability.emitOldHttpSemconv());
    this.httpRouteHolderGetter = httpRouteHolderGetter;
  }

  @Override
  public void onStart(AttributesBuilder attributes, Context parentContext, REQUEST request) {
    super.onStart(attributes, parentContext, request);

    internalUrlExtractor.onStart(attributes, request);
    internalNetExtractor.onStart(attributes, request);
    internalServerExtractor.onStart(attributes, request);
    internalClientExtractor.onStart(attributes, request);

    internalSet(attributes, SemanticAttributes.HTTP_ROUTE, getter.getHttpRoute(request));
  }

  private boolean shouldCaptureServerPort(int port, REQUEST request) {
    String scheme = getter.getUrlScheme(request);
    if (scheme == null) {
      return true;
    }
    // according to spec: extract if not default (80 for http scheme, 443 for https).
    if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
      return false;
    }
    return true;
  }

  @Override
  public void onEnd(
      AttributesBuilder attributes,
      Context context,
      REQUEST request,
      @Nullable RESPONSE response,
      @Nullable Throwable error) {

    super.onEnd(attributes, context, request, response, error);

    internalNetworkExtractor.onEnd(attributes, request, response);
    internalServerExtractor.onEnd(attributes, request, response);
    internalClientExtractor.onEnd(attributes, request, response);

    internalSet(attributes, SemanticAttributes.HTTP_ROUTE, httpRouteHolderGetter.apply(context));
  }

  @Nullable
  private String forwardedProto(REQUEST request) {
    if (!PREFER_FORWARDED_URL_SCHEME) {
      // don't parse headers, extract scheme from the URL
      return null;
    }

    // try Forwarded
    String forwarded = firstHeaderValue(getter.getHttpRequestHeader(request, "forwarded"));
    if (forwarded != null) {
      forwarded = extractProtoFromForwardedHeader(forwarded);
      if (forwarded != null) {
        return forwarded;
      }
    }

    // try X-Forwarded-Proto
    forwarded = firstHeaderValue(getter.getHttpRequestHeader(request, "x-forwarded-proto"));
    if (forwarded != null) {
      return extractProtoFromForwardedProtoHeader(forwarded);
    }

    return null;
  }

  /**
   * This method is internal and is hence not for public use. Its API is unstable and can change at
   * any time.
   */
  @Override
  public SpanKey internalGetSpanKey() {
    return SpanKey.HTTP_SERVER;
  }

  private static final class ClientAddressAndPortExtractor<REQUEST>
      implements FallbackAddressPortExtractor<REQUEST> {

    private final HttpServerAttributesGetter<REQUEST, ?> getter;

    private ClientAddressAndPortExtractor(HttpServerAttributesGetter<REQUEST, ?> getter) {
      this.getter = getter;
    }

    @Override
    public void extract(AddressPortSink sink, REQUEST request) {
      // try Forwarded
      String forwarded = firstHeaderValue(getter.getHttpRequestHeader(request, "forwarded"));
      if (forwarded != null) {
        forwarded = extractClientIpFromForwardedHeader(forwarded);
        if (forwarded != null) {
          sink.setAddress(forwarded);
          return;
        }
      }

      // try X-Forwarded-For
      forwarded = firstHeaderValue(getter.getHttpRequestHeader(request, "x-forwarded-for"));
      if (forwarded != null) {
        sink.setAddress(extractClientIpFromForwardedForHeader(forwarded));
      }

      // TODO: client.port will be implemented in a future PR
    }
  }
}
