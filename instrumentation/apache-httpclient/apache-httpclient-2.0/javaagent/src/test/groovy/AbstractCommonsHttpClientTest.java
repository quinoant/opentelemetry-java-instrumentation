/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

 import io.opentelemetry.api.common.AttributeKey;
 import io.opentelemetry.instrumentation.test.AgentTestTrait;
 import io.opentelemetry.instrumentation.test.base.HttpClientTest;
 import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

import java.util.Map;

import org.apache.commons.httpclient.HttpClient;
 import org.apache.commons.httpclient.HttpConnectionManager;
 import org.apache.commons.httpclient.HttpMethod;
 import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
 import org.apache.commons.httpclient.methods.DeleteMethod;
 import org.apache.commons.httpclient.methods.GetMethod;
 import org.apache.commons.httpclient.methods.HeadMethod;
 import org.apache.commons.httpclient.methods.OptionsMethod;
 import org.apache.commons.httpclient.methods.PostMethod;
 import org.apache.commons.httpclient.methods.PutMethod;
 import org.apache.commons.httpclient.methods.TraceMethod;
 import spock.lang.Shared;
 

 abstract class AbstractCommonsHttpClientTest extends HttpClientTest<HttpMethod> implements AgentTestTrait {
    private static final HttpConnectionManager connectionManager = new MultiThreadedHttpConnectionManager();
    private final HttpClient client = buildClient(false);
    private final HttpClient clientWithReadTimeout = buildClient(true);
  
    private static HttpClient buildClient(boolean readTimeout) {
      HttpClient client = new HttpClient(connectionManager);
      client.setConnectionTimeout(CONNECT_TIMEOUT_MS);
      if (readTimeout) {
        client.setTimeout(READ_TIMEOUT_MS);
      }
      return client;
    }
  
    private HttpClient getClient(URI uri) {
      if (uri.toString().contains("/read-timeout")) {
        return clientWithReadTimeout;
      }
      return client;
    }
  
    @Override
    public HttpMethod buildRequest(String method, URI uri, Map<String, String> headers) {
      def request;
      switch (method) {
        case "GET":
          request = new GetMethod(uri.toString());
          break;
        case "PUT":
          request = new PutMethod(uri.toString());
          break;
        case "POST":
          request = new PostMethod(uri.toString());
          break;
        case "HEAD":
          request = new HeadMethod(uri.toString());
          break;
        case "DELETE":
          request = new DeleteMethod(uri.toString());
          break;
        case "OPTIONS":
          request = new OptionsMethod(uri.toString());
          break;
        case "TRACE":
          request = new TraceMethod(uri.toString());
          break;
        default:
          throw new IllegalStateException("Unsupported method: " + method);
      }
      headers.forEach(request::setRequestHeader);
      return request;
    }
  
    @Override
    boolean testCircularRedirects() {
      return false;
    }
  
    @Override
    boolean testReusedRequest() {
      // apache commons throws an exception if the request is reused without being recycled first
      // at which point this test is not useful (and requires re-populating uri)
      return false;
    }
  
    @Override
    boolean testCallback() {
      return false;
    }

    @Override
    Set<AttributeKey<?>> httpAttributes(URI uri) {
        //gotta make the array a different way
      Set<AttributeKey<?>> extra = new ArrayList<>();
      extra.add(SemanticAttributes.HTTP_SCHEME);
      extra.add(SemanticAttributes.HTTP_TARGET);
      return super.httpAttributes(uri) + extra;
    }
  }