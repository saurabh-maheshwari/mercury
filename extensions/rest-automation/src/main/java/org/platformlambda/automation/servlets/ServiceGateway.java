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

package org.platformlambda.automation.servlets;

import org.platformlambda.automation.config.RoutingEntry;
import org.platformlambda.automation.models.AssignedRoute;
import org.platformlambda.automation.models.AsyncContextHolder;
import org.platformlambda.automation.models.CorsInfo;
import org.platformlambda.automation.models.HeaderInfo;
import org.platformlambda.core.annotations.EventInterceptor;
import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.serializers.SimpleMapper;
import org.platformlambda.core.serializers.SimpleXmlParser;
import org.platformlambda.core.serializers.SimpleXmlWriter;
import org.platformlambda.core.system.*;
import org.platformlambda.core.util.AppConfigReader;
import org.platformlambda.core.util.ConfigReader;
import org.platformlambda.core.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

@WebServlet(urlPatterns="/api/*", asyncSupported=true)
@MultipartConfig
public class ServiceGateway extends HttpServlet {
    private static final Logger log = LoggerFactory.getLogger(ServiceGateway.class);

    private static final SimpleXmlParser xmlReader = new SimpleXmlParser();
    private static final SimpleXmlWriter xmlWriter = new SimpleXmlWriter();

    private static final String UTF_8 = "utf-8";
    private static final String BASE_PATH = "/api";
    private static final String URL = "url";
    private static final String IP = "ip";
    private static final String PARAMETERS = "parameters";
    private static final String PATH = "path";
    private static final String QUERY = "query";
    private static final String HEADERS = "headers";
    private static final String COOKIE = "cookie";
    private static final String COOKIES = "cookies";
    private static final String ASYNC_HTTP = "async.http";
    private static final String METHOD = "method";
    private static final String OPTIONS = "OPTIONS";
    private static final String PUT = "PUT";
    private static final String POST = "POST";
    private static final String BODY = "body";
    private static final String STREAM = "stream";
    private static final String STREAM_PREFIX = "stream.";
    private static final String TIMEOUT = "timeout";
    private static final String FILE_NAME = "filename";
    private static final String ACCEPT = "accept";
    private static final String CONTENT_TYPE = "content-type";
    private static final String CONTENT_LENGTH = "content-length";
    private static final String HTML_START = "<!DOCTYPE html>\n<html>\n<body>\n<pre>\n";
    private static final String HTML_END = "\n</pre>\n<body>\n</html>";
    private static final String RESULT = "result";
    private static final String ACCEPT_ANY = "*/*";

    private static final int BUFFER_SIZE = 2048;

    private static final ConcurrentMap<String, AsyncContextHolder> contexts = new ConcurrentHashMap<>();

    private static Boolean ready;

    public ServiceGateway() {
        if (ready == null) {
            ConfigReader config = getConfig();
            try {
                if (config == null) {
                    throw new IOException("REST automation configuration file is not available");
                }
                RoutingEntry routing = RoutingEntry.getInstance();
                routing.load(config.getMap());
                // start service response handler
                ServerPersonality.getInstance().setType(ServerPersonality.Type.REST);
                Platform platform = Platform.getInstance();
                platform.registerPrivate(ASYNC_HTTP, new ServiceResponseHandler(), 100);
                /*
                 * When AsyncContext timeout, the HttpServletResponse object is already closed.
                 * Therefore, we use a custom timeout handler so we can control the timeout experience.
                 */
                AsyncTimeoutHandler timeoutHandler = new AsyncTimeoutHandler();
                timeoutHandler.start();
                ready = true;
            } catch (Exception e) {
                // capture all exceptions to avoid blocking
                log.error("Unable to load REST automation configuration - {}", e.getMessage());
                ready = false;
            }
        }
    }

    private ConfigReader getConfig() {
        AppConfigReader reader = AppConfigReader.getInstance();
        List<String> paths = Utility.getInstance().split(reader.getProperty("rest.automation.yaml",
                "file:/tmp/config/rest.yaml, classpath:/rest.yaml"), ", ");

        for (String p: paths) {
            ConfigReader config = new ConfigReader();
            try {
                config.load(p);
                log.info("Loading config from {}", p);
                return config;
            } catch (IOException e) {
                log.warn("Skipping {} - {}", p, e.getMessage());
            }
        }
        return null;
    }

    private String getHeaderCase(String header) {
        StringBuilder sb = new StringBuilder();
        List<String> parts = Utility.getInstance().split(header, "-");
        for (String p: parts) {
            sb.append(p.substring(0, 1).toUpperCase());
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
            sb.append('-');
        }
        return sb.length() == 0? null : sb.substring(0, sb.length()-1);
    }

	@Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ready) {
            response.sendError(404, "Unable to serve requests because REST endpoints are not configured");
        } else {
            String path = request.getPathInfo();
            if (path == null || path.equals("/")) {
                response.sendError(404, "Resource not found");
            } else {
                String url = BASE_PATH + path;
                RoutingEntry re = RoutingEntry.getInstance();
                AssignedRoute route = re.getRouteInfo(url);
                if (route == null) {
                    response.sendError(404, "Resource not found");
                } else {
                    routeRequest(url, route, request, response);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void routeRequest(String url, AssignedRoute route, HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding(UTF_8);
        Utility util = Utility.getInstance();
        // handle OPTIONS
        if (OPTIONS.equals(request.getMethod())) {
            if (route.info.corsId == null) {
                response.sendError(405, "Method not allowed");
            } else {
                CorsInfo corsInfo = RoutingEntry.getInstance().getCorsInfo(route.info.corsId);
                if (corsInfo != null && !corsInfo.options.isEmpty()) {
                    for (String ch : corsInfo.options.keySet()) {
                        String prettyHeader = getHeaderCase(ch);
                        if (prettyHeader != null) {
                            response.setHeader(prettyHeader, corsInfo.headers.get(ch));
                        }
                    }
                    response.setStatus(204);
                } else {
                    response.sendError(405, "Method not allowed");
                }
            }
            return;
        }
        // validate HTTP method
        if (!route.info.methods.contains(request.getMethod())) {
            response.sendError(405, "Method not allowed");
            return;
        }
        // check if target service is available
        PostOffice po = PostOffice.getInstance();
        if (route.info.authService == null) {
            if (!po.exists(route.info.service)) {
                response.sendError(503, "Service "+route.info.service+" not reachable");
                return;
            }
        } else {
            if (!po.exists(route.info.service, route.info.authService)) {
                response.sendError(503, "Service "+route.info.service+" or "+route.info.authService+" not reachable");
                return;
            }
        }
        Map<String, Object> dataset = new HashMap<>();
        dataset.put(URL, url);
        dataset.put(METHOD, request.getMethod());
        dataset.put(TIMEOUT, route.info.timeoutSeconds);
        Map<String, Object> parameters = new HashMap<>();
        dataset.put(PARAMETERS, parameters);
        if (!route.arguments.isEmpty()) {
            parameters.put(PATH, route.arguments);
        }
        Map<String, Object> queryParams = new HashMap<>();
        Enumeration<String> pNames = request.getParameterNames();
        while (pNames.hasMoreElements()) {
            String key = pNames.nextElement();
            String[] values = request.getParameterValues(key);
            if (values.length == 1) {
                queryParams.put(key, values[0]);
            }
            if (values.length > 1) {
                queryParams.put(key, Arrays.asList(values));
            }
        }
        if (!queryParams.isEmpty()) {
            parameters.put(QUERY, queryParams);
        }
        boolean hasCookies = false;
        Map<String, Object> headers = new HashMap<>();
        Enumeration<String> hNames = request.getHeaderNames();
        while (hNames.hasMoreElements()) {
            String key = hNames.nextElement();
            /*
             * Single-value HTTP header is assumed.
             * Header key is assumed to be lower-case to ensure case-insensitivity.
             */
            String value = request.getHeader(key);
            String lk = key.toLowerCase();
            if (lk.equals(COOKIE)) {
                // cookie is not kept in the headers
                hasCookies = true;
            } else {
                headers.put(lk, value);
            }
        }
        // load cookies
        if (hasCookies) {
            // save cookies in dataset
            Map<String, String> cookieMap = new HashMap<>();
            Cookie[] cookies = request.getCookies();
            for (Cookie c: cookies) {
                cookieMap.put(c.getName(), c.getValue());
            }
            dataset.put(COOKIES, cookieMap);
        }
        RoutingEntry re = RoutingEntry.getInstance();
        if (route.info.transformId != null) {
            HeaderInfo headerInfo = re.getHeaderInfo(route.info.transformId);
            if (headerInfo.keepHeaders != null && !headerInfo.keepHeaders.isEmpty()) {
                // drop all headers except those to be kept
                Map<String, Object> toBeKept = new HashMap<>();
                for (String h: headers.keySet()) {
                    if (headerInfo.keepHeaders.contains(h)) {
                        toBeKept.put(h, headers.get(h));
                    }
                }
                headers = toBeKept;
            } else if (headerInfo.dropHeaders != null && !headerInfo.dropHeaders.isEmpty()) {
                // drop the headers according to "drop" list
                Map<String, Object> toBeKept = new HashMap<>();
                for (String h: headers.keySet()) {
                    if (!headerInfo.dropHeaders.contains(h)) {
                        toBeKept.put(h, headers.get(h));
                    }
                }
                headers = toBeKept;
            }
            if (headerInfo.additionalHeaders != null && !headerInfo.additionalHeaders.isEmpty()) {
                for (String h: headerInfo.additionalHeaders.keySet()) {
                    headers.put(h, headerInfo.additionalHeaders.get(h));
                }
            }
        }
        if (!headers.isEmpty()) {
            dataset.put(HEADERS, headers);
        }
        dataset.put(IP, request.getRemoteAddr());
        // authentication required?
        if (route.info.authService != null) {
            try {
                long authTimeout = route.info.timeoutSeconds * 1000;
                EventEnvelope authResponse = po.request(route.info.authService, authTimeout, dataset);
                if (!authResponse.hasError() && authResponse.getBody() instanceof Boolean) {
                    Boolean authOK = (Boolean) authResponse.getBody();
                    if (!authOK) {
                        response.sendError(401, "Unauthorized");
                        return;
                    }
                }
            } catch (IOException e) {
                log.error("Missing authentication service - {}", e.getMessage());
                response.sendError(400, e.getMessage());
                return;
            } catch (TimeoutException e) {
                log.error("Authentication timeout - {}", e.getMessage());
                response.sendError(408, e.getMessage());
                return;
            } catch (AppException e) {
                log.error("Authentication exception, status={}, message={}", e.getStatus(), e.getMessage());
                response.sendError(e.getStatus(), e.getMessage());
                return;
            }
        }
        // load HTTP body
        String contentType = request.getContentType();
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        // ignore URL encoded content type because it has been already fetched as request/query parameters
        if (!contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED) && (request.getMethod().equals(POST) || request.getMethod().equals(PUT))) {
            // handle request body
            if (contentType.startsWith(MediaType.MULTIPART_FORM_DATA) && request.getMethod().equals(POST)) {
                // file upload
                String label = route.info.upload;
                try {
                    Part filePart = request.getPart(label);
                    if (filePart != null) {
                        String fileName = getFileName(filePart);
                        if (fileName != null) {
                            int len;
                            int total = 0;
                            byte[] buffer = new byte[BUFFER_SIZE];
                            InputStream in = filePart.getInputStream();
                            ObjectStreamIO stream = null;
                            ObjectStreamWriter out = null;
                            while ((len = in.read(buffer, 0, buffer.length)) != -1) {
                                if (out == null) {
                                    // Depending on input file size, the servlet will save the input stream
                                    // into memory or local file system, the system therefore defer creation
                                    // of the object stream until the buffered input stream is ready.
                                    stream = new ObjectStreamIO(route.info.timeoutSeconds);
                                    out = stream.getOutputStream();
                                }
                                total += len;
                                out.write(buffer, 0, len);
                            }
                            if (out != null) {
                                out.close();
                                dataset.put(STREAM, stream.getRoute());
                                dataset.put(FILE_NAME, fileName);
                                dataset.put(CONTENT_LENGTH, total);
                            }
                        }
                    }
                } catch (ServletException e) {
                    response.sendError(400, e.getMessage());
                    return;
                }

            } else if (contentType.startsWith(MediaType.APPLICATION_JSON)) {
                // request body is assumed to be JSON
                String json = util.getUTF(util.stream2bytes(request.getInputStream(), false)).trim();
                if (json.startsWith("{") && json.endsWith("}")) {
                    dataset.put(BODY, SimpleMapper.getInstance().getMapper().readValue(json, Map.class));
                } else if (json.startsWith("[") && json.endsWith("]")) {
                    dataset.put(BODY, SimpleMapper.getInstance().getMapper().readValue(json, List.class));
                } else {
                    response.sendError(400, "Invalid JSON input");
                    return;
                }

            } else if (contentType.startsWith(MediaType.APPLICATION_XML)) {
                // request body is assumed to be XML
                String text = util.getUTF(util.stream2bytes(request.getInputStream(), false));
                try {
                    Map<String, Object> map = xmlReader.parse(text);
                    dataset.put(BODY, map);
                } catch (Exception e) {
                    response.sendError(400, e.getMessage());
                    return;
                }

            } else {
                /*
                 * The input is not JSON or XML.
                 * Check if the content-length is larger than threshold.
                 */
                boolean sendAsBytes = false;
                int contentLen = request.getContentLength();
                if (contentLen > 0 && contentLen <= route.info.threshold) {
                    byte[] bytes = util.stream2bytes(request.getInputStream(), false);
                    dataset.put(BODY, bytes);
                    sendAsBytes = true;
                }
                // If content-length is undefined or larger than threshold, it will be sent as a stream.
                if (!sendAsBytes) {
                    int len;
                    int total = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    InputStream in = request.getInputStream();
                    ObjectStreamIO stream = null;
                    ObjectStreamWriter out = null;
                    while ((len = in.read(buffer, 0, buffer.length)) != -1) {
                        if (out == null) {
                            // defer creation of object stream because input stream may be empty
                            stream = new ObjectStreamIO(route.info.timeoutSeconds);
                            out = stream.getOutputStream();
                        }
                        total += len;
                        out.write(buffer, 0, len);
                    }
                    if (out != null) {
                        out.close();
                        dataset.put(STREAM, stream.getRoute());
                        dataset.put(CONTENT_LENGTH, total);
                    }
                }
            }
        }
        // generate unique requestId
        String requestId = Utility.getInstance().getUuid();
        // create HTTP async context
        AsyncContext context = request.startAsync(request, response);
        context.addListener(new AsyncHttpHandler(requestId));
        // save to context map
        AsyncContextHolder holder = new AsyncContextHolder(context, route.info.timeoutSeconds * 1000);
        holder.setUrl(url).setCorsId(route.info.corsId);
        String acceptContent = request.getHeader(ACCEPT);
        if (acceptContent != null) {
            holder.setAccept(acceptContent);
        }
        contexts.put(requestId, holder);
        // forward to HTTP request to the target service
        EventEnvelope event = new EventEnvelope();
        event.setTo(route.info.service).setBody(dataset)
                .setCorrelationId(requestId).setReplyTo(ASYNC_HTTP+"@"+Platform.getInstance().getOrigin());
        try {
            po.send(event);
        } catch (IOException e) {
            response.sendError(400, e.getMessage());
            context.complete();
        }
     }

    private String getFileName(final Part part) {
        for (String content : part.getHeader("content-disposition").split(";")) {
            if (content.trim().startsWith("filename")) {
                return content.substring(content.indexOf('=') + 1).trim().replace("\"", "");
            }
        }
        return null;
    }

    @EventInterceptor
    private class ServiceResponseHandler implements LambdaFunction {

        private long getReadTimeout(String timeoutOverride, long contextTimeout) {
            if (timeoutOverride == null) {
                return contextTimeout;
            }
            long timeout = Utility.getInstance().str2long(timeoutOverride) * 1000;
            if (timeout < 1) {
                return contextTimeout;
            }
            return timeout > contextTimeout? contextTimeout : timeout;
        }

        @Override
        public Object handleEvent(Map<String, String> headers, Object body, int instance) throws Exception {
            if (body instanceof EventEnvelope) {
                Utility util = Utility.getInstance();
                EventEnvelope event = (EventEnvelope) body;
                String requestId = event.getCorrelationId();
                if (requestId != null) {
                    AsyncContextHolder holder = contexts.get(requestId);
                    if (holder != null) {
                        holder.touch();
                        ServletResponse res = holder.context.getResponse();
                        if (res instanceof HttpServletResponse) {
                            HttpServletResponse response = (HttpServletResponse) res;
                            if (event.getStatus() != 200) {
                                response.setStatus(event.getStatus());
                            }
                            if (holder.corsId != null) {
                                CorsInfo corsInfo = RoutingEntry.getInstance().getCorsInfo(holder.corsId);
                                if (corsInfo != null && !corsInfo.headers.isEmpty()) {
                                    for (String ch: corsInfo.headers.keySet()) {
                                        String prettyHeader = getHeaderCase(ch);
                                        if (prettyHeader != null) {
                                            response.setHeader(prettyHeader, corsInfo.headers.get(ch));
                                        }
                                    }
                                }
                            }
                            String accept = holder.accept;
                            String timeoutOverride = null;
                            String streamId = null;
                            String contentType = null;
                            if (!event.getHeaders().isEmpty()) {
                                Map<String, String> resHeaders = event.getHeaders();
                                for (String h: resHeaders.keySet()) {
                                    String key = h.toLowerCase();
                                    String value = resHeaders.get(h);
                                    // "stream" and "timeout" are reserved as stream ID and read timeout in seconds
                                    if (key.equals(STREAM) && value.startsWith(STREAM_PREFIX) && value.contains("@")) {
                                        streamId = resHeaders.get(h);
                                    } else if (key.equals(TIMEOUT)) {
                                        timeoutOverride = resHeaders.get(h);
                                    } else if (key.equals(CONTENT_TYPE)) {
                                        contentType = value.toLowerCase();
                                        response.setContentType(contentType);
                                    } else {
                                        String prettyHeader = getHeaderCase(key);
                                        if (prettyHeader != null) {
                                            response.setHeader(prettyHeader, value);
                                        }
                                    }
                                }
                            }
                            // default content type is JSON
                            if (contentType == null) {
                                if (accept == null) {
                                    contentType = MediaType.APPLICATION_JSON;
                                    response.setContentType(MediaType.APPLICATION_JSON);
                                } else if (accept.contains(MediaType.TEXT_HTML)) {
                                    contentType = MediaType.TEXT_HTML;
                                    response.setContentType(MediaType.TEXT_HTML);
                                } else if (accept.contains(MediaType.APPLICATION_XML)) {
                                    contentType = MediaType.APPLICATION_XML;
                                    response.setContentType(MediaType.APPLICATION_XML);
                                } else if (accept.contains(MediaType.APPLICATION_JSON) || accept.contains(ACCEPT_ANY)) {
                                    contentType = MediaType.APPLICATION_JSON;
                                    response.setContentType(MediaType.APPLICATION_JSON);
                                } else {
                                    contentType = MediaType.TEXT_PLAIN;
                                    response.setContentType(MediaType.TEXT_PLAIN);
                                }
                            }
                            response.setCharacterEncoding(UTF_8);
                            // is this an exception?
                            int status = event.getStatus();
                            /*
                             * status range 100: used for HTTP protocol handshake
                             * status range 200: normal responses
                             * status range 300: redirection or unchanged content
                             * status ranges 400 and 500: HTTP exceptions
                             */
                            if (status < 400 && event.getHeaders().isEmpty() && event.getBody() instanceof String) {
                                String message = ((String) event.getBody()).trim();
                                // make sure it does not look like JSON or XML
                                if (!message.startsWith("{") && !message.startsWith("[") && !message.startsWith("<")) {
                                    response.sendError(status, (String) event.getBody());
                                    holder.context.complete();
                                    return null;
                                }
                            }
                            // output is a stream?
                            Object resBody = event.getBody();
                            if (resBody == null && streamId != null) {
                                ObjectStreamIO io = new ObjectStreamIO(streamId);
                                ObjectStreamReader in = io.getInputStream(getReadTimeout(timeoutOverride, holder.timeout));
                                try {
                                    OutputStream out = response.getOutputStream();
                                    for (Object block : in) {
                                        // update last access time
                                        holder.touch();
                                        /*
                                         * only bytes or text are supported when using output stream
                                         * e.g. for downloading a large file
                                         */
                                        if (block instanceof byte[]) {
                                            out.write((byte[]) block);
                                        }
                                        if (block instanceof String) {
                                            out.write(util.getUTF((String) block));
                                        }
                                    }
                                    in.close();
                                } catch (IOException | RuntimeException e) {
                                    log.warn("{} output stream {} interrupted - {}", holder.url, streamId, e.getMessage());
                                    if (e.getMessage().contains("timeout")) {
                                        response.sendError(408, e.getMessage());
                                    } else {
                                        response.sendError(500, e.getMessage());
                                    }
                                }
                            // regular output
                            } else if (resBody instanceof Map) {
                                if (contentType.startsWith(MediaType.TEXT_HTML)) {
                                    byte[] payload = SimpleMapper.getInstance().getMapper().writeValueAsBytes(resBody);
                                    OutputStream out = response.getOutputStream();
                                    out.write(util.getUTF(HTML_START));
                                    out.write(payload);
                                    out.write(util.getUTF(HTML_END));
                                } else if (contentType.startsWith(MediaType.APPLICATION_XML)) {
                                    response.getOutputStream().write(util.getUTF(xmlWriter.write(resBody)));
                                } else {
                                    byte[] payload = SimpleMapper.getInstance().getMapper().writeValueAsBytes(resBody);
                                    response.getOutputStream().write(payload);
                                }
                            } else if (resBody instanceof List) {
                                if (contentType.startsWith(MediaType.TEXT_HTML)) {
                                    byte[] payload = SimpleMapper.getInstance().getMapper().writeValueAsBytes(resBody);
                                    OutputStream out = response.getOutputStream();
                                    out.write(util.getUTF(HTML_START));
                                    out.write(payload);
                                    out.write(util.getUTF(HTML_END));
                                } else if (contentType.startsWith(MediaType.APPLICATION_XML)) {
                                    // xml must be delivered as a map so we use a wrapper here
                                    Map<String, Object> map = new HashMap<>();
                                    map.put(RESULT, resBody);
                                    response.getOutputStream().write(util.getUTF(xmlWriter.write(map)));
                                } else {
                                    byte[] payload = SimpleMapper.getInstance().getMapper().writeValueAsBytes(resBody);
                                    response.getOutputStream().write(payload);
                                }
                            } else if (resBody instanceof String) {
                                String text = (String) resBody;
                                response.getOutputStream().write(util.getUTF(text));
                            } else if (resBody instanceof byte[]) {
                                byte[] binary = (byte[]) resBody;
                                response.getOutputStream().write(binary);
                            } else if (resBody != null) {
                                response.getOutputStream().write(util.getUTF(resBody.toString()));
                            }
                        }
                        holder.context.complete();
                    }
                }
            }
            return null;
        }
    }

    private class AsyncHttpHandler implements AsyncListener {

        private String id;

        public AsyncHttpHandler(String id) {
            this.id = id;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            if (contexts.containsKey(id)) {
                contexts.remove(id);
                log.debug("Async HTTP Context {} completed, remaining {}", id, contexts.size());
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // this should not occur as we use our own async timeout handler
            contexts.remove(id);
        }

        @Override
        public void onError(AsyncEvent event) {
            if (contexts.containsKey(id)) {
                contexts.remove(id);
                log.warn("Async HTTP Context {} exception {}", id, event.getThrowable().getMessage());
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // no-op
        }
    }

    private class AsyncTimeoutHandler extends Thread {

        private boolean normal = true;

        public AsyncTimeoutHandler() {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        }

        @Override
        public void run() {
            log.info("Async HTTP timeout handler started");
            while (normal) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // ok to ignore
                }
                // check async context timeout
                if (!contexts.isEmpty()) {
                    List<String> contextList = new ArrayList<>(contexts.keySet());
                    long now = System.currentTimeMillis();
                    for (String id : contextList) {
                        AsyncContextHolder holder = contexts.get(id);
                        long t1 = holder.lastAccess;
                        if (now - t1 > holder.timeout) {
                            ServletResponse res = holder.context.getResponse();
                            if (res instanceof HttpServletResponse) {
                                contexts.remove(id);
                                log.warn("Async HTTP Context {} timeout for {} ms", id, now - t1);
                                HttpServletResponse response = (HttpServletResponse) res;
                                try {
                                    response.sendError(408, "Timeout for " + (holder.timeout / 1000) + " seconds");
                                    holder.context.complete();
                                } catch (IOException e) {
                                    log.error("Unable to send timeout exception to async context {}", id);
                                }
                            }
                        }
                    }
                }
            }
            log.info("Async HTTP timeout handler stopped");
        }

        private void shutdown() {
            normal = false;
        }
    }
}
