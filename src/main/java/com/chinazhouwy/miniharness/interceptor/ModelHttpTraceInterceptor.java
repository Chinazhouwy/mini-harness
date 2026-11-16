package com.chinazhouwy.miniharness.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import okhttp3.RequestBody;
import okio.Buffer;


public class ModelHttpTraceInterceptor implements Interceptor {

    private static final Logger log =
            LoggerFactory.getLogger(ModelHttpTraceInterceptor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        log.info("[HTTP 请求] method={} path={}",
                request.method(),
                request.url().encodedPath());

        // 1. 复制并打印请求体。
        // one-shot / duplex 请求体不适合提前再次写入，遇到时跳过。
        RequestBody requestBody = request.body();

        if (requestBody != null
                && !requestBody.isOneShot()
                && !requestBody.isDuplex()) {

            Buffer requestBuffer = new Buffer();
            requestBody.writeTo(requestBuffer);

            String body = requestBuffer.readUtf8();
            try {
                log.info("[HTTP 请求体]\n{}", JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(JSON.readTree(body)));
            } catch (IOException exception) {
                // 非 JSON 请求保留原文，不能因为日志格式化失败影响模型请求。
                log.info("[HTTP 请求体] {}", body);
            }
        }

        // 2. 发送请求，拿到响应头和可供后续读取的响应体。
        Response response = chain.proceed(request);

        log.info("[HTTP 响应] code={} message={} path={}",
                response.code(),
                response.message(),
                response.request().url().encodedPath());

        ResponseBody responseBody = response.body();
        if (responseBody != null) {
            String body = response.peekBody(Long.MAX_VALUE).string();
            try {
                log.info("[HTTP 响应体]\n{}", JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(JSON.readTree(body)));
            } catch (IOException exception) {
                log.info("[HTTP 响应体] {}", body);
            }
        }

        return response;
    }
}
