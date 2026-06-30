package com.iacross.flowpilot.channel.spi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Raw HTTP request wrapper passed to ChannelAdapter for webhook verification and parsing.
 */
public record InboundRequest(
    Map<String, String> headers,
    String rawBody
) {
    public static InboundRequest from(HttpServletRequest request, String rawBody) {
        var headers = new java.util.LinkedHashMap<String, String>();
        var names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name.toLowerCase(), request.getHeader(name));
            }
        }
        return new InboundRequest(headers, rawBody);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase());
    }
}
