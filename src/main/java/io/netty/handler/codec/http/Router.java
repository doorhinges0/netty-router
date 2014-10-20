package io.netty.handler.codec.http;

import jauter.Routed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;

@ChannelHandler.Sharable
public class Router extends SimpleChannelInboundHandler<HttpRequest> {
  public static final String ROUTER_HANDLER_NAME = Router.class.getName() + "_ROUTER_HANDLER";
  public static final String ROUTED_HANDLER_NAME = Router.class.getName() + "_ROUTED_HANDLER";

  public static final String PATH_PARAM_HEADER_PREFIX  = Router.class.getName() + "_PATH_";
  public static final String QUERY_PARAM_HEADER_PREFIX = Router.class.getName() + "_QUERY_";

  private final Map<HttpMethod, jauter.Router<Object>> routers =
      new HashMap<HttpMethod, jauter.Router<Object>>();

  private final ChannelInboundHandler handler404;

  //----------------------------------------------------------------------------

  public Router() {
    this(new DefaultHandler404());
  }

  public Router(ChannelInboundHandler handler404) {
    this.handler404 = handler404;
  }

  /**
   * Should be used to add the router to pipeline:
   * channel.pipeline().addLast(router.name(), router)
   */
  public String name() {
    return ROUTER_HANDLER_NAME;
  }

  //----------------------------------------------------------------------------

  public Router pattern(HttpMethod method, String path, ChannelInboundHandler handler) {
    return _pattern(method, path, handler);
  }

  public Router pattern(HttpMethod method, String path, Class<? extends ChannelInboundHandler> handlerClass) {
    return _pattern(method, path, handlerClass);
  }

  private Router _pattern(HttpMethod method, String path, Object target) {
    jauter.Router<Object> jr = routers.get(method);
    if (jr == null) {
      jr = new jauter.Router<Object>();
      routers.put(method, jr);
    }
    jr.pattern(path, target);
    return this;
  }

  //----------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpRequest req) throws InstantiationException, IllegalAccessException {
    if (HttpHeaders.is100ContinueExpected(req)) {
      ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
      return;
    }

    HttpMethod            method  = req.getMethod();
    jauter.Router<Object> jr      = routers.get(method);
    ChannelInboundHandler handler = handler404;

    // Create handler
    if (jr != null) {
      // Params will be added to headers
      HttpHeaders headers = req.headers();

      String             uri    = req.getUri();
      QueryStringDecoder qsd    = new QueryStringDecoder(uri);
      String             path   = qsd.path();
      Routed<Object>     routed = jr.route(path);

      if (routed != null) {
        Object target = routed.target();
        if (target instanceof ChannelInboundHandler) {
          handler = (ChannelInboundHandler) target;
        } else {
          Class<? extends ChannelInboundHandler> klass = (Class<? extends ChannelInboundHandler>) target;
          handler = klass.newInstance();
        }

        for (Map.Entry<String, String> entry : routed.params().entrySet()) {
          String key   = entry.getKey();
          String value = entry.getValue();
          headers.add(PATH_PARAM_HEADER_PREFIX + key, value);
        }
      }

      for (Map.Entry<String, List<String>> entry : qsd.parameters().entrySet()) {
        String       key    = entry.getKey();
        List<String> values = entry.getValue();
        headers.add(QUERY_PARAM_HEADER_PREFIX + key, values);
      }
    }

    // The handler may have been added (keep alive)
    ChannelPipeline pipeline     = ctx.pipeline();
    ChannelHandler  addedHandler = pipeline.get(ROUTED_HANDLER_NAME);
    if (handler != addedHandler) {
      if (addedHandler != null) pipeline.remove(addedHandler);
      pipeline.addAfter(ROUTER_HANDLER_NAME, ROUTED_HANDLER_NAME, handler);
    }

    // Pass request to the routed handler
    ctx.fireChannelRead(ReferenceCountUtil.retain(req));
  }

  //----------------------------------------------------------------------------
  // Utilities to extract params from headers.

  public static String param(HttpRequest req, String name) {
    HttpHeaders headers = req.headers();

    String pathKey   = PATH_PARAM_HEADER_PREFIX + name;
    String pathValue = headers.get(pathKey);
    if (pathValue != null) return pathValue;

    String queryKey = QUERY_PARAM_HEADER_PREFIX + name;
    String queryValue = headers.get(queryKey);
    if (queryValue != null) return queryValue;

    return null;
  }

  public static List<String> params(HttpRequest req, String name) {
    HttpHeaders headers = req.headers();

    String       pathKey    = PATH_PARAM_HEADER_PREFIX + name;
    List<String> pathValues = headers.getAll(pathKey);
    if (pathValues != null) return pathValues;

    String       queryKey    = QUERY_PARAM_HEADER_PREFIX + name;
    List<String> queryValues = headers.getAll(queryKey);
    if (queryValues != null) return queryValues;

    return null;
  }

  public static Map<String, String> pathParams(HttpRequest req) {
    Map<String, String> ret = new HashMap<String, String>();
    for (Entry<String, String> entry : req.headers().entries()) {
      String key   = entry.getKey();
      String value = entry.getValue();
      if (key.startsWith(PATH_PARAM_HEADER_PREFIX)) {
        String withoutPrefix = key.substring(PATH_PARAM_HEADER_PREFIX.length());
        ret.put(withoutPrefix, value);
      }
    }
    return ret;
  }

  public static Map<String, List<String>> queryParams(HttpRequest req) {
    Map<String, List<String>> ret = new HashMap<String, List<String>>();
    for (Entry<String, String> entry : req.headers().entries()) {
      String key   = entry.getKey();
      String value = entry.getValue();
      if (key.startsWith(QUERY_PARAM_HEADER_PREFIX)) {
        String       withoutPrefix = key.substring(QUERY_PARAM_HEADER_PREFIX.length());
        List<String> values        = ret.get(withoutPrefix);
        if (values == null) {
          values = new ArrayList<String>();
          ret.put(withoutPrefix, values);
        }
        values.add(value);
      }
    }
    return ret;
  }

  //----------------------------------------------------------------------------
  // Utilities to write.

  public static ChannelFuture keepAliveWriteAndFlush(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
    if (!HttpHeaders.isKeepAlive(req)) {
      return ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    } else {
      res.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
      return ctx.writeAndFlush(res);
    }
  }

  public static ChannelFuture keepAliveWriteAndFlush(Channel ch, HttpRequest req, HttpResponse res) {
    if (!HttpHeaders.isKeepAlive(req)) {
      return ch.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    } else {
      res.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
      return ch.writeAndFlush(res);
    }
  }
}
